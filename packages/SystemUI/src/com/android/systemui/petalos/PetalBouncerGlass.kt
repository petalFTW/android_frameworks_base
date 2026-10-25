/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.systemui.island.IslandConstants
import com.android.systemui.island.render.LiquidGlassDrawable
import com.android.systemui.island.render.RefractionShader
import com.android.systemui.res.R
import java.util.function.Consumer
import kotlin.math.roundToInt
import org.petalos.config.PetalConfig

// Same lens as the blob, minus the dark paint slapped over it.
internal class PetalBouncerGlass private constructor(private val view: View) :
    Drawable(), Drawable.Callback, View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private val windowManager = view.context.getSystemService(WindowManager::class.java)
    private val glass = LiquidGlassDrawable()
    private val lens = RefractionShader()
    private val panel = Rect()
    private val childBounds = Rect()
    private var backdrop: BackgroundBlurDrawable? = null
    private var opacity = 255
    private var dismissing = false
    private var fadeAnimator: ValueAnimator? = null
    private var treeObserver: ViewTreeObserver? = null
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        syncBackdropAlpha()
        true
    }
    private val blurListener = Consumer<Boolean> { refresh() }

    init {
        val density = view.resources.displayMetrics.density
        glass.setCornerRadius(32f * density)
        glass.setTintColor(Color.TRANSPARENT)
        glass.setRim(0x40FFFFFF, 0x0CFFFFFF)
        glass.setRimWidth(density)
        glass.setGrainAlpha(0)
        glass.setSpecularProgress(0.35f)
        glass.callback = this
    }

    override fun onViewAttachedToWindow(target: View) {
        backdrop = target.viewRootImpl.createBackgroundBlurDrawable().also {
            it.callback = this
            it.setVisible(isVisible, false)
        }
        windowManager.addCrossWindowBlurEnabledListener(target.context.mainExecutor, blurListener)
        treeObserver = target.viewTreeObserver.also { it.addOnPreDrawListener(preDrawListener) }
        reset()
    }

    override fun onViewDetachedFromWindow(target: View) {
        windowManager.removeCrossWindowBlurEnabledListener(blurListener)
        treeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        treeObserver = null
        fadeAnimator?.cancel()
        fadeAnimator = null
        backdrop?.let {
            it.setBlurRadius(0)
            lens.clear(it)
            it.setVisible(false, false)
            it.callback = null
        }
        backdrop = null
    }

    fun fadeOut() {
        if (dismissing) return
        dismissing = true
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isShown) {
            setAlpha(0)
            return
        }
        fadeAnimator = ValueAnimator.ofInt(opacity, 0).apply {
            duration = 100L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { this@PetalBouncerGlass.setAlpha(it.animatedValue as Int) }
            start()
        }
    }

    fun reset() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        dismissing = false
        setAlpha(255)
        if (view.isAttachedToWindow) refresh()
    }

    private fun syncBackdropAlpha() {
        val blur = backdrop ?: return
        // Parent transitions were killing the blur while the rim was still on screen.
        val effectiveOpacity = if (isVisible && view.isShown) opacity else 0
        blur.alpha = effectiveOpacity
        blur.setVisible(effectiveOpacity > 0, false)
    }

    override fun onLayoutChange(
        target: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
    ) = refresh()

    private fun refresh() {
        panel.set(0, 0, view.width, view.height)
        if (view.id == R.id.pin_container && view is ViewGroup) {
            panel.setEmpty()
            for (id in PIN_CONTENT) {
                val child = view.findViewById<View>(id) ?: continue
                if (child.width == 0 || child.height == 0 || child.visibility == View.GONE) continue
                childBounds.set(0, 0, child.width, child.height)
                view.offsetDescendantRectToMyCoords(child, childBounds)
                panel.union(childBounds)
            }
            if (!panel.isEmpty) {
                val padding = (12f * view.resources.displayMetrics.density).roundToInt()
                panel.inset(-padding, -padding)
                panel.intersect(0, 0, view.width, view.height)
            }
        }
        val density = view.resources.displayMetrics.density
        val radius = 32f * density
        val enabled = windowManager.isCrossWindowBlurEnabled
        val strength = if (enabled && PetalConfig.isIslandBubbleEnabled(view.context)) {
            PetalConfig.getIslandPopoutScale(view.context)
        } else 0f
        glass.bounds = panel
        glass.setCornerRadius(radius)
        glass.setSlab(lens.bandFor(panel.width(), panel.height(), density), strength)
        backdrop?.let {
            it.bounds = panel
            it.setCornerRadius(radius)
            it.setBlurRadius(if (enabled) {
                (IslandConstants.BLUR_BEHIND_RADIUS_DP * density *
                    PetalConfig.getIslandBlurScale(view.context)).roundToInt()
            } else 0)
            lens.applyTo(it, panel.width(), panel.height(), density, strength,
                PetalConfig.getIslandChromaScale(view.context))
        }
        syncBackdropAlpha()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (panel.isEmpty || !isVisible || opacity == 0) return
        syncBackdropAlpha()
        if (canvas.isHardwareAccelerated) backdrop?.draw(canvas)
        val save = if (opacity < 255) canvas.saveLayerAlpha(null, opacity) else canvas.save()
        glass.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        backdrop?.setVisible(visible, restart)
        glass.setVisible(visible, restart)
        if (visible && changed) reset()
        else if (visible && view.isAttachedToWindow) refresh()
        return changed
    }

    override fun onBoundsChange(bounds: Rect) = refresh()
    override fun setAlpha(alpha: Int) {
        opacity = alpha.coerceIn(0, 255)
        syncBackdropAlpha()
        invalidateSelf()
    }
    override fun getAlpha(): Int = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) { glass.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = scheduleSelf(what, at)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    companion object {
        private val PIN_CONTENT = intArrayOf(R.id.row0, R.id.key1, R.id.key2, R.id.key3,
            R.id.key4, R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9,
            R.id.delete_button, R.id.key0, R.id.key_enter)

        fun install(view: View) {
            if (view.background is PetalBouncerGlass) return
            val background = PetalBouncerGlass(view)
            view.background = background
            view.addOnAttachStateChangeListener(background)
            view.addOnLayoutChangeListener(background)
            if (view.isAttachedToWindow) background.onViewAttachedToWindow(view)
        }
    }
}
