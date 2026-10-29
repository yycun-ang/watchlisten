package com.watchlisten.music.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlisten.music.AppGraph
import com.watchlisten.music.BuildConfig
import com.watchlisten.music.R
import com.watchlisten.music.data.NetworkKind
import com.watchlisten.music.data.Quality
import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.domain.SLEEP_AFTER_TRACK
import com.watchlisten.music.model.MusicCollection
import com.watchlisten.music.model.CollectionKind
import com.watchlisten.music.model.ListeningAccess
import com.watchlisten.music.model.sameKugouTrack
import com.watchlisten.music.model.message
import com.watchlisten.music.playback.PlaybackSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun MoreContent(graph: AppGraph, playback: PlaybackSnapshot, music: MusicState,
                         open: (String) -> Unit, rootPage: Boolean = false,
                         visible: Boolean = true) {
    val favorites by graph.favorites.state.collectAsStateWithLifecycle()
    val track = music.playRequest?.track ?: music.preparing ?: playback.current?.track
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)) {
        if (!rootPage) Title("播放设置")
        AudioControls(visible)
        CompactUtilityAction(title = when {
            playback.shuffle -> "随机播放"
            playback.repeat == 1 -> "单曲循环"
            playback.repeat == 2 -> "列表循环"
            else -> "顺序播放"
        }, subtitle = "点按切换", icon = if (playback.shuffle) R.drawable.ic_shuffle else R.drawable.ic_repeat,
            onClick = graph.playback::cycleMode)
        SleepTimer(graph, music, visible)
        SectionLabel("歌曲与播放")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)) {
            CompactUtilityAction("播放队列", R.drawable.ic_queue, { open("queue") },
                Modifier.weight(1f), subtitle = "${playback.queue.size} 首")
            CompactUtilityAction(if (track?.isRemote == true) "手机歌词" else "查看歌词", R.drawable.ic_lyric,
                { open("lyrics") }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)) {
            CompactUtilityAction("歌曲信息", R.drawable.ic_info, { open("info") }, Modifier.weight(1f))
            if (track != null && !track.isRemote && !track.isLocal) {
                val favorite = favorites.tracks.any { sameKugouTrack(it, track) }
                val pending = track.id in favorites.pendingIds
                CompactUtilityAction(if (favorite) "已喜欢" else "喜欢歌曲", R.drawable.ic_heart,
                    { if (graph.sessions.session.value == null) open("account") else graph.favorites.toggle(track) },
                    Modifier.weight(1f), subtitle = if (pending) "正在同步" else "酷狗云端",
                    enabled = !pending, accent = if (favorite) WarmRose else WatchTokens.TextSecondary)
            } else Spacer(Modifier.weight(1f))
        }
        favorites.error?.let { InlineBanner(it.message(), color = WatchTokens.Error) }
        if (favorites.error == null) favorites.message?.let { Text(it,
            style = WatchTypography.Supporting, color = WatchTokens.Secondary) }
        if (favorites.undoAvailable) SecondaryAction(favorites.undoLabel ?: "撤销云端移除",
            enabled = favorites.pendingIds.isEmpty()) { graph.favorites.undo() }
        CompactUtilityAction("手机投放", R.drawable.ic_wifi, { open("dlna") },
            subtitle = "接收手机酷狗的音乐", accent = WatchTokens.Primary)
        LowEmphasisAction("结束播放", color = WatchTokens.Error) { graph.music.stop() }
    }
}

/** 控件的视觉高度随内容增长，独立的命中范围仍保留 48dp。 */
@Composable
private fun CompactUtilityAction(title: String, @DrawableRes icon: Int, onClick: () -> Unit,
                                 modifier: Modifier = Modifier, subtitle: String? = null,
                                 accent: Color = WatchTokens.TextSecondary, enabled: Boolean = true) {
    Box(modifier.heightIn(min = WatchTokens.MinimumTouch)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp)
            .clip(RoundedCornerShape(16.dp)).background(WatchTokens.Surface)
            .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = accent)
            Spacer(Modifier.width(WatchTokens.SpaceSmall))
            Column(Modifier.weight(1f)) {
                Text(title, style = WatchTypography.Label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = WatchTypography.Metadata,
                    color = WatchTokens.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private data class AudioStatus(val level: Int?, val output: String)

@Composable
private fun AudioControls(visible: Boolean) {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val status = rememberAudioStatus(audio, visible)
    var error by remember { mutableStateOf<String?>(null) }
    SectionLabel("音量")
    Row(Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        VolumeAction("减小", AudioManager.ADJUST_LOWER, audio, status) { error = it }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(status.value.level?.let { "$it%" } ?: "—", style = WatchTypography.CardTitle,
                color = WatchTokens.Secondary, textAlign = TextAlign.Center)
            Text("媒体音量", style = WatchTypography.Metadata)
        }
        VolumeAction("增大", AudioManager.ADJUST_RAISE, audio, status) { error = it }
    }
    CompactUtilityAction("音频输出", icon = R.drawable.ic_bluetooth, onClick = {
        error = if (openSystemSettings(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) null
        else "暂时无法打开蓝牙设置"
    }, subtitle = status.value.output, accent = WatchTokens.Secondary)
    error?.let { InlineBanner(it, color = WatchTokens.Error) }
}

@Composable
private fun VolumeAction(label: String, direction: Int, audio: AudioManager?,
                         status: MutableState<AudioStatus>, onError: (String?) -> Unit) {
    AdjustmentButton("${label}媒体音量", direction == AudioManager.ADJUST_RAISE,
        enabled = audio != null && status.value.level != null) {
        val result = runCatching { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        status.value = audioStatus(audio)
        onError(if (result.isFailure) "暂时无法调整音量" else null)
    }
}

@Composable
private fun rememberAudioStatus(audio: AudioManager?, visible: Boolean): MutableState<AudioStatus> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val status = remember(audio) { mutableStateOf(audioStatus(audio)) }
    DisposableEffect(audio, context, lifecycle, visible) {
        if (!visible) return@DisposableEffect onDispose { }
        val mainHandler = Handler(Looper.getMainLooper())
        val refresh = { status.value = audioStatus(audio) }
        refresh()
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) { refresh() }
        }
        // 音频设备回调从 API 23 可用；通信设备监听需要 API 31，不能用于此处。
        val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refresh() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refresh() }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        val resolver = context.contentResolver
        val observing = runCatching {
            resolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        }.isSuccess
        val watching = audio != null && runCatching { audio.registerAudioDeviceCallback(devices, mainHandler) }.isSuccess
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            if (observing) runCatching { resolver.unregisterContentObserver(observer) }
            if (watching) runCatching { audio?.unregisterAudioDeviceCallback(devices) }
            mainHandler.removeCallbacksAndMessages(null)
        }
    }
    return status
}

private fun audioStatus(audio: AudioManager?): AudioStatus {
    if (audio == null) return AudioStatus(null, "暂时无法读取输出")
    val level = runCatching {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maximum
    }.getOrNull()
    return AudioStatus(level, runCatching { audioOutputName(audio) }.getOrDefault("请在系统设置中查看"))
}

@Suppress("DEPRECATION")
private fun audioOutputName(audio: AudioManager): String = when {
    audio.isBluetoothA2dpOn -> "蓝牙耳机"
    audio.isWiredHeadsetOn -> "有线耳机"
    else -> "手表扬声器"
}

@Composable
private fun SleepTimer(graph: AppGraph, music: MusicState, visible: Boolean) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var customMinutes by rememberSaveable { mutableStateOf("30") }
    LaunchedEffect(music.timerDeadlineElapsedMs, visible) {
        now = SystemClock.elapsedRealtime()
        if (!visible) return@LaunchedEffect
        while (music.timerDeadlineElapsedMs > now) { delay(1000); now = SystemClock.elapsedRealtime() }
    }
    SectionLabel("定时关闭")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WatchTokens.SpaceExtraSmall)) {
        listOf(15, 30, 60).forEach { minutes ->
            TimerChip("${minutes}分", music.timerMinutes == minutes, Modifier.weight(1f)) {
                graph.music.sleepAfter(minutes, music.timerFadeOut)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)) {
        TimerChip("本曲结束", music.timerMinutes == SLEEP_AFTER_TRACK, Modifier.weight(1f)) {
            graph.music.sleepAfter(SLEEP_AFTER_TRACK, music.timerFadeOut)
        }
        TimerChip("自定义", music.timerMinutes > 0 && music.timerMinutes !in listOf(15, 30, 60),
            Modifier.weight(1f)) { customExpanded = !customExpanded }
    }
    if (customExpanded) {
        val minutes = customMinutes.toIntOrNull()?.takeIf { it in 1..180 }
        SettingRow("自定义分钟", "1–180 分钟") {
            BasicTextField(value = customMinutes,
                onValueChange = { raw -> customMinutes = raw.filter(Char::isDigit).take(3) },
                modifier = Modifier.width(54.dp).heightIn(min = 48.dp)
                    .semantics { contentDescription = "自定义定时分钟" },
                textStyle = WatchTypography.PageTitle.copy(color = WatchTokens.Primary,
                    textAlign = TextAlign.Center), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                decorationBox = { input -> Box(Modifier.fillMaxSize(), Alignment.Center) { input() } })
        }
        PrimaryAction(minutes?.let { "设为 $it 分钟" } ?: "请输入 1–180", enabled = minutes != null) {
            minutes?.let { graph.music.sleepAfter(it, music.timerFadeOut); customExpanded = false }
        }
    }
    ToggleSetting("结束前渐弱",
        if (music.timerMinutes == SLEEP_AFTER_TRACK) "分钟定时可用" else "最后 30 秒逐渐降低音量",
        music.timerFadeOut, enabled = music.timerMinutes != SLEEP_AFTER_TRACK,
        onChecked = graph.music::setTimerFadeOut)
    val label = when {
        music.timerMinutes == SLEEP_AFTER_TRACK -> "本曲结束后暂停"
        music.timerDeadlineElapsedMs <= 0L -> "定时关闭未开启"
        else -> "还剩 ${((music.timerDeadlineElapsedMs - now).coerceAtLeast(0) + 59999) / 60000} 分钟"
    }
    Text(label, style = WatchTypography.Supporting,
        color = if (music.timerMinutes == 0) WatchTokens.TextSecondary else WatchTokens.Secondary,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    if (music.timerMinutes != 0) LowEmphasisAction("取消定时") { graph.music.sleepAfter(0, music.timerFadeOut) }
}

@Composable
private fun TimerChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(WatchTokens.MinimumTouch)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) WatchTokens.PrimaryContainer else WatchTokens.Surface)
            .padding(horizontal = WatchTokens.SpaceExtraSmall, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(text, style = WatchTypography.Supporting, maxLines = 1,
                color = if (selected) WatchTokens.Primary else WatchTokens.TextSecondary)
        }
    }
}

@Composable
internal fun InfoContent(graph: AppGraph, playback: PlaybackSnapshot, music: MusicState, open: (String) -> Unit = {}) {
    val track = music.playRequest?.track ?: music.preparing ?: playback.current?.track
    val quality by graph.library.quality.collectAsStateWithLifecycle()
    val sources by graph.gateway.sourceDetails.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Title("歌曲信息")
        if (track == null) {
            WatchEmptyState("暂无歌曲", "开始播放后，可在这里查看歌曲信息。", icon = R.drawable.ic_info)
            return
        }
        Cover(track.cover, Modifier.size(64.dp), cornerRadius = WatchTokens.CardRadius)
        Text(track.title, style = WatchTypography.PageTitle, textAlign = TextAlign.Center,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(track.artist, style = WatchTypography.Body, color = WatchTokens.TextSecondary,
            textAlign = TextAlign.Center)
        val currentTrack = playback.current?.track?.id == track.id && music.playRequest == null
        val access = music.listeningAccess(track, playback)
        val duration = track.durationMs.takeIf { it > 0 }
            ?: playback.durationMs.takeIf { currentTrack && access == ListeningAccess.FULL } ?: 0
        SettingRow("歌曲时长", if (duration > 0) clockTime(duration) else "暂未提供")
        if (track.isRemote) {
            SettingRow("播放来源", "手机投放 · DLNA")
            UtilityBody("歌曲、封面与播放地址由当前连接的手机提供。会员与歌曲权益请在手机酷狗中查看；本机不推断完整或试听权限。")
            WatchListItem("投放连接", { open("dlna") }, icon = R.drawable.ic_wifi)
            return@Column
        }
        if (track.isLocal) {
            SettingRow("播放来源", "导入的本地音乐")
            SettingRow("文件格式", track.localPath.substringAfterLast('.', "音频").uppercase(Locale.ROOT))
            UtilityBody("此文件由你导入，可在手表上离线播放。")
            return@Column
        }
        if (access == ListeningAccess.PREVIEW && currentTrack && playback.durationMs > 0)
            SettingRow("试听时长", clockTime(playback.durationMs))
        SettingRow("音质偏好", quality.label)
        sources[track.id]?.takeIf { currentTrack }?.let { source ->
            SettingRow("本次音质", source.qualityLabel)
            SettingRow("音频格式", listOf(source.format.uppercase(Locale.ROOT),
                source.bitrateKbps?.let { "$it kbps" }.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · "))
            source.qualityNote?.takeIf { it.isNotBlank() }?.let { UtilityBody(it) }
        }
        if (track.albumId > 0) WatchListItem("查看所在专辑", {
            open(graph.browse.open(MusicCollection("${track.albumId}", "专辑歌曲", CollectionKind.ALBUM,
                cover = track.cover, subtitle = track.title)))
        }, icon = R.drawable.ic_library)
        UtilityBody("实际播放音质以服务端授权为准。")
        SettingRow("歌曲标记", track.rights.label)
        UtilityBody(track.rights.detail)
        SettingRow("本次播放", access.label)
        UtilityBody(when (access) {
            ListeningAccess.FULL -> "服务端已为本次请求提供完整播放授权。"
            ListeningAccess.PREVIEW -> "本次仅提供试听片段，完整播放需要相应授权。"
            ListeningAccess.DENIED -> "当前账号未获得这首歌的播放权限，请在手机酷狗中查看歌曲权益。"
            ListeningAccess.UNKNOWN -> "无会员能否听取决于单曲授权，可能可完整播放、仅能试听或无法播放，点击播放后确认。"
        })
    }
}

@Composable
internal fun SettingsContent(graph: AppGraph, open: (String) -> Unit) {
    val reduced by graph.library.reduceMotion.collectAsStateWithLifecycle()
    val mobileAllowed by graph.library.mobileDataAllowed.collectAsStateWithLifecycle()
    val network by graph.music.network.collectAsStateWithLifecycle()
    val quality by graph.library.quality.collectAsStateWithLifecycle()
    val offset by graph.library.lyricOffset.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)) {
        Title("偏好设置")
        SectionLabel("音质与网络")
        WatchListItem("音质偏好", { expanded = if (expanded == "quality") null else "quality" },
            subtitle = quality.label, icon = R.drawable.ic_quality)
        if (expanded == "quality") QualitySetting(graph)
        ToggleSetting("移动网络播放", if (mobileAllowed) "允许使用移动流量" else "仅 Wi-Fi 或蓝牙网络",
            mobileAllowed, onChecked = graph.library::setMobileDataAllowed)
        Text("当前网络：${networkLabel(network)}" +
            if (network == NetworkKind.MOBILE && !mobileAllowed) "，移动网络播放已关闭。" else "",
            style = WatchTypography.Metadata, modifier = Modifier.padding(horizontal = 4.dp))
        WatchListItem("手机投放", { open("dlna") }, subtitle = "手机酷狗控制手表播放", icon = R.drawable.ic_wifi)
        SectionLabel("显示与歌词")
        WatchListItem("歌词偏移", { expanded = if (expanded == "lyrics") null else "lyrics" },
            subtitle = String.format(Locale.ROOT, "%+.1f 秒", offset / 1000f), icon = R.drawable.ic_lyric)
        if (expanded == "lyrics") LyricsOffsetSetting(graph)
        ToggleSetting("减少动画", "简化页面切换动效", reduced, onChecked = graph.library::setReduceMotion)
        WatchListItem("缓存管理", { expanded = if (expanded == "cache") null else "cache" },
            subtitle = "封面与歌词缓存", icon = R.drawable.ic_library)
        if (expanded == "cache") CacheSettings(graph)
        NotificationSetting()
        SectionLabel("账号与应用")
        WatchListItem("账号与登录", { open("account") }, icon = R.drawable.ic_user)
        WatchListItem("隐私与使用说明", { open("privacy") }, icon = R.drawable.ic_info)
        WatchListItem("关于腕上听", { open("about") }, icon = R.drawable.ic_info)
    }
}

@Composable
internal fun ToggleSetting(title: String, description: String, checked: Boolean,
                           enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    SettingRow(title, description, enabled = enabled,
        modifier = Modifier.semantics(mergeDescendants = true) {
        role = Role.Switch
        toggleableState = ToggleableState(checked)
        stateDescription = if (checked) "已开启" else "已关闭"
    }, onClick = { onChecked(!checked) }) {
        Spacer(Modifier.width(WatchTokens.SpaceSmall))
        Box(Modifier.width(32.dp).height(18.dp).clip(CircleShape)
            .background(if (checked) WatchTokens.PrimaryContainer else WatchTokens.SurfaceRaised)) {
            Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 3.dp).size(12.dp).clip(CircleShape)
                .background(if (checked) WatchTokens.Primary else WatchTokens.TextSecondary))
        }
    }
}

@Composable
private fun LyricsOffsetSetting(graph: AppGraph) {
    val offset by graph.library.lyricOffset.collectAsStateWithLifecycle()
    TextStepper("歌词偏移", String.format(Locale.ROOT, "%+.1f 秒", offset / 1000f),
        "正数提前，负数延后", "延后", "提前", offset > -10000, offset < 10000,
        { graph.library.shiftLyrics(-500) }, { graph.library.shiftLyrics(500) })
}

@Composable
private fun QualitySetting(graph: AppGraph) {
    val quality by graph.library.quality.collectAsStateWithLifecycle()
    TextStepper("音质偏好", quality.label, quality.note, "降低", "提高",
        quality != Quality.entries.first(), quality != Quality.entries.last(),
        { shiftQuality(graph, quality, -1) }, { shiftQuality(graph, quality, 1) })
    UtilityBody("实际播放音质以服务端授权为准。")
}

@Composable
private fun TextStepper(title: String, value: String, description: String,
                        decreaseLabel: String, increaseLabel: String,
                        canDecrease: Boolean, canIncrease: Boolean,
                        decrease: () -> Unit, increase: () -> Unit) {
    Surface(color = WatchTokens.Surface, shape = RoundedCornerShape(WatchTokens.ListItemRadius)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = WatchTokens.SpaceMedium, vertical = 4.dp)) {
            Text(title, style = WatchTypography.CardTitle)
            Text(description, style = WatchTypography.Supporting)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AdjustmentButton("$title，$decreaseLabel", false, canDecrease, decrease)
                Text(value, style = WatchTypography.Label, color = WatchTokens.Primary,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                AdjustmentButton("$title，$increaseLabel", true, canIncrease, increase)
            }
        }
    }
}

@Composable
private fun AdjustmentButton(label: String, increase: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(WatchTokens.MinimumTouch).semantics { contentDescription = label }) {
        Icon(painterResource(if (increase) R.drawable.ic_plus else R.drawable.ic_minus), null,
            Modifier.size(16.dp), tint = if (enabled) WatchTokens.TextPrimary else WatchTokens.TextMuted)
    }
}

private fun shiftQuality(graph: AppGraph, current: Quality, step: Int) {
    val index = (Quality.entries.indexOf(current) + step).coerceIn(Quality.entries.indices)
    graph.library.setQuality(Quality.entries[index])
}

private fun networkLabel(network: NetworkKind): String = when (network) {
    NetworkKind.WIFI -> "Wi-Fi"
    NetworkKind.MOBILE -> "移动数据"
    NetworkKind.BLUETOOTH -> "蓝牙"
    NetworkKind.NONE -> "未连接"
}

@Composable
private fun CacheSettings(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lyricsResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var coverResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var clearingCover by remember { mutableStateOf(false) }
    SectionLabel("缓存")
    WatchListItem("清理歌词缓存", onClick = {
        lyricsResult = if (runCatching { graph.gateway.clearLyrics() }.isSuccess) "歌词缓存已清理" to true
        else "歌词缓存清理失败，请重试" to false
    }, icon = R.drawable.ic_lyric)
    CacheFeedback(lyricsResult)
    WatchListItem(if (clearingCover) "正在清理封面…" else "清理封面缓存", onClick = {
        clearingCover = true
        coverResult = null
        scope.launch {
            try {
                CoverCache.shared(context).clear()
                coverResult = "封面缓存已清理" to true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coverResult = "封面缓存清理失败，请重试" to false
            } finally {
                clearingCover = false
            }
        }
    }, enabled = !clearingCover, icon = R.drawable.ic_library)
    CacheFeedback(coverResult)
}

@Composable
private fun CacheFeedback(result: Pair<String, Boolean>?) {
    result?.let { (message, success) ->
        InlineBanner(message, color = if (success) WatchTokens.Secondary else WatchTokens.Error)
    }
}

@Composable
private fun NotificationSetting() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember(context) { mutableStateOf(notificationsAllowed(context)) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(context, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = notificationsAllowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!allowed) {
        SectionLabel("系统通知")
        UtilityBody("通知已关闭，系统媒体入口不可用，播放仍可继续。")
        WatchListItem("开启媒体通知", onClick = {
            error = !openSystemSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, subtitle = "前往系统设置")
        if (error) InlineBanner("暂时无法打开通知设置", color = WatchTokens.Error)
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openSystemSettings(context: Context, intent: Intent): Boolean = runCatching {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

@Composable
internal fun AboutContent() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)) {
        Title("关于腕上听")
        Brand(32)
        Text("腕上听", style = WatchTypography.StateTitle)
        Text("版本 ${BuildConfig.VERSION_NAME}", style = WatchTypography.Supporting)
        Text("作者 · 存存", style = WatchTypography.Label, color = WatchTokens.Primary)
        Text("音乐，随腕而行", style = WatchTypography.Body, color = WatchTokens.Secondary)
        UtilityBody("为圆形手表设计的在线音乐播放器，支持发现、排行榜、精选与云歌单、每日推荐、专辑、搜索、播放队列、同步歌词、定时关闭与手机 DLNA 音乐投放。")
        SectionLabel("开发者信息")
        UtilityBody("QQ 3056945480")
        SectionLabel("项目说明")
        UtilityBody("本应用为个人独立项目，与酷狗音乐、OPPO 无隶属或合作关系。歌曲、封面与歌词权益归各权利人所有。")
        SectionLabel("第三方组件")
        UtilityBody("ZXing（Apache 2.0）· 二维码生成\nMedia3 · ExoPlayer 播放\nOkHttp · 网络请求\nCompose · 界面框架")
        UtilityBody("播放器控件布局改编自 Google Horologist，采用 Apache 2.0 许可。许可文本随应用提供。")
        SectionLabel("歌曲版权")
        UtilityBody("在线歌曲版权与播放权限均以酷狗服务端返回为准。支持导入用户自有音乐离线播放，不提供酷狗音频下载。")
    }
}

@Composable
private fun UtilityBody(text: String) {
    Text(text, style = WatchTypography.Body, color = WatchTokens.TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = WatchTokens.SpaceExtraSmall))
}

@Composable
private fun LowEmphasisAction(text: String, enabled: Boolean = true,
                              color: Color = WatchTokens.TextSecondary, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = WatchTokens.MinimumTouch)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = WatchTypography.Supporting,
            color = if (enabled) color else WatchTokens.TextMuted, textAlign = TextAlign.Center)
    }
}
