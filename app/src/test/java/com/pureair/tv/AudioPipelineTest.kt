package com.pureair.tv

import com.beatofthedrum.alacdecoder.AlacDecoder
import com.dd.plist.NSDictionary
import com.pureair.tv.core.rtp.AudioJitterBuffer
import com.pureair.tv.core.rtp.AudioReceiver
import com.pureair.tv.core.rtp.AudioStreamFormat
import com.pureair.tv.core.rtp.RtpPacket
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class AudioPipelineTest {
    private fun packet(sequence: Int) = RtpPacket(2, false, false, 0, false, 96,
        sequence, sequence.toLong() * 480, 0, byteArrayOf(1, 2))

    @Test
    fun repeatedEldPacketsArePlayedOnceAndReordered() {
        val buffer = AudioJitterBuffer(44100, 480)
        listOf(0, 0, 1, 0, 1, 2, 1, 2, 3).forEach { buffer.offer(packet(it), 0) }
        assertNull(buffer.poll(30))
        assertEquals(listOf(0, 1, 2, 3),
            List(4) { buffer.poll(60)!!.packet.sequenceNumber })
        assertNull(buffer.poll(60))
    }

    @Test
    fun sequenceWrapAndLateRetransmissionsDoNotReplayAudio() {
        val buffer = AudioJitterBuffer(44100, 480)
        listOf(65534, 0, 65535, 1).forEach { buffer.offer(packet(it), 0) }
        assertEquals(listOf(65534, 65535, 0, 1),
            List(4) { buffer.poll(60)!!.packet.sequenceNumber })
        buffer.offer(packet(65535), 70)
        assertNull(buffer.poll(70))
        buffer.offer(packet(2), 70)
        assertEquals(2, buffer.poll(70)!!.packet.sequenceNumber)
    }

    @Test
    fun retransmittedGapIsFilledAndLostGapHasABoundedDeadline() {
        val buffer = AudioJitterBuffer(44100, 480)
        buffer.offer(packet(10), 0)
        buffer.offer(packet(12), 0)
        assertEquals(10, buffer.poll(60)!!.packet.sequenceNumber)
        assertNull(buffer.poll(60))
        assertEquals(11 to 1, buffer.missingRange())
        buffer.offer(packet(11), 70)
        assertEquals(11, buffer.poll(70)!!.packet.sequenceNumber)
        assertEquals(12, buffer.poll(70)!!.packet.sequenceNumber)
        buffer.offer(packet(14), 75)
        assertNull(buffer.poll(75))
        val ready = buffer.poll(116)!!
        assertEquals(14, ready.packet.sequenceNumber)
        assertEquals(1, ready.missing)
        assertArrayEquals(FairPlayFixtures.hex("80d50001000b0001"), AudioReceiver.resendRequest(11, 1, 1))
    }

    @Test
    fun flushDropsOldPacketsAndDiscontinuityRebuffers() {
        val buffer = AudioJitterBuffer(44100, 480)
        buffer.offer(packet(20), 0)
        buffer.clear(100)
        buffer.offer(packet(99), 0)
        assertNull(buffer.poll(100))
        buffer.offer(packet(100), 100)
        assertNull(buffer.poll(120))
        assertEquals(100, buffer.poll(160)!!.packet.sequenceNumber)
        buffer.offer(packet(1000), 200)
        assertNull(buffer.poll(220))
        assertEquals(1000, buffer.poll(260)!!.packet.sequenceNumber)
    }

    @Test
    fun formatBitmaskSuppliesRateDepthAndCodecDefaults() {
        fun format(code: Long) = AudioStreamFormat.fromPlist(NSDictionary().apply { put("audioFormat", code) })
        assertEquals(AudioStreamFormat(2, 48000, 2, 16, 352), format(0x100000))
        assertEquals(AudioStreamFormat(2, 44100, 2, 24, 352), format(0x80000))
        assertEquals(AudioStreamFormat(4, 48000, 2, 16, 1024), format(0x800000))
        assertEquals(AudioStreamFormat(8, 48000, 1, 16, 480), format(0x100000000L))
        assertEquals(AudioStreamFormat(1, 44100, 1, 24, 352), format(0x1000))
        assertThrows(IllegalArgumentException::class.java) { format(0x10000001) }
        assertThrows(IllegalArgumentException::class.java) {
            AudioStreamFormat.fromPlist(NSDictionary().apply {
                put("audioFormat", 0x2000000); put("sr", 44100)
            })
        }
    }

    @Test
    fun pcmAndUncompressedAlacPreserveSignedSamples() {
        assertArrayEquals(FairPlayFixtures.hex("0000ff7f0080ffff"),
            AudioReceiver.pcmTo16(FairPlayFixtures.hex("00007fff8000ffff"), 16))
        assertArrayEquals(FairPlayFixtures.hex("3412cbed4365ffff"),
            AlacDecoder(24, 2, 352, 44100).decode(
                FairPlayFixtures.hex("200012000000042468addb9754ca8643ffffffc0")))
        assertArrayEquals(FairPlayFixtures.hex("0000ff7f0080ffff"),
            AlacDecoder(16, 2, 352, 44100).decode(
                FairPlayFixtures.hex("200012000000040000ffff0001ffffc0")))
    }

    @Test
    fun compressedAlacMatchesIndependentFfmpegDecode() {
        val hashes = mapOf(
            16 to "e86f428c92597e08850a98ac2f1bdfbeac444823b163733ad7aac1092c4f23bf",
            24 to "1658bc3730989c9b469eb5281afed9295e5bf7b7acf3299104f0dde5ef1329e3")
        for ((bits, hash) in hashes) {
            val frame = javaClass.getResourceAsStream("/alac-$bits-frame.base64")!!.use { Base64.decode(it.readBytes()) }
            val pcm = AlacDecoder(bits, 2, 4096, 44100).decode(frame)
            assertEquals(16384, pcm.size)
            assertArrayEquals(FairPlayFixtures.hex(hash), MessageDigest.getInstance("SHA-256").digest(pcm))
            assertThrows(IllegalArgumentException::class.java) {
                AlacDecoder(bits, 2, 4096, 44100).decode(frame.copyOf(10))
            }
        }
    }
}
