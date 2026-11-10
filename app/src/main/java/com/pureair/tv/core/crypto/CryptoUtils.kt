package com.pureair.tv.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    private val secureRandom = SecureRandom()

    /**
     * 生成 Curve25519 (X25519) 密钥对
     */
    fun generateCurve25519KeyPair(): Pair<ByteArray, ByteArray> {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        val privateKey = (keyPair.private as X25519PrivateKeyParameters).encoded
        val publicKey = (keyPair.public as X25519PublicKeyParameters).encoded
        return Pair(publicKey, privateKey)
    }

    /**
     * 根据固定 32 字节私钥种子生成固定的 Ed25519 密钥对
     */
    fun getEd25519KeyPairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val privParams = Ed25519PrivateKeyParameters(seed, 0)
        val pubParams = privParams.generatePublicKey()
        return Pair(pubParams.encoded, privParams.encoded)
    }

    /**
     * 生成 Ed25519 数字签名密钥对 (用于 AirPlay pair-verify 身份鉴权)
     */
    fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()

        val privateKey = (keyPair.private as Ed25519PrivateKeyParameters).encoded
        val publicKey = (keyPair.public as Ed25519PublicKeyParameters).encoded
        return Pair(publicKey, privateKey)
    }

    /**
     * 计算 X25519 共享密钥 (Shared Secret)
     */
    fun computeSharedSecret(myPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val privParams = X25519PrivateKeyParameters(myPrivateKey, 0)
        val pubParams = X25519PublicKeyParameters(peerPublicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)
        return sharedSecret
    }

    /**
     * 使用 Ed25519 私钥对数据进行签名 (输出 64 字节签名)
     */
    fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray {
        val privParams = Ed25519PrivateKeyParameters(privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** 校验客户端身份签名，错误长度直接拒绝，避免把任意确认报文当成认证成功。 */
    fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    /**
     * AirPlay 标准 Pair-Verify AES 密钥与 IV 派生算法
     * Key = 前16字节 of SHA-512("Pair-Verify-AES-Key" + sharedSecret)
     * IV  = 前16字节 of SHA-512("Pair-Verify-AES-IV" + sharedSecret)
     */
    fun derivePairVerifyAesKeyAndIv(sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val saltKey = "Pair-Verify-AES-Key".toByteArray(Charsets.UTF_8)
        val saltIv = "Pair-Verify-AES-IV".toByteArray(Charsets.UTF_8)

        val keyDigest = sha512(saltKey + sharedSecret)
        val ivDigest = sha512(saltIv + sharedSecret)

        val key = keyDigest.copyOfRange(0, 16)
        val iv = ivDigest.copyOfRange(0, 16)
        return Pair(key, iv)
    }

    /**
     * AES-128-CTR 加密/解密 (对称流密码)
     */
    fun encryptAes128Ctr(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(data)
    }

    /**
     * SHA-512 单向散列
     */
    fun sha512(input: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        return md.digest(input)
    }

    /**
     * AES-128-CBC 解密
     */
    fun decryptAes128Cbc(encryptedData: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(encryptedData)
    }
}
