package com.android.systemui.petalos

import android.view.View
import com.android.internal.widget.LockPatternView
import com.android.systemui.res.R
import kotlin.math.roundToInt

object PetalPatternStyle {
    const val BACKGROUND_BLUR_FRACTION = 0.05f
    const val DOT_COLOR = 0xFFD7D7DF.toInt()
    const val ACTIVE_COLOR = 0xFFF5F5F8.toInt()
    const val ERROR_COLOR = 0xFFFF9B9B.toInt()
    const val DOT_SIZE_DP = 6
    const val ACTIVE_DOT_SIZE_DP = 9
    const val PATH_WIDTH_DP = 2.4f

    @JvmStatic
    fun applyPattern(view: LockPatternView) {
        val density = view.resources.displayMetrics.density
        val dotSize = (DOT_SIZE_DP * density).roundToInt()
        view.setDotColors(DOT_COLOR, ACTIVE_COLOR)
        view.setColors(ACTIVE_COLOR, ACTIVE_COLOR, ERROR_COLOR)
        view.setDotSizes(dotSize, (ACTIVE_DOT_SIZE_DP * density).roundToInt())
        view.setPathWidth((PATH_WIDTH_DP * density).roundToInt().coerceAtLeast(1))
        view.setLiquidPatternEnabled(true)
        applyGlass(view)
    }

    @JvmStatic
    fun applyGlass(view: View) {
        PetalBouncerGlass.install(view)
    }

    @JvmStatic
    fun fadeOutGlass(view: View) {
        findGlass(view)?.fadeOut()
    }

    @JvmStatic
    fun resetGlass(view: View) {
        findGlass(view)?.reset()
    }

    private fun findGlass(view: View): PetalBouncerGlass? =
        view.background as? PetalBouncerGlass
            ?: view.findViewById<View>(R.id.pin_container)?.background as? PetalBouncerGlass
            ?: view.findViewById<View>(R.id.lockPatternView)?.background as? PetalBouncerGlass
}
