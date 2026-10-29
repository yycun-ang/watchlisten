package com.watchlisten.music.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 挂在整屏容器上，底缘装饰和内容安全边距不应形成上滑空区。 */
@Composable
internal fun Modifier.lyricsSwipe(enabled: Boolean = true, onOpen: () -> Unit): Modifier {
    val open by rememberUpdatedState(onOpen)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var upward = 0f
        detectVerticalDragGestures(
            onDragStart = { upward = 0f },
            onDragCancel = { upward = 0f },
            onVerticalDrag = { change, amount -> upward -= amount; change.consume() },
            onDragEnd = { if (upward >= 24.dp.toPx()) open() }
        )
    }
}
