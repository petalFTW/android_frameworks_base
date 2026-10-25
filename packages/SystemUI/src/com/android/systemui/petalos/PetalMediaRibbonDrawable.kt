/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

// The bottom needs the same bloody curve as the top.
class PetalMediaRibbonDrawable(private val density: Float) : Drawable() {
    var backdrop: PetalMediaRibbonBackdrop? = null
    private val surface = Path()
    private val upper = Path()
    private val lower = Path()
    private val shift = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private var fill: Shader? = null
    private var rim: Shader? = null
    private var upperGlow: Shader? = null
    private var lowerGlow: Shader? = null
    private var capLeft: Shader? = null
    private var capRight: Shader? = null
    private var opacity = 255
    private var playbackGlow = 0f

    fun setPlaybackGlow(amount: Float) {
        val value = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
        if (playbackGlow == value) return
        playbackGlow = value
        invalidateSelf()
    }

    private var thickness = 0f

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.isEmpty) {
            surface.rewind()
            upper.rewind()
            lower.rewind()
            fill = null
            rim = null
            upperGlow = null
            lowerGlow = null
            capLeft = null
            capRight = null
            return
        }
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val sweep = minOf(w * 0.13f, 52f * density)
        thickness = (h - sweep).coerceAtLeast(1f)

        rimPath(upper, left, top, w, sweep)
        rimPath(lower, left, top, w, sweep)
        shift.setTranslate(0f, thickness)
        lower.transform(shift)
        surface.set(upper)
        surface.lineTo(left + w, top + thickness)
        surface.cubicTo(left + w * 0.78f, top + thickness + sweep * 0.34f,
            left + w * 0.60f, top + thickness + sweep * 0.78f,
            left + w * 0.36f, top + thickness + sweep * 0.62f)
        surface.cubicTo(left + w * 0.20f, top + thickness + sweep * 0.46f,
            left + w * 0.06f, top + thickness + sweep * 0.64f,
            left, top + thickness + sweep)
        surface.close()

        fill = LinearGradient(left, top + sweep * 0.4f, left + w * 0.75f, top + h,
            intArrayOf(0x687F5148, 0x805F3D3C.toInt(), 0x667E5648),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rim = LinearGradient(left, top, left + w, top,
            intArrayOf(0xFFFFEBD0.toInt(), 0xFFEEC2B1.toInt(), 0xFFC7BBF3.toInt(), 0xFFA9D6FF.toInt()),
            floatArrayOf(0f, 0.48f, 0.78f, 1f), Shader.TileMode.CLAMP)
        upperGlow = LinearGradient(left, top, left + w, top,
            intArrayOf(0xFFFFBC68.toInt(), 0xFFFFA58A.toInt(), 0xFFEBA9CD.toInt(),
                0xFFAA9CEF.toInt(), 0xFF77BBFF.toInt()),
            floatArrayOf(0f, 0.25f, 0.52f, 0.78f, 1f), Shader.TileMode.CLAMP)
        lowerGlow = LinearGradient(left, top, left + w, top,
            intArrayOf(0xFFFFC36D.toInt(), 0xFFFF9962.toInt(), 0xFFEEA188.toInt(),
                0xFFB58ED2.toInt(), 0xFF719ACF.toInt()),
            floatArrayOf(0f, 0.32f, 0.58f, 0.82f, 1f), Shader.TileMode.CLAMP)
        capLeft = LinearGradient(left, 0f, left + w * 0.30f, 0f,
            intArrayOf(0x660D0B16, 0x180D0B16, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        capRight = LinearGradient(left + w, 0f, left + w * 0.70f, 0f,
            intArrayOf(0x880E101E.toInt(), 0x280E101E, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    // Little curl on the left, long lazy sweep on the right.
    private fun rimPath(path: Path, left: Float, top: Float, w: Float, sweep: Float) {
        path.rewind()
        path.moveTo(left, top + sweep)
        path.cubicTo(left + w * 0.06f, top + sweep * 0.64f,
            left + w * 0.20f, top + sweep * 0.46f,
            left + w * 0.36f, top + sweep * 0.62f)
        path.cubicTo(left + w * 0.60f, top + sweep * 0.78f,
            left + w * 0.78f, top + sweep * 0.34f, left + w, top)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.clipPath(surface)
        backdrop?.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.shader = fill
        paint.alpha = opacity
        canvas.drawPath(surface, paint)

        // Smoked ends pull focus to the centre.
        paint.shader = capLeft
        paint.alpha = opacity
        canvas.drawRect(bounds, paint)
        paint.shader = capRight
        canvas.drawRect(bounds, paint)
        // Feather the colour inward; the smoked ends were eating the glow.
        paint.style = Paint.Style.STROKE
        for (index in GLOW_WIDTHS.indices) {
            paint.strokeWidth = GLOW_WIDTHS[index] * density
            paint.alpha = (opacity * GLOW_ALPHAS[index] * (1f + playbackGlow * 0.5f) / 255f).toInt()
            paint.shader = upperGlow
            canvas.drawPath(upper, paint)
            paint.shader = lowerGlow
            canvas.drawPath(lower, paint)
        }
        canvas.restoreToCount(save)

        val rimSave = canvas.save()
        paint.style = Paint.Style.STROKE
        paint.shader = rim
        paint.strokeWidth = 5f * density
        paint.alpha = opacity * 26 / 255
        canvas.drawPath(upper, paint)
        canvas.drawPath(lower, paint)
        paint.strokeWidth = density
        paint.alpha = opacity
        canvas.drawPath(upper, paint)
        paint.alpha = opacity * 120 / 255
        canvas.drawPath(lower, paint)
        paint.shader = null
        canvas.restoreToCount(rimSave)
    }

    override fun getOutline(outline: Outline) {
        if (surface.isEmpty) outline.setEmpty() else outline.setPath(surface)
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val ACCENT = 0xFFC78373.toInt()
        const val TRACK_ACCENT = 0xFFE1816D.toInt()
        private val GLOW_WIDTHS = floatArrayOf(32f, 24f, 18f, 12f, 8f, 5f, 3f)
        private val GLOW_ALPHAS = intArrayOf(3, 5, 8, 12, 18, 25, 38)
    }
}
