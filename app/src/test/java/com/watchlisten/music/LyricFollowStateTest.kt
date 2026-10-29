package com.watchlisten.music

import com.watchlisten.music.ui.LyricFollowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricFollowStateTest {

    private var now = 10_000L
    private val follow = LyricFollowState(resumeDelayMs = 5000L, clock = { now })

    @Test
    fun 初始状态就是跟随中() {
        assertTrue(follow.tryResumeFollow())
        assertFalse(follow.isBrowsing)
    }

    @Test
    fun 用户拖动后挂起跟随() {
        follow.onUserScroll()
        assertTrue(follow.isBrowsing)
        assertFalse("刚拖动完不应立刻恢复", follow.tryResumeFollow())
    }

    @Test
    fun 挂起期间重复滚动从最后一次交互计时() {
        follow.onUserScroll()
        now += 3000
        follow.onScrollInProgress()
        now += 2000
        assertEquals(3000L, follow.timeUntilResume())
        assertFalse("距最后一次交互只有 2 秒，不该恢复", follow.tryResumeFollow())
        now += 3000
        assertTrue(follow.tryResumeFollow())
    }

    @Test
    fun 再次点歌词也延后自动恢复() {
        follow.onUserScroll()
        now += 4000
        follow.onUserScroll()
        now += 1000
        assertFalse(follow.tryResumeFollow())
        now += 4000
        assertTrue(follow.tryResumeFollow())
    }

    @Test
    fun 停止操作满五秒后恢复跟随() {
        follow.onUserScroll()
        now += 5000
        assertTrue(follow.tryResumeFollow())
        assertFalse(follow.isBrowsing)
    }

    @Test
    fun 恢复后再次拖动重新计时() {
        follow.onUserScroll()
        now += 5000
        follow.tryResumeFollow()
        now += 100_000
        follow.onUserScroll()
        assertFalse(follow.tryResumeFollow())
        now += 5000
        assertTrue(follow.tryResumeFollow())
    }

    @Test
    fun 点回到当前立即恢复不等待() {
        follow.onUserScroll()
        follow.resumeNow()
        assertTrue(follow.tryResumeFollow())
        assertFalse(follow.isBrowsing)
    }

    @Test
    fun 恢复后的重复调用保持已恢复状态() {
        follow.onUserScroll()
        now += 5000
        assertTrue(follow.tryResumeFollow())
        assertTrue("已经是跟随态，继续返回 true", follow.tryResumeFollow())
    }
}
