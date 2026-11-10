package com.pureair.tv.core.rtp

import android.util.Log
import com.pureair.tv.core.player.MediaEngine
import com.pureair.tv.util.LogEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** 接收镜像 TCP 数据；必须先绑定端口再向 iPhone 返回 SETUP。 */
class RtpReceiver(private val port: Int = 0, private val mediaEngine: MediaEngine) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var workerJob: Job? = null
    @Volatile var isRunning = false
        private set

    /** 同步绑定监听端口，后台只接收来自已认证客户端的媒体连接。 */
    @Synchronized
    fun start(scope: CoroutineScope, peer: InetAddress): Int {
        check(!isRunning) { "视频接收器已经启动" }
        val server = ServerSocket(port)
        serverSocket = server
        isRunning = true
        workerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isRunning) {
                    val client = server.accept()
                    if (client.inetAddress != peer) {
                        client.close()
                        continue
                    }
                    synchronized(this@RtpReceiver) {
                        if (serverSocket !== server) {
                            client.close()
                            return@launch
                        }
                        clientSocket = client
                    }
                    client.tcpNoDelay = true
                    try {
                        client.use {
                            val input = BufferedInputStream(it.getInputStream(), 65536)
                            while (isRunning) {
                                val packet = MirrorPacket.read(input) ?: break
                                val current = synchronized(this@RtpReceiver) {
                                    if (serverSocket !== server) false
                                    else {
                                        mediaEngine.onMirrorPacket(packet)
                                        true
                                    }
                                }
                                if (!current) break
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) LogEventBus.log("视频连接等待重建: ${e.message}")
                    }
                    // 应用或方向切换可能重新连接同一视频端口，保留监听直到会话显式结束。
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("RtpReceiver", "镜像接收失败", e)
                    LogEventBus.log("视频流异常: ${e.message}")
                }
            } finally {
                // 不调用全局 stop，避免旧协程结束时关闭新会话的监听器。
                server.close()
                if (serverSocket === server) isRunning = false
            }
        }
        return server.localPort
    }

    /** 同时关闭已接受的连接，防止 read 阻塞导致上一个会话残留。 */
    @Synchronized
    fun stop() {
        isRunning = false
        clientSocket?.close()
        clientSocket = null
        serverSocket?.close()
        serverSocket = null
        workerJob?.cancel()
        workerJob = null
    }
}
