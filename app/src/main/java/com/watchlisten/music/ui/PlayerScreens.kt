package com.watchlisten.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.model.*
import com.watchlisten.music.playback.PlaybackSnapshot
import kotlin.math.roundToLong
import kotlin.math.abs
import kotlinx.coroutines.delay

/** 单屏主控；上滑展开歌词，音量、模式和队列集中在第三个根页面。 */
@Composable
fun PlayerScreen(graph: AppGraph, music: MusicState, playback: PlaybackSnapshot,
                 open: (String) -> Unit, bottomInset: Dp = 0.dp,
                 sourceLabel: String? = null, onReturnToSource: (() -> Unit)? = null) {
    val request = music.playRequest
    val presentation = music.presentPlayback(playback)
    val track = presentation.track
    val error = presentation.error
    val busy = presentation.buffering
    val largeText = LocalDensity.current.fontScale > 1.2f
    val openLyrics by rememberUpdatedState { open("lyrics") }
    RoundFrame {
        if (track != null) PlayerBackdrop(track.cover)
        Box(Modifier.fillMaxSize().lyricsSwipe { openLyrics() }
            .semantics { customActions = listOf(CustomAccessibilityAction("查看歌词") {
                openLyrics(); true
            }) }.padding(bottom = bottomInset), contentAlignment = Alignment.Center) {
            if (track == null) {
                Column(Modifier.widthIn(max = 184.dp).padding(top = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Brand(36)
                    Spacer(Modifier.height(8.dp))
                    Text("让音乐陪你", style = WatchTypography.StateTitle)
                    Text("搜索一首，马上开听", style = WatchTypography.Supporting,
                        modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
                    PrimaryAction("找首歌", Modifier.width(144.dp)) { open("search") }
                }
            } else if (error != null) {
                Column(Modifier.widthIn(max = 180.dp).padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(track.title, style = WatchTypography.CardTitle, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(if (playback.current != null && playback.current?.track?.id != track.id)
                        "当前音乐继续播放" else "暂时无法播放",
                        style = WatchTypography.Supporting, color = Warn,
                        modifier = Modifier.padding(top = 2.dp))
                    Text(error.message(), style = WatchTypography.Supporting,
                        textAlign = TextAlign.Center, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton({ when { track.isRemote -> open("dlna"); error == MusicError.MobileDataBlocked -> open("settings"); else -> open("search") } },
                            Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(0.dp)) {
                            Text(when { track.isRemote -> "投放设置"; error == MusicError.MobileDataBlocked -> "流量设置"; else -> "换首歌" },
                                style = WatchTypography.Label, color = Quiet)
                        }
                        Button({ graph.music.retryPlayback() }, Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(16.dp)) {
                            Text("重试", style = WatchTypography.Label, color = WatchTokens.Background)
                        }
                    }
                }
            } else {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp, top = when {
                        sourceLabel != null && onReturnToSource != null -> if (largeText) 30.dp else 38.dp
                        largeText -> 40.dp
                        else -> 46.dp
                    }),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 174.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(track.title, fontSize = 16.sp, lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold, color = Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        val accessLabel = if (track.isRemote) "手机投放" else if (track.isLocal) "本地音乐" else listOf(
                                when (track.rights) {
                                    TrackRights.VIP -> "VIP"
                                    TrackRights.LIMITED_FREE -> "限免"
                                    else -> ""
                                },
                                when (music.listeningAccess(track, playback)) {
                                    ListeningAccess.PREVIEW -> "试听"
                                    ListeningAccess.FULL -> "完整"
                                    ListeningAccess.DENIED -> "无权限"
                                    ListeningAccess.UNKNOWN -> "权限待确认"
                                })
                                .filter { it.isNotBlank() }.joinToString(" · ")
                        if (busy) Text("正在准备播放…", fontSize = 11.sp, lineHeight = 14.sp,
                            color = Color(0xFFCED4D5), maxLines = 1)
                        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            if (track.artist.isNotBlank()) Text(track.artist,
                                modifier = Modifier.weight(1f, fill = false),
                                fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFCED4D5),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (track.artist.isNotBlank()) " · " else "") + accessLabel,
                                fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFCED4D5),
                                maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(if (largeText || sourceLabel != null) 4.dp else 8.dp))
                    PlayerControlLayout(
                        leftButton = { TransportButton(R.drawable.ic_previous, "上一首", graph.playback::previous,
                            enabled = !busy && playback.canPrevious) },
                        middleButton = { PlayControl(presentation.playing, busy, graph.playback::toggle) },
                        rightButton = { TransportButton(R.drawable.ic_next, "下一首", graph.playback::next,
                            enabled = !busy && playback.canNext) },
                        modifier = Modifier.fillMaxWidth())
                    PlayerProgress(if (request == null) playback else PlaybackSnapshot(
                        positionMs = request.positionMs, durationMs = track.durationMs), track,
                        enabled = !busy && request == null, onSeek = graph.playback::seek)
                }
                if (sourceLabel != null && onReturnToSource != null) TextButton(
                    onClick = onReturnToSource,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                        .widthIn(max = 160.dp).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("‹ 返回$sourceLabel", style = WatchTypography.Metadata,
                        color = Color(0xFFCED4D5), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 封面铺满圆屏；渐暗层让不同明暗的专辑图都能承托文字和按钮。 */
@Composable
private fun PlayerBackdrop(url: String) {
    Cover(url, Modifier.fillMaxSize(), cornerRadius = 0.dp, fallback = {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(Color(0xFF293B43), Color(0xFF111619), Color.Black))))
    })
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = .65f),
        .32f to Color.Black.copy(alpha = .68f),
        .52f to Color.Black.copy(alpha = .35f),
        1f to Color.Black.copy(alpha = .91f))))
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = .3f)))))
}

@Composable
private fun PlayControl(playing: Boolean, busy: Boolean, toggle: () -> Unit) {
    IconButton(toggle, Modifier.size(48.dp).semantics {
        stateDescription = when { busy -> "正在准备播放"; playing -> "播放中"; else -> "已暂停" }
    }, enabled = !busy) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFF0F2EE)),
            contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(17.dp), color = Color(0xFF172125), strokeWidth = 1.5.dp)
            else Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                if (playing) "暂停" else "继续播放", Modifier.size(20.dp), tint = Color(0xFF142126))
        }
    }
}

@Composable
private fun TransportButton(icon: Int, label: String, onClick: () -> Unit, enabled: Boolean) {
    IconButton(onClick, Modifier.size(48.dp), enabled) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .20f)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), label, Modifier.size(18.dp),
                tint = if (enabled) Color.White else Color.White.copy(alpha = .35f))
        }
    }
}

@Composable
private fun PlayerProgress(playback: PlaybackSnapshot, track: Track, enabled: Boolean,
                           onSeek: (Long, String) -> Unit) {
    val duration = (playback.durationMs.takeIf { it > 0 } ?: track.durationMs).coerceAtLeast(0)
    val entryId = playback.current?.takeIf { it.track.id == track.id }?.instanceId
    val canSeek = enabled && playback.seekable && playback.durationMs > 0 && entryId != null
    // 时间线变化时取消旧手势，不能把旧拖动提交给新曲目。
    key(entryId, duration, canSeek) {
        var seekingPosition by remember { mutableStateOf<Long?>(null) }
        val position = (seekingPosition ?: playback.positionMs).coerceIn(0, duration)
        val progress = if (duration > 0) (position.toDouble() / duration).toFloat() else 0f
        val thumbInset = with(LocalDensity.current) { 6.dp.toPx() }
        // 左侧留给返回起手，低进度位置向右拖动也不会被宿主当作返回。
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(40.dp)
            .semantics {
                    contentDescription = "播放进度"
                    stateDescription = "${clockTime(position)} / ${if (duration > 0) clockTime(duration) else "--:--"}"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                if (!canSeek) disabled() else setProgress { fraction ->
                    if (!fraction.isFinite()) false else {
                        onSeek((fraction.coerceIn(0f, 1f).toDouble() * duration).roundToLong(), entryId)
                        true
                    }
                }
            }
            .focusable(enabled = canSeek)
            .pointerInput(entryId, duration, canSeek) {
                if (!canSeek) return@pointerInput
                fun positionAt(x: Float): Long {
                    val width = (size.width - thumbInset * 2).coerceAtLeast(1f)
                    val fraction = ((x - thumbInset) / width).coerceIn(0f, 1f)
                    return (fraction.toDouble() * duration).roundToLong()
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    var dragging = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.size != 1) break
                            val change = event.changes.first()
                            if (change.id != down.id || change.isConsumed) break
                            if (!change.pressed) {
                                change.consume()
                                onSeek(positionAt(change.position.x), entryId)
                                break
                            }
                            val distance = change.position - down.position
                            if (!dragging) {
                                if (abs(distance.y) > viewConfiguration.touchSlop &&
                                    abs(distance.y) >= abs(distance.x)) break
                                dragging = abs(distance.x) > viewConfiguration.touchSlop
                            }
                            if (dragging) {
                                change.consume()
                                seekingPosition = positionAt(change.position.x)
                            }
                        }
                    } finally {
                        seekingPosition = null
                    }
                }
            }) {
            Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                val start = Offset(thumbInset, center.y)
                val end = Offset(size.width - thumbInset, center.y)
                val thumb = Offset(start.x + (end.x - start.x) * progress, center.y)
                val activeColor = if (canSeek) KugouBlue else Quiet
                drawLine(PanelHi, start, end, 2.dp.toPx(), StrokeCap.Round)
                if (progress > 0f) drawLine(activeColor, start, thumb, 2.dp.toPx(), StrokeCap.Round)
                drawCircle(activeColor, 3.5.dp.toPx(), thumb)
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clockTime(position),
                    style = WatchTypography.Metadata, color = Color(0xFFDADEDB))
                Text(if (duration > 0) clockTime(duration) else "--:--",
                    style = WatchTypography.Metadata, color = Color(0xFFDADEDB))
            }
        }
    }
}

@Composable
fun QueueScreen(graph: AppGraph, playback: PlaybackSnapshot, open: (String) -> Unit) {
    val list = rememberLazyListState()
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(feedback) {
        if (feedback != null) { delay(5000); feedback = null }
    }
    RoundFrame(list) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 38.dp,
                bottom = if (feedback == null) 38.dp else 86.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Title("播放队列", "${playback.queue.size} 首歌曲") }
            if (playback.queue.isEmpty()) item {
                WatchEmptyState("下一首，听什么", "把喜欢的音乐加入队列", icon = R.drawable.ic_queue,
                    actionLabel = "找首歌", onAction = { open("search") })
            }
            itemsIndexed(playback.queue, key = { _, entry -> entry.instanceId }) { index, entry ->
                QueueEntry(entry, index, graph, playback) { feedback = it }
            }
            if (playback.queue.isNotEmpty()) item {
                TextButton(onClick = { graph.playback.clearUpcoming(); feedback = "已保留当前歌曲" },
                    enabled = playback.queue.size > 1 && playback.current?.track?.isRemote != true,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("清空其他歌曲", color = Quiet)
                }
            }
        }
        feedback?.let { message ->
            InlineBanner(message, modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 22.dp), color = WatchTokens.Secondary,
                actionLabel = if (playback.canUndoQueueEdit) "撤销" else null,
                onAction = if (playback.canUndoQueueEdit) ({ graph.playback.undoLastQueueEdit(); feedback = null }) else null)
        }
    }
}

@Composable
private fun QueueEntry(entry: PlaybackEntry, index: Int, graph: AppGraph,
                       playback: PlaybackSnapshot, feedback: (String) -> Unit) {
    val current = playback.current?.instanceId == entry.instanceId
    val editable = playback.current?.track?.isRemote != true
    var expanded by remember(entry.instanceId) { mutableStateOf(false) }
    Column {
        Surface(onClick = { graph.playback.select(index) }, shape = RoundedCornerShape(14.dp),
            color = Panel, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.heightIn(min = 48.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                    if (current) Icon(painterResource(R.drawable.ic_bars), "当前歌曲", Modifier.size(14.dp), tint = KugouBlue)
                    else Text("${index + 1}", style = WatchTypography.Metadata)
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(entry.track.title, style = WatchTypography.CardTitle, color = if (current) KugouBlue else Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.track.artist, style = WatchTypography.Supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ expanded = !expanded }, Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_more), "队列操作", Modifier.size(18.dp), tint = Quiet)
                }
            }
        }
        if (expanded) Row(Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            SmallIcon(R.drawable.ic_down, "上移", {
                graph.playback.move(index, index - 1); expanded = false
            }, modifier = Modifier.rotate(180f), enabled = editable && index > 0)
            SmallIcon(R.drawable.ic_down, "下移", {
                graph.playback.move(index, index + 1); expanded = false
            }, enabled = editable && index < playback.queue.lastIndex)
            SmallIcon(R.drawable.ic_close, "移除歌曲", {
                graph.playback.remove(index); expanded = false; feedback("已移除《${entry.track.title}》")
            }, accent = Bad, enabled = editable)
        }
    }
}

