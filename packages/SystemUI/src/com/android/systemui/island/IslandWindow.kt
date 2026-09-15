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
import com.android.systemui.island.render.IslandMiniNotifSource

// the window that holds both islands
class IslandWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val geometry: IslandGeometry,
    miniNotifSource: IslandMiniNotifSource? = null,
) {
    val rootView = IslandRootView(context, geometry, miniNotifSource)

    private var layoutParams = buildLayoutParams()
    private var added = false

    fun addToWindow() {
        if (added) return
        try {
            windowManager.addView(rootView, layoutParams)
            added = true
        } catch (e: RuntimeException) {
            // bad type or missing permission. log it and move on
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
        layoutParams = buildLayoutParams()
    }

    // flip focus so the reply field can pull up the ime
    fun setFocusable(focusable: Boolean) {
        val isFocusable = layoutParams.flags and
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        if (isFocusable == focusable) return
        layoutParams = layoutParams.apply {
            flags = if (focusable) {
                flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
        }
        if (added) {
            try {
                windowManager.updateViewLayout(rootView, layoutParams)
            } catch (e: RuntimeException) {
                android.util.Log.w(TAG, "Failed to update island window focus", e)
            }
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        // full height, so outside taps reach ACTION_OUTSIDE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTrustedOverlay()
            title = "IslandWindow"
            packageName = context.packageName
            // no blur: the window is full width, it would frost everything
        }
    }

    companion object {
        private const val TAG = "IslandWindow"
    }
}
