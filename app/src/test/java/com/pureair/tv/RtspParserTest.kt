package com.pureair.tv

import com.pureair.tv.core.rtsp.RtspParser
import com.pureair.tv.core.rtsp.RtspResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertThrows

class RtspParserTest {

    @Test
    fun rejectsTruncatedAndUnboundedBodies() {
        assertThrows(EOFException::class.java) {
            RtspParser.parseRequest("POST /pair-verify RTSP/1.0\r\nContent-Length: 68\r\n\r\nx".byteInputStream())
        }
        for (length in listOf("-1", "999999999", "invalid")) {
            assertThrows(IllegalArgumentException::class.java) {
                RtspParser.parseRequest("POST /pair-verify RTSP/1.0\r\nContent-Length: $length\r\n\r\n".byteInputStream())
            }
        }
    }

    @Test
    fun testParseOptionsRequest() {
        val rawRequest = "OPTIONS * RTSP/1.0\r\n" +
                "CSeq: 1\r\n" +
                "User-Agent: AirPlay/220.68\r\n" +
                "\r\n"

        val inputStream = ByteArrayInputStream(rawRequest.toByteArray(Charsets.UTF_8))
        val request = RtspParser.parseRequest(inputStream)

        assertNotNull(request)
        assertEquals("OPTIONS", request?.method)
        assertEquals("*", request?.uri)
        assertEquals("RTSP/1.0", request?.version)
        assertEquals("1", request?.getCSeq())
        assertEquals(0, request?.body?.size)
    }

    @Test
    fun testParsePostRequestWithBody() {
        val payload = "Hello-AirPlay-Body"
        val rawRequest = "POST /pair-setup RTSP/1.0\r\n" +
                "CSeq: 2\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${payload.length}\r\n" +
                "\r\n" +
                payload

        val inputStream = ByteArrayInputStream(rawRequest.toByteArray(Charsets.UTF_8))
        val request = RtspParser.parseRequest(inputStream)

        assertNotNull(request)
        assertEquals("POST", request?.method)
        assertEquals("/pair-setup", request?.uri)
        assertEquals("2", request?.getCSeq())
        assertEquals(payload.length, request?.body?.size)
        assertEquals(payload, request?.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun testRtspResponseSerialization() {
        val response = RtspResponse(200, "OK")
        response.setCSeq("3")
        response.headers["Public"] = "OPTIONS, SETUP, RECORD"
        val responseBytes = response.toByteArray()
        val responseStr = String(responseBytes, Charsets.UTF_8)

        assertTrue(responseStr.startsWith("RTSP/1.0 200 OK\r\n"))
        assertTrue(responseStr.contains("CSeq: 3\r\n"))
        assertTrue(responseStr.contains("Public: OPTIONS, SETUP, RECORD\r\n"))
        assertTrue(responseStr.contains("Server: AirTunes/220.68\r\n"))
    }
}
