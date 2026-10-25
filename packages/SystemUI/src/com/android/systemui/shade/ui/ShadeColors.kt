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

package com.android.systemui.shade.ui

import android.content.Context
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {
    // shade panel colour, blur fallback handled inside
    @JvmStatic
    fun shadePanel(context: Context, blurSupported: Boolean, withScrim: Boolean): Int {
        return if (blurSupported) frostPanelColor(context) else neutralPanelBase(context)
    }

    private fun neutralPanelBase(context: Context): Int =
        if (com.android.systemui.petalos.PetalQsSkin.isDark(context)) 0xFF0A0B10.toInt()
        else 0xFFF6F6FA.toInt()

    /** A single achromatic veil over the shared QS and notification blur. */
    @JvmStatic
    fun frostPanelColor(context: Context): Int =
        ColorUtils.setAlphaComponent(neutralPanelBase(context),
            if (com.android.systemui.petalos.PetalQsSkin.isDark(context)) 0x80 else 0xB0)

    /**
     * Panel color for surfaces that share their backdrop with the bouncer: always the stock
     * palette, so the frost never turns the lock screen bouncer (which uses light-on-dark themed
     * text) unreadable.
     */
    @JvmStatic
    fun bouncerSurfacePanel(context: Context, blurSupported: Boolean): Int =
        shadePanelStock(context, blurSupported, withScrim = true)

    private fun shadePanelStock(context: Context, blurSupported: Boolean, withScrim: Boolean): Int {
        return if (blurSupported) {
            if (withScrim) {
                ColorUtils.compositeColors(
                    shadePanelStandard(context),
                    shadePanelScrimBehind(context),
                )
            } else {
                shadePanelStandard(context)
            }
        } else {
            shadePanelFallback(context)
        }
    }

    @JvmStatic
    fun notificationScrim(context: Context, blurSupported: Boolean): Int {
        // The full-screen behind scrim already provides contrast for both regions.
        return android.graphics.Color.TRANSPARENT
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int {
        return context.resources.getColor(
            com.android.internal.R.color.shade_panel_scrim,
            context.theme,
        )
    }

    @JvmStatic
    private fun shadePanelStandard(context: Context): Int {
        val layerAbove =
            context.resources.getColor(com.android.internal.R.color.shade_panel_fg, context.theme)
        val layerBelow =
            context.resources.getColor(com.android.internal.R.color.shade_panel_bg, context.theme)
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun shadePanelFallback(context: Context): Int {
        return context.getColor(R.color.shade_panel_fallback)
    }

}
