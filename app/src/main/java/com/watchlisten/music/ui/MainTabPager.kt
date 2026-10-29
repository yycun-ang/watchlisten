package com.watchlisten.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.watchlisten.music.AppGraph
import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.playback.PlaybackSnapshot
import com.watchlisten.music.ui.nav.MainTab
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * 根页的三标签容器。
 *
 * 首页 / 播放器 / 播放控制。歌词在播放器上滑后压栈，独占完整圆屏。
 *
 * 当前 tab 由外部持有：横滑到某一页时先回调 [onTabChange] 更新宿主，
 * 宿主再把 tab 作为新目标传回。状态只有一份，指示器与落点不会分叉。
 */
@Composable
fun MainTabPager(
    graph: AppGraph,
    music: MusicState,
    playback: PlaybackSnapshot,
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val pager = rememberPagerState(initialPage = tab.id, pageCount = { MainTab.count })
    val scope = rememberCoroutineScope()
    val reduced by graph.library.reduceMotion.collectAsStateWithLifecycle()

    // 外部指定 tab 时对齐到该页，横滑切换回同一目标则忽略。
    LaunchedEffect(tab) {
        if (pager.currentPage != tab.id || pager.targetPage != tab.id || pager.isScrollInProgress) {
            pager.scrollToPage(tab.id)
        }
    }
    // 用户横滑落到新页时把当前 tab 交回宿主，作为下一次外部跳转的起点。
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { onTabChange(MainTab.of(it)) }
    }

    Box(Modifier.fillMaxSize().background(WatchTokens.Background)
        .lyricsSwipe(tab == MainTab.PLAYER) { onOpenDetail("lyrics") }.semantics {
        customActions = listOf(
            CustomAccessibilityAction("上一页") {
                if (tab.id == 0) false else { scope.launch {
                    if (reduced) pager.scrollToPage(tab.id - 1) else pager.animateScrollToPage(tab.id - 1)
                }; true }
            },
            CustomAccessibilityAction("下一页") {
                if (tab.id == MainTab.count - 1) false else { scope.launch {
                    if (reduced) pager.scrollToPage(tab.id + 1) else pager.animateScrollToPage(tab.id + 1)
                }; true }
            })
    }) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = true
        ) { page ->
            when (MainTab.of(page)) {
                MainTab.HOME -> StandardScreen(
                    MainTab.HOME.route, graph, music, playback, onOpenDetail, IndicatorBand
                )
                MainTab.PLAYER -> StandardScreen(
                    MainTab.PLAYER.route, graph, music, playback, onOpenDetail, IndicatorBand
                )
                MainTab.CONTROLS -> StandardScreen(
                    MainTab.CONTROLS.route, graph, music, playback, onOpenDetail,
                    IndicatorBand, visible = pager.currentPage == page
                )
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(IndicatorBand + 6.dp)
            .background(Brush.verticalGradient(
                0f to WatchTokens.Background.copy(alpha = 0f),
                .6f to WatchTokens.Background.copy(alpha = .8f),
                1f to WatchTokens.Background)))
        MainIndicator(tab.id, Modifier.align(Alignment.BottomCenter))
    }
}
