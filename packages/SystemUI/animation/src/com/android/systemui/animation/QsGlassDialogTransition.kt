/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import com.android.app.animation.Interpolators
import com.android.internal.graphics.drawable.PetalGlassDrawable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class QsGlassDialogTransition(
    private val panel: FrameLayout,
    template: PetalGlassDrawable,
    private val sourceBounds: TransitionAnimator.State,
    private val dimAmount: Float,
    private val onReady: () -> Unit,
    private val onOpened: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val density = panel.resources.displayMetrics.density
    private val radius = template.cornerRadii?.get(0) ?: template.cornerRadius
    private val glass = PetalGlassDrawable(panel, radius / density, 0f)
    private val content = FrameLayout(panel.context)
    private val scrim = View(panel.context)
    private var animator: ValueAnimator? = null
    private var ready = false
    private var closing = false
    private var finished = false
    private var originX = 0f
    private var originY = 0f

    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {}

        override fun onViewDetachedFromWindow(view: View) {
            finish()
        }
    }

    init {
        val padding = Rect()
        panel.background?.getPadding(padding)
        template.color?.let { glass.setColor(it) }
        template.cornerRadii?.let { glass.cornerRadii = it }
        template.setBackdropEnabled(false)
        glass.alpha = 0
        panel.background = InsetDrawable(glass,
            padding.left, padding.top, padding.right, padding.bottom)
        panel.clipToOutline = true

        // one real panel, no ghost copy of the tile's text
        while (panel.childCount > 0) {
            val child = panel.getChildAt(0)
            val params = child.layoutParams
            panel.removeViewAt(0)
            content.addView(child, params)
        }
        content.alpha = 0f
        panel.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        scrim.setBackgroundColor(Color.BLACK)
        scrim.alpha = 0f
        scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        (panel.parent as ViewGroup).addView(scrim, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.addOnAttachStateChangeListener(detachListener)
    }

    fun start() {
        panel.doOnLayout {
            // let the fake window finish its fullscreen resize first
            panel.post {
                if (!finished && !closing && panel.isAttachedToWindow) {
                    panel.doOnPreDraw { begin() }
                }
            }
        }
    }

    private fun begin() {
        if (finished || closing || ready) return
        val location = IntArray(2)
        panel.getLocationOnScreen(location)
        val hasSource = sourceBounds.width > 0 && sourceBounds.height > 0
        val sourceX = if (hasSource) (sourceBounds.left + sourceBounds.right) / 2f
            else location[0] + panel.width / 2f
        val sourceY = if (hasSource) (sourceBounds.top + sourceBounds.bottom) / 2f
            else location[1] + panel.height / 2f
        panel.pivotX = (sourceX - location[0]).coerceIn(0f, panel.width.toFloat())
        panel.pivotY = (sourceY - location[1]).coerceIn(0f, panel.height.toFloat())
        originX = ((sourceX - location[0] - panel.width / 2f) * .06f)
            .coerceIn(-24f * density, 24f * density)
        originY = ((sourceY - location[1] - panel.height / 2f) * .06f)
            .coerceIn(-28f * density, 28f * density)
        panel.translationX = originX
        panel.translationY = originY
        panel.scaleX = .96f
        panel.scaleY = .96f
        ready = true
        onReady()
        animate(opening = true)
    }

    fun dismiss(animate: Boolean) {
        if (finished || closing) return
        closing = true
        if (!animate || !ready) finish()
        else this.animate(opening = false)
    }

    private fun animate(opening: Boolean) {
        cancelAnimator()
        val startAlpha = glass.alpha / 255f
        val startContentAlpha = content.alpha
        val startDim = scrim.alpha
        val startX = panel.translationX
        val startY = panel.translationY
        val startScaleX = panel.scaleX
        val startScaleY = panel.scaleY
        val endAlpha = if (opening) 1f else 0f
        val endX = if (opening) 0f else originX
        val endY = if (opening) 0f else originY
        // predictive back may have already shrunk it, don't jump it larger
        val endScaleX = if (opening) 1f else min(.96f, startScaleX * .98f)
        val endScaleY = if (opening) 1f else min(.96f, startScaleY * .98f)
        val animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (opening) 280L else max(90L, (200L * startAlpha).roundToInt().toLong())
            interpolator = if (opening) Interpolators.FAST_OUT_SLOW_IN
                else Interpolators.EMPHASIZED_ACCELERATE
            addUpdateListener {
                val fraction = it.animatedValue as Float
                glass.alpha = (lerp(startAlpha, endAlpha, fraction) * 255f).roundToInt()
                content.alpha = lerp(startContentAlpha, endAlpha, fraction)
                scrim.alpha = lerp(startDim, if (opening) dimAmount else 0f, fraction)
                panel.translationX = lerp(startX, endX, fraction)
                panel.translationY = lerp(startY, endY, fraction)
                panel.scaleX = lerp(startScaleX, endScaleX, fraction)
                panel.scaleY = lerp(startScaleY, endScaleY, fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (opening) onOpened() else finish()
                }
            })
        }
        animator = animation
        animation.start()
    }

    private fun cancelAnimator() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    private fun finish() {
        if (finished) return
        finished = true
        cancelAnimator()
        panel.removeOnAttachStateChangeListener(detachListener)
        (scrim.parent as? ViewGroup)?.removeView(scrim)
        glass.setBackdropEnabled(false)
        onClosed()
    }

    private fun lerp(start: Float, end: Float, fraction: Float) =
        start + (end - start) * fraction
}
