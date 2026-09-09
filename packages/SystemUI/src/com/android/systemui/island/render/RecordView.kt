/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.island.render

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

class RecordView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groove = RectF()
    private var accent = 0xFFFFFFFF.toInt()
    private var angle = 0f
    private var visibleToUser = false
    private var animator: ValueAnimator? = null

    var active = false
        set(value) {
            field = value
            updateAnimation()
        }

    fun setRecordColor(color: Int) {
        accent = color
        invalidate()
    }

    fun stop() {
        active = false
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        visibleToUser = false
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (!active || !visibleToUser || !isAttachedToWindow ||
            !ValueAnimator.areAnimatorsEnabled()) {
            animator?.cancel()
            animator = null
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(angle, angle + 360f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = (it.animatedValue as Float) % 360f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf(width, height) * 0.48f
        if (radius <= 0f) return
        val saved = canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(angle)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF252529.toInt()
        canvas.drawCircle(0f, 0f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * 0.7f
        paint.color = 0xFF85858D.toInt()
        canvas.drawCircle(0f, 0f, radius * 0.94f, paint)
        for (fraction in GROOVES) {
            val r = radius * fraction
            groove.set(-r, -r, r, r)
            canvas.drawArc(groove, 25f, 95f, false, paint)
            canvas.drawArc(groove, 205f, 95f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = accent
        canvas.drawCircle(0f, 0f, radius * 0.38f, paint)
        paint.color = 0xFF101012.toInt()
        canvas.drawCircle(0f, 0f, radius * 0.10f, paint)
        canvas.drawCircle(radius * 0.22f, 0f, radius * 0.065f, paint)
        canvas.restoreToCount(saved)
    }

    companion object {
        private val GROOVES = floatArrayOf(0.57f, 0.72f, 0.85f)
    }
}
