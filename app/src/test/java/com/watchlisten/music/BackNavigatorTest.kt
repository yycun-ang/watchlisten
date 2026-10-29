package com.watchlisten.music

import com.watchlisten.music.ui.nav.BackDecision
import com.watchlisten.music.ui.nav.BackNavigator
import com.watchlisten.music.ui.nav.MainTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackNavigatorTest {

    @Test
    fun 恢复深度后逐层返回() {
        val restored = BackNavigator(onExit = {}, dispatchToPage = {})
        restored.restoreDepth(3)
        assertEquals(BackDecision.PopStack, restored.onBack(false, true))
        assertEquals(BackDecision.PopStack, restored.onBack(false, true))
        assertEquals(BackDecision.ExitNow, restored.onBack(false, true))
    }

    private var exits = 0
    private var dispatches = 0
    private val navigator = BackNavigator(
        onExit = { exits++ }, dispatchToPage = { dispatches++ })

    @Test
    fun 当前页消费返回时不回退栈() {
        assertEquals(BackDecision.ConsumedByPage, navigator.onBack(pageConsumes = true, onRootTab = false))
        assertEquals(0, exits)
        assertEquals(0, dispatches)
    }

    @Test
    fun 栈中有页面时先回退而不是退出() {
        navigator.pushPage()
        assertEquals(BackDecision.PopStack, navigator.onBack(pageConsumes = false, onRootTab = false))
        assertEquals(0, exits)
        assertEquals(0, dispatches)
    }

    @Test
    fun 连续回退逐层退出直到只剩根页() {
        navigator.pushPage()
        navigator.pushPage()
        assertEquals(3, navigator.stackDepth)
        assertEquals(BackDecision.PopStack, navigator.onBack(false, false))
        assertEquals(BackDecision.PopStack, navigator.onBack(false, false))
        assertEquals(1, navigator.stackDepth)
        assertEquals(0, exits)
    }

    @Test
    fun 非根标签页把返回交给页面处理而不是退出() {
        assertEquals(BackDecision.DispatchToPage, navigator.onBack(false, onRootTab = false))
        assertEquals(1, dispatches)
        assertEquals(0, exits)
    }

    @Test
    fun 首页根页一次返回立即退出界面() {
        assertEquals(BackDecision.ExitNow, navigator.onBack(false, onRootTab = true))
        assertEquals(1, exits)
        assertEquals(0, dispatches)
    }

    @Test
    fun 详情退到首页后下一次明确返回才退出() {
        navigator.pushPage()
        assertEquals(BackDecision.PopStack, navigator.onBack(false, true))
        assertEquals(0, exits)
        assertEquals(BackDecision.ExitNow, navigator.onBack(false, true))
        assertEquals(1, exits)
    }

    @Test
    fun 非首页根标签不会跨过首页退出() {
        assertEquals(BackDecision.DispatchToPage, navigator.onBack(false, false))
        assertEquals(1, dispatches)
        assertEquals(0, exits)
    }

    @Test
    fun 首页当前页面消费返回时不退出() {
        assertEquals(BackDecision.ConsumedByPage, navigator.onBack(true, true))
        assertEquals(0, exits)
        assertEquals(0, dispatches)
    }

    @Test
    fun 回到根页后栈深度复位() {
        navigator.pushPage()
        navigator.pushPage()
        navigator.popToRoot()
        assertEquals(1, navigator.stackDepth)
        assertEquals(BackDecision.ExitNow, navigator.onBack(false, onRootTab = true))
        assertEquals(1, exits)
    }
}

class MainTabTest {

    @Test
    fun 三个标签页顺序固定() {
        assertEquals(3, MainTab.count)
        assertEquals(MainTab.HOME, MainTab.of(0))
        assertEquals(MainTab.PLAYER, MainTab.of(1))
        assertEquals(MainTab.CONTROLS, MainTab.of(2))
    }

    @Test
    fun 越界索引回退到首页() {
        assertEquals(MainTab.HOME, MainTab.of(-1))
        assertEquals(MainTab.HOME, MainTab.of(99))
    }

    @Test
    fun 每个标签都有唯一索引() {
        val ids = MainTab.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(MainTab.entries.containsAll(MainTab.entries))
    }

    @Test
    fun 路由反查回标签() {
        assertEquals(MainTab.HOME, MainTab.ofRoute("home"))
        assertEquals(MainTab.PLAYER, MainTab.ofRoute("player"))
        assertEquals(MainTab.CONTROLS, MainTab.ofRoute("more"))
    }

    @Test
    fun 详情页不属于任何标签() {
        // 详情页压在标签容器之上，不能被当成标签页渲染。
        for (route in listOf("search", "queue", "settings", "about", "favorites", "lyrics")) {
            assertNull(MainTab.ofRoute(route))
        }
    }
}
