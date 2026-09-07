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

/**
 * Derives the three-tone island tint from album artwork (§5.3).
 */
object PaletteTinter {
    private const val TINT_BG_ALPHA = 0xE0
    private const val FALLBACK_RGB = 0xFF303034.toInt()

    /** Extract the tint from an album-art bitmap. Call off the main thread. */
    fun extract(bitmap: Bitmap): IslandTint {
        val swatch = Palette.from(bitmap).maximumColorCount(16).generate().let {
            it.vibrantSwatch ?: it.darkVibrantSwatch ?: it.mutedSwatch
        }
        val rgb = swatch?.rgb ?: FALLBACK_RGB

        // tintBg: blend the swatch 82% toward black (blend BEFORE applying alpha).
        val bg = ColorUtils.blendARGB(rgb, Color.BLACK, 0.82f)
        val background = Color.argb(TINT_BG_ALPHA, Color.red(bg), Color.green(bg), Color.blue(bg))

        // tintAccent: clamp lightness into [0.55, 0.72], raise saturation to min(1, s * 1.25).
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = (hsl[1] * 1.25f).coerceAtMost(1f)
        hsl[2] = hsl[2].coerceIn(0.55f, 0.72f)
        val accent = ColorUtils.HSLToColor(hsl)

        // tintOnBg: white if it meets 4.5:1 contrast against the tinted background, else dark.
        // calculateContrast rejects translucent backgrounds, so composite the tinted background
        // over the island's black base first.
        val opaqueBackground = ColorUtils.compositeColors(background, Color.BLACK)
        val onBackground =
            if (ColorUtils.calculateContrast(Color.WHITE, opaqueBackground) >= 4.5f) {
                Color.WHITE
            } else {
                0xFF101012.toInt()
            }

        return IslandTint(accent, onBackground, background)
    }
}
