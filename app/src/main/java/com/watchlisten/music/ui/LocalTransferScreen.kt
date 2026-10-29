package com.watchlisten.music.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.watchlisten.music.AppGraph
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun LocalTransferScreen(graph: AppGraph, open: (String) -> Unit) {
    val state by graph.transfer.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.expiresAtElapsedMs) {
        while (state.enabled) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    RoundScreenScaffold(list) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall),
            horizontalAlignment = Alignment.CenterHorizontally) {
            item { Title("手机传歌", if (state.ready) null else "把自己的音乐带到手表") }
            state.error?.let { message -> item { InlineBanner(message,
                color = if (message.contains("已保留")) WatchTokens.Warning else WatchTokens.Error) } }
            when {
                state.starting -> item { WatchLoadingState("正在开启", "正在连接 Wi-Fi") }
                !state.enabled -> {
                    item { Text("手机和手表连接同一个 Wi-Fi，在手机浏览器选择音乐文件，即可传入手表离线播放。",
                        style = WatchTypography.Body, color = WatchTokens.TextSecondary) }
                    item { PrimaryAction("开启手机传歌", onClick = graph.transfer::start) }
                    item { Text("仅在开启期间接收，15 分钟后自动关闭。",
                        style = WatchTypography.Metadata, textAlign = TextAlign.Center) }
                }
                state.ready -> {
                    item {
                        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(WatchTokens.Surface)
                            .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("传歌验证码", style = WatchTypography.Metadata)
                            Text(state.code, style = WatchTypography.PageTitle.copy(fontSize = 16.sp,
                                lineHeight = 20.sp, letterSpacing = 1.5.sp),
                                color = WatchTokens.Primary, modifier = Modifier.semantics {
                                    contentDescription = "传歌验证码：${state.code.toList().joinToString(" ")}"
                                })
                        }
                    }
                    item { TransferQr(state.url) }
                    item { Text("手机扫码，输入上方验证码", style = WatchTypography.Metadata,
                        textAlign = TextAlign.Center) }
                    item { Text(state.url, style = WatchTypography.Supporting,
                        color = WatchTokens.Primary, textAlign = TextAlign.Center) }
                    if (state.uploading) item {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.fileName, style = WatchTypography.CardTitle, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            LinearProgressIndicator(progress = {
                                if (state.totalBytes > 0) (state.receivedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f) else 0f
                            }, modifier = Modifier.fillMaxWidth(), color = WatchTokens.Primary,
                                trackColor = WatchTokens.SurfaceRaised)
                            Text("${transferMegabytes(state.receivedBytes)} / ${transferMegabytes(state.totalBytes)} MB",
                                style = WatchTypography.Metadata)
                        }
                    }
                    if (!state.uploading) state.message?.let { message -> item {
                        Text(message, style = WatchTypography.Supporting, color = WatchTokens.Secondary,
                            textAlign = TextAlign.Center)
                    } }
                    item { Text("${clockTime((state.expiresAtElapsedMs - now).coerceAtLeast(0))} 后自动关闭 · 已传 ${state.completed} 首",
                        style = WatchTypography.Metadata, textAlign = TextAlign.Center) }
                    item { Text("断线可在手机继续当前任务；会话结束后清理暂存。",
                        style = WatchTypography.Metadata, textAlign = TextAlign.Center) }
                    item { SecondaryAction("停止传歌", color = WatchTokens.Error, onClick = graph.transfer::stop) }
                }
            }
            if (state.completed > 0) item { SecondaryAction("查看本地音乐") { open("local-music") } }
        }
    }
}

@Composable
private fun TransferQr(url: String) {
    val side = with(LocalDensity.current) { 104.dp.roundToPx() }.coerceAtLeast(96)
    val bitmap = remember(url, side) {
        runCatching {
            val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, side, side)
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
                val pixels = IntArray(side * side) { index -> if (matrix[index % side, index / side])
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                setPixels(pixels, 0, side, 0, 0, side, side)
            }.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, "手机扫码打开传歌网页",
        Modifier.size(104.dp).clip(RoundedCornerShape(10.dp)).background(Color.White), filterQuality = FilterQuality.None)
}

private fun transferMegabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)
