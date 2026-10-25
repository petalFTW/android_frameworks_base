/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.WindowManager
import com.android.systemui.res.R
import java.util.function.Consumer

// Blur what's behind the card, not the bloody sleeve and buttons.
object PetalMediaBackdrop {
    @JvmStatic
    @JvmOverloads
    fun setEnabled(view: View, enabled: Boolean, blurRadiusDp: Float = 28f) {
        val binding = view.getTag(R.id.petal_media_backdrop) as? Binding ?: Binding(view).also {
            view.setTag(R.id.petal_media_backdrop, it)
        }
        binding.setEnabled(enabled, blurRadiusDp)
    }

    private class Binding(private val view: View) : View.OnAttachStateChangeListener,
        View.OnLayoutChangeListener {
        private val windowManager = view.context.getSystemService(WindowManager::class.java)
        private var radiusDp = 28f
        private var appliedRadius = -1
        private val listener = Consumer<Boolean> { refresh() }
        private var enabled = false
        private var listening = false
        private var applied = false

        init {
            view.addOnAttachStateChangeListener(this)
            view.addOnLayoutChangeListener(this)
        }

        fun setEnabled(value: Boolean, blurRadiusDp: Float = radiusDp) {
            enabled = value
            radiusDp = if (blurRadiusDp.isFinite()) blurRadiusDp.coerceAtLeast(0f) else 28f
            if (enabled && view.isAttachedToWindow && !listening) {
                listening = true
                windowManager.addCrossWindowBlurEnabledListener(view.context.mainExecutor, listener)
            } else if (!enabled && listening) {
                windowManager.removeCrossWindowBlurEnabledListener(listener)
                listening = false
            }
            refresh()
        }

        private fun refresh(force: Boolean = false) {
            val radius = kotlin.math.round(radiusDp * view.resources.displayMetrics.density).toInt()
            // Within-window backdrop blur, so this must not wait on cross-window blur support.
            val active = enabled && radius > 0 && view.isAttachedToWindow
            if (!force && applied == active && (!active || appliedRadius == radius)) return
            applied = active
            appliedRadius = if (active) radius else -1
            view.setBackdropRenderEffect(if (active) {
                RenderEffect.createBlurEffect(radius.toFloat(), radius.toFloat(), Shader.TileMode.CLAMP)
            } else null)
            view.invalidateOutline()
            view.invalidate()
        }

        override fun onLayoutChange(
            view: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
        ) {
            if (enabled && (right - left != oldRight - oldLeft ||
                    bottom - top != oldBottom - oldTop)) refresh(force = true)
        }

        override fun onViewAttachedToWindow(view: View) = setEnabled(enabled)

        override fun onViewDetachedFromWindow(view: View) {
            if (listening) windowManager.removeCrossWindowBlurEnabledListener(listener)
            listening = false
            applied = false
            appliedRadius = -1
            view.setBackdropRenderEffect(null)
        }
    }
}
