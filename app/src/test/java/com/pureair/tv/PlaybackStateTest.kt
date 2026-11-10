package com.pureair.tv

import com.pureair.tv.core.player.MediaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackStateTest {
    /** 复现后台收到投屏后才创建页面，开始事件已发生也必须立即显示当前画面。 */
    @Test
    fun recreatedPageReadsCurrentPlaybackInsteadOfWaitingForAnotherStart() = runBlocking {
        val engine = MediaEngine()
        assertFalse(engine.streaming.first())
        engine.start()
        assertTrue(engine.streaming.first())
        assertTrue(engine.isStreaming)
        engine.stop()
        assertFalse(engine.streaming.first())
        assertFalse(engine.isStreaming)
    }

    /** 已订阅的页面跟随停流、重建流和断开切换，避免残留全屏黑画面。 */
    @Test
    fun boundPageFollowsVideoRestartsAndStops() = runBlocking {
        val engine = MediaEngine()
        val states = async(Dispatchers.Unconfined) { engine.streaming.take(5).toList() }
        engine.start()
        engine.stop()
        engine.start()
        engine.stop()
        assertEquals(listOf(false, true, false, true, false), states.await())
    }
}
