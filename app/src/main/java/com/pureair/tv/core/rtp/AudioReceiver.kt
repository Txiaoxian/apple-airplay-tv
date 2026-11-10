package com.pureair.tv.core.rtp

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import com.beatofthedrum.alacdecoder.AlacDecoder
import com.pureair.tv.core.crypto.CryptoUtils
import com.pureair.tv.core.player.AudioPlayer
import com.pureair.tv.util.LogEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** 接收、重排和播放独立运行，网络线程不等待硬件解码或 AudioTrack。 */
class AudioReceiver {
    companion object {
        /** 生成 AAC-LC/ELD 的 AudioSpecificConfig；PCM 和 ALAC 走各自解码路径。 */
        fun codecConfig(compression: Int, rate: Int, channels: Int, samples: Int): ByteArray {
            val frequency = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
                16000, 12000, 11025, 8000).indexOf(rate)
            require(frequency >= 0 && channels in 1..2 && compression in listOf(4, 8)) {
                "不支持的 AAC 参数 ct=$compression sr=$rate ch=$channels spf=$samples"
            }
            if (compression == 4) {
                require(samples in listOf(960, 1024))
                val value = (2 shl 11) or (frequency shl 7) or (channels shl 3) or
                    (if (samples == 960) 4 else 0)
                return byteArrayOf((value ushr 8).toByte(), value.toByte())
            }
            require(samples in listOf(480, 512)) { "AAC-ELD spf=$samples 无效" }
            val value = (31 shl 27) or (7 shl 21) or (frequency shl 17) or
                (channels shl 13) or ((if (samples == 480) 1 else 0) shl 12)
            return ByteBuffer.allocate(4).putInt(value).array()
        }

        /** RTP LPCM 为大端整数，输出统一转换成 Android 使用的 16 位小端 PCM。 */
        fun pcmTo16(payload: ByteArray, bits: Int): ByteArray {
            require(bits == 16 || bits == 24)
            val bytes = bits / 8
            require(payload.size % bytes == 0) { "PCM 样本被截断" }
            return ByteArray(payload.size / bytes * 2).also { output ->
                for (i in 0 until payload.size / bytes) {
                    output[i * 2] = payload[i * bytes + 1]
                    output[i * 2 + 1] = payload[i * bytes]
                }
            }
        }

        /** RTCP 重传请求使用网络字节序，序号按 16 位环绕。 */
        fun resendRequest(sequence: Int, count: Int, requestId: Int): ByteArray {
            require(sequence in 0..65535 && count in 1..128)
            return ByteBuffer.allocate(8).put(0x80.toByte()).put(0xd5.toByte())
                .putShort(requestId.toShort()).putShort(sequence.toShort()).putShort(count.toShort()).array()
        }
    }

    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private val jobs = mutableListOf<Job>()
    private val playbackLock = Any()
    private var decoder: MediaCodec? = null
    private var player: AudioPlayer? = null
    private var jitter: AudioJitterBuffer? = null
    private var firstTimestamp: Long? = null
    private var needsCodecConfig = false

    /** 先准备真实端口和解码器，ALAC 不依赖电视是否内置该编解码器。 */
    @Synchronized
    fun start(scope: CoroutineScope, peer: InetAddress, key: ByteArray, iv: ByteArray,
              format: AudioStreamFormat, controlPort: Int): Pair<Int, Int> {
        check(dataSocket == null) { "音频接收器已经启动" }
        require(key.size == 16 && iv.size == 16 && controlPort in 0..65535)
        try {
            val data = DatagramSocket(0).also { dataSocket = it; it.receiveBufferSize = 1024 * 1024 }
            val control = DatagramSocket(0).also { controlSocket = it }
            val buffer = AudioJitterBuffer(format.rate, format.samples).also { jitter = it }
            val remoteControl = AtomicInteger(controlPort)
            val config = if (format.compression in listOf(4, 8)) {
                codecConfig(format.compression, format.rate, format.channels, format.samples)
            } else null
            val codec = config?.let {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { current ->
                    decoder = current
                    val mediaFormat = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, format.rate, format.channels)
                    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(it))
                    mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                    mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    current.configure(mediaFormat, null, null, 0)
                    current.start()
                }
            }
            val alac = if (format.compression == 2) AlacDecoder(
                format.bits, format.channels, format.samples, format.rate) else null
            var outputRate = format.rate
            var outputChannels = format.channels
            fun openOutput(rate: Int, channels: Int): AudioPlayer =
                AudioPlayer(rate, if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
                    else AudioFormat.CHANNEL_OUT_STEREO).apply { start() }
            var output = openOutput(outputRate, outputChannels).also { player = it }
            val info = MediaCodec.BufferInfo()

            fun drainAudio() {
                if (codec == null) return
                while (!data.isClosed) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val actual = codec.outputFormat
                        val rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(rate in 8000..96000 && channels in 1..2)
                        if (rate != outputRate || channels != outputChannels) {
                            output.release()
                            output = openOutput(rate, channels).also { player = it }
                            outputRate = rate
                            outputChannels = channels
                        }
                        continue
                    }
                    if (index < 0) return
                    try {
                        if (info.size > 0) {
                            val bytes = ByteArray(info.size)
                            checkNotNull(codec.getOutputBuffer(index)).apply {
                                position(info.offset); get(bytes)
                            }
                            output.write(bytes)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }

            fun submit(bytes: ByteArray, pts: Long, flags: Int = 0) {
                val current = checkNotNull(codec)
                while (!data.isClosed) {
                    drainAudio()
                    val index = current.dequeueInputBuffer(10000)
                    if (index < 0) continue
                    val input = checkNotNull(current.getInputBuffer(index))
                    if (bytes.size > input.capacity()) {
                        current.queueInputBuffer(index, 0, 0, pts, 0)
                        throw IllegalArgumentException("音频帧超出解码缓冲")
                    }
                    input.clear()
                    input.put(bytes)
                    current.queueInputBuffer(index, 0, bytes.size, pts, flags)
                    return
                }
            }

            firstTimestamp = null
            needsCodecConfig = false
            jobs += scope.launch(Dispatchers.IO) { receive(data, peer, false, buffer, remoteControl) }
            jobs += scope.launch(Dispatchers.IO) { receive(control, peer, true, buffer, remoteControl) }
            jobs += scope.launch(Dispatchers.IO) {
                var lastResend = 0L
                var requestId = 0
                var errors = 0
                while (isActive && !data.isClosed) {
                    var didWork = false
                    val now = System.nanoTime() / 1_000_000
                    synchronized(playbackLock) {
                        if (!data.isClosed) {
                            val ready = buffer.poll(now)
                            try {
                                if (ready != null) {
                                    didWork = true
                                    val payload = ready.packet.payload
                                    if (ready.missing > 0) {
                                        output.write(ByteArray(minOf(ready.missing, 8) *
                                            format.samples * outputChannels * 2))
                                    }
                                    val noData = payload.isEmpty() ||
                                        payload.contentEquals(byteArrayOf(0, 0x68, 0x34, 0)) ||
                                        (format.compression == 2 && payload.size == 32)
                                    if (!noData) {
                                        val blockLength = payload.size / 16 * 16
                                        if (blockLength > 0) {
                                            CryptoUtils.decryptAes128Cbc(payload.copyOf(blockLength), key, iv)
                                                .copyInto(payload)
                                        }
                                        if (firstTimestamp == null) firstTimestamp = ready.packet.timestamp
                                        val pts = ((ready.packet.timestamp - firstTimestamp!!) and 0xffffffffL) *
                                            1_000_000 / format.rate
                                        when (format.compression) {
                                            1 -> output.write(pcmTo16(payload, format.bits))
                                            2 -> output.write(checkNotNull(alac).decode(payload))
                                            else -> {
                                                if (needsCodecConfig) {
                                                    submit(checkNotNull(config), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                                    needsCodecConfig = false
                                                }
                                                submit(payload, pts)
                                            }
                                        }
                                    }
                                }
                                drainAudio()
                            } catch (e: Exception) {
                                if (!data.isClosed && errors++ < 3) {
                                    LogEventBus.log("音频帧解码失败: ${e.message}")
                                }
                            }
                        }
                    }
                    val missing = buffer.missingRange()
                    if (missing != null && remoteControl.get() > 0 && now - lastResend >= 20 && !control.isClosed) {
                        runCatching {
                            val request = resendRequest(missing.first, missing.second, requestId++)
                            control.send(DatagramPacket(request, request.size, peer, remoteControl.get()))
                        }
                        lastResend = now
                    }
                    if (!didWork) delay(3)
                }
            }
            return data.localPort to control.localPort
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /** 网络线程只验证来源并入队，重传和原始包在同一个缓冲中按序号去重。 */
    private fun receive(socket: DatagramSocket, peer: InetAddress, retransmit: Boolean,
                        buffer: AudioJitterBuffer, remoteControl: AtomicInteger) {
        val bytes = ByteArray(65536)
        try {
            while (!socket.isClosed) {
                val packet = DatagramPacket(bytes, bytes.size)
                socket.receive(packet)
                if (packet.address != peer) continue
                if (retransmit) {
                    if (packet.length < 4) continue
                    if (remoteControl.get() == 0) remoteControl.compareAndSet(0, packet.port)
                    if (bytes[1].toInt() and 127 != 86) continue
                }
                val offset = if (retransmit) 4 else 0
                val rtp = RtpPacket.parse(bytes, offset, packet.length - offset) ?: continue
                if (rtp.payloadType == 96) buffer.offer(rtp, System.nanoTime() / 1_000_000)
            }
        } catch (e: Exception) {
            if (!socket.isClosed) LogEventBus.log("音频接收异常: ${e.message}")
        }
    }

    /** 原子清理重排、编解码和播放缓冲，防止旧应用的音频混入新片段。 */
    fun flush(nextSequence: Int?) {
        synchronized(playbackLock) {
            jitter?.clear(nextSequence)
            decoder?.flush()
            player?.flush()
            firstTimestamp = null
            needsCodecConfig = decoder != null
        }
    }

    /** 先关闭 socket 并解除 PCM 写入阻塞，再释放专用播放线程使用的解码器。 */
    @Synchronized
    fun stop() {
        dataSocket?.close()
        controlSocket?.close()
        player?.release()
        jobs.forEach { it.cancel() }
        jobs.clear()
        synchronized(playbackLock) {
            decoder?.let { codec ->
                runCatching { codec.stop() }
                codec.release()
            }
            decoder = null
            player = null
            jitter = null
        }
        dataSocket = null
        controlSocket = null
    }
}
