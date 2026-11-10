package com.pureair.tv.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 镜像视频使用连续 AES-CTR；跨报文保留计数器及不足一块的密钥流。 */
class StreamDecryptor(key: ByteArray, iv: ByteArray) {
    companion object {
        /** 根据 FairPlay 媒体密钥、配对共享密钥和无符号流标识派生视频密钥。 */
        fun forVideo(mediaKey: ByteArray, sharedSecret: ByteArray, streamId: Long): StreamDecryptor {
            require(mediaKey.size == 16 && sharedSecret.size == 32)
            val baseKey = CryptoUtils.sha512(mediaKey + sharedSecret).copyOf(16)
            val id = streamId.toULong().toString()
            val key = CryptoUtils.sha512(("AirPlayStreamKey" + id).toByteArray() + baseKey).copyOf(16)
            val iv = CryptoUtils.sha512(("AirPlayStreamIV" + id).toByteArray() + baseKey).copyOf(16)
            return StreamDecryptor(key, iv)
        }
    }

    private val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        require(key.size == 16 && iv.size == 16)
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }
    private var remainingKeyStream = ByteArray(0)

    /** 批量生成整块密钥流，保留尾部字节，兼容 Android 对 update 的缓冲差异。 */
    @Synchronized
    fun decrypt(encrypted: ByteArray, offset: Int = 0, length: Int = encrypted.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= encrypted.size - length)
        val result = ByteArray(length)
        val cached = minOf(length, remainingKeyStream.size)
        for (index in 0 until cached) {
            result[index] = (encrypted[offset + index].toInt() xor remainingKeyStream[index].toInt()).toByte()
        }
        remainingKeyStream = remainingKeyStream.copyOfRange(cached, remainingKeyStream.size)
        val needed = length - cached
        if (needed > 0) {
            val stream = cipher.update(ByteArray((needed + 15) / 16 * 16))
            for (index in 0 until needed) {
                result[cached + index] = (encrypted[offset + cached + index].toInt() xor stream[index].toInt()).toByte()
            }
            remainingKeyStream = stream.copyOfRange(needed, stream.size)
        }
        return result
    }
}
