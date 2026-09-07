/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.hardware.graphics.common.AlphaInterpretation
import android.hardware.graphics.common.DisplayDecorationSupport
import android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM
import android.view.DisplayCutout.BOUNDS_POSITION_LEFT
import android.view.DisplayCutout.BOUNDS_POSITION_LENGTH
import android.view.DisplayCutout.BOUNDS_POSITION_TOP
import android.view.DisplayCutout.BOUNDS_POSITION_RIGHT
import android.view.RoundedCorner
import android.view.RoundedCorners
import android.view.Surface
import android.view.animation.LinearInterpolator
import androidx.annotation.VisibleForTesting
import com.android.systemui.petalos.PetalUiConfig
import com.android.systemui.util.asIndenting
import java.io.PrintWriter
import kotlin.math.ceil
import kotlin.math.floor

// This thing draws hardware screen decorations.
class ScreenDecorHwcLayer(
    context: Context,
    displayDecorationSupport: DisplayDecorationSupport,
    private val debug: Boolean,
) : DisplayCutoutBaseView(context) {
    val colorMode: Int
    private val useInvertedAlphaColor: Boolean
    private var color: Int = Color.BLACK
        set(value) {
            field = value
            paint.color = value
        }

    private val bgColor: Int
    private var cornerFilter: ColorFilter
    private val cornerBgFilter: ColorFilter
    private val clearPaint: Paint
    @JvmField val transparentRect: Rect = Rect()
    private val debugTransparentRegionPaint: Paint?
    private val tempRect: Rect = Rect()

    private var hasTopRoundedCorner = false
    private var hasBottomRoundedCorner = false
    private var roundedCornerTopSize = 0
    private var roundedCornerBottomSize = 0
    private var roundedCornerDrawableTop: Drawable? = null
    private var roundedCornerDrawableBottom: Drawable? = null

    private val extraKeyPath = Path()
    private val extraKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var extraKeyAnimator: ValueAnimator? = null
    private var extraKeyProgress = 0f
    private var extraKeyLeft = true
    private var extraKeyAnchor = 0.25f

    // One push per press on the decor thread.
    fun pulseExtraKey() {
        if (!isAttachedToWindow || pendingConfigChange || !ValueAnimator.areAnimatorsEnabled()) {
            return
        }
        extraKeyAnimator?.cancel()
        extraKeyLeft = PetalUiConfig.isExtraKeyEdgeLeft(context)
        extraKeyAnchor = PetalUiConfig.getExtraKeyAnchorFraction(context)
        extraKeyAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Push for 100 ms, return for 200 ms.
                val phase = if (t < 1f / 3f) t * 3f else (1f - t) * 1.5f
                extraKeyProgress = phase * phase * (3f - 2f * phase)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    extraKeyProgress = 0f
                    extraKeyAnimator = null
                    requestLayout()
                    parent?.requestTransparentRegion(this@ScreenDecorHwcLayer)
                    invalidate()
                }
            })
            start()
        }
        requestLayout()
        parent?.requestTransparentRegion(this)
    }

    override fun onDetachedFromWindow() {
        extraKeyAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun drawExtraKey(canvas: Canvas) {
        if (extraKeyProgress <= 0f || pendingConfigChange) return
        val landscape = displayRotation == Surface.ROTATION_90 ||
            displayRotation == Surface.ROTATION_270
        val naturalWidth = (if (landscape) height else width).toFloat()
        val naturalHeight = (if (landscape) width else height).toFloat()
        val halfHeight = minOf(24f * resources.displayMetrics.density, naturalHeight / 2f)
        val cy = (naturalHeight * extraKeyAnchor).coerceIn(halfHeight, naturalHeight - halfHeight)
        val depth = 6f * resources.displayMetrics.density * extraKeyProgress
        val saved = canvas.save()
        // Keep this thing beside the physical key.
        when (displayRotation) {
            Surface.ROTATION_90 -> { canvas.translate(0f, height.toFloat()); canvas.rotate(-90f) }
            Surface.ROTATION_180 -> { canvas.translate(width.toFloat(), height.toFloat()); canvas.rotate(180f) }
            Surface.ROTATION_270 -> { canvas.translate(width.toFloat(), 0f); canvas.rotate(90f) }
        }
        if (!extraKeyLeft) { canvas.translate(naturalWidth, 0f); canvas.scale(-1f, 1f) }
        extraKeyPath.rewind()
        extraKeyPath.moveTo(0f, cy - halfHeight)
        extraKeyPath.cubicTo(0f, cy - halfHeight * 0.70f,
            depth, cy - halfHeight * 0.80f, depth, cy - halfHeight * 0.35f)
        extraKeyPath.lineTo(depth, cy + halfHeight * 0.35f)
        extraKeyPath.cubicTo(depth, cy + halfHeight * 0.80f,
            0f, cy + halfHeight * 0.70f, 0f, cy + halfHeight)
        extraKeyPath.close()
        // Keep the layer alpha mode.
        extraKeyPaint.set(paint)
        extraKeyPaint.isAntiAlias = true
        canvas.drawPath(extraKeyPath, extraKeyPaint)
        canvas.restoreToCount(saved)
    }

    init {
        if (displayDecorationSupport.format != PixelFormat.R_8) {
            throw IllegalArgumentException("Attempting to use unsupported mode " +
                    "${PixelFormat.formatToString(displayDecorationSupport.format)}")
        }
        if (debug) {
            color = Color.GREEN
            bgColor = Color.TRANSPARENT
            colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            useInvertedAlphaColor = false
            debugTransparentRegionPaint = Paint().apply {
                color = 0x2f00ff00 // semi-transparent green
                style = Paint.Style.FILL
            }
        } else {
            colorMode = ActivityInfo.COLOR_MODE_A8
            useInvertedAlphaColor = displayDecorationSupport.alphaInterpretation ==
                    AlphaInterpretation.COVERAGE
            if (useInvertedAlphaColor) {
                color = Color.TRANSPARENT
                bgColor = Color.BLACK
            } else {
                color = Color.BLACK
                bgColor = Color.TRANSPARENT
            }
            debugTransparentRegionPaint = null
        }
        cornerFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        cornerBgFilter = PorterDuffColorFilter(bgColor, PorterDuff.Mode.SRC_OUT)

        clearPaint = Paint()
        clearPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        parent.requestTransparentRegion(this)
        updateColors()
    }

    private fun updateColors() {
        if (!debug) {
            viewRootImpl.setDisplayDecoration(true)
        }

        cornerFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        if (useInvertedAlphaColor) {
            paint.set(clearPaint)
        } else {
            paint.color = color
            paint.style = Paint.Style.FILL
        }
    }

    fun setDebugColor(color: Int) {
        if (!debug) {
            return
        }

        if (this.color == color) {
            return
        }

        this.color = color

        updateColors()
        invalidate()
    }

    override fun onUpdate() {
        parent.requestTransparentRegion(this)
    }

    override fun onDraw(canvas: Canvas) {
        // If updating onDraw, also update gatherTransparentRegion
        if (useInvertedAlphaColor) {
            canvas.drawColor(bgColor)
        }

        // Draw corners first so clearing cannot erase the cutout.
        drawRoundedCorners(canvas)
        // Cutouts are drawn in DisplayCutoutBaseView.onDraw()
        super.onDraw(canvas)
        drawExtraKey(canvas)

        debugTransparentRegionPaint?.let {
            canvas.drawRect(transparentRect, it)
        }
    }

    override fun gatherTransparentRegion(region: Region?): Boolean {
        region?.let {
            calculateTransparentRect()
            if (debug) {
                // Debug fills the layer, so nothing stays transparent.
                region.setEmpty()
            } else {
                region.op(transparentRect, Region.Op.INTERSECT)
            }
        }
        // Always return false - views underneath this should always be visible.
        return false
    }

    // Remove decorations from the transparent region.
    @VisibleForTesting
    fun calculateTransparentRect() {
        transparentRect.set(0, 0, width, height)

        // Remove cutout region.
        removeCutoutFromTransparentRegion()

        // Remove cutout protection region.
        removeCutoutProtectionFromTransparentRegion()

        // Remove rounded corner region.
        removeRoundedCornersFromTransparentRegion()

        // Reserve the whole bend once per pulse.
        if (extraKeyAnimator != null) {
            val depth = ceil(6f * resources.displayMetrics.density).toInt() + 1
            val naturalEdge = if (extraKeyLeft) BOUNDS_POSITION_LEFT else BOUNDS_POSITION_RIGHT
            when ((naturalEdge - displayRotation + BOUNDS_POSITION_LENGTH) % BOUNDS_POSITION_LENGTH) {
                BOUNDS_POSITION_LEFT -> transparentRect.left = maxOf(transparentRect.left, depth)
                BOUNDS_POSITION_TOP -> transparentRect.top = maxOf(transparentRect.top, depth)
                BOUNDS_POSITION_RIGHT -> transparentRect.right = minOf(transparentRect.right, width - depth)
                BOUNDS_POSITION_BOTTOM -> transparentRect.bottom = minOf(transparentRect.bottom, height - depth)
            }
        }
    }

    private fun removeCutoutFromTransparentRegion() {
        displayInfo.displayCutout?.let {
                cutout ->
            if (!cutout.boundingRectLeft.isEmpty) {
                transparentRect.left =
                    cutout.boundingRectLeft.right.coerceAtLeast(transparentRect.left)
            }
            if (!cutout.boundingRectTop.isEmpty) {
                transparentRect.top =
                    cutout.boundingRectTop.bottom.coerceAtLeast(transparentRect.top)
            }
            if (!cutout.boundingRectRight.isEmpty) {
                transparentRect.right =
                    cutout.boundingRectRight.left.coerceAtMost(transparentRect.right)
            }
            if (!cutout.boundingRectBottom.isEmpty) {
                transparentRect.bottom =
                    cutout.boundingRectBottom.top.coerceAtMost(transparentRect.bottom)
            }
        }
    }

    private fun removeCutoutProtectionFromTransparentRegion() {
        if (protectionRect.isEmpty) {
            return
        }

        val centerX = protectionRect.centerX()
        val centerY = protectionRect.centerY()
        val scaledDistanceX = (centerX - protectionRect.left) * cameraProtectionProgress
        val scaledDistanceY = (centerY - protectionRect.top) * cameraProtectionProgress
        tempRect.set(
            floor(centerX - scaledDistanceX).toInt(),
            floor(centerY - scaledDistanceY).toInt(),
            ceil(centerX + scaledDistanceX).toInt(),
            ceil(centerY + scaledDistanceY).toInt()
        )

        // Remove the protected edge from the transparent region.
        val leftDistance = tempRect.left
        val topDistance = tempRect.top
        val rightDistance = width - tempRect.right
        val bottomDistance = height - tempRect.bottom
        val minDistance = minOf(leftDistance, topDistance, rightDistance, bottomDistance)
        when (minDistance) {
            leftDistance -> {
                transparentRect.left = tempRect.right.coerceAtLeast(transparentRect.left)
            }
            topDistance -> {
                transparentRect.top = tempRect.bottom.coerceAtLeast(transparentRect.top)
            }
            rightDistance -> {
                transparentRect.right = tempRect.left.coerceAtMost(transparentRect.right)
            }
            bottomDistance -> {
                transparentRect.bottom = tempRect.top.coerceAtMost(transparentRect.bottom)
            }
        }
    }

    private fun removeRoundedCornersFromTransparentRegion() {
        var hasTopOrBottomCutouts = false
        var hasLeftOrRightCutouts = false
        displayInfo.displayCutout?.let {
                cutout ->
            hasTopOrBottomCutouts = !cutout.boundingRectTop.isEmpty ||
                    !cutout.boundingRectBottom.isEmpty
            hasLeftOrRightCutouts = !cutout.boundingRectLeft.isEmpty ||
                    !cutout.boundingRectRight.isEmpty
        }
        // Trim short edges to keep more of this thing transparent.
        val isShortEdgeTopBottom = width < height
        if (isShortEdgeTopBottom) {
            // Short edges on top & bottom.
            if (!hasTopOrBottomCutouts && hasLeftOrRightCutouts) {
                // Side cutouts need side trims for corners.
                transparentRect.left = getRoundedCornerSizeByPosition(BOUNDS_POSITION_LEFT)
                    .coerceAtLeast(transparentRect.left)
                transparentRect.right =
                    (width - getRoundedCornerSizeByPosition(BOUNDS_POSITION_RIGHT))
                        .coerceAtMost(transparentRect.right)
            } else {
                // Otherwise trim top and bottom for corners.
                transparentRect.top = getRoundedCornerSizeByPosition(BOUNDS_POSITION_TOP)
                    .coerceAtLeast(transparentRect.top)
                transparentRect.bottom =
                    (height - getRoundedCornerSizeByPosition(BOUNDS_POSITION_BOTTOM))
                        .coerceAtMost(transparentRect.bottom)
            }
        } else {
            // Short edges on left & right.
            if (hasTopOrBottomCutouts && !hasLeftOrRightCutouts) {
                // If there are cutouts only on top or bottom edges, remove top and bottom sides
                // for rounded corners.
                transparentRect.top = getRoundedCornerSizeByPosition(BOUNDS_POSITION_TOP)
                    .coerceAtLeast(transparentRect.top)
                transparentRect.bottom =
                    (height - getRoundedCornerSizeByPosition(BOUNDS_POSITION_BOTTOM))
                        .coerceAtMost(transparentRect.bottom)
            } else {
                // If there are cutouts on left or right edges or no cutout at all, remove left
                // and right sides for rounded corners.
                transparentRect.left = getRoundedCornerSizeByPosition(BOUNDS_POSITION_LEFT)
                    .coerceAtLeast(transparentRect.left)
                transparentRect.right =
                    (width - getRoundedCornerSizeByPosition(BOUNDS_POSITION_RIGHT))
                        .coerceAtMost(transparentRect.right)
            }
        }
    }

    private fun getRoundedCornerSizeByPosition(position: Int): Int {
        val delta = displayRotation - Surface.ROTATION_0
        return when ((position + delta) % BOUNDS_POSITION_LENGTH) {
            BOUNDS_POSITION_LEFT -> roundedCornerTopSize.coerceAtLeast(roundedCornerBottomSize)
            BOUNDS_POSITION_TOP -> roundedCornerTopSize
            BOUNDS_POSITION_RIGHT -> roundedCornerTopSize.coerceAtLeast(roundedCornerBottomSize)
            BOUNDS_POSITION_BOTTOM -> roundedCornerBottomSize
            else -> throw IllegalArgumentException("Incorrect position: $position")
        }
    }

    private fun drawRoundedCorners(canvas: Canvas) {
        if (!hasTopRoundedCorner && !hasBottomRoundedCorner) {
            return
        }
        var degree: Int
        for (i in RoundedCorner.POSITION_TOP_LEFT
                until RoundedCorners.ROUNDED_CORNER_POSITION_LENGTH) {
            canvas.save()
            degree = getRoundedCornerRotationDegree(90 * i)
            canvas.rotate(degree.toFloat())
            canvas.translate(
                    getRoundedCornerTranslationX(degree).toFloat(),
                    getRoundedCornerTranslationY(degree).toFloat())
            if (hasTopRoundedCorner && (i == RoundedCorner.POSITION_TOP_LEFT ||
                            i == RoundedCorner.POSITION_TOP_RIGHT)) {
                drawRoundedCorner(canvas, roundedCornerDrawableTop, roundedCornerTopSize)
            } else if (hasBottomRoundedCorner && (i == RoundedCorner.POSITION_BOTTOM_LEFT ||
                            i == RoundedCorner.POSITION_BOTTOM_RIGHT)) {
                drawRoundedCorner(canvas, roundedCornerDrawableBottom, roundedCornerBottomSize)
            }
            canvas.restore()
        }
    }

    private fun drawRoundedCorner(canvas: Canvas, drawable: Drawable?, size: Int) {
        if (useInvertedAlphaColor) {
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), clearPaint)
            drawable?.colorFilter = cornerBgFilter
        } else {
            drawable?.colorFilter = cornerFilter
        }
        drawable?.draw(canvas)
        // Clear color filter when we are done with drawing.
        drawable?.clearColorFilter()
    }

    private fun getRoundedCornerRotationDegree(defaultDegree: Int): Int {
        return (defaultDegree - 90 * displayRotation + 360) % 360
    }

    private fun getRoundedCornerTranslationX(degree: Int): Int {
        return when (degree) {
            0, 90 -> 0
            180 -> -width
            270 -> -height
            else -> throw IllegalArgumentException("Incorrect degree: $degree")
        }
    }

    private fun getRoundedCornerTranslationY(degree: Int): Int {
        return when (degree) {
            0, 270 -> 0
            90 -> -width
            180 -> -height
            else -> throw IllegalArgumentException("Incorrect degree: $degree")
        }
    }

    /**
     * Update the rounded corner drawables.
     */
    fun updateRoundedCornerDrawable(top: Drawable?, bottom: Drawable?) {
        roundedCornerDrawableTop = top
        roundedCornerDrawableBottom = bottom
        updateRoundedCornerDrawableBounds()
        invalidate()
    }

    /**
     * Update the rounded corner existence and size.
     */
    fun updateRoundedCornerExistenceAndSize(
        hasTop: Boolean,
        hasBottom: Boolean,
        topSize: Int,
        bottomSize: Int
    ) {
        if (hasTopRoundedCorner == hasTop &&
                hasBottomRoundedCorner == hasBottom &&
                roundedCornerTopSize == topSize &&
                roundedCornerBottomSize == bottomSize) {
            return
        }
        hasTopRoundedCorner = hasTop
        hasBottomRoundedCorner = hasBottom
        roundedCornerTopSize = topSize
        roundedCornerBottomSize = bottomSize
        updateRoundedCornerDrawableBounds()

        // Use requestLayout() to trigger transparent region recalculated
        requestLayout()
    }

    private fun updateRoundedCornerDrawableBounds() {
        if (roundedCornerDrawableTop != null) {
            roundedCornerDrawableTop?.setBounds(0, 0, roundedCornerTopSize,
                    roundedCornerTopSize)
        }
        if (roundedCornerDrawableBottom != null) {
            roundedCornerDrawableBottom?.setBounds(0, 0, roundedCornerBottomSize,
                    roundedCornerBottomSize)
        }
        invalidate()
    }

    override fun dump(pw: PrintWriter) {
        val ipw = pw.asIndenting()
        ipw.increaseIndent()
        ipw.println("ScreenDecorHwcLayer:")
        super.dump(pw)
        ipw.println("this=$this")
        ipw.println("transparentRect=$transparentRect")
        ipw.println("hasTopRoundedCorner=$hasTopRoundedCorner")
        ipw.println("hasBottomRoundedCorner=$hasBottomRoundedCorner")
        ipw.println("roundedCornerTopSize=$roundedCornerTopSize")
        ipw.println("roundedCornerBottomSize=$roundedCornerBottomSize")
        ipw.decreaseIndent()
    }
}
