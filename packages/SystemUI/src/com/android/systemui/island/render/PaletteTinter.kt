/*
 * Copyright (C) 2026 The petalOS Project
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

package com.android.systemui.island.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.android.systemui.island.presenter.IslandTint

// pulls a tint out of the album art
object PaletteTinter {
    // glass, not paint: keep this low so the backdrop blur reads through the media island
    private const val TINT_BG_ALPHA = 0xA8
    private const val FALLBACK_RGB_DARK = 0xFF303034.toInt()
    private const val FALLBACK_RGB_LIGHT = 0xFFE8E8EC.toInt()

    /** Pull an IslandTint from album art. Not for the main thread. */
    fun extract(bitmap: Bitmap, dark: Boolean = true): IslandTint {
        val swatch = Palette.from(bitmap).maximumColorCount(16).generate().let {
            it.vibrantSwatch ?: it.darkVibrantSwatch ?: it.mutedSwatch
        }
        val rgb = swatch?.rgb ?: if (dark) FALLBACK_RGB_DARK else FALLBACK_RGB_LIGHT

        // background: blend toward black on a dark theme, toward the light surface on a
        // light theme, so the media island matches the rest of the UI
        val base = if (dark) Color.BLACK else Color.WHITE
        val bg = ColorUtils.blendARGB(rgb, base, 0.82f)
        val background = Color.argb(TINT_BG_ALPHA, Color.red(bg), Color.green(bg), Color.blue(bg))

        // accent: clamp lightness and bump saturation. darker on light so it stays legible
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = (hsl[1] * 1.25f).coerceAtMost(1f)
        hsl[2] = if (dark) hsl[2].coerceIn(0.55f, 0.72f) else hsl[2].coerceIn(0.30f, 0.48f)
        val accent = ColorUtils.HSLToColor(hsl)

        // composite over the matching base or the contrast math breaks
        val opaqueBackground = ColorUtils.compositeColors(background, base)
        val onBackground =
            if (ColorUtils.calculateContrast(Color.WHITE, opaqueBackground) >= 4.5f) {
                Color.WHITE
            } else {
                0xFF101012.toInt()
            }

        return IslandTint(accent, onBackground, background)
    }
}
