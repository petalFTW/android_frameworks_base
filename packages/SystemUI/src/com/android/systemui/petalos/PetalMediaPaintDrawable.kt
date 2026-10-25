/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import com.android.systemui.island.render.LiquidGlassDrawable
import com.android.systemui.island.render.RefractionShader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The sleeve's lower edge spills into the glass, not another sharp rectangle.
class PetalMediaPaintDrawable(
    private val glass: LiquidGlassDrawable,
    private val cover: () -> ImageView?,
) : Drawable(), Drawable.Callback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var current: Bloom? = null
    private var previous: Bloom? = null
    private var animator: ValueAnimator? = null
    private var reveal = 1f
    private var tintAlpha = 0.52f
    private var opacity = 255
    private var bloomAmount = 1f
    private var style = PetalMediaStyle.Stock

    init {
        glass.callback = this
    }

    fun setTintColor(color: Int) {
        tintAlpha = Color.alpha(color) / 255f
        glass.setTintColor(color)
    }

    fun setStyle(style: PetalMediaStyle, bloomAmount: Float) {
        val amount = if (bloomAmount.isFinite()) bloomAmount.coerceIn(0f, 1f) else 0f
        if (this.style == style && this.bloomAmount == amount) return
        this.style = style
        this.bloomAmount = amount
        invalidateSelf()
    }

    fun setArtwork(bitmap: Bitmap?) {
        val old = current?.bitmap
        if (old === bitmap || (old != null && bitmap != null && !old.isRecycled &&
                !bitmap.isRecycled && old.sameAs(bitmap))) return
        stop()
        previous = current
        current = bitmap?.takeUnless { it.isRecycled }?.let(::Bloom)
        if (callback == null || !isVisible || !ValueAnimator.areAnimatorsEnabled()) {
            previous = null
            invalidateSelf()
            return
        }
        reveal = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                reveal = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    previous?.release()
                    previous = null
                    reveal = 1f
                }
            })
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        previous?.release()
        previous = null
        reveal = 1f
    }

    override fun draw(canvas: Canvas) {
        glass.draw(canvas)
        val art = cover() ?: return
        if (bloomAmount <= 0f || !canvas.isHardwareAccelerated || art.visibility != View.VISIBLE ||
            art.width <= 0 || art.height <= 0) return
        val depth = min((bounds.bottom - art.bottom).toFloat(), 160f * art.resources.displayMetrics.density)
        if (depth <= 0f) return
        val alpha = opacity / 255f * art.alpha * bloomAmount
        // lockscreen runs clear frost: full paint, no tint-driven dimming
        val strength = if (style == PetalMediaStyle.Lockscreen) {
            1f
        } else {
            ((tintAlpha - 0.28f) / (0.52f - 0.28f)).coerceIn(0f, 1f)
        }
        val blur = (10f + 12f * strength) * art.resources.displayMetrics.density
        previous?.draw(canvas, art, bounds, depth, blur, paint, alpha * (1f - reveal), style)
        current?.draw(canvas, art, bounds, depth, blur, paint, alpha * reveal, style)
    }

    override fun onBoundsChange(bounds: Rect) {
        glass.bounds = bounds
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) stop()
        glass.setVisible(visible, restart)
        return super.setVisible(visible, restart)
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha.coerceIn(0, 255)
        glass.alpha = opacity
        invalidateSelf()
    }

    override fun getAlpha(): Int = opacity

    override fun jumpToCurrentState() {
        stop()
        glass.jumpToCurrentState()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glass.colorFilter = colorFilter
        paint.colorFilter = colorFilter
        current?.release()
        previous?.release()
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = scheduleSelf(what, at)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    private class Bloom(val bitmap: Bitmap) {
        private val artworkBounds = Rect()
        private val nodeBounds = Rect()
        private var cachedDepth = -1f
        private var cachedStyle = PetalMediaStyle.Stock
        private val node = RenderNode("PetalMediaArtworkFlow").apply { setClipToBounds(true) }
        private val lens = RefractionShader()

        fun draw(canvas: Canvas, art: ImageView, bounds: Rect, depth: Float, blur: Float,
                 paint: Paint, alpha: Float, style: PetalMediaStyle) {
            if (alpha <= 0f || bitmap.isRecycled || bounds.isEmpty) return
            if (artworkBounds.left != art.left || artworkBounds.top != art.top ||
                artworkBounds.right != art.right || artworkBounds.bottom != art.bottom ||
                nodeBounds != bounds || cachedDepth != depth ||
                cachedStyle != style || !node.hasDisplayList()) {
                artworkBounds.set(art.left, art.top, art.right, art.bottom)
                nodeBounds.set(bounds)
                cachedDepth = depth
                cachedStyle = style
                rebuild(art, bounds, depth, paint)
            }
            val density = art.resources.displayMetrics.density
            val artwork = RectF((art.left - bounds.left).toFloat(), (art.top - bounds.top).toFloat(),
                (art.right - bounds.left).toFloat(), (art.bottom - bounds.top).toFloat())
            val feather = min(art.height * 0.30f, 32f * density)
            node.setRenderEffect(lens.mediaFlowEffect(bounds.width(), bounds.height(), density,
                artwork, feather, depth, blur, style == PetalMediaStyle.Lockscreen))
            node.setAlpha(alpha.coerceIn(0f, 1f))
            canvas.drawRenderNode(node)
        }

        fun release() = node.discardDisplayList()

        private fun rebuild(art: ImageView, bounds: Rect, depth: Float, paint: Paint) {
            val scale = max(art.width.toFloat() / bitmap.width, art.height.toFloat() / bitmap.height)
            val visibleWidth = art.width / scale
            val visibleHeight = art.height / scale
            val sourceLeft = (bitmap.width - visibleWidth) / 2f
            val sourceTop = (bitmap.height - visibleHeight) / 2f
            val left = sourceLeft.roundToInt().coerceIn(0, bitmap.width - 1)
            val top = sourceTop.roundToInt().coerceIn(0, bitmap.height - 1)
            val source = Rect(left, top,
                (sourceLeft + visibleWidth).roundToInt().coerceIn(left + 1, bitmap.width),
                (sourceTop + visibleHeight).roundToInt().coerceIn(top + 1, bitmap.height))
            val destination = RectF((art.left - bounds.left).toFloat(), (art.top - bounds.top).toFloat(),
                (art.right - bounds.left).toFloat(), (art.bottom - bounds.top).toFloat())
            node.setPosition(bounds.left, bounds.top, bounds.right, bounds.bottom)
            val recording = node.beginRecording(bounds.width(), bounds.height())
            try {
                paint.alpha = 255
                recording.drawBitmap(bitmap, source, destination, paint)
                val strip = Rect(source.left,
                    (source.bottom - source.height() * 0.15f).roundToInt().coerceIn(source.top, source.bottom - 1),
                    source.right, source.bottom)
                // Mirror just the edge band: its first stretched row meets the sleeve exactly.
                recording.save()
                recording.translate(0f, destination.bottom * 2f)
                recording.scale(1f, -1f)
                recording.drawBitmap(bitmap, strip,
                    RectF(destination.left, destination.bottom - depth, destination.right, destination.bottom), paint)
                recording.restore()
            } finally {
                node.endRecording()
            }
        }
    }

    companion object {
        // Run on the artwork worker, not in the frame loop.
        @JvmStatic
        fun prepareArtwork(drawable: Drawable?): Bitmap? {
            if (drawable == null) return null
            return runCatching {
                val source = if (drawable is BitmapDrawable && drawable.bitmap.config == Bitmap.Config.HARDWARE) {
                    val copy = drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                    BitmapDrawable(copy)
                } else drawable.constantState?.newDrawable()?.mutate() ?: drawable
                val width = source.intrinsicWidth.coerceAtLeast(1)
                val height = source.intrinsicHeight.coerceAtLeast(1)
                val factor = 512f / max(width, height)
                val bitmap = Bitmap.createBitmap((width * factor).roundToInt().coerceAtLeast(1),
                    (height * factor).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val old = Rect(source.bounds)
                try {
                    source.setBounds(0, 0, bitmap.width, bitmap.height)
                    source.draw(Canvas(bitmap))
                } finally {
                    source.bounds = old
                }
                bitmap
            }.getOrNull()
        }
    }
}
