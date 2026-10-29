package com.watchlisten.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.watchlisten.music.data.ConsentStore

/** 首次同意前只绘制本地内容，不初始化账号页、搜索或封面请求。 */
@Composable
fun ConsentScreen(onAccept: () -> Boolean, onDecline: () -> Unit) {
    val list = rememberLazyListState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = MusicColors) {
        RoundFrame(list) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 34.dp, bottom = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Brand(32) }
                item { Title("欢迎使用腕上听", "音乐，随腕而行") }
                item { ConsentParagraph("使用前请了解", "本应用由存存开发，是独立第三方手表客户端，与酷狗官方无隶属或授权合作关系。") }
                item { ConsentParagraph("联网与账号", "搜索、播放和扫码登录会连接酷狗服务，发送必要的搜索词、歌曲标识、设备标识及登录凭据。头像、昵称和会员资料由服务端提供。") }
                item { ConsentParagraph("版权与会员", "仅播放服务端允许的内容。会员身份不代表全部歌曲均可完整播放；部分内容可能仅可试听或需要另行授权。") }
                item { ConsentParagraph("可选手机投放", "手动开启后，同一 Wi-Fi 内的手机可发现手表并投放音乐、控制播放与音量。设备名称和局域网地址会在网络中提供，接收默认关闭。") }
                item {
                    SecondaryAction(if (expanded) "收起完整说明" else "阅读隐私与免责声明") { expanded = !expanded }
                }
                if (expanded) item { LegalDetails() }
                item { Notice("点击下方“同意并使用”，表示已阅读并同意隐私与免责声明。可在设置中查看或撤回。") }
                if (saveFailed) item { InlineBanner("无法保存同意状态，请重试") }
                item { PrimaryAction("同意并使用") { saveFailed = !onAccept() } }
                item { SecondaryAction("暂不使用", onClick = onDecline) }
            }
        }
    }
}

/** 放在设置详情页中，撤回必须先看到影响，再进行第二次明确操作。 */
@Composable
fun PrivacyContent(onWithdraw: () -> Boolean) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title("隐私与免责声明", "说明版本 ${ConsentStore.DOCUMENT_VERSION}")
        LegalDetails()
        if (confirming) {
            InlineBanner("撤回后停止播放、退出手表账号并关闭联网与传歌。酷狗云端喜欢和歌单仍保留。")
            if (failed) InlineBanner("暂时无法保存撤回状态，请重试")
            PrimaryAction("确认撤回并停止使用") { failed = !onWithdraw() }
            SecondaryAction("保留当前设置") { confirming = false; failed = false }
        } else {
            SecondaryAction("撤回同意", color = WatchTokens.Error) { confirming = true }
        }
    }
}

@Composable
private fun LegalDetails() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConsentParagraph("独立第三方应用", "腕上听由存存开发，供手表用户访问酷狗服务。本应用不是酷狗官方产品；酷狗的名称、商标、头像、歌曲及相关内容属于相应权利人。")
        ConsentParagraph("必要联网信息", "使用搜索、排行榜、精选歌单、扫码登录、账号资料、封面、歌词和音频播放时，会向酷狗及其内容分发服务发送相应请求。打开云歌单或每日推荐时，使用登录授权读取相应账号的音乐库与推荐内容。服务方可能收到 IP 地址、搜索词、歌曲或歌单标识、安装生成的设备标识，以及已登录时必要的账号标识和凭据。")
        ConsentParagraph("云端音乐库", "喜欢和歌单使用当前酷狗账号的云端数据。点按喜欢、取消喜欢、创建或整理歌单，会向酷狗提交相应更改，并在服务端确认后显示结果。旧版手表收藏可由你选择迁入当前账号，未确认成功的旧记录会保留待重试。")
        ConsentParagraph("本地保存", "登录凭据与账号资料使用 Android 系统密钥加密保存在本机。最近播放、搜索历史、偏好设置、封面缓存和你导入的音乐保存在应用私有目录；退出或换号会清除旧身份的私人云库内存内容。本应用没有独立的互联网用户数据服务器，不内置广告或第三方行为分析。")
        ConsentParagraph("本机诊断", "同意后在手表本地保留最近 96 条网络、播放、云同步、传歌与投放的白名单状态，供你排查问题。不记录账号凭据、歌曲内容、搜索词或访问地址。你可在“连接与播放诊断”清除记录；仅在你选择保存位置后导出设备型号、版本和这些状态，不自动发送给开发者。撤回同意会停止记录并清除本机诊断。")
        ConsentParagraph("文件导入与语音", "导入和离线播放仅处理你有权使用的音频文件。手机传歌由你手动开启，在当前 Wi-Fi 临时提供有验证码的接收页面，15 分钟后自动关闭；文件仅保存在手表。语音搜索调用系统语音服务，语音处理方式以该服务的说明为准，识别的歌曲名称用于酷狗搜索。")
        ConsentParagraph("本地歌词与文件导出", "关联歌词只读取你在系统选择器明确选中的文本，并绑定到选定的本机音乐。原音频和诊断文件只有在你选择系统保存位置后才导出。选择器期间仅在私有目录保留待操作曲目 ID，完成或取消后清理。传歌断点仅在当前会话内保留，结束会话会清理暂存。")
        ConsentParagraph("扫码与账号安全", "请使用手机上的酷狗客户端扫描手表二维码，并在手机端自行确认授权。我们不要求输入酷狗密码。不要向他人提供二维码、登录凭据或账号敏感信息。退出登录会删除本机登录状态，不会注销酷狗账号。")
        ConsentParagraph("会员与版权限制", "会员信息和歌曲权益以酷狗服务端的实时授权为准，可能因账号、歌曲、地区、音质及有效期而不同。应用不绕过付费、会员、试听或版权限制；无法确认的权益会显示为待确认。请仅在有权使用的范围内收听，不对音频进行未经授权的复制或传播。")
        ConsentParagraph("局域网音乐投放", "手机投放默认关闭，需在投放页手动开启。开启后使用 DLNA / UPnP 向同一 Wi-Fi 网络发布设备名称、局域网地址和播放能力，并接收手机提供的歌曲标题、艺人、封面与音频地址，以及播放、进度和音量指令。投放协议不加密，请仅在信任的网络使用；同网设备可能发现并请求控制。当前连接信息用于保持播放与回报状态，不提供独立上传服务器。投放内容的会员、版权和有效期由手机及服务端决定。你可随时断开手机或关闭接收；等待 10 分钟、暂停或停止 30 分钟后自动关闭。")
        ConsentParagraph("服务可用性", "网络、第三方接口调整及账号状态可能导致登录、资料或播放暂时不可用。界面缓存的会员信息仅供参考，不构成权益承诺；购买、续费、退款与账号申诉请在酷狗官方渠道办理。")
        ConsentParagraph("你的选择", "同意后才启用联网。你可以不登录使用可公开访问的内容，也可在个人中心退出本机账号。在设置中撤回同意会停止播放、退出本机账号并关闭联网；卸载应用可删除本机应用数据。通知权限由系统另行征求，拒绝不会阻止基础使用。")
        Text("开发者：存存\n更新日期：${ConsentStore.DOCUMENT_DATE}",
            style = WatchTypography.Metadata, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConsentParagraph(title: String, body: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = WatchTypography.CardTitle)
        Text(body, style = WatchTypography.Body, color = WatchTokens.TextSecondary)
    }
}
