package com.pureair.tv.core.rtsp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * RTSP 7000 信令服务主机
 */
class RtspServer(
    val port: Int = 7000,
    private val sessionListener: RtspSessionListener? = null
) {
    companion object {
        private const val TAG = "RtspServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeSessions = ConcurrentHashMap<String, RtspSession>()
    @Volatile var isRunning: Boolean = false
        private set

    /**
     * 同步绑定控制端口，绑定失败直接交给调用方，避免提前广播不可用服务。
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress("0.0.0.0", port))
        }
        serverSocket = server
        isRunning = true

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "RtspServer started and listening on 0.0.0.0:$port")
                com.pureair.tv.util.LogEventBus.log("TCP 7000 信令端口就绪，等待 iPhone 连接")

                while (isActive && isRunning) {
                    val socket = server.accept()
                    launch(Dispatchers.IO) {
                        val session = RtspSession(socket, object : RtspSessionListener {
                            override fun onSessionStarted(sessionId: String) {
                                sessionListener?.onSessionStarted(sessionId)
                            }

                            override fun onStreamingStarted(sessionId: String, videoPort: Int, audioPort: Int) {
                                sessionListener?.onStreamingStarted(sessionId, videoPort, audioPort)
                            }

                            override fun onStreamingStopped(sessionId: String) {
                                sessionListener?.onStreamingStopped(sessionId)
                            }

                            override fun onSessionClosed(sessionId: String) {
                                activeSessions.remove(sessionId)
                                sessionListener?.onSessionClosed(sessionId)
                            }

                            override fun onVideoSetup(sessionId: String, peer: java.net.InetAddress,
                                                      decryptor: com.pureair.tv.core.crypto.StreamDecryptor): Int =
                                checkNotNull(sessionListener).onVideoSetup(sessionId, peer, decryptor)

                            override fun onAudioSetup(sessionId: String, peer: java.net.InetAddress,
                                                      key: ByteArray, iv: ByteArray,
                                                      format: com.pureair.tv.core.rtp.AudioStreamFormat,
                                                      controlPort: Int): Pair<Int, Int> =
                                checkNotNull(sessionListener).onAudioSetup(sessionId, peer, key, iv,
                                    format, controlPort)

                            override fun onAudioStopped(sessionId: String) {
                                sessionListener?.onAudioStopped(sessionId)
                            }

                            override fun onVideoStopped(sessionId: String) {
                                sessionListener?.onVideoStopped(sessionId)
                            }

                            override fun onAudioFlush(sessionId: String, nextSequence: Int?) {
                                sessionListener?.onAudioFlush(sessionId, nextSequence)
                            }
                        })
                        activeSessions[session.sessionId] = session
                        session.handle()
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "RtspServer exception", e)
                }
            } finally {
                // 旧监听协程退出时不能清理已经重启的新服务。
                if (serverSocket === server) stop() else server.close()
            }
        }
    }

    /**
     * 结束指定投屏连接，不影响设备发现或其他探测连接。
     */
    fun closeSession(sessionId: String) {
        activeSessions[sessionId]?.close()
    }

    /**
     * 停止 RTSP 服务并清理所有会话
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        for (session in activeSessions.values) {
            session.close()
        }
        activeSessions.clear()

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        Log.i(TAG, "RtspServer stopped")
    }
}
