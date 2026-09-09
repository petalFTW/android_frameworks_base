package com.android.systemui.petalos

import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import com.android.internal.widget.LockPatternView
import com.android.systemui.island.render.LiquidGlassDrawable
import java.util.function.Consumer
import kotlin.math.roundToInt

object PetalPatternStyle {
    const val DOT_COLOR = 0xFFD7D7DF.toInt()
    const val ACTIVE_COLOR = 0xFFF5F5F8.toInt()
    const val ERROR_COLOR = 0xFFFF9B9B.toInt()
    const val DOT_SIZE_DP = 6
    const val ACTIVE_DOT_SIZE_DP = 9
    const val PATH_WIDTH_DP = 1.5f

    @JvmStatic
    fun applyPattern(view: LockPatternView) {
        val density = view.resources.displayMetrics.density
        val dotSize = (DOT_SIZE_DP * density).roundToInt()
        view.setDotColors(DOT_COLOR, ACTIVE_COLOR)
        view.setColors(ACTIVE_COLOR, ACTIVE_COLOR, ERROR_COLOR)
        view.setDotSizes(dotSize, (ACTIVE_DOT_SIZE_DP * density).roundToInt())
        view.setPathWidth((PATH_WIDTH_DP * density).roundToInt().coerceAtLeast(1))
        applyGlass(view)
    }

    @JvmStatic
    fun applyGlass(view: View) {
        val density = view.resources.displayMetrics.density
        val radius = 32f * density
        val glass = LiquidGlassDrawable().apply {
            setCornerRadius(radius)
            setRimWidth(density)
            setRim(0x40FFFFFF, 0x0CFFFFFF)
            setGrainAlpha(0x10)
            setTintColor(0xF2101012.toInt())
        }
        view.background = glass
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, radius)
            }
        }
        view.clipToOutline = true
        val windowManager = view.context.getSystemService(WindowManager::class.java)
        val blur = RenderEffect.createBlurEffect(28f * density, 28f * density,
            Shader.TileMode.CLAMP)
        val listener = Consumer<Boolean> { enabled ->
            if (view.isAttachedToWindow) {
                view.setBackdropRenderEffect(if (enabled) blur else null)
                glass.setTintColor(if (enabled) 0xC0101012.toInt() else 0xF2101012.toInt())
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(target: View) {
                windowManager.addCrossWindowBlurEnabledListener(view.context.mainExecutor, listener)
            }

            override fun onViewDetachedFromWindow(target: View) {
                windowManager.removeCrossWindowBlurEnabledListener(listener)
                target.setBackdropRenderEffect(null)
            }
        })
        if (view.isAttachedToWindow) {
            windowManager.addCrossWindowBlurEnabledListener(view.context.mainExecutor, listener)
        }
    }
}
