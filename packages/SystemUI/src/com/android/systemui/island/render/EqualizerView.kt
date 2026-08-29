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
import android.view.Choreographer
import android.view.View
import androidx.annotation.ColorInt
import com.android.systemui.island.IslandConstants

/**
 * Animated equalizer bars. 3 bars in the collapsed capsule, 6 in the expanded card. Height is
 * driven by independent sine waves from a single [Choreographer.FrameCallback] shared by all bars.
 * The callback is unregistered whenever the view is not visible.
 */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()

    private var barColor = 0xFFFFFFFF.toInt()
    private var barWidthPx = 0f
    private var gapPx = 0f
    private var maxHeightPx = 0f
    private var running = false
    private var t = 0f
    private var lastFrameNanos = 0L

    var barCount: Int = 3
        set(value) {
            field = value.coerceIn(1, IslandConstants.BAR_OMEGA.size)
            requestLayout()
        }

    /** When false, bars freeze at 30% height. */
    var active: Boolean = true

    fun setBarColor(@ColorInt color: Int) {
        barColor = color
        invalidate()
    }

    fun setMetrics(barWidthPx: Float, gapPx: Float, maxHeightPx: Float) {
        this.barWidthPx = barWidthPx
        this.gapPx = gapPx
        this.maxHeightPx = maxHeightPx
        requestLayout()
    }

    fun start() {
        if (running || !isAttachedToWindow) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !isAttachedToWindow) return
        if (lastFrameNanos != 0L) {
            val dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
            t += dt
        }
        lastFrameNanos = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (barWidthPx * barCount + gapPx * (barCount - 1)).toInt()
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), maxHeightPx.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (barWidthPx <= 0 || maxHeightPx <= 0) return
        paint.color = barColor
        var x = 0f
        for (i in 0 until barCount) {
            val omega = IslandConstants.BAR_OMEGA[i]
            val phi = i * IslandConstants.BAR_PHASE_SEED
            val h =
                if (active) {
                    maxHeightPx *
                        (0.25f + 0.75f * (0.5f + 0.5f * kotlin.math.sin(t * omega + phi)))
                } else {
                    maxHeightPx * 0.3f
                }
            val radius = barWidthPx / 2f
            barRect.set(x, height - h, x + barWidthPx, height.toFloat())
            canvas.drawRoundRect(barRect, radius, radius, paint)
            x += barWidthPx + gapPx
        }
    }
}
