package com.pureair.tv.core.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.pureair.tv.core.rtp.NalType
import com.pureair.tv.core.rtp.NalUnit
import com.pureair.tv.util.LogEventBus
import java.nio.ByteBuffer

/**
 * 基于 Android 原生 MediaCodec 的零拷贝低延迟 H.264 硬件解码播放器
 */
class VideoPlayer(private val onVideoSizeChanged: (Int, Int) -> Unit) {

    companion object {
        private const val TAG = "VideoPlayer"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10000L
    }

    private var mediaCodec: MediaCodec? = null
    private var surface: Surface? = null
    private var isConfigured = false
    private var hasRenderedFrame = false
    private val frameGate = VideoFrameGate()
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    private var configuredWidth = 1920
    private var configuredHeight = 1080
    var isRunning = false
        private set

    /**
     * 绑定渲染的 Surface (直通电视 GPU)
     */
    @Synchronized
    fun attachSurface(surface: Surface) {
        if (this.surface === surface) return
        if (isRunning && surface.isValid) {
            try {
                mediaCodec?.setOutputSurface(surface)
            } catch (_: IllegalStateException) {
                release()
            } catch (_: IllegalArgumentException) {
                release()
            }
        }
        this.surface = surface
    }

    /**
     * 根据 SPS 和 PPS 初始化硬件解码器
     */
    @Synchronized
    fun configureWithSpsPps(sps: ByteArray, pps: ByteArray, width: Int = 1920, height: Int = 1080) {
        try {
            release()
            lastSps = sps
            lastPps = pps
            configuredWidth = width
            configuredHeight = height

            val targetSurface = surface
            if (targetSurface == null || !targetSurface.isValid) {
                Log.w(TAG, "Surface is null or invalid, cannot configure MediaCodec.")
                return
            }

            Log.i(TAG, "Configuring MediaCodec for H.264 (${width}x${height})...")
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 5 * 1024 * 1024)

            // 此配置从 Android 11 开始提供，旧系统保持原生解码路径。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            val codec = MediaCodec.createDecoderByType(MIME_TYPE)
            this.mediaCodec = codec
            codec.configure(format, targetSurface, null, 0)
            codec.start()
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)

            this.isConfigured = true
            this.isRunning = true
            Log.i(TAG, "MediaCodec successfully configured and started!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure MediaCodec", e)
            LogEventBus.log("视频解码器初始化失败: ${e.message}")
            release()
        }
    }

    /**
     * 将 NALU 送入解码器输入缓冲区
     */
    @Synchronized
    fun queueNalUnit(unit: NalUnit) {
        val codec = mediaCodec ?: return
        if (!isConfigured || !isRunning) return
        if (unit.data.isEmpty()) return
        val type = frameGate.inspect(unit.data) ?: return

        try {
            require(unit.data.size <= 5 * 1024 * 1024) { "视频访问单元超长" }
            drainOutput(codec)
            var inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            val startedAt = System.nanoTime()
            while (inputBufferIndex < 0 && isRunning) {
                drainOutput(codec)
                check(System.nanoTime() - startedAt < 500_000_000) { "视频解码输入超时" }
                inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    if (unit.data.size > inputBuffer.remaining()) {
                        codec.queueInputBuffer(inputBufferIndex, 0, 0, unit.ptsUs, 0)
                        throw IllegalArgumentException("视频帧超出解码缓冲区")
                    }
                    inputBuffer.put(unit.data)
                    val flags = if (type == NalType.IDR) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        unit.data.size,
                        unit.ptsUs,
                        flags
                    )
                }
            }

            // 驱动输出并渲染到绑定的 Surface 上 (render = true)
            drainOutput(codec)
        } catch (e: Exception) {
            Log.e(TAG, "Error queueing NALU to MediaCodec", e)
            LogEventBus.log("视频解码恢复: ${e.message}")
            val sps = lastSps
            val pps = lastPps
            if (sps != null && pps != null) configureWithSpsPps(sps, pps, configuredWidth, configuredHeight)
        }
    }

    /** 输出格式变化先通知显示尺寸，再渲染后续画面，支持手机旋转及视频分辨率切换。 */
    private fun drainOutput(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputBufferIndex >= 0) {
                // render = true 直接交给底层 Surface 渲染，零内存拷贝
                codec.releaseOutputBuffer(outputBufferIndex, true)
                if (!hasRenderedFrame) {
                    hasRenderedFrame = true
                    LogEventBus.log("硬件解码器已输出首帧")
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                Log.i(TAG, "MediaCodec output format changed: $newFormat")
                notifyVideoSize(newFormat)
                codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            } else {
                break
            }
        }
    }

    /** 使用有效裁剪区域和旋转后的尺寸，裁剪键采用字符串以兼容旧版 Android。 */
    private fun notifyVideoSize(format: MediaFormat) {
        fun dimension(sizeKey: String, startKey: String, endKey: String): Int =
            if (format.containsKey(startKey) && format.containsKey(endKey)) {
                format.getInteger(endKey) - format.getInteger(startKey) + 1
            } else {
                format.getInteger(sizeKey)
            }
        val width = dimension(MediaFormat.KEY_WIDTH, "crop-left", "crop-right")
        val height = dimension(MediaFormat.KEY_HEIGHT, "crop-top", "crop-bottom")
        if (width <= 0 || height <= 0) return
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            format.getInteger(MediaFormat.KEY_ROTATION)
        } else 0
        if (rotation == 90 || rotation == 270) onVideoSizeChanged(height, width)
        else onVideoSizeChanged(width, height)
    }

    /**
     * 释放解码器资源
     */
    @Synchronized
    fun release() {
        isRunning = false
        isConfigured = false
        hasRenderedFrame = false
        frameGate.reset()
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            // ignore
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            // ignore
        }
        mediaCodec = null
        Log.i(TAG, "MediaCodec released.")
    }
}
