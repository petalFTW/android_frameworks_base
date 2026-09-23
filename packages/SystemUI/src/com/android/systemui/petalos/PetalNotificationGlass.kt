/*
 * Copyright (C) 2026 petalOS
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

package com.android.systemui.petalos

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.provider.Settings
import com.android.systemui.island.render.LiquidGlassDrawable
import com.android.systemui.island.settings.IslandSettings
import com.android.systemui.res.R

// island glass pill for the notification footer buttons
object PetalNotificationGlass {

    /** same secure flag as the island glass notifications */
    @JvmStatic
    fun enabled(context: Context): Boolean =
        Settings.Secure.getInt(
            context.contentResolver,
            IslandSettings.KEY_GLASS_NOTIFICATIONS,
            1,
        ) != 0

    /** glass pill background, with the press feedback baked in so we dont fight a ripple */
    @JvmStatic
    fun footerButtonBackground(context: Context): Drawable = PetalFooterGlassDrawable(context)
}

class PetalFooterGlassDrawable(private val context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val dark = PetalQsSkin.isDark(context)
    private val glass = LiquidGlassDrawable()
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = RectF()
    private val corner = context.resources.getDimension(R.dimen.island_corner_expanded)
    private val insetTop = 3f * density
    private val insetBottom = 6f * density

    private var pressed = false

    init {
        glass.setCornerRadius(corner)
        glass.setRimWidth(1.1f * density)
        glass.setTintColor(if (dark) 0xCC2A2A31.toInt() else 0xE6F4F5FA.toInt())
        glass.setRim(
            if (dark) 0x66FFFFFF.toInt() else 0xFFFFFFFF.toInt(),
            if (dark) 0x1AFFFFFF.toInt() else 0x1F000000.toInt(),
        )
        glass.setGrainAlpha(if (dark) 0x14 else 0x10)
        glass.setSheenStrength(0x55)
        glass.setSpecularProgress(0.16f)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        inner.set(b.left.toFloat(), b.top + insetTop, b.right.toFloat(), b.bottom - insetBottom)
        glass.setBounds(inner.left.toInt(), inner.top.toInt(), inner.right.toInt(), inner.bottom.toInt())
        glass.draw(canvas)
        if (pressed) {
            pressPaint.color = if (dark) 0x33FFFFFF.toInt() else 0x24000000.toInt()
            canvas.drawRoundRect(inner, corner, corner, pressPaint)
        }
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = state.contains(android.R.attr.state_pressed)
        if (nowPressed != pressed) {
            pressed = nowPressed
            invalidateSelf()
            return true
        }
        return super.onStateChange(state)
    }

    override fun getPadding(padding: Rect): Boolean {
        val p = (20 * density).toInt()
        padding.set(p, 0, p, 0)
        return true
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
