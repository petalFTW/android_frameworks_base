/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.composefragment.ui

import androidx.compose.animation.core.LinearEasing
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.qs.composefragment.SceneKeys
import com.android.systemui.petalos.PetalQsFadeInEasing

fun TransitionBuilder.toEditMode(petalEnabled: Boolean = false) {
    val entering = petalEnabled && transition.toContent == SceneKeys.EditMode
    fractionRange(start = 0.5f, easing = if (entering) PetalQsFadeInEasing else LinearEasing) {
        fade(SceneKeys.EditMode.rootElementKey)
    }
    fractionRange(end = 0.5f,
        easing = if (petalEnabled && !entering) PetalQsFadeInEasing else LinearEasing) {
        fade(SceneKeys.QuickQuickSettings.rootElementKey)
        fade(SceneKeys.QuickSettings.rootElementKey)
    }
}
