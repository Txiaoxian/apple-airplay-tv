package com.pureair.tv.core.rtsp

import android.util.Log
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.pureair.tv.App
import com.pureair.tv.R
import com.pureair.tv.core.crypto.AirPlayAuth
import com.pureair.tv.core.crypto.AirPlayCredentials
import com.pureair.tv.core.crypto.CryptoUtils
import com.pureair.tv.util.DeviceUtils
import com.pureair.tv.core.crypto.StreamDecryptor
import com.pureair.tv.core.rtp.AudioStreamFormat
import com.pureair.tv.util.LogEventBus
import com.pureair.tv.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.Socket
import java.util.UUID

interface RtspSessionListener {
    fun onSessionStarted(sessionId: String)
    fun onVideoSetup(sessionId: String, peer: InetAddress, decryptor: StreamDecryptor): Int
    fun onAudioSetup(sessionId: String, peer: InetAddress, key: ByteArray, iv: ByteArray,
                     format: AudioStreamFormat, controlPort: Int): Pair<Int, Int>
    fun onAudioStopped(sessionId: String)
    fun onVideoStopped(sessionId: String)
    fun onAudioFlush(sessionId: String, nextSequence: Int?)
    fun onStreamingStarted(sessionId: String, videoPort: Int, audioPort: Int)
    fun onStreamingStopped(sessionId: String)
    fun onSessionClosed(sessionId: String)
}

/** 管理一个 RTSP 连接的认证、FairPlay、分阶段 SETUP 和媒体资源生命周期。 */
class RtspSession(private val socket: Socket, private val listener: RtspSessionListener? = null) {
    val sessionId: String = UUID.randomUUID().toString()
    val auth = AirPlayAuth()
    @Volatile private var isRunning = true
    private var isStreaming = false
    private var mediaKey: ByteArray? = null
    private var mediaIv: ByteArray? = null
    private var encryptedMediaKey: ByteArray? = null
    private var channels: SessionChannels? = null
    private var videoPort = 0
    private var audioPort = 0
    private var videoStreamId: Long? = null
    private var audioFormat: AudioStreamFormat? = null
    private var audioControlPort = 0
    private var localAudioControlPort = 0
    private var closeAfterResponse = false

    /** 保持同一连接的报文顺序，解析或网络失败时关闭本会话及其辅助通道。 */
    suspend fun handle() = withContext(Dispatchers.IO) {
        LogEventBus.log("客户端已连入: ${socket.remoteSocketAddress}")
        listener?.onSessionStarted(sessionId)
        try {
            val input = socket.getInputStream().buffered(16384)
            val output = socket.getOutputStream().buffered(16384)
            while (isRunning && !socket.isClosed) {
                val request = RtspParser.parseRequest(input) ?: break
                LogEventBus.log(">> ${request.method} ${request.uri} (${request.body.size} 字节)")
                val response = processRequest(request)
                output.write(response.toByteArray())
                output.flush()
                LogEventBus.log("<< ${response.statusCode} ${response.statusText}")
                // 可选控制请求未实现时保留连接，认证、报文或状态错误仍结束会话。
                if (closeAfterResponse ||
                    (response.statusCode >= 400 && response.statusCode != 404 && response.statusCode != 501)) break
            }
        } catch (e: Exception) {
            Log.w("RtspSession", "连接结束: $sessionId", e)
            LogEventBus.log("连接结束: ${e.message}")
        } finally {
            close()
        }
    }

    /** 错误请求返回明确状态，不能继续用 200 掩盖认证或协商失败。 */
    internal fun processRequest(request: RtspRequest): RtspResponse {
        val response = RtspResponse(protocol = request.version)
        response.setCSeq(request.getCSeq())
        try {
            val path = request.uri.substringBefore('?')
            when {
                request.method == "GET" && path == "/info" -> handleGetInfo(response)
                request.method == "OPTIONS" ->
                    response.headers["Public"] = "OPTIONS, GET, POST, SETUP, RECORD, FLUSH, GET_PARAMETER, SET_PARAMETER, TEARDOWN"
                request.method == "POST" -> {
                    response.headers["Content-Type"] = "application/octet-stream"
                    response.body = when (path) {
                        "/pair-setup" -> auth.handlePairSetup(request.body)
                        "/pair-verify" -> auth.handlePairVerify(request.body)
                        "/fp-setup" -> auth.handleFpSetup(request.body)
                        "/audioMode" -> handleAudioMode(request.body)
                        "/feedback" -> ByteArray(0)
                        else -> return errorResponse(request, 404, "Not Found")
                    }
                }
                request.method == "SETUP" -> handleSetup(request, response)
                request.method == "RECORD" -> {
                    // iPhone 可在会话 SETUP 后、流 SETUP 前发送 RECORD，此处只确认会话已初始化。
                    check(channels != null) { "尚未完成会话 SETUP" }
                    response.headers["Audio-Latency"] = "0"
                }
                request.method == "GET_PARAMETER" -> {
                    response.headers["Content-Type"] = "text/parameters"
                    response.body = "volume: 0.000000\r\n".toByteArray()
                }
                request.method == "TEARDOWN" -> handleTeardown(request)
                request.method == "FLUSH" -> {
                    check(auth.isVerified) { "请先完成双向认证" }
                    val rtpInfo = request.headers.entries.firstOrNull { it.key.equals("RTP-Info", true) }?.value
                    val value = rtpInfo?.split(';')?.firstOrNull { it.trim().startsWith("seq=") }
                        ?.trim()?.substringAfter("seq=")
                    val sequence = value?.toIntOrNull()
                    require(value == null || sequence != null && sequence in 0..65535) { "FLUSH seq 无效" }
                    listener?.onAudioFlush(sessionId, sequence)
                }
                request.method == "SET_PARAMETER" -> Unit
                else -> return errorResponse(request, 501, "Not Implemented")
            }
            return response
        } catch (e: SecurityException) {
            LogEventBus.log("认证失败: ${e.message}")
            return errorResponse(request, 403, "Forbidden")
        } catch (e: IllegalArgumentException) {
            LogEventBus.log("报文无效: ${e.message}")
            return errorResponse(request, 400, "Bad Request")
        } catch (e: IllegalStateException) {
            LogEventBus.log("协商阶段错误: ${e.message}")
            return errorResponse(request, 455, "Method Not Valid in This State")
        } catch (e: Exception) {
            Log.e("RtspSession", "处理请求失败", e)
            LogEventBus.log("处理请求失败: ${e.message}")
            return errorResponse(request, 500, "Internal Server Error")
        }
    }

    /** 统一错误响应的协议与 CSeq，便于发送端和日志定位失败阶段。 */
    private fun errorResponse(request: RtspRequest, code: Int, text: String): RtspResponse =
        RtspResponse(code, text, request.version).apply { setCSeq(request.getCSeq()) }

    /** 确认音频策略提示，保留已有媒体通道和解密计数器，不把模式通知当作重新建流。 */
    private fun handleAudioMode(body: ByteArray): ByteArray {
        check(auth.isVerified) { "请先完成双向认证" }
        val settings = try {
            PropertyListParser.parse(body)
        } catch (e: Exception) {
            throw IllegalArgumentException("audioMode Plist 无效", e)
        }
        require(settings is NSDictionary) { "audioMode 必须为 Plist 字典" }
        val mode = settings.objectForKey("audioMode") as? NSString
        require(mode != null && mode.content.isNotBlank()) { "audioMode 必须为非空字符串" }
        LogEventBus.log("已确认音频模式通知，保持当前投屏")
        return ByteArray(0)
    }

    /** 使用二进制 Plist 发布设备、显示和音频能力，身份公钥与签名保持一致。 */
    private fun handleGetInfo(response: RtspResponse) {
        val info = NSDictionary().apply {
            put("deviceID", NetworkUtils.getMacAddress())
            put("features", 130367356646L)
            put("model", "AppleTV5,3")
            put("name", DeviceUtils.getDeviceDisplayName())
            put("pi", AirPlayCredentials.FIXED_PI)
            put("psi", AirPlayCredentials.FIXED_PI)
            put("pk", AirPlayCredentials.publicKey)
            put("sourceVersion", "220.68")
            put("statusFlags", 4)
            put("vv", 2)
            put("keepAliveSendStatsAsBody", true)
        }
        val display = NSDictionary().apply {
            put("uuid", AirPlayCredentials.FIXED_PI)
            put("width", 1920)
            put("height", 1080)
            put("widthPixels", 1920)
            put("heightPixels", 1080)
            put("widthPhysical", 0)
            put("heightPhysical", 0)
            put("rotation", false)
            put("refreshRate", 1.0 / 60.0)
            put("maxFPS", 30)
            put("features", 14)
            put("overscanned", false)
        }
        info.put("displays", NSArray(1).apply { setValue(0, display) })
        val formats = NSArray(2)
        listOf(100, 101).forEachIndexed { index, type ->
            formats.setValue(index, NSDictionary().apply {
                put("type", type)
            put("audioInputFormats", AudioStreamFormat.SUPPORTED_FORMATS)
            put("audioOutputFormats", AudioStreamFormat.SUPPORTED_FORMATS)
            })
        }
        info.put("audioFormats", formats)
        response.headers["Content-Type"] = "application/x-apple-binary-plist"
        response.body = BinaryPropertyListWriter.writeToArray(info)
    }

    /** 先协商密钥与时钟，再按 streams 内容建立真实音视频端口；不返回固定假端口。 */
    private fun handleSetup(request: RtspRequest, response: RtspResponse) {
        check(auth.isVerified) { "请先完成双向认证" }
        require(request.body.isNotEmpty()) { "SETUP 缺少 Plist" }
        val root = PropertyListParser.parse(request.body) as? NSDictionary
            ?: throw IllegalArgumentException("SETUP 必须为 Plist 字典")
        if (root.containsKey("ekey")) {
            val encrypted = (root.objectForKey("ekey") as? NSData)?.bytes()
                ?: throw IllegalArgumentException("ekey 类型错误")
            val iv = (root.objectForKey("eiv") as? NSData)?.bytes()
                ?: throw IllegalArgumentException("缺少 eiv")
            require(iv.size == 16) { "eiv 长度错误" }
            // 重复会话 SETUP 不影响现有流；新媒体密钥只能在旧流停止后替换。
            if (encryptedMediaKey?.contentEquals(encrypted) != true || mediaIv?.contentEquals(iv) != true) {
                check(videoPort == 0 && audioPort == 0) { "更换媒体密钥前请先停止旧流" }
                mediaKey = auth.decryptMediaKey(encrypted)
                mediaIv = iv
                encryptedMediaKey = encrypted.copyOf()
            }
        }
        val key = checkNotNull(mediaKey) { "尚未收到 FairPlay 媒体密钥" }
        val secret = checkNotNull(auth.sharedSecret)
        if (channels == null) {
            val peerTiming = (root.objectForKey("timingPort") as? NSNumber)?.intValue() ?: 0
            require(peerTiming in 0..65535) { "timingPort 无效" }
            channels = SessionChannels(socket.inetAddress, peerTiming).also { it.start() }
        }
        val result = NSDictionary().apply {
            put("eventPort", channels!!.eventPort)
            put("timingPort", channels!!.timingPort)
        }
        val streamsValue = root.objectForKey("streams")
        require(streamsValue == null || streamsValue is NSArray) { "streams 类型错误" }
        if (streamsValue is NSArray) {
            require(streamsValue.count() in 1..2) { "不支持的媒体流数量" }
            val accepted = NSArray(streamsValue.count())
            streamsValue.array.forEachIndexed { index, value ->
                val stream = value as? NSDictionary ?: throw IllegalArgumentException("媒体流类型错误")
                val type = (stream.objectForKey("type") as? NSNumber)?.intValue()
                    ?: throw IllegalArgumentException("缺少媒体流类型")
                val entry = NSDictionary().apply { put("type", type) }
                val target = checkNotNull(listener) { "媒体服务未就绪" }
                when (type) {
                    110 -> {
                        val id = (stream.objectForKey("streamConnectionID") as? NSNumber)?.longValue()
                            ?: throw IllegalArgumentException("缺少 streamConnectionID")
                        // 相同流的 SETUP 可重试；新流标识才重建 CTR，不能因重复请求重置计数器。
                        if (videoPort == 0 || id != videoStreamId) {
                            if (videoPort != 0) target.onVideoStopped(sessionId)
                            videoPort = target.onVideoSetup(sessionId, socket.inetAddress,
                                StreamDecryptor.forVideo(key, secret, id))
                            videoStreamId = id
                            isStreaming = true
                            target.onStreamingStarted(sessionId, videoPort, audioPort)
                        }
                        require(videoPort in 1..65535) { "视频端口无效" }
                        entry.put("dataPort", videoPort)
                        LogEventBus.log("视频通道已就绪: $videoPort")
                    }
                    96 -> {
                        val format = AudioStreamFormat.fromPlist(stream)
                        val remotePort = (stream.objectForKey("controlPort") as? NSNumber)?.longValue() ?: 0
                        require(remotePort in 0..65535) { "音频 controlPort 无效" }
                        LogEventBus.log("音频格式: ct=${format.compression} ${format.rate}Hz ${format.channels}声道 ${format.bits}位 spf=${format.samples}")
                        if (audioPort > 0 && (format != audioFormat || remotePort.toInt() != audioControlPort)) {
                            target.onAudioStopped(sessionId)
                            audioPort = 0
                        }
                        if (audioPort == 0) {
                            val ports = target.onAudioSetup(sessionId, socket.inetAddress,
                                CryptoUtils.sha512(key + secret).copyOf(16), checkNotNull(mediaIv),
                                format, remotePort.toInt())
                            audioPort = ports.first
                            localAudioControlPort = ports.second
                            audioFormat = format
                            audioControlPort = remotePort.toInt()
                        }
                        require(audioPort in 1..65535 && localAudioControlPort in 1..65535) { "音频端口无效" }
                        entry.put("dataPort", audioPort)
                        entry.put("controlPort", localAudioControlPort)
                        LogEventBus.log("音频通道已就绪: $audioPort")
                    }
                    else -> throw IllegalArgumentException("不支持的媒体流类型: $type")
                }
                accepted.setValue(index, entry)
            }
            result.put("streams", accepted)
        }
        response.headers["Session"] = sessionId
        response.headers["Content-Type"] = "application/x-apple-binary-plist"
        response.body = BinaryPropertyListWriter.writeToArray(result)
    }

    /** 带 streams 的 TEARDOWN 只停止指定流，手机切换应用后可在原会话内重新 SETUP。 */
    private fun handleTeardown(request: RtspRequest) {
        if (request.body.isEmpty()) {
            closeAfterResponse = true
            return
        }
        check(auth.isVerified) { "请先完成双向认证" }
        val root = PropertyListParser.parse(request.body) as? NSDictionary
            ?: throw IllegalArgumentException("TEARDOWN 必须为 Plist 字典")
        val value = root.objectForKey("streams")
        if (value == null) {
            closeAfterResponse = true
            return
        }
        require(value is NSArray && value.count() <= 2) { "TEARDOWN streams 无效" }
        if (value.count() == 0) {
            closeAfterResponse = true
            return
        }
        val types = value.array.map {
            val stream = it as? NSDictionary ?: throw IllegalArgumentException("TEARDOWN 媒体流无效")
            (stream.objectForKey("type") as? NSNumber)?.intValue()
                ?: throw IllegalArgumentException("缺少 TEARDOWN 流类型")
        }
        require(types.all { it == 96 || it == 110 }) { "未知 TEARDOWN 流类型" }
        if (96 in types && audioPort != 0) {
            listener?.onAudioStopped(sessionId)
            audioPort = 0
            localAudioControlPort = 0
            audioFormat = null
        }
        if (110 in types && videoPort != 0) {
            listener?.onVideoStopped(sessionId)
            videoPort = 0
            videoStreamId = null
            isStreaming = false
        }
        LogEventBus.log("已停止指定媒体流，保留投屏会话")
    }

    /** 只清理本连接的资源；探测 /info 的短连接不能中止其他客户端的投屏。 */
    @Synchronized
    fun close() {
        if (!isRunning) return
        isRunning = false
        if (auth.sharedSecret != null) {
            LogEventBus.log(if (auth.isVerified) "投屏会话已结束" else "客户端已断开，双向认证未完成")
        }
        channels?.close()
        if (isStreaming) listener?.onStreamingStopped(sessionId)
        listener?.onSessionClosed(sessionId)
        socket.close()
    }
}
