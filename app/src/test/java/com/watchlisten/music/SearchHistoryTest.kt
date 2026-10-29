package com.watchlisten.music

import com.watchlisten.music.data.mergeSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun 空白查询不改变历史() {
        assertEquals(emptyList<String>(), mergeSearch(emptyList(), "   "))
        assertEquals(emptyList<String>(), mergeSearch(emptyList(), ""))
    }

    @Test
    fun 最近查询排在最前() {
        assertEquals(listOf("b", "a"), mergeSearch(listOf("a"), "b"))
    }

    @Test
    fun 重复查询忽略大小写并置顶() {
        assertEquals(listOf("HELLO", "world"), mergeSearch(listOf("world", "hello"), "HELLO"))
    }

    @Test
    fun 超出上限时裁剪最旧的() {
        val full = (1..12).map { "q$it" }
        val merged = mergeSearch(full, "q13")
        assertEquals(12, merged.size)
        assertEquals("q13", merged.first())
        assertEquals("q11", merged.last())
    }

    @Test
    fun 超长查询被裁剪到一百字符() {
        val merged = mergeSearch(emptyList(), "x".repeat(500))
        assertEquals(100, merged.single().length)
    }
}