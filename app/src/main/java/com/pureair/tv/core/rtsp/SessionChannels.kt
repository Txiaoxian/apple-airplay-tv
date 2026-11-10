package com.pureair.tv.core.rtsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** 每个已认证会话独占事件和 NTP 通道，返回的端口必须已经真实监听。 */
class SessionChannels(private val peer: InetAddress, private val peerTimingPort: Int) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val eventServer = ServerSocket(0)
    private val timingSocket = try {
        DatagramSocket(0)
    } catch (e: Exception) {
        eventServer.close()
        throw e
    }
    @Volatile private var eventClient: Socket? = null
    val eventPort: Int get() = eventServer.localPort
    val timingPort: Int get() = timingSocket.localPort

    /** 接受反向事件连接，并主动向发送端请求时钟，避免声明端口后无人应答。 */
    fun start() {
        scope.launch {
            try {
                while (isActive) {
                    val client = eventServer.accept()
                    if (client.inetAddress != peer) {
                        client.close()
                        continue
                    }
                    eventClient = client
                    client.use {
                        val input = it.getInputStream().buffered()
                        while (isActive) {
                            val request = RtspParser.parseRequest(input) ?: break
                            val response = RtspResponse(protocol = request.version)
                            response.setCSeq(request.getCSeq())
                            it.getOutputStream().write(response.toByteArray())
                        }
                    }
                }
            } catch (_: java.net.SocketException) {
                // 关闭会话时关闭 socket，使阻塞的网络读取及时退出。
            } catch (_: java.io.IOException) {
                close()
            }
        }
        scope.launch {
            val data = ByteArray(64)
            timingSocket.soTimeout = 1000
            var lastProbeMs = 0L
            try {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (peerTimingPort > 0 && now - lastProbeMs >= 3000) {
                        val request = ByteArray(32)
                        request[0] = 0x80.toByte()
                        request[1] = 0xd2.toByte()
                        request[3] = 7
                        putTimestamp(request, 24)
                        timingSocket.send(DatagramPacket(request, request.size, peer, peerTimingPort))
                        lastProbeMs = now
                    }
                    val packet = DatagramPacket(data, data.size)
                    try {
                        timingSocket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (packet.address != peer || packet.length != 32) continue
                    if (data[1].toInt() and 127 == 82) {
                        val reply = ByteArray(32)
                        reply[0] = 0x80.toByte()
                        reply[1] = 0xd3.toByte()
                        reply[3] = 7
                        data.copyInto(reply, 8, 24, 32)
                        putTimestamp(reply, 16)
                        putTimestamp(reply, 24)
                        timingSocket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                    }
                }
            } catch (_: java.net.SocketException) {
                // socket 关闭是正常的会话退出路径。
            } catch (_: java.io.IOException) {
                close()
            }
        }
    }

    /** NTP 通道按网络字节序写入自 1900 年起的定点时间。 */
    private fun putTimestamp(data: ByteArray, offset: Int) {
        val now = System.currentTimeMillis()
        val seconds = now / 1000 + 2_208_988_800L
        val fraction = (now % 1000 shl 32) / 1000
        ByteBuffer.wrap(data).putLong(offset, (seconds shl 32) or fraction)
    }

    /** 关闭所有阻塞读写后取消任务，避免探测连接影响下一次投屏。 */
    fun close() {
        eventClient?.close()
        eventServer.close()
        timingSocket.close()
        scope.cancel()
    }
}
