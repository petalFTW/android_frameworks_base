package com.android.systemui.navigationbar.gestural

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.MathUtils.min
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.util.LatencyTracker
import com.android.internal.graphics.drawable.PetalGlassDrawable
import com.android.systemui.navigationbar.gestural.BackPanelController.DelayedOnAnimationEndListener

private const val TAG = "BackPanel"
private const val DEBUG = false

class BackPanel(context: Context, private val latencyTracker: LatencyTracker) : View(context) {

    var arrowsPointLeft = false
        set(value) {
            if (field != value) {
                invalidate()
                field = value
            }
        }

    // Arrow color and shape
    private val arrowPath = Path()
    private val arrowPaint = Paint()

    // Arrow background color and shape
    private var arrowBackgroundPaint = Paint()
    // little back blob gets sheen without the backdrop churn
    private val glass = PetalGlassDrawable(this, 24f, 0f, false)

    // True if the panel is currently on the left of the screen
    var isLeftPanel = false

    /** Used to track back arrow latency from [android.view.MotionEvent.ACTION_DOWN] to [onDraw] */
    private var trackingBackArrowLatency = false

    /** The length of the arrow measured horizontally. Used for animating [arrowPath] */
    private var arrowLength =
        AnimatedFloat(
            name = "arrowLength",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
        )

    // half the arrow height, springs do the rest
    var arrowHeight =
        AnimatedFloat(
            name = "arrowHeight",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ROTATION_DEGREES,
        )

    val backgroundWidth =
        AnimatedFloat(
            name = "backgroundWidth",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = 0f,
        )

    val backgroundHeight =
        AnimatedFloat(
            name = "backgroundHeight",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = 0f,
        )

    // corners nearest the screen edge
    val backgroundEdgeCornerRadius = AnimatedFloat("backgroundEdgeCornerRadius")

    // corners on the dragged-out end
    val backgroundFarCornerRadius = AnimatedFloat("backgroundFarCornerRadius")

    var scale =
        AnimatedFloat(
            name = "scale",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_SCALE,
            minimumValue = 0f,
        )

    val scalePivotX =
        AnimatedFloat(
            name = "scalePivotX",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_PIXELS,
            minimumValue = backgroundWidth.pos / 2,
        )

    // how far this sucker sticks out from the edge
    var horizontalTranslation = AnimatedFloat(name = "horizontalTranslation")

    var arrowAlpha =
        AnimatedFloat(
            name = "arrowAlpha",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ALPHA,
            minimumValue = 0f,
            maximumValue = 1f,
        )

    val backgroundAlpha =
        AnimatedFloat(
            name = "backgroundAlpha",
            minimumVisibleChange = SpringAnimation.MIN_VISIBLE_CHANGE_ALPHA,
            minimumValue = 0f,
            maximumValue = 1f,
        )

    var triggerLongSwipe = false

    private val allAnimatedFloat =
        setOf(
            arrowLength,
            arrowHeight,
            backgroundWidth,
            backgroundEdgeCornerRadius,
            backgroundFarCornerRadius,
            scalePivotX,
            scale,
            horizontalTranslation,
            arrowAlpha,
            backgroundAlpha,
        )

    // vertical nudge for the whole capsule
    var verticalTranslation = AnimatedFloat("verticalTranslation")

    /** Use for drawing debug info. Can only be set if [DEBUG]=true */
    var drawDebugInfo: ((canvas: Canvas) -> Unit)? = null
        set(value) {
            if (DEBUG) field = value
        }

    internal fun updateArrowPaint(arrowThickness: Float) {
        arrowPaint.strokeWidth = arrowThickness

        val isDeviceInNightTheme =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

        arrowPaint.color =
            context.getColor(
                if (isDeviceInNightTheme) {
                    com.android.internal.R.color.materialColorOnSecondaryContainer
                } else {
                    com.android.internal.R.color.materialColorOnSecondaryFixed
                }
            )

        arrowBackgroundPaint.color =
            context.getColor(
                if (isDeviceInNightTheme) {
                    com.android.internal.R.color.materialColorSecondaryContainer
                } else {
                    com.android.internal.R.color.materialColorSecondaryFixedDim
                }
            )
    }

    inner class AnimatedFloat(
        name: String,
        private val minimumVisibleChange: Float? = null,
        private val minimumValue: Float? = null,
        private val maximumValue: Float? = null,
    ) {

        // The resting position when not stretched by a touch drag
        private var restingPosition = 0f

        // The current position as updated by the SpringAnimation
        var pos = 0f
            private set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        private val animation: SpringAnimation
        var spring: SpringForce
            get() = animation.spring
            set(value) {
                animation.cancel()
                animation.spring = value
            }

        val isRunning: Boolean
            get() = animation.isRunning

        fun addEndListener(listener: DelayedOnAnimationEndListener) {
            animation.addEndListener(listener)
        }

        init {
            val floatProp =
                object : FloatPropertyCompat<AnimatedFloat>(name) {
                    override fun setValue(animatedFloat: AnimatedFloat, value: Float) {
                        animatedFloat.pos = value
                    }

                    override fun getValue(animatedFloat: AnimatedFloat): Float = animatedFloat.pos
                }
            animation =
                SpringAnimation(this, floatProp).apply {
                    spring = SpringForce()
                    this@AnimatedFloat.minimumValue?.let { setMinValue(it) }
                    this@AnimatedFloat.maximumValue?.let { setMaxValue(it) }
                    this@AnimatedFloat.minimumVisibleChange?.let { minimumVisibleChange = it }
                }
        }

        fun snapTo(newPosition: Float) {
            animation.cancel()
            restingPosition = newPosition
            animation.spring.finalPosition = newPosition
            pos = newPosition
        }

        fun snapToRestingPosition() {
            snapTo(restingPosition)
        }

        fun stretchTo(
            stretchAmount: Float,
            startingVelocity: Float? = null,
            springForce: SpringForce? = null,
        ) {
            animation.apply {
                startingVelocity?.let {
                    cancel()
                    setStartVelocity(it)
                }
                springForce?.let { spring = springForce }
                animateToFinalPosition(restingPosition + stretchAmount)
            }
        }

        // stretch toward the target without messing with the resting spot
        fun stretchBy(finalPosition: Float?, amount: Float) {
            val stretchedAmount = amount * ((finalPosition ?: 0f) - restingPosition)
            animation.animateToFinalPosition(restingPosition + stretchedAmount)
        }

        fun updateRestingPosition(pos: Float?, animated: Boolean = true) {
            if (pos == null) return

            restingPosition = pos
            if (animated) {
                animation.animateToFinalPosition(restingPosition)
            } else {
                snapTo(restingPosition)
            }
        }

        fun cancel() = animation.cancel()
    }

    init {
        visibility = GONE
        arrowPaint.apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
        }
        arrowBackgroundPaint.apply {
            style = Paint.Style.FILL
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }

    private fun calculateArrowPath(dx: Float, dy: Float): Path {
        arrowPath.reset()
        arrowPath.moveTo(dx, -dy)
        arrowPath.lineTo(0f, 0f)
        arrowPath.lineTo(dx, dy)
        arrowPath.moveTo(dx, -dy)
        if (triggerLongSwipe) {
            arrowPath.addPath(arrowPath, arrowPaint.strokeWidth * 2.0f * -1, 0.0f)
        }
        return arrowPath
    }

    fun addAnimationEndListener(
        animatedFloat: AnimatedFloat,
        endListener: DelayedOnAnimationEndListener,
    ): Boolean {
        return if (animatedFloat.isRunning) {
            animatedFloat.addEndListener(endListener)
            true
        } else {
            endListener.run()
            false
        }
    }

    fun cancelAnimations() {
        allAnimatedFloat.forEach { it.cancel() }
    }

    fun setStretch(
        horizontalTranslationStretchAmount: Float,
        arrowStretchAmount: Float,
        arrowAlphaStretchAmount: Float,
        backgroundAlphaStretchAmount: Float,
        backgroundWidthStretchAmount: Float,
        backgroundHeightStretchAmount: Float,
        edgeCornerStretchAmount: Float,
        farCornerStretchAmount: Float,
        fullyStretchedDimens: EdgePanelParams.BackIndicatorDimens,
    ) {
        horizontalTranslation.stretchBy(
            finalPosition = fullyStretchedDimens.horizontalTranslation,
            amount = horizontalTranslationStretchAmount,
        )
        arrowLength.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.length,
            amount = arrowStretchAmount,
        )
        arrowHeight.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.height,
            amount = arrowStretchAmount,
        )
        arrowAlpha.stretchBy(
            finalPosition = fullyStretchedDimens.arrowDimens.alpha,
            amount = arrowAlphaStretchAmount,
        )
        backgroundAlpha.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.alpha,
            amount = backgroundAlphaStretchAmount,
        )
        backgroundWidth.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.width,
            amount = backgroundWidthStretchAmount,
        )
        backgroundHeight.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.height,
            amount = backgroundHeightStretchAmount,
        )
        backgroundEdgeCornerRadius.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.edgeCornerRadius,
            amount = edgeCornerStretchAmount,
        )
        backgroundFarCornerRadius.stretchBy(
            finalPosition = fullyStretchedDimens.backgroundDimens.farCornerRadius,
            amount = farCornerStretchAmount,
        )
    }

    fun popOffEdge(startingVelocity: Float) {
        scale.stretchTo(stretchAmount = 0f, startingVelocity = startingVelocity * -.8f)
        horizontalTranslation.stretchTo(stretchAmount = 0f, startingVelocity * 200f)
    }

    fun popScale(startingVelocity: Float) {
        scalePivotX.snapTo(backgroundWidth.pos / 2)
        scale.stretchTo(stretchAmount = 0f, startingVelocity = startingVelocity)
    }

    fun popArrowAlpha(startingVelocity: Float, springForce: SpringForce? = null) {
        arrowAlpha.stretchTo(
            stretchAmount = 0f,
            startingVelocity = startingVelocity,
            springForce = springForce,
        )
    }

    fun resetStretch() {
        backgroundAlpha.snapTo(1f)
        verticalTranslation.snapTo(0f)
        scale.snapTo(1f)

        horizontalTranslation.snapToRestingPosition()
        arrowLength.snapToRestingPosition()
        arrowHeight.snapToRestingPosition()
        arrowAlpha.snapToRestingPosition()
        backgroundWidth.snapToRestingPosition()
        backgroundHeight.snapToRestingPosition()
        backgroundEdgeCornerRadius.snapToRestingPosition()
        backgroundFarCornerRadius.snapToRestingPosition()
    }

    /** Updates resting arrow and background size not accounting for stretch */
    internal fun setRestingDimens(
        restingParams: EdgePanelParams.BackIndicatorDimens,
        animate: Boolean = true,
    ) {
        horizontalTranslation.updateRestingPosition(restingParams.horizontalTranslation)
        scale.updateRestingPosition(restingParams.scale)
        backgroundAlpha.updateRestingPosition(restingParams.backgroundDimens.alpha)

        arrowAlpha.updateRestingPosition(restingParams.arrowDimens.alpha, animate)
        arrowLength.updateRestingPosition(restingParams.arrowDimens.length, animate)
        arrowHeight.updateRestingPosition(restingParams.arrowDimens.height, animate)
        scalePivotX.updateRestingPosition(restingParams.scalePivotX, animate)
        backgroundWidth.updateRestingPosition(restingParams.backgroundDimens.width, animate)
        backgroundHeight.updateRestingPosition(restingParams.backgroundDimens.height, animate)
        backgroundEdgeCornerRadius.updateRestingPosition(
            restingParams.backgroundDimens.edgeCornerRadius,
            animate,
        )
        backgroundFarCornerRadius.updateRestingPosition(
            restingParams.backgroundDimens.farCornerRadius,
            animate,
        )
    }

    fun animateVertically(yPos: Float) = verticalTranslation.stretchTo(yPos)

    fun setSpring(
        horizontalTranslation: SpringForce? = null,
        verticalTranslation: SpringForce? = null,
        scale: SpringForce? = null,
        arrowLength: SpringForce? = null,
        arrowHeight: SpringForce? = null,
        arrowAlpha: SpringForce? = null,
        backgroundAlpha: SpringForce? = null,
        backgroundFarCornerRadius: SpringForce? = null,
        backgroundEdgeCornerRadius: SpringForce? = null,
        backgroundWidth: SpringForce? = null,
        backgroundHeight: SpringForce? = null,
    ) {
        arrowLength?.let { this.arrowLength.spring = it }
        arrowHeight?.let { this.arrowHeight.spring = it }
        arrowAlpha?.let { this.arrowAlpha.spring = it }
        backgroundAlpha?.let { this.backgroundAlpha.spring = it }
        backgroundFarCornerRadius?.let { this.backgroundFarCornerRadius.spring = it }
        backgroundEdgeCornerRadius?.let { this.backgroundEdgeCornerRadius.spring = it }
        scale?.let { this.scale.spring = it }
        backgroundWidth?.let { this.backgroundWidth.spring = it }
        backgroundHeight?.let { this.backgroundHeight.spring = it }
        horizontalTranslation?.let { this.horizontalTranslation.spring = it }
        verticalTranslation?.let { this.verticalTranslation.spring = it }
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        val edgeCorner = backgroundEdgeCornerRadius.pos.coerceAtLeast(0f)
        val farCorner = backgroundFarCornerRadius.pos.coerceAtLeast(0f)
        val halfHeight = backgroundHeight.pos / 2
        val canvasWidth = width
        val backgroundWidth = backgroundWidth.pos
        val scalePivotX = scalePivotX.pos

        canvas.save()

        if (!isLeftPanel) canvas.scale(-1f, 1f, canvasWidth / 2.0f, 0f)

        canvas.translate(horizontalTranslation.pos, height * 0.5f + verticalTranslation.pos)

        canvas.scale(scale.pos, scale.pos, scalePivotX, 0f)

        glass.setBounds(0, -halfHeight.toInt(), backgroundWidth.toInt(), halfHeight.toInt())
        glass.setGlassCorners(edgeCorner, farCorner)
        glass.setColor((arrowBackgroundPaint.color and 0x00FFFFFF) or 0xA8000000.toInt())
        glass.alpha = (255 * backgroundAlpha.pos).toInt().coerceIn(0, 255)
        glass.draw(canvas)

        val dx = arrowLength.pos
        val dy = arrowHeight.pos

        // keep the arrow centred in this thing
        val arrowOffset = (backgroundWidth - dx) / 2
        canvas.translate(
            /* dx= */ arrowOffset,
            /* dy= */ 0f, /* pass 0 for the y position since the canvas was already translated */
        )

        val arrowPointsAwayFromEdge = !arrowsPointLeft.xor(isLeftPanel)
        if (arrowPointsAwayFromEdge) {
            canvas.apply {
                scale(-1f, 1f, 0f, 0f)
                translate(-dx, 0f)
            }
        }

        val arrowPath = calculateArrowPath(dx = dx, dy = dy)
        val arrowPaint =
            arrowPaint.apply { alpha = (255 * min(arrowAlpha.pos, backgroundAlpha.pos)).toInt() }
        if (isLeftPanel) {
            canvas.scale(-1f, 1f, dx / 2f, dy / 2f)
        }
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()

        if (trackingBackArrowLatency) {
            latencyTracker.onActionEnd(LatencyTracker.ACTION_SHOW_BACK_ARROW)
            trackingBackArrowLatency = false
        }

        if (DEBUG) drawDebugInfo?.invoke(canvas)
    }

    fun startTrackingShowBackArrowLatency() {
        latencyTracker.onActionStart(LatencyTracker.ACTION_SHOW_BACK_ARROW)
        trackingBackArrowLatency = true
    }

}
