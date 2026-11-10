package com.pureair.tv.core.rtp

import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber

/** 保存发送端实际协商的音频参数，不能把缺省 sr 一律当成 44100 Hz。 */
data class AudioStreamFormat(val compression: Int, val rate: Int, val channels: Int,
                             val bits: Int, val samples: Int) {
    init {
        require(compression in listOf(1, 2, 4, 8) && rate in 8000..96000 &&
            channels in 1..2 && bits in listOf(16, 24) && samples in 1..4096) {
            "不支持的音频参数 ct=$compression sr=$rate ch=$channels bits=$bits spf=$samples"
        }
        require(compression != 8 || samples in listOf(480, 512)) { "AAC-ELD spf=$samples 无效" }
        require(compression != 4 || samples in listOf(960, 1024)) { "AAC spf=$samples 无效" }
    }

    companion object {
        const val SUPPORTED_FORMATS = 0x3fffffcL

        /** audioFormat 使用单个比特编码格式，保留 64 位以免单声道高位标志溢出。 */
        fun fromPlist(stream: NSDictionary): AudioStreamFormat {
            fun number(key: String): Long? {
                val value = stream.objectForKey(key) ?: return null
                require(value is NSNumber) { "音频 $key 必须为数字" }
                return value.longValue()
            }
            val code = number("audioFormat") ?: 0L
            require(code >= 0 && (code == 0L || code and (code - 1) == 0L)) { "audioFormat=$code 无效" }
            val inferred = if (code == 0L) null else when (val bit = java.lang.Long.numberOfTrailingZeros(code)) {
                in 2..9 -> AudioStreamFormat(1, listOf(8000, 16000, 24000, 32000)[(bit - 2) / 2],
                    1 + (bit and 1), 16, 352)
                in 10..17 -> AudioStreamFormat(1, if (bit < 14) 44100 else 48000,
                    1 + (bit and 1), if ((bit - 10) % 4 < 2) 16 else 24, 352)
                in 18..21 -> AudioStreamFormat(2, if (bit < 20) 44100 else 48000,
                    2, if (bit and 1 == 0) 16 else 24, 352)
                in 22..23 -> AudioStreamFormat(4, if (bit == 22) 44100 else 48000, 2, 16, 1024)
                in 24..25 -> AudioStreamFormat(8, if (bit == 24) 44100 else 48000, 2, 16, 480)
                26, 27 -> AudioStreamFormat(8, if (bit == 26) 16000 else 24000, 1, 16, 480)
                31, 32 -> AudioStreamFormat(8, if (bit == 31) 44100 else 48000, 1, 16, 480)
                else -> throw IllegalArgumentException("不支持的 audioFormat=0x${code.toString(16)} ct=${number("ct")}")
            }
            fun field(key: String, fallback: Int): Int {
                val value = number(key) ?: return fallback
                require(value in 1..96000) { "音频 $key=$value 无效" }
                return value.toInt()
            }
            val ct = field("ct", inferred?.compression ?: 8)
            val result = AudioStreamFormat(ct, field("sr", inferred?.rate ?: 44100),
                field("ch", inferred?.channels ?: 2), field("ss", inferred?.bits ?: 16),
                field("spf", when (ct) { 2, 1 -> 352; 4 -> 1024; else -> 480 }))
            require(inferred == null || (result.compression == inferred.compression &&
                result.rate == inferred.rate && result.channels == inferred.channels && result.bits == inferred.bits)) {
                "audioFormat=0x${code.toString(16)} 与显式音频参数冲突: $result"
            }
            return result
        }
    }
}
