package com.watchlisten.music

import com.watchlisten.music.ui.nav.PageStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageStackTest {

    @Test
    fun 恢复快照后保留顺序和深度() {
        val restored = PageStack("home")
        restored.restore(listOf("home", "search", "settings"))
        assertEquals(3, restored.depth)
        assertEquals("settings", restored.current)
        restored.pop()
        assertEquals("search", restored.current)
    }

    @Test
    fun 空快照恢复根页() {
        val restored = PageStack("home")
        restored.push("search")
        restored.restore(emptyList())
        assertEquals(listOf("home"), restored.snapshot())
    }

    private val stack = PageStack("home")

    @Test
    fun 初始只有根页且深度为一() {
        assertEquals(1, stack.depth)
        assertEquals("home", stack.current)
        assertTrue(stack.isRootVisible)
    }

    @Test
    fun 压栈后当前页变为新页() {
        assertTrue(stack.push("search"))
        assertEquals("search", stack.current)
        assertEquals(2, stack.depth)
        assertFalse(stack.isRootVisible)
    }

    @Test
    fun 重复压入同一页被忽略() {
        stack.push("search")
        assertFalse(stack.push("search"))
        assertEquals(2, stack.depth)
    }

    @Test
    fun 替换模式不增加深度() {
        stack.push("search")
        assertTrue(stack.push("player", replace = true))
        assertEquals("player", stack.current)
        assertEquals(2, stack.depth)
        assertFalse("被替换的页应已移除", stack.contains("search"))
    }

    @Test
    fun 回退逐层弹出直到根页() {
        stack.push("search")
        stack.push("player")
        assertEquals("player", stack.pop())
        assertEquals("search", stack.pop())
        assertNull("根页不可再回退", stack.pop())
        assertEquals(1, stack.depth)
    }

    @Test
    fun 回到根页清空其上所有页面() {
        stack.push("search")
        stack.push("player")
        assertEquals("home", stack.popToRoot())
        assertEquals(1, stack.depth)
        assertFalse(stack.contains("search"))
    }

    @Test
    fun 移到栈顶会改变顺序但不丢页面() {
        stack.push("search")
        stack.push("player")
        assertTrue(stack.moveToTop("search"))
        assertEquals("search", stack.current)
        assertEquals(3, stack.depth)
        assertTrue(stack.contains("player"))
    }

    @Test
    fun 移到不存在的页面返回失败() {
        assertFalse(stack.moveToTop("nowhere"))
        assertEquals(1, stack.depth)
    }

    @Test
    fun 快照顺序从根到顶() {
        stack.push("search")
        stack.push("player")
        assertEquals(listOf("home", "search", "player"), stack.snapshot())
    }

    @Test
    fun 替换根页也能正常工作() {
        assertTrue(stack.push("player", replace = true))
        assertEquals(1, stack.depth)
        assertEquals("player", stack.current)
    }
}
