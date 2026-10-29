package com.watchlisten.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CloudManagementScreen(graph: AppGraph, open: (String) -> Unit) {
    val state by graph.cloudManager.state.collectAsStateWithLifecycle()
    val session by graph.sessions.session.collectAsStateWithLifecycle()
    var name by rememberSaveable(state.selected?.collection?.key) { mutableStateOf("") }
    var query by rememberSaveable(state.selected?.collection?.key) { mutableStateOf("") }
    var rename by remember(state.selected?.collection?.key) { mutableStateOf(false) }
    var deleting by remember(state.selected?.collection?.key) { mutableStateOf(false) }
    var searching by remember(state.selected?.collection?.key) { mutableStateOf(false) }
    var options by remember(state.selected?.collection?.key) { mutableStateOf(false) }
    var selecting by remember(state.selected?.collection?.key) { mutableStateOf(false) }
    var selectedIds by remember(state.selected?.collection?.key) { mutableStateOf(setOf<Long>()) }
    var abandoningCreate by remember(state.pendingCreate?.operationId) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session?.userId, session?.token) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && graph.sessions.session.value != null) graph.cloudManager.refreshIfStale()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && session != null) graph.cloudManager.refreshIfStale()
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.songs) { selectedIds = selectedIds.intersect(state.songs.map { it.fileId }.toSet()) }
    val selected = state.selected
    RoundFrame {
        Column(Modifier.fillMaxSize().padding(bottom = 18.dp)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Title(if (state.addingTrack != null) "加入酷狗歌单" else selected?.collection?.title ?: "管理酷狗歌单",
                if (state.updatedAtMs > 0) "上次同步 ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(state.updatedAtMs))}" else null) }
            if (session == null) item {
                WatchEmptyState("登录后管理云歌单", "与手机酷狗使用同一个账号", icon = R.drawable.ic_user,
                    actionLabel = "扫码登录", onAction = { open("account") })
            } else {
                if (state.loading) item { WatchLoadingState("正在与酷狗同步") }
                state.pendingCreate?.let { pending -> item {
                    Text("正在确认「${pending.name}」的创建结果", style = WatchTypography.CardTitle)
                    Notice("请求可能已经提交到酷狗。确认前会保留记录，避免重复创建。")
                    PrimaryAction("查询酷狗结果", enabled = !state.loading) { graph.cloudManager.refresh() }
                    if (abandoningCreate) {
                        Notice("放弃记录后重新创建可能产生同名歌单，请先在手机酷狗检查。")
                        SecondaryAction("确认放弃记录", enabled = !state.loading, color = WatchTokens.Error) {
                            graph.cloudManager.abandonPendingCreate(); abandoningCreate = false
                        }
                        SecondaryAction("保留待确认记录") { abandoningCreate = false }
                    } else SecondaryAction("放弃这次待确认", enabled = !state.loading) { abandoningCreate = true }
                } }
                if (selected == null) {
                    state.addingTrack?.let { item { Notice("将「${it.title}」加入所选云歌单") } }
                    item { LibrarySearch(query, { query = it }, "查找云歌单") }
                    val playlists = state.playlists.filter { it.collection.title.contains(query, true) }
                    items(playlists, key = { it.collection.key }) { playlist ->
                        WatchListItem(playlist.collection.title, {
                            if (state.addingTrack != null) graph.cloudManager.addTo(playlist) else graph.cloudManager.open(playlist)
                        }, subtitle = if (playlist.canEdit) "${playlist.collection.count ?: 0} 首 · 我的歌单" else "收藏的歌单",
                            icon = if (playlist.isFavorites) R.drawable.ic_heart else R.drawable.ic_library,
                            enabled = !state.loading && (state.addingTrack == null || playlist.canEdit))
                    }
                    if (state.addingTrack == null) {
                        item { SectionLabel("新建云歌单") }
                        item { LibrarySearch(name, { name = it.take(40) }, "输入歌单名称") }
                        item { PrimaryAction(if (state.pendingCreate != null) "先确认上次创建结果" else "在酷狗创建",
                            enabled = name.isNotBlank() && !state.loading && state.pendingCreate == null) { graph.cloudManager.create(name) } }
                    }
                    item { SecondaryAction("刷新云歌单", enabled = !state.loading) { graph.cloudManager.refresh() } }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            SmallIcon(R.drawable.ic_back, "返回云歌单", graph.cloudManager::clearSelection, enabled = !state.loading)
                            SmallIcon(R.drawable.ic_search, "搜索此歌单", { searching = !searching }, active = searching)
                            SmallIcon(R.drawable.ic_more, "歌单操作", { options = !options }, active = options)
                        }
                    }
                    if (options && selected.canEdit) item {
                        SecondaryAction(if (selecting) "结束多选" else "多选整理歌曲", enabled = !state.loading) {
                            selecting = !selecting; selectedIds = emptySet()
                        }
                    }
                    if (options) item { SecondaryAction("刷新当前歌单", enabled = !state.loading) { graph.cloudManager.refresh() } }
                    if (options && selected.canEdit && !selected.isFavorites) {
                        item { SecondaryAction(if (rename) "收起改名" else "修改歌单名称", enabled = !state.loading) {
                            rename = !rename; name = selected.collection.title
                        } }
                        if (rename) {
                            item { LibrarySearch(name, { name = it.take(40) }, "新的歌单名称") }
                            item { PrimaryAction("保存到酷狗", enabled = name.isNotBlank() && !state.loading) { graph.cloudManager.rename(selected, name) } }
                        }
                    }
                    if (searching || query.isNotBlank()) item { LibrarySearch(query, { query = it }, "搜索此歌单") }
                    val songs = state.songs.filter { it.track.title.contains(query, true) || it.track.artist.contains(query, true) }
                    if (selected.canEdit && selectedIds.isNotEmpty()) item {
                        SecondaryAction("从酷狗移除 ${selectedIds.size} 首", enabled = !state.loading, color = WatchTokens.Error) {
                            graph.cloudManager.removeSongs(state.songs.filter { it.fileId in selectedIds })
                        }
                    }
                    items(songs, key = { "${it.fileId}:${it.track.id}" }) { song ->
                        Column {
                            TrackRow(song.track, graph, open, songs.map { it.track })
                            if (selecting && selected.canEdit && song.fileId > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(song.fileId in selectedIds, { checked ->
                                    selectedIds = if (checked) selectedIds + song.fileId else selectedIds - song.fileId
                                }, enabled = !state.loading)
                                Text("选择整理", style = WatchTypography.Supporting)
                            }
                        }
                    }
                    if (songs.isEmpty() && !state.loading && state.error == null) item { Notice("歌单中暂无匹配歌曲") }
                    if (options && !selected.isFavorites) {
                        item { SecondaryAction(if (selected.canEdit) "删除云歌单" else "取消云端收藏", enabled = !state.loading,
                            color = WatchTokens.Error) { deleting = true } }
                        if (deleting) {
                            item { Notice("确认在酷狗${if (selected.canEdit) "删除" else "取消收藏"}「${selected.collection.title}」？手机端也会同步此操作。") }
                            item { PrimaryAction("确认操作", enabled = !state.loading) { deleting = false; graph.cloudManager.delete(selected) } }
                            item { SecondaryAction("保留歌单") { deleting = false } }
                        }
                    }
                }
            }
        }
        CloudFeedbackStrip(state.message, state.error?.message(), state.undoAvailable, state.undoExpiresAtMs,
            onUndo = graph.cloudManager::undo, onRetry = graph.cloudManager::refresh,
            onDismiss = { graph.cloudManager.clearMessage(); graph.cloudManager.dismissUndo() }, busy = state.loading,
            retryLabel = if (state.pendingCreate != null) "确认" else "刷新")
        }
    }
}
