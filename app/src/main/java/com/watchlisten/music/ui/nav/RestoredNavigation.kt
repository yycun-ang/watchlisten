package com.watchlisten.music.ui.nav

internal data class RestoredNavigation(val pages: List<String>, val tab: MainTab)

/** 将旧版压入详情栈的根页面迁移到唯一的主导航，并保留其后的详情页。 */
internal fun restoreNavigation(pages: List<String>, savedTabId: Int): RestoredNavigation {
    val home = MainTab.HOME.route
    if (pages.firstOrNull() != home) return RestoredNavigation(listOf(home), MainTab.HOME)

    val nestedRootIndex = pages.indexOfLast { MainTab.ofRoute(it) != null }
    if (nestedRootIndex <= 0) return RestoredNavigation(pages.toList(), MainTab.of(savedTabId))

    val tab = checkNotNull(MainTab.ofRoute(pages[nestedRootIndex]))
    return RestoredNavigation(listOf(home) + pages.drop(nestedRootIndex + 1), tab)
}
