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

package com.android.systemui.island

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.android.systemui.island.cluster.IslandView

/**
 * Hosts both clusters (LEFT + RIGHT) in the island window and restricts the window's touchable
 * region to just the visible islands, so status-bar pulldowns and touches pass through everywhere
 * else (§12.2). Also draws the soft drop shadow that sits just underneath each island.
 */
class IslandRootView(
    context: Context,
    private val geometry: IslandGeometry,
) : FrameLayout(context), ViewTreeObserver.OnComputeInternalInsetsListener {

    val leftIsland = IslandView(context, Cluster.LEFT, geometry)
    val rightIsland = IslandView(context, Cluster.RIGHT, geometry)

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(geometry.dp(4f).toFloat(), BlurMaskFilter.Blur.NORMAL)
    }
    private val shadowPath = Path()
    private val shadowRect = RectF()
    private var dark = true

    init {
        leftIsland.visibility = android.view.View.GONE
        rightIsland.visibility = android.view.View.GONE

        val top = geometry.collapsedTopY
        val sideMargin = geometry.expandedSideMargin

        addView(
            leftIsland,
            IslandView.layoutParams(Gravity.TOP or Gravity.START).apply {
                topMargin = top
                setMarginStart(sideMargin)
            },
        )
        addView(
            rightIsland,
            IslandView.layoutParams(Gravity.TOP or Gravity.END).apply {
                topMargin = top
                setMarginEnd(sideMargin)
            },
        )
    }

    fun setDarkMode(darkTheme: Boolean) {
        if (dark != darkTheme) {
            dark = darkTheme
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        for (island in listOf(leftIsland, rightIsland)) {
            if (island.visibility == View.VISIBLE && island.width > 0 && island.height > 0) {
                drawIslandShadow(canvas, island)
            }
        }
        super.dispatchDraw(canvas)
    }

    private fun drawIslandShadow(canvas: Canvas, island: IslandView) {
        val offsetY = geometry.dp(3f).toFloat()
        val r = island.cornerRadius()
        shadowRect.set(
            island.left.toFloat(),
            island.top + offsetY,
            island.right.toFloat(),
            island.bottom + offsetY,
        )
        shadowPath.reset()
        shadowPath.addRoundRect(shadowRect, r, r, Path.Direction.CW)
        shadowPaint.color = if (dark) 0x33000000.toInt() else 0x1A000000.toInt()
        canvas.drawPath(shadowPath, shadowPaint)
    }

    fun setInsetsListener() {
        viewTreeObserver.addOnComputeInternalInsetsListener(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnComputeInternalInsetsListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnComputeInternalInsetsListener(this)
    }

    override fun onComputeInternalInsets(inout: ViewTreeObserver.InternalInsetsInfo?) {
        inout ?: return
        inout.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
        inout.touchableRegion.setEmpty()
        val r = Rect()
        val pad = geometry.dp(4f)
        for (island in listOf(leftIsland, rightIsland)) {
            if (island.visibility == android.view.View.VISIBLE && island.width > 0) {
                island.getBoundsOnScreen(r)
                r.inset(-pad, -pad)
                inout.touchableRegion.op(r, Region.Op.UNION)
            }
        }
    }
}
