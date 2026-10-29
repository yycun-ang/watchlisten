package com.watchlisten.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.data.LocalFileDetails
import com.watchlisten.music.data.localStorageSize
import com.watchlisten.music.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

enum class LibrarySort(val label: String) { RECENT("原始顺序"), TITLE("按歌名"), ARTIST("按歌手") }

internal fun filterLibrary(tracks: List<Track>, query: String, sort: LibrarySort): List<Track> {
    val keyword = query.trim()
    val found = if (keyword.isBlank()) tracks else tracks.filter {
        it.title.contains(keyword, true) || it.artist.contains(keyword, true)
    }
    val comparator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    return when (sort) {
        LibrarySort.RECENT -> found
        LibrarySort.TITLE -> found.sortedWith { a, b -> comparator.compare(a.title, b.title) }
        LibrarySort.ARTIST -> found.sortedWith { a, b -> comparator.compare(a.artist, b.artist) }
    }
}

@Composable
internal fun MusicLibraryScreen(route: String, graph: AppGraph, open: (String) -> Unit) {
    val cloud by graph.favorites.state.collectAsStateWithLifecycle()
    val history by graph.library.history.collectAsStateWithLifecycle()
    val local by graph.localMusic.state.collectAsStateWithLifecycle()
    val legacy by graph.library.favorites.collectAsStateWithLifecycle()
    val session by graph.sessions.session.collectAsStateWithLifecycle()
    var query by rememberSaveable(route) { mutableStateOf("") }
    var sort by rememberSaveable(route) { mutableIntStateOf(0) }
    var searching by rememberSaveable(route) { mutableStateOf(false) }
    var importingOptions by rememberSaveable(route) { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var migrationResult by remember { mutableStateOf<String?>(null) }
    var selectingFiles by rememberSaveable(route) { mutableStateOf(false) }
    var selectedFiles by rememberSaveable(route) { mutableStateOf(listOf<String>()) }
    var confirmingFilesDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val restored = remember(route) { graph.library.scrollFor(route) }
    val list = rememberLazyListState(restored.first, restored.second)
    val tracks = when (route) { "favorites" -> cloud.tracks; "history" -> history; else -> local.tracks }
    val displayed = remember(tracks, query, sort) { filterLibrary(tracks, query, LibrarySort.entries[sort]) }
    DisposableEffect(route) {
        onDispose { graph.library.saveScroll(route, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
    }
    LaunchedEffect(route, session?.userId) { if (route == "favorites" && session != null) graph.favorites.refresh() }
    LaunchedEffect(session?.userId, session?.token) { migrating = false; migrationResult = null }
    LaunchedEffect(local.tracks) {
        selectedFiles = selectedFiles.filter { id -> local.tracks.any { it.id == id } }
        if (selectedFiles.isEmpty()) confirmingFilesDelete = false
    }
    LaunchedEffect(route, local.message, local.error, local.importing, local.lyricImporting, local.exporting) {
        val message = local.message
        if (route == "local-music" && message != null && local.error == null &&
            !local.importing && !local.lyricImporting && !local.exporting) {
            delay(4000)
            if (graph.localMusic.state.value.message == message) graph.localMusic.clearMessage()
        }
    }
    fun deleteLocalFiles(tracksToRemove: List<Track>) {
        graph.localMusic.removeAll(tracksToRemove) { confirmedIds ->
            confirmedIds.forEach { id ->
                graph.playback.forgetLocalTrack(id)
                graph.library.forgetImportedTrack(id)
            }
        }
    }
    RoundFrame(list) {
        Column(Modifier.fillMaxSize().padding(bottom = if (route == "favorites") 18.dp else 0.dp)) {
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Title(when (route) { "favorites" -> "酷狗我喜欢"; "history" -> "最近播放"; else -> "离线音乐" },
                when {
                    route == "favorites" && !cloud.loaded -> "与酷狗账号相连"
                    route == "favorites" && cloud.updatedAtMs > 0 -> "${tracks.size} 首 · 上次同步 ${SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(Date(cloud.updatedAtMs))}"
                    route == "local-music" && local.detailsReady -> "${tracks.size} 首 · ${localStorageSize(local.totalBytes)}"
                    else -> "${tracks.size} 首"
                }) }
            if (route == "favorites" && session == null) item {
                WatchEmptyState("登录酷狗查看喜欢", "与手机酷狗使用同一个账号", icon = R.drawable.ic_user,
                    actionLabel = "扫码登录", onAction = { open("account") })
            } else {
                if (route == "favorites") {
                    if (cloud.loading) item { WatchLoadingState("正在同步酷狗") }
                    if (legacy.isNotEmpty()) item {
                        MigrationCard(legacy.size, migrating, migrationResult) {
                            migrating = true
                            scope.launch {
                                try {
                                    val result = graph.favorites.migrate(legacy) { graph.library.removeLegacyFavorite(it.id) }
                                    migrationResult = if (result.remainingIds.isEmpty()) "旧收藏已全部迁入酷狗"
                                        else "已确认 ${result.confirmedIds.size} 首，剩余 ${result.remainingIds.size} 首可重试"
                                } finally { migrating = false }
                            }
                        }
                    }
                }
                if (route == "local-music") {
                    if (importingOptions || tracks.isEmpty()) {
                        item { WatchListItem("手机传歌", { open("transfer") }, subtitle = "同 Wi-Fi 传入自有音频", icon = R.drawable.ic_wifi) }
                        item { SecondaryAction("从手表文件导入", enabled = !local.importing) { open("import-music") } }
                        if (tracks.isNotEmpty()) item {
                            SecondaryAction(if (selectingFiles) "结束文件多选" else "多选整理文件") {
                                selectingFiles = !selectingFiles
                                selectedFiles = emptyList()
                                confirmingFilesDelete = false
                            }
                        }
                    }
                    if (local.importing) item { WatchLoadingState("正在导入音频") }
                    if (local.lyricImporting) item { WatchLoadingState("正在关联本地歌词") }
                    if (local.exporting) item { WatchLoadingState("正在导出原始文件") }
                    local.error?.let { item { InlineBanner(it, color = WatchTokens.Error) } }
                    if (local.error == null) local.message?.let { item { Notice(it) } }
                }
                if (tracks.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            SmallIcon(R.drawable.ic_search, "搜索此音乐库", { searching = !searching }, active = searching)
                            androidx.compose.material3.TextButton({ sort = (sort + 1) % LibrarySort.entries.size }, Modifier.height(48.dp)) {
                                Text(when (LibrarySort.entries[sort]) { LibrarySort.RECENT -> "原始顺序"; LibrarySort.TITLE -> "按歌名"; LibrarySort.ARTIST -> "按歌手" },
                                    style = WatchTypography.Supporting, color = WatchTokens.TextSecondary)
                            }
                            when (route) {
                                "favorites" -> SmallIcon(R.drawable.ic_refresh, "刷新酷狗喜欢", graph.favorites::refresh, enabled = !cloud.loading)
                                "local-music" -> SmallIcon(R.drawable.ic_more, "离线文件操作", { importingOptions = !importingOptions }, active = importingOptions)
                            }
                        }
                    }
                    if (searching || query.isNotBlank()) item { LibrarySearch(query, { query = it }, "搜索歌名或歌手") }
                    if (route == "local-music" && selectingFiles) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(displayed.isNotEmpty() && displayed.all { it.id in selectedFiles }, { checked ->
                                selectedFiles = if (checked) (selectedFiles + displayed.map(Track::id)).distinct()
                                    else selectedFiles - displayed.map(Track::id).toSet()
                            })
                            Text("选择当前结果 · ${selectedFiles.size} 首", style = WatchTypography.Supporting)
                        }
                        if (selectedFiles.isNotEmpty()) {
                            if (!confirmingFilesDelete) SecondaryAction("删除所选文件", color = WatchTokens.Error) {
                                confirmingFilesDelete = true
                            } else {
                                Notice("从手表删除所选 ${selectedFiles.size} 首及关联歌词？")
                                PrimaryAction("确认删除 ${selectedFiles.size} 首") {
                                    deleteLocalFiles(local.tracks.filter { it.id in selectedFiles })
                                    selectedFiles = emptyList(); confirmingFilesDelete = false
                                }
                                SecondaryAction("保留文件") { confirmingFilesDelete = false }
                            }
                        }
                    }
                    items(displayed, key = { it.id }) { track ->
                        val details = local.files[track.id]
                        if (route == "local-music" && selectingFiles) {
                            val toggle = { selectedFiles = if (track.id in selectedFiles) selectedFiles - track.id else selectedFiles + track.id }
                            WatchListItem(track.title, toggle, subtitle = localFileDescription(details),
                                icon = R.drawable.ic_library, trailingContent = {
                                    Checkbox(track.id in selectedFiles, { toggle() })
                                })
                        } else {
                            val actions: (@Composable () -> Unit)? = if (route == "local-music") {
                                { LocalFileActions(track, details, graph, open) { deleteLocalFiles(listOf(track)) } }
                            } else null
                            TrackRow(track, graph, open, displayed, localActions = actions)
                        }
                    }
                    if (displayed.isEmpty()) item { Notice("没有匹配的歌曲") }
                } else if (route != "favorites" || (cloud.loaded && cloud.error == null && !cloud.loading)) item {
                    WatchEmptyState(if (route == "favorites") "云端还没有喜欢的歌曲" else "从一首歌开始",
                        when (route) {
                            "local-music" -> "导入后无需联网即可播放"
                            "history" -> "你听过的歌曲会留在这里"
                            else -> "在歌曲菜单中点亮爱心，同步到酷狗"
                        },
                        icon = if (route == "favorites") R.drawable.ic_heart else R.drawable.ic_library)
                }
            }
        }
        if (route == "favorites") CloudFeedbackStrip(cloud.message, cloud.error?.message(), cloud.undoAvailable,
            cloud.undoExpiresAtMs, graph.favorites::undo, graph.favorites::refresh,
            onDismiss = { graph.favorites.clearMessage(); graph.favorites.dismissUndo() },
            busy = cloud.loading || cloud.pendingIds.isNotEmpty(),
            retryLabel = if (cloud.confirmationStatus == CloudConfirmationStatus.AWAITING_CONFIRMATION) "确认" else "刷新")
        }
    }
}

@Composable
private fun LocalFileActions(track: Track, details: LocalFileDetails?, graph: AppGraph,
                             open: (String) -> Unit, remove: () -> Unit) {
    val state by graph.localMusic.state.collectAsStateWithLifecycle()
    var confirmingDelete by remember(track.id) { mutableStateOf(false) }
    var confirmingLyricsDelete by remember(track.id) { mutableStateOf(false) }
    val busy = state.lyricImporting || state.exporting
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localFileDescription(details), style = WatchTypography.Supporting,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        when {
            confirmingDelete -> {
                Notice("从手表删除音频、封面和关联歌词？")
                SecondaryAction("确认删除原文件", enabled = !busy, color = WatchTokens.Error) { remove() }
                SecondaryAction("保留文件") { confirmingDelete = false }
            }
            confirmingLyricsDelete -> {
                Notice("只移除这首歌的关联歌词，保留音频？")
                SecondaryAction("确认移除歌词", enabled = !busy, color = WatchTokens.Error) {
                    confirmingLyricsDelete = false
                    graph.localMusic.removeLyrics(track)
                }
                SecondaryAction("保留歌词") { confirmingLyricsDelete = false }
            }
            else -> {
                SecondaryAction(if (details?.hasLyrics == true) "替换关联歌词" else "导入本地 LRC 歌词", enabled = !busy) {
                    if (graph.localMusic.prepareLyricsImport(track)) open("import-lyrics")
                }
                SecondaryAction("导出原始音频文件", enabled = !busy) {
                    if (graph.localMusic.prepareExport(track)) open("export-music")
                }
                if (details?.hasLyrics == true) SecondaryAction("移除关联歌词", enabled = !busy) { confirmingLyricsDelete = true }
                SecondaryAction("删除原文件", enabled = !busy, color = WatchTokens.Error) { confirmingDelete = true }
            }
        }
    }
}

private fun localFileDescription(details: LocalFileDetails?): String = details?.let {
    listOf(it.format.takeUnless { format -> format == "audio" }.orEmpty().uppercase(),
        localStorageSize(it.audioBytes), if (it.hasLyrics) "已关联歌词" else "").filter(String::isNotBlank).joinToString(" · ")
} ?: "正在读取文件信息"

@Composable
private fun MigrationCard(count: Int, busy: Boolean, result: String?, migrate: () -> Unit) {
    Surface(color = WatchTokens.Surface, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("迁移旧收藏", style = WatchTypography.CardTitle)
            Text("$count 首旧记录可迁入当前酷狗账号，失败的歌曲会保留待重试。", style = WatchTypography.Supporting)
            result?.let { Text(it, style = WatchTypography.Supporting, color = WatchTokens.Secondary) }
            SecondaryAction(if (busy) "正在逐首确认" else "迁入当前酷狗账号", enabled = !busy, onClick = migrate)
        }
    }
}

@Composable
internal fun LibrarySearch(query: String, change: (String) -> Unit, hint: String) {
    Surface(color = WatchTokens.Surface, shape = RoundedCornerShape(14.dp)) {
        BasicTextField(query, { change(it.take(100)) }, textStyle = WatchTypography.Body,
            singleLine = true, cursorBrush = SolidColor(WatchTokens.Primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 14.dp),
            decorationBox = { inner -> Box { if (query.isBlank()) Text(hint, style = WatchTypography.Supporting); inner() } })
    }
}
