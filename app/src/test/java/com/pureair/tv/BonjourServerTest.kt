package com.pureair.tv

import com.pureair.tv.core.bonjour.BonjourServer
import com.pureair.tv.core.crypto.AirPlayCredentials
import com.pureair.tv.util.NetworkUtils
import org.junit.Assert.*
import org.junit.Test

class BonjourServerTest {
    @Test
    fun discoveryUsesTheSigningIdentity() {
        val mac = "5A:91:A0:11:22:33"
        val txt = BonjourServer.buildAirPlayTxtRecord(mac)
        assertEquals(mac, txt["deviceid"])
        assertEquals("0x5A7FFEE6,0x1E", txt["features"])
        assertEquals(AirPlayCredentials.publicKeyHex, txt["pk"])
        assertEquals(AirPlayCredentials.FIXED_PI, txt["pi"])
        assertEquals(txt["pi"], txt["psi"])
    }

    @Test
    fun fallbackDeviceIdIsAValidMacAddress() {
        assertTrue(NetworkUtils.getMacAddress().matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
    }
}
