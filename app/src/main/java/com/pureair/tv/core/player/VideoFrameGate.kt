package com.pureair.tv.core.player

import com.pureair.tv.core.rtp.NalType

/** 解码器重建后先等 IDR；原有解码器继续接收 P 帧，不因相同配置重复清空参考帧。 */
class VideoFrameGate {
    private var waitingForKeyFrame = true

    fun reset() { waitingForKeyFrame = true }

    /** 一个访问单元可能含 SPS、PPS、SEI 和多片 IDR，不能只看第一个 NAL。 */
    fun inspect(frame: ByteArray): NalType? {
        var keyFrame = false
        var index = 0
        while (index + 3 < frame.size) {
            if (frame[index] == 0.toByte() && frame[index + 1] == 0.toByte() &&
                frame[index + 2] == 1.toByte() && frame[index + 3].toInt() and 31 == 5) {
                keyFrame = true
                break
            }
            index++
        }
        if (keyFrame) waitingForKeyFrame = false
        return if (waitingForKeyFrame) null else if (keyFrame) NalType.IDR else NalType.OTHER
    }
}
