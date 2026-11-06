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
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class IslandGlyph { CHARGING, TORCH }

// little animated icon for the system chips. drawn on a 24 unit grid, scales to any size
class IslandGlyphView(
    context: Context,
    private val glyph: IslandGlyph,
    @ColorInt private val accent: Int,
) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f
    }
    private val bolt = Path().apply {
        moveTo(13f, 2f)
        lineTo(5f, 14f)
        lineTo(11f, 14f)
        lineTo(10f, 22f)
        lineTo(19f, 9f)
        lineTo(13f, 9f)
        close()
    }
    private val torch = Path().apply {
        moveTo(6.5f, 9f)
        lineTo(17.5f, 9f)
        lineTo(15f, 14f)
        lineTo(15f, 21f)
        quadTo(15f, 23f, 13f, 23f)
        lineTo(11f, 23f)
        quadTo(9f, 23f, 9f, 21f)
        lineTo(9f, 14f)
        close()
    }
    private val startMs = SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val ms = SystemClock.uptimeMillis() - startMs
        val unit = min(width, height) / 24f
        if (unit <= 0f) return
        canvas.save()
        canvas.translate((width - unit * 24f) / 2f, (height - unit * 24f) / 2f)
        canvas.scale(unit, unit)
        // pops in with a bit of bounce
        val pop = 1f - exp(-ms / 70f) * cos(ms / 55f)
        canvas.scale(pop, pop, 12f, 12f)
        when (glyph) {
            IslandGlyph.CHARGING -> drawCharging(canvas, ms)
            IslandGlyph.TORCH -> drawTorch(canvas, ms)
        }
        canvas.restore()
        // onDraw only runs while we're on screen, so this stops by itself. 30fps is plenty
        postInvalidateDelayed(FRAME_MS)
    }

    // power fills the bolt from the bottom up, then flashes and starts over
    private fun drawCharging(canvas: Canvas, ms: Long) {
        val t = (ms % CHARGE_LOOP_MS) / CHARGE_LOOP_MS.toFloat()
        val rise = min(t / 0.72f, 1f)
        val eased = rise * rise * (3f - 2f * rise)
        val flash = max(0f, (t - 0.72f) / 0.28f)
        fill.color = withAlpha(accent, 0.3f)
        canvas.drawPath(bolt, fill)
        canvas.save()
        canvas.clipRect(0f, 24f * (1f - eased), 24f, 24f)
        fill.color = withAlpha(accent, 1f - 0.65f * flash)
        canvas.drawPath(bolt, fill)
        canvas.restore()
    }

    // solid torch, three rays pulsing out of the head one after another
    private fun drawTorch(canvas: Canvas, ms: Long) {
        fill.color = accent
        canvas.drawPath(torch, fill)
        val t = (ms % TORCH_LOOP_MS) / TORCH_LOOP_MS.toFloat()
        for (i in 0..2) {
            val wave = sin(2f * PI.toFloat() * (t - lag(i) * 0.14f))
            val glow = 0.35f + 0.65f * max(0f, wave)
            line.color = withAlpha(accent, glow)
            val x = 12f + (i - 1) * 5.2f
            val lean = (i - 1) * 1.9f
            canvas.drawLine(x, 6.4f, x + lean, 6.4f - (2f + 2f * glow), line)
        }
    }

    // centre ray goes first, the sides follow
    private fun lag(i: Int): Int = if (i == 1) 0 else 1

    private fun withAlpha(@ColorInt color: Int, alpha: Float): Int =
        Color.argb(
            (Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private companion object {
        const val FRAME_MS = 33L
        const val CHARGE_LOOP_MS = 1500L
        const val TORCH_LOOP_MS = 1600L
    }
}
