package com.pureair.tv.core.rtp

import java.io.ByteArrayOutputStream

/**
 * H.264 NALU 类型
 */
enum class NalType(val typeId: Int) {
    NON_IDR(1), // 非关键帧 (P/B 帧)
    IDR(5),     // 关键帧 (I 帧)
    SEI(6),     // 补充增强信息
    SPS(7),     // 序列参数集
    PPS(8),     // 图像参数集
    AUD(9),     // 访问单元分隔符
    OTHER(0);

    companion object {
        fun fromHeader(headerByte: Byte): NalType {
            val type = headerByte.toInt() and 0x1F
            return values().firstOrNull { it.typeId == type } ?: OTHER
        }
    }
}

data class NalUnit(
    val type: NalType,
    val data: ByteArray, // 包含 00 00 00 01 起始码的完整 Annex-B 字节数据
    val ptsUs: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NalUnit
        return type == other.type && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * H.264 Annex-B 码流分析与 NALU 提取器
 */
class H264Extractor {

    private val streamBuffer = ByteArrayOutputStream()

    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    /**
     * 将接收到的数据写入缓冲区，并提取出所有完整的 NALU 单元
     */
    @Synchronized
    fun extractNalUnits(data: ByteArray, offset: Int = 0, length: Int = data.size, ptsUs: Long = 0): List<NalUnit> {
        streamBuffer.write(data, offset, length)
        val bufferBytes = streamBuffer.toByteArray()
        val units = mutableListOf<NalUnit>()

        val startCodeIndices = findStartCodeIndices(bufferBytes)
        if (startCodeIndices.size < 2) {
            // 尚未积累到至少两个起始码，无法确定第一个 NALU 的边界，保留等待后续数据
            return units
        }

        // 解析前面完整的 NALU 单元
        for (i in 0 until startCodeIndices.size - 1) {
            val start = startCodeIndices[i]
            val end = startCodeIndices[i + 1]
            val nalLen = end - start
            val nalBytes = ByteArray(nalLen)
            System.arraycopy(bufferBytes, start, nalBytes, 0, nalLen)

            // 起始码长度可能是 3 (00 00 01) 或 4 (00 00 00 01)
            val headerOffset = if (nalBytes[2].toInt() == 0x01) 3 else 4
            if (nalBytes.size > headerOffset) {
                val nalType = NalType.fromHeader(nalBytes[headerOffset])
                if (nalType == NalType.SPS) {
                    sps = nalBytes.clone()
                } else if (nalType == NalType.PPS) {
                    pps = nalBytes.clone()
                }
                units.add(NalUnit(nalType, nalBytes, ptsUs))
            }
        }

        // 保留最后一个可能尚未完整的 NALU 到 buffer 中
        val lastStart = startCodeIndices.last()
        val remainderLen = bufferBytes.size - lastStart
        val remainder = ByteArray(remainderLen)
        System.arraycopy(bufferBytes, lastStart, remainder, 0, remainderLen)

        streamBuffer.reset()
        streamBuffer.write(remainder)

        return units
    }

    private fun findStartCodeIndices(data: ByteArray): List<Int> {
        val indices = mutableListOf<Int>()
        var i = 0
        val max = data.size - 3
        while (i < max) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    // 00 00 01
                    indices.add(i)
                    i += 3
                    continue
                } else if (i < max - 1 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    // 00 00 00 01
                    indices.add(i)
                    i += 4
                    continue
                }
            }
            i++
        }
        return indices
    }

    fun reset() {
        streamBuffer.reset()
        sps = null
        pps = null
    }
}
