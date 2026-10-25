/*
 * Copyright (C) 2024 The Android Open Source Project
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
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.qs.composefragment.SceneKeys
import com.android.systemui.qs.shared.ui.QuickSettings.Elements
import com.android.systemui.petalos.PetalQsFadeInEasing

fun TransitionBuilder.quickQuickSettingsToQuickSettings(
    petalEnabled: Boolean = false,
    animateTilesExpansion: () -> Boolean = { true }
) {
    val enteringQs = petalEnabled && transition.toContent == SceneKeys.QuickSettings
    val enteringQqs = petalEnabled && transition.toContent == SceneKeys.QuickQuickSettings
    fractionRange(start = 0.43f, easing = if (enteringQs) PetalQsFadeInEasing else LinearEasing) {
        fade(Elements.QuickSettingsContent)
    }

    fractionRange(start = 0.9f, easing = if (enteringQs) PetalQsFadeInEasing else LinearEasing) {
        fade(Elements.FooterActions)
    }

    anchoredTranslate(Elements.QuickSettingsContent, Elements.GridAnchor)

    sharedElement(Elements.TileElementMatcher, enabled = animateTilesExpansion())
    sharedElement(Elements.BrightnessSlider)

    // Media bounds differ between hosts; sharing made the bloody player fly about.
    sharedElement(Media.Elements.mediaCarousel, enabled = false)

    // Keep the faint overlap instead of flashing an empty shade.
    fractionRange(end = 0.5f, easing = if (enteringQqs) PetalQsFadeInEasing else LinearEasing) {
        fade(SceneKeys.QqsTileElementMatcher)
    }
    anchoredTranslate(SceneKeys.QqsTileElementMatcher, Elements.GridAnchor)
}
