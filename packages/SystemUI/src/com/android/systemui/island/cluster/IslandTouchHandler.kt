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

// gestures for the island. tapped into intercept and touch events
class IslandTouchHandler(
    private val view: IslandView,
    private val geometry: IslandGeometry,
) {
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var gotDown = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var tapEligible = false
    private var dragUpdatedThisEvent = false

    // set once we pick an axis, doesn't change mid drag
    private var horizontalAxis = false

    private var velocityTracker: VelocityTracker? = null

    private val longPressRunnable = Runnable { onLongPress() }

    // Record once, before intercept and touch can both see the same event.
    fun onDispatchTouchEvent(ev: MotionEvent) {
        dragUpdatedThisEvent = false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            activePointerId = ev.getPointerId(0)
            downX = ev.getRawX(0)
            downY = ev.getRawY(0)
            gotDown = true
            tapEligible = true
            velocityTracker = VelocityTracker.obtain()
            view.callbacks?.onUserTouchChanged(view, true)
        }
        if (!gotDown) return
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            tapEligible = false
            view.removeCallbacks(longPressRunnable)
        }
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP &&
            ev.getPointerId(ev.actionIndex) == activePointerId) {
            val replacement = if (ev.actionIndex == 0) 1 else 0
            if (replacement >= ev.pointerCount) {
                cancelDrag()
                cancel()
                return
            }
            // Keep displacement when the active finger lifts, not its absolute position.
            downX = ev.getRawX(replacement) - (ev.getRawX(ev.actionIndex) - downX)
            downY = ev.getRawY(replacement) - (ev.getRawY(ev.actionIndex) - downY)
            activePointerId = ev.getPointerId(replacement)
            tapEligible = false
            velocityTracker?.clear()
        }
        val rawEvent = MotionEvent.obtain(ev)
        rawEvent.offsetLocation(ev.getRawX(0) - ev.getX(0), ev.getRawY(0) - ev.getY(0))
        velocityTracker?.addMovement(rawEvent)
        rawEvent.recycle()
    }

    fun onDispatchTouchEventFinished(ev: MotionEvent) {
        // Interception can spend the whole MOVE cancelling a child button.
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && dragging && !dragUpdatedThisEvent) {
            updateDrag(ev)
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancel()
        }
    }

    // Local coordinates move with the island. Using them here made it bounce between two spots.
    fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_MOVE) startDragIfNeeded(ev)
        return dragging
    }

    private fun startDragIfNeeded(ev: MotionEvent) {
        if (dragging || !gotDown) return
        val index = ev.findPointerIndex(activePointerId)
        if (index < 0) return
        val dx = ev.getRawX(index) - downX
        val dy = ev.getRawY(index) - downY
        if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) return
        tapEligible = false
        view.removeCallbacks(longPressRunnable)
        // A bit sideways is still down, don't chuck the bloody blob away.
        val downward = dy > touchSlop && dy >= abs(dx) * 0.5f
        val vertical = downward || abs(dy) >= abs(dx)
        val horizontal = abs(dx) > touchSlop * 2f &&
            (dy <= 0f || abs(dx) > abs(dy) * 2f)
        if (!vertical && !horizontal) return
        horizontalAxis = !vertical
        if (!view.onGestureDragStart(horizontalAxis)) return
        dragging = true
        view.parent?.requestDisallowInterceptTouchEvent(true)
    }

    // taps and drags get handled here
    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!gotDown) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                startDragIfNeeded(ev)
                updateDrag(ev)
                return true
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPressRunnable)
                // A quick flick can dump its last bit of travel into UP.
                startDragIfNeeded(ev)
                val index = ev.findPointerIndex(activePointerId)
                if (index < 0) {
                    cancelDrag()
                    return true
                }
                val dx = ev.getRawX(index) - downX
                val dy = ev.getRawY(index) - downY
                // no timeout on taps, any quick release counts
                if (!dragging && !longPressed && tapEligible && abs(dx) < touchSlop &&
                    abs(dy) < touchSlop
                ) {
                    onTap(ev.getX(index), ev.getY(index))
                } else if (dragging) {
                    updateDrag(ev)
                    if (horizontalAxis) {
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vx = velocityTracker?.getXVelocity(activePointerId) ?: 0f
                        view.onHorizontalDragEnd(dx, vx)
                    } else {
                        view.onDragEnd(dy)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelDrag()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return true
        }
        return false
    }

    private fun cancelDrag() {
        if (dragging && horizontalAxis) view.onHorizontalDragEnd(0f, 0f)
        else if (dragging) view.onDragEnd(0f)
    }

    private fun updateDrag(ev: MotionEvent) {
        val index = ev.findPointerIndex(activePointerId)
        if (index < 0) {
            cancelDrag()
            cancel()
            return
        }
        if (!dragging) return
        dragUpdatedThisEvent = true
        if (horizontalAxis) {
            view.onHorizontalDragUpdate(ev.getRawX(index) - downX)
        } else {
            view.onDragUpdate(ev.getRawY(index) - downY)
        }
    }

    fun cancel() {
        val hadTouch = gotDown
        view.removeCallbacks(longPressRunnable)
        velocityTracker?.recycle()
        velocityTracker = null
        if (dragging) view.parent?.requestDisallowInterceptTouchEvent(false)
        dragging = false
        gotDown = false
        longPressed = false
        horizontalAxis = false
        tapEligible = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (hadTouch) view.callbacks?.onUserTouchChanged(view, false)
    }

    private fun onLongPress() {
        if (!gotDown || dragging || longPressed || !tapEligible) return
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
