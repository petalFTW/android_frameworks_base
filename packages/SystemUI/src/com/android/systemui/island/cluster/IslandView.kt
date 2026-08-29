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

package com.android.systemui.island.cluster

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandConstants
import com.android.systemui.island.IslandGeometry
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.presenter.IslandPresenter
import com.android.systemui.island.presenter.IslandTint
import com.android.systemui.island.render.LiquidGlassDrawable
import com.android.systemui.island.render.MetaballPathRenderer
import com.android.systemui.island.render.MetaballShaderRenderer

/**
 * One island: a floating, animated capsule that owns shape, motion and touch. Content is owned by
 * an [IslandPresenter].
 */
class IslandView(
    context: Context,
    val cluster: Cluster,
    private val geometry: IslandGeometry,
) : FrameLayout(context) {

    interface Callbacks {
        fun onExpandedStateChanged(island: IslandView, expanded: Boolean)
        fun onFormSettled(island: IslandView, expanded: Boolean)
        fun onUserDismiss(island: IslandView)
        fun onUserExpand(island: IslandView)
        fun onUserPrimaryAction(island: IslandView)
    }

    var callbacks: Callbacks? = null

    private val glass = LiquidGlassDrawable()
    private val content = FrameLayout(context)
    private val animator = IslandAnimator(this)
    private val touchHandler = IslandTouchHandler(this, geometry)
    private val metaball = MetaballPathRenderer()
    private val shaderMetaball: MetaballShaderRenderer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) MetaballShaderRenderer() else null

    private var presenter: IslandPresenter? = null
    private var signal: IslandSignal? = null
    private var form: Form = Form.CAPSULE
    private var dragOffset = 0f

    private var emerging = false
    private var emergeP = 0f
    private var emergeAnimator: android.animation.ValueAnimator? = null
    private var tintColor = 0xE0101012.toInt()
    private var rimTopColor = 0x59FFFFFF.toInt()

    /** True once the island has grown to its expanded size. */
    var isExpanded: Boolean = false
        private set

    private var dark = true

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        clipChildren = true
        clipToPadding = true
        background = glass
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurPx = geometry.dp(14f).toFloat()
            setBackdropRenderEffect(
                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            )
        }
        addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        touchHandler.attach()
        animator.onSettled = { callbacks?.onFormSettled(this, isExpanded) }
    }

    fun onConfigurationChanged(darkTheme: Boolean) {
        if (dark != darkTheme) {
            dark = darkTheme
            applyTheme()
        }
    }

    fun cornerRadius(): Float = currentCornerRadius()

    fun setAnimationMode(mode: com.android.systemui.island.settings.AnimationMode) {
        animator.mode = mode
    }

    fun show(signal: IslandSignal, presenter: IslandPresenter, form: Form) {
        this.signal = signal
        this.form = if (form == Form.EXPANDED) Form.EXPANDED else Form.CAPSULE
        destroyPresenter()
        this.presenter = presenter
        applyTint()
        applyTheme()
        if (this.form == Form.EXPANDED) {
            bindExpanded()
        } else {
            bindCollapsed()
        }
        alpha = 0f
        animator.animateAlpha(1f, 300L)
        glass.setSpecularProgress(0f)
        val sweep = valueAnimatorSpecular()
        sweep.start()
        isExpanded = this.form == Form.EXPANDED
        startEmergence()
    }

    fun morph(signal: IslandSignal, presenter: IslandPresenter) {
        val sameExpanded = isExpanded
        this.signal = signal
        destroyPresenter()
        this.presenter = presenter
        applyTint()
        if (sameExpanded) bindExpanded() else bindCollapsed()
    }

    fun expand() {
        if (isExpanded) return
        bindExpanded()
        isExpanded = true
        callbacks?.onExpandedStateChanged(this, true)
        glass.setSpecularProgress(0f)
        valueAnimatorSpecular().start()
    }

    fun collapse() {
        if (!isExpanded) return
        bindCollapsed()
        isExpanded = false
        callbacks?.onExpandedStateChanged(this, false)
    }

    fun dismiss() {
        animator.cancelAll()
        // Swipe off-screen toward the bezel rather than fading.
        val direction = if (cluster == Cluster.LEFT) -1f else 1f
        val distance = (width + geometry.dp(24f)) * direction
        animator.animateTranslationX(distance, 220L) {
            destroyPresenter()
            signal = null
            visibility = View.GONE
            translationX = 0f
        }
    }

    fun presenterForSignal(): IslandPresenter? = presenter

    /** True if the given point is on an interactive child (a button), so the tap isn't treated as
     *  a background tap. */
    fun hasClickableChildAt(x: Float, y: Float): Boolean =
        findClickableChild(content, x, y)

    private fun findClickableChild(parent: ViewGroup, x: Float, y: Float): Boolean {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.isClickable &&
                x >= child.left && x <= child.right && y >= child.top && y <= child.bottom
            ) {
                return true
            }
            if (child is ViewGroup && findClickableChild(child, x - child.left, y - child.top)) {
                return true
            }
        }
        return false
    }

    /** Called while the user drags the island vertically (positive = down). */
    fun onDragUpdate(dy: Float) {
        dragOffset = dy
    }

    /** Called when the user releases a drag. */
    fun onDragEnd(dy: Float) {
        val threshold = geometry.dp(24f)
        when {
            dy < -threshold -> callbacks?.onUserDismiss(this)
            dy > threshold && !isExpanded -> callbacks?.onUserExpand(this)
            dy > threshold && isExpanded -> {
                // Drag-to-resize: commit to the taller layout past the threshold.
                if (dy > geometry.dp(IslandConstants.DRAG_COMMIT_THRESHOLD_DP)) {
                    resizeToMediaMax()
                }
            }
        }
        dragOffset = 0f
    }

    /** Grows the expanded media island to its maximum height (drag-resize commit). */
    private fun resizeToMediaMax() {
        animator.animateSize(
            (geometry.screenWidth - geometry.expandedSideMargin * 2)
                .coerceAtMost(geometry.expandedMaxWidth),
            geometry.expandedHeightMediaMax,
            expanding = true,
        )
    }

    /** Corner emergence (§8.3): a droplet bulges from the leading edge and melts into the capsule. */
    private fun startEmergence() {
        emergeAnimator?.cancel()
        emerging = true
        emergeP = 0f
        emergeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = IslandConstants.DUR_EMERGE
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                emergeP = a.animatedValue as Float
                invalidate()
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        emerging = false
                        invalidate()
                    }
                },
            )
        }
        emergeAnimator?.start()
    }

    override fun onDraw(canvas: Canvas) {
        if (emerging) {
            drawEmergence(canvas)
        } else {
            super.onDraw(canvas)
        }
    }

    private fun drawEmergence(canvas: Canvas) {
        val p = emergeP
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val r = if (isExpanded) geometry.cornerExpanded else geometry.cornerCapsule
        val cy = h / 2f
        val leading = if (cluster == Cluster.LEFT) 0f else w

        val kMax = geometry.dp(IslandConstants.METABALL_K_MAX_DP.toFloat()).toFloat()
        val travelEnd = 0.55f
        val dropR: Float
        val k: Float
        if (p < travelEnd) {
            val t = p / travelEnd
            dropR = r * t
            k = kMax
        } else {
            val t = (p - travelEnd) / (1f - travelEnd)
            dropR = r * (1f - t)
            k = kMax * (1f - t)
        }
        val dropCx = leading
        val box = RectF(0f, 0f, w, h)
        val shader = shaderMetaball
        if (shader != null) {
            shader.draw(canvas, box, r, dropCx, cy, dropR, k, tintColor, rimTopColor)
        } else {
            metaball.draw(
                canvas,
                box,
                r,
                dropCx,
                cy,
                dropR,
                k,
                tintColor,
                rimTopColor,
                geometry.dp(IslandConstants.RIM_WIDTH_DP.toFloat()).toFloat(),
            )
        }
    }

    private fun bindCollapsed() {
        content.removeAllViews()
        val width = presenter?.bindCollapsed(content) ?: geometry.chipSize
        val targetW = width.coerceIn(geometry.capsuleMinWidth, geometry.capsuleMaxWidth)
        animator.animateSize(targetW, geometry.capsuleHeight, expanding = false)
        glass.setCornerRadius(geometry.cornerCapsule)
        form = Form.CAPSULE
    }

    private fun bindExpanded() {
        content.removeAllViews()
        presenter?.bindExpanded(content)
        val screenW = geometry.screenWidth
        val targetW = (screenW - geometry.expandedSideMargin * 2)
            .coerceAtMost(geometry.expandedMaxWidth)
        val targetH = presenter?.expandedHeightPx()?.takeIf { it > 0 }
            ?: geometry.expandedHeightNotif
        animator.animateSize(targetW, targetH, expanding = true)
        glass.setCornerRadius(geometry.cornerExpanded)
        form = Form.EXPANDED
    }

    private fun applyTint() {
        val tint = presenter?.tint()
        if (tint != null) {
            applyTint(tint)
        } else {
            applyTheme()
        }
    }

    private fun applyTint(tint: IslandTint) {
        tintColor = tint.background ?: themeBackground()
        rimTopColor = withAlpha(tint.accent, 0x59)
        glass.setTintColor(tintColor)
        glass.setRim(
            rimTopColor,
            withAlpha(tint.accent, 0x14),
        )
    }

    private fun applyTheme() {
        tintColor = themeBackground()
        glass.setTintColor(tintColor)
        if (dark) {
            rimTopColor = 0x59FFFFFF.toInt()
            glass.setRim(rimTopColor, 0x14FFFFFF.toInt())
        } else {
            // Light glass: brighter top highlight + a subtle dark lower edge for definition.
            rimTopColor = 0x99FFFFFF.toInt()
            glass.setRim(rimTopColor, 0x26000000.toInt())
        }
    }

    private fun themeBackground(): Int =
        if (dark) 0xE0101012.toInt() else 0x99F3F3F6.toInt()

    private fun valueAnimatorSpecular(): android.animation.ValueAnimator =
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = com.android.systemui.island.IslandConstants.DUR_SPECULAR
            addUpdateListener { a -> glass.setSpecularProgress(a.animatedValue as Float) }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        glass.setSpecularProgress(-1f)
                    }
                },
            )
        }

    private fun destroyPresenter() {
        presenter?.onDestroy()
        content.removeAllViews()
        presenter = null
    }

    private fun currentCornerRadius(): Float =
        if (isExpanded) geometry.cornerExpanded else geometry.cornerCapsule

    /** Returns the island's current bounds in the parent's coordinate space. */
    fun boundsOnParent(): Rect {
        val r = Rect()
        r.set(left, top, right, bottom)
        return r
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancelAll()
        destroyPresenter()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        fun layoutParams(gravity: Int): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity,
            )
    }
}
