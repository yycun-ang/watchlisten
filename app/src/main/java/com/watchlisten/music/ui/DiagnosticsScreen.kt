package com.watchlisten.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.watchlisten.music.AppGraph
import com.watchlisten.music.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DiagnosticsScreen(graph: AppGraph, open: (String) -> Unit) {
    val events by graph.diagnostics.events.collectAsStateWithLifecycle()
    val status by graph.diagnostics.exportStatus.collectAsStateWithLifecycle()
    val network by graph.music.network.collectAsStateWithLifecycle()
    val playback by graph.playback.state.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    RoundFrame(list) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Title("连接与播放诊断") }
            item { SettingRow("当前网络", when (network) { NetworkKind.WIFI -> "Wi-Fi"; NetworkKind.BLUETOOTH -> "蓝牙共享"; NetworkKind.MOBILE -> "移动网络"; NetworkKind.NONE -> "未连接" }) }
            item { SettingRow("播放器", when { playback.error != null -> "出现播放错误"; playback.buffering -> "正在缓冲"; playback.playing -> "播放中"; else -> "未在播放" }) }
            item { PrimaryAction("导出诊断文件") { open("export-diagnostics") } }
            status?.let { item { Notice(it) } }
            item { Text("记录仅保存在手表，不含账号、歌曲内容或访问地址；你选择保存时才导出。",
                style = WatchTypography.Supporting) }
            item { SectionLabel("最近状态 · ${events.size} 条") }
            items(events.asReversed().take(8)) { event ->
                val time = remember(event.timeMs) { SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(event.timeMs)) }
                Text("$time · ${diagnosticLabel(event)}", style = WatchTypography.Supporting, modifier = Modifier.fillMaxWidth())
            }
            item { SecondaryAction("清除本机诊断记录", enabled = events.isNotEmpty()) { clear = true } }
            if (clear) {
                item { Notice("清除这份手表上的状态记录？") }
                item { SecondaryAction("确认清除", color = WatchTokens.Error) { graph.diagnostics.clear(); clear = false } }
                item { SecondaryAction("保留记录") { clear = false } }
            }
        }
    }
}

private fun diagnosticLabel(event: DiagnosticEvent): String {
    val area = when (event.area) { DiagnosticArea.NETWORK -> "网络"; DiagnosticArea.PLAYBACK -> "播放"; DiagnosticArea.CLOUD -> "云同步"; DiagnosticArea.TRANSFER -> "传歌"; DiagnosticArea.CAST -> "手机投放" }
    val code = when (event.code) {
        DiagnosticCode.WIFI -> "Wi-Fi"; DiagnosticCode.MOBILE -> "移动网络"; DiagnosticCode.BLUETOOTH -> "蓝牙共享"
        DiagnosticCode.OFFLINE -> "未联网"; DiagnosticCode.CONNECTED -> "已连接"; DiagnosticCode.DISCONNECTED -> "已断开"
        DiagnosticCode.PLAYING -> "播放中"; DiagnosticCode.PAUSED -> "已暂停"; DiagnosticCode.BUFFERING -> "正在缓冲"
        DiagnosticCode.STARTED -> "已开启"; DiagnosticCode.STOPPED -> "已停止"; DiagnosticCode.CONFIRMED -> "已确认"
        DiagnosticCode.AWAITING_CONFIRMATION -> "等待云端确认"; DiagnosticCode.TIMEOUT -> "响应超时"
        DiagnosticCode.AUTH_EXPIRED -> "登录已失效"; DiagnosticCode.PERMISSION_DENIED -> "授权不足"
        DiagnosticCode.VERIFICATION_REQUIRED -> "需要验证"; DiagnosticCode.CONSENT_REQUIRED -> "等待同意说明"
        DiagnosticCode.MOBILE_BLOCKED -> "移动流量已关闭"; DiagnosticCode.UNAVAILABLE -> "内容不可用"
        DiagnosticCode.DNS_ERROR -> "域名解析失败"; DiagnosticCode.NETWORK_ERROR -> "连接错误"
        DiagnosticCode.INVALID_RESPONSE -> "响应异常"; DiagnosticCode.PLAYBACK_ERROR -> "播放错误"
        DiagnosticCode.NONE, DiagnosticCode.UNKNOWN_ERROR -> "暂时异常"
    }
    return "$area · $code"
}
