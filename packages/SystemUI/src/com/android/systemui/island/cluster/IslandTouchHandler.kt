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
import android.view.VelocityTracker
import android.view.ViewConfiguration
import com.android.systemui.island.IslandGeometry
import kotlin.math.abs

/**
 * Gesture handling for a single island: tap to expand / run primary action, tap a button to invoke
 * it, swipe up to dismiss, swipe down to expand or drag-resize, swipe left/right to dismiss with
 * rubber-band feedback, long press for the context menu.
 *
 * Because the island is a [ViewGroup], the gestures are wired through
 * [android.view.ViewGroup.onInterceptTouchEvent] / [android.view.ViewGroup.onTouchEvent] rather
 * than a plain [android.view.View.OnTouchListener] (which [ViewGroup] never consults). A drag is
 * intercepted from children once the touch slop is exceeded; taps are left to children so buttons
 * (action pills, transport controls) still receive their click events.
 */
class IslandTouchHandler(
    private val view: IslandView,
    private val geometry: IslandGeometry,
) {
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val longPressTimeout = 500L

    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var longPressed = false

    /**
     * False when [onTouchEvent] hasn't seen ACTION_DOWN yet — this happens right after
     * [onInterceptTouchEvent] starts intercepting mid-gesture (the framework sends MOVE/CANCEL
     * without a fresh DOWN). The first MOVE then seeds the gesture state instead of computing
     * drag deltas against stale coordinates.
     */
    private var gotDown = false

    /** Once the drag axis is locked, this records whether it's horizontal. */
    private var horizontalAxis = false

    private var velocityTracker: VelocityTracker? = null

    private val longPressRunnable = Runnable { onLongPress() }

    /** Called from [IslandView.onInterceptTouchEvent]; intercepts once a drag is detected. */
    fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startX = ev.x
                startY = ev.y
                dragging = false
                longPressed = false
                horizontalAxis = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragging = true
                        horizontalAxis = abs(dx) > abs(dy)
                        view.removeCallbacks(longPressRunnable)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }
        return dragging
    }

    /** Called from [IslandView.onTouchEvent]; handles taps and applies drags. */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startX = ev.x
                startY = ev.y
                dragging = false
                gotDown = true
                longPressed = false
                horizontalAxis = false
                view.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gotDown) {
                    // First event after mid-gesture interception: seed the gesture state.
                    downX = ev.x
                    downY = ev.y
                    startX = ev.x
                    startY = ev.y
                    gotDown = true
                }
                if (!dragging) {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragging = true
                        horizontalAxis = abs(dx) > abs(dy)
                        view.removeCallbacks(longPressRunnable)
                    }
                }
                if (dragging) {
                    if (horizontalAxis) {
                        view.onHorizontalDragUpdate(ev.x - startX)
                    } else {
                        view.onDragUpdate(ev.y - startY)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPressRunnable)
                val dx = ev.x - downX
                val dy = ev.y - downY
                // Any release that isn't a drag and didn't already trigger a long-press is a
                // tap, regardless of how long the finger was down. Capping taps at the framework's
                // short tap-timeout dropped normal (slightly slow) presses, so action pills and
                // the inline-reply OK button would intermittently do nothing.
                if (!dragging && !longPressed && gotDown && abs(dx) < touchSlop &&
                    abs(dy) < touchSlop
                ) {
                    onTap(ev.x, ev.y)
                } else if (dragging) {
                    if (horizontalAxis) {
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vx = velocityTracker?.xVelocity ?: 0f
                        view.onHorizontalDragEnd(ev.x - startX, vx)
                    } else {
                        view.onDragEnd(ev.y - startY)
                    }
                }
                recycleVelocityTracker()
                dragging = false
                gotDown = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                if (dragging && horizontalAxis) {
                    view.onHorizontalDragEnd(0f, 0f)
                } else if (dragging) {
                    view.onDragEnd(0f)
                }
                recycleVelocityTracker()
                dragging = false
                gotDown = false
                return true
            }
        }
        return false
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun onLongPress() {
        longPressed = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!view.isExpanded) view.callbacks?.onUserExpand(view)
    }

    private fun onTap(x: Float, y: Float) {
        val hitChild = view.performClickableChildAt(x, y)
        android.util.Log.d(
            "IslandTouch",
            "onTap x=$x y=$y expanded=${view.isExpanded} hitChild=$hitChild",
        )
        if (hitChild) return
        if (view.isExpanded) {
            view.callbacks?.onUserPrimaryAction(view)
        } else {
            view.callbacks?.onUserExpand(view)
        }
    }
}
