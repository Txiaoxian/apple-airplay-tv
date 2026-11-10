package com.pureair.tv.core.rtp

/** 短缓冲按 RTP 序号去重和重排；AAC-ELD 的同一包可能重复发送三次。 */
class AudioJitterBuffer(rate: Int, samples: Int) {
    init { require(rate > 0 && samples > 0) }
    data class ReadyPacket(val packet: RtpPacket, val missing: Int)
    private val targetPackets = ((rate * 60L + samples * 1000 - 1) / (samples * 1000))
        .toInt().coerceIn(2, 32)
    private val packets = HashMap<Int, RtpPacket>()
    private var expected: Int? = null
    private var firstArrival: Long? = null
    private var gapSince: Long? = null
    private var started = false
    private var flushBoundary = false

    private fun distance(sequence: Int, reference: Int): Int =
        ((sequence - reference + 32768) and 65535) - 32768

    /** 最多保留 128 包；大幅跳号重新预缓冲，不把旧应用的音频留到新应用播放。 */
    @Synchronized
    fun offer(packet: RtpPacket, nowMs: Long) {
        if (expected == null) expected = packet.sequenceNumber
        val delta = distance(packet.sequenceNumber, expected!!)
        if (delta < 0) {
            if (started || flushBoundary || delta < -128) return
            expected = packet.sequenceNumber
        } else if (delta >= 128) {
            clear()
            expected = packet.sequenceNumber
        }
        packets.keys.removeAll { distance(it, expected!!) !in 0..127 }
        if (firstArrival == null) firstArrival = nowMs
        packets.putIfAbsent(packet.sequenceNumber, packet)
    }

    /** 先等待约 60ms 预缓冲；丢包最多再等 40ms 重传，超时交给播放端补静音。 */
    @Synchronized
    fun poll(nowMs: Long): ReadyPacket? {
        if (packets.isEmpty()) return null
        if (!started) {
            if (packets.size < targetPackets && nowMs - firstArrival!! < 60) return null
            started = true
        }
        var missing = 0
        if (!packets.containsKey(expected)) {
            if (gapSince == null) gapSince = nowMs
            if (nowMs - gapSince!! < 40 && packets.size < 127) return null
            val next = packets.keys.minBy { distance(it, expected!!) }
            missing = distance(next, expected!!).coerceAtLeast(0)
            expected = next
        }
        val packet = packets.remove(expected) ?: return null
        expected = (expected!! + 1) and 65535
        gapSince = null
        return ReadyPacket(packet, missing)
    }

    /** 返回当前缺口，供控制通道请求发送端重发，不含任何媒体内容。 */
    @Synchronized
    fun missingRange(): Pair<Int, Int>? {
        if (!started || packets.isEmpty() || packets.containsKey(expected)) return null
        val count = packets.keys.minOf { distance(it, expected!!) }
        return if (count > 0) expected!! to count else null
    }

    /** FLUSH 的 nextSequence 是下一包；有边界时拒绝延迟到达的旧包。 */
    @Synchronized
    fun clear(nextSequence: Int? = null) {
        require(nextSequence == null || nextSequence in 0..65535)
        packets.clear()
        expected = nextSequence
        firstArrival = null
        gapSince = null
        started = false
        flushBoundary = nextSequence != null
    }
}
