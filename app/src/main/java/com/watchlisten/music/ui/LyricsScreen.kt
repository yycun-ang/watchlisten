package com.watchlisten.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.playback.PlaybackSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import watchmusic.lyrics.LyricsDocument
import watchmusic.lyrics.LyricsTimeline

/**
 * 歌词跟随状态机。
 *
 * 歌词视图通过统一的播放进度入口更新，仅在当前行变化时滚动。
 * 用户手动浏览或拖动歌词时暂停自动跟随，保留当前浏览位置；
 * 停止操作后等待一段时间，再恢复对当前播放行的跟随。
 * 状态独立于播放器与 Android 对象，便于单独验证。
 */
class LyricFollowState(
    /** 用户停止浏览后，等待多久恢复自动跟随。 */
    private val resumeDelayMs: Long = 5000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var lastInteractionAt: Long? = null

    /** 用户开始拖动歌词。 */
    fun onUserScroll() { lastInteractionAt = clock() }

    /** 拖动仍在进行，从最后一次交互重新计时。 */
    fun onScrollInProgress() { lastInteractionAt = clock() }

    fun timeUntilResume(): Long {
        val last = lastInteractionAt ?: return 0L
        return (resumeDelayMs - (clock() - last)).coerceAtLeast(0L)
    }

    /** 停止操作后经过足够时间则恢复跟随。 */
    fun tryResumeFollow(): Boolean {
        if (lastInteractionAt == null) return true
        if (timeUntilResume() > 0L) return false
        lastInteractionAt = null
        return true
    }

    /** 立即回到跟随，例如点"回到当前"或开始播放新曲目。 */
    fun resumeNow() { lastInteractionAt = null }

    val isBrowsing: Boolean get() = lastInteractionAt != null
}

/**
 * 歌词页。
 *
 * 进度只经由 [graph.playback.positionNow] 单一来源读取，与歌词核心共享同一时钟，
 * 定位、暂停与倍速都不需要维护第二套状态。
 */
@Composable fun LyricsScreen(graph: AppGraph, music: MusicState, playback: PlaybackSnapshot,
                             close: () -> Unit, visible: Boolean = true,
                             bottomInset: Dp = 0.dp) {
    val offset by graph.library.lyricOffset.collectAsStateWithLifecycle()
    val reduced by graph.library.reduceMotion.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var position by remember { mutableLongStateOf(playback.positionMs + offset) }
    val timeline = remember { LyricsTimeline() }
    val remote = playback.current?.track?.isRemote == true
    val document = if (remote) null else music.lyrics?.takeIf { music.lyricsEntry == playback.current?.instanceId }
    val list = rememberLazyListState()
    val follow = remember(document) { LyricFollowState() }
    val lineHeights = remember(document) { mutableStateMapOf<Int, Int>() }
    val normalLineHeightPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    var browsing by remember(document) { mutableStateOf(false) }
    var selected by remember(document) { mutableIntStateOf(-1) }
    val timed = document?.format == LyricsDocument.Format.KRC || document?.format == LyricsDocument.Format.LRC

    LaunchedEffect(playback.positionMs, playback.playing, offset) {
        if (!playback.playing) position = playback.positionMs + offset
    }
    LaunchedEffect(playback.current?.instanceId, playback.playing, offset, visible) {
        if (!visible || remote) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            position = (if (playback.playing) graph.playback.positionNow() else playback.positionMs) + offset
            if (playback.playing) while (isActive) withFrameNanos { position = graph.playback.positionNow() + offset }
        }
    }
    val current = document?.let { timeline.lineAt(it, position) } ?: -1
    LaunchedEffect(current, browsing, document, visible, lineHeights[current]) {
        if (visible && current >= 0 && !browsing) {
            // 折行后的长句也按实际高度居中，避免下半句落入页脚。
            val adjustment = ((lineHeights[current] ?: normalLineHeightPx) - normalLineHeightPx) / 2
            if (reduced) list.scrollToItem(current, adjustment) else list.animateScrollToItem(current, adjustment)
        }
    }
    LaunchedEffect(browsing, list.isScrollInProgress) {
        while (browsing && !list.isScrollInProgress) {
            delay(follow.timeUntilResume().coerceAtLeast(1L))
            if (follow.tryResumeFollow()) { browsing = false; selected = -1 }
        }
    }
    val scrolling = remember(document) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && timed) {
                follow.onScrollInProgress(); browsing = true; selected = -1
            }
            return Offset.Zero
        }
    } }

    // 歌词独占屏幕；只有边缘页眉和页脚，正文中间不叠放定位按钮。
    BoxWithConstraints(Modifier.fillMaxSize().background(WatchTokens.Background)) {
        val topInset = 46.dp
        val footerHeight = 40.dp + bottomInset
        val centerPadding = (maxHeight / 2 - topInset - 12.dp).coerceAtLeast(0.dp)
        if (document == null || document.lines.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_lyric), null,
                    Modifier.size(26.dp), tint = Faint)
                Spacer(Modifier.height(10.dp))
                Text(when { remote -> "请在手机查看歌词"; music.lyricsLoading -> "正在寻找歌词…"; else -> "暂时没有可用歌词" },
                    style = WatchTypography.CardTitle, textAlign = TextAlign.Center)
                Text(if (remote) "手机投放暂不传输歌词\n打开手机酷狗，跟着音乐唱" else "继续听，让音乐陪你", style = WatchTypography.Supporting,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 5.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(top = topInset, bottom = footerHeight)
                    .nestedScroll(scrolling), state = list,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp,
                    top = if (timed) centerPadding else 8.dp,
                    bottom = if (timed) centerPadding + 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!timed) item { Notice("纯文本歌词 · 自由浏览") }
                itemsIndexed(document.lines) { index, _ ->
                    KaraokeLine(document, index, position, timeline, index == current,
                        Modifier.fillMaxWidth()
                            .onSizeChanged { lineHeights[index] = it.height }
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(if (selected == index) KugouBlueSoft.copy(alpha = .65f) else Color.Transparent)
                            .clickable(enabled = timed, onClickLabel = "选择这句歌词") {
                                selected = index; browsing = true; follow.onUserScroll()
                            }.padding(vertical = 2.dp))
                }
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = topInset).fillMaxWidth().height(12.dp)
                .background(Brush.verticalGradient(listOf(Color.Black, Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = footerHeight).fillMaxWidth().height(12.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).width(176.dp).height(36.dp)
            .pointerInput(close) { var distance = 0f
                detectVerticalDragGestures(onDragStart = { distance = 0f },
                    onVerticalDrag = { change, amount -> distance += amount; change.consume() },
                    onDragEnd = { if (distance > 36.dp.toPx()) close() })
            }, verticalAlignment = Alignment.CenterVertically) {
            Text(playback.current?.track?.title ?: "歌词", color = Quiet, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp))
            IconButton(close, Modifier.size(48.dp)) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_down), "收起歌词",
                    Modifier.size(16.dp), tint = Quiet)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset)
                .widthIn(max = 156.dp).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            if (selected >= 0 && document != null) {
                TextButton({
                    playback.current?.instanceId?.let { entryId ->
                        graph.playback.seek((document.lines[selected].startMs + document.offsetMs - offset)
                            .coerceAtLeast(0), entryId)
                    }
                    selected = -1; browsing = false; follow.resumeNow()
                }, Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_check), null, Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("跳到这句", fontSize = 11.sp)
                }
                TextButton({ selected = -1; browsing = false; follow.resumeNow() },
                    Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("跟随", fontSize = 11.sp, color = Quiet)
                }
            } else if (browsing && timed) {
                TextButton({ browsing = false; follow.resumeNow() }, Modifier.height(48.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_refresh), null, Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("回到当前", fontSize = 11.sp)
                }
            } else if (document != null) {
                Text("滑动浏览歌词", style = WatchTypography.Metadata)
            }
        }
    }
}

@Composable private fun KaraokeLine(document: LyricsDocument, index: Int, position: Long,
                                   timeline: LyricsTimeline, current: Boolean, modifier: Modifier) {
    val line = document.lines[index]
    var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier) {
        Text(line.text.ifEmpty { "♪" }, color = if (current) Ink else Quiet.copy(alpha = .7f),
            fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(), onTextLayout = { layout = it })
        if (current) Canvas(Modifier.matchParentSize()) {
            val text = layout ?: return@Canvas
            if (line.words.isEmpty()) { drawText(text, color = KugouBlue); return@Canvas }
            val visibleEnd = text.getLineEnd(text.lineCount - 1, visibleEnd = true)
            var start = 0
            line.words.forEachIndexed { wordIndex, word ->
                val progress = timeline.wordProgress(document, index, wordIndex, position).toFloat()
                for (character in word.text.indices) {
                    val fraction = (progress * word.text.length - character).coerceIn(0f, 1f)
                    val characterIndex = start + character
                    if (fraction > 0 && characterIndex < visibleEnd && characterIndex < text.layoutInput.text.length) {
                        val rect = text.getBoundingBox(characterIndex)
                        clipRect(rect.left, rect.top, rect.left + rect.width * fraction, rect.bottom) { drawText(text, color = KugouBlue) }
                    }
                }
                start += word.text.length
            }
        }
    }
}
