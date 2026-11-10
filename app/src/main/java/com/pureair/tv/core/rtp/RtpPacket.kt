package com.pureair.tv.core.rtp

/**
 * RTP (Real-Time Transport Protocol) 数据包解析器
 */
data class RtpPacket(
    val version: Int,
    val hasPadding: Boolean,
    val hasExtension: Boolean,
    val csrcCount: Int,
    val marker: Boolean,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
) {
    companion object {
        const val HEADER_SIZE = 12

        /**
         * 解析二进制原始数据为 RtpPacket
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): RtpPacket? {
            if (offset < 0 || length < HEADER_SIZE || offset > data.size - length) return null

            val b0 = data[offset].toInt() and 0xFF
            val version = (b0 ushr 6) and 0x03
            if (version != 2) return null
            val hasPadding = ((b0 ushr 5) and 0x01) == 1
            val hasExtension = ((b0 ushr 4) and 0x01) == 1
            val csrcCount = b0 and 0x0F

            val b1 = data[offset + 1].toInt() and 0xFF
            val marker = ((b1 ushr 7) and 0x01) == 1
            val payloadType = b1 and 0x7F

            val sequenceNumber = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)

            val timestamp = ((data[offset + 4].toLong() and 0xFF) shl 24) or
                    ((data[offset + 5].toLong() and 0xFF) shl 16) or
                    ((data[offset + 6].toLong() and 0xFF) shl 8) or
                    (data[offset + 7].toLong() and 0xFF)

            val ssrc = ((data[offset + 8].toLong() and 0xFF) shl 24) or
                    ((data[offset + 9].toLong() and 0xFF) shl 16) or
                    ((data[offset + 10].toLong() and 0xFF) shl 8) or
                    (data[offset + 11].toLong() and 0xFF)

            var headerOffset = HEADER_SIZE + csrcCount * 4
            if (hasExtension) {
                if (length < headerOffset + 4) return null
                val extensionLength = ((data[offset + headerOffset + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + headerOffset + 3].toInt() and 0xFF)
                headerOffset += 4 + extensionLength * 4
            }

            if (length < headerOffset) return null

            var payloadLen = length - headerOffset
            if (hasPadding && payloadLen > 0) {
                val paddingLen = data[offset + length - 1].toInt() and 0xFF
                if (paddingLen == 0 || paddingLen > payloadLen) return null
                payloadLen -= paddingLen
            }

            if (payloadLen < 0) return null

            val payload = ByteArray(payloadLen)
            System.arraycopy(data, offset + headerOffset, payload, 0, payloadLen)

            return RtpPacket(
                version = version,
                hasPadding = hasPadding,
                hasExtension = hasExtension,
                csrcCount = csrcCount,
                marker = marker,
                payloadType = payloadType,
                sequenceNumber = sequenceNumber,
                timestamp = timestamp,
                ssrc = ssrc,
                payload = payload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtpPacket
        return sequenceNumber == other.sequenceNumber && timestamp == other.timestamp && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
