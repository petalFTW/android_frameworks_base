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

package com.android.systemui.island

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

/**
 * Owns the WindowManager window that hosts both island clusters (§12.1).
 */
class IslandWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val geometry: IslandGeometry,
) {
    val rootView = IslandRootView(context, geometry)

    private var added = false

    fun addToWindow() {
        if (added) return
        try {
            windowManager.addView(rootView, buildLayoutParams())
            added = true
        } catch (e: RuntimeException) {
            // Window type conflict or permission issue; island is non-fatal.
            android.util.Log.w(TAG, "Failed to add island window", e)
        }
    }

    fun removeFromWindow() {
        if (!added) return
        try {
            windowManager.removeView(rootView)
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "Failed to remove island window", e)
        }
        added = false
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val height = geometry.statusBarTopInset + geometry.dp(220f)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTrustedOverlay()
            title = "IslandWindow"
            packageName = context.packageName
            // NOTE: backdrop blur (FLAG_BLUR_BEHIND) is intentionally NOT set here. It blurs the
            // entire full-width window rather than just the island, and the liquid-glass tint is
            // opaque enough that blur-behind is imperceptible through it.
        }
    }

    companion object {
        private const val TAG = "IslandWindow"
    }
}
