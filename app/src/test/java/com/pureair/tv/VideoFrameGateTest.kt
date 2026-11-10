package com.pureair.tv

import com.pureair.tv.core.player.VideoFrameGate
import com.pureair.tv.core.rtp.NalType
import org.junit.Assert.*
import org.junit.Test

class VideoFrameGateTest {
    @Test
    fun decoderResetWaitsForIdrEvenWhenParameterSetsComeFirst() {
        val gate = VideoFrameGate()
        val delta = FairPlayFixtures.hex("00000001410102")
        assertNull(gate.inspect(delta))
        assertEquals(NalType.IDR, gate.inspect(
            FairPlayFixtures.hex("00000001670100000001680200000001650304")))
        assertEquals(NalType.OTHER, gate.inspect(delta))
        gate.reset()
        assertNull(gate.inspect(delta))
        assertEquals(NalType.IDR, gate.inspect(FairPlayFixtures.hex("000001650405")))
    }
}
