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
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
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
import com.android.systemui.island.render.RefractionShader
import com.android.systemui.island.settings.AnimationMode
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import org.petalos.config.PetalConfig
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
        fun onUserCollapse(island: IslandView)
        fun onUserPrimaryAction(island: IslandView)
        fun onUserTouchChanged(island: IslandView, touching: Boolean)
    }

    var callbacks: Callbacks? = null

    private val glass = LiquidGlassDrawable()
    private val refraction = RefractionShader()
    // two stacked layers: compositor blur, then the glass tint/rim on top
    private val blurLayer = View(context)
    private val glassLayer = View(context)
    private var backdrop: BackgroundBlurDrawable? = null
    private val content = FrameLayout(context)
    private val miniStatus = IslandMiniStatusView(context, cluster, geometry, miniNotifSource)
    private val animator = IslandAnimator(this)
    private val touchHandler = IslandTouchHandler(this, geometry)

    private var presenter: IslandPresenter? = null
    private var signal: IslandSignal? = null
    private var form: Form = Form.CAPSULE
    private var dragOffset = 0f
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    // slid off the top while qs is up
    private var parked = false

    private var emerging = false
    private var emergeP = 0f
    private var emergeAnimator: android.animation.ValueAnimator? = null

    // non-zero while sliding out on dismiss; drives the status-bar hand-off
    private var dismissing = false
    private var dismissDistance = 1f

    // timeout exit, the blob gets sucked out through the edge
    private var draining = false
    private var drainP = 0f
    private var drainAnimator: android.animation.ValueAnimator? = null
    private var drainFromX = 0f

    // signals that open as a card emerge bigger
    private var pendingRebloom = false

    // radius used during the emerge animation
    private var emergeCornerRadius = 0f

    // live corner radius, so expand/collapse can slide it instead of popping
    private var cornerRadiusPx = 0f
    private var cornerAnimator: android.animation.ValueAnimator? = null

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var blurEnabled = false
    private val blurListener = Consumer<Boolean> {
        updateBackdropBlur(animate = false)
    }

    private var tintColor = 0xC0101012.toInt()
    private var rimTopColor = 0x2EFFFFFF.toInt()
    private var accentColor = 0xFFFFFFFF.toInt()
    private var neonAnimator: android.animation.ValueAnimator? = null
    private var specularAnimator: android.animation.ValueAnimator? = null
    private var chromaAnimator: android.animation.ValueAnimator? = null
    private var auraAnimator: android.animation.ValueAnimator? = null

    // true while the big card is showing
    var isExpanded: Boolean = false
        private set

    val isShadowAnimating: Boolean
        get() = visibility == View.VISIBLE && (emerging || dismissing || animator.isMotionRunning)

    private var dark = true

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepParked() }
        clipChildren = true
        clipToPadding = true
        background = null
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius())
            }
        }
        clipToOutline = true
        updateBackdropBlur()
        updateGlassStyle()
        glassLayer.background = glass
        addView(
            blurLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            glassLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        animator.onSettled = {
            // morph done, drop the glass if we ended up as the little chip again
            updateBackdropBlur()
            callbacks?.onFormSettled(this, isExpanded)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (dismissing) return false
        touchHandler.onDispatchTouchEvent(ev)
        return try {
            super.dispatchTouchEvent(ev)
        } finally {
            touchHandler.onDispatchTouchEventFinished(ev)
        }
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean =
        touchHandler.onInterceptTouchEvent(ev)

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // capsule buttons may click, but they don't get to swallow an expand swipe
        super.requestDisallowInterceptTouchEvent(disallowIntercept && isExpanded)
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean =
        touchHandler.onTouchEvent(ev) || super.onTouchEvent(ev)

    // collapsed chip sits on the black status bar, blur there is invisible but still makes
    // SF gpu-compose every frame under it. so glass fades in with the card and back out with it.
    // glassT 0 = flat chip, 1 = full glass. blur, lens and tint all ride it so nothing pops
    private var glassT = 0f
    private var glassTarget = -1f
    private var glassAnim: android.animation.ValueAnimator? = null

    private fun blurAllowed(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        windowManager.isCrossWindowBlurEnabled && PetalConfig.getIslandBlurScale(context) > 0f

    private fun refractionAllowed(): Boolean =
        windowManager.isCrossWindowBlurEnabled && PetalConfig.isIslandBubbleEnabled(context)

    private fun updateBackdropBlur(animate: Boolean = true) {
        blurEnabled = blurAllowed()
        val target = if (isExpanded && (blurEnabled || refractionAllowed())) 1f else 0f
        if (!animate || !isAttachedToWindow || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            glassAnim?.cancel()
            glassAnim = null
            glassTarget = target
            setGlassT(target)
            return
        }
        if (target == glassTarget) {
            // already heading there, just repaint with fresh settings
            if (glassAnim?.isRunning != true) setGlassT(target)
            return
        }
        glassTarget = target
        glassAnim?.cancel()
        glassAnim = android.animation.ValueAnimator.ofFloat(glassT, target).apply {
            duration = GLASS_FADE_MS
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener { setGlassT(it.animatedValue as Float) }
            start()
        }
    }

    private fun setGlassT(t: Float) {
        glassT = t
        val radius = if (blurEnabled) {
            (geometry.dp(IslandConstants.BLUR_BEHIND_RADIUS_DP) *
                PetalConfig.getIslandBlurScale(context) * t).toInt()
        } else 0
        backdrop?.setBlurRadius(radius)
        updateRefraction()
        // was applyTheme(), which stomped the album art tint every expand. dumbass
        applyTint()
        blurLayer.invalidate()
        glassLayer.invalidate()
    }

    fun refreshBackdropBlur() = updateBackdropBlur(animate = false)

    private fun isBackdropRefractionEnabled(): Boolean = glassT > 0.01f && refractionAllowed()

    // SF grabs the windows behind this glass thing.
    private fun updateRefraction() {
        val popout = PetalConfig.getIslandPopoutScale(context)
        val chroma = PetalConfig.getIslandChromaScale(context)
        val drawable = backdrop ?: return
        if (!isBackdropRefractionEnabled()) {
            refraction.clear(drawable)
            glass.setSlab(0f, 0f)
            return
        }
        // Big cards need more lens; live height keeps it from popping.
        val span = (geometry.expandedHeightNotif - geometry.capsuleHeight).coerceAtLeast(1)
        val expandT = ((height - geometry.capsuleHeight).toFloat() / span).coerceIn(0f, 1f)
        val strength = popout * (1f + expandT * (EXPANDED_REFRACT_MULTIPLIER - 1f)) * glassT
        val density = resources.displayMetrics.density
        glass.setSlab(refraction.bandFor(width, height, density), strength)
        refraction.applyTo(drawable, width, height, density, strength, chroma)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRefraction()
    }

    private fun updateGlassStyle() {
        glassLayer.invalidate()
        updateRefraction()
    }

    fun refreshGlassStyle() = updateGlassStyle()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
        }
        // Blur this blob, not the whole bloody screen.
        backdrop = viewRootImpl.createBackgroundBlurDrawable().apply {
            setCornerRadius(cornerRadius())
        }
        blurLayer.background = backdrop
        updateBackdropBlur(animate = false)
        updateRefraction()
    }

    private fun setGlassCornerRadius(radius: Float) {
        cornerRadiusPx = radius
        glass.setCornerRadius(radius)
        backdrop?.setCornerRadius(radius)
        // clip was caching the old radius, corners popped on collapse
        invalidateOutline()
        blurLayer.invalidate()
        glassLayer.invalidate()
        updateRefraction()
    }

    // same thing but eased, stops the corners snapping mid resize
    private fun animateGlassCornerRadius(target: Float, durationMs: Long) {
        cornerAnimator?.cancel()
        val start = cornerRadiusPx
        if (animator.mode == AnimationMode.NONE || start == target) {
            setGlassCornerRadius(target)
            return
        }
        cornerAnimator = android.animation.ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                setGlassCornerRadius(a.animatedValue as Float)
                invalidate()
            }
        }
        cornerAnimator?.start()
    }

    fun onConfigurationChanged(darkTheme: Boolean) {
        if (dark != darkTheme) {
            dark = darkTheme
            // presenters whose tint depends on the theme (media art) re-derive it
            presenter?.onThemeChanged()
            applyTint()
        }
    }

    fun cornerRadius(): Float = cornerRadiusPx

    fun setAnimationMode(mode: com.android.systemui.island.settings.AnimationMode) {
        animator.mode = mode
    }

    fun show(signal: IslandSignal, presenter: IslandPresenter, form: Form) {
        touchHandler.cancel()
        this.signal = signal
        this.form = if (form == Form.EXPANDED) Form.EXPANDED else Form.CAPSULE
        animator.cancelAll()
        translationX = 0f
        translationY = 0f
        parked = false
        specularAnimator?.cancel()
        visibility = View.VISIBLE
        cancelEmergence()
        cancelDrain()
        destroyPresenter()
        detachMini()
        this.presenter = presenter
        presenter.setTintListener { onTintChanged(it) }
        applyTint()
        applyTheme()
        isExpanded = false
        dismissing = false
        content.alpha = 1f
        glassLayer.visibility = View.VISIBLE
        blurLayer.visibility = View.VISIBLE
        clipToOutline = true
        // fresh signal starts as a flat chip, no fade from whatever was here before
        updateBackdropBlur(animate = false)

        // bind collapsed content first so the path has a width to use
        val contentWidth = presenter.bindCollapsed(content)
        val capsuleW = contentWidth.coerceIn(geometry.capsuleMinWidth, geometry.capsuleMaxWidth)
        setGlassCornerRadius(geometry.cornerCapsule)

        when (animator.mode) {
            AnimationMode.NONE -> {
                alpha = 1f
                isExpanded = this.form == Form.EXPANDED
                applyCollapsedSize(capsuleW)
                if (isExpanded) applyExpandedContent()
                if (isExpanded) updateBackdropBlur()
                glass.setSpecularProgress(-1f)
            }
            AnimationMode.CLASSIC -> {
                alpha = 0f
                animator.animateAlpha(1f, 300L)
                isExpanded = this.form == Form.EXPANDED
                applyCollapsedSize(capsuleW)
                if (isExpanded) applyExpandedContent()
                if (isExpanded) updateBackdropBlur()
                startSpecularSweep()
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
                    applyTopNudge(expanded = true)
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
        // download blob keeps morphing every percent and kept losing its lens. poke it
        updateRefraction()
    }

    fun expand() {
        if (isExpanded) return
        isExpanded = true
        updateBackdropBlur()
        content.alpha = 1f
        applyExpandedContent()
        callbacks?.onExpandedStateChanged(this, true)
        startSpecularSweep()
        maybeStartNeonSweep()
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        specularAnimator?.cancel()
        specularAnimator = null
        glass.setSpecularProgress(-1f)
        content.alpha = 1f
        applyCollapsedContent()
        // still morphing = keeps glass till onSettled, no anim = flat right now
        updateBackdropBlur()
        callbacks?.onExpandedStateChanged(this, false)
    }

    // park the blob off the top while qs is up, drag it back after
    fun slideAway(hidden: Boolean, animate: Boolean = true) {
        if (hidden) touchHandler.cancel()
        parked = hidden
        val target = if (hidden) parkedOffset() else 0f
        if (animate) {
            // the blob can grow mid-slide, so land on the offset for its final size
            animator.animateTranslationY(target) { if (parked) translationY = parkedOffset() }
        } else {
            // only the slide; size and fade-in from show() keep running
            animator.cancelVerticalMotion()
            translationY = target
        }
        (parent as? View)?.invalidate()
    }

    // high enough that the whole blob clears the top, using the size it's heading to
    private fun parkedOffset(): Float {
        val h = maxOf(height, layoutParams?.height ?: 0, geometry.capsuleHeight)
        val t = if (top > 0) top else geometry.collapsedTopY
        return -(t + h + geometry.dp(24f)).toFloat()
    }

    // a parked blob that expands or nudges down would otherwise peek back in
    private fun keepParked() {
        if (!parked || animator.isSlidingVertically) return
        val target = parkedOffset()
        if (translationY != target) translationY = target
    }

    // slide it off. the hint decides which way
    fun dismiss(directionHint: Float = 0f) {
        if (dismissing) return
        touchHandler.cancel()
        specularAnimator?.cancel()
        specularAnimator = null
        glass.setSpecularProgress(-1f)
        animator.cancelAll()
        cornerAnimator?.cancel()
        cornerAnimator = null
        cancelEmergence()
        detachMini()
        // use the glass layer for the exit, not the bare shape
        glassLayer.visibility = View.VISIBLE
        blurLayer.visibility = View.VISIBLE
        clipToOutline = true
        val direction = when {
            directionHint > 0f -> 1f
            directionHint < 0f -> -1f
            else -> if (cluster == Cluster.LEFT) -1f else 1f
        }
        // nobody flung it, so it drains home instead of sliding
        if (directionHint == 0f && animator.mode == AnimationMode.DYNAMIC && width > 0) {
            startDrain()
            return
        }
        val distance = (width + geometry.dp(24f)) * direction
        dismissing = true
        dismissDistance = kotlin.math.abs(distance).coerceAtLeast(1f)
        (parent as? View)?.invalidate()
        val goo = animator.mode == AnimationMode.DYNAMIC
        if (goo) {
            // trailing side stays put, nose gets yanked ahead
            pivotX = if (direction > 0f) 0f else width.toFloat()
            pivotY = height / 2f
        }
        val startSx = scaleX
        val startSy = scaleY
        val stretch: ((Float) -> Unit)? = if (!goo) null else { tx ->
            val f = (kotlin.math.abs(tx) / dismissDistance).coerceIn(0f, 1f)
            scaleX = startSx + (1.32f - startSx) * f
            scaleY = startSy + (0.74f - startSy) * f
        }
        animator.animateTranslationX(distance, 220L, onUpdate = stretch) {
            finishDismiss()
        }
    }

    private fun finishDismiss() {
        dismissing = false
        draining = false
        destroyPresenter()
        signal = null
        backdrop?.setBlurRadius(0)
        backdrop?.alpha = 255
        backdrop?.let { refraction.clear(it) }
        blurLayer.visibility = View.GONE
        glassLayer.visibility = View.GONE
        blurLayer.alpha = 1f
        glassLayer.alpha = 1f
        visibility = View.GONE
        translationX = 0f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        (parent as? View)?.invalidate()
    }

    private fun startDrain() {
        dismissing = true
        draining = true
        drainP = 0f
        drainFromX = translationX
        dismissDistance = 1f
        (parent as? View)?.invalidate()
        drainAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = IslandConstants.DUR_DRAIN
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a -> applyDrainProgress(a.animatedValue as Float) }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        drainAnimator = null
                        finishDismiss()
                    }
                },
            )
        }
        drainAnimator?.start()
    }

    // real glass the whole way, the lens only works on the actual view
    private fun applyDrainProgress(q: Float) {
        drainP = q
        val e = ((q - 0.12f) / 0.88f).coerceIn(0f, 1f)
        val f = e * e * e
        translationX = drainFromX + (offscreenX() - drainFromX) * f
        val settle = kotlin.math.sin(Math.PI.toFloat() * (q / 0.24f).coerceIn(0f, 1f))
        applyJelly(0.1f * e * e - 0.035f * settle)
        (parent as? View)?.invalidate()
    }

    // kill a running drain, put the layers back how show() expects them
    private fun cancelDrain() {
        drainAnimator?.let { a ->
            a.removeAllUpdateListeners()
            a.removeAllListeners()
            a.cancel()
        }
        drainAnimator = null
        draining = false
        drainP = 0f
        glassLayer.alpha = 1f
        blurLayer.alpha = 1f
        backdrop?.alpha = 255
        scaleX = 1f
        scaleY = 1f
    }

    fun presenterForSignal(): IslandPresenter? = presenter

    fun canExpand(): Boolean = presenter?.canExpand() != false

    // 0..1 presence, so the bar icons fade with this thing.
    fun presenceProgress(): Float {
        if (visibility != View.VISIBLE) return 0f
        return when {
            emerging -> emergeP.coerceIn(0f, 1f)
            draining -> 1f - smoothstep(0.1f, 0.6f, drainP)
            dismissing -> (1f - (kotlin.math.abs(translationX) / dismissDistance))
                .coerceIn(0f, 1f)
            else -> alpha.coerceIn(0f, 1f)
        }
    }

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

    fun onGestureDragStart(horizontal: Boolean): Boolean {
        if (dismissing) return false
        expandedDuringDrag = false
        if (emerging) {
            val currentX = translationX
            emergeAnimator?.let {
                it.removeAllUpdateListeners()
                it.removeAllListeners()
                it.cancel()
            }
            emergeAnimator = null
            finishEmergence()
            translationX = currentX
        }
        // Finger owns position now; a return animator can't keep writing over it.
        if (horizontal) {
            animator.cancelHorizontalMotion()
            if (translationY != 0f) animator.settleTranslationY()
        } else {
            animator.cancelVerticalMotion()
            if (translationX != 0f) animator.settleTranslationX()
        }
        gestureStartX = translationX
        gestureStartY = translationY
        return true
    }

    // Screen-space displacement, positive means downward.
    private var expandedDuringDrag = false
    fun onDragUpdate(dy: Float) {
        dragOffset = dy
        translationY = gestureStartY + dy
        (parent as? View)?.invalidate()
        if (dy > geometry.dp(16f) && !isExpanded && !expandedDuringDrag) {
            callbacks?.onUserExpand(this)
            expandedDuringDrag = isExpanded
        }
    }

    // finger lifted, work out what the drag meant
    fun onDragEnd(dy: Float) {
        animator.settleTranslationY()
        (parent as? View)?.invalidate()
        if (expandedDuringDrag && dy >= 0f) {
            expandedDuringDrag = false
            dragOffset = 0f
            return
        }
        val threshold = geometry.dp(24f)
        when {
            dy < -threshold && isExpanded -> callbacks?.onUserCollapse(this)
            dy < -threshold -> callbacks?.onUserDismiss(this)
            dy > threshold && !isExpanded -> callbacks?.onUserExpand(this)
            dy > threshold && isExpanded && signal?.kind == SignalKind.MEDIA -> {
                if (dy > geometry.dp(IslandConstants.DRAG_COMMIT_THRESHOLD_DP)) {
                    resizeToMediaMax()
                }
            }
        }
        dragOffset = 0f
        expandedDuringDrag = false
    }

    // horizontal drag, with a rubber band on it
    fun onHorizontalDragUpdate(dx: Float) {
        val rubberBanded = gestureStartX + dx * IslandConstants.RUBBER_BAND_FACTOR
        translationX = rubberBanded
        // fades from fully opaque to 0.6 by the dismiss distance
        val dismissPx = geometry.dp(DISMISS_THRESHOLD_DP).toFloat()
        alpha = (1f - (kotlin.math.abs(rubberBanded) / dismissPx) * 0.4f).coerceIn(0.6f, 1f)
        if (animator.mode == AnimationMode.DYNAMIC) {
            // taffy pull, the side you're dragging away from stays anchored
            val pull = (kotlin.math.abs(rubberBanded) / dismissPx).coerceIn(0f, 1.5f)
            pivotX = if (rubberBanded > 0f) 0f else width.toFloat()
            pivotY = height / 2f
            scaleX = 1f + 0.10f * pull
            scaleY = 1f - 0.07f * pull
        }
        (parent as? View)?.invalidate()
    }

    // far or fast enough means dismiss, otherwise snap back
    fun onHorizontalDragEnd(dx: Float, velocityX: Float) {
        val dismissPx = geometry.dp(DISMISS_THRESHOLD_DP).toFloat()
        // Judge the finger, not the rubber band dragging behind it.
        val shouldDismiss = kotlin.math.abs(dx) > dismissPx ||
            kotlin.math.abs(velocityX) > FLING_VELOCITY_THRESHOLD

        if (shouldDismiss && (dx != 0f || velocityX != 0f)) {
            // fling velocity wins over distance when picking a direction
            val direction = if (kotlin.math.abs(velocityX) > FLING_VELOCITY_THRESHOLD) {
                if (velocityX > 0f) 1f else -1f
            } else {
                if (dx > 0f) 1f else -1f
            }
            dismiss(direction)
            callbacks?.onUserDismiss(this)
        } else {
            // didn't commit, ease it back
            animator.settleTranslationX()
            (parent as? View)?.invalidate()
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
        // only undo the jelly if it was ours, a drag stretch has to survive into the fling
        if (emerging) {
            scaleX = 1f
            scaleY = 1f
        }
        emerging = false
        pendingRebloom = false
    }

    private fun startEmergence() {
        emerging = true
        emergeP = 0f
        background = null
        glassLayer.visibility = View.VISIBLE
        blurLayer.visibility = View.VISIBLE
        clipToOutline = true
        setGlassCornerRadius(emergeCornerRadius)
        applyEmergeProgress(0f)
        val dur = if (pendingRebloom) 420L else IslandConstants.DUR_EMERGE
        emergeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            // springOut does the easing
            interpolator = android.view.animation.LinearInterpolator()
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

    // comes in past the corner, stretched while it's quick, little squash on landing
    private fun applyEmergeProgress(p: Float) {
        emergeP = p
        val g = springOut(p, 0.74f, 10.5f)
        translationX = offscreenX() * (1f - g)
        val fast = kotlin.math.sin(Math.PI.toFloat() * (p / 0.42f).coerceIn(0f, 1f))
        val land = kotlin.math.sin(Math.PI.toFloat() * ((p - 0.36f) / 0.44f).coerceIn(0f, 1f))
        applyJelly(0.1f * fast - 0.05f * land)
        content.alpha = smoothstep(0.12f, 0.55f, p)
        (parent as? View)?.invalidate()
    }

    private fun finishEmergence() {
        emerging = false
        translationX = 0f
        scaleX = 1f
        scaleY = 1f
        content.alpha = 1f
        glassLayer.visibility = View.VISIBLE
        blurLayer.visibility = View.VISIBLE
        clipToOutline = true
        updateRefraction()
        invalidate()
        startSpecularSweep()
        if (pendingRebloom) {
            pendingRebloom = false
            // content is already bound, only the radius is left
            setGlassCornerRadius(geometry.cornerExpanded)
            isExpanded = true
            callbacks?.onExpandedStateChanged(this, true)
        }
        updateBackdropBlur()
        invalidateOutline()
        // no spring here, so fire the settle callback by hand
        callbacks?.onFormSettled(this, isExpanded)
    }

    // lp is the size we're heading to, width is stale on the first frame of show()
    private fun offscreenX(): Float {
        val w = maxOf(width, layoutParams?.width ?: 0)
        val out = (geometry.expandedSideMargin + w + geometry.dp(8f)).toFloat()
        return if (cluster == Cluster.LEFT) -out else out
    }

    // +stretch long and thin, -squash short and fat. pinned at the inner end, so the
    // outer end does the moving. big cards get less, 10% of a card is a lot of pixels
    private fun applyJelly(amount: Float) {
        val w = maxOf(width, layoutParams?.width ?: 0).coerceAtLeast(1)
        pivotX = if (cluster == Cluster.LEFT) w.toFloat() else 0f
        pivotY = maxOf(height, layoutParams?.height ?: 0) / 2f
        val sx = 1f + amount * minOf(geometry.capsuleMaxWidth.toFloat() / w, 1f)
        scaleX = sx
        scaleY = 1f / sx
    }

    // closed form wobbly spring 0->1, t is 0..1 of the anim, pinned to 1 at the very end
    private fun springOut(t: Float, zeta: Float, omega: Float): Float {
        if (t <= 0f) return 0f
        val wd = omega * kotlin.math.sqrt(1f - zeta * zeta)
        val e = kotlin.math.exp(-zeta * omega * t)
        val v = 1f - e * (kotlin.math.cos(wd * t) + zeta * omega / wd * kotlin.math.sin(wd * t))
        return v + (1f - v) * smoothstep(0.86f, 1f, t)
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
        if (presenter is com.android.systemui.island.presenter.MediaPresenter ||
            presenter?.showsMiniStatus() == false
        ) {
            detachMini()
            return
        }
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
        animateGlassCornerRadius(geometry.cornerCapsule, IslandConstants.DUR_COLLAPSE)
        applyTopNudge(expanded = false)
        form = Form.CAPSULE
    }

    // rebind the card content and grow to full size
    private fun applyExpandedContent() {
        content.removeAllViews()
        presenter?.bindExpanded(content)
        attachMini()
        val screenW = geometry.screenWidth
        val targetW = presenter?.expandedWidthPx()?.takeIf { it > 0 }
            ?: (screenW - geometry.expandedSideMargin * 2).coerceAtMost(geometry.expandedMaxWidth)
        val targetH = presenter?.expandedHeightPx()?.takeIf { it > 0 }
            ?: geometry.expandedHeightNotif
        animator.animateSize(targetW, targetH, expanding = true)
        animateGlassCornerRadius(geometry.cornerExpanded, IslandConstants.DUR_EXPAND)
        applyTopNudge(expanded = true)
        form = Form.EXPANDED
    }

    // expanded cards drop a few px, the punch-hole chews the top edge otherwise
    private fun applyTopNudge(expanded: Boolean) {
        val lp = layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        val target = if (expanded) geometry.expandedTopY else geometry.collapsedTopY
        if (lp.topMargin != target) {
            lp.topMargin = target
            requestLayout()
        }
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
        tintColor = tint.background?.let { flatten(it) } ?: themeBackground()
        rimTopColor = withAlpha(tint.accent, 0x2E)
        glass.setTintColor(tintColor)
        glass.setRim(
            rimTopColor,
            withAlpha(tint.accent, 0x0A),
        )
        glass.setNeonColor(tint.accent)
        glassLayer.invalidate()
    }

    private fun applyTheme() {
        accentColor = if (dark) 0xFFFFFFFF.toInt() else 0xFF101012.toInt()
        tintColor = themeBackground()
        glass.setTintColor(tintColor)
        glass.setGrainAlpha(if (dark) 0x14 else 0x10)
        if (dark) {
            rimTopColor = 0x2EFFFFFF.toInt()
            glass.setRim(rimTopColor, 0x0AFFFFFF.toInt())
        } else {
            // light theme wants a brighter top and a soft dark bottom
            rimTopColor = 0x55FFFFFF.toInt()
            glass.setRim(rimTopColor, 0x14000000.toInt())
        }
        glass.setNeonColor(accentColor)
        glassLayer.invalidate()
    }

    // flat chip tint and glass tint, blended by glassT so expand/collapse crossfades
    private fun themeBackground(): Int {
        // blur fully off in settings = nothing behind to fake, stay properly solid
        if (!blurEnabled && !refractionAllowed()) {
            return if (dark) 0xF2101012.toInt() else 0xF7F3F3F6.toInt()
        }
        // chip = smoked glass, no blur. hwc blends alpha for free, it's the blur that costs.
        // keeps it close to the card's look so the crossfade isn't doing all the work
        val smoked = if (dark) SMOKED_DARK else SMOKED_LIGHT
        // lean on the backdrop blur, the tint only has to carry contrast
        val glassy = if (dark) 0xA8101012.toInt() else 0xC2F3F3F6.toInt()
        return blendArgb(smoked, glassy, glassT)
    }

    // presenter tints are made for blur and can be really see-through. floor them at smoked
    // when there's no blur behind, or album art tints turn the chip into a window
    private fun flatten(color: Int): Int {
        val a = color ushr 24
        val glassOn = blurEnabled || refractionAllowed()
        val floor = when {
            !glassOn -> 0xF2
            dark -> SMOKED_DARK ushr 24
            else -> SMOKED_LIGHT ushr 24
        }
        val solid = maxOf(a, floor)
        val alpha = (solid + (a - solid) * glassT).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun blendArgb(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun startSpecularSweep() {
        specularAnimator?.cancel()
        glass.setSpecularProgress(0f)
        glassLayer.invalidate()
        specularAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = com.android.systemui.island.IslandConstants.DUR_SPECULAR
            // glass now lives on glassLayer, so kick that view by hand
            addUpdateListener { a ->
                glass.setSpecularProgress(a.animatedValue as Float)
                glassLayer.invalidate()
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        glass.setSpecularProgress(-1f)
                        glassLayer.invalidate()
                        if (specularAnimator === animation) specularAnimator = null
                    }
                },
            )
        }
        specularAnimator?.start()
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
            addUpdateListener { a ->
                glass.setNeonProgress(a.animatedValue as Float)
                glassLayer.invalidate()
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        glass.setNeonProgress(-1f)
                        glassLayer.invalidate()
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
            addUpdateListener { a ->
                glass.setChromaProgress(a.animatedValue as Float)
                glassLayer.invalidate()
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        glass.setChromaProgress(-1f)
                        glassLayer.invalidate()
                        chromaAnimator = null
                    }
                },
            )
        }
        chromaAnimator?.start()
    }

    // automix edge aura, breathes for the whole blend until handover
    fun startChromaAura(durationMs: Long) {
        auraAnimator?.cancel()
        glass.setAuraProgress(0f)
        val dur = durationMs.coerceIn(900L, 32_000L)
        auraAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                glass.setAuraProgress(a.animatedValue as Float)
                glassLayer.invalidate()
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        glass.setAuraProgress(-1f)
                        glassLayer.invalidate()
                        auraAnimator = null
                    }
                },
            )
        }
        auraAnimator?.start()
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

    // our rect in the parent's coordinates
    fun boundsOnParent(): Rect {
        val r = Rect()
        r.set(left, top, right, bottom)
        return r
    }

    override fun onDetachedFromWindow() {
        touchHandler.cancel()
        cancelEmergence()
        cancelDrain()
        glassAnim?.cancel()
        glassAnim = null
        specularAnimator?.cancel()
        specularAnimator = null
        glass.setSpecularProgress(-1f)
        backdrop?.setBlurRadius(0)
        backdrop?.let { refraction.clear(it) }
        backdrop = null
        blurLayer.background = null
        super.onDetachedFromWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                windowManager.removeCrossWindowBlurEnabledListener(blurListener)
            }
        }
        animator.cancelAll()
        cornerAnimator?.cancel()
        cornerAnimator = null
        neonAnimator?.cancel()
        neonAnimator = null
        chromaAnimator?.cancel()
        chromaAnimator = null
        auraAnimator?.cancel()
        auraAnimator = null
        destroyPresenter()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        // how far you drag before it counts as a dismiss
        private const val DISMISS_THRESHOLD_DP = 80f
        // or how fast, in px per second
        private const val FLING_VELOCITY_THRESHOLD = 1200f
        // expanded cards get this multiple of the bubble refraction slider
        private const val EXPANDED_REFRACT_MULTIPLIER = 1.2f
        // roughly the morph length so the glass lands with the shape
        private const val GLASS_FADE_MS = 260L
        // collapsed chip tint, ~77% dark / ~84% light. tweak here if it reads too see-through
        private const val SMOKED_DARK = 0xC4101012.toInt()
        private const val SMOKED_LIGHT = 0xD6F3F3F6.toInt()

        fun layoutParams(gravity: Int): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity,
            )
    }
}
