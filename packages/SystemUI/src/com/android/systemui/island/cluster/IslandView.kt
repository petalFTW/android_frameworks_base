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
    private val metaball = MetaballPathRenderer()
    private val shaderMetaball: MetaballShaderRenderer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) MetaballShaderRenderer() else null

    private var presenter: IslandPresenter? = null
    private var signal: IslandSignal? = null
    private var form: Form = Form.CAPSULE
    private var dragOffset = 0f
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    private var emerging = false
    private var emergeP = 0f
    private var emergeAnimator: android.animation.ValueAnimator? = null

    // non-zero while sliding out on dismiss; drives the status-bar hand-off
    private var dismissing = false
    private var dismissDistance = 1f

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
        updateBackdropBlur()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
            }
        }
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
        animator.onSettled = { callbacks?.onFormSettled(this, isExpanded) }
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

    // blur on or off, tint goes darker when there's no blur
    private fun updateBackdropBlur() {
        blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            windowManager.isCrossWindowBlurEnabled && PetalConfig.getIslandBlurScale(context) > 0f
        val radius = if (blurEnabled) {
            (geometry.dp(IslandConstants.BLUR_BEHIND_RADIUS_DP) *
                PetalConfig.getIslandBlurScale(context)).toInt()
        } else 0
        backdrop?.setBlurRadius(radius)
        updateRefraction()
        applyTheme()
        blurLayer.invalidate()
        glassLayer.invalidate()
    }

    fun refreshBackdropBlur() = updateBackdropBlur()

    private fun isBackdropRefractionEnabled(): Boolean =
        windowManager.isCrossWindowBlurEnabled && PetalConfig.isIslandBubbleEnabled(context)

    // SF grabs the windows behind this glass thing.
    private fun updateRefraction() {
        val popout = PetalConfig.getIslandPopoutScale(context)
        val chroma = PetalConfig.getIslandChromaScale(context)
        val drawable = backdrop ?: return
        if (emerging || !isBackdropRefractionEnabled()) {
            refraction.clear(drawable)
            glass.setSlab(0f, 0f)
            return
        }
        // Big cards need more lens; live height keeps it from popping.
        val span = (geometry.expandedHeightNotif - geometry.capsuleHeight).coerceAtLeast(1)
        val expandT = ((height - geometry.capsuleHeight).toFloat() / span).coerceIn(0f, 1f)
        val strength = popout * (1f + expandT * (EXPANDED_REFRACT_MULTIPLIER - 1f))
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
        // Blur this blob, not the whole bloody screen.
        backdrop = viewRootImpl.createBackgroundBlurDrawable().apply {
            setCornerRadius(cornerRadius())
        }
        blurLayer.background = backdrop
        updateBackdropBlur()
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
        specularAnimator?.cancel()
        visibility = View.VISIBLE
        cancelEmergence()
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
        updateBackdropBlur()

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
                glass.setSpecularProgress(-1f)
            }
            AnimationMode.CLASSIC -> {
                alpha = 0f
                animator.animateAlpha(1f, 300L)
                isExpanded = this.form == Form.EXPANDED
                applyCollapsedSize(capsuleW)
                if (isExpanded) applyExpandedContent()
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
        callbacks?.onExpandedStateChanged(this, false)
    }

    // park the blob off the top while qs is up, drag it back after
    fun slideAway(hidden: Boolean, animate: Boolean = true) {
        if (hidden) touchHandler.cancel()
        val h = if (height > 0) height else geometry.capsuleHeight
        val t = if (top > 0) top else geometry.collapsedTopY
        val target = if (hidden) -(t + h + geometry.dp(24f)).toFloat() else 0f
        if (animate) {
            animator.animateTranslationY(target)
        } else {
            animator.cancelAll()
            translationY = target
        }
        (parent as? View)?.invalidate()
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
        val distance = (width + geometry.dp(24f)) * direction
        dismissing = true
        dismissDistance = kotlin.math.abs(distance).coerceAtLeast(1f)
        (parent as? View)?.invalidate()
        animator.animateTranslationX(distance, 220L) {
            dismissing = false
            destroyPresenter()
            signal = null
            backdrop?.setBlurRadius(0)
            backdrop?.let { refraction.clear(it) }
            blurLayer.visibility = View.GONE
            glassLayer.visibility = View.GONE
            visibility = View.GONE
            translationX = 0f
            alpha = 1f
            (parent as? View)?.invalidate()
        }
    }

    fun presenterForSignal(): IslandPresenter? = presenter

    // 0..1 presence, so the bar icons fade with this thing.
    fun presenceProgress(): Float {
        if (visibility != View.VISIBLE) return 0f
        return when {
            emerging -> emergeP.coerceIn(0f, 1f)
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
        emerging = false
        pendingRebloom = false
    }

    private fun startEmergence() {
        emerging = true
        updateRefraction()
        emergeP = 0f
        background = null
        glassLayer.visibility = View.GONE
        blurLayer.visibility = View.GONE
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
        if (presenter is com.android.systemui.island.presenter.MediaPresenter) {
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
        val targetW = (screenW - geometry.expandedSideMargin * 2)
            .coerceAtMost(geometry.expandedMaxWidth)
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
        tintColor = tint.background ?: themeBackground()
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

    // Keep the lens visible even when the user turns the blur radius down to zero.
    private fun themeBackground(): Int =
        when {
            !blurEnabled && !isBackdropRefractionEnabled() ->
                if (dark) 0xF2101012.toInt() else 0xF7F3F3F6.toInt()
            // lean on the backdrop blur, the tint only has to carry contrast
            dark -> 0xA8101012.toInt()
            else -> 0xC2F3F3F6.toInt()
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

        fun layoutParams(gravity: Int): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity,
            )
    }
}
