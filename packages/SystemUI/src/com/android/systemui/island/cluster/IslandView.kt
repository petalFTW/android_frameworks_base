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
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandConstants
import com.android.systemui.island.IslandGeometry
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.IslandPresenter
import com.android.systemui.island.presenter.IslandTint
import com.android.systemui.island.render.IslandMiniNotifSource
import com.android.systemui.island.render.IslandMiniStatusView
import com.android.systemui.island.render.LiquidGlassDrawable
import com.android.systemui.island.render.MetaballPathRenderer
import com.android.systemui.island.render.MetaballShaderRenderer
import com.android.systemui.island.settings.AnimationMode
import java.util.function.Consumer

// one island. draws the shape, runs motion, handles touch. content is a presenter's job
class IslandView(
    context: Context,
    val cluster: Cluster,
    private val geometry: IslandGeometry,
    miniNotifSource: IslandMiniNotifSource? = null,
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
    private val miniStatus = IslandMiniStatusView(context, cluster, geometry, miniNotifSource)
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

    // signals that open as a card emerge bigger
    private var pendingRebloom = false

    // radius used during the emerge animation
    private var emergeCornerRadius = 0f

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var blurEnabled = false
    private val blurListener = Consumer<Boolean> { enabled ->
        if (blurEnabled != enabled) updateBackdropBlur()
    }

    private var tintColor = 0xC0101012.toInt()
    private var rimTopColor = 0x59FFFFFF.toInt()
    private var accentColor = 0xFFFFFFFF.toInt()
    private var neonAnimator: android.animation.ValueAnimator? = null
    private var chromaAnimator: android.animation.ValueAnimator? = null

    // true while the big card is showing
    var isExpanded: Boolean = false
        private set

    private var dark = true

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        clipChildren = true
        clipToPadding = true
        background = glass
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius())
            }
        }
        clipToOutline = true
        updateBackdropBlur()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
            }
        }
        addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        animator.onSettled = { callbacks?.onFormSettled(this, isExpanded) }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean =
        touchHandler.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean =
        touchHandler.onTouchEvent(ev) || super.onTouchEvent(ev)

    // blur on or off, tint goes darker when there's no blur
    private fun updateBackdropBlur() {
        blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            windowManager.isCrossWindowBlurEnabled
        if (blurEnabled) {
            val blurPx = geometry.dp(IslandConstants.BLUR_BEHIND_RADIUS_DP.toFloat()).toFloat()
            setBackdropRenderEffect(
                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
            )
        } else {
            setBackdropRenderEffect(null)
        }
        applyTheme()
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
        animator.cancelAll()
        cancelEmergence()
        destroyPresenter()
        detachMini()
        this.presenter = presenter
        presenter.setTintListener { onTintChanged(it) }
        applyTint()
        applyTheme()
        isExpanded = false
        content.alpha = 1f

        // bind collapsed content first so the path has a width to use
        val contentWidth = presenter.bindCollapsed(content)
        val capsuleW = contentWidth.coerceIn(geometry.capsuleMinWidth, geometry.capsuleMaxWidth)
        glass.setCornerRadius(geometry.cornerCapsule)

        when (animator.mode) {
            AnimationMode.NONE -> {
                alpha = 1f
                applyCollapsedSize(capsuleW)
                if (this.form == Form.EXPANDED) applyExpandedContent()
                isExpanded = this.form == Form.EXPANDED
                glass.setSpecularProgress(-1f)
            }
            AnimationMode.CLASSIC -> {
                alpha = 0f
                animator.animateAlpha(1f, 300L)
                applyCollapsedSize(capsuleW)
                if (this.form == Form.EXPANDED) applyExpandedContent()
                isExpanded = this.form == Form.EXPANDED
                glass.setSpecularProgress(0f)
                valueAnimatorSpecular().start()
            }
            AnimationMode.DYNAMIC -> {
                // the blob squeeze-out on open. fragile, leave it alone
                alpha = 1f
                pendingRebloom = this.form == Form.EXPANDED
                if (pendingRebloom) {
                    content.removeAllViews()
                    presenter.bindExpanded(content)
                    attachMini()
                    val screenW = geometry.screenWidth
                    val targetW = (screenW - geometry.expandedSideMargin * 2)
                        .coerceAtMost(geometry.expandedMaxWidth)
                    val targetH = presenter.expandedHeightPx().takeIf { it > 0 }
                        ?: geometry.expandedHeightNotif
                    setSizeDirect(targetW, targetH)
                    emergeCornerRadius = geometry.cornerExpanded
                    this.form = Form.EXPANDED
                } else {
                    setSizeDirect(capsuleW, geometry.capsuleHeight)
                    emergeCornerRadius = geometry.cornerCapsule
                }
                startEmergence()
            }
        }
    }

    fun morph(signal: IslandSignal, presenter: IslandPresenter) {
        val sameExpanded = isExpanded
        this.signal = signal
        destroyPresenter()
        this.presenter = presenter
        presenter.setTintListener { onTintChanged(it) }
        applyTint()
        if (sameExpanded) applyExpandedContent() else applyCollapsedContent()
    }

    fun expand() {
        if (isExpanded) return
        content.alpha = 1f
        applyExpandedContent()
        isExpanded = true
        callbacks?.onExpandedStateChanged(this, true)
        glass.setSpecularProgress(0f)
        valueAnimatorSpecular().start()
        maybeStartNeonSweep()
    }

    fun collapse() {
        if (!isExpanded) return
        content.alpha = 1f
        applyCollapsedContent()
        isExpanded = false
        callbacks?.onExpandedStateChanged(this, false)
    }

    // slide it off. the hint decides which way
    fun dismiss(directionHint: Float = 0f) {
        animator.cancelAll()
        cancelEmergence()
        detachMini()
        // use the glass layer for the exit, not the bare shape
        background = glass
        clipToOutline = true
        val direction = when {
            directionHint > 0f -> 1f
            directionHint < 0f -> -1f
            else -> if (cluster == Cluster.LEFT) -1f else 1f
        }
        val distance = (width + geometry.dp(24f)) * direction
        animator.animateTranslationX(distance, 220L) {
            destroyPresenter()
            signal = null
            visibility = View.GONE
            translationX = 0f
            alpha = 1f
        }
    }

    fun presenterForSignal(): IslandPresenter? = presenter

    // true if that point hit a button instead of empty space
    fun hasClickableChildAt(x: Float, y: Float): Boolean =
        findClickableChild(content, x, y) != null

    // we swallow every touch, so forward taps to the child ourselves
    fun performClickableChildAt(x: Float, y: Float): Boolean {
        val child = findClickableChild(content, x, y) ?: return false
        return child.isEnabled && child.performClick()
    }

    private fun findClickableChild(parent: ViewGroup, x: Float, y: Float): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.isClickable &&
                x >= child.left && x <= child.right && y >= child.top && y <= child.bottom
            ) {
                return child
            }
            if (child is ViewGroup) {
                val nested = findClickableChild(child, x - child.left, y - child.top)
                if (nested != null) return nested
            }
        }
        return null
    }

    // vertical drag, positive means downward
    fun onDragUpdate(dy: Float) {
        dragOffset = dy
        translationY = dy
    }

    // finger lifted, work out what the drag meant
    fun onDragEnd(dy: Float) {
        translationY = 0f
        val threshold = geometry.dp(24f)
        when {
            dy < -threshold -> callbacks?.onUserDismiss(this)
            dy > threshold && !isExpanded -> callbacks?.onUserExpand(this)
            dy > threshold && isExpanded -> {
                // dragged past the threshold, so grow the card
                if (dy > geometry.dp(IslandConstants.DRAG_COMMIT_THRESHOLD_DP)) {
                    resizeToMediaMax()
                }
            }
        }
        dragOffset = 0f
    }

    // horizontal drag, with a rubber band on it
    fun onHorizontalDragUpdate(dx: Float) {
        val rubberBanded = dx * IslandConstants.RUBBER_BAND_FACTOR
        translationX = rubberBanded
        // fades from fully opaque to 0.6 by the dismiss distance
        val dismissPx = geometry.dp(DISMISS_THRESHOLD_DP).toFloat()
        alpha = (1f - (kotlin.math.abs(rubberBanded) / dismissPx) * 0.4f).coerceIn(0.6f, 1f)
    }

    // far or fast enough means dismiss, otherwise snap back
    fun onHorizontalDragEnd(dx: Float, velocityX: Float) {
        val rubberBanded = dx * IslandConstants.RUBBER_BAND_FACTOR
        val dismissPx = geometry.dp(DISMISS_THRESHOLD_DP).toFloat()
        val shouldDismiss = kotlin.math.abs(rubberBanded) > dismissPx ||
            kotlin.math.abs(velocityX) > FLING_VELOCITY_THRESHOLD

        if (shouldDismiss && (dx != 0f || velocityX != 0f)) {
            // fling velocity wins over distance when picking a direction
            val direction = if (kotlin.math.abs(velocityX) > FLING_VELOCITY_THRESHOLD) {
                if (velocityX > 0f) 1f else -1f
            } else {
                if (rubberBanded > 0f) 1f else -1f
            }
            dismiss(direction)
            presenterForSignal()?.onDismiss()
            callbacks?.onUserDismiss(this)
        } else {
            // didn't commit, ease it back
            animator.settleTranslationX()
        }
    }

    // drag resize for media, go to the tall size
    private fun resizeToMediaMax() {
        animator.animateSize(
            (geometry.screenWidth - geometry.expandedSideMargin * 2)
                .coerceAtMost(geometry.expandedMaxWidth),
            geometry.expandedHeightMediaMax,
            expanding = true,
        )
    }

    // stop the emerge animation, callbacks included
    private fun cancelEmergence() {
        emergeAnimator?.let { a ->
            a.removeAllUpdateListeners()
            a.removeAllListeners()
            a.cancel()
        }
        emergeAnimator = null
        emerging = false
        pendingRebloom = false
    }

    private fun startEmergence() {
        emerging = true
        emergeP = 0f
        background = null
        clipToOutline = false
        content.alpha = 0f
        applyEmergeProgress(0f)
        // cards take longer to emerge, makes it look wet
        val dur = if (pendingRebloom) 520L else IslandConstants.DUR_EMERGE
        emergeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a -> applyEmergeProgress(a.animatedValue as Float) }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        finishEmergence()
                    }
                },
            )
        }
        emergeAnimator?.start()
    }

    private fun applyEmergeProgress(p: Float) {
        emergeP = p
        // slide out from the edge to the resting position
        val margin = geometry.expandedSideMargin.toFloat()
        val settle = easeOutCubic(p)
        translationX =
            if (cluster == Cluster.LEFT) -(1f - settle) * margin else (1f - settle) * margin
        // content shows up near the end, later for cards
        content.alpha = if (pendingRebloom) {
            smoothstep(0.45f, 0.90f, p)
        } else {
            smoothstep(0.35f, 0.82f, p)
        }
        invalidate()
    }

    private fun finishEmergence() {
        emerging = false
        translationX = 0f
        content.alpha = 1f
        background = glass
        clipToOutline = true
        invalidate()
        glass.setSpecularProgress(0f)
        valueAnimatorSpecular().start()
        if (pendingRebloom) {
            pendingRebloom = false
            // content is already bound, only the radius is left
            glass.setCornerRadius(geometry.cornerExpanded)
            isExpanded = true
            callbacks?.onExpandedStateChanged(this, true)
        }
        invalidateOutline()
        // no spring here, so fire the settle callback by hand
        callbacks?.onFormSettled(this, isExpanded)
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
        val r = emergeCornerRadius
        val cy = h / 2f
        val leading = if (cluster == Cluster.LEFT) 0f else w
        val dir = if (cluster == Cluster.LEFT) -1f else 1f

        val kMax = geometry.dp(IslandConstants.METABALL_K_MAX_DP.toFloat()).toFloat()
        val rBase = geometry.dp(IslandConstants.DROPLET_EMERGE_RADIUS_DP)
        val rMin = geometry.dp(IslandConstants.DROPLET_EMERGE_MIN_DP)
        val travelEnd = 0.55f
        val dropR: Float
        val k: Float
        if (p < travelEnd) {
            val t = easeOutCubic(p / travelEnd)
            dropR = rMin + (rBase - rMin) * t
            k = kMax
        } else {
            val t = (p - travelEnd) / (1f - travelEnd)
            val st = smoothstep(0f, 1f, t)
            dropR = rBase * (1f - st)
            k = kMax * (1f - st)
        }
        // push it past the edge a little so it looks squeezed
        val dropCx = leading + dir * dropR * 0.55f
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

    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // rebind the small content and shrink to the capsule
    private fun applyCollapsedContent() {
        detachMini()
        content.removeAllViews()
        val width = presenter?.bindCollapsed(content) ?: geometry.chipSize
        applyCollapsedSize(width)
    }

    // mini status goes in the top outer corner
    private fun attachMini() {
        if (miniStatus.parent != null) return
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or if (cluster == Cluster.LEFT) Gravity.START else Gravity.END,
        ).apply {
            marginStart = geometry.dp(14f)
            marginEnd = geometry.dp(14f)
            topMargin = geometry.dp(6f)
        }
        addView(miniStatus, lp)
    }

    private fun detachMini() {
        if (miniStatus.parent === this) removeView(miniStatus)
    }

    private fun applyCollapsedSize(contentWidth: Int) {
        val targetW = contentWidth.coerceIn(geometry.capsuleMinWidth, geometry.capsuleMaxWidth)
        animator.animateSize(targetW, geometry.capsuleHeight, expanding = false)
        glass.setCornerRadius(geometry.cornerCapsule)
        form = Form.CAPSULE
    }

    // rebind the card content and grow to full size
    private fun applyExpandedContent() {
        content.removeAllViews()
        presenter?.bindExpanded(content)
        attachMini()
        val screenW = geometry.screenWidth
        val targetW = (screenW - geometry.expandedSideMargin * 2)
            .coerceAtMost(geometry.expandedMaxWidth)
        val targetH = presenter?.expandedHeightPx()?.takeIf { it > 0 }
            ?: geometry.expandedHeightNotif
        animator.animateSize(targetW, targetH, expanding = true)
        glass.setCornerRadius(geometry.cornerExpanded)
        form = Form.EXPANDED
    }

    // jump to a size with no animation, used mid emerge
    private fun setSizeDirect(w: Int, h: Int) {
        val lp = layoutParams ?: return
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            requestLayout()
        }
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
        accentColor = tint.accent
        tintColor = tint.background ?: themeBackground()
        rimTopColor = withAlpha(tint.accent, 0x59)
        glass.setTintColor(tintColor)
        glass.setRim(
            rimTopColor,
            withAlpha(tint.accent, 0x14),
        )
        glass.setNeonColor(tint.accent)
    }

    private fun applyTheme() {
        accentColor = if (dark) 0xFFFFFFFF.toInt() else 0xFF101012.toInt()
        tintColor = themeBackground()
        glass.setTintColor(tintColor)
        glass.setGrainAlpha(if (dark) 0x14 else 0x10)
        if (dark) {
            rimTopColor = 0x59FFFFFF.toInt()
            glass.setRim(rimTopColor, 0x14FFFFFF.toInt())
        } else {
            // light theme wants a brighter top and a soft dark bottom
            rimTopColor = 0x99FFFFFF.toInt()
            glass.setRim(rimTopColor, 0x26000000.toInt())
        }
        glass.setNeonColor(accentColor)
    }

    // see-through when blur works, almost solid when it doesn't
    private fun themeBackground(): Int =
        when {
            !blurEnabled -> if (dark) 0xF2101012.toInt() else 0xF7F3F3F6.toInt()
            dark -> 0xC0101012.toInt()
            else -> 0xD9F3F3F6.toInt()
        }

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

    // run the neon rim only for notification cards
    private fun maybeStartNeonSweep() {
        if (signal?.kind != SignalKind.NOTIFICATION) return
        startNeonSweep()
    }

    private fun startNeonSweep() {
        neonAnimator?.cancel()
        glass.setNeonProgress(0f)
        neonAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = com.android.systemui.island.IslandConstants.DUR_NEON
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { a -> glass.setNeonProgress(a.animatedValue as Float) }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        glass.setNeonProgress(-1f)
                        neonAnimator = null
                    }
                },
            )
        }
        neonAnimator?.start()
    }

    // prism sweep across the glass while the bridge crossfades
    fun startChromaShimmer(durationMs: Long) {
        chromaAnimator?.cancel()
        glass.setChromaProgress(0f)
        // full 10s of sweeping is boring as hell, cap it
        val dur = durationMs.coerceIn(1200L, 2600L)
        chromaAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = android.view.animation.DecelerateInterpolator(1.2f)
            addUpdateListener { a -> glass.setChromaProgress(a.animatedValue as Float) }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        glass.setChromaProgress(-1f)
                        chromaAnimator = null
                    }
                },
            )
        }
        chromaAnimator?.start()
    }

    private fun destroyPresenter() {
        presenter?.setTintListener(null)
        presenter?.onDestroy()
        content.removeAllViews()
        presenter = null
    }

    // presenter changed the tint, usually a new track
    private fun onTintChanged(tint: IslandTint?) {
        if (tint != null) applyTint(tint) else applyTheme()
        invalidate()
    }

    private fun currentCornerRadius(): Float =
        if (isExpanded) geometry.cornerExpanded else geometry.cornerCapsule

    // our rect in the parent's coordinates
    fun boundsOnParent(): Rect {
        val r = Rect()
        r.set(left, top, right, bottom)
        return r
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                windowManager.removeCrossWindowBlurEnabledListener(blurListener)
            }
        }
        animator.cancelAll()
        neonAnimator?.cancel()
        neonAnimator = null
        chromaAnimator?.cancel()
        chromaAnimator = null
        destroyPresenter()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        // how far you drag before it counts as a dismiss
        private const val DISMISS_THRESHOLD_DP = 80f
        // or how fast, in px per second
        private const val FLING_VELOCITY_THRESHOLD = 1200f

        fun layoutParams(gravity: Int): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity,
            )
    }
}