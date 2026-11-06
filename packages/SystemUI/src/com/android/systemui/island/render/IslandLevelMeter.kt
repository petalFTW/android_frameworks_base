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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

// tall pill you drag up and down, one notch per level
class IslandLevelMeter(
    context: Context,
    private val maxLevel: Int,
    @ColorInt private val accent: Int,
    @ColorInt private val trackColor: Int,
    @ColorInt private val notchColor: Int,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()

    var level: Int = 1
        set(value) {
            val clamped = value.coerceIn(1, maxLevel)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    // every level change, not just the last one
    var onLevelChanged: ((Int) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.color = trackColor
        canvas.drawRect(0f, 0f, w, h, paint)
        val fraction = level.toFloat() / maxLevel
        paint.color = accent
        canvas.drawRect(0f, h * (1f - fraction), w, h, paint)
        // too many levels and the notches are just noise
        if (maxLevel in 2..MAX_NOTCHES) {
            paint.color = notchColor
            val notch = resources.displayMetrics.density
            for (i in 1 until maxLevel) {
                val y = h * i / maxLevel
                canvas.drawRect(0f, y - notch / 2f, w, y + notch / 2f, paint)
            }
        }
        canvas.restore()
    }

    private fun levelAt(y: Float): Int {
        val fraction = (1f - y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        return (fraction * maxLevel).roundToInt().coerceIn(1, maxLevel)
    }

    private fun track(y: Float) {
        val next = levelAt(y)
        if (next == level) return
        level = next
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onLevelChanged?.invoke(next)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // the island wants vertical drags for itself, this one's ours
                parent?.requestDisallowInterceptTouchEvent(true)
                track(event.y)
            }
            MotionEvent.ACTION_MOVE -> track(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private companion object {
        const val MAX_NOTCHES = 16
    }
}
