package com.watchlisten.music

import com.watchlisten.music.playback.SeekBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekBoundsTest {
    private val currentEntry = "song-instance-2"

    @Test
    fun 已知媒体时长内保留毫秒位置() {
        assertEquals(42_345L, SeekBounds(currentEntry, 240_000, true)
            .resolveTarget(42_345, currentEntry))
    }

    @Test
    fun 负值与极大位置夹在媒体边界内() {
        val bounds = SeekBounds(currentEntry, 240_000, true)
        assertEquals(0L, bounds.resolveTarget(Long.MIN_VALUE, currentEntry))
        assertEquals(240_000L, bounds.resolveTarget(Long.MAX_VALUE, currentEntry))
    }

    @Test
    fun 试听片段按实际长度限制而不是目录全曲长度() {
        val preview = SeekBounds(currentEntry, 60_000, true)
        assertEquals(60_000L, preview.resolveTarget(210_000, currentEntry))
        assertEquals(30_000L, preview.resolveTarget(30_000, currentEntry))
    }

    @Test
    fun 未知或零时长不能提交定位() {
        listOf(0L, -1L, -9223372036854775807L).forEach { duration ->
            assertNull(SeekBounds(currentEntry, duration, true).resolveTarget(10_000, currentEntry))
        }
    }

    @Test
    fun 已切换实例时丢弃旧拖动() {
        assertNull(SeekBounds(currentEntry, 240_000, true)
            .resolveTarget(80_000, "song-instance-1"))
    }

    @Test
    fun 没有明确目标实例时不能误定位当前歌曲() {
        assertNull(SeekBounds(currentEntry, 240_000, true).resolveTarget(80_000, null))
    }

    @Test
    fun 空播放项不能定位() {
        listOf(null, "", " ").forEach { entry ->
            assertNull(SeekBounds(entry, 240_000, true).resolveTarget(80_000, entry))
        }
    }

    @Test
    fun 媒体不支持定位或无命令权限时拒绝() {
        assertNull(SeekBounds(currentEntry, 240_000, false).resolveTarget(80_000, currentEntry))
    }
}
