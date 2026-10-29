package com.watchlisten.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.domain.ArtistLibraryState
import com.watchlisten.music.model.*

@Composable
fun DiscoveryScreen(route: String, graph: AppGraph, open: (String) -> Unit) {
    DiscoveryLifecycle(route, graph)
    if (route == "catalog-search") CatalogSearchScreen(graph, open)
    else artistIdFromRoute(route)?.let { ArtistScreen(it, route, graph, open) }
}

@Composable
private fun DiscoveryLifecycle(route: String, graph: AppGraph) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(route, graph, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> graph.discovery.activate(route)
            Lifecycle.Event.ON_PAUSE -> graph.discovery.deactivate(route)
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) graph.discovery.activate(route)
        onDispose { lifecycle.removeObserver(observer); graph.discovery.deactivate(route) }
    }
}

@Composable
private fun CatalogSearchScreen(graph: AppGraph, open: (String) -> Unit) {
    val state by graph.discovery.state.collectAsStateWithLifecycle()
    val search = state.search
    var query by rememberSaveable { mutableStateOf(search.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(search.query) { query = search.query }
    fun submit(kind: DiscoveryKind = search.kind) {
        graph.discovery.search(query, kind)
        keyboard?.hide()
    }
    DiscoveryList("catalog-search", graph) {
        item { CatalogSearchField(query, { query = it.take(100) }, { submit() }) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                DiscoveryKind.entries.forEach { kind ->
                    TextButton({ submit(kind) }, Modifier.weight(1f).height(48.dp).semantics {
                        stateDescription = if (kind == search.kind) "已选择" else "未选择"
                    }, contentPadding = PaddingValues(0.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(kind.label, style = WatchTypography.Supporting,
                                color = if (kind == search.kind) WatchTokens.Primary else WatchTokens.TextSecondary)
                            Box(Modifier.width(12.dp).height(2.dp).background(
                                if (kind == search.kind) WatchTokens.Primary else Color.Transparent,
                                RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
        }
        when {
            search.query.isBlank() -> item { WatchEmptyState("分类找音乐", "输入歌曲、歌手、专辑或歌单名称", icon = R.drawable.ic_search) }
            search.loading && search.items.isEmpty() -> item { WatchLoadingState("正在搜索${search.kind.label}") }
            search.error != null && search.items.isEmpty() -> item {
                WatchErrorState("搜索暂不可用", search.error.message(), actionLabel = "重试", onAction = graph.discovery::retrySearch)
            }
            else -> {
                item { Text(search.total?.let { "${search.items.size} / $it 个结果" } ?: "已加载 ${search.items.size} 个结果",
                    style = WatchTypography.Metadata) }
                val tracks = search.items.filterIsInstance<DiscoveryItem.Song>().map { it.track }
                items(search.items, key = DiscoveryItem::key) { result -> when (result) {
                    is DiscoveryItem.Song -> TrackRow(result.track, graph, open, tracks)
                    is DiscoveryItem.Artist -> ArtistRow(result.artist) { open(graph.discovery.openArtist(result.artist)) }
                    is DiscoveryItem.Collection -> DiscoveryCollectionRow(result.collection) { open(graph.browse.open(result.collection)) }
                } }
                if (search.items.isEmpty()) item { WatchEmptyState("没有匹配的${search.kind.label}", "换个关键词或分类再试试") }
                when {
                    search.loading -> item { WatchLoadingState("正在加载更多") }
                    search.error != null -> item { WatchErrorState("结果尚未加载完整", search.error.message(),
                        actionLabel = "重试", onAction = graph.discovery::retrySearch) }
                    search.hasMore -> item { SecondaryAction("加载更多${search.kind.label}") { graph.discovery.search(search.query, search.kind, true) } }
                }
            }
        }
    }
}

@Composable
private fun CatalogSearchField(query: String, change: (String) -> Unit, submit: () -> Unit) {
    Surface(color = WatchTokens.Surface, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(query, change, Modifier.weight(1f).semantics { contentDescription = "分类搜索关键词" },
                textStyle = WatchTypography.Body, singleLine = true, cursorBrush = SolidColor(WatchTokens.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }), decorationBox = { inner ->
                    Box { if (query.isBlank()) Text("输入名称", style = WatchTypography.Supporting); inner() }
                })
            IconButton(submit, Modifier.size(48.dp), enabled = query.isNotBlank()) {
                Icon(painterResource(R.drawable.ic_search), "开始分类搜索", Modifier.size(16.dp), tint = WatchTokens.Primary)
            }
        }
    }
}

@Composable
private fun ArtistScreen(id: Long, route: String, graph: AppGraph, open: (String) -> Unit) {
    val state by graph.discovery.state.collectAsStateWithLifecycle()
    val artist = state.artists[id] ?: ArtistLibraryState(id)
    var albums by rememberSaveable(route) { mutableStateOf(false) }
    var intro by rememberSaveable(route) { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(id, albums, lifecycleState, artist.albumsLoaded, artist.albumsError) {
        if (albums && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !artist.albumsLoaded && artist.albumsError == null)
            graph.discovery.loadArtistAlbums()
    }
    DiscoveryList(route, graph) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Cover(artist.profile?.avatar.orEmpty(), Modifier.size(42.dp), cornerRadius = 21.dp,
                    fallback = { Icon(painterResource(R.drawable.ic_user), null, Modifier.size(20.dp), tint = WatchTokens.TextSecondary) })
                Column(Modifier.weight(1f)) {
                    Text(artist.profile?.name ?: "歌手", style = WatchTypography.CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(artist.profile?.songCount?.let { "$it 首" }, artist.profile?.albumCount?.let { "$it 张专辑" })
                        .filterNotNull().joinToString(" · "), style = WatchTypography.Metadata)
                }
                if (artist.profile?.intro?.isNotBlank() == true) SmallIcon(R.drawable.ic_info, "歌手介绍", { intro = !intro }, active = intro)
            }
        }
        if (artist.profileLoading && artist.profile == null) item { WatchLoadingState("正在加载歌手资料") }
        artist.profileError?.let { item { InlineBanner(it.message(), color = WatchTokens.Warning,
            actionLabel = "重试", onAction = graph.discovery::refreshArtist) } }
        if (intro && artist.profile?.intro?.isNotBlank() == true) item {
            Text(artist.profile.intro, style = WatchTypography.Body, color = WatchTokens.TextSecondary)
            SecondaryAction("收起介绍") { intro = false }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ArtistTab("热门歌曲", !albums, Modifier.weight(1f)) { albums = false }
                ArtistTab("专辑", albums, Modifier.weight(1f)) { albums = true }
            }
        }
        if (!albums) {
            items(artist.songs, key = Track::id) { TrackRow(it, graph, open, artist.songs) }
            when {
                artist.songsLoading || (!artist.songsLoaded && artist.songsError == null) -> item { WatchLoadingState("正在加载歌曲") }
                artist.songsError != null -> item { WatchErrorState("歌曲暂未加载完整", artist.songsError.message(), actionLabel = "重试",
                    onAction = { graph.discovery.loadArtistSongs(artist.songsRetryAppend) }) }
                artist.songs.isEmpty() -> item { WatchEmptyState("暂时没有可显示的歌曲", "稍后刷新再看看") }
                artist.songsHasMore -> item { SecondaryAction("加载更多歌曲") { graph.discovery.loadArtistSongs(true) } }
            }
        } else {
            items(artist.albums, key = MusicCollection::key) { collection ->
                DiscoveryCollectionRow(collection) { open(graph.browse.open(collection)) }
            }
            when {
                artist.albumsLoading || (!artist.albumsLoaded && artist.albumsError == null) -> item { WatchLoadingState("正在加载专辑") }
                artist.albumsError != null -> item { WatchErrorState("专辑暂未加载完整", artist.albumsError.message(), actionLabel = "重试",
                    onAction = { graph.discovery.loadArtistAlbums(artist.albumsRetryAppend) }) }
                artist.albums.isEmpty() -> item { WatchEmptyState("暂时没有专辑", "稍后刷新再看看") }
                artist.albumsHasMore -> item { SecondaryAction("加载更多专辑") { graph.discovery.loadArtistAlbums(true) } }
            }
        }
        item { SecondaryAction("刷新歌手内容", enabled = !artist.profileLoading && !artist.songsLoading && !artist.albumsLoading,
            onClick = graph.discovery::refreshArtist) }
    }
}

@Composable
private fun ArtistTab(title: String, selected: Boolean, modifier: Modifier, choose: () -> Unit) {
    TextButton(choose, modifier.height(48.dp).semantics { stateDescription = if (selected) "已选择" else "未选择" }) {
        Text(title, style = WatchTypography.Supporting, color = if (selected) WatchTokens.Primary else WatchTokens.TextSecondary)
    }
}

@Composable
private fun ArtistRow(artist: MusicArtist, open: () -> Unit) = DiscoveryResultRow(artist.name,
    listOf(artist.songCount?.let { "$it 首歌曲" }, artist.albumCount?.let { "$it 张专辑" }).filterNotNull().joinToString(" · "),
    artist.avatar, true, open)

@Composable
private fun DiscoveryCollectionRow(collection: MusicCollection, open: () -> Unit) = DiscoveryResultRow(collection.title,
    listOf(collection.count?.let { "$it 首" }, collection.subtitle.takeIf(String::isNotBlank)).filterNotNull().joinToString(" · "),
    collection.cover, false, open)

@Composable
private fun DiscoveryResultRow(title: String, subtitle: String, cover: String, round: Boolean, open: () -> Unit) {
    Surface(onClick = open, color = Color.Transparent, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cover(cover, Modifier.size(30.dp), cornerRadius = if (round) 15.dp else 8.dp, fallback = { Brand(20) })
            Column(Modifier.weight(1f)) {
                Text(title, style = WatchTypography.CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = WatchTypography.Supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(12.dp), tint = WatchTokens.TextMuted)
        }
    }
}

@Composable
private fun DiscoveryList(route: String, graph: AppGraph, content: LazyListScope.() -> Unit) {
    val restored = remember(route) { graph.library.scrollFor(route) }
    val list = rememberLazyListState(restored.first, restored.second)
    DisposableEffect(route) { onDispose { graph.library.saveScroll(route, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) } }
    RoundFrame(list) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 34.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}
