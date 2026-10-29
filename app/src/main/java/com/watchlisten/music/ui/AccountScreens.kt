package com.watchlisten.music.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.watchlisten.music.AppGraph
import com.watchlisten.music.R
import com.watchlisten.music.domain.MusicState
import com.watchlisten.music.model.LoginPhase
import com.watchlisten.music.model.Membership
import com.watchlisten.music.model.MembershipStatus
import com.watchlisten.music.model.SessionData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 资料先显示本机的加密缓存，再由账号生命周期刷新；未知资料不会填成真实信息。 */
@Composable
internal fun AccountContent(graph: AppGraph, music: MusicState, open: (String) -> Unit) {
    val session by graph.sessions.session.collectAsStateWithLifecycle()
    val favorites by graph.favorites.state.collectAsStateWithLifecycle()
    val history by graph.library.history.collectAsStateWithLifecycle()
    AccountLifecycle(graph)
    AccountPanel(session, music, if (favorites.loaded) favorites.tracks.size else -1, history.size,
        onRefreshProfile = graph.music::refreshProfile,
        onRefreshQr = { graph.music.startLogin(true) },
        onLogout = { graph.music.logout(); Unit }, open = open)
}

/** 仅接收显示状态和动作；debug 样本可复用生产 UI，不接触真实账号或联网。 */
@Composable
internal fun AccountPanel(
    session: SessionData?, music: MusicState, favoritesCount: Int, historyCount: Int,
    onRefreshProfile: () -> Unit, onRefreshQr: () -> Unit, onLogout: () -> Unit,
    open: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WatchTokens.SpaceSmall)) {
        Title(if (session == null) "登录酷狗" else "个人中心")
        val current = session
        if (current == null) {
            LoginCode(music)
            SecondaryAction("刷新二维码", enabled = !music.loginBusy, color = WatchTokens.Primary) {
                onRefreshQr()
            }
            AccountNote("打开手机酷狗「扫一扫」，在手机上确认登录。请勿将二维码分享给他人。")
        } else {
            AccountIdentitySection(current)
            val profile = current.profile
            when {
                music.profileLoading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp,
                        color = WatchTokens.Primary)
                    Text("正在同步账号资料", style = WatchTypography.Supporting)
                }
                music.profileError != null -> {
                    InlineBanner(music.profileError, color = WatchTokens.Warning,
                        actionLabel = "重试", onAction = onRefreshProfile)
                    if (profile != null) Text("当前显示上次同步的资料", style = WatchTypography.Metadata,
                        textAlign = TextAlign.Center)
                }
                profile != null && profile.updatedAtMs > 0 -> Text(
                    "更新于 ${accountDate(profile.updatedAtMs, true)}",
                    style = WatchTypography.Metadata, textAlign = TextAlign.Center
                )
            }
            WatchListItem("酷狗云歌单", { open("cloud") }, subtitle = "手机上的创建与收藏", icon = R.drawable.ic_library)
            LocalLibrarySummary(favoritesCount, historyCount, open)
            SectionLabel("账号管理")
            WatchListItem("刷新账号资料", onRefreshProfile,
                subtitle = "昵称、头像与会员状态", icon = R.drawable.ic_refresh,
                enabled = !music.profileLoading)
            WatchListItem("隐私与使用说明", { open("privacy") }, icon = R.drawable.ic_info)
            AccountNote("会员身份与单曲播放权限分别由服务端确认，具体以歌曲的播放结果为准。")
            LogoutAction(current.userId, onLogout)
        }
    }
}

/** 与生产个人中心共用的资料块；debug 可使用明确标注的样本独立验证圆屏排版。 */
@Composable
internal fun AccountIdentitySection(session: SessionData) {
    ProfileHeader(session)
    MembershipCard(session.profile?.vip, session.profile?.memberships.orEmpty())
    session.profile?.signature?.takeIf(String::isNotBlank)?.let { signature ->
        Text(signature, style = WatchTypography.Supporting, maxLines = 3,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
    }
}

@Composable
private fun ProfileHeader(session: SessionData) {
    val context = LocalContext.current
    val profile = session.profile
    Row(Modifier.fillMaxWidth().heightIn(min = WatchTokens.MinimumTouch)
        .clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) {
            val copied = runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("酷狗 UID", session.userId))
            }.isSuccess
            Toast.makeText(context, if (copied) "UID 已复制" else "暂时无法复制 UID", Toast.LENGTH_SHORT).show()
        }.semantics(mergeDescendants = true) {
            contentDescription = "酷狗账号 ${profile?.nickname.orEmpty()}，UID ${session.userId}，点按复制 UID"
        }.padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Cover(profile?.avatarUrl.orEmpty(), Modifier.size(42.dp)
            .semantics { contentDescription = "酷狗账号头像" }, cornerRadius = 21.dp,
            fallback = {
                Icon(painterResource(R.drawable.ic_user), null, Modifier.size(22.dp),
                    tint = WatchTokens.TextSecondary)
            })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(profile?.nickname?.takeIf(String::isNotBlank) ?: "昵称待同步",
                style = WatchTypography.CardTitle.copy(fontSize = 14.sp, lineHeight = 18.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("UID ${session.userId} · 复制",
                style = WatchTypography.Metadata.copy(fontSize = 9.sp, lineHeight = 12.sp),
                color = WatchTokens.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MembershipCard(membership: Membership?, products: List<Membership>) {
    val current = membership ?: Membership()
    val expired = membership?.expiresAtMs?.let { it > 0 && it <= System.currentTimeMillis() } == true
    val active = membership?.effectiveStatus() == MembershipStatus.ACTIVE
    val known = membership != null && membership.effectiveStatus() != MembershipStatus.UNKNOWN
    val color = if (active) WatchTokens.Warning else WatchTokens.TextSecondary
    Surface(color = WatchTokens.Surface, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when {
                !known -> "会员状态待同步"
                expired -> "会员已到期"
                active -> current.label.ifBlank { "酷狗会员" }
                else -> "非会员账号"
            }, style = WatchTypography.CardTitle, color = color)
            Text(when {
                !known -> "资料返回后显示真实权益"
                current.expiresAtMs > 0 -> "${accountDate(current.expiresAtMs)} 到期"
                active -> "会员身份已同步"
                else -> "可听范围以单曲授权为准"
            }, style = WatchTypography.Supporting)
            if (active && current.level > 0) Text("会员等级 ${current.level}",
                style = WatchTypography.Metadata, color = WatchTokens.TextSecondary)
            products.filter { it.label != membership?.label }.forEach { product ->
                val status = when (product.effectiveStatus()) {
                    MembershipStatus.ACTIVE -> "有效"
                    MembershipStatus.INACTIVE -> if (product.expiresAtMs > 0 &&
                        product.expiresAtMs <= System.currentTimeMillis()) "已到期" else "未开通"
                    MembershipStatus.UNKNOWN -> "待确认"
                }
                Text("${product.label} · $status" +
                    if (product.expiresAtMs > 0) " · ${accountDate(product.expiresAtMs)}" else "",
                    style = WatchTypography.Metadata, color = WatchTokens.TextSecondary)
            }
        }
    }
}

@Composable
private fun LocalLibrarySummary(favoritesCount: Int, historyCount: Int, open: (String) -> Unit) {
    SectionLabel("我的音乐")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LibraryStat("酷狗喜欢", favoritesCount, R.drawable.ic_heart, Modifier.weight(1f)) { open("favorites") }
        LibraryStat("最近播放", historyCount, R.drawable.ic_clock, Modifier.weight(1f)) { open("history") }
    }
    Text("喜欢同步酷狗 · 最近播放记录在手表", style = WatchTypography.Metadata,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LibraryStat(label: String, count: Int, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = WatchTokens.Surface, shape = RoundedCornerShape(14.dp),
        modifier = modifier.heightIn(min = WatchTokens.MinimumTouch)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(painterResource(icon), null, Modifier.size(13.dp), tint = WatchTokens.TextSecondary)
                Text(if (count < 0) "待同步" else count.toString(), style = WatchTypography.CardTitle)
            }
            Text(label, style = WatchTypography.Metadata, color = WatchTokens.TextSecondary)
        }
    }
}

@Composable
private fun LogoutAction(userId: String, onLogout: () -> Unit) {
    var confirming by remember(userId) { mutableStateOf(false) }
    if (confirming) {
        AccountNote("退出后清除手表登录凭据并停止播放，酷狗云端喜欢和歌单会保留。手表收听记录和导入文件不受影响。")
        PrimaryAction("确认退出登录") { confirming = false; onLogout() }
        SecondaryAction("暂不退出") { confirming = false }
    } else {
        SecondaryAction("退出登录", color = WatchTokens.Error) { confirming = true }
    }
}

@Composable
private fun AccountLifecycle(graph: AppGraph) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(graph, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> graph.music.accountActive(true)
                Lifecycle.Event.ON_PAUSE -> graph.music.accountActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        graph.music.accountActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); graph.music.accountActive(false) }
    }
}

@Composable
internal fun LoginCode(music: MusicState) {
    val qr = music.qr
    val bitmap = remember(qr?.key, qr?.url) { qr?.let { runCatching { qrBitmap(it.url) }.getOrNull() } }
    if (bitmap != null && music.loginPhase in setOf(LoginPhase.WAITING, LoginPhase.SCANNED)) {
        Surface(shape = RoundedCornerShape(9.dp), color = Color.White) {
            Image(bitmap.asImageBitmap(), "使用手机酷狗扫描此二维码登录",
                Modifier.padding(4.dp).size(120.dp), filterQuality = FilterQuality.None)
        }
    } else if (music.loginBusy) {
        Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = WatchTokens.Primary, strokeWidth = 2.dp)
        }
    }
    if (qr != null && bitmap == null) {
        InlineBanner("二维码暂时无法显示，请刷新重试", color = WatchTokens.Error)
    } else {
        Text(music.loginMessage, style = WatchTypography.Body,
            color = when (music.loginPhase) {
                LoginPhase.SCANNED -> WatchTokens.Primary
                LoginPhase.ERROR, LoginPhase.EXPIRED -> WatchTokens.Warning
                else -> WatchTokens.TextSecondary
            }, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    if (qr != null && music.qrRemainingSeconds > 0 &&
        music.loginPhase in setOf(LoginPhase.WAITING, LoginPhase.SCANNED)) {
        Text("${music.qrRemainingSeconds} 秒后更新二维码", style = WatchTypography.Metadata,
            textAlign = TextAlign.Center)
    }
}

private fun qrBitmap(url: String): Bitmap {
    val size = 240
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { index ->
        if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun AccountNote(text: String) {
    Text(text, style = WatchTypography.Supporting,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
}

private fun accountDate(timeMs: Long, includeTime: Boolean = false): String =
    SimpleDateFormat(if (includeTime) "MM-dd HH:mm" else "yyyy.MM.dd", Locale.CHINA).format(Date(timeMs))
