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

package com.android.systemui.island.cluster

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.android.systemui.island.IslandGeometry
import kotlin.math.abs

/**
 * Gesture handling for a single island (§9): tap to expand / run primary action, swipe up to
 * dismiss, swipe down to expand or drag-resize, long press for the context menu.
 */
class IslandTouchHandler(
    private val view: IslandView,
    private val geometry: IslandGeometry,
) {
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val minFlingDp = 24f
    private val maxTapDuration = 300L
    private val longPressTimeout = 500L

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var dragging = false

    private val longPressRunnable = Runnable { onLongPress() }

    fun attach() {
        view.setOnTouchListener { _, ev -> onTouch(ev) }
    }

    private fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startX = ev.x
                startY = ev.y
                downTime = ev.eventTime
                dragging = false
                view.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - startX
                val dy = ev.y - startY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    view.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    view.onDragUpdate(dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPressRunnable)
                val dx = ev.x - downX
                val dy = ev.y - downY
                val duration = ev.eventTime - downTime
                if (!dragging && abs(dx) < touchSlop && abs(dy) < touchSlop &&
                    duration < maxTapDuration
                ) {
                    onTap(ev.x, ev.y)
                } else if (dragging) {
                    view.onDragEnd(dyFrom(ev))
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                view.onDragEnd(0f)
                return true
            }
        }
        return false
    }

    private fun onLongPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!view.isExpanded) view.callbacks?.onUserExpand(view)
    }

    private fun dyFrom(ev: MotionEvent): Float = ev.y - startY

    private fun onTap(x: Float, y: Float) {
        if (view.hasClickableChildAt(x, y)) return
        if (view.isExpanded) {
            view.callbacks?.onUserPrimaryAction(view)
        } else {
            view.callbacks?.onUserExpand(view)
        }
    }
}
