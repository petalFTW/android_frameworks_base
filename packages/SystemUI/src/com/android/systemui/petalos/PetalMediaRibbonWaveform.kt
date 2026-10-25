/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.sin

class PetalMediaRibbonWaveform(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val heights = floatArrayOf(0.28f, 0.62f, 0.88f, 0.48f, 1f, 0.68f, 0.42f, 0.22f)
    private var motionRunning = false
    private val motion by lazy {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                applyGlow(it.animatedValue as Float)
                invalidate()
            }
        }
    }
    var playing = false
        set(value) {
            if (field == value) return
            field = value
            updateMotion()
            invalidate()
        }
    var listening = false
        set(value) {
            if (field == value) return
            field = value
            updateMotion()
        }

    private fun applyGlow(value: Float) {
        ((parent as? View)?.background as? PetalMediaRibbonDrawable)?.setPlaybackGlow(value)
    }

    private fun updateMotion() {
        val active = playing && listening && isAttachedToWindow && isShown &&
            windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        if (active && !motionRunning) {
            motionRunning = true
            motion.start()
        } else if (!active) {
            stopMotion()
        }
    }

    private fun stopMotion() {
        if (motionRunning) {
            motionRunning = false
            motion.cancel()
            invalidate()
        }
        applyGlow(0f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateMotion()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMotion()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val bar = 1.5f * density
        val gap = 2.5f * density
        val total = heights.size * bar + (heights.size - 1) * gap
        val left = (width - total) / 2f
        val time = SystemClock.uptimeMillis() / 1000.0
        for (i in heights.indices) {
            val motion = if (motionRunning) 0.65f + 0.35f * sin(time * (3f + i * 0.3f) + i).toFloat() else 1f
            val h = minOf(height.toFloat(), 28f * density) * heights[i] * motion
            val x = left + i * (bar + gap)
            canvas.drawRoundRect(x, (height - h) / 2f, x + bar, (height + h) / 2f,
                bar / 2f, bar / 2f, paint)
        }
    }
}
