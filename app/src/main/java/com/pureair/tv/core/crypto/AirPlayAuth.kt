package com.pureair.tv.core.crypto

import com.github.serezhka.jap2lib.FairPlay
import com.pureair.tv.util.LogEventBus
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 处理免 PIN 的旧式 AirPlay 配对；只有双方签名验证完成后才允许建立媒体流。 */
class AirPlayAuth {
    private var keyPair = CryptoUtils.generateCurve25519KeyPair()
    private var clientPublicKey: ByteArray? = null
    private var clientSigningKey: ByteArray? = null
    private var verifyCipher: Cipher? = null
    private val fairPlay = FairPlay()

    var sharedSecret: ByteArray? = null
        private set
    var isVerified = false
        private set

    /** 返回身份签名公钥，必须与 Bonjour、/info 和 pair-verify 使用的身份一致。 */
    fun handlePairSetup(body: ByteArray): ByteArray {
        require(body.size == 32) { "旧式 pair-setup 必须为 32 字节" }
        LogEventBus.log("已回复 Ed25519 身份公钥，等待客户端认证")
        return AirPlayCredentials.publicKey
    }

    /** 严格解析两阶段认证，第二阶段延续第一阶段的 CTR 计数器再验签。 */
    fun handlePairVerify(body: ByteArray): ByteArray {
        require(body.size == 68 && body.sliceArray(1..3).all { it == 0.toByte() }) {
            "旧式 pair-verify 必须为 68 字节"
        }
        when (body[0].toInt()) {
            1 -> {
                isVerified = false
                sharedSecret = null
                verifyCipher = null
                keyPair = CryptoUtils.generateCurve25519KeyPair()
                val peerEcdh = body.copyOfRange(4, 36)
                val peerEd = body.copyOfRange(36, 68)
                val secret = CryptoUtils.computeSharedSecret(keyPair.second, peerEcdh)
                val (key, iv) = CryptoUtils.derivePairVerifyAesKeyAndIv(secret)
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val signature = CryptoUtils.signEd25519(
                    AirPlayCredentials.privateKey, keyPair.first + peerEcdh
                )
                val encryptedSignature = cipher.update(signature)
                clientPublicKey = peerEcdh
                clientSigningKey = peerEd
                sharedSecret = secret
                verifyCipher = cipher
                LogEventBus.log("已发送认证第一阶段响应，等待 iPhone 验签")
                return keyPair.first + encryptedSignature
            }
            0 -> {
                val cipher = checkNotNull(verifyCipher) { "缺少 pair-verify 第一阶段" }
                verifyCipher = null
                val signature = cipher.update(body.copyOfRange(4, 68))
                val valid = CryptoUtils.verifyEd25519(
                    checkNotNull(clientSigningKey),
                    checkNotNull(clientPublicKey) + keyPair.first,
                    signature
                )
                if (!valid) {
                    sharedSecret = null
                    throw SecurityException("iPhone 认证签名无效")
                }
                isVerified = true
                LogEventBus.log("iPhone 双向认证完成")
                return ByteArray(0)
            }
            else -> throw IllegalArgumentException("未知 pair-verify 阶段")
        }
    }

    /** 完成真正的 FairPlay 握手，拒绝无效版本、长度和阶段。 */
    fun handleFpSetup(body: ByteArray): ByteArray {
        check(isVerified) { "请先完成 pair-verify" }
        return fairPlay.setup(body)
    }

    /** 从 SETUP 的 ekey 解出基础媒体密钥，不把配对共享密钥直接当成视频密钥。 */
    fun decryptMediaKey(encryptedKey: ByteArray): ByteArray {
        check(isVerified) { "请先完成 pair-verify" }
        return fairPlay.decryptAesKey(encryptedKey)
    }
}
