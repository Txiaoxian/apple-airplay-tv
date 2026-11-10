package com.pureair.tv.core.crypto

import org.bouncycastle.util.encoders.Base64

/**
 * AirPlay 全局身份凭据管理器
 * 提供全局唯一的 Ed25519 密钥对，确保 mDNS 广播、/info 查询以及 /pair-verify 签名完全匹配
 */
object AirPlayCredentials {

    // 固定的 32 字节私钥种子，保证设备公钥永久固定不变，广播与接口完全对齐
    private val SEED_HEX = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    private val seedBytes: ByteArray = SEED_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // 全局唯一确定性生成的 Ed25519 秘钥对
    private val keyPair: Pair<ByteArray, ByteArray> = CryptoUtils.getEd25519KeyPairFromSeed(seedBytes)

    /**
     * 32 字节 Ed25519 公钥 (用于广播和让客户端验签)
     */
    val publicKey: ByteArray get() = keyPair.first

    /**
     * 32 字节 Ed25519 私钥 (用于生成 pair-verify 数字签名)
     */
    val privateKey: ByteArray get() = keyPair.second

    /**
     * mDNS / Bonjour 广播所需的十六进制公钥字符串
     */
    val publicKeyHex: String by lazy {
        publicKey.joinToString("") { "%02x".format(it) }
    }

    /**
     * /info Plist <data> 字段所需的 Base64 公钥字符串
     */
    val publicKeyBase64: String by lazy {
        Base64.toBase64String(publicKey)
    }

    const val FIXED_PI = "2e388006-13ba-4041-9a67-25b1a4a73ae1"
}
