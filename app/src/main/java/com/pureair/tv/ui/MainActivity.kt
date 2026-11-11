package com.pureair.tv.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Gravity
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pureair.tv.R
import com.pureair.tv.databinding.ActivityMainBinding
import com.pureair.tv.service.AirPlayService
import com.pureair.tv.util.DeviceUtils
import com.pureair.tv.util.LogEventBus
import com.pureair.tv.util.NetworkUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var airPlayService: AirPlayService? = null
    private var isBound = false
    private val logList = java.util.LinkedList<String>()
    private var videoSize: Pair<Int, Int>? = null
    private var videoSizeJob: Job? = null
    private var streamingJob: Job? = null
    private var videoSurface: Surface? = null
    private var videoTexture: SurfaceTexture? = null

    // 记录是否处于前台显示状态，以及本次投屏会话是否由后台唤起
    private var isActivityVisible = false
    private var launchedFromBackground = false
    private var hasBeenStreaming = false

    private val serviceConnection = object : ServiceConnection {
        /** 服务可能早已开始投屏，绑定后立即订阅当前状态并恢复窗口。 */
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AirPlayService.LocalBinder
            airPlayService = binder.service
            isBound = true
            Log.i(TAG, "Connected to AirPlayService")

            binding.surfaceView.surfaceTexture?.let { attachVideoSurface(it) }
            streamingJob?.cancel()
            streamingJob = lifecycleScope.launch {
                binder.service.mediaEngine.streaming.collectLatest { streaming ->
                    if (streaming) {
                        hasBeenStreaming = true
                        binding.standbyContainer.visibility = View.GONE
                        binding.tvStatus.setText(R.string.status_streaming)
                    } else {
                        // 如果本次投屏是由后台运行拉起的，投屏结束后自动退回后台，不展示待机页面（无感体验）
                        if (hasBeenStreaming && launchedFromBackground) {
                            Log.i(TAG, "投屏结束：本次会话由后台唤醒，自动返回后台以实现无感运行")
                            hasBeenStreaming = false
                            launchedFromBackground = false
                            returnToBackground()
                        } else {
                            hasBeenStreaming = false
                            binding.standbyContainer.visibility = View.VISIBLE
                            binding.tvStatus.setText(R.string.status_standby)
                        }
                    }
                }
            }
            videoSizeJob?.cancel()
            videoSizeJob = lifecycleScope.launch {
                binder.service.mediaEngine.videoSize.collectLatest { size ->
                    videoSize = size
                    updateVideoLayout()
                }
            }
        }

        /** 服务异常断开后恢复待机，停止使用旧服务的状态订阅。 */
        override fun onServiceDisconnected(name: ComponentName?) {
            streamingJob?.cancel()
            videoSizeJob?.cancel()
            if (!launchedFromBackground) {
                binding.standbyContainer.visibility = View.VISIBLE
                binding.tvStatus.setText(R.string.status_standby)
            }
            videoSize = null
            updateVideoLayout()
            airPlayService = null
            isBound = false
        }
    }

    /** 创建渲染窗口并绑定常驻服务，播放状态由服务统一恢复。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent?.getBooleanExtra(AirPlayService.EXTRA_WAKE_FROM_BACKGROUND, false) == true) {
            launchedFromBackground = true
            Log.i(TAG, "onCreate: 由后台投屏服务创建唤醒 (launchedFromBackground=true)")
        }

        initView()
        setupSurface()
        startAndBindService()
        observeLogs()
    }

    /** 处理 singleTop 模式下由后台服务重新拉起投屏的唤醒请求。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val fromBackgroundExtra = intent?.getBooleanExtra(AirPlayService.EXTRA_WAKE_FROM_BACKGROUND, false) == true
        if (!isActivityVisible || fromBackgroundExtra) {
            launchedFromBackground = true
            Log.i(TAG, "onNewIntent: 标记本次会话为后台唤醒 (isActivityVisible=$isActivityVisible, fromExtra=$fromBackgroundExtra)")
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
    }

    /** 从系统授权页或后台返回时重新读取权限并刷新界面提示。 */
    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        val streaming = airPlayService?.mediaEngine?.isStreaming == true
        if (!streaming && !launchedFromBackground) {
            binding.standbyContainer.visibility = View.VISIBLE
        }
    }

    private fun updatePermissionStatus() {
        val isXiaomi = DeviceUtils.isXiaomi()
        val miuiBgAllowed = if (isXiaomi) DeviceUtils.checkMiuiBackgroundStartAllowed(this) else null
        val overlayAllowed = Settings.canDrawOverlays(this)

        val statusText: String
        val isFullyReady: Boolean

        if (isXiaomi) {
            when {
                miuiBgAllowed == true && overlayAllowed -> {
                    statusText = "✅ 小米权限状态：【后台弹出界面】与【悬浮窗】均已开启，后台自动唤醒就绪"
                    isFullyReady = true
                }
                miuiBgAllowed == false -> {
                    statusText = "⚠️ 小米权限状态：【后台弹出界面】未开启，挂在后台无法自动弹起，请点击右侧按钮开启！"
                    isFullyReady = false
                }
                !overlayAllowed -> {
                    statusText = "⚠️ 小米权限状态：【显示悬浮窗】未开启，建议点击右侧按钮开启"
                    isFullyReady = false
                }
                else -> {
                    statusText = "ℹ️ 小米设备挂在后台投屏，请确保已开启「后台弹出界面」与「悬浮窗」权限"
                    isFullyReady = false
                }
            }
        } else {
            if (overlayAllowed || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                statusText = "✅ 后台唤醒状态：权限正常，挂在后台收到手机投屏将自动打开画面"
                isFullyReady = true
            } else {
                statusText = "⚠️ 后台唤醒状态：未开启「显示在其他应用上层」，请点击右侧按钮授权"
                isFullyReady = false
            }
        }

        binding.tvPermissionStatus.text = statusText
        binding.tvPermissionStatus.setTextColor(
            ContextCompat.getColor(this, if (isFullyReady) R.color.accent_green else R.color.accent_blue)
        )
        binding.btnEnableBackgroundCasting.visibility = View.VISIBLE
        binding.btnEnableBackgroundCasting.text = if (isFullyReady) "⚙️ 权限设置 (已就绪)" else "🔐 开启后台投屏权限"
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            LogEventBus.logFlow.collectLatest { logMsg ->
                if (logList.size >= 5) {
                    logList.removeFirst()
                }
                logList.add(logMsg)
                binding.tvLiveLog.text = logList.joinToString("\n")
            }
        }
    }

    /** 初始化设备信息与后台显示授权入口。 */
    private fun initView() {
        val ip = NetworkUtils.getLocalIpAddress()
        val deviceName = DeviceUtils.getDeviceDisplayName()
        binding.tvIpAddress.text = "设备 IP 地址：$ip"
        binding.tvDeviceTitle.text = deviceName
        binding.tvGuideStep4.text = getString(R.string.guide_step4_format, deviceName)
        binding.tvStatus.text = getString(R.string.status_standby)
        binding.btnEnableBackgroundCasting.setOnClickListener { showBackgroundPermissionDialog() }
    }

    /** 监听电视可用区域变化，视频横竖屏切换时只调整窗口，不重置媒体会话。 */
    private fun setupSurface() {
        binding.surfaceView.surfaceTextureListener = this
        binding.root.addOnLayoutChangeListener { _, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateVideoLayout()
            }
        }
    }

    /** 根据最新视频尺寸居中留边，避免将竖屏画面拉伸到电视宽高比。 */
    private fun updateVideoLayout() {
        val root = binding.root
        val availableWidth = root.width - root.paddingLeft - root.paddingRight
        val availableHeight = root.height - root.paddingTop - root.paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return
        val (width, height) = videoSize?.let {
            fitVideoSize(it.first, it.second, availableWidth, availableHeight)
        } ?: (availableWidth to availableHeight)
        val params = binding.surfaceView.layoutParams as FrameLayout.LayoutParams
        if (params.width == width && params.height == height && params.gravity == Gravity.CENTER) return
        params.width = width
        params.height = height
        params.gravity = Gravity.CENTER
        binding.surfaceView.layoutParams = params
    }

    private fun startAndBindService() {
        AirPlayService.start(this)
        val intent = Intent(this, AirPlayService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /** 检查是否具备后台唤醒权限。小米需检查 MIUI 后台弹出界面权限，其他机型检查悬浮窗。 */
    private fun canShowFromBackground(): Boolean {
        val isXiaomi = DeviceUtils.isXiaomi()
        if (isXiaomi) {
            val miuiAllowed = DeviceUtils.checkMiuiBackgroundStartAllowed(this)
            if (miuiAllowed == false) return false
            return Settings.canDrawOverlays(this)
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)
    }

    /** 弹窗引导用户开启相关权限，并提供一键跳转系统设置。 */
    private fun showBackgroundPermissionDialog(onDismissOrLater: (() -> Unit)? = null) {
        val isXiaomi = DeviceUtils.isXiaomi()
        val message = if (isXiaomi) {
            "【小米 / 红米设备后台唤醒指引】\n\n小米系统对后台拉起界面有严格安全管控。退到后台时自动唤醒投屏需要：\n\n1. 开启「后台弹出界面」权限（最关键）\n2. 开启「显示悬浮窗」权限\n\n点击【去设置】将直达小米权限管理页，请找到并允许上述两项权限。"
        } else {
            "【后台自动唤醒权限指引】\n\n若希望在退到后台或电视主页时，手机发起镜像能自动弹出投屏画面，请开启【显示在其他应用上层】权限。\n\n点击【去设置】立即前往系统设置页面。"
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.background_permission_title)
            .setMessage(message)
            .setPositiveButton(R.string.background_permission_open) { _, _ ->
                openBackgroundPermissionSettings()
            }

        if (onDismissOrLater != null) {
            builder.setNegativeButton(R.string.background_permission_later) { _, _ ->
                onDismissOrLater()
            }
            builder.setNeutralButton("取消", null)
        } else {
            builder.setNegativeButton("取消", null)
        }

        builder.show()
    }

    /** 优先打开小米 MIUI / HyperOS 专用权限页；通用 Android 则跳转悬浮窗或应用详情。 */
    private fun openBackgroundPermissionSettings() {
        val intents = mutableListOf<Intent>()

        // 1. 小米 MIUI / HyperOS 专用权限管理页面（包含「后台弹出界面」、「显示悬浮窗」等）
        intents.add(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", packageName)
            }
        )
        intents.add(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
                putExtra("extra_pkgname", packageName)
            }
        )
        intents.add(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", packageName)
            }
        )

        // 2. Android 悬浮窗 / 显示在其他应用上层权限
        intents.add(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
        intents.add(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        )

        // 3. 应用详情页面 (系统设置 -> 应用信息)
        intents.add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // 当前 Intent 不支持时继续尝试下一个备选 Intent
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.background_permission_unavailable, Toast.LENGTH_LONG).show()
    }

    /** 同一个纹理只创建一次 Surface，视图宽高变化不会重置解码器。 */
    private fun attachVideoSurface(texture: SurfaceTexture) {
        val previous = videoSurface
        if (videoTexture !== texture || previous?.isValid != true) {
            videoTexture = texture
            videoSurface = Surface(texture)
        }
        videoSurface?.let { airPlayService?.mediaEngine?.attachSurface(it) }
        if (previous !== videoSurface) previous?.release()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        attachVideoSurface(texture)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        // 这里只是显示窗口缩放，继续使用同一个解码 Surface。
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (videoTexture === texture) {
            airPlayService?.mediaEngine?.detachSurface()
            videoSurface?.release()
            videoSurface = null
            videoTexture = null
        }
        return true
    }

    /**
     * 将投屏页面无感退回后台，恢复投屏前的大屏界面（如电视桌面或前一个应用）
     */
    private fun returnToBackground() {
        val moved = moveTaskToBack(true)
        if (!moved) {
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to navigate to HOME: ${e.message}")
            }
        }
    }

    /**
     * 针对遥控器返回键优化：
     * 如果正在投屏，返回键停止当前投屏；若此前为后台唤起则退回后台，否则返回待机；
     * 待机返回时保留后台服务；缺少自动显示权限时说明限制并让用户选择。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (binding.standbyContainer.visibility == View.GONE) {
                // 正在投屏中，按返回键停止投屏
                airPlayService?.stopStreaming()
                if (launchedFromBackground) {
                    Log.i(TAG, "用户按返回键中断后台投屏，直接退回后台")
                    hasBeenStreaming = false
                    launchedFromBackground = false
                    returnToBackground()
                    return true
                }
                binding.standbyContainer.visibility = View.VISIBLE
                return true
            } else {
                // 待机状态下按返回键，退到后台不杀进程
                if (canShowFromBackground()) {
                    Toast.makeText(this, R.string.background_ready_hint, Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                } else {
                    showBackgroundPermissionDialog {
                        moveTaskToBack(true)
                    }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 页面销毁只解绑显示资源和订阅，后台接收服务继续运行。 */
    override fun onDestroy() {
        super.onDestroy()
        streamingJob?.cancel()
        videoSizeJob?.cancel()
        airPlayService?.mediaEngine?.detachSurface()
        videoSurface?.release()
        videoSurface = null
        videoTexture = null
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
