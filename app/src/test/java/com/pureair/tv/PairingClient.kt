package com.pureair.tv

import com.pureair.tv.core.crypto.CryptoUtils
import org.junit.Assert.assertTrue
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 模拟发送端独立执行密钥交换、验签与客户端签名，避免测试只断言响应长度。 */
class PairingClient {
    private val ecdh = CryptoUtils.generateCurve25519KeyPair()
    private val identity = CryptoUtils.generateEd25519KeyPair()
    val request = byteArrayOf(1, 0, 0, 0) + ecdh.first + identity.first

    /** 使用 pair-setup 公钥验签，并生成延续 CTR 计数器的第二阶段请求。 */
    fun confirmation(response: ByteArray, advertisedPublicKey: ByteArray): ByteArray {
        val serverKey = response.copyOfRange(0, 32)
        val secret = CryptoUtils.computeSharedSecret(ecdh.second, serverKey)
        fun derive(salt: String) = MessageDigest.getInstance("SHA-512")
            .digest(salt.toByteArray() + secret).copyOf(16)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive("Pair-Verify-AES-Key"), "AES"),
            IvParameterSpec(derive("Pair-Verify-AES-IV")))
        val serverSignature = cipher.update(response.copyOfRange(32, 96))
        assertTrue("服务端签名必须能被先前公布的身份公钥验证",
            CryptoUtils.verifyEd25519(advertisedPublicKey, serverKey + ecdh.first, serverSignature))
        val signature = CryptoUtils.signEd25519(identity.second, ecdh.first + serverKey)
        return ByteArray(4) + cipher.update(signature)
    }
}
