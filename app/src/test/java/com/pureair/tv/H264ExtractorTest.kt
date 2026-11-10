package com.pureair.tv

import com.pureair.tv.core.rtp.H264Extractor
import com.pureair.tv.core.rtp.NalType
import com.pureair.tv.core.rtp.RtpPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264ExtractorTest {

    @Test
    fun testRtpPacketParse() {
        val raw = ByteArray(20)
        raw[0] = 0x80.toByte() // Version 2
        raw[1] = 96.toByte()   // Payload type 96 (H264 dynamic)
        raw[2] = 0x01
        raw[3] = 0x23          // Sequence number 0x0123 = 291
        raw[12] = 0xAA.toByte() // Payload start

        val packet = RtpPacket.parse(raw)
        assertNotNull(packet)
        assertEquals(2, packet?.version)
        assertEquals(96, packet?.payloadType)
        assertEquals(291, packet?.sequenceNumber)
        assertEquals(8, packet?.payload?.size)
    }

    @Test
    fun testExtractSpsAndPpsNalu() {
        val extractor = H264Extractor()

        // 构造 Annex-B 码流:
        // 1. [00 00 00 01 67 ...] (SPS, 0x67 & 0x1F = 7)
        // 2. [00 00 00 01 68 ...] (PPS, 0x68 & 0x1F = 8)
        // 3. [00 00 00 01 65 ...] (IDR, 0x65 & 0x1F = 5)
        // 4. [00 00 00 01 ...] 结尾标记
        val stream = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x28, // SPS
            0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(), // PPS
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00, // IDR
            0x00, 0x00, 0x00, 0x01, 0x41                     // Non-IDR next
        )

        val units = extractor.extractNalUnits(stream)

        assertEquals(3, units.size)
        assertEquals(NalType.SPS, units[0].type)
        assertEquals(NalType.PPS, units[1].type)
        assertEquals(NalType.IDR, units[2].type)

        assertNotNull(extractor.sps)
        assertNotNull(extractor.pps)
        assertTrue(extractor.sps!!.isNotEmpty())
        assertTrue(extractor.pps!!.isNotEmpty())
    }
}
