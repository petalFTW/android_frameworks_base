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

package com.android.systemui.petalos

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import java.io.PrintWriter
import javax.inject.Inject
import org.petalos.config.PetalConfig

/**
 * Applies the petalOS lock screen clock style.
 *
 * Styles are a catalog of font-axis presets (weight / width / roundness / slant) plus an optional
 * accent seed colour, delivered through the stock clock-customisation pipeline
 * ([Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE]) so the existing [com.android.systemui.shared.clocks.ClockRegistry]
 * renders them without any direct coupling to the keyguard views.
 */
@SysUISingleton
class PetalClockStyleController @Inject constructor(
    @Application private val context: Context,
) : CoreStartable {

    private val handler = Handler(Looper.getMainLooper())
    private var appliedStyle = -1

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = applyStyle(PetalConfig.getLockClockStyle(context))
    }

    override fun start() {
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_LOCK_CLOCK_STYLE),
            false,
            settingsObserver,
        )
        applyStyle(PetalConfig.getLockClockStyle(context))
    }

    private fun applyStyle(style: Int) {
        if (style == appliedStyle) return
        appliedStyle = style

        val resolver = context.contentResolver
        if (style <= 0) {
            // Empty string clears the custom clock face, restoring the stock clock.
            Settings.Secure.putString(resolver, Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE, "")
            return
        }

        val axes = ClockAxisStyle()
        var seedColor: Int? = null
        // The style index is carried through the stock clock-customisation pipeline
        // (LOCK_SCREEN_CUSTOM_CLOCK_FACE) and consumed by DefaultClockController, which maps it to a
        // distinct typeface / layout. The accent style additionally tints the clock.
        axes.put(STYLE_AXIS, style.toFloat())
        if (style == STYLE_NEON) {
            seedColor = context.getColor(android.R.color.system_accent1_500)
        }

        val settings = ClockSettings(clockId = null, seedColor = seedColor, axes = axes)
        Settings.Secure.putString(
            resolver,
            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
            ClockSettings.toJson(settings).toString(),
        )
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("PetalClockStyleController: style=$appliedStyle")
    }

    companion object {
        const val STYLE_AXIS = "petal_style"
        const val STYLE_NEON = 11
    }
}
