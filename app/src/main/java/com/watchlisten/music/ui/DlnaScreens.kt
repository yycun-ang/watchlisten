package com.watchlisten.music.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.dlna.DlnaStatus

/** 只有用户打开开关才启用接收；离开页面不会改变后台投放状态。 */
@Composable
internal fun DlnaContent(graph: AppGraph, open: (String) -> Unit) {
    val state by graph.dlna.state.collectAsStateWithLifecycle()
    val playback by graph.playback.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val list = rememberLazyListState()
    var settingsError by remember { mutableStateOf(false) }
    RoundFrame(list) {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)) {
            item { Title("手机投放", "酷狗 · DLNA") }
            item { DlnaConnectionCard(state) }
            item { ToggleSetting("接收手机音乐", if (state.enabled) "同一 Wi-Fi 可发现" else "需要时开启，默认关闭",
                state.enabled) { enabled -> if (enabled) graph.dlna.enable() else graph.dlna.disable() } }
            state.error?.let { item { InlineBanner(it, color = WatchTokens.Error) } }
            state.notice?.let { item { InlineBanner(it, color = WatchTokens.Warning) } }
            if (state.ready && state.address.isNotBlank() && state.port > 0) {
                item { SettingRow("局域网地址", "${state.address}:${state.port}") }
            }
            if (state.peer != null) {
                item { SettingRow("当前控制手机", state.peer) }
                if (playback.current?.track?.isRemote == true) {
                    item { WatchListItem("正在投放", { open("player") }, subtitle = playback.current?.track?.title,
                        icon = R.drawable.ic_bars, accent = WatchTokens.Primary) }
                }
                item { SecondaryAction("断开当前手机", color = WatchTokens.Error, onClick = graph.dlna::disconnect) }
            }
            if (!state.enabled && state.error != null) {
                item { PrimaryAction("重新开启", onClick = graph.dlna::enable) }
            }
            item { SectionLabel("用手机酷狗播放") }
            item { DlnaHelp("1", "连接同一 Wi-Fi", "手机和手表连接同一个局域网，蓝牙连接不能代替 Wi-Fi。") }
            item { DlnaHelp("2", "选择这块手表", "打开手机酷狗的播放页，在设备投放 / DLNA 列表中选择下方设备。入口名称可能随手机版本不同。") }
            item { Text(state.name, style = WatchTypography.Label, color = WatchTokens.Primary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
            item { DlnaHelp("3", "手机选歌，手表播放", "手机控制播放、暂停、进度与音量。歌曲封面和标题由手机提供，歌词请在手机查看。") }
            item { SecondaryAction("Wi-Fi 设置") {
                settingsError = runCatching {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isFailure
            } }
            if (settingsError) item { InlineBanner("暂时无法打开 Wi-Fi 设置，请使用手表系统设置。", color = WatchTokens.Error) }
            item { ReceiverPowerSettings() }
            state.lastDisconnect?.let { item { SettingRow("最近中断", it) } }
            item { SectionLabel("连接与隐私") }
            item { Text("仅在信任的 Wi-Fi 中开启。同网设备可发现设备名称，并请求播放与控制；接入后由当前手机控制。关闭接收会断开连接。",
                style = WatchTypography.Body, color = WatchTokens.TextSecondary) }
            item { Text("等待投放 10 分钟、暂停或停止 30 分钟后自动关闭。网络隔离、热点设置或手机歌曲的授权限制可能影响发现和播放。",
                style = WatchTypography.Supporting) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun ReceiverPowerSettings() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    fun readPowerExemption() = runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    var isExempt by remember(context) { mutableStateOf(readPowerExemption()) }
    var unavailable by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) isExempt = readPowerExemption() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("稳定连接")
        Text("手机酷狗和手表应用都需允许后台运行。若锁屏后容易断开，可检查两端的省电与后台限制。",
            style = WatchTypography.Supporting)
        SettingRow("系统电量优化", if (isExempt) "已豁免，厂商设置仍可能限制后台" else "息屏时可能限制网络")
        SecondaryAction("后台电量设置") {
            val opened = runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            unavailable = !opened && runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isFailure
        }
        if (unavailable) InlineBanner("请在手表系统设置中允许后台运行。", color = WatchTokens.Warning)
    }
}

@Composable
private fun DlnaConnectionCard(state: DlnaStatus) {
    val label = when {
        state.error != null -> "连接暂不可用"
        state.starting -> "正在准备连接"
        state.peer != null -> "手机已连接"
        state.ready -> "等待手机投放"
        state.enabled -> "等待 Wi-Fi 连接"
        else -> "接收已关闭"
    }
    val detail = when {
        state.error != null -> "检查 Wi-Fi 后重新开启"
        state.starting -> "即将在设备列表中出现"
        state.peer != null -> "手机选歌 · 手表播放"
        state.ready -> "手机酷狗中选择手表"
        state.enabled -> "连接同一 Wi-Fi 后可被发现"
        else -> "手机选歌，手表播放"
    }
    val accent = when {
        state.error != null -> WatchTokens.Error
        state.peer != null -> WatchTokens.Secondary
        state.ready || state.starting -> WatchTokens.Primary
        else -> WatchTokens.TextSecondary
    }
    Surface(color = WatchTokens.Surface, shape = RoundedCornerShape(WatchTokens.CardRadius),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(accent.copy(alpha = .10f)),
                contentAlignment = Alignment.Center) {
                if (state.starting) CircularProgressIndicator(Modifier.size(17.dp), color = accent, strokeWidth = 1.5.dp)
                else Icon(painterResource(R.drawable.ic_wifi), null, Modifier.size(18.dp), tint = accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = WatchTypography.CardTitle, color = accent)
                Text(detail, style = WatchTypography.Supporting)
            }
        }
    }
}

@Composable
private fun DlnaHelp(number: String, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 1.dp).size(19.dp).clip(CircleShape).background(WatchTokens.SurfaceRaised),
            contentAlignment = Alignment.Center) {
            Text(number, style = WatchTypography.Metadata, color = WatchTokens.Primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = WatchTypography.CardTitle)
            Text(detail, style = WatchTypography.Supporting)
        }
    }
}
