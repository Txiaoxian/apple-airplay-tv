package com.pureair.tv.core.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pureair.tv.util.LogEventBus

/** PCM 播放端先预填充短缓冲，再连续播放，避免每个网络包到达时发生欠载。 */
class AudioPlayer(private val sampleRate: Int = 44100,
                  private val channelConfig: Int = AudioFormat.CHANNEL_OUT_STEREO) {
    private var audioTrack: AudioTrack? = null
    private var queuedBytes = 0
    private var playing = false
    private val channels = if (channelConfig == AudioFormat.CHANNEL_OUT_MONO) 1 else 2
    private val prefillBytes = sampleRate * channels * 2 * 40 / 1000
    @Volatile var isRunning = false
        private set

    /** 准备约 120ms 的输出缓冲；收到至少 40ms PCM 后才开始播放。 */
    @Synchronized
    fun start() {
        if (isRunning) return
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "不支持的音频输出参数" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate * channels * 2 * 120 / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("音频输出初始化失败")
        }
        audioTrack = track
        queuedBytes = 0
        playing = false
        isRunning = true
    }

    /** 阻塞写入只发生在专用播放线程；部分写入时继续提交，不能丢掉剩余 PCM。 */
    fun write(pcmData: ByteArray) {
        val track = audioTrack ?: return
        var offset = 0
        while (isRunning && offset < pcmData.size) {
            // 未开始播放前最多写到预填充阈值，避免单个大块填满缓冲后永久等待。
            val count = if (playing) pcmData.size - offset
                else minOf(pcmData.size - offset, (prefillBytes - queuedBytes).coerceAtLeast(1))
            val written = track.write(pcmData, offset, count, AudioTrack.WRITE_BLOCKING)
            check(written > 0) { "音频输出写入失败: $written" }
            offset += written
            if (!playing) {
                queuedBytes += written
                if (queuedBytes >= prefillBytes) {
                    track.play()
                    playing = true
                }
            }
        }
    }

    /** 应用切换或跳转进度时清空旧声音，下一段重新预填充，不结束视频会话。 */
    @Synchronized
    fun flush() {
        val track = audioTrack ?: return
        if (playing) track.pause()
        track.flush()
        playing = false
        queuedBytes = 0
    }

    /** 先停止输出解除阻塞写入，再释放资源。 */
    @Synchronized
    fun release() {
        isRunning = false
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            track.release()
        }
        playing = false
        queuedBytes = 0
    }
}
