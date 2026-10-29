package com.watchlisten.music.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal enum class CloudFeedbackAction { UNDO, RETRY }

internal data class CloudFeedbackPresentation(val text: String, val action: CloudFeedbackAction?,
                                             val actionLabel: String?)

/** 撤销失败后仍保留原操作入口；刷新清空文案时也不能丢失尚在有效期内的撤销。 */
internal fun cloudFeedbackPresentation(message: String?, error: String?, undoAvailable: Boolean,
                                       undoExpiresAtMs: Long, nowMs: Long,
                                       retryLabel: String): CloudFeedbackPresentation? {
    val canUndo = undoAvailable && nowMs < undoExpiresAtMs
    val text = error ?: message ?: if (canUndo) "可撤销云端移除" else return null
    return when {
        canUndo -> CloudFeedbackPresentation(text, CloudFeedbackAction.UNDO, if (error != null) "重试撤销" else "撤销")
        error != null -> CloudFeedbackPresentation(text, CloudFeedbackAction.RETRY, retryLabel)
        else -> CloudFeedbackPresentation(text, null, null)
    }
}

/** 在列表之外分配空间，歌曲移除后反馈仍在当前视野内，避免挡住正文。 */
@Composable
internal fun CloudFeedbackStrip(message: String?, error: String?, undoAvailable: Boolean,
                                undoExpiresAtMs: Long, onUndo: () -> Unit, onRetry: () -> Unit,
                                onDismiss: () -> Unit, busy: Boolean = false,
                                retryLabel: String = "重试") {
    var dismissed by remember(message, error) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(message, error, undoAvailable, undoExpiresAtMs) {
        now = SystemClock.elapsedRealtime()
        if (undoAvailable) {
            while (now < undoExpiresAtMs) { delay(1000); now = SystemClock.elapsedRealtime() }
            if (error == null) dismissed = true
        } else if (error == null && message != null) { delay(4500); dismissed = true }
    }
    val presentation = cloudFeedbackPresentation(message, error, undoAvailable, undoExpiresAtMs, now, retryLabel)
    if (dismissed || presentation == null) return
    Surface(shape = RoundedCornerShape(14.dp), color = WatchTokens.SurfaceRaised,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(presentation.text, style = WatchTypography.Supporting,
                color = if (error != null) WatchTokens.Error else WatchTokens.Secondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (presentation.action != null) TextButton(if (presentation.action == CloudFeedbackAction.UNDO) onUndo else onRetry,
                Modifier.heightIn(min = WatchTokens.MinimumTouch), enabled = !busy,
                contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(presentation.actionLabel.orEmpty(), style = WatchTypography.Supporting, color = WatchTokens.Primary)
            }
            SmallIcon(com.watchlisten.music.R.drawable.ic_close, "收起操作提示", {
                dismissed = true; onDismiss()
            })
        }
    }
}
