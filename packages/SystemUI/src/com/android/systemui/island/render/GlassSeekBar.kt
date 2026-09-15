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
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt

// seek bar sized to sit in the transport row
class GlassSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val progressRect = RectF()

    @ColorInt
    var trackColor: Int = 0x40FFFFFF.toInt()

    @ColorInt
    var progressColor: Int = 0xFFFFFFFF.toInt()

    @ColorInt
    var thumbColor: Int = 0xFFFFFFFF.toInt()

    private var trackHeight = 4f
    private var thumbRadius = 6f
    private var dragging = false

    /** true while scrubbing. */
    var isDragging: Boolean = false
        private set

    /** 0 to 1. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Called with the final fraction when the drag ends. */
    var onSeek: ((Float) -> Unit)? = null

    fun setMetrics(trackHeightPx: Float, thumbRadiusPx: Float) {
        trackHeight = trackHeightPx
        thumbRadius = thumbRadiusPx
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (thumbRadius * 3f).toInt()
        setMeasuredDimension(
            resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val half = trackHeight / 2f
        val left = thumbRadius
        val right = (width - thumbRadius).coerceAtLeast(left + 1f)
        val usable = right - left

        trackRect.set(left, cy - half, right, cy + half)
        trackPaint.color = trackColor
        canvas.drawRoundRect(trackRect, half, half, trackPaint)

        val fillRight = left + usable * progress
        progressRect.set(left, cy - half, fillRight, cy + half)
        progressPaint.color = progressColor
        canvas.drawRoundRect(progressRect, half, half, progressPaint)

        val cx = left + usable * progress
        val radius = if (dragging) thumbRadius * 1.35f else thumbRadius
        thumbPaint.color = thumbColor
        canvas.drawCircle(cx, cy, radius, thumbPaint)
    }

    private fun fractionFor(x: Float): Float {
        val left = thumbRadius
        val right = (width - thumbRadius).coerceAtLeast(left + 1f)
        return ((x - left) / (right - left)).coerceIn(0f, 1f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                isDragging = true
                progress = fractionFor(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                progress = fractionFor(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                isDragging = false
                onSeek?.invoke(progress)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
