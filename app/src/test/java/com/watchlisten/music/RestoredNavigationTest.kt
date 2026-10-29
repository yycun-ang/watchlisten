package com.watchlisten.music

import com.watchlisten.music.ui.nav.MainTab
import com.watchlisten.music.ui.nav.RestoredNavigation
import com.watchlisten.music.ui.nav.restoreNavigation
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoredNavigationTest {
    @Test
    fun 历史搜索播放器迁移到主播放页并移除旧搜索栈() {
        assertEquals(RestoredNavigation(listOf("home"), MainTab.PLAYER),
            restoreNavigation(listOf("home", "search", "player"), MainTab.HOME.id))
    }

    @Test
    fun 历史播放器的歌词详情继续保留() {
        assertEquals(RestoredNavigation(listOf("home", "lyrics"), MainTab.PLAYER),
            restoreNavigation(listOf("home", "search", "player", "lyrics"), MainTab.HOME.id))
    }

    @Test
    fun 历史控制页迁移到主控制页并保留后续详情() {
        assertEquals(RestoredNavigation(listOf("home", "volume"), MainTab.CONTROLS),
            restoreNavigation(listOf("home", "player", "more", "volume"), MainTab.HOME.id))
    }

    @Test
    fun 多个旧根页面使用最后一个根页() {
        assertEquals(RestoredNavigation(listOf("home", "lyrics"), MainTab.PLAYER),
            restoreNavigation(listOf("home", "more", "settings", "player", "lyrics"),
                MainTab.CONTROLS.id))
    }

    @Test
    fun 嵌套首页同样恢复为首页根导航() {
        assertEquals(RestoredNavigation(listOf("home", "search"), MainTab.HOME),
            restoreNavigation(listOf("home", "player", "home", "search"), MainTab.PLAYER.id))
    }

    @Test
    fun 普通详情顺序和已保存的主页面保持() {
        val pages = listOf("home", "search", "settings")
        assertEquals(RestoredNavigation(pages, MainTab.CONTROLS),
            restoreNavigation(pages, MainTab.CONTROLS.id))
    }

    @Test
    fun 只有根页时保留已保存的播放页() {
        assertEquals(RestoredNavigation(listOf("home"), MainTab.PLAYER),
            restoreNavigation(listOf("home"), MainTab.PLAYER.id))
    }

    @Test
    fun 无效主页面索引回退首页且不丢失普通详情() {
        val pages = listOf("home", "search", "settings")
        for (invalidTabId in listOf(-1, MainTab.count, Int.MAX_VALUE)) {
            assertEquals(RestoredNavigation(pages, MainTab.HOME),
                restoreNavigation(pages, invalidTabId))
        }
    }

    @Test
    fun 空快照丢弃已保存的主页面并恢复首页() {
        assertEquals(RestoredNavigation(listOf("home"), MainTab.HOME),
            restoreNavigation(emptyList(), MainTab.PLAYER.id))
    }

    @Test
    fun 非首页开头快照丢弃整栈并恢复首页() {
        for (pages in listOf(listOf("player"), listOf("search", "player", "lyrics"))) {
            assertEquals(RestoredNavigation(listOf("home"), MainTab.HOME),
                restoreNavigation(pages, MainTab.CONTROLS.id))
        }
    }
}
