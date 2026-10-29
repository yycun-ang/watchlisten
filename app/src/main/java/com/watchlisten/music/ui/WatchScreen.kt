package com.watchlisten.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.model.CollectionFeed
import com.watchlisten.music.model.isCollectionRoute
import com.watchlisten.music.model.isArtistRoute
import com.watchlisten.music.playback.PlaybackSnapshot
import com.watchlisten.music.ui.nav.MainTab
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 根标签和详情页共用宿主；页面只消费状态并发出导航事件。 */
@Composable
fun WatchScreen(route: String, depth: Int, tab: MainTab, onTab: (MainTab) -> Unit,
                graph: AppGraph, open: (String) -> Unit, close: () -> Unit) {
    val music by graph.music.state.collectAsStateWithLifecycle()
    val playback by graph.playback.state.collectAsStateWithLifecycle()
    val reduced by graph.library.reduceMotion.collectAsStateWithLifecycle()
    val detailStates = rememberSaveableStateHolder()
    MaterialTheme(colorScheme = MusicColors) {
        PageStackContainer(depth, animateTransitions = !reduced) {
            if (depth == 1 && MainTab.ofRoute(route) != null) {
                MainTabPager(graph, music, playback, tab, onTab, open)
            } else if (route == "lyrics") {
                LyricsScreen(graph, music, playback, close)
            } else {
                detailStates.SaveableStateProvider(route) {
                    StandardScreen(route, graph, music, playback, open)
                }
            }
        }
    }
}

@Composable
fun StandardScreen(route: String, graph: AppGraph, music: MusicState,
                   playback: PlaybackSnapshot, open: (String) -> Unit,
                   indicatorInset: Dp = 0.dp, visible: Boolean = true) {
    when (route) {
        "home" -> HomeScreen(graph, music, playback, open)
        "search" -> SearchScreen(graph, music, playback, open, onVoiceSearch = { open("voice-search") })
        "player" -> {
            val source by graph.playbackSource.collectAsStateWithLifecycle()
            PlayerScreen(graph, music, playback, open, indicatorInset, source?.label,
                source?.let { { open("return-source") } })
        }
        "favorites", "history", "local-music" -> MusicLibraryScreen(route, graph, open)
        "cloud-manage" -> CloudManagementScreen(graph, open)
        "transfer" -> LocalTransferScreen(graph, open)
        "diagnostics" -> DiagnosticsScreen(graph, open)
        "catalog-search" -> DiscoveryScreen(route, graph, open)
        "dlna" -> DlnaContent(graph, open)
        "queue" -> QueueScreen(graph, playback, open)
        "pinned" -> BrowseScreen("cloud", graph, music, open)
        "discover", "rankings", "featured", "cloud", "hot-search" -> BrowseScreen(route, graph, music, open)
        else -> if (isArtistRoute(route)) DiscoveryScreen(route, graph, open)
            else if (isCollectionRoute(route)) BrowseScreen(route, graph, music, open)
            else LibraryOrUtilityScreen(route, graph, music, playback, open, indicatorInset, visible)
    }
}

@Composable
private fun LibraryOrUtilityScreen(route: String, graph: AppGraph, music: MusicState,
                                   playback: PlaybackSnapshot, open: (String) -> Unit,
                                   indicatorInset: Dp = 0.dp, visible: Boolean = true) {
    val restored = remember(route) { graph.library.scrollFor(route) }
    val list = rememberLazyListState(restored.first, restored.second)
    val favorites by graph.favorites.state.collectAsStateWithLifecycle()
    val history by graph.library.history.collectAsStateWithLifecycle()
    LaunchedEffect(route, graph) {
        if (route == "account") graph.sessions.session.map { it?.userId }.distinctUntilChanged().collect {
            // 登录与退出切换资料/二维码时，先展示新身份的首屏。
            list.scrollToItem(0)
        }
    }
    DisposableEffect(route) {
        onDispose { graph.library.saveScroll(route, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
    }
    RoundFrame(list) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(bottom = indicatorInset),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            when (route) {
                "library" -> {
                    item { Title("我的音乐", "随时接着听") }
                    item { SectionLabel("酷狗账号") }
                    item { WatchListItem("酷狗云歌单", { open("cloud") }, subtitle = "手机上的创建与收藏", icon = R.drawable.ic_user) }
                    item { WatchListItem("发现音乐", { open("discover") }, subtitle = "榜单、歌单与每日推荐", icon = R.drawable.ic_library) }
                    item { WatchListItem("酷狗我喜欢", { open("favorites") }, subtitle = if (favorites.loaded) "${favorites.tracks.size} 首 · 酷狗云端" else "与手机酷狗相连", icon = R.drawable.ic_heart, accent = WarmRose) }
                    item { WatchListItem("管理酷狗歌单", { graph.cloudManager.clearSelection(); open("cloud-manage") }, subtitle = "创建、改名与整理歌曲", icon = R.drawable.ic_library) }
                    item { SectionLabel("手表记录") }
                    item { WatchListItem("最近播放", { open("history") }, subtitle = "${history.size} 首", icon = R.drawable.ic_clock) }
                    item { WatchListItem("离线音乐", { open("local-music") }, subtitle = "导入文件与手机传歌", icon = R.drawable.ic_library) }
                    item { WatchListItem("播放队列", { open("queue") }, subtitle = "${playback.queue.size} 首", icon = R.drawable.ic_queue) }
                    item { WatchListItem("连接与播放诊断", { open("diagnostics") }, subtitle = "查看状态与导出诊断", icon = R.drawable.ic_info) }
                }
                "account" -> item { AccountContent(graph, music, open) }
                "more" -> item { MoreContent(graph, playback, music, open,
                    rootPage = indicatorInset > 0.dp, visible = visible) }
                "info" -> item { InfoContent(graph, playback, music, open) }
                "settings" -> item { SettingsContent(graph, open) }
                "about" -> item { AboutContent() }
                "privacy" -> item { PrivacyContent(graph::revokeConsent) }
                else -> item { WatchEmptyState("页面暂不可用", "返回后重新打开", icon = R.drawable.ic_info) }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
