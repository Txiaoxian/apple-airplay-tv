package com.pureair.tv

import com.github.serezhka.jap2lib.FairPlay
import com.pureair.tv.core.crypto.StreamDecryptor
import com.pureair.tv.core.rtp.AudioReceiver
import com.pureair.tv.core.rtp.AvcConfiguration
import com.pureair.tv.core.rtp.MirrorPacket
import com.pureair.tv.core.rtp.RtpPacket
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.bouncycastle.util.encoders.Base64

class MirrorProtocolTest {
    private val avc = FairPlayFixtures.hex("0142001fffe100046742001f0100026811")

    @Test
    fun fairPlayResponsesMatchPublishedVectors() {
        val fp = FairPlay()
        assertArrayEquals(FairPlayFixtures.response1, fp.setup(FairPlayFixtures.setup1))
        assertArrayEquals(FairPlayFixtures.response2, fp.setup(FairPlayFixtures.setup2))
        assertEquals(16, fp.decryptAesKey(FairPlayFixtures.encryptedKey).size)
        assertArrayEquals(FairPlayFixtures.hex("335c0da6c20d3ab07c3e8338589c7dd6"),
            fp.decryptAesKey(FairPlayFixtures.encryptedKey))
        assertThrows(IllegalArgumentException::class.java) { fp.setup(ByteArray(4)) }
        assertThrows(IllegalArgumentException::class.java) {
            fp.setup(FairPlayFixtures.setup1.copyOf().apply { this[14] = 4 })
        }
        assertThrows(IllegalStateException::class.java) {
            FairPlay().decryptAesKey(FairPlayFixtures.encryptedKey)
        }
    }

    @Test
    fun recordedVideoDecryptsWithUnsignedStreamId() {
        val fp = FairPlay()
        fp.setup(FairPlayFixtures.setup1)
        fp.setup(FairPlayFixtures.setup2)
        val decryptor = StreamDecryptor.forVideo(fp.decryptAesKey(FairPlayFixtures.encryptedKey),
            FairPlayFixtures.sharedSecret, -3907568444900622110L)
        val encrypted = javaClass.getResourceAsStream("/encrypted_payload.base64")!!.use {
            Base64.decode(it.readBytes())
        }
        val clear = decryptor.decrypt(encrypted)
        // 期望值由上游未修改的 Java 实现独立生成，覆盖查找表、派生密钥和完整视频数据。
        assertArrayEquals(FairPlayFixtures.hex("7a8e0dec30ec86dd91618c55de5c03a6dd70930156601ea539b3d3fbbad51122"),
            MessageDigest.getInstance("SHA-256").digest(clear))
    }

    @Test
    fun ctrMatchesNistVectorAcrossUnalignedPackets() {
        val hex = FairPlayFixtures::hex
        val decryptor = StreamDecryptor(hex("2b7e151628aed2a6abf7158809cf4f3c"),
            hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"))
        val encrypted = hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee")
        val expected = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")
        val output = ByteArrayOutputStream()
        var offset = 0
        for (size in listOf(1, 15, 3, 17, 28)) {
            output.write(decryptor.decrypt(encrypted, offset, size))
            offset += size
        }
        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun mirrorHeaderHandlesFragmentedAndConsecutivePackets() {
        fun packet(type: Int, payload: ByteArray) = ByteBuffer.allocate(128)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, payload.size)
                putShort(4, type.toShort())
                putLong(8, (2L shl 32) or 0x80000000L)
                putFloat(56, 1080f)
                putFloat(60, 1920f)
            }.array() + payload
        val stream = object : ByteArrayInputStream(packet(1, avc) + packet(0, byteArrayOf(1, 2, 3))) {
            override fun read(data: ByteArray, offset: Int, length: Int): Int =
                super.read(data, offset, minOf(length, 3))
        }
        val config = MirrorPacket.read(stream)!!
        assertEquals(1, config.type)
        assertEquals(2_500_000L, config.timestampUs)
        assertEquals(1080, config.width)
        assertEquals(1920, config.height)
        assertArrayEquals(avc, config.payload)
        assertArrayEquals(byteArrayOf(1, 2, 3), MirrorPacket.read(stream)!!.payload)
        assertNull(MirrorPacket.read(stream))
        assertThrows(EOFException::class.java) { MirrorPacket.read(byteArrayOf(1).inputStream()) }
        val oversized = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(6 * 1024 * 1024).array()
        assertThrows(IllegalArgumentException::class.java) { MirrorPacket.read(oversized.inputStream()) }
    }

    @Test
    fun avcConfigurationAndCompleteAccessUnitsAreParsed() {
        val config = AvcConfiguration.parse(avc)
        assertEquals(4, config.lengthSize)
        assertArrayEquals(FairPlayFixtures.hex("000000016742001f"), config.sps)
        assertArrayEquals(FairPlayFixtures.hex("000000016811"), config.pps)
        assertArrayEquals(FairPlayFixtures.hex("000000016511000000014122"),
            config.toAnnexB(FairPlayFixtures.hex("000000026511000000024122")))
        assertThrows(IllegalArgumentException::class.java) {
            config.toAnnexB(FairPlayFixtures.hex("000000046511"))
        }
        assertThrows(Exception::class.java) { AvcConfiguration.parse(avc.copyOf(9)) }
    }

    @Test
    fun audioConfigAndRtpBoundariesAreValidated() {
        assertArrayEquals(FairPlayFixtures.hex("f8e85000"), AudioReceiver.codecConfig(8, 44100, 2, 480))
        assertArrayEquals(FairPlayFixtures.hex("1210"), AudioReceiver.codecConfig(4, 44100, 2, 1024))
        assertThrows(IllegalArgumentException::class.java) { AudioReceiver.codecConfig(2, 44100, 2, 352) }
        assertNull(RtpPacket.parse(ByteArray(12), -1, 12))
        assertNull(RtpPacket.parse(ByteArray(12), 1, 12))
        assertNull(RtpPacket.parse(ByteArray(12).apply { this[0] = 0x90.toByte() }))
        assertNull(RtpPacket.parse(ByteArray(13).apply { this[0] = 0xa0.toByte(); this[12] = 2 }))
    }
}
