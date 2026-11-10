package com.pureair.tv

import com.pureair.tv.core.crypto.AirPlayAuth
import com.pureair.tv.core.crypto.AirPlayCredentials
import com.pureair.tv.core.crypto.CryptoUtils
import org.junit.Assert.*
import org.junit.Test

class AirPlayAuthTest {
    @Test
    fun identityKeyIsConsistentAndBothSignaturesAreVerified() {
        val auth = AirPlayAuth()
        val client = PairingClient()
        val advertised = auth.handlePairSetup(ByteArray(32))
        assertArrayEquals(AirPlayCredentials.publicKey, advertised)
        val response = auth.handlePairVerify(client.request)
        assertEquals(96, response.size)
        assertFalse(auth.isVerified)
        val confirmation = client.confirmation(response, advertised)
        assertTrue(auth.handlePairVerify(confirmation).isEmpty())
        assertTrue(auth.isVerified)
    }

    @Test
    fun tamperedClientSignatureDoesNotAuthenticate() {
        val auth = AirPlayAuth()
        val client = PairingClient()
        val response = auth.handlePairVerify(client.request)
        val confirmation = client.confirmation(response, AirPlayCredentials.publicKey)
        confirmation[10] = (confirmation[10].toInt() xor 1).toByte()
        assertThrows(SecurityException::class.java) { auth.handlePairVerify(confirmation) }
        assertFalse(auth.isVerified)
        assertNull(auth.sharedSecret)
    }

    @Test
    fun truncatedUnknownAndOutOfOrderMessagesAreRejected() {
        val auth = AirPlayAuth()
        assertThrows(IllegalArgumentException::class.java) { auth.handlePairSetup(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) {
            auth.handlePairVerify(ByteArray(36).apply { this[0] = 1 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            auth.handlePairVerify(ByteArray(68).apply { this[0] = 2 })
        }
        assertThrows(IllegalStateException::class.java) { auth.handlePairVerify(ByteArray(68)) }
        assertThrows(IllegalStateException::class.java) { auth.handleFpSetup(ByteArray(16)) }
    }

    @Test
    fun invalidCurvePointCannotBecomeAnAllZeroSessionKey() {
        val auth = AirPlayAuth()
        val request = ByteArray(68).apply { this[0] = 1 }
        assertThrows(IllegalStateException::class.java) { auth.handlePairVerify(request) }
        assertNull(auth.sharedSecret)
        assertFalse(auth.isVerified)
    }

    @Test
    fun curveAgreementMatchesOnBothSides() {
        val alice = CryptoUtils.generateCurve25519KeyPair()
        val bob = CryptoUtils.generateCurve25519KeyPair()
        assertArrayEquals(CryptoUtils.computeSharedSecret(alice.second, bob.first),
            CryptoUtils.computeSharedSecret(bob.second, alice.first))
    }
}
