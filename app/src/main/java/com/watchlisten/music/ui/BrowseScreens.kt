package com.watchlisten.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.domain.*
import com.watchlisten.music.model.*

/** 页面与息屏生命周期都交给同一个协调器，不在隐藏页面悄悄预取私人内容。 */
@Composable
internal fun BrowseLifecycle(route: String, graph: AppGraph) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(route, graph, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> graph.browse.activate(route)
                Lifecycle.Event.ON_PAUSE -> graph.browse.deactivate(route)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) graph.browse.activate(route)
        onDispose { lifecycle.removeObserver(observer); graph.browse.deactivate(route) }
    }
}

@Composable
internal fun BrowseScreen(route: String, graph: AppGraph, music: MusicState, open: (String) -> Unit) {
    val state by graph.browse.state.collectAsStateWithLifecycle()
    val session by graph.sessions.session.collectAsStateWithLifecycle()
    val initial = remember(route) { graph.library.scrollFor(route) }
    val list = rememberLazyListState(initial.first, initial.second)
    BrowseLifecycle(route, graph)
    DisposableEffect(route) {
        onDispose { graph.library.saveScroll(route, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
    }
    val feed = CollectionFeed.ofRoute(route)
    val detail = state.details[route] ?: graph.browse.detail(route)
    RoundFrame(list) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                route == "discover" -> {
                    item { Title("发现音乐", "找到下一首喜欢") }
                    item { WatchListItem("音乐排行榜", { open("rankings") }, subtitle = "酷狗实时榜单", icon = R.drawable.ic_bars) }
                    item { WatchListItem("精选歌单", { open("featured") }, subtitle = "按心情找到好音乐", icon = R.drawable.ic_library) }
                    item { WatchListItem("每日推荐", {
                        open(graph.browse.daily())
                    }, subtitle = if (session == null) "游客精选 · 登录后更懂你" else "酷狗为你推荐", icon = R.drawable.ic_quality) }
                    item { WatchListItem("酷狗云歌单", { open("cloud") }, subtitle = "手机上的创建与收藏", icon = R.drawable.ic_user) }
                    item { WatchListItem("酷狗我喜欢", { open("favorites") }, subtitle = "与手机酷狗同步", icon = R.drawable.ic_heart) }
                    item { WatchListItem("热门搜索", { open("hot-search") }, icon = R.drawable.ic_search) }
                    item { Notice("会员与播放权限以每首歌曲的实时授权为准") }
                }
                route == "hot-search" -> {
                    item { Title("热门搜索", "来自酷狗实时热搜") }
                    items(state.hotWords, key = { it }) { word ->
                        WatchListItem(word, { graph.music.search(word); open("search") }, icon = R.drawable.ic_search)
                    }
                    when {
                        state.hotLoading -> item { WatchLoadingState("正在获取热搜") }
                        state.hotError != null -> item { WatchErrorState("热搜暂不可用", state.hotError?.message(), actionLabel = "重试", onAction = { graph.browse.loadHotWords(true) }) }
                        state.hotWords.isEmpty() -> item { WatchEmptyState("暂无热搜", "稍后刷新再看看。") }
                    }
                    item { SecondaryAction("刷新热搜", enabled = !state.hotLoading) { graph.browse.loadHotWords(true) } }
                }
                feed != null -> {
                    val listing = state.feeds[feed] ?: CollectionListing()
                    item { Title(feed.title, when {
                        listing.total != null -> "${listing.items.size} / ${listing.total} 个"
                        listing.items.isNotEmpty() -> "已加载 ${listing.items.size} 个"
                        else -> null
                    }) }
                    if (feed == CollectionFeed.CLOUD && session == null) item {
                        WatchEmptyState("登录后查看云歌单", "读取手机酷狗的创建和收藏歌单，与本机记录分别管理。",
                            icon = R.drawable.ic_user, actionLabel = "扫码登录", onAction = { open("account") })
                    } else {
                        if (feed == CollectionFeed.CLOUD) item { SecondaryAction("管理我的云歌单") {
                            graph.cloudManager.clearSelection(); open("cloud-manage")
                        } }
                        items(listing.items, key = { it.key }) { collection ->
                            CollectionRow(collection) { open(graph.browse.open(collection)) }
                        }
                        when {
                            listing.loading || (!listing.loaded && listing.error == null && feed != CollectionFeed.PINNED) -> item { WatchLoadingState("正在加载", "请稍候") }
                            listing.error != null -> item { WatchErrorState("暂时无法加载", listing.error.message(), actionLabel = "重试", onAction = { graph.browse.loadFeed(feed, listing.retryAppend) }) }
                            listing.items.isEmpty() -> item { WatchEmptyState("这里还没有歌单", if (feed == CollectionFeed.PINNED)
                                "打开榜单或歌单后，可保存为本机快捷入口。" else "你可以刷新，或先在手机酷狗整理音乐。", icon = R.drawable.ic_library) }
                            listing.hasMore -> item { SecondaryAction("加载更多") { graph.browse.loadFeed(feed, true) } }
                        }
                        if (feed != CollectionFeed.PINNED) item {
                            SecondaryAction("刷新列表", enabled = !listing.loading) { graph.browse.loadFeed(feed) }
                        }
                    }
                }
                detail != null -> {
                    val collection = detail.collection
                    item { CollectionHeader(collection) }
                    item {
                        Text(buildString {
                            append("已加载 ${detail.tracks.size}")
                            detail.total?.let { append(" / $it") }
                            append(" 首")
                        }, style = WatchTypography.Supporting)
                    }
                    if (detail.loadingAll) item {
                        InlineBanner("正在获取完整列表：${detail.tracks.size} 首", actionLabel = "取消", onAction = graph.browse::cancelAllLoading)
                    } else if (detail.tracks.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PrimaryAction("全部播放", Modifier.weight(1f), enabled = !detail.loading) {
                                graph.browse.performAll(route, CollectionAction.PLAY) { open("player") }
                            }
                            SecondaryAction("加入队列", Modifier.weight(1f), enabled = !detail.loading) {
                                graph.browse.performAll(route, CollectionAction.QUEUE)
                            }
                        }
                    }
                    detail.message?.let { message -> item { InlineBanner(message, color = WatchTokens.Secondary) } }
                    items(detail.tracks, key = { it.id }) { TrackRow(it, graph, open, detail.tracks) }
                    when {
                        detail.loading -> item { WatchLoadingState("正在加载歌曲", "请稍候") }
                        detail.error != null -> item { WatchErrorState("加载未完成", detail.error.message(), actionLabel = "重试", onAction = { graph.browse.loadTracks(route, detail.retryAppend) }) }
                        !detail.loaded -> item { WatchLoadingState("正在读取歌单", "请稍候") }
                        detail.tracks.isEmpty() -> item { WatchEmptyState("歌单暂时没有歌曲", "刷新后再试，或到手机酷狗查看。", icon = R.drawable.ic_library) }
                        detail.hasMore && !detail.loadingAll -> item { SecondaryAction("继续加载歌曲") { graph.browse.loadTracks(route, true) } }
                    }
                    if (!detail.loadingAll && detail.tracks.isNotEmpty()) item {
                        SecondaryAction("全部加入酷狗我喜欢") {
                            if (session == null) open("account") else graph.browse.performAll(route, CollectionAction.FAVORITE)
                        }
                    }
                    if (detail.description.isNotBlank()) item { CollectionDescription(detail.description) }
                    item { SecondaryAction("刷新歌曲", enabled = !detail.loading && !detail.loadingAll) { graph.browse.loadTracks(route) } }
                    if (music.presentPlayback(graph.playback.state.value).track != null) item {
                        SecondaryAction("回到正在播放") { open("player") }
                    }
                }
                else -> item { WatchEmptyState("请重新打开歌单", "账号可能已变化，返回后从列表中重新选择。",
                    icon = R.drawable.ic_library, actionLabel = "返回首页", onAction = { open("home") }) }
            }
        }
    }
}

@Composable
private fun CollectionRow(collection: MusicCollection, onOpen: () -> Unit) {
    Surface(onClick = onOpen, color = WatchTokens.Surface, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
        Row(Modifier.padding(7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cover(collection.cover, Modifier.size(32.dp), cornerRadius = 9.dp, fallback = { Brand(20) })
            Column(Modifier.weight(1f)) {
                Text(collection.title, style = WatchTypography.CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(collection.count?.let { "$it 首" }.orEmpty(), collection.subtitle)
                    .filter(String::isNotBlank).joinToString(" · "),
                    style = WatchTypography.Metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(12.dp), tint = WatchTokens.TextMuted)
        }
    }
}

@Composable
private fun CollectionHeader(collection: MusicCollection) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Cover(collection.cover, Modifier.size(32.dp), cornerRadius = 9.dp, fallback = { Brand(22) })
        Column(Modifier.weight(1f)) {
            Text(collection.title, style = WatchTypography.CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when (collection.kind) {
                CollectionKind.CHART -> "酷狗排行榜"
                CollectionKind.CLOUD -> "酷狗云歌单"
                CollectionKind.DAILY -> if (collection.ownerId.isBlank()) "游客每日精选" else "账号专属推荐"
                CollectionKind.ALBUM -> "专辑歌曲"
                CollectionKind.PLAYLIST -> "酷狗精选歌单"
            }, style = WatchTypography.Metadata)
        }
    }
}

@Composable
private fun CollectionDescription(description: String) {
    var expanded by remember(description) { mutableStateOf(false) }
    Text(description, style = WatchTypography.Supporting, maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis)
    SecondaryAction(if (expanded) "收起介绍" else "展开歌单介绍") { expanded = !expanded }
}
