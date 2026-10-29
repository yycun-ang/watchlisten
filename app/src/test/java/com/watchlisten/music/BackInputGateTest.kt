package com.watchlisten.music

import com.watchlisten.music.ui.nav.BackDecision
import com.watchlisten.music.ui.nav.BackInput
import com.watchlisten.music.ui.nav.BackInputGate
import com.watchlisten.music.ui.nav.BackNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackInputGateTest {
    private val gate = BackInputGate()

    @Test
    fun 同一次右划同时上报键与系统回调仅弹一层() {
        val navigator = BackNavigator(onExit = {}, dispatchToPage = {})
        navigator.restoreDepth(3)
        if (gate.accept(BackInput.EDGE_GESTURE, 1000)) navigator.onBack(false, true)
        if (gate.acceptKeyDown(1001, 0, 1010)) navigator.onBack(false, true)
        if (gate.accept(BackInput.SYSTEM, 1020)) navigator.onBack(false, true)
        assertEquals(2, navigator.stackDepth)
        assertTrue(gate.accept(BackInput.EDGE_GESTURE, 1100))
        assertEquals(BackDecision.PopStack, navigator.onBack(false, true))
        assertEquals(1, navigator.stackDepth)
    }

    @Test
    fun 同一次键按下的重复事件和长按不再次返回() {
        assertTrue(gate.acceptKeyDown(1000, 0, 1000))
        assertFalse(gate.acceptKeyDown(1000, 0, 1001))
        assertFalse(gate.acceptKeyDown(1000, 1, 2000))
        assertFalse(gate.accept(BackInput.SYSTEM, 1010))
    }

    @Test
    fun 首页一次明确按键退出并拦截随后的重复系统回调() {
        var exits = 0
        val navigator = BackNavigator(onExit = { exits++ }, dispatchToPage = {})
        assertTrue(gate.acceptKeyDown(1000, 0, 1000))
        assertEquals(BackDecision.ExitNow, navigator.onBack(false, true))
        assertFalse(gate.accept(BackInput.SYSTEM, 1010))
        assertEquals(1, exits)
    }

    @Test
    fun 首页系统返回只执行一次退出() {
        var exits = 0
        val navigator = BackNavigator(onExit = { exits++ }, dispatchToPage = {})
        if (gate.accept(BackInput.SYSTEM, 1000)) navigator.onBack(false, true)
        if (gate.accept(BackInput.SYSTEM, 1001)) navigator.onBack(false, true)
        assertEquals(1, exits)
        assertTrue(gate.accept(BackInput.SYSTEM, 1400))
    }

    @Test
    fun 超出去重窗口的新来源正常返回() {
        assertTrue(gate.accept(BackInput.EDGE_GESTURE, 1000))
        assertTrue(gate.acceptKeyDown(1400, 0, 1400))
        assertTrue(gate.accept(BackInput.SYSTEM, 1800))
    }

    @Test
    fun 按键先到而触摸抬手随后上报仍仅一次() {
        assertTrue(gate.acceptKeyDown(1000, 0, 1000))
        assertFalse(gate.accept(BackInput.EDGE_GESTURE, 1020))
        assertFalse(gate.accept(BackInput.SYSTEM, 1030))
    }

    @Test
    fun 慢速右划期间即使超过去重窗口也只返回一次() {
        gate.beginEdgeGesture()
        assertTrue(gate.acceptKeyDown(1000, 0, 1000))
        assertFalse(gate.accept(BackInput.SYSTEM, 2000))
        assertFalse(gate.accept(BackInput.EDGE_GESTURE, 3000))
        gate.endEdgeGesture(3000)
        assertTrue(gate.accept(BackInput.EDGE_GESTURE, 3100))
    }

    @Test
    fun 未触发返回的边缘触摸结束后不阻止正常按键() {
        gate.beginEdgeGesture()
        gate.endEdgeGesture(900)
        assertTrue(gate.acceptKeyDown(1000, 0, 1000))
        gate.beginEdgeGesture()
        assertFalse(gate.accept(BackInput.EDGE_GESTURE, 1100))
        gate.endEdgeGesture(1100)
        assertTrue(gate.acceptKeyDown(1200, 0, 1200))
    }

    @Test
    fun 首页固件先报按键或系统回调时仍然只退出一次() {
        for (first in BackInput.entries) {
            val inputGate = BackInputGate()
            var exits = 0
            val navigator = BackNavigator(onExit = { exits++ }, dispatchToPage = {})
            inputGate.beginEdgeGesture()
            if (inputGate.accept(first, 1000)) navigator.onBack(false, true)
            for (input in BackInput.entries) {
                if (inputGate.accept(input, 2500)) navigator.onBack(false, true)
            }
            inputGate.endEdgeGesture(2500)
            assertEquals(1, exits)
        }
    }

    @Test
    fun 详情页右滑到首页后迟到的返回不能再退出() {
        for (first in BackInput.entries) {
            val inputGate = BackInputGate()
            var exits = 0
            val navigator = BackNavigator(onExit = { exits++ }, dispatchToPage = {})
            navigator.restoreDepth(2)
            inputGate.beginEdgeGesture()
            if (inputGate.accept(first, 1000)) assertEquals(BackDecision.PopStack, navigator.onBack(false, true))
            for (input in BackInput.entries) {
                if (inputGate.accept(input, 2500)) navigator.onBack(false, true)
            }
            inputGate.endEdgeGesture(2500)
            assertFalse(inputGate.accept(BackInput.SYSTEM, 2510))
            assertFalse(inputGate.acceptKeyDown(2520, 0, 2520))
            assertEquals(1, navigator.stackDepth)
            assertEquals(0, exits)
        }
    }

    @Test
    fun 重复结束回调不会延长已结束手势的去重窗口() {
        gate.beginEdgeGesture()
        assertTrue(gate.acceptKeyDown(1000, 0, 1000))
        gate.endEdgeGesture(3000)
        gate.endEdgeGesture(3200)
        assertTrue(gate.accept(BackInput.SYSTEM, 3400))
    }
}
