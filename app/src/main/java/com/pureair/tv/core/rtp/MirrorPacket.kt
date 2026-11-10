package com.pureair.tv.core.rtp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AirPlay 镜像专用报文；包头为 128 字节小端结构，不是 RTP 长度前缀。 */
data class MirrorPacket(
    val type: Int,
    val timestampUs: Long,
    val width: Int,
    val height: Int,
    val payload: ByteArray
) {
    companion object {
        /** 只有报文边界处的 EOF 表示正常关闭，截断或超长报文必须终止连接。 */
        fun read(input: InputStream): MirrorPacket? {
            val first = input.read()
            if (first == -1) return null
            val header = ByteArray(128)
            header[0] = first.toByte()
            val data = DataInputStream(input)
            data.readFully(header, 1, 127)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val size = buffer.getInt(0)
            require(size in 0..5 * 1024 * 1024) { "镜像报文长度无效: $size" }
            val type = buffer.getShort(4).toInt() and 255
            val timestamp = buffer.getLong(8)
            val pts = (timestamp ushr 32) * 1_000_000 +
                ((timestamp and 0xffffffffL) * 1_000_000 ushr 32)
            val width = buffer.getFloat(56).toInt()
            val height = buffer.getFloat(60).toInt()
            val payload = ByteArray(size)
            data.readFully(payload)
            return MirrorPacket(type, pts, width, height, payload)
        }
    }
}

/** 解析未加密的 AVC 配置，并按配置中的长度字段转换完整视频帧。 */
data class AvcConfiguration(val sps: ByteArray, val pps: ByteArray, val lengthSize: Int) {
    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** 读取 avcC 的全部参数集，拒绝截断数据和没有 SPS/PPS 的配置。 */
        fun parse(payload: ByteArray): AvcConfiguration {
            require(payload.size >= 7 && payload[0] == 1.toByte()) { "无效 AVC 配置" }
            val data = DataInputStream(payload.inputStream())
            data.skipBytes(4)
            val lengthSize = (data.readUnsignedByte() and 3) + 1
            require(lengthSize != 3) { "不支持的 AVC 长度字段" }
            val spsCount = data.readUnsignedByte() and 31
            fun readSets(count: Int): ByteArray {
                require(count > 0) { "AVC 参数集缺失" }
                val out = ByteArrayOutputStream()
                repeat(count) {
                    val size = data.readUnsignedShort()
                    require(size > 0 && size <= data.available()) { "AVC 参数集长度无效" }
                    val bytes = ByteArray(size)
                    data.readFully(bytes)
                    out.write(START_CODE)
                    out.write(bytes)
                }
                return out.toByteArray()
            }
            val sps = readSets(spsCount)
            val pps = readSets(data.readUnsignedByte())
            return AvcConfiguration(sps, pps, lengthSize)
        }
    }

    /** 一次返回整个访问单元，避免把同一帧的多个 NAL 分别提交给解码器。 */
    fun toAnnexB(payload: ByteArray): ByteArray {
        val data = DataInputStream(payload.inputStream())
        val out = ByteArrayOutputStream(payload.size)
        while (data.available() > 0) {
            require(data.available() >= lengthSize) { "视频 NAL 长度字段被截断" }
            var size = 0L
            repeat(lengthSize) { size = (size shl 8) or data.readUnsignedByte().toLong() }
            require(size > 0 && size <= data.available()) { "视频 NAL 长度无效" }
            out.write(START_CODE)
            val nal = ByteArray(size.toInt())
            data.readFully(nal)
            out.write(nal)
        }
        return out.toByteArray()
    }
}
