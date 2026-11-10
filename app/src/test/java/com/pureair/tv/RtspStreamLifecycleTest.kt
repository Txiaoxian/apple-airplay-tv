package com.pureair.tv

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.pureair.tv.core.crypto.StreamDecryptor
import com.pureair.tv.core.rtp.AudioStreamFormat
import com.pureair.tv.core.rtsp.RtspResponse
import com.pureair.tv.core.rtsp.RtspSession
import com.pureair.tv.core.rtsp.RtspSessionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class RtspStreamLifecycleTest {
    @Test
    fun selectiveTeardownKeepsTheRealControlConnectionOpenUntilFullTeardown() {
        val peer = InetAddress.getLoopbackAddress()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ServerSocket(0, 1, peer).use { server ->
            Socket(peer, server.localPort).use { sender ->
                val receiver = server.accept()
                ServerSocket(0, 1, peer).use { video ->
                    DatagramSocket(0, peer).use { audio ->
                        DatagramSocket(0, peer).use { control ->
                            val videoStops = AtomicInteger()
                            val audioStops = AtomicInteger()
                            val sessionCloses = AtomicInteger()
                            val listener = object : RtspSessionListener {
                                override fun onSessionStarted(sessionId: String) = Unit
                                override fun onVideoSetup(sessionId: String, peer: InetAddress,
                                                          decryptor: StreamDecryptor) = video.localPort
                                override fun onAudioSetup(sessionId: String, peer: InetAddress,
                                    key: ByteArray, iv: ByteArray, format: AudioStreamFormat,
                                    controlPort: Int) = audio.localPort to control.localPort
                                override fun onAudioStopped(sessionId: String) { audioStops.incrementAndGet() }
                                override fun onVideoStopped(sessionId: String) { videoStops.incrementAndGet() }
                                override fun onAudioFlush(sessionId: String, nextSequence: Int?) = Unit
                                override fun onStreamingStarted(sessionId: String, videoPort: Int, audioPort: Int) = Unit
                                override fun onStreamingStopped(sessionId: String) = Unit
                                override fun onSessionClosed(sessionId: String) { sessionCloses.incrementAndGet() }
                            }
                            val session = RtspSession(receiver, listener)
                            scope.launch { session.handle() }
                            sender.soTimeout = 3000
                            val input = DataInputStream(sender.getInputStream())
                            var cseq = 0
                            fun line(): String {
                                val text = StringBuilder()
                                while (true) {
                                    val value = input.read()
                                    check(value >= 0) { "控制连接意外断开" }
                                    if (value == 10) return text.toString().trimEnd('\r')
                                    text.append(value.toChar())
                                    check(text.length < 16384)
                                }
                            }
                            fun send(method: String, body: ByteArray = ByteArray(0),
                                     path: String = "/stream"): RtspResponse {
                                val sequence = ++cseq
                                sender.getOutputStream().write(
                                    "$method $path RTSP/1.0\r\nCSeq: $sequence\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
                                val status = line().split(" ", limit = 3)
                                val result = RtspResponse(status[1].toInt(), status[2], status[0])
                                while (true) {
                                    val header = line()
                                    if (header.isEmpty()) break
                                    val parts = header.split(':', limit = 2)
                                    result.headers[parts[0]] = parts[1].trim()
                                }
                                result.body = ByteArray(result.headers["Content-Length"]!!.toInt())
                                input.readFully(result.body!!)
                                assertEquals(sequence.toString(), result.headers["CSeq"])
                                assertEquals(200, result.statusCode)
                                return result
                            }
                            fun plist(root: NSDictionary) = BinaryPropertyListWriter.writeToArray(root)
                            fun streams(type: Int, id: Long? = null): ByteArray =
                                plist(NSDictionary().apply {
                                    put("streams", NSArray(1).apply { setValue(0, NSDictionary().apply {
                                        put("type", type)
                                        if (id != null) put("streamConnectionID", id)
                                        if (type == 96) { put("ct", 2); put("audioFormat", 0x40000) }
                                    }) })
                                })
                            try {
                                val client = PairingClient()
                                val identity = send("POST", ByteArray(32), "/pair-setup").body!!
                                val phase1 = send("POST", client.request, "/pair-verify").body!!
                                send("POST", client.confirmation(phase1, identity), "/pair-verify")
                                send("POST", FairPlayFixtures.setup1, "/fp-setup")
                                send("POST", FairPlayFixtures.setup2, "/fp-setup")
                                send("SETUP", plist(NSDictionary().apply {
                                    put("ekey", FairPlayFixtures.encryptedKey)
                                    put("eiv", ByteArray(16)); put("timingPort", 0)
                                }))
                                send("SETUP", streams(110, 10L))
                                send("SETUP", streams(96))
                                send("TEARDOWN", streams(96))
                                assertEquals(1, audioStops.get())
                                assertEquals(0, videoStops.get())
                                send("RECORD")
                                send("SETUP", streams(96))
                                send("TEARDOWN", streams(110))
                                assertEquals(1, videoStops.get())
                                send("POST", path = "/feedback")
                                send("SETUP", streams(110, 11L))
                                assertEquals(0, sessionCloses.get())
                                send("TEARDOWN")
                                assertEquals(-1, input.read())
                                assertEquals(1, sessionCloses.get())
                            } finally {
                                session.close()
                                scope.cancel()
                            }
                        }
                    }
                }
            }
        }
    }
}
