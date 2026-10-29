package com.watchlisten.music.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.watchlisten.music.R
import com.watchlisten.music.ui.nav.MainTab
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 圆屏页面的统一容器。顶部时钟与正文分别占据安全区，不会压住可点击内容。
 * 正文宽度固定为圆屏中央的安全弦宽，滚动指示弧只负责绘制，不参与命中测试。
 */
@Composable
fun RoundScreenScaffold(
    state: LazyListState? = null,
    modifier: Modifier = Modifier,
    bottomSafeArea: androidx.compose.ui.unit.Dp = WatchTokens.BottomSafeArea,
    content: @Composable BoxScope.() -> Unit
) {
    RoundScreenSurface(modifier, state) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(WatchTokens.ContentWidth)
                .fillMaxHeight()
                .padding(
                    top = WatchTokens.TopSafeArea,
                    bottom = bottomSafeArea
                ),
            content = content
        )
    }
}

/** 旧页面自带安全边距；迁移完成前保留不改变其内容坐标的容器。 */
@Composable
fun RoundFrame(state: LazyListState? = null, content: @Composable BoxScope.() -> Unit) {
    RoundScreenSurface(Modifier, state, content)
}

@Composable
private fun RoundScreenSurface(
    modifier: Modifier,
    state: LazyListState?,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.fillMaxSize().background(WatchTokens.Background)) {
        content()
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(WatchTokens.TopSafeArea)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            WatchTokens.Background,
                            WatchTokens.Background,
                            Color.Transparent
                        )
                    )
                )
        )
        WatchClock()
        if (state != null) ScrollArc(state)
    }
}

@Composable private fun WatchClock() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var time by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())
                delay(60000 - System.currentTimeMillis() % 60000)
            }
        }
    }
    Text(time, color = WatchTokens.TextSecondary, fontSize = 11.sp, lineHeight = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().height(29.dp).padding(top = 6.dp))
}

@Composable private fun ScrollArc(state: LazyListState) {
    val alpha by animateFloatAsState(if (state.isScrollInProgress) .8f else 0f, label = "scroll arc")
    Canvas(Modifier.fillMaxSize()) {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || (!state.canScrollBackward && !state.canScrollForward)) return@Canvas
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1).toFloat()
        // 大页面也可能只有一个 Lazy item；按像素偏移计算，滚动弧仍能跟随内容。
        val averageHeight = visible.sumOf { it.size }.toFloat() / visible.size
        val estimatedContent = max(viewport, averageHeight * info.totalItemsCount +
            info.beforeContentPadding + info.afterContentPadding)
        val ratio = (viewport / estimatedContent).coerceIn(.12f, 1f)
        val first = visible.first()
        val scrollOffset = first.index * averageHeight +
            (info.viewportStartOffset - first.offset).coerceAtLeast(0)
        val progress = when {
            !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> scrollOffset / (estimatedContent - viewport).coerceAtLeast(1f)
        }
        val inset = 3.dp.toPx()
        drawArc(WatchTokens.Primary.copy(alpha = alpha), -28 + 56 * (1 - ratio) * progress.coerceIn(0f, 1f),
            (56 * ratio).coerceAtLeast(7f), false, androidx.compose.ui.geometry.Offset(inset, inset),
            androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable fun Title(text: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = WatchTypography.PageTitle, textAlign = TextAlign.Center, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, style = WatchTypography.Supporting, textAlign = TextAlign.Center) }
    }
}

/** 单个页面或状态的主要动作。 */
@Composable
fun PrimaryAction(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    FilledAction(text, enabled, WatchTokens.Primary, modifier, onClick)
}

@Composable
private fun FilledAction(
    text: String,
    enabled: Boolean,
    containerColor: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val contentColor = if (containerColor.luminance() > 0.179f) {
        WatchTokens.Background
    } else {
        WatchTokens.TextPrimary
    }
    Box(modifier.fillMaxWidth().heightIn(min = WatchTokens.MinimumTouch)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().heightIn(min = WatchTokens.CompactActionHeight)
            .clip(RoundedCornerShape(WatchTokens.ActionRadius))
            .background(if (enabled) containerColor else WatchTokens.Surface)
            .padding(horizontal = WatchTokens.SpaceMedium, vertical = 7.dp),
            contentAlignment = Alignment.Center) {
            Text(
                text,
                style = WatchTypography.Label,
                color = if (enabled) contentColor else WatchTokens.TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable fun Action(text: String, onClick: () -> Unit) = PrimaryAction(text, onClick = onClick)

@Composable fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    PrimaryAction(text, enabled = enabled, onClick = onClick)

@Composable fun Action(text: String, accent: Color, onClick: () -> Unit) =
    Action(text, true, accent, onClick)

@Composable fun Action(text: String, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    FilledAction(text, enabled, accent, Modifier, onClick)
}

/** 次要动作使用紧凑视觉面，外围仍留出可触控空间。 */
@Composable
fun SecondaryAction(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = WatchTokens.TextSecondary,
    onClick: () -> Unit
) {
    Box(
        modifier.fillMaxWidth().height(WatchTokens.MinimumTouch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxWidth().height(WatchTokens.CompactActionHeight)
                .clip(RoundedCornerShape(WatchTokens.ActionRadius))
                .background(WatchTokens.Surface),
            contentAlignment = Alignment.Center
        ) {
            Text(text, style = WatchTypography.Label,
                color = if (enabled) color else WatchTokens.TextMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 普通图标不画底盘，选中态仅用小圆底。 */
@Composable fun SmallIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit,
                          modifier: Modifier = Modifier, active: Boolean = false,
                          size: Int = 28, accent: Color = KugouBlue,
                          enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size(WatchTokens.MinimumTouch)) {
        Box(
            Modifier.size(size.coerceIn(24, 30).dp).clip(CircleShape)
                .background(if (active) WatchTokens.PrimaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(icon), label, Modifier.size(WatchTokens.IconSize),
                tint = when { !enabled -> WatchTokens.TextMuted.copy(alpha = .45f)
                    active -> accent; else -> WatchTokens.TextSecondary })
        }
    }
}

/** 48dp 的低亮导航或内容行。 */
@Composable
fun WatchListItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    accent: Color = WatchTokens.TextSecondary,
    enabled: Boolean = true,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(WatchTokens.ListItemRadius),
        color = WatchTokens.Surface,
        modifier = modifier.fillMaxWidth().heightIn(min = WatchTokens.ListItemHeight)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = WatchTokens.ListItemHeight)
                .padding(horizontal = WatchTokens.SpaceMedium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(painterResource(icon), null, Modifier.size(WatchTokens.IconSize),
                    tint = if (enabled) accent else WatchTokens.TextMuted)
                Spacer(Modifier.width(WatchTokens.SpaceSmall))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = WatchTypography.CardTitle,
                    color = if (enabled) WatchTokens.TextPrimary else WatchTokens.TextMuted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = WatchTypography.Supporting, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailingContent != null) {
                trailingContent()
            } else {
                Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(14.dp),
                    tint = WatchTokens.TextMuted)
            }
        }
    }
}

/** 设置项的低亮容器；控制件通过尾部插槽放置，整行可按需要响应点击。 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val rowContent: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().heightIn(min = WatchTokens.ListItemHeight)
                .padding(horizontal = WatchTokens.SpaceMedium, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = WatchTypography.CardTitle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (!description.isNullOrEmpty()) {
                    Text(description, style = WatchTypography.Supporting, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            trailingContent()
        }
    }
    if (onClick == null) {
        Surface(
            modifier = modifier.fillMaxWidth().heightIn(min = WatchTokens.ListItemHeight),
            shape = RoundedCornerShape(WatchTokens.ListItemRadius),
            color = WatchTokens.Surface,
            content = rowContent
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().heightIn(min = WatchTokens.ListItemHeight),
            enabled = enabled,
            shape = RoundedCornerShape(WatchTokens.ListItemRadius),
            color = WatchTokens.Surface,
            content = rowContent
        )
    }
}

@Composable fun TextCard(title: String, subtitle: String = "", onClick: () -> Unit) {
    RowCard(null, title, subtitle, null, onClick)
}

/** 旧调用入口，统一转入 [WatchListItem]。 */
@Composable
fun RowCard(@DrawableRes icon: Int?, title: String, subtitle: String = "",
            accent: Color?, onClick: () -> Unit) {
    WatchListItem(
        title = title,
        subtitle = subtitle.takeIf { it.isNotEmpty() },
        icon = icon,
        accent = accent ?: WatchTokens.TextSecondary,
        onClick = onClick
    )
}

/** 分组标题：给长列表建立层级，避免所有内容等权重。 */
@Composable fun SectionLabel(text: String) {
    Text(text, color = Quiet, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 4.dp, bottom = 1.dp))
}

/** 与当前操作相邻的轻量反馈，可带一个 48dp 的文字动作。 */
@Composable
fun InlineBanner(
    message: String,
    modifier: Modifier = Modifier,
    color: Color = WatchTokens.Warning,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(WatchTokens.ListItemRadius),
        color = WatchTokens.SurfaceRaised
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = WatchTokens.SpaceMedium, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(WatchTokens.SpaceSmall))
            Text(
                message,
                style = WatchTypography.Supporting,
                color = WatchTokens.TextPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = WatchTokens.SpaceMedium)
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.sizeIn(
                        minWidth = WatchTokens.MinimumTouch,
                        minHeight = WatchTokens.MinimumTouch
                    ),
                    colors = ButtonDefaults.textButtonColors(contentColor = color)
                ) {
                    Text(actionLabel, style = WatchTypography.Label, color = LocalContentColor.current)
                }
            }
        }
    }
}

@Composable
fun WatchEmptyState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = R.drawable.ic_library,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    WatchStateContent(
        title = title,
        message = message,
        modifier = modifier,
        icon = icon,
        accent = WatchTokens.TextSecondary,
        actionLabel = actionLabel,
        onAction = onAction
    )
}

@Composable
fun WatchErrorState(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = R.drawable.ic_info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    WatchStateContent(
        title = title,
        message = message,
        modifier = modifier,
        icon = icon,
        accent = WatchTokens.Error,
        actionLabel = actionLabel,
        onAction = onAction
    )
}

@Composable
fun WatchLoadingState(
    title: String = "正在加载",
    message: String? = null,
    modifier: Modifier = Modifier
) {
    WatchStateContent(
        title = title,
        message = message,
        modifier = modifier,
        accent = WatchTokens.Primary,
        showProgress = true
    )
}

@Composable
private fun WatchStateContent(
    title: String,
    message: String?,
    modifier: Modifier,
    @DrawableRes icon: Int? = null,
    accent: Color,
    showProgress: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = WatchTokens.SpaceLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)
    ) {
        when {
            showProgress -> CircularProgressIndicator(
                Modifier.size(24.dp),
                color = accent,
                strokeWidth = 2.dp
            )
            icon != null -> Box(
                Modifier.size(WatchTokens.IconButtonVisual).clip(CircleShape)
                    .background(accent.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(icon), null, Modifier.size(WatchTokens.IconSize), tint = accent)
            }
        }
        Text(title, style = WatchTypography.StateTitle, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        if (!message.isNullOrEmpty()) {
            Text(message, style = WatchTypography.Body, color = WatchTokens.TextSecondary,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(WatchTokens.SpaceExtraSmall))
            PrimaryAction(actionLabel, onClick = onAction)
        }
    }
}

/**
 * 首页的悬浮播放控制：中心 48dp 切换播放，外围区域打开播放器。
 */
@Composable fun PlayBall(playing: Boolean, buffering: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Box(Modifier
        .size(64.dp)
        .clip(CircleShape).clickable(onClick = onOpen)
        .background(WatchTokens.SurfaceRaised), contentAlignment = Alignment.Center) {
        if (buffering) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = KugouBlue)
        else Box(
            Modifier
                .size(WatchTokens.MinimumTouch)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), "当前播放",
                Modifier.size(15.dp), tint = KugouBlue)
        }
    }
}

/** 兼容的紧凑入口行，图标不带底色，点击区域至少 48dp。 */
@Composable
fun EntryRow(@DrawableRes icon: Int, label: String, accent: Color, count: String? = null,
             enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(WatchTokens.ListItemRadius),
        color = if (enabled) Panel else WatchTokens.Surface.copy(alpha = 0.72f),
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled) {
        Row(Modifier.heightIn(min = 39.dp).minimumInteractiveComponentSize()
            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(WatchTokens.IconSize), tint = accent)
            Spacer(Modifier.width(WatchTokens.SpaceSmall))
            Text(label, color = if (enabled) Ink else Faint, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!count.isNullOrEmpty()) Text(count, color = Quiet, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(13.dp), tint = Faint)
        }
    }
}

/**
 * 圆屏最底缘的细弧仅提示页码，不接收点击或拖动。
 */
val IndicatorBand = 12.dp

/**
 * 三段沿屏幕曲率排列的非交互指示弧。
 */
@Composable fun MainIndicator(selected: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(IndicatorBand).semantics {
        contentDescription = "${MainTab.of(selected).label}，第 ${selected + 1} 页，共 ${MainTab.count} 页"
    }) {
        val inset = 5.dp.toPx()
        val diameter = size.width - 2 * inset
        repeat(MainTab.count) { index ->
            drawArc(if (index == selected) WatchTokens.Primary else Color(0xFF505652),
                startAngle = 97f - index * 10f, sweepAngle = 6f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, size.height - inset - diameter),
                size = androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable fun Notice(text: String, color: Color = Quiet) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

@Composable fun Brand(size: Int = 48) {
    Image(painterResource(R.drawable.ic_brand), "腕上听", Modifier.size(size.dp))
}

/**
 * 常驻迷你播放器：显示当前曲目并可直接暂停/继续。
 * 只有存在当前曲目且不在播放器/歌词页时才显示，避免重复控制。
 */
@Composable fun MiniPlayer(trackTitle: String, trackArtist: String, playing: Boolean,
                          buffering: Boolean, onToggle: () -> Unit, onOpen: () -> Unit,
                          failed: Boolean = false, compact: Boolean = false) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(WatchTokens.CardRadius), color = WatchTokens.SurfaceRaised,
        modifier = Modifier.fillMaxWidth().heightIn(min = WatchTokens.ListItemHeight)) {
        Row(Modifier.padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Column {
                    Text(trackTitle, color = Ink, fontSize = if (compact) 12.sp else 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compact) Text(trackArtist, color = WatchTokens.TextSecondary, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (buffering) Box(Modifier.size(WatchTokens.MinimumTouch), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = KugouBlue)
            } else IconButton(onClick = onToggle, modifier = Modifier.size(WatchTokens.MinimumTouch)) {
                Icon(painterResource(when { failed -> R.drawable.ic_refresh;
                    playing -> R.drawable.ic_pause; else -> R.drawable.ic_play }),
                    when { failed -> "重试播放"; playing -> "暂停"; else -> "继续播放" }, Modifier.size(WatchTokens.IconSize),
                    tint = if (playing) KugouBlue else Ink)
            }
        }
    }
}

fun clockTime(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
