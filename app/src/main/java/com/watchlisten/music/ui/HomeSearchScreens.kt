package com.watchlisten.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.model.Track
import com.watchlisten.music.model.SessionData
import com.watchlisten.music.model.TrackRights
import com.watchlisten.music.model.MusicCollection
import com.watchlisten.music.model.CollectionKind
import com.watchlisten.music.model.DiscoveryKind
import com.watchlisten.music.model.ListeningAccess
import com.watchlisten.music.model.message
import com.watchlisten.music.model.sameKugouTrack
import com.watchlisten.music.playback.PlaybackSnapshot
import kotlinx.coroutines.delay

private const val MAX_QUERY_LENGTH = 100
private const val HOME_RECENT_LIMIT = 3

@Composable
fun HomeScreen(
    graph: AppGraph,
    music: MusicState,
    playback: PlaybackSnapshot,
    open: (String) -> Unit
) {
    val favorites by graph.favorites.state.collectAsStateWithLifecycle()
    val history by graph.library.history.collectAsStateWithLifecycle()
    val session by graph.sessions.session.collectAsStateWithLifecycle()
    val restored = remember { graph.library.scrollFor("home") }
    val listState = rememberLazyListState(restored.first, restored.second)
    val presentation = music.presentPlayback(playback)
    val continueTrack = presentation.track

    DisposableEffect(Unit) {
        onDispose {
            graph.library.saveScroll(
                "home",
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    RoundScreenScaffold(listState, bottomSafeArea = 18.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = WatchTokens.SpaceExtraLarge),
            verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)
        ) {
            item {
                HomeSearchEntry(
                    session = session,
                    onAccount = { open("account") },
                    onClick = { open("search") }
                )
            }
            if (continueTrack != null) {
                item {
                    ContinuePlayingCard(
                        track = continueTrack,
                        playback = playback.copy(playing = presentation.playing, buffering = presentation.buffering),
                        preparing = presentation.buffering,
                        failed = presentation.error != null,
                        onOpen = { open("player") },
                        onToggle = {
                            if (presentation.error != null) graph.music.retryPlayback()
                            else graph.playback.toggle()
                        }
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CollectionCard(
                        title = "酷狗喜欢",
                        detail = when {
                            session == null -> "登录后同步"
                            favorites.loading && !favorites.loaded -> "正在同步"
                            favorites.error != null -> "同步未完成"
                            favorites.loaded -> "${favorites.tracks.size} 首歌曲"
                            else -> "点按同步"
                        },
                        icon = R.drawable.ic_heart,
                        accent = Color(0xFFECC7C2),
                        modifier = Modifier.weight(1f),
                        onClick = { open(if (session == null) "account" else "favorites") }
                    )
                    CollectionCard(
                        title = "最近",
                        detail = "${history.size} 首听过",
                        icon = R.drawable.ic_clock,
                        accent = Color(0xFFD6D5CC),
                        modifier = Modifier.weight(1f),
                        onClick = { open("history") }
                    )
                }
            }
            item {
                HomeDestination(
                    title = "我的音乐",
                    detail = "云端喜欢与收听记录",
                    icon = R.drawable.ic_library,
                    onClick = { open("library") }
                )
            }
            item { HomeDestination("发现音乐", "排行榜、精选歌单与每日推荐", R.drawable.ic_quality) { open("discover") } }
            item { HomeDestination("分类搜索", "歌曲、歌手、专辑与歌单", R.drawable.ic_search) { open("catalog-search") } }
            item { HomeDestination("酷狗云歌单", "手机上的创建与收藏", R.drawable.ic_user) { open("cloud") } }
            item { RecentHeader(history.size) { open("history") } }
            if (history.isEmpty()) {
                item {
                    Text(
                        "听过的歌，会留在这里。",
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                        style = WatchTypography.Supporting,
                        color = WatchTokens.TextMuted
                    )
                }
            } else {
                items(history.take(HOME_RECENT_LIMIT), key = { it.id }) { track ->
                    TrackRow(track, graph, open, history)
                }
            }
            item {
                HomeDestination(
                    title = "播放队列",
                    detail = "${playback.queue.size} 首待播",
                    icon = R.drawable.ic_queue,
                    onClick = { open("queue") }
                )
            }
            item {
                HomeDestination(
                    title = "偏好设置",
                    detail = "音质、网络与显示",
                    icon = R.drawable.ic_settings,
                    onClick = { open("settings") }
                )
            }
        }
    }
}

@Composable
fun SearchScreen(graph: AppGraph, music: MusicState, playback: PlaybackSnapshot,
                 open: (String) -> Unit, onVoiceSearch: (() -> Unit)? = null) {
    val browse by graph.browse.state.collectAsStateWithLifecycle()
    BrowseLifecycle("search", graph)
    val searches by graph.library.searches.collectAsStateWithLifecycle()
    val restored = remember { graph.library.scrollFor("search") }
    val listState = rememberLazyListState(restored.first, restored.second)
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf(music.query) }
    var submittedQuery by rememberSaveable { mutableStateOf(music.query) }

    LaunchedEffect(music.query) {
        if (music.query.isNotBlank() && music.query != submittedQuery) {
            query = music.query
            submittedQuery = music.query
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            graph.library.saveScroll(
                "search",
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    val submit: (String) -> Unit = { rawQuery ->
        val keyword = rawQuery.trim().take(MAX_QUERY_LENGTH)
        if (keyword.isNotEmpty()) {
            query = keyword
            submittedQuery = keyword
            keyboard?.hide()
            graph.music.search(keyword)
        }
    }
    val presentation = music.presentPlayback(playback)
    val current = presentation.track

    RoundScreenScaffold(listState, bottomSafeArea = 18.dp) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = if (current == null) WatchTokens.SpaceLarge else 54.dp
                ),
                verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)
            ) {
                item {
                    SearchField(
                        query = query,
                        onQueryChange = {
                            query = it.take(MAX_QUERY_LENGTH)
                            if (query.isBlank()) submittedQuery = ""
                        },
                        onClear = {
                            query = ""
                            submittedQuery = ""
                        },
                        onSearch = { submit(query) },
                        onVoiceSearch = onVoiceSearch
                    )
                }

                item {
                    TextButton(
                        onClick = {
                            graph.discovery.search(query, DiscoveryKind.ARTISTS)
                            keyboard?.hide()
                            open("catalog-search")
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("搜索歌手、专辑与歌单", style = WatchTypography.Supporting,
                            color = WatchTokens.Primary)
                    }
                }

                when {
                    query.isBlank() -> {
                        searchHistory(searches, graph, submit)
                        if (browse.hotWords.isNotEmpty()) {
                            item { SectionLabel("大家都在搜") }
                            items(browse.hotWords.take(8), key = { "hot-$it" }) { word ->
                                HomeDestination(word, icon = R.drawable.ic_search) { submit(word) }
                            }
                            if (browse.hotWords.size > 8) item { SecondaryAction("全部热搜") { open("hot-search") } }
                        } else if (browse.hotError != null) item {
                            InlineBanner("热搜暂不可用", actionLabel = "重试", onAction = { graph.browse.loadHotWords(true) })
                        }
                    }
                    submittedQuery.isBlank() || query.trim() != submittedQuery -> item {
                        WatchEmptyState(
                            title = "输入后按搜索",
                            message = "可搜索歌曲或歌手",
                            icon = R.drawable.ic_search
                        )
                    }
                    music.searching && music.tracks.isEmpty() -> item {
                        WatchLoadingState("正在搜索", submittedQuery)
                    }
                    music.searchError != null && music.tracks.isEmpty() -> item {
                        WatchErrorState(
                            title = "搜索失败",
                            message = music.searchError.message(),
                            actionLabel = "重新搜索",
                            onAction = { graph.music.search(submittedQuery) }
                        )
                    }
                    music.tracks.isEmpty() -> item {
                        WatchEmptyState(
                            title = "没有找到歌曲",
                            message = "换个歌名或歌手试试",
                            icon = R.drawable.ic_search
                        )
                    }
                    else -> searchResults(graph, music, submittedQuery, open)
                }
            }

            if (current != null) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    MiniPlayer(
                        trackTitle = current.title,
                        trackArtist = current.artist,
                        playing = presentation.playing,
                        buffering = presentation.buffering,
                        onToggle = {
                            if (presentation.error != null) graph.music.retryPlayback()
                            else graph.playback.toggle()
                        },
                        onOpen = { open("player") },
                        failed = presentation.error != null,
                        compact = true
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchHistory(
    searches: List<String>,
    graph: AppGraph,
    submit: (String) -> Unit
) {
    if (searches.isEmpty()) {
        item {
            WatchEmptyState(
                title = "搜索音乐",
                message = "最近搜索会保存在这里",
                icon = R.drawable.ic_search
            )
        }
        return
    }

    item { SectionLabel("最近搜索") }
    items(searches.take(5), key = { it }) { keyword ->
        HomeDestination(
            title = keyword,
            icon = R.drawable.ic_clock,
            onClick = { submit(keyword) }
        )
    }
    item {
        LowEmphasisAction("清空搜索历史") { graph.library.clearSearches() }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(
    graph: AppGraph,
    music: MusicState,
    submittedQuery: String,
    open: (String) -> Unit
) {
    item { Text("${music.total.coerceAtLeast(music.tracks.size)} 首结果",
        style = WatchTypography.Metadata, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp)) }
    items(music.tracks, key = { it.id }) { track ->
        TrackRow(track, graph, open, music.tracks)
    }

    when {
        music.searching -> item {
            Box(
                Modifier.fillMaxWidth().height(WatchTokens.ListItemHeight),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    Modifier.size(28.dp),
                    color = WatchTokens.Primary,
                    strokeWidth = 2.dp
                )
            }
        }
        music.searchError != null -> item {
            InlineBanner(
                message = music.searchError.message(),
                color = WatchTokens.Error,
                actionLabel = "重试",
                onAction = { graph.music.search(submittedQuery, true) }
            )
        }
        music.hasMore -> item {
            LowEmphasisAction("加载更多") { graph.music.search(submittedQuery, true) }
        }
    }
}

@Composable
internal fun HomeSearchEntry(session: SessionData?, onAccount: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = WatchTokens.MinimumTouch)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxWidth().height(34.dp).clip(CircleShape)
                .background(WatchTokens.SurfaceRaised))
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(painterResource(R.drawable.ic_search), null, Modifier.size(17.dp),
                    tint = WatchTokens.TextSecondary)
                Text("搜索音乐", style = WatchTypography.Label,
                    color = WatchTokens.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onAccount, modifier = Modifier.size(WatchTokens.MinimumTouch)
            .semantics { contentDescription = if (session != null) "管理酷狗账号" else "登录酷狗账号" }) {
            key(session?.userId) {
                Cover(session?.profile?.avatarUrl.orEmpty(), Modifier.size(30.dp), cornerRadius = 15.dp,
                    fallback = {
                        Icon(painterResource(R.drawable.ic_user), null, Modifier.size(17.dp),
                            tint = WatchTokens.TextSecondary)
                    })
            }
        }
    }
}

/** 借鉴媒体浏览页的集合层级；小图标和数量用于区分内容，不做整行大按钮。 */
@Composable
private fun CollectionCard(
    title: String,
    detail: String,
    icon: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(18.dp),
        color = WatchTokens.Surface
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(painterResource(icon), null, Modifier.size(16.dp), tint = accent)
                Text(title, style = WatchTypography.CardTitle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Text(detail, style = WatchTypography.Metadata, color = WatchTokens.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeDestination(
    title: String,
    detail: String? = null,
    icon: Int,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = WatchTokens.MinimumTouch)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = WatchTokens.TextSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = WatchTypography.Label, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = WatchTypography.Metadata,
                color = WatchTokens.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = WatchTokens.TextMuted, fontSize = 20.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun ContinuePlayingCard(
    track: Track,
    playback: PlaybackSnapshot,
    preparing: Boolean,
    failed: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = WatchTokens.SurfaceRaised
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(
                track.cover,
                Modifier.size(32.dp),
                cornerRadius = 10.dp,
                fallback = { Brand(22) }
            )
            Spacer(Modifier.width(WatchTokens.SpaceSmall))
            Column(Modifier.weight(1f)) {
                Text(when { failed -> "重试播放"; preparing -> "正在准备";
                    playback.playing -> "正在播放"; else -> "继续收听" },
                    style = WatchTypography.Metadata, color = WatchTokens.TextSecondary)
                Text(
                    track.title,
                    style = WatchTypography.CardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    style = WatchTypography.Supporting,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (preparing || playback.buffering) {
                Box(Modifier.size(WatchTokens.MinimumTouch), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = WatchTokens.Primary,
                        strokeWidth = 2.dp
                    )
                }
            } else {
                IconButton(onClick = onToggle, modifier = Modifier.size(WatchTokens.MinimumTouch)) {
                    Icon(
                        painterResource(when { failed -> R.drawable.ic_refresh;
                            playback.playing -> R.drawable.ic_pause; else -> R.drawable.ic_play }),
                        contentDescription = when { failed -> "重试播放";
                            playback.playing -> "暂停"; else -> "继续播放" },
                        modifier = Modifier.size(WatchTokens.IconSize),
                        tint = if (playback.playing) WatchTokens.Primary else WatchTokens.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentHeader(total: Int, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = WatchTokens.MinimumTouch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "最近播放",
            style = WatchTypography.Supporting,
            modifier = Modifier.weight(1f).padding(start = WatchTokens.SpaceExtraSmall)
        )
        TextButton(
            onClick = onOpen,
            modifier = Modifier.heightIn(min = WatchTokens.MinimumTouch),
            contentPadding = PaddingValues(horizontal = WatchTokens.SpaceSmall),
            colors = ButtonDefaults.textButtonColors(contentColor = WatchTokens.TextSecondary)
        ) {
            Text(if (total > HOME_RECENT_LIMIT) "全部 $total" else "全部",
                style = WatchTypography.Metadata, maxLines = 1)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    onVoiceSearch: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = WatchTokens.MinimumTouch),
        singleLine = true,
        textStyle = WatchTypography.Body,
        cursorBrush = SolidColor(WatchTokens.Primary),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { input ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(WatchTokens.CompactActionHeight)
                    .clip(CircleShape)
                    .background(WatchTokens.SurfaceRaised)
                    .border(1.dp, if (focused) WatchTokens.TextSecondary else Color.Transparent,
                        CircleShape))
                Row(Modifier.fillMaxSize().padding(start = WatchTokens.SpaceMedium,
                    end = if (query.isEmpty() && onVoiceSearch == null) WatchTokens.SpaceMedium else 0.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_search), null, Modifier.size(WatchTokens.IconSize),
                        tint = WatchTokens.TextSecondary)
                    Spacer(Modifier.width(WatchTokens.SpaceSmall))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("歌曲、歌手", style = WatchTypography.Body,
                            color = WatchTokens.TextSecondary)
                        input()
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear, modifier = Modifier.size(WatchTokens.MinimumTouch)) {
                            Icon(painterResource(R.drawable.ic_close), "清空输入", Modifier.size(16.dp),
                                tint = WatchTokens.TextSecondary)
                        }
                    } else if (onVoiceSearch != null) {
                        IconButton(onClick = onVoiceSearch, modifier = Modifier.size(WatchTokens.MinimumTouch)) {
                            Icon(painterResource(R.drawable.ic_mic), "语音搜索", Modifier.size(16.dp),
                                tint = WatchTokens.Primary)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun LowEmphasisAction(text: String, onClick: () -> Unit) {
    SecondaryAction(text, onClick = onClick)
}

/** 歌曲行正文保持两行，更多操作使用独立的 48dp 命中区。 */
@Composable
internal fun TrackRow(
    track: Track,
    graph: AppGraph,
    open: (String) -> Unit,
    tracks: List<Track>,
    onRemoveLocal: (() -> Unit)? = null,
    localActions: (@Composable () -> Unit)? = null
) {
    var expanded by remember(track.id) { mutableStateOf(false) }
    var feedback by remember(track.id) { mutableStateOf<String?>(null) }
    var feedbackError by remember(track.id) { mutableStateOf(false) }
    var awaitingFavorite by remember(track.id) { mutableStateOf(false) }
    val favorites by graph.favorites.state.collectAsStateWithLifecycle()
    val access by graph.gateway.sourceAccess.collectAsStateWithLifecycle()
    val favorite = favorites.tracks.any { sameKugouTrack(it, track) }
    val pendingFavorite = track.id in favorites.pendingIds
    LaunchedEffect(feedback) {
        if (feedback != null) { delay(3500); feedback = null }
    }
    LaunchedEffect(favorites.pendingIds, favorites.message, favorites.error) {
        if (awaitingFavorite && !pendingFavorite) {
            feedback = if (favorites.userId == graph.sessions.session.value?.userId)
                favorites.error?.message() ?: favorites.message else null
            feedbackError = favorites.error != null
            awaitingFavorite = false
        }
    }

    Column {
        Surface(
            onClick = {
                graph.music.play(track, tracks)
                open("player")
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = WatchTokens.ListItemHeight),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = WatchTokens.ListItemHeight)
                    .padding(start = 6.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(track.cover, Modifier.size(28.dp), cornerRadius = 7.dp,
                    fallback = { Brand(20) })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(track.title, style = WatchTypography.CardTitle,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!track.isRemote && !track.isLocal) TrackRightsBadge(track.rights)
                    }
                    Text(
                        trackSubtitle(track, access[track.id] ?: ListeningAccess.UNKNOWN),
                        style = WatchTypography.Supporting,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(WatchTokens.MinimumTouch)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_more),
                        contentDescription = "歌曲更多",
                        modifier = Modifier.size(16.dp),
                        tint = WatchTokens.TextMuted
                    )
                }
            }
        }
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().padding(top = WatchTokens.SpaceExtraSmall),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                SmallIcon(
                    R.drawable.ic_plus,
                    "加入队列",
                    onClick = {
                        graph.playback.add(track) { error ->
                            feedbackError = error != null
                            feedback = error?.message() ?: "已加入队列"
                        }
                        expanded = false
                    }
                )
                SmallIcon(
                    R.drawable.ic_next,
                    "下一首播放",
                    onClick = {
                        graph.playback.add(track, true) { error ->
                            feedbackError = error != null
                            feedback = error?.message() ?: "将在下一首播放"
                        }
                        expanded = false
                    }
                )
                if (!track.isRemote && !track.isLocal) SmallIcon(
                    R.drawable.ic_heart,
                    if (pendingFavorite) "正在同步酷狗喜欢" else if (favorite) "取消酷狗喜欢" else "喜欢到酷狗",
                    onClick = {
                        if (graph.sessions.session.value == null) open("account") else {
                            graph.favorites.toggle(track)
                            awaitingFavorite = true
                            feedbackError = false
                            feedback = "正在同步酷狗…"
                        }
                        expanded = false
                    },
                    active = favorite,
                    accent = WarmRose,
                    enabled = !pendingFavorite
                )
            }
            if (track.isLocal && localActions != null) localActions()
            if (track.isLocal && localActions == null && onRemoveLocal != null) SecondaryAction("从手表删除文件", color = WatchTokens.Error) {
                expanded = false
                onRemoveLocal()
            }
            if (track.albumId > 0) SecondaryAction("查看所在专辑") {
                expanded = false
                open(graph.browse.open(MusicCollection("${track.albumId}", "专辑歌曲", CollectionKind.ALBUM,
                    cover = track.cover, subtitle = track.title)))
            }
            if (!track.isRemote && !track.isLocal) SecondaryAction("加入酷狗歌单") {
                expanded = false
                if (graph.sessions.session.value == null) open("account") else {
                    graph.cloudManager.chooseTrack(track)
                    open("cloud-manage")
                }
            }
        }
        feedback?.let { message ->
            Text(message, style = WatchTypography.Supporting,
                color = if (feedbackError) WatchTokens.Error else WatchTokens.Secondary,
                modifier = Modifier.padding(start = 42.dp, bottom = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** 曲库标记描述歌曲，取源结果描述本次账号可听范围，两者独立。 */
@Composable
private fun TrackRightsBadge(rights: TrackRights) {
    if (rights != TrackRights.VIP && rights != TrackRights.LIMITED_FREE) return
    val color = if (rights == TrackRights.VIP) WatchTokens.Warning else WatchTokens.Secondary
    Text(rights.label, color = color, fontSize = 9.sp, lineHeight = 11.sp,
        fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = .12f))
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .semantics { contentDescription = if (rights == TrackRights.VIP) "VIP 歌曲" else "限时免费歌曲" })
}

/** 缺少权益数据保留未知；成功取源后才显示本次完整、试听或拒绝。 */
internal fun trackSubtitle(track: Track, access: ListeningAccess = ListeningAccess.UNKNOWN): String {
    val duration = if (track.durationMs > 0) clockTime(track.durationMs) else ""
    if (track.isRemote) return listOf(track.artist, duration, "手机投放").filter { it.isNotBlank() }.joinToString(" · ")
    if (track.isLocal) return listOf(track.artist, duration, "本地音乐").filter { it.isNotBlank() }.joinToString(" · ")
    val permission = when (access) {
        ListeningAccess.FULL -> "可完整播放"
        ListeningAccess.PREVIEW -> "仅可试听"
        ListeningAccess.DENIED -> "无播放权限"
        ListeningAccess.UNKNOWN -> when (track.rights) {
            TrackRights.FREE -> "免费"
            TrackRights.VIP, TrackRights.LIMITED_FREE -> "以授权为准"
            TrackRights.UNKNOWN -> if (track.payType == -1) "" else "权益待确认"
        }
    }
    return listOf(track.artist, duration, permission)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}
