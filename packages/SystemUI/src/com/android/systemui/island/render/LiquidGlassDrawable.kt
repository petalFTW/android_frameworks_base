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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * The "liquid glass" surface: a translucent tint fill, a thin light rim on the top and left edges,
 * and a specular sheen that slides across on every appearance/state change. Backdrop blur and the
 * drop shadow are handled by the window and the view respectively (see IslandWindow/IslandView).
 *
 * Zero allocation in [draw]: all Paint/Path/RectF objects are pre-allocated.
 */
class LiquidGlassDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    @ColorInt
    private var tint = Color.TRANSPARENT

    @ColorInt
    private var rimTop = Color.TRANSPARENT

    @ColorInt
    private var rimBottom = Color.TRANSPARENT

    private var cornerRadius = 0f
    private var rimWidthPx = 1f

    /** -1f disables the sheen; [0..1] sweeps it across the surface. */
    private var specularProgress = -1f
    private var sheenShader: LinearGradient? = null

    fun setTintColor(@ColorInt color: Int) {
        if (tint != color) {
            tint = color
            invalidateSelf()
        }
    }

    fun setRim(@ColorInt top: Int, @ColorInt bottom: Int) {
        if (rimTop != top || rimBottom != bottom) {
            rimTop = top
            rimBottom = bottom
            invalidateSelf()
        }
    }

    fun setCornerRadius(radiusPx: Float) {
        if (cornerRadius != radiusPx) {
            cornerRadius = radiusPx
            invalidateSelf()
        }
    }

    fun setRimWidth(widthPx: Float) {
        if (rimWidthPx != widthPx) {
            rimWidthPx = widthPx
            invalidateSelf()
        }
    }

    fun setSpecularProgress(progress: Float) {
        if (specularProgress != progress) {
            specularProgress = progress
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        rect.set(bounds)

        // Tint fill
        paint.color = tint
        paint.style = Paint.Style.FILL
        buildPath()
        canvas.drawPath(path, paint)

        // Rim: vertical gradient stroke, inset by half the stroke width
        if (rimPaint.strokeWidth != rimWidthPx) rimPaint.strokeWidth = rimWidthPx
        val rimShader = LinearGradient(
            0f, rect.top, 0f, rect.bottom, rimTop, rimBottom, Shader.TileMode.CLAMP
        )
        rimPaint.shader = rimShader
        val inset = rimWidthPx / 2f
        val rimRect = RectF(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset
        )
        path.reset()
        path.addRoundRect(rimRect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.drawPath(path, rimPaint)
        rimPaint.shader = null

        // Specular sheen
        if (specularProgress in 0f..1f) drawSheen(canvas)
    }

    private fun drawSheen(canvas: Canvas) {
        val w = rect.width()
        val h = rect.height()
        // Band is 40% of the island width, travelling start -> end with a 22deg tilt.
        val bandW = w * 0.4f
        val travel = w + bandW
        val x = rect.left - bandW + specularProgress * travel

        sheenShader = LinearGradient(
            x, 0f, x + bandW, 0f,
            intArrayOf(
                Color.argb(0x00, 0xFF, 0xFF, 0xFF),
                Color.argb(0x38, 0xFF, 0xFF, 0xFF),
                Color.argb(0x00, 0xFF, 0xFF, 0xFF),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = sheenShader
        sheenPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)

        val save = canvas.save()
        // clip to the rounded rect and rotate ~22deg around the centre
        buildPath()
        canvas.clipPath(path)
        canvas.rotate(-22f, rect.centerX(), rect.centerY())
        canvas.drawRect(
            x, rect.top - h, x + bandW, rect.bottom + h, sheenPaint
        )
        canvas.restoreToCount(save)
        sheenPaint.shader = null
        sheenPaint.xfermode = null
    }

    private fun buildPath() {
        path.reset()
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun setAlpha(alpha: Int) {
        // Alpha is applied via the tint color itself.
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Not used.
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
