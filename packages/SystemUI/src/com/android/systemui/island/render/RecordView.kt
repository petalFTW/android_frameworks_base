/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.island.render

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// vinyl and tonearm, spins while playing
class RecordView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groove = RectF()
    private var accent = 0xFFFFFFFF.toInt()
    private var angle = 0f
    private var needle = 0f
    private var spinning = false
    private var visibleToUser = false
    private var spinAnim: ValueAnimator? = null
    private var needleAnim: ValueAnimator? = null
    private val density = resources.displayMetrics.density

    var active = false
        set(value) {
            if (field == value) return
            field = value
            animateNeedle()
            updateSpin()
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
        if (!isVisible) {
            cancelAnims()
            needle = if (active) 1f else 0f
            invalidate()
        } else {
            animateNeedle()
            updateSpin()
        }
    }

    override fun onDetachedFromWindow() {
        visibleToUser = false
        cancelAnims()
        super.onDetachedFromWindow()
    }

    private fun cancelAnims() {
        needleAnim?.cancel()
        needleAnim = null
        spinAnim?.cancel()
        spinAnim = null
        spinning = false
    }

    private fun canAnimate(): Boolean =
        visibleToUser && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    private fun animateNeedle() {
        needleAnim?.cancel()
        needleAnim = null
        val target = if (active) 1f else 0f
        if (needle == target) return
        if (!canAnimate()) {
            needle = target
            invalidate()
            return
        }
        needleAnim = ValueAnimator.ofFloat(needle, target).apply {
            duration = if (active) DROP_MS else LIFT_MS
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                needle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateSpin() {
        val wasSpinning = spinning
        spinAnim?.cancel()
        spinAnim = null
        spinning = false
        if (!canAnimate()) return
        if (active) {
            // wait for the needle to land first
            spinAnim = ValueAnimator.ofFloat(angle, angle + 360f).apply {
                startDelay = if (needle < 0.95f) DROP_MS else 0L
                duration = SPIN_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    angle = (it.animatedValue as Float) % 360f
                    invalidate()
                }
                start()
            }
            spinning = true
        } else if (wasSpinning) {
            // stopping, let it coast
            spinAnim = ValueAnimator.ofFloat(angle, angle + 120f).apply {
                duration = COAST_MS
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    angle = (it.animatedValue as Float) % 360f
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = minOf(width, height).toFloat()
        if (m <= 0f) return
        val cx = width * 0.46f
        val cy = height * 0.54f
        val radius = m * 0.36f
        drawDisc(canvas, cx, cy, radius)
        drawTonearm(canvas, m, cx, cy, radius)
    }

    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val saved = canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF252529.toInt()
        canvas.drawCircle(0f, 0f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * 0.7f
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

    private fun drawTonearm(canvas: Canvas, m: Float, cx: Float, cy: Float, radius: Float) {
        val lift = 1f - needle
        val px = width * 0.88f
        val py = height * 0.14f
        val armLen = radius * 1.42f
        val sweep = SWEEP_UP + (SWEEP_DOWN - SWEEP_UP) * needle
        val dir = atan2((cy - py).toDouble(), (cx - px).toDouble()) -
            Math.toRadians(sweep.toDouble())
        val tipX = px + (cos(dir) * armLen).toFloat()
        val tipY = py + (sin(dir) * armLen).toFloat()
        val hover = lift * m * 0.035f

        // hovering arm gets a shadow
        if (lift > 0.02f) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = maxOf(armLen * 0.05f, density * 2f)
            paint.color = Color.argb((70 * lift).toInt(), 0, 0, 0)
            canvas.drawLine(px, py + hover * 2f, tipX, tipY + hover * 2f, paint)
        }

        canvas.save()
        canvas.translate(0f, -hover)

        val bwX = px - (cos(dir) * armLen * 0.18f).toFloat()
        val bwY = py - (sin(dir) * armLen * 0.18f).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = maxOf(armLen * 0.10f, density * 2.6f)
        paint.color = 0xFF5A5A62.toInt()
        canvas.drawLine(px, py, bwX, bwY, paint)

        paint.strokeWidth = maxOf(armLen * 0.055f, density * 2f)
        paint.color = 0xFFE8E8EC.toInt()
        canvas.drawLine(px, py, tipX, tipY, paint)

        paint.style = Paint.Style.FILL
        paint.color = 0xFF3A3A40.toInt()
        canvas.drawCircle(px, py, maxOf(radius * 0.17f, density * 2.6f), paint)
        paint.color = 0xFFC9C9CF.toInt()
        canvas.drawCircle(px, py, maxOf(radius * 0.07f, density * 1.3f), paint)

        paint.color = if (needle > 0.5f) accent else 0xFFC9C9CF.toInt()
        canvas.drawCircle(tipX, tipY, maxOf(radius * 0.11f, density * 1.7f), paint)
        canvas.restore()
    }

    companion object {
        private val GROOVES = floatArrayOf(0.57f, 0.72f, 0.85f)
        private const val SWEEP_UP = 55f
        private const val SWEEP_DOWN = 27f
        private const val SPIN_MS = 2400L
        private const val DROP_MS = 240L
        private const val LIFT_MS = 340L
        private const val COAST_MS = 750L
    }
}
