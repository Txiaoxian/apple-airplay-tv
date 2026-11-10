package com.pureair.tv.core.player

import android.view.Surface
import com.pureair.tv.core.crypto.StreamDecryptor
import com.pureair.tv.core.rtp.AvcConfiguration
import com.pureair.tv.core.rtp.MirrorPacket
import com.pureair.tv.core.rtp.NalType
import com.pureair.tv.core.rtp.NalUnit
import com.pureair.tv.util.LogEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 将镜像配置和加密视频帧交给硬件解码器，所有状态按当前投屏会话持有。 */
class MediaEngine {
    // 页面在后台被回收后，新订阅者仍能恢复当前播放状态，不能依赖一次性广播。
    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()
    private val videoPlayer = VideoPlayer { videoWidth, videoHeight ->
        _videoSize.value = videoWidth to videoHeight
    }
    private var decryptor: StreamDecryptor? = null
    private var configuration: AvcConfiguration? = null
    private var firstTimestampUs: Long? = null
    private var width = 1920
    private var height = 1080

    val isStreaming: Boolean get() = _streaming.value

    /** Surface 重建后使用保留的参数集恢复解码。 */
    @Synchronized
    fun attachSurface(surface: Surface) {
        videoPlayer.attachSurface(surface)
        if (!videoPlayer.isRunning) {
            configuration?.let { videoPlayer.configureWithSpsPps(it.sps, it.pps, width, height) }
        }
    }

    /** Surface 销毁时及时释放解码器，避免继续向无效窗口提交帧。 */
    @Synchronized
    fun detachSurface() {
        videoPlayer.release()
    }

    /** SETUP 成功后配置本次视频流；配对阶段不提前配置媒体密钥。 */
    @Synchronized
    fun configureEncryption(streamDecryptor: StreamDecryptor) {
        decryptor = streamDecryptor
    }

    /** 在回复流 SETUP 前开启管线，不依赖客户端是否另发 RECORD。 */
    @Synchronized
    fun start() {
        _streaming.value = true
    }

    /** 配置包不加密，只有视频数据包推进连续 CTR 解密状态。 */
    @Synchronized
    fun onMirrorPacket(packet: MirrorPacket) {
        if (!isStreaming) return
        when (packet.type) {
            1 -> {
                val config = AvcConfiguration.parse(packet.payload)
                val previous = configuration
                val changed = previous == null || !previous.sps.contentEquals(config.sps) ||
                    !previous.pps.contentEquals(config.pps)
                if (packet.width in 1..4096 && packet.height in 1..4096) {
                    width = packet.width
                    height = packet.height
                }
                configuration = config
                // 先按镜像配置调整窗口，解码器输出后再用实际裁剪尺寸校正。
                _videoSize.value = width to height
                if (changed || !videoPlayer.isRunning) {
                    videoPlayer.configureWithSpsPps(config.sps, config.pps, width, height)
                }
                LogEventBus.log("已收到视频参数: ${width}×${height}")
            }
            0 -> {
                val clear = checkNotNull(decryptor) { "视频密钥尚未就绪" }.decrypt(packet.payload)
                val config = checkNotNull(configuration) { "缺少视频 AVC 配置" }
                val frame = config.toAnnexB(clear)
                if (firstTimestampUs == null) {
                    firstTimestampUs = packet.timestampUs
                    LogEventBus.log("已收到并解密首帧视频")
                }
                videoPlayer.queueNalUnit(NalUnit(
                    NalType.OTHER, frame, (packet.timestampUs - firstTimestampUs!!).coerceAtLeast(0)
                ))
            }
        }
    }

    /** 结束会话后清理参数和计数器，保证下一次连接从头开始。 */
    @Synchronized
    fun stop() {
        _streaming.value = false
        videoPlayer.release()
        configuration = null
        decryptor = null
        firstTimestampUs = null
        width = 1920
        height = 1080
        _videoSize.value = null
    }
}
