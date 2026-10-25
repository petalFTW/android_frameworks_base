/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.WindowManager
import android.widget.ImageView
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.island.render.RefractionShader
import java.util.function.Consumer
import kotlin.math.sin
import kotlin.math.roundToInt

class FingerprintBlobView(context: Context, attrs: AttributeSet?) : ImageView(context, attrs) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val refraction = RefractionShader()
    private val shape = Path()
    private val rect = RectF()
    private val radii = FloatArray(8)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private var backdrop: BackgroundBlurDrawable? = null
    private var iconType = DeviceEntryIconView.IconType.NONE
    private var aod = false
    private var aggregatedVisible = false
    private var containerAlpha = 1f
    private var framePosted = false
    private var lastFrameAt = 0L
    private var tint = Color.WHITE
    private var gradientWidth = 0
    private var gradientHeight = 0
    private val blurListener = Consumer<Boolean> { updateBackdrop() }
    private val frame = object : Runnable {
        override fun run() {
            framePosted = false
            if (!shouldAnimate()) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt >= 16L) {
                lastFrameAt = now
                invalidate()
            }
            scheduleFrame()
        }
    }

    val minimumGlassSizePx: Int
        get() = (64f * resources.displayMetrics.density + 2f * rim.strokeWidth).roundToInt()

    private val restScale = 0.95f

    init {
        // sit a hair smaller, then pop up when the finger lands
        scaleX = restScale
        scaleY = restScale
    }

    fun setFingerPressed(pressed: Boolean) {
        animate().cancel()
        animate()
            .scaleX(if (pressed) 1f else restScale)
            .scaleY(if (pressed) 1f else restScale)
            .setDuration(if (pressed) 90L else 160L)
            .start()
    }

    fun setIconState(type: DeviceEntryIconView.IconType, useAodVariant: Boolean, color: Int) {
        iconType = type
        aod = useAodVariant
        tint = color
        updateBackdrop()
        updateAnimation()
        invalidate()
    }

    fun setContainerAlpha(value: Float) {
        containerAlpha = value
        updateBackdrop()
        updateAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdrop = viewRootImpl.createBackgroundBlurDrawable().also { it.callback = this }
        windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
        updateBackdrop()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        framePosted = false
        windowManager.removeCrossWindowBlurEnabledListener(blurListener)
        backdrop?.let {
            it.setVisible(false, false)
            it.callback = null
        }
        backdrop = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateBackdrop()
        updateAnimation()
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        who === backdrop || super.verifyDrawable(who)

    private fun shouldAnimate(): Boolean =
        iconType != DeviceEntryIconView.IconType.NONE && !aod && aggregatedVisible && containerAlpha > 0f &&
            isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    private fun scheduleFrame() {
        if (!framePosted && shouldAnimate()) {
            framePosted = true
            postOnAnimation(frame)
        }
    }

    private fun updateAnimation() {
        if (shouldAnimate()) {
            scheduleFrame()
        } else {
            removeCallbacks(frame)
            framePosted = false
        }
    }

    private fun updateBackdrop() {
        val visible = iconType != DeviceEntryIconView.IconType.NONE && !aod &&
            aggregatedVisible && containerAlpha > 0f &&
            windowManager.isCrossWindowBlurEnabled
        backdrop?.let {
            it.setVisible(visible, false)
            it.alpha = (containerAlpha.coerceIn(0f, 1f) * 255).toInt()
            it.setBlurRadius(if (visible) (6f * resources.displayMetrics.density).toInt() else 0)
            if (!visible) refraction.clear(it)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (iconType == DeviceEntryIconView.IconType.NONE) {
            super.onDraw(canvas)
            return
        }
        // Glyph padding was shrinking the whole bloody lens.
        val size = minOf(width, height).toFloat() - 2f * rim.strokeWidth
        if (size <= 0f) return
        val phase = if (shouldAnimate()) {
            (SystemClock.uptimeMillis() % 12_000L) / 12_000f * (Math.PI * 2).toFloat()
        } else 0f
        val cx = width * .5f
        val cy = height * .5f
        val halfWidth = size * .5f
        val halfHeight = halfWidth
        // Keep the lens and the rim on the same damn pixels.
        rect.set((cx - halfWidth).toInt().toFloat(), (cy - halfHeight).toInt().toFloat(),
            (cx + halfWidth).toInt().toFloat(), (cy + halfHeight).toInt().toFloat())
        val span = minOf(rect.width(), rect.height())
        for (corner in 0..3) {
            // Blur's bottom-corner order is wonky; matching them dodges it.
            val offset = minOf(corner, 2) * 1.5707964f
            val radius = span * (.39f + .10f * sin(phase + offset))
            radii[corner * 2] = radius
            radii[corner * 2 + 1] = radius
        }
        shape.rewind()
        shape.addRoundRect(rect, radii, Path.Direction.CW)
        if (!aod) {
            backdrop?.takeIf { it.isVisible }?.let {
                it.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                it.setCornerRadius(radii[0], radii[2], radii[4], radii[6])
                refraction.applyTo(it, rect.width().toInt(), rect.height().toInt(),
                    resources.displayMetrics.density, .75f, .55f)
                it.draw(canvas)
            }
        }
        // The lock/print sits behind the tint, not hidden by an empty blob.
        super.onDraw(canvas)
        if (!aod) {
            fill.color = (tint and 0x00FFFFFF) or 0x24000000
            canvas.drawPath(shape, fill)
        }
        if (gradientWidth != width || gradientHeight != height) {
            gradientWidth = width
            gradientHeight = height
            rim.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xE6FFFFFF.toInt(), 0x407BDFFF, 0xA6FFFFFF.toInt(), 0x807E9CFF.toInt()),
                null, Shader.TileMode.CLAMP)
        }
        if (aod) {
            rim.shader = null
            rim.color = tint
            gradientWidth = 0
        }
        canvas.drawPath(shape, rim)
        scheduleFrame()
    }
}
