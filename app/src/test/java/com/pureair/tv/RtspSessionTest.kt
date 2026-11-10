package com.pureair.tv

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.PropertyListParser
import com.pureair.tv.core.crypto.AirPlayCredentials
import com.pureair.tv.core.crypto.StreamDecryptor
import com.pureair.tv.core.rtp.AudioStreamFormat
import com.pureair.tv.core.rtsp.RtspRequest
import com.pureair.tv.core.rtsp.RtspSession
import com.pureair.tv.core.rtsp.RtspSessionListener
import com.pureair.tv.core.rtsp.RtspServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class RtspSessionTest {
    @Test
    fun optionalRequestErrorsKeepConnectionOpenButInvalidPairingClosesIt() {
        val loopback = InetAddress.getLoopbackAddress()
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val receiver = RtspServer(port)
        try {
            receiver.start(scope)
            Socket(loopback, port).use { client ->
                client.soTimeout = 2000
                val reader = client.getInputStream().bufferedReader()
                fun send(method: String, path: String): String {
                    client.getOutputStream().write(
                        "$method $path RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray()
                    )
                    val status = reader.readLine()
                    assertNotNull("非致命响应后必须保留控制连接", status)
                    while (!reader.readLine().isNullOrEmpty()) { }
                    return status
                }
                assertEquals("RTSP/1.0 404 Not Found", send("POST", "/optional-probe"))
                assertEquals("RTSP/1.0 200 OK", send("OPTIONS", "*"))
                assertEquals("RTSP/1.0 501 Not Implemented", send("UNSUPPORTED", "*"))
                assertEquals("RTSP/1.0 200 OK", send("OPTIONS", "*"))
                assertEquals("RTSP/1.0 400 Bad Request", send("POST", "/pair-setup"))
                assertNull(reader.readLine())
            }
        } finally {
            receiver.stop()
            scope.cancel()
        }
    }

    @Test
    fun controlListenerIsReadyBeforeStartReturnsAndCanRestart() {
        val loopback = InetAddress.getLoopbackAddress()
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val receiver = RtspServer(port)
        try {
            repeat(2) {
                receiver.start(scope)
                Socket(loopback, port).use { client ->
                    client.soTimeout = 2000
                    client.getOutputStream().write("OPTIONS * RTSP/1.0\r\nCSeq: 9\r\n\r\n".toByteArray())
                    val reader = client.getInputStream().bufferedReader()
                    assertEquals("RTSP/1.0 200 OK", reader.readLine())
                    val headers = mutableListOf<String>()
                    while (true) {
                        val header = reader.readLine()
                        if (header.isNullOrEmpty()) break
                        headers.add(header)
                    }
                    assertTrue(headers.contains("CSeq: 9"))
                }
                assertTrue(receiver.isRunning)
                receiver.stop()
            }
            ServerSocket(port).use {
                assertThrows(java.net.BindException::class.java) { receiver.start(scope) }
                assertFalse(receiver.isRunning)
            }
        } finally {
            receiver.stop()
            scope.cancel()
        }
    }

    @Test
    fun authenticatedSetupAcceptsRecordBeforeAndAfterStreamSetup() {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            Socket(loopback, server.localPort).use { _ ->
                server.accept().use { connection ->
                    val video = ServerSocket(0, 1, loopback)
                    val audio = DatagramSocket(0, loopback)
                    val control = DatagramSocket(0, loopback)
                    var videoSetups = 0
                    var audioSetups = 0
                    var started = 0
                    var stopped = 0
                    var audioStopped = 0
                    var videoStopped = 0
                    var audioFlushed = 0
                    val negotiatedFormats = mutableListOf<AudioStreamFormat>()
                    val listener = object : RtspSessionListener {
                        override fun onSessionStarted(sessionId: String) = Unit
                        override fun onVideoSetup(sessionId: String, peer: InetAddress,
                                                  decryptor: StreamDecryptor): Int {
                            videoSetups++
                            assertEquals(loopback, peer)
                            return video.localPort
                        }
                        override fun onAudioSetup(sessionId: String, peer: InetAddress, key: ByteArray,
                                                  iv: ByteArray, format: AudioStreamFormat,
                                                  controlPort: Int): Pair<Int, Int> {
                            audioSetups++
                            negotiatedFormats.add(format)
                            assertEquals(16, key.size)
                            return audio.localPort to control.localPort
                        }
                        override fun onAudioStopped(sessionId: String) { audioStopped++ }
                        override fun onVideoStopped(sessionId: String) { videoStopped++ }
                        override fun onAudioFlush(sessionId: String, nextSequence: Int?) { audioFlushed++ }
                        override fun onStreamingStarted(sessionId: String, videoPort: Int, audioPort: Int) { started++ }
                        override fun onStreamingStopped(sessionId: String) { stopped++ }
                        override fun onSessionClosed(sessionId: String) = Unit
                    }
                    val session = RtspSession(connection, listener)
                    fun send(method: String, path: String, body: ByteArray = ByteArray(0)) =
                        session.processRequest(RtspRequest(method, path, "RTSP/1.0", mapOf("cseq" to "7"), body))
                    fun plist(dict: NSDictionary) = BinaryPropertyListWriter.writeToArray(dict)
                    try {
                        val recordUri = "rtsp://fe80::b12d:2dce:8f0:9049/254637430531792999"
                        assertEquals(455, send("RECORD", recordUri).statusCode)
                        assertEquals(455, send("SETUP", "/stream").statusCode)
                        val mode = plist(NSDictionary().apply { put("audioMode", "default") })
                        assertEquals(64, mode.size)
                        assertEquals(455, send("POST", "/audioMode", mode).statusCode)
                        val info = send("GET", "/info")
                        assertEquals("7", info.headers["CSeq"])
                        val parsedInfo = PropertyListParser.parse(info.body) as NSDictionary
                        assertArrayEquals(AirPlayCredentials.publicKey,
                            (parsedInfo.objectForKey("pk") as NSData).bytes())
                        val sender = PairingClient()
                        val setupKey = send("POST", "/pair-setup", ByteArray(32)).body!!
                        val phase1 = send("POST", "/pair-verify", sender.request)
                        assertEquals(0, videoSetups)
                        assertEquals(0, started)
                        assertEquals(200, send("POST", "/pair-verify",
                            sender.confirmation(phase1.body!!, setupKey)).statusCode)
                        // 模式通知无需依赖流是否已建立，但必须经过认证且具有合法 Plist。
                        assertEquals(200, send("POST", "/audioMode", mode).statusCode)
                        assertEquals(400, send("POST", "/audioMode", byteArrayOf(1)).statusCode)
                        assertEquals(400, send("POST", "/audioMode",
                            plist(NSDictionary().apply { put("audioMode", 1) })).statusCode)
                        assertEquals(200, send("POST", "/fp-setup", FairPlayFixtures.setup1).statusCode)
                        assertEquals(200, send("POST", "/fp-setup", FairPlayFixtures.setup2).statusCode)
                        assertEquals(455, send("RECORD", recordUri).statusCode)
                        val keys = NSDictionary().apply {
                            put("ekey", FairPlayFixtures.encryptedKey)
                            put("eiv", ByteArray(16))
                            put("timingPort", 0)
                        }
                        val base = send("SETUP", "/stream", plist(keys))
                        assertEquals(200, base.statusCode)
                        assertEquals(0, started)
                        // 复现真机顺序：会话 SETUP → RECORD → 音视频流 SETUP。
                        repeat(2) { assertEquals(200, send("RECORD", recordUri).statusCode) }
                        assertEquals(0, videoSetups)
                        assertEquals(0, audioSetups)
                        assertEquals(0, started)
                        val ports = PropertyListParser.parse(base.body) as NSDictionary
                        val eventPort = (ports.objectForKey("eventPort") as NSNumber).intValue()
                        val timingPort = (ports.objectForKey("timingPort") as NSNumber).intValue()
                        Socket(loopback, eventPort).use { event ->
                            event.soTimeout = 2000
                            event.getOutputStream().write("POST /feedback HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            assertEquals("HTTP/1.1 200 OK", event.getInputStream().bufferedReader().readLine())
                        }
                        DatagramSocket(0, loopback).use { clock ->
                            clock.soTimeout = 2000
                            val request = ByteArray(32).apply { this[0] = 0x80.toByte(); this[1] = 0xd2.toByte() }
                            clock.send(DatagramPacket(request, 32, loopback, timingPort))
                            val reply = DatagramPacket(ByteArray(32), 32)
                            clock.receive(reply)
                            assertEquals(0xd3, reply.data[1].toInt() and 255)
                        }
                        val stream = NSDictionary().apply {
                            put("type", 110)
                            put("streamConnectionID", -3907568444900622110L)
                        }
                        val videoSetup = send("SETUP", "/stream", plist(NSDictionary().apply {
                            put("streams", NSArray(1).apply { setValue(0, stream) })
                        }))
                        assertEquals(200, videoSetup.statusCode)
                        val videoResult = PropertyListParser.parse(videoSetup.body) as NSDictionary
                        val videoStream = (videoResult.objectForKey("streams") as NSArray).array[0] as NSDictionary
                        assertEquals(video.localPort, (videoStream.objectForKey("dataPort") as NSNumber).intValue())
                        assertEquals(1, videoSetups)
                        assertEquals(1, started)
                        val audioSetup = send("SETUP", "/stream", plist(NSDictionary().apply {
                            put("streams", NSArray(1).apply { setValue(0, NSDictionary().apply {
                                put("type", 96); put("ct", 8); put("spf", 480)
                            }) })
                        }))
                        assertEquals(200, audioSetup.statusCode)
                        assertEquals(1, audioSetups)
                        repeat(2) {
                            val notification = send("POST", "/audioMode", mode)
                            assertEquals(200, notification.statusCode)
                            assertTrue(notification.body!!.isEmpty())
                        }
                        assertEquals(1, videoSetups)
                        assertEquals(1, audioSetups)
                        assertEquals(0, stopped)
                        assertEquals(200, send("RECORD", "/stream").statusCode)
                        assertEquals(1, started)
                        // 相同的配置重试不得重建视频 CTR 或音频解码器。
                        assertEquals(200, send("SETUP", "/stream", plist(keys)).statusCode)
                        assertEquals(200, send("SETUP", "/stream", plist(NSDictionary().apply {
                            put("streams", NSArray(1).apply { setValue(0, stream) })
                        })).statusCode)
                        assertEquals(1, videoSetups)
                        val alacStream = NSDictionary().apply {
                            put("streams", NSArray(1).apply { setValue(0, NSDictionary().apply {
                                put("type", 96); put("ct", 2); put("spf", 352)
                                put("audioFormat", 0x100000L); put("controlPort", 65000)
                            }) })
                        }
                        assertEquals(200, send("SETUP", "/stream", plist(alacStream)).statusCode)
                        assertEquals(AudioStreamFormat(2, 48000, 2, 16, 352), negotiatedFormats.last())
                        assertEquals(1, audioStopped)
                        assertEquals(2, audioSetups)
                        assertEquals(200, send("FLUSH", "/stream").statusCode)
                        assertEquals(1, audioFlushed)
                        fun teardown(type: Int) = plist(NSDictionary().apply {
                            put("streams", NSArray(1).apply { setValue(0, NSDictionary().apply { put("type", type) }) })
                        })
                        assertEquals(200, send("TEARDOWN", "/stream", teardown(96)).statusCode)
                        assertEquals(2, audioStopped)
                        assertEquals(0, videoStopped)
                        assertEquals(0, stopped)
                        assertEquals(200, send("RECORD", "/stream").statusCode)
                        assertEquals(200, send("SETUP", "/stream", plist(alacStream)).statusCode)
                        assertEquals(3, audioSetups)
                        assertEquals(200, send("TEARDOWN", "/stream", teardown(110)).statusCode)
                        assertEquals(1, videoStopped)
                        assertEquals(0, stopped)
                        assertEquals(200, send("SETUP", "/stream", plist(NSDictionary().apply {
                            put("streams", NSArray(1).apply { setValue(0, stream) })
                        })).statusCode)
                        assertEquals(2, videoSetups)
                        assertEquals(404, send("POST", "/unknown").statusCode)
                    } finally {
                        session.close()
                        video.close()
                        audio.close()
                        control.close()
                    }
                }
            }
        }
    }
}
