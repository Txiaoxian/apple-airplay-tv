package com.pureair.tv

import com.pureair.tv.ui.fitVideoSize
import org.junit.Assert.*
import org.junit.Test

class VideoLayoutTest {
    @Test
    fun portraitLandscapeAndSquareVideoFitWithoutStretching() {
        assertEquals(496 to 1080, fitVideoSize(1206, 2622, 1920, 1080))
        assertEquals(1920 to 883, fitVideoSize(2622, 1206, 1920, 1080))
        assertEquals(1080 to 1080, fitVideoSize(1080, 1080, 1920, 1080))
        assertEquals(1920 to 1080, fitVideoSize(1920, 1080, 1920, 1080))
    }

    @Test
    fun layoutAdaptsToContainerChangesAndLargeDimensions() {
        assertEquals(993 to 2160, fitVideoSize(1206, 2622, 3840, 2160))
        assertEquals(1440 to 810, fitVideoSize(1920, 1080, 1440, 1080))
        assertEquals(1920 to 1080, fitVideoSize(1920000000, 1080000000, 1920, 1080))
        assertEquals(1 to 1080, fitVideoSize(1, Int.MAX_VALUE, 1920, 1080))
    }

    @Test
    fun unmeasuredOrInvalidSizesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { fitVideoSize(0, 100, 1920, 1080) }
        assertThrows(IllegalArgumentException::class.java) { fitVideoSize(100, 100, 0, 1080) }
        assertThrows(IllegalArgumentException::class.java) { fitVideoSize(100, -1, 1920, 1080) }
    }
}
