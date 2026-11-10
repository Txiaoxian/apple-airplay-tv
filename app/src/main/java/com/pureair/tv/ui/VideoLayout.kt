package com.pureair.tv.ui

/** 在可用区域内等比例完整显示视频，使用长整数避免大尺寸相乘溢出。 */
internal fun fitVideoSize(videoWidth: Int, videoHeight: Int,
                          availableWidth: Int, availableHeight: Int): Pair<Int, Int> {
    require(videoWidth > 0 && videoHeight > 0 && availableWidth > 0 && availableHeight > 0)
    return if (videoWidth.toLong() * availableHeight > videoHeight.toLong() * availableWidth) {
        availableWidth to (availableWidth.toLong() * videoHeight / videoWidth).toInt().coerceAtLeast(1)
    } else {
        (availableHeight.toLong() * videoWidth / videoHeight).toInt().coerceAtLeast(1) to availableHeight
    }
}
