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

package com.android.systemui.petalos.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.android.systemui.res.R

/**
 * The subject layer of the petalOS 3D depth lock screen.
 *
 * Draws the segmented wallpaper subject (RGBA cutout, screen-sized by construction — the depth
 * editor crops the photo exactly like the lock wallpaper it sets) on top of the keyguard clock.
 * Notifications render above this view because they live in a sibling container of
 * [com.android.systemui.keyguard.ui.view.KeyguardRootView].
 *
 * The layer is display-only: touches fall through to views underneath.
 */
class PetalDepthSubjectView(context: Context) : View(context) {

    companion object {
        /** Elevation keeps the layer above every sibling in the keyguard root. */
        const val LAYER_ELEVATION = 100f
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dstRect = RectF()

    private var subject: Bitmap? = null

    /** True when a cutout is loaded (controller checks before showing the layer). */
    val hasSubject: Boolean
        get() = subject != null

    /** Dimensions for dump output only. */
    fun subjectForDump(): Bitmap? = subject

    /** Smoothed parallax offsets in px (already scaled by density by the controller). */
    private var tiltX = 0f
    private var tiltY = 0f

    init {
        id = R.id.petal_depth_subject
        elevation = LAYER_ELEVATION
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        visibility = GONE
    }

    fun setSubject(bitmap: Bitmap?) {
        subject = bitmap
        invalidate()
    }

    /** Sets the smoothed parallax offsets in pixels. */
    fun setTilt(px: Float, py: Float) {
        if (tiltX != px || tiltY != py) {
            tiltX = px
            tiltY = py
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = subject ?: return
        if (width <= 0 || height <= 0) return

        // Keep a 1:1 mapping with the lock wallpaper. Scaling the transparent full-screen cutout
        // by 1.05 made the same pixels sit at different coordinates even when motion was zero.
        dstRect.set(tiltX, tiltY, width + tiltX, height + tiltY)
        canvas.drawBitmap(bitmap, null, dstRect, paint)
    }

    /** Convenience for the controller: size the layer to fill the keyguard root. */
    fun attachToRoot(root: ViewGroup) {
        val cl = root as androidx.constraintlayout.widget.ConstraintLayout
        val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart =
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }
        cl.addView(this, lp)
    }
}
