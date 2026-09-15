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

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import kotlin.random.Random

/** glass fill, grain and a moving rim. */
class LiquidGlassDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val rimRect = RectF()
    private val rimPath = Path()
    private val wrappedNeon = Path()
    private val sheenMatrix = Matrix()
    private var geometryDirty = true
    private var rimShaderDirty = true
    private var neonBlur: BlurMaskFilter? = null

    @ColorInt
    private var tint = Color.TRANSPARENT

    @ColorInt
    private var rimTop = Color.TRANSPARENT

    @ColorInt
    private var rimBottom = Color.TRANSPARENT

    private var cornerRadius = 0f
    private var rimWidthPx = 1f
    private var grainAlpha = DEFAULT_GRAIN_ALPHA

    /** sheen position, -1 is off. */
    private var specularProgress = -1f
    private var sheenShader: LinearGradient? = null

    /** grain bitmap, built once. */
    private var grainShader: BitmapShader? = null

    /** neon sweep position, -1 is off. */
    private var neonProgress = -1f

    @ColorInt
    private var neonColor = Color.TRANSPARENT

    /** chroma shimmer sweep position, -1 is off. bridge transitions only. */
    private var chromaProgress = -1f
    private val chromaMatrix = Matrix()
    private val chromaPaints = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG),
        Paint(Paint.ANTI_ALIAS_FLAG),
        Paint(Paint.ANTI_ALIAS_FLAG),
    )
    private val chromaShaders = arrayOfNulls<LinearGradient>(3)


    private val neonMeasurePath = Path()
    private val neonSegment = Path()
    private val neonMeasure = PathMeasure()
    private val neonCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val neonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        grainShader = BitmapShader(noiseBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        grainPaint.shader = grainShader
        grainPaint.alpha = grainAlpha
    }

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
            rimShaderDirty = true
            invalidateSelf()
        }
    }

    fun setCornerRadius(radiusPx: Float) {
        if (cornerRadius != radiusPx) {
            cornerRadius = radiusPx
            geometryDirty = true
            invalidateSelf()
        }
    }

    fun setRimWidth(widthPx: Float) {
        if (rimWidthPx != widthPx) {
            rimWidthPx = widthPx
            geometryDirty = true
            invalidateSelf()
        }
    }

    fun setGrainAlpha(alpha: Int) {
        if (grainAlpha != alpha) {
            grainAlpha = alpha
            grainPaint.alpha = alpha
            invalidateSelf()
        }
    }

    fun setSpecularProgress(progress: Float) {
        if (specularProgress != progress) {
            specularProgress = progress
            invalidateSelf()
        }
    }

    fun setNeonColor(@ColorInt color: Int) {
        neonColor = color
    }

    fun setNeonProgress(progress: Float) {
        if (neonProgress != progress) {
            neonProgress = progress
            invalidateSelf()
        }
    }

    fun setChromaProgress(progress: Float) {
        if (chromaProgress != progress) {
            chromaProgress = progress
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        updateGeometry()

        // fill
        paint.color = tint
        paint.style = Paint.Style.FILL
        paint.shader = null
        canvas.drawPath(path, paint)

        // grain
        canvas.drawPath(path, grainPaint)

        if (rimShaderDirty) {
            rimPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom, rimTop, rimBottom, Shader.TileMode.CLAMP
            )
            rimShaderDirty = false
        }
        canvas.drawPath(rimPath, rimPaint)

        // neon sweep
        if (neonProgress >= 0f) drawNeon(canvas)

        // chroma shimmer, the prism fart
        if (chromaProgress in 0f..1f) drawChroma(canvas)

        // sheen
        if (specularProgress in 0f..1f) drawSheen(canvas)
    }

    // draw the comet
    private fun drawNeon(canvas: Canvas) {
        val perimeter = neonMeasure.length
        if (perimeter <= 0f) return

        val chainLength = perimeter * NEON_CHAIN_FRACTION
        val head = neonProgress * (perimeter + chainLength)
        val tail = (head - chainLength).coerceAtLeast(0f)
        if (tail >= perimeter) return

        neonSegment.reset()
        val wrapped = head - perimeter
        if (wrapped <= 0f) {
            neonMeasure.getSegment(tail, head, neonSegment, true)
        } else {
            // head wrapped past the start, stitch both ends together
            neonMeasure.getSegment(tail, perimeter, neonSegment, true)
            wrappedNeon.reset()
            neonMeasure.getSegment(0f, wrapped, wrappedNeon, true)
            neonSegment.addPath(wrappedNeon)
        }

        val alpha = neonAlpha(neonProgress)
        val coreWidth = rimWidthPx * 1.6f
        val glowWidth = rimWidthPx * 5f

        neonGlowPaint.color = neonWithAlpha((alpha * 0x30).toInt())
        neonGlowPaint.strokeWidth = glowWidth
        neonGlowPaint.maskFilter = neonBlur
        canvas.drawPath(neonSegment, neonGlowPaint)
        neonGlowPaint.maskFilter = null

        neonCorePaint.color = neonWithAlpha((alpha * 0xFF).toInt())
        neonCorePaint.strokeWidth = coreWidth
        canvas.drawPath(neonSegment, neonCorePaint)
    }

    /** fade the comet in and out at the ends. */
    private fun neonAlpha(p: Float): Float {
        val fadeIn = (p / NEON_FADE_FRACTION).coerceIn(0f, 1f)
        val fadeOut = ((1f - p) / NEON_FADE_FRACTION).coerceIn(0f, 1f)
        return fadeIn * fadeOut
    }

    private fun neonWithAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(neonColor), Color.green(neonColor), Color.blue(neonColor))

    // three offset rgb bands sliding across like a busted prism
    private fun drawChroma(canvas: Canvas) {
        val w = rect.width()
        val bandW = w * 0.30f
        val travel = w + bandW * 2.5f
        val x = rect.left - bandW * 2f + chromaProgress * travel
        val a = chromaAlpha(chromaProgress)

        val save = canvas.save()
        canvas.clipPath(path)
        canvas.rotate(-18f, rect.centerX(), rect.centerY())
        for (i in chromaPaints.indices) {
            val off = (i - 1) * bandW * 0.24f
            chromaMatrix.setTranslate(x + off, 0f)
            chromaShaders[i]?.setLocalMatrix(chromaMatrix)
            chromaPaints[i].alpha = (a * CHROMA_BAND_ALPHA).toInt()
            canvas.drawRect(
                x + off, rect.top - rect.height(), x + off + bandW, rect.bottom + rect.height(),
                chromaPaints[i],
            )
        }
        canvas.restoreToCount(save)
    }

    /** ramp in, ramp out, same trick as the neon comet. */
    private fun chromaAlpha(p: Float): Float {
        val fadeIn = (p / CHROMA_FADE_FRACTION).coerceIn(0f, 1f)
        val fadeOut = ((1f - p) / CHROMA_FADE_FRACTION).coerceIn(0f, 1f)
        return fadeIn * fadeOut
    }


    private fun drawSheen(canvas: Canvas) {
        val w = rect.width()
        val h = rect.height()
        // 40% wide band, sliding across at a tilt
        val bandW = w * 0.4f
        val travel = w + bandW
        val x = rect.left - bandW + specularProgress * travel

        sheenMatrix.setTranslate(x, 0f)
        sheenShader?.setLocalMatrix(sheenMatrix)

        val save = canvas.save()
        // clip to the shape, then rotate
        canvas.clipPath(path)
        canvas.rotate(-22f, rect.centerX(), rect.centerY())
        canvas.drawRect(
            x, rect.top - h, x + bandW, rect.bottom + h, sheenPaint
        )
        canvas.restoreToCount(save)
    }

    override fun onBoundsChange(bounds: Rect) {
        geometryDirty = true
        rimShaderDirty = true
    }

    private fun updateGeometry() {
        if (!geometryDirty) return
        rect.set(bounds)
        path.reset()
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        rimRect.set(rect)
        rimRect.inset(rimWidthPx / 2f, rimWidthPx / 2f)
        rimPath.reset()
        rimPath.addRoundRect(rimRect, cornerRadius, cornerRadius, Path.Direction.CW)
        neonMeasurePath.set(rimPath)
        neonMeasure.setPath(neonMeasurePath, false)
        rimPaint.strokeWidth = rimWidthPx
        neonBlur = if (rimWidthPx > 0f) {
            BlurMaskFilter(rimWidthPx * 5f, BlurMaskFilter.Blur.NORMAL)
        } else null
        sheenShader = LinearGradient(
            0f, 0f, rect.width() * 0.4f, 0f,
            intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF),
            null, Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = sheenShader
        sheenPaint.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.SRC_ATOP
        )
        // rgb band shaders for the chroma sweep, additive so they glow
        for (i in chromaShaders.indices) {
            chromaShaders[i] = LinearGradient(
                0f, 0f, rect.width() * 0.3f, 0f,
                intArrayOf(Color.TRANSPARENT, CHROMA_COLORS[i], Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            chromaPaints[i].shader = chromaShaders[i]
            chromaPaints[i].xfermode = android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SCREEN
            )
        }
        geometryDirty = false
    }

    override fun setAlpha(alpha: Int) {
        // alpha comes through the tint colour
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // unused
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val DEFAULT_GRAIN_ALPHA = 0x12
        private const val NOISE_SIZE = 96

        /** how much of the rim the comet covers. */
        private const val NEON_CHAIN_FRACTION = 0.24f

        /** fade length at each end of the sweep. */
        private const val NEON_FADE_FRACTION = 0.12f

        /** chroma band tuning */
        private const val CHROMA_FADE_FRACTION = 0.22f
        private const val CHROMA_BAND_ALPHA = 0x5A
        private val CHROMA_COLORS = intArrayOf(0xFFFF5050.toInt(), 0xFF50FF9B.toInt(), 0xFF5AA0FF.toInt())

        @Volatile
        private var cachedNoise: Bitmap? = null

        /** one-time noise bitmap. */
        private fun noiseBitmap(): Bitmap {
            cachedNoise?.let { return it }
            val bmp = Bitmap.createBitmap(NOISE_SIZE, NOISE_SIZE, Bitmap.Config.ARGB_8888)
            val px = IntArray(NOISE_SIZE * NOISE_SIZE)
            val rnd = Random(0x5EED)
            for (i in px.indices) {
                val light = rnd.nextBoolean()
                val a = rnd.nextInt(0x40)
                px[i] = if (light) Color.argb(a, 0xFF, 0xFF, 0xFF) else Color.argb(a, 0x00, 0x00, 0x00)
            }
            bmp.setPixels(px, 0, NOISE_SIZE, 0, 0, NOISE_SIZE, NOISE_SIZE)
            cachedNoise = bmp
            return bmp
        }
    }
}
