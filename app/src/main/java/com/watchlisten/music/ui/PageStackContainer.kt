package com.watchlisten.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 页面栈只负责内容与转场；左边缘返回由宿主 Activity 统一处理。
 */
@Composable
fun PageStackContainer(depth: Int, animateTransitions: Boolean,
                       content: @Composable () -> Unit) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(depth, animateTransitions) {
        if (animateTransitions && depth > 1) {
            scale.snapTo(.97f)
            scale.animateTo(1f, tween(220))
        } else scale.snapTo(1f)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
            content()
        }
    }
}
