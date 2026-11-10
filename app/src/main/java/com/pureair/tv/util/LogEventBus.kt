package com.pureair.tv.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogEventBus {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _logFlow = MutableSharedFlow<String>(replay = 20, extraBufferCapacity = 50)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    /** 认证、音视频和界面线程共用格式器，串行格式化以免日志时间损坏。 */
    @Synchronized
    fun log(msg: String) {
        val time = timeFormat.format(Date())
        _logFlow.tryEmit("[$time] $msg")
    }
}
