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

package com.android.systemui.petalos.depth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import com.android.systemui.res.R
import java.util.Calendar
import java.util.Locale

// used by both the preview and the real lockscreen
class PetalDepthClockView(context: Context) : View(context) {

    companion object {
        const val STYLE_SOLID = 1
        const val STYLE_OUTLINE = 2
        const val STYLE_GRADIENT = 3
        const val STYLE_HYBRID = 4
        const val STYLE_BLOCKBUSTER = 5
        const val STYLE_NEON = 6
        const val STYLE_GOLD = 7
        const val STYLE_VOGUE = 8
        const val STYLE_MONOLITH = 9

        // clock trails the subject on purpose
        const val PARALLAX_RATE = 0.45f
        private const val TICK_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val calendar = Calendar.getInstance()

    private val materials = org.petalos.config.DepthClockMaterials()
    private var backdrop: android.graphics.Bitmap? = null

    private var liquidBackdrop: android.graphics.Bitmap? = null

    fun setLiquidBackdrop(bitmap: android.graphics.Bitmap?) {
        liquidBackdrop = bitmap
        invalidate()
    }

    fun setBackdrop(bitmap: android.graphics.Bitmap?) {
        backdrop = bitmap
        invalidate()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var style = STYLE_SOLID
    private var vertical = false
    private var accentColor = Color.TRANSPARENT
    private var showDate = true
    private var twentyFourHour = true

    // vertical position, clamped 0.05-0.85
    private var anchor = 0.20f
    // horizontal center, clamped to 0.1-0.9
    private var anchorX = 0.5f
    // digit scale, clamped 0.6-1.6
    private var scale = 1f

    private var hourText = ""
    private var minuteText = ""
    private var dateText = ""

    private var tiltX = 0f
    private var tiltY = 0f

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        id = R.id.petal_depth_clock
        elevation = 90f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        twentyFourHour = DateFormat.is24HourFormat(context)
        applyTypeface(style)
    }

    // every style gets its own font
    private fun applyTypeface(forStyle: Int) {
        val face =
            when (forStyle) {
                in org.petalos.config.DepthClockMaterials.HEAVY..org.petalos.config.DepthClockMaterials.LAST ->
                    org.petalos.config.DepthClockMaterials.typeface(forStyle)
                STYLE_BLOCKBUSTER, STYLE_MONOLITH ->
                    Typeface.create("sans-serif-black", Typeface.NORMAL)
                STYLE_VOGUE -> Typeface.create("serif", Typeface.NORMAL)
                STYLE_GOLD -> Typeface.create("serif", Typeface.BOLD)
                STYLE_NEON -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                else -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            }
        fillPaint.typeface = face
        strokePaint.typeface = face
        datePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun setStyle(newStyle: Int) {
        style = newStyle.coerceIn(0, org.petalos.config.DepthClockMaterials.LAST)
        updateColors()
        applyTypeface(style)
        invalidate()
    }

    // vertical placement
    fun setAnchor(fraction: Float) {
        anchor = fraction.coerceIn(0.05f, 0.85f)
        invalidate()
    }

    fun setAnchorX(fraction: Float) {
        anchorX = fraction.coerceIn(0.1f, 0.9f)
        invalidate()
    }

    // how big the digits get
    fun setScale(factor: Float) {
        scale = factor.coerceIn(0.6f, 1.6f)
        invalidate()
    }

    fun setStackVertical(stackVertical: Boolean) {
        vertical = stackVertical
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        updateColors()
        invalidate()
    }

    fun setDateEnabled(enabled: Boolean) {
        showDate = enabled
        invalidate()
    }

    // clock only follows a fraction of the tilt
    fun setSubjectTilt(px: Float, py: Float) {
        val nx = px * PARALLAX_RATE
        val ny = py * PARALLAX_RATE
        if (tiltX != nx || tiltY != ny) {
            tiltX = nx
            tiltY = ny
            invalidate()
        }
    }

    fun startTicking() {
        updateTime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stopTicking() {
        handler.removeCallbacks(ticker)
    }

    private fun updateTime() {
        calendar.timeInMillis = System.currentTimeMillis()
        val h = if (twentyFourHour) calendar.get(Calendar.HOUR_OF_DAY)
        else {
            val h12 = calendar.get(Calendar.HOUR)
            if (h12 == 0) 12 else h12
        }
        hourText = String.format(Locale.US, "%02d", h)
        minuteText = String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE))
        dateText = DateFormat.format(
            context.getString(R.string.petal_depth_clock_date_format), calendar
        ).toString()
        invalidate()
    }

    private fun updateColors() {
        val base =
            when {
                accentColor != Color.TRANSPARENT -> accentColor
                style == STYLE_BLOCKBUSTER -> Color.rgb(255, 59, 48)
                style == STYLE_NEON -> Color.rgb(155, 232, 255)
                style == STYLE_GOLD -> Color.rgb(255, 201, 77)
                else -> Color.WHITE
            }
        fillPaint.color = base
        strokePaint.color = base
        datePaint.color = Color.argb(210, Color.red(base), Color.green(base), Color.blue(base))
    }

    // stretch across the keyguard root
    fun attachToRoot(root: android.view.ViewGroup) {
        val cl = root as androidx.constraintlayout.widget.ConstraintLayout
        // must be the stable res id or the blueprint binder wipes us
        id = R.id.petal_depth_clock
        val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }
        cl.addView(this, lp)
    }

    private val digitSpacing: Float
        get() = fillPaint.textSize * 0.12f

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        val rawHour = hourText
        val rawMinute = minuteText
        val square = org.petalos.config.DepthClockMaterials.isSquare(style)
        // square styles stack, hours over minutes
        val stack = vertical || square
        val useColon = !stack
        val hour = if (useColon) "$rawHour:" else rawHour
        val minute = rawMinute

        // size digits to fill the width
        val probe = Paint(fillPaint).apply { textSize = 100f }
        val unitWidth = if (stack) probe.measureText("00") else probe.measureText("00:00")
        val targetWidth = width * (when {
            square -> 0.92f
            stack -> 0.62f
            else -> 0.78f
        }) * scale
        val textSize = 100f * targetWidth / unitWidth.coerceAtLeast(1f)
        // cap the height so it fits
        val heightCap = height * 0.5f * scale / (when {
            square -> 2.1f
            stack -> 2.3f
            else -> 1.4f
        })
        val finalTextSize = minOf(textSize, heightCap)

        fillPaint.textSize = finalTextSize
        strokePaint.textSize = finalTextSize
        strokePaint.strokeWidth = finalTextSize * 0.035f
        datePaint.textSize = finalTextSize * 0.12f

        // dx/dy from the smoothed tilt
        val dx = tiltX
        val dy = tiltY


        val hh = textBounds(hour)
        val mm = textBounds(minute)
        val centerX = width * anchorX + dx
        if (stack) {
            val lineStep = finalTextSize * 1.25f
            val baseline = height * anchor - (hh.top + lineStep + mm.bottom) / 2f + dy
            drawStyled(canvas, hour, centerX - hh.centerX(), baseline, style)
            drawStyled(canvas, minute, centerX - mm.centerX(), baseline + lineStep, style)
            if (showDate) {
                canvas.drawText(dateText, centerX - datePaint.measureText(dateText) / 2f,
                    baseline + hh.top - datePaint.textSize * 0.5f
                        - datePaint.fontMetrics.bottom, datePaint)
            }
        } else {
            val minuteX = fillPaint.measureText(hour) + digitSpacing
            val left = minOf(hh.left, minuteX + mm.left)
            val right = maxOf(hh.right, minuteX + mm.right)
            val startX = centerX - (left + right) / 2f
            val baseline = height * anchor -
                (minOf(hh.top, mm.top) + maxOf(hh.bottom, mm.bottom)) / 2f + dy
            drawStyled(canvas, hour, startX, baseline, style)
            drawStyled(canvas, minute, startX + minuteX, baseline, style)
            if (showDate) {
                canvas.drawText(dateText, centerX - datePaint.measureText(dateText) / 2f,
                    baseline + datePaint.textSize * 1.9f, datePaint)
            }
        }
    }

    private fun textBounds(text: String): android.graphics.RectF {
        val bounds = android.graphics.Rect()
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        return android.graphics.RectF(bounds).apply {
            if (style == STYLE_VOGUE) {
                val extra = fillPaint.textSize * 0.18f * (text.length - 1).coerceAtLeast(0)
                left -= extra / 2f
                right += extra / 2f
            }
        }
    }

    // draw a single run in the given style
    private fun drawStyled(canvas: Canvas, text: String, x: Float, y: Float, forStyle: Int) {
        if (materials.draw(canvas, text, x, y, forStyle, fillPaint,
                if (forStyle == org.petalos.config.DepthClockMaterials.LIQUID)
                    liquidBackdrop ?: backdrop else backdrop, width, height)) return
        when (forStyle) {
            STYLE_OUTLINE -> {
                drawSoftShadow(canvas, text, x, y)
                canvas.drawText(text, x, y, strokePaint)
            }
            STYLE_GRADIENT -> {
                val w = fillPaint.measureText(text)
                fillPaint.shader = LinearGradient(
                    0f, y - fillPaint.textSize, 0f, y,
                    Color.argb(255, Color.red(fillPaint.color), Color.green(fillPaint.color),
                        Color.blue(fillPaint.color)),
                    Color.argb(90, Color.red(fillPaint.color), Color.green(fillPaint.color),
                        Color.blue(fillPaint.color)),
                    Shader.TileMode.CLAMP
                )
                canvas.drawText(text, x, y, fillPaint)
                fillPaint.shader = null
            }
            STYLE_HYBRID -> {
                // flip fill and outline every other char
                var cx = x
                var solid = true
                for (ch in text) {
                    val chStr = ch.toString()
                    val cw = fillPaint.measureText(chStr)
                    if (ch == ' ') { cx += cw; continue }
                    if (solid) canvas.drawText(chStr, cx, y, fillPaint)
                    else canvas.drawText(chStr, cx, y, strokePaint)
                    cx += cw
                    solid = !solid
                }
            }
            STYLE_BLOCKBUSTER -> drawBlockbuster(canvas, text, x, y)
            STYLE_NEON -> drawNeon(canvas, text, x, y)
            STYLE_GOLD -> drawGoldOutline(canvas, text, x, y)
            STYLE_VOGUE -> drawEditorial(canvas, text, x, y)
            STYLE_MONOLITH -> drawMonolith(canvas, text, x, y)
            else -> {
                drawSoftShadow(canvas, text, x, y)
                canvas.drawText(text, x, y, fillPaint)
            }
        }
    }

    // poster look with a hard split
    private fun drawBlockbuster(canvas: Canvas, text: String, x: Float, y: Float) {
        fillPaint.shader = LinearGradient(
            0f, y - fillPaint.textSize, 0f, y + fillPaint.textSize * 0.1f,
            intArrayOf(fillPaint.color, fillPaint.color, shade(fillPaint.color, 0.45f)),
            floatArrayOf(0f, 0.58f, 0.58f),
            Shader.TileMode.CLAMP
        )
        drawSoftShadow(canvas, text, x, y)
        canvas.drawText(text, x, y, fillPaint)
        fillPaint.shader = null
    }

    // fat glow under a thin bright core
    private fun drawNeon(canvas: Canvas, text: String, x: Float, y: Float) {
        glowPaint.typeface = fillPaint.typeface
        glowPaint.textSize = fillPaint.textSize
        glowPaint.color = fillPaint.color
        glowPaint.strokeWidth = fillPaint.textSize * 0.02f
        glowPaint.setShadowLayer(fillPaint.textSize * 0.28f, 0f, 0f,
            withAlpha(fillPaint.color, 180))
        canvas.drawText(text, x, y, glowPaint)
        canvas.drawText(text, x, y, glowPaint)
        // bright core last
        glowPaint.setShadowLayer(fillPaint.textSize * 0.08f, 0f, 0f, Color.WHITE)
        canvas.drawText(text, x, y, glowPaint)
        glowPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    // serif outline with a warm glow
    private fun drawGoldOutline(canvas: Canvas, text: String, x: Float, y: Float) {
        glowPaint.typeface = strokePaint.typeface
        glowPaint.textSize = strokePaint.textSize
        glowPaint.color = strokePaint.color
        glowPaint.strokeWidth = strokePaint.strokeWidth
        glowPaint.setShadowLayer(strokePaint.textSize * 0.14f, 0f, 0f,
            withAlpha(strokePaint.color, 150))
        canvas.drawText(text, x, y, glowPaint)
        glowPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        canvas.drawText(text, x, y, strokePaint)
    }

    // serif with wide tracking
    private fun drawEditorial(canvas: Canvas, text: String, x: Float, y: Float) {
        val spacing = fillPaint.textSize * 0.18f
        val plain = fillPaint.measureText(text)
        val total = if (text.length > 1) plain + spacing * (text.length - 1) else plain
        var cx = x + (plain - total) / 2f
        val savedColor = fillPaint.color
        fillPaint.color = Color.argb(70, 0, 0, 0)
        var sx = cx
        for (ch in text) {
            val s = ch.toString()
            canvas.drawText(
                s, sx + fillPaint.textSize * 0.02f, y + fillPaint.textSize * 0.03f, fillPaint
            )
            sx += fillPaint.measureText(s) + spacing
        }
        fillPaint.color = savedColor
        for (ch in text) {
            val s = ch.toString()
            canvas.drawText(s, cx, y, fillPaint)
            cx += fillPaint.measureText(s) + spacing
        }
    }

    // fake extrude, just offset copies
    private fun drawMonolith(canvas: Canvas, text: String, x: Float, y: Float) {
        val depth = fillPaint.textSize * 0.09f
        val steps = 14
        depthPaint.typeface = fillPaint.typeface
        depthPaint.textSize = fillPaint.textSize
        depthPaint.color = shade(fillPaint.color, 0.22f)
        for (i in steps downTo 1) {
            val t = i / steps.toFloat()
            canvas.drawText(text, x + depth * t, y + depth * t, depthPaint)
        }
        canvas.drawText(text, x, y, fillPaint)
    }

    private fun shade(color: Int, factor: Float): Int =
        Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255),
        )

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun drawSoftShadow(canvas: Canvas, text: String, x: Float, y: Float) {
        val savedColor = fillPaint.color
        fillPaint.color = Color.argb(70, 0, 0, 0)
        canvas.drawText(text, x + fillPaint.textSize * 0.02f, y + fillPaint.textSize * 0.03f,
            fillPaint)
        fillPaint.color = savedColor
    }
}
