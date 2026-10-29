package com.watchlisten.music

import com.watchlisten.music.ui.nav.EdgeBackGesture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeBackGestureTest {
    private val gesture = EdgeBackGesture(EdgeBackGesture.Thresholds(
        edgeWidthPx = 24f, touchSlopPx = 8f, minimumDistancePx = 48f))

    @Test
    fun 禁用时和普通页面正文起手不接管右划() {
        gesture.start(2f, 100f, enabled = false)
        assertFalse(gesture.move(80f, 100f))
        assertFalse(gesture.finish(80f, 100f))
        gesture.start(25f, 100f, enabled = true)
        assertFalse(gesture.move(100f, 100f))
        assertFalse(gesture.finish(100f, 100f))
    }

    @Test
    fun 小幅抖动和点击不抢页面触摸() {
        gesture.start(2f, 100f, enabled = true)
        assertFalse(gesture.move(9f, 101f))
        assertFalse(gesture.finish(9f, 101f))
    }

    @Test
    fun 明确右划越过容差后才接管且距离足够才返回() {
        gesture.start(2f, 100f, enabled = true)
        assertFalse(gesture.move(10f, 100f))
        assertTrue(gesture.move(11f, 101f))
        assertTrue(gesture.finish(50f, 110f))
        assertFalse(gesture.isCaptured)
        assertFalse(gesture.finish(80f, 110f))
    }

    @Test
    fun 纵向列表和播放器上滑在边缘起手也不被抢() {
        for (endY in listOf(60f, 140f)) {
            gesture.start(2f, 100f, enabled = true)
            assertFalse(gesture.move(5f, endY))
            assertFalse(gesture.move(100f, endY))
            assertFalse(gesture.finish(100f, endY))
        }
    }

    @Test
    fun 先向左划后改变方向也不触发返回() {
        gesture.start(12f, 100f, enabled = true)
        assertFalse(gesture.move(2f, 100f))
        assertFalse(gesture.move(80f, 100f))
        assertFalse(gesture.finish(80f, 100f))
    }

    @Test
    fun 太短或回拉的右划不返回() {
        gesture.start(2f, 100f, enabled = true)
        assertTrue(gesture.move(20f, 100f))
        assertFalse(gesture.finish(49f, 100f))
        gesture.start(2f, 100f, enabled = true)
        assertTrue(gesture.move(80f, 100f))
        assertFalse(gesture.finish(20f, 100f))
    }

    @Test
    fun 横向意图不明确和最后大幅纵移均不返回() {
        gesture.start(2f, 100f, enabled = true)
        assertFalse(gesture.move(20f, 117f))
        assertFalse(gesture.finish(80f, 180f))
        gesture.start(2f, 100f, enabled = true)
        assertTrue(gesture.move(20f, 100f))
        assertFalse(gesture.finish(80f, 180f))
    }

    @Test
    fun 系统取消不返回且下一次手势可正常开始() {
        gesture.start(2f, 100f, enabled = true)
        assertTrue(gesture.move(20f, 100f))
        gesture.cancel()
        assertFalse(gesture.finish(80f, 100f))
        gesture.start(2f, 100f, enabled = true)
        assertTrue(gesture.move(20f, 100f))
        assertTrue(gesture.finish(80f, 100f))
    }

    @Test
    fun 多指取消返回且继续保有已接管序列() {
        gesture.start(2f, 100f, enabled = true)
        assertTrue(gesture.move(20f, 100f))
        gesture.cancelNavigation()
        assertTrue(gesture.isCaptured)
        assertTrue(gesture.move(80f, 100f))
        assertFalse(gesture.finish(80f, 100f))
        assertFalse(gesture.isCaptured)
    }

    @Test
    fun 首页明确右滑可以从正文起手() {
        gesture.start(120f, 100f, enabled = true, fromAnywhere = true)
        assertTrue(gesture.move(145f, 101f))
        assertTrue(gesture.finish(190f, 103f))
    }

    @Test
    fun 首页左滑与纵向滚动不触发返回() {
        gesture.start(120f, 100f, enabled = true, fromAnywhere = true)
        assertFalse(gesture.move(100f, 100f))
        assertFalse(gesture.move(200f, 100f))
        assertFalse(gesture.finish(200f, 100f))
        gesture.start(120f, 100f, enabled = true, fromAnywhere = true)
        assertFalse(gesture.move(122f, 130f))
        assertFalse(gesture.finish(200f, 200f))
    }

    @Test
    fun 首页正文起手权限不延续到下个详情页手势() {
        gesture.start(120f, 100f, enabled = true, fromAnywhere = true)
        assertTrue(gesture.move(160f, 100f))
        assertTrue(gesture.finish(200f, 100f))
        gesture.start(120f, 100f, enabled = true)
        assertFalse(gesture.move(200f, 100f))
        assertFalse(gesture.finish(220f, 100f))
    }
}
