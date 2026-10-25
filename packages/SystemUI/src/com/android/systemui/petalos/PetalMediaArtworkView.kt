/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.ImageView
import kotlin.math.min
import kotlin.math.roundToInt

class PetalMediaArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        blendMode = BlendMode.DST_IN
        isDither = true
    }
    private var featherAmount = 0f
    private var maskHeight = -1
    private var maskFeather = -1f
    private var maskBottomAlpha = -1

    val featherHeight: Float
        get() = min(height * 0.30f, 32f * resources.displayMetrics.density)

    fun setFeatherBottom(amount: Float) {
        val strength = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
        if (featherAmount == strength) return
        featherAmount = strength
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (featherAmount <= 0f || !canvas.isHardwareAccelerated || width <= 0 || height <= 0) {
            super.draw(canvas)
            return
        }
        val bottomAlpha = ((1f - featherAmount) * 255f).roundToInt()
        if (maskHeight != height || maskFeather != featherHeight || maskBottomAlpha != bottomAlpha) {
            mask.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.WHITE, Color.WHITE, Color.argb(bottomAlpha, 255, 255, 255)),
                floatArrayOf(0f, 1f - featherHeight / height, 1f), Shader.TileMode.CLAMP)
            maskHeight = height
            maskFeather = featherHeight
            maskBottomAlpha = bottomAlpha
        }
        // Fade the image and its backing together, or the old rectangle still shows.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.draw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        canvas.restoreToCount(layer)
    }
}
