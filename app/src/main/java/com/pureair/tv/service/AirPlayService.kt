package com.pureair.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pureair.tv.R
import com.pureair.tv.core.bonjour.BonjourServer
import com.pureair.tv.core.player.MediaEngine
import com.pureair.tv.core.rtsp.RtspServer
import com.pureair.tv.core.rtsp.RtspSessionListener
import com.pureair.tv.ui.MainActivity
import com.pureair.tv.util.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 极简投屏核心后台前台常驻服务
 */
class AirPlayService : Service(), RtspSessionListener {

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_CHANNEL_ID = "airplay_wake_channel"
        private const val WAKE_NOTIFICATION_ID = 1002
        const val EXTRA_WAKE_FROM_BACKGROUND = "com.pureair.tv.EXTRA_WAKE_FROM_BACKGROUND"

        fun start(context: Context) {
            val intent = Intent(context, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService get() = this@AirPlayService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val bonjourServer = BonjourServer()
    val rtspServer = RtspServer(7000, this)
    val mediaEngine = MediaEngine()
    val rtpReceiver = com.pureair.tv.core.rtp.RtpReceiver(0, mediaEngine)
    private val audioReceiver = com.pureair.tv.core.rtp.AudioReceiver()
    @Volatile private var streamingSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AirPlayService onCreate")
        acquireSystemLocks()
        startForegroundNotification()
        startServers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun acquireSystemLocks() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // 组播锁：必须开启，否则 Android 系统会丢弃 mDNS / Bonjour 组播包
            multicastLock = wifiManager.createMulticastLock("PureAirMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }

            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "PureAirWifiLock"
            ).apply {
                acquire()
            }

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PureAirTV:WakeLock"
            ).apply {
                acquire(24 * 60 * 60 * 1000L) // 24小时防休眠保护
            }

            Log.i(TAG, "MulticastLock, WifiLock, and WakeLock acquired.")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring locks", e)
        }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "投屏后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 AirPlay 电视投屏服务常驻就绪"
            }
            val wakeChannel = NotificationChannel(
                WAKE_CHANNEL_ID,
                "投屏画面自动唤醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "收到手机投屏时自动唤醒并拉起播放窗口"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(wakeChannel)
        }

        val notifyIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notifyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startServers() {
        serviceScope.launch {
            try {
                // 先确认控制端口可用，再向局域网广播设备，避免发现后立即连接失败。
                rtspServer.start(serviceScope)
                bonjourServer.start(deviceName = DeviceUtils.getDeviceDisplayName(), airPlayPort = 7000)
                Log.i(TAG, "All AirPlay servers successfully started!")
            } catch (e: Exception) {
                rtspServer.stop()
                Log.e(TAG, "投屏服务启动失败", e)
                com.pureair.tv.util.LogEventBus.log("投屏服务启动失败: ${e.message}")
            }
        }
    }

    override fun onSessionStarted(sessionId: String) {
        Log.i(TAG, "Client connected: $sessionId")
    }

    /** 同一时间只允许一个媒体会话，/info 探测连接不拥有媒体资源。 */
    private fun claimSession(sessionId: String) {
        check(streamingSessionId == null || streamingSessionId == sessionId) { "已有设备正在投屏" }
        streamingSessionId = sessionId
    }

    /** 先准备密钥、解码管线和 TCP 监听，再把真实端口返回给 SETUP。 */
    @Synchronized
    override fun onVideoSetup(sessionId: String, peer: java.net.InetAddress,
                              decryptor: com.pureair.tv.core.crypto.StreamDecryptor): Int {
        claimSession(sessionId)
        mediaEngine.configureEncryption(decryptor)
        mediaEngine.start()
        return rtpReceiver.start(serviceScope, peer)
    }

    /** 音频与视频共用会话所有权，独立准备 AAC 接收与播放端口。 */
    @Synchronized
    override fun onAudioSetup(sessionId: String, peer: java.net.InetAddress, key: ByteArray,
                              iv: ByteArray, format: com.pureair.tv.core.rtp.AudioStreamFormat,
                              controlPort: Int): Pair<Int, Int> {
        claimSession(sessionId)
        return audioReceiver.start(serviceScope, peer, key, iv, format, controlPort)
    }

    /** 音频重新协商仅替换音频资源，不影响仍在运行的视频和会话所有权。 */
    @Synchronized
    override fun onAudioStopped(sessionId: String) {
        if (streamingSessionId == sessionId) audioReceiver.stop()
    }

    /** 横竖屏或应用切换时可重建视频流，保留同一会话的音频通道。 */
    @Synchronized
    override fun onVideoStopped(sessionId: String) {
        if (streamingSessionId != sessionId) return
        rtpReceiver.stop()
        mediaEngine.stop()
    }

    /** FLUSH 只清空音频缓冲，不等同于断开投屏。 */
    @Synchronized
    override fun onAudioFlush(sessionId: String, nextSequence: Int?) {
        if (streamingSessionId == sessionId) audioReceiver.flush(nextSequence)
    }

    /** 已认证的视频流就绪后打开播放页；通过双重通道（Activity + 全屏意图通知）强力唤醒前台。 */
    override fun onStreamingStarted(sessionId: String, videoPort: Int, audioPort: Int) {
        Log.i(TAG, "Streaming started: $sessionId, videoPort=$videoPort")
        serviceScope.launch {
            // 网络线程排队期间可能已断开，不能再把已结束的投屏页面弹到前台。
            if (streamingSessionId != sessionId || !mediaEngine.isStreaming) return@launch

            val launchIntent = Intent(this@AirPlayService, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(EXTRA_WAKE_FROM_BACKGROUND, true)
            }

            // 通道 1：创建系统级全屏通知（Android 8+ 高优先级渠道强力唤醒推荐机制）
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this@AirPlayService,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val wakeNotification = NotificationCompat.Builder(this@AirPlayService, WAKE_CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("正在接收手机屏幕镜像…")
                .setSmallIcon(R.drawable.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(WAKE_NOTIFICATION_ID, wakeNotification)

            // 通道 2：常规直接 startActivity
            try {
                startActivity(launchIntent)
            } catch (e: RuntimeException) {
                Log.e(TAG, "无法直接启动 Activity，已通过全屏通知通道唤醒", e)
                com.pureair.tv.util.LogEventBus.log("正在通过全屏通知拉起投屏画面")
            }
        }
    }

    /** 完整会话结束时释放媒体，播放状态同步恢复待机页面。 */
    @Synchronized
    override fun onStreamingStopped(sessionId: String) {
        if (streamingSessionId != sessionId) return
        streamingSessionId = null
        Log.i(TAG, "Streaming stopped: $sessionId")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(WAKE_NOTIFICATION_ID)
        rtpReceiver.stop()
        audioReceiver.stop()
        mediaEngine.stop()
        startForegroundNotification()
    }

    override fun onSessionClosed(sessionId: String) {
        Log.i(TAG, "Session finished: $sessionId")
        onStreamingStopped(sessionId)
    }

    /** 遥控器停止时关闭对应控制连接，让发送端也结束会话。 */
    fun stopStreaming() {
        streamingSessionId?.let { rtspServer.closeSession(it) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AirPlayService onDestroy")
        rtspServer.stop()
        rtpReceiver.stop()
        audioReceiver.stop()
        mediaEngine.stop()
        serviceScope.launch(Dispatchers.IO) {
            bonjourServer.stop()
        }.invokeOnCompletion { serviceScope.cancel() }

        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}
