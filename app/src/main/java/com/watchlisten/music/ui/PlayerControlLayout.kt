/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from google/horologist ControlButtonLayout.kt (release-0.7.x).
 * Modified for this application's standard Compose / API 30 stack:
 * compact 52dp middle slot, centered 48dp side actions, no Wear dependency.
 */
package com.watchlisten.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerControlLayout(
    leftButton: @Composable () -> Unit,
    middleButton: @Composable () -> Unit,
    rightButton: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) { leftButton() }
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { middleButton() }
        Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) { rightButton() }
    }
}
