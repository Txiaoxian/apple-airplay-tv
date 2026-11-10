package com.pureair.tv.core.rtsp

import java.io.InputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/**
 * RTSP 请求报文
 */
data class RtspRequest(
    val method: String,
    val uri: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    fun getCSeq(): String = headers["cseq"] ?: "0"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtspRequest
        return method == other.method && uri == other.uri && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * RTSP 响应报文
 */
data class RtspResponse(
    val statusCode: Int = 200,
    val statusText: String = "OK",
    var protocol: String = "RTSP/1.0",
    val headers: MutableMap<String, String> = TreeMap(String.CASE_INSENSITIVE_ORDER),
    var body: ByteArray? = null
) {
    init {
        headers["Server"] = "AirTunes/220.68"
    }

    fun setCSeq(cseq: String) {
        if (cseq.isNotEmpty() && cseq != "0") {
            headers["CSeq"] = cseq
        }
    }

    fun toByteArray(): ByteArray {
        val sb = StringBuilder()
        sb.append(protocol).append(" ").append(statusCode).append(" ").append(statusText).append("\r\n")
        val payload = body
        if (payload != null && payload.isNotEmpty()) {
            headers["Content-Length"] = payload.size.toString()
        } else {
            headers["Content-Length"] = "0"
        }

        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")

        val headerBytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        return if (payload != null && payload.isNotEmpty()) {
            headerBytes + payload
        } else {
            headerBytes
        }
    }
}

/**
 * RTSP 报文解析工具
 */
object RtspParser {

    /**
     * 从输入流中读取并解析一条完整的 RTSP Request
     */
    fun parseRequest(inputStream: InputStream): RtspRequest? {
        val headerLines = mutableListOf<String>()
        val lineBuffer = StringBuilder()
        var headerSize = 0

        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                if (headerLines.isEmpty() && lineBuffer.isEmpty()) return null
                throw EOFException("RTSP 请求头被截断")
            }
            require(++headerSize <= 16384) { "RTSP 请求头超长" }
            if (b == '\n'.code) {
                val line = lineBuffer.toString().trimEnd('\r')
                lineBuffer.setLength(0)
                if (line.isEmpty()) {
                    // 空行，表示 Header 结束
                    break
                }
                headerLines.add(line)
            } else {
                lineBuffer.append(b.toChar())
            }
        }

        if (headerLines.isEmpty()) return null

        // 解析请求行: 例如 "OPTIONS * RTSP/1.0"
        val requestLine = headerLines[0]
        val parts = requestLine.split(" ", limit = 3)
        require(parts.size == 3) { "RTSP 请求行无效" }

        val method = parts[0].trim().uppercase(java.util.Locale.ROOT)
        val uri = parts[1].trim()
        val version = parts[2].trim()

        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx != -1) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }

        val contentLength = headers["content-length"]?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("Content-Length 无效")
        } ?: 0
        require(contentLength in 0..8 * 1024 * 1024) { "RTSP 请求体长度无效" }
        val body = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
                if (read == -1) throw EOFException("RTSP 请求体被截断")
                totalRead += read
            }
            buffer
        } else {
            ByteArray(0)
        }

        return RtspRequest(method, uri, version, headers, body)
    }
}
