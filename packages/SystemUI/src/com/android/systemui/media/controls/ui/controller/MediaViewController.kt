/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.controller

import android.animation.Animator
import android.animation.AnimatorInflater
import android.animation.AnimatorSet
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.MATCH_CONSTRAINT
import com.android.app.tracing.traceSection
import com.android.systemui.media.controls.ui.controller.MediaCarouselController.Companion.calculateAlpha
import com.android.systemui.media.controls.ui.view.GutsViewHolder
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.ui.view.MediaViewHolder.Companion.bodyMediumTF
import com.android.systemui.media.controls.ui.view.MediaViewHolder.Companion.headlineSmallTF
import com.android.systemui.media.controls.ui.view.MediaViewHolder.Companion.labelMediumTF
import com.android.systemui.media.controls.ui.view.MediaViewHolder.Companion.titleMediumEmphasizedTF
import com.android.systemui.media.controls.ui.view.MediaViewHolder.Companion.titleMediumTF
import com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel
import com.android.systemui.media.remedia.shared.flag.MediaControlsInComposeFlag
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.animation.MeasurementOutput
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.animation.TransitionLayoutController
import com.android.systemui.util.animation.TransitionViewState
import java.lang.Float.max
import java.lang.Float.min
import javax.inject.Inject
import org.petalos.config.PetalConfig

// Keeps one player view lined up with its host and playback state.
open class MediaViewController
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    private val mediaHostStatesManager: MediaHostStatesManager,
    private val logger: MediaViewLogger,
    private val seekBarViewModel: SeekBarViewModel,
) {

    companion object {
        @JvmField val GUTS_ANIMATION_DURATION = 234L
    }

    /** A listener when the current dimensions of the player change */
    lateinit var sizeChangedListener: () -> Unit
    private var firstRefresh: Boolean = true
    @VisibleForTesting private var transitionLayout: TransitionLayout? = null
    private val layoutController = TransitionLayoutController()
    private var animationDelay: Long = 0
    private var animationDuration: Long = 0
    private var animateNextStateChange: Boolean = false
    private var lastPetalStyle = com.android.systemui.petalos.PetalMediaStyle.Stock
    private var petalButtonAccent: Int? = null
    // same recipe as the qs tiles, so the player reads as the same glass
    private val petalCardBackgroundDelegate = lazy {
        val density = context.resources.displayMetrics.density
        val glass = com.android.systemui.island.render.LiquidGlassDrawable().apply {
            setCornerRadius(24f * density)
            setTintColor(
                android.graphics.Color.argb(
                    kotlin.math.round(255f * com.android.systemui.petalos.PetalQsSkin.GlassFillAlpha).toInt(),
                    0x33, 0x33, 0x3D,
                )
            )
            setRim(0x99FFFFFF.toInt(), 0x14FFFFFF.toInt())
            setRimWidth(1.1f * density)
            setGrainAlpha(0x16)
            setSheenStrength(0x55)
            setSpecularProgress(0.16f)
        }
        com.android.systemui.petalos.PetalMediaPaintDrawable(glass) {
            transitionLayout?.findViewById<android.widget.ImageView>(R.id.petal_media_cover)
        }
    }
    private val petalCardBackground by petalCardBackgroundDelegate
    private val petalRibbonBackground by lazy {
        com.android.systemui.petalos.PetalMediaRibbonDrawable(context.resources.displayMetrics.density)
    }
    private var petalRibbonBackdrop: com.android.systemui.petalos.PetalMediaRibbonBackdrop? = null
    private val measurement = MeasurementOutput(0, 0)

    /** A map containing all viewStates for all locations of this mediaState */
    private val viewStates: MutableMap<CacheKey, TransitionViewState?> = mutableMapOf()

    // Where the player lands once the animation settles.
    @MediaLocation
    var currentEndLocation: Int = MediaHierarchyManager.LOCATION_UNKNOWN
        set(value) {
            if (field != value) {
                field = value
            }
        }

    /** The starting location of the view where it starts for all animations and transitions */
    @MediaLocation private var currentStartLocation: Int = MediaHierarchyManager.LOCATION_UNKNOWN

    /** The progress of the transition or 1.0 if there is no transition happening */
    private var currentTransitionProgress: Float = 1.0f

    /** A temporary state used to store intermediate measurements. */
    private val tmpState = TransitionViewState()

    /** A temporary state used to store intermediate measurements. */
    private val tmpState2 = TransitionViewState()

    /** A temporary state used to store intermediate measurements. */
    private val tmpState3 = TransitionViewState()

    /** A temporary cache key to be used to look up cache entries */
    private val tmpKey = CacheKey()

    // Target width; the animation might still be catching up.
    var currentWidth: Int = 0
    // Target height, same deal as the width.
    var currentHeight: Int = 0

    /** Get the translationX of the layout */
    var translationX: Float = 0.0f
        private set
        get() {
            return transitionLayout?.translationX ?: 0.0f
        }

    /** Get the translationY of the layout */
    var translationY: Float = 0.0f
        private set
        get() {
            return transitionLayout?.translationY ?: 0.0f
        }

    /** Whether font family should be updated. */
    private var isFontUpdateAllowed: Boolean = true

    // Stop the extra work when QS is shut.
    fun setListening(listening: Boolean) {
        if (!SceneContainerFlag.isEnabled) return
        seekBarViewModel.listening = listening
    }

    /** A callback for config changes */
    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            var lastOrientation = -1

            override fun onConfigChanged(newConfig: Configuration?) {
                // Detached layouts still cache geometry, so push the locale direction ourselves.
                newConfig?.apply {
                    if (transitionLayout?.rawLayoutDirection != layoutDirection) {
                        transitionLayout?.layoutDirection = layoutDirection
                        refreshState()
                    }
                    val newOrientation = newConfig.orientation
                    if (lastOrientation != newOrientation) {
                        // Rotation can change the player height.
                        lastOrientation = newOrientation
                        // Expanded geometry needs the new resource height too.
                        setBackgroundHeights(
                            context.resources.getDimensionPixelSize(
                                R.dimen.qs_media_session_height_expanded
                            )
                        )
                    }
                    refreshState()
                }
            }
        }

    /** A callback for media state changes */
    val stateCallback =
        object : MediaHostStatesManager.Callback {
            override fun onHostStateChanged(
                @MediaLocation location: Int,
                mediaHostState: MediaHostState,
            ) {
                if (location == currentEndLocation || location == currentStartLocation) {
                    setCurrentState(
                        currentStartLocation,
                        currentEndLocation,
                        currentTransitionProgress,
                        applyImmediately = false,
                    )
                }
            }
        }

    // Collapsed geometry; call refreshState after messing with it.
    var collapsedLayout = ConstraintSet()
        @VisibleForTesting set

    // Expanded geometry, also needs refreshState after edits.
    var expandedLayout = ConstraintSet()
        @VisibleForTesting set

    /** Whether the guts are visible for the associated player. */
    var isGutsVisible = false
        private set

    init {
        setUpListenersAndCallbacks()
    }

    private fun setUpListenersAndCallbacks() {
        if (MediaControlsInComposeFlag.isEnabled) return
        mediaHostStatesManager.addController(this)
        layoutController.sizeChangedListener = { width: Int, height: Int ->
            currentWidth = width
            currentHeight = height
            sizeChangedListener.invoke()
        }
        configurationController.addCallback(configurationListener)
    }

    // Player's gone; bin its listeners too.
    fun onDestroy() {
        petalRibbonBackdrop?.destroy()
        petalRibbonBackdrop = null
        petalRibbonBackground.backdrop = null
        if (petalCardBackgroundDelegate.isInitialized()) petalCardBackground.stop()
        mediaHostStatesManager.removeController(this)
        configurationController.removeCallback(configurationListener)
    }

    /** Show guts with an animated transition. */
    fun openGuts() {
        if (isGutsVisible) return
        isGutsVisible = true
        animatePendingStateChange(GUTS_ANIMATION_DURATION, 0L)
        setCurrentState(
            currentStartLocation,
            currentEndLocation,
            currentTransitionProgress,
            applyImmediately = false,
            isGutsAnimation = true,
        )
    }

    /**
     * Close the guts for the associated player.
     *
     * @param immediate if `false`, it will animate the transition.
     */
    @JvmOverloads
    fun closeGuts(immediate: Boolean = false) {
        if (!isGutsVisible) return
        isGutsVisible = false
        if (!immediate) {
            animatePendingStateChange(GUTS_ANIMATION_DURATION, 0L)
        }
        setCurrentState(
            currentStartLocation,
            currentEndLocation,
            currentTransitionProgress,
            applyImmediately = immediate,
            isGutsAnimation = true,
        )
    }

    private fun ensureAllMeasurements() {
        val mediaStates = mediaHostStatesManager.mediaHostStates
        for (entry in mediaStates) {
            obtainViewState(entry.value)
        }
    }

    /** Get the constraintSet for a given expansion */
    private fun constraintSetForExpansion(expansion: Float): ConstraintSet =
        if (expansion > 0) expandedLayout else collapsedLayout

    /** Set the height of UMO background constraints. */
    private fun setBackgroundHeights(height: Int) {
        MediaViewHolder.backgroundIds.forEach { id ->
            expandedLayout.getConstraint(id).layout.mHeight = height
        }
    }

    /**
     * Set the views to be showing/hidden based on the [isGutsVisible] for a given
     * [TransitionViewState].
     */
    private fun setGutsViewState(viewState: TransitionViewState) {
        val controlsIds = MediaViewHolder.controlsIds
        val gutsIds = GutsViewHolder.ids
        controlsIds.forEach { id ->
            viewState.widgetStates.get(id)?.let { state ->
                // Make sure to use the unmodified state if guts are not visible.
                state.alpha = if (isGutsVisible) 0f else state.alpha
                state.gone = if (isGutsVisible) true else state.gone
            }
        }
        gutsIds.forEach { id ->
            viewState.widgetStates.get(id)?.let { state ->
                // Make sure to use the unmodified state if guts are visible
                state.alpha = if (isGutsVisible) state.alpha else 0f
                state.gone = if (isGutsVisible) state.gone else true
            }
        }
    }

    /** Apply squishFraction to a copy of viewState such that the cached version is untouched. */
    internal fun squishViewState(
        viewState: TransitionViewState,
        squishFraction: Float,
    ): TransitionViewState {
        val squishedViewState = viewState.copy()
        val squishedHeight = (squishedViewState.measureHeight * squishFraction).toInt()
        squishedViewState.height = squishedHeight
        // Shrink the background without kicking off another full measure.
        MediaViewHolder.backgroundIds.forEach { id ->
            squishedViewState.widgetStates.get(id)?.let { state -> state.height = squishedHeight }
        }

        calculateWidgetGroupAlphaForSquishiness(
            MediaViewHolder.expandedBottomActionIds,
            squishedViewState.measureHeight.toFloat(),
            squishedViewState,
            squishFraction,
        )
        calculateWidgetGroupAlphaForSquishiness(
            MediaViewHolder.detailIds,
            squishedViewState.measureHeight.toFloat(),
            squishedViewState,
            squishFraction,
        )
        return squishedViewState
    }

    // Fade each group before the shrinking card chops its bottom off.
    private fun calculateWidgetGroupAlphaForSquishiness(
        widgetGroupIds: Set<Int>,
        groupEndPosition: Float,
        squishedViewState: TransitionViewState,
        squishFraction: Float,
    ): Float {
        val nonsquishedHeight = squishedViewState.measureHeight
        var groupTop = squishedViewState.measureHeight.toFloat()
        var groupBottom = 0F
        widgetGroupIds.forEach { id ->
            squishedViewState.widgetStates.get(id)?.let { state ->
                groupTop = min(groupTop, state.y)
                groupBottom = max(groupBottom, state.y + state.height)
            }
        }
        // Fade between the group's bottom and the next group's edge.
        var startPosition = groupBottom
        val endPosition = groupEndPosition
        if (startPosition == endPosition) {
            startPosition = (endPosition - 0.2 * (groupBottom - groupTop)).toFloat()
        }
        widgetGroupIds.forEach { id ->
            squishedViewState.widgetStates.get(id)?.let { state ->
                // Don't modify alpha for elements that should be invisible (e.g. disabled seekbar)
                if (state.alpha != 0f) {
                    state.alpha =
                        calculateAlpha(
                            squishFraction,
                            startPosition / nonsquishedHeight,
                            endPosition / nonsquishedHeight,
                        )
                }
            }
        }
        return groupTop // used for the widget group above this group
    }

    /**
     * Obtain a new viewState for a given media state. This usually returns a cached state, but if
     * it's not available, it will recreate one by measuring, which may be expensive.
     */
    @VisibleForTesting
    fun obtainViewState(
        state: MediaHostState?,
        isGutsAnimation: Boolean = false,
    ): TransitionViewState? {
        MediaControlsInComposeFlag.assertInLegacyMode()

        if (state == null || state.measurementInput == null) {
            return null
        }
        // Only a subset of the state is relevant to get a valid viewState. Let's get the cachekey
        var cacheKey = getKey(state, isGutsVisible, tmpKey)
        val viewState = viewStates[cacheKey]
        if (viewState != null) {
            // we already have cached this measurement, let's continue
            if (state.squishFraction <= 1f && !isGutsAnimation) {
                return squishViewState(viewState, state.squishFraction)
            }
            return viewState
        }
        // Copy the key since this might call recursively into it and we're using tmpKey
        cacheKey = cacheKey.copy()
        val result: TransitionViewState?

        if (transitionLayout == null) {
            return null
        }
        // Let's create a new measurement
        if (state.expansion == 0.0f || state.expansion == 1.0f) {
            if (state.expansion == 1.0f) {
                val height =
                    if (state.expandedMatchesParentHeight) {
                        MATCH_CONSTRAINT
                    } else {
                        context.resources.getDimensionPixelSize(
                            R.dimen.qs_media_session_height_expanded
                        )
                    }
                setBackgroundHeights(height)
            }

            val concept = state.petalStyle == com.android.systemui.petalos.PetalMediaStyle.Lockscreen ||
                (PetalConfig.isQsSkinEnabled(context) &&
                    state.petalStyle != com.android.systemui.petalos.PetalMediaStyle.Stock)
            val constraints = petalConstraintSet(state)
            val measurementInput = state.measurementInput!!.copy()
            if (concept && !state.expandedMatchesParentHeight) {
                // Wrap-content stopped at the artist and chopped the rest off.
                measurementInput.heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                    constraints.getConstraint(R.id.album_art).layout.mHeight,
                    View.MeasureSpec.EXACTLY,
                )
            }
            result =
                transitionLayout!!.calculateViewState(
                    measurementInput,
                    constraints,
                    TransitionViewState(),
                )

            setGutsViewState(result)
            // We don't want to cache interpolated or null states as this could quickly fill up
            // our cache. We only cache the start and the end states since the interpolation
            // is cheap
            viewStates[cacheKey] = result
        } else {
            // This is an interpolated state
            val startState = state.copy().also { it.expansion = 0.0f }

            // Given that we have a measurement and a view, let's get (guaranteed) viewstates
            // from the start and end state and interpolate them
            val startViewState = obtainViewState(startState, isGutsAnimation) as TransitionViewState
            val endState = state.copy().also { it.expansion = 1.0f }
            val endViewState = obtainViewState(endState, isGutsAnimation) as TransitionViewState
            result =
                layoutController.getInterpolatedState(startViewState, endViewState, state.expansion)
        }
        // Skip the adjustments of squish view state if UMO changes due to guts animation.
        if (state.squishFraction <= 1f && !isGutsAnimation) {
            return squishViewState(result, state.squishFraction)
        }
        return result
    }

    private fun petalConstraintSet(state: MediaHostState): ConstraintSet {
        val original = constraintSetForExpansion(state.expansion)
        val style = state.petalStyle
        if (style == com.android.systemui.petalos.PetalMediaStyle.Lockscreen ||
            (PetalConfig.isQsSkinEnabled(context) &&
                style != com.android.systemui.petalos.PetalMediaStyle.Stock)) {
            return com.android.systemui.petalos.PetalMediaConstraints.create(context, original, state)
        }
        val density = context.resources.displayMetrics.density
        val widthDp = (state.measurementInput?.width ?: 0) / density
        if (!PetalConfig.isQsSkinEnabled(context) || state.expansion != 1f ||
            widthDp !in 180f..260f || context.resources.configuration.fontScale > 1.3f) {
            return original
        }
        // Clone geometry, keep the actions' live visibility and alpha.
        val layout = ConstraintSet().apply { clone(original) }
        fun px(dp: Int) = (dp * density).toInt()
        fun clearAnchors(id: Int) {
            listOf(ConstraintSet.LEFT, ConstraintSet.RIGHT, ConstraintSet.START, ConstraintSet.END,
                ConstraintSet.TOP, ConstraintSet.BOTTOM, ConstraintSet.BASELINE).forEach {
                layout.clear(id, it)
            }
        }
        fun fullWidth(id: Int) {
            layout.constrainWidth(id, MATCH_CONSTRAINT)
            layout.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, px(12))
            layout.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, px(12))
        }
        clearAnchors(R.id.icon)
        layout.constrainWidth(R.id.icon, px(20))
        layout.constrainHeight(R.id.icon, px(20))
        layout.connect(R.id.icon, ConstraintSet.START,
            ConstraintSet.PARENT_ID, ConstraintSet.START, px(12))
        layout.connect(R.id.icon, ConstraintSet.TOP,
            ConstraintSet.PARENT_ID, ConstraintSet.TOP, px(12))
        clearAnchors(R.id.media_seamless)
        layout.connect(R.id.media_seamless, ConstraintSet.START, R.id.icon, ConstraintSet.END, px(8))
        layout.connect(R.id.media_seamless, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        layout.connect(R.id.media_seamless, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        layout.constrainWidth(R.id.media_seamless, MATCH_CONSTRAINT)
        layout.setHorizontalBias(R.id.media_seamless, 1f)

        clearAnchors(R.id.header_title)
        fullWidth(R.id.header_title)
        // output switcher can be gone, so it can't hold the title below the app icon
        layout.connect(R.id.header_title, ConstraintSet.TOP,
            ConstraintSet.PARENT_ID, ConstraintSet.TOP, px(48))
        clearAnchors(R.id.header_artist)
        fullWidth(R.id.header_artist)
        layout.connect(R.id.header_artist, ConstraintSet.TOP, R.id.header_title, ConstraintSet.BOTTOM)
        clearAnchors(R.id.media_explicit_indicator)
        layout.connect(R.id.media_explicit_indicator, ConstraintSet.START,
            ConstraintSet.PARENT_ID, ConstraintSet.START, px(12))
        layout.connect(R.id.media_explicit_indicator, ConstraintSet.TOP,
            R.id.header_artist, ConstraintSet.TOP)
        if (layout.getVisibility(R.id.media_explicit_indicator) != View.GONE) {
            layout.connect(R.id.header_artist, ConstraintSet.START,
                R.id.media_explicit_indicator, ConstraintSet.END, px(4))
        }

        val play = R.id.actionPlayPause
        clearAnchors(play)
        layout.constrainWidth(play, px(48))
        layout.constrainHeight(play, px(48))
        layout.connect(play, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT)
        layout.connect(play, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT)
        layout.connect(play, ConstraintSet.TOP, R.id.header_artist, ConstraintSet.BOTTOM, px(4))
        for ((id, side) in listOf(R.id.actionPrev to ConstraintSet.RIGHT, R.id.actionNext to ConstraintSet.LEFT)) {
            clearAnchors(id)
            layout.connect(id, ConstraintSet.TOP, play, ConstraintSet.TOP)
            layout.connect(id, side, play,
                if (side == ConstraintSet.RIGHT) ConstraintSet.LEFT else ConstraintSet.RIGHT, px(8))
        }

        val generic = MediaViewHolder.genericButtonIds.filter { layout.getVisibility(it) != View.GONE }
        val hasPrimary = layout.getVisibility(play) != View.GONE
        val extraRows = (generic.size + 2) / 3
        for ((index, id) in generic.withIndex()) {
            clearAnchors(id)
            val row = index / 3
            val column = index % 3
            layout.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START,
                px(12 + column * 56))
            layout.connect(id, ConstraintSet.TOP, R.id.header_artist, ConstraintSet.BOTTOM,
                px((if (hasPrimary) 52 else 4) + row * 48))
        }
        clearAnchors(R.id.media_progress_bar)
        fullWidth(R.id.media_progress_bar)
        layout.connect(R.id.media_progress_bar, ConstraintSet.BOTTOM,
            ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        for ((id, side) in listOf(R.id.media_scrubbing_elapsed_time to ConstraintSet.LEFT,
            R.id.media_scrubbing_total_time to ConstraintSet.RIGHT)) {
            clearAnchors(id)
            layout.connect(id, side, ConstraintSet.PARENT_ID, side, px(12))
            layout.connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM, px(48))
        }
        val height = 232 + (if (hasPrimary) extraRows else (extraRows - 1).coerceAtLeast(0)) * 48
        MediaViewHolder.backgroundIds.forEach { layout.constrainHeight(it, px(height)) }
        return layout
    }

    private fun getKey(state: MediaHostState, guts: Boolean, result: CacheKey): CacheKey {
        result.apply {
            heightMeasureSpec = state.measurementInput?.heightMeasureSpec ?: 0
            widthMeasureSpec = state.measurementInput?.widthMeasureSpec ?: 0
            expansion = state.expansion
            gutsVisible = guts
            petalStyle = state.petalStyle
            expandedMatchesParentHeight = state.expandedMatchesParentHeight
        }
        return result
    }

    // New player, fresh measurements.
    fun attach(transitionLayout: TransitionLayout) =
        traceSection("MediaViewController#attach") {
            MediaControlsInComposeFlag.assertInLegacyMode()

            loadLayoutConstraints()
            logger.logMediaLocation("attach", currentStartLocation, currentEndLocation)
            this.transitionLayout = transitionLayout
            petalRibbonBackdrop?.destroy()
            petalRibbonBackdrop = com.android.systemui.petalos.PetalMediaRibbonBackdrop(transitionLayout)
            petalRibbonBackground.backdrop = petalRibbonBackdrop
            layoutController.attach(transitionLayout)
            if (currentEndLocation == MediaHierarchyManager.LOCATION_UNKNOWN) {
                return
            }
            // Set the previously set state immediately to the view, now that it's finally attached
            setCurrentState(
                startLocation = currentStartLocation,
                endLocation = currentEndLocation,
                transitionProgress = currentTransitionProgress,
                applyImmediately = true,
            )
        }

    // Measure this host, cache or no cache.
    fun getMeasurementsForState(hostState: MediaHostState): MeasurementOutput? =
        traceSection("MediaViewController#getMeasurementsForState") {
            // measurements should never factor in the squish fraction
            val viewState = obtainViewState(hostState) ?: return null
            measurement.measuredWidth = viewState.measureWidth
            measurement.measuredHeight = viewState.measureHeight
            return measurement
        }

    // Move the player between hosts, including the half-dragged mess.
    fun setCurrentState(
        @MediaLocation startLocation: Int,
        @MediaLocation endLocation: Int,
        transitionProgress: Float,
        applyImmediately: Boolean,
        isGutsAnimation: Boolean = false,
    ) =
        traceSection("MediaViewController#setCurrentState") {
            MediaControlsInComposeFlag.assertInLegacyMode()

            currentEndLocation = endLocation
            currentStartLocation = startLocation
            currentTransitionProgress = transitionProgress

            val endHostState = mediaHostStatesManager.mediaHostStates[endLocation] ?: return
            val startHostState = mediaHostStatesManager.mediaHostStates[startLocation]
            val styleChanged = lastPetalStyle != endHostState.petalStyle
            val applyLayoutImmediately = applyImmediately || styleChanged
            if (styleChanged) {
                // Old-style measurements never get to poison the morph.
                viewStates.clear()
            }
            val shouldAnimate = animateNextStateChange && !applyLayoutImmediately
            val oldStyle = transitionLayout?.getTag(R.id.petal_media_cover)
            transitionLayout?.setTag(R.id.petal_media_cover, endHostState.petalStyle)
            val concept = endHostState.petalStyle == com.android.systemui.petalos.PetalMediaStyle.Lockscreen ||
                (PetalConfig.isQsSkinEnabled(context) &&
                    endHostState.petalStyle != com.android.systemui.petalos.PetalMediaStyle.Stock)
            val startStyle = startHostState?.petalStyle ?: endHostState.petalStyle
            val startStrength = petalGlassStrength(startStyle)
            val endStrength = petalGlassStrength(endHostState.petalStyle)
            val progress = if (transitionProgress.isFinite()) transitionProgress.coerceIn(0f, 1f) else 1f
            val strength = startStrength + (endStrength - startStrength) * progress
            val startFlow = petalArtworkFlowStrength(startStyle)
            val endFlow = petalArtworkFlowStrength(endHostState.petalStyle)
            // Follow the drag both ways; the destination style doesn't get to kill the smear early.
            val artworkFlow = startFlow + (endFlow - startFlow) * progress
            val lock = endHostState.petalStyle == com.android.systemui.petalos.PetalMediaStyle.Lockscreen
            petalRibbonBackdrop?.setEnabled(lock)
            val blurRadius = 12f + (28f - 12f) * strength
            transitionLayout?.let {
                it.setLayerType(if (concept) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE, null)
                // The ribbon has its own paper; don't blur the whole window again.
                com.android.systemui.petalos.PetalMediaBackdrop.setEnabled(it, concept && !lock, blurRadius)
                it.findViewById<com.android.systemui.petalos.PetalMediaArtworkView>(R.id.petal_media_cover)
                    ?.setFeatherBottom(
                        if (concept) artworkFlow else 0f
                    )
            }
            if (concept) {
                petalCardBackground.setStyle(endHostState.petalStyle, artworkFlow)
                val fillAlpha = 0.28f +
                    (com.android.systemui.petalos.PetalQsSkin.GlassFillAlpha - 0.28f) * strength
                petalCardBackground.setTintColor(android.graphics.Color.argb(
                    kotlin.math.round(255f * fillAlpha).toInt(), 0x33, 0x33, 0x3D))
                val accent = if (lock) com.android.systemui.petalos.PetalMediaRibbonDrawable.ACCENT
                    else context.getColor(android.R.color.system_accent1_500)
                val background = if (lock) petalRibbonBackground else petalCardBackground
                if (oldStyle != endHostState.petalStyle || petalButtonAccent != accent ||
                    transitionLayout?.background !== background) {
                    petalButtonAccent = accent
                    transitionLayout?.background = background
                    transitionLayout?.apply {
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            // The live background would call straight back into this bloody outline.
                            private val ribbonOutline = com.android.systemui.petalos.PetalMediaRibbonDrawable(
                                context.resources.displayMetrics.density
                            )

                            override fun getOutline(view: View, outline: android.graphics.Outline) {
                                if (lock) {
                                    ribbonOutline.setBounds(0, 0, view.width, view.height)
                                    ribbonOutline.getOutline(outline)
                                } else {
                                    outline.setRoundRect(0, 0, view.width, view.height,
                                        24f * view.resources.displayMetrics.density)
                                }
                            }
                        }
                        clipToOutline = true
                        foreground = if (lock) null else android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 24f * resources.displayMetrics.density
                            setColor(android.graphics.Color.TRANSPARENT)
                            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), 0x33FFFFFF)
                        }
                    }
                    // flush artwork takes the card's own corners, inset art stays cosy
                    val insetArt =
                        endHostState.petalStyle != com.android.systemui.petalos.PetalMediaStyle.LargeProgress
                    val coverRadius =
                        (if (lock) 10f else if (insetArt) 16f else 0f) * context.resources.displayMetrics.density
                    transitionLayout?.findViewById<android.widget.ImageView>(R.id.petal_media_cover)
                        ?.apply {
                            clipToOutline = true
                            foreground = if (lock) android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = coverRadius
                                setColor(android.graphics.Color.TRANSPARENT)
                                setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), 0x70FFFFFF)
                            } else null
                            outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(
                                    view: View,
                                    outline: android.graphics.Outline,
                                ) {
                                    outline.setRoundRect(
                                        0, 0, view.width, view.height, coverRadius
                                    )
                                }
                            }
                        }
                    val play = transitionLayout?.findViewById<android.widget.ImageButton>(R.id.actionPlayPause)
                    play?.background = com.android.systemui.petalos.PetalMediaBubbleDrawable(
                        true, true, context.resources.displayMetrics.density, accent)
                    play?.backgroundTintList = null
                    play?.imageTintList = android.content.res.ColorStateList.valueOf(
                        if (lock) android.graphics.Color.WHITE else
                            com.android.systemui.petalos.PetalQsSkin.glyphOnBackground(accent))
                    val sideColor = if (lock || com.android.systemui.petalos.PetalQsSkin.isDark(context)) {
                        0xFFF5F5F8.toInt()
                    } else 0xFF202127.toInt()
                    (MediaViewHolder.genericButtonIds + setOf(R.id.actionPrev, R.id.actionNext)).forEach { id ->
                        transitionLayout?.findViewById<android.widget.ImageButton>(id)?.imageTintList =
                            android.content.res.ColorStateList.valueOf(sideColor)
                    }
                    val seek = transitionLayout?.findViewById<android.widget.SeekBar>(R.id.media_progress_bar)
                    seek?.apply {
                        setPadding(paddingLeft, if (lock) 0 else context.resources.getDimensionPixelSize(
                            R.dimen.qs_media_session_enabled_seekbar_vertical_padding), paddingRight,
                            if (lock) 0 else (12f * context.resources.displayMetrics.density).toInt())
                    }
                    val mini = endHostState.petalStyle == com.android.systemui.petalos.PetalMediaStyle.Mini
                    (seek?.progressDrawable as? com.android.systemui.media.controls.ui.drawable.SquigglyProgress)?.apply {
                        lineAmplitude = 0f
                        strokeWidth = (if (mini) 2f else 6f) * context.resources.displayMetrics.density
                        animate = false
                    }
                    val seekAvailable = seekBarViewModel.progress.value?.seekAvailable == true
                    seek?.thumb?.alpha = if (seekAvailable && !mini) 255 else 0
                    seek?.isEnabled = seekAvailable && !mini
                    seek?.progressTintList = android.content.res.ColorStateList.valueOf(
                        if (lock) com.android.systemui.petalos.PetalMediaRibbonDrawable.TRACK_ACCENT else accent)
                    seek?.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x40FFFFFF)
                    seek?.thumb = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        val diameter = ((if (lock) 14f else 10f) * context.resources.displayMetrics.density).toInt()
                        setSize(diameter, diameter)
                        setColor(android.graphics.Color.WHITE)
                        alpha = if (seekAvailable && !mini) 255 else 0
                    }
                    transitionLayout?.findViewById<android.widget.TextView>(R.id.header_title)?.apply {
                        textSize = if (lock) 20f else 16f
                        typeface = if (lock) Typeface.create("sans-serif", Typeface.BOLD) else titleMediumEmphasizedTF
                    }
                    transitionLayout?.findViewById<android.widget.TextView>(R.id.header_artist)?.apply {
                        textSize = if (lock) 18f else 14f
                        typeface = if (lock) Typeface.create("sans-serif", Typeface.NORMAL) else bodyMediumTF
                    }
                    listOf(R.id.petal_media_elapsed, R.id.petal_media_duration).forEach { id ->
                        transitionLayout?.findViewById<android.widget.TextView>(id)?.textSize =
                            if (lock) 14f else 11f
                    }
                    updatePetalOutputControls(endHostState.petalStyle == com.android.systemui.petalos.PetalMediaStyle.Split)
                    // equalizer and label swap every expand, so let them crossfade
                    transitionLayout?.findViewById<android.widget.TextView>(R.id.media_seamless_text)?.visibility =
                        deviceNameVisibility(true)
                    fadeSeamless(endHostState.petalStyle == com.android.systemui.petalos.PetalMediaStyle.Split,
                        shouldAnimate)
                    transitionLayout?.findViewById<android.widget.TextView>(R.id.header_title)?.setTextColor(android.graphics.Color.WHITE)
                    transitionLayout?.findViewById<android.widget.TextView>(R.id.header_artist)?.setTextColor(0xBFFFFFFF.toInt())
                }
            } else {
                if (petalButtonAccent != null) {
                    petalCardBackground.stop()
                    transitionLayout?.setBackgroundResource(R.drawable.qs_media_outline_layout_bg)
                    transitionLayout?.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    transitionLayout?.foreground = null
                    transitionLayout?.findViewById<android.widget.ImageView>(R.id.petal_media_cover)?.foreground = null
                    transitionLayout?.findViewById<android.widget.TextView>(R.id.header_title)?.apply {
                        textSize = 16f
                        typeface = titleMediumEmphasizedTF
                    }
                    transitionLayout?.findViewById<android.widget.TextView>(R.id.header_artist)?.apply {
                        textSize = 14f
                        typeface = bodyMediumTF
                    }
                }
                petalButtonAccent = null
                transitionLayout?.findViewById<android.widget.TextView>(R.id.media_seamless_text)?.visibility = View.VISIBLE
                transitionLayout?.findViewById<View>(R.id.petal_media_equalizer)?.visibility = View.GONE
                transitionLayout?.findViewById<View>(R.id.media_seamless_image)?.visibility = View.VISIBLE
                transitionLayout?.findViewById<View>(R.id.media_seamless_button)?.animate()?.cancel()
                transitionLayout?.findViewById<View>(R.id.media_seamless_button)?.alpha = 1f
                transitionLayout?.findViewById<View>(R.id.petal_media_equalizer)?.animate()?.cancel()
                transitionLayout?.findViewById<View>(R.id.petal_media_equalizer)?.alpha = 1f
            }

            // No measurement yet? It'll catch up once the view exists.
            var endViewState = obtainViewState(endHostState, isGutsAnimation) ?: return
            endViewState = updateViewStateSize(endViewState, endLocation, tmpState2)!!
            layoutController.setMeasureState(endViewState)

            // If the view isn't bound, we can drop the animation, otherwise we'll execute it
            animateNextStateChange = false
            if (transitionLayout == null) {
                logger.logMediaLocation(
                    "setCurrentState: view not bound",
                    startLocation,
                    endLocation,
                )
                return
            }

            val result: TransitionViewState
            var startViewState = obtainViewState(startHostState, isGutsAnimation)
            startViewState = updateViewStateSize(startViewState, startLocation, tmpState3)

            if (!endHostState.visible) {
                // Let's handle the case where the end is gone first. In this case we take the
                // start viewState and will make it gone
                if (startViewState == null || startHostState == null || !startHostState.visible) {
                    // the start isn't a valid state, let's use the endstate directly
                    result = endViewState
                } else {
                    // Let's get the gone presentation from the start state
                    result =
                        layoutController.getGoneState(
                            startViewState,
                            startHostState.disappearParameters,
                            transitionProgress,
                            tmpState,
                        )
                }
            } else if (startHostState != null && !startHostState.visible) {
                // We have a start state and it is gone.
                // Let's get presentation from the endState
                result =
                    layoutController.getGoneState(
                        endViewState,
                        endHostState.disappearParameters,
                        1.0f - transitionProgress,
                        tmpState,
                    )
            } else if (transitionProgress == 1.0f || startViewState == null) {
                // We're at the end. Let's use that state
                result = endViewState
            } else if (transitionProgress == 0.0f) {
                // We're at the start. Let's use that state
                result = startViewState
            } else {
                result =
                    layoutController.getInterpolatedState(
                        startViewState,
                        endViewState,
                        transitionProgress,
                        tmpState,
                    )
            }
            logger.logMediaSize(
                "setCurrentState $startLocation -> $endLocation (progress $transitionProgress)",
                result.width,
                result.height,
            )
            // Missing measurements don't count as switching the live layout.
            result.widgetStates[R.id.petal_media_ribbon_waveform]?.apply {
                gone = true
                alpha = 0f
            }
            lastPetalStyle = endHostState.petalStyle
            layoutController.setState(
                result,
                // The host owns the morph; bin any tween left running by the previous style.
                applyLayoutImmediately,
                shouldAnimate,
                animationDuration,
                animationDelay,
                isGutsAnimation,
            )
        }

    private fun updateViewStateSize(
        viewState: TransitionViewState?,
        location: Int,
        outState: TransitionViewState,
    ): TransitionViewState? {
        var result = viewState?.copy(outState) ?: return null
        val state = mediaHostStatesManager.mediaHostStates[location]
        val style = state?.petalStyle ?: com.android.systemui.petalos.PetalMediaStyle.Stock
        val concept = style == com.android.systemui.petalos.PetalMediaStyle.Lockscreen ||
            (PetalConfig.isQsSkinEnabled(context) && style != com.android.systemui.petalos.PetalMediaStyle.Stock)
        val overrideSize = mediaHostStatesManager.carouselSizes[location]
        var overridden = false
        // Concept layouts size themselves; a stale carousel height just clips the morph.
        overrideSize?.takeIf { !concept }?.let {
            if (
                result.measureHeight != it.measuredHeight || result.measureWidth != it.measuredWidth
            ) {
                // To be safe we're using a maximum here. The override size should always be set
                // properly though.
                result.measureHeight = Math.max(it.measuredHeight, result.measureHeight)
                result.measureWidth = Math.max(it.measuredWidth, result.measureWidth)
                overridden = true
            }
            if (overridden) {
                // The measureHeight and the shown height should both be set to the overridden
                // height
                result.height = result.measureHeight
                result.width = result.measureWidth
                // Make sure all background views are also resized such that their size is correct
                MediaViewHolder.backgroundIds.forEach { id ->
                    result.widgetStates.get(id)?.let { state ->
                        state.height = result.height
                        state.width = result.width
                    }
                }
            }
        }
        if (overridden && state != null && state.squishFraction <= 1f) {
            // Let's squish the media player if our size was overridden
            result = squishViewState(result, state.squishFraction)
        }
        logger.logMediaSize(
            "update to carousel (squish ${state?.squishFraction}",
            result.width,
            result.height,
        )
        return result
    }

    private fun loadLayoutConstraints() {
        // Stock geometry starts here; Petal shoves it around later.
        collapsedLayout.load(context, R.xml.media_session_collapsed)
        expandedLayout.load(context, R.xml.media_session_expanded)
        listOf(collapsedLayout, expandedLayout).forEach { layout ->
            listOf(R.id.petal_media_cover, R.id.petal_media_elapsed, R.id.petal_media_duration,
                R.id.petal_media_output, R.id.petal_media_ribbon_waveform)
                .forEach { layout.setVisibility(it, View.GONE); layout.setAlpha(it, 0f) }
        }
        refreshState()
    }

    fun setPetalPaintArtwork(bitmap: android.graphics.Bitmap?) {
        petalCardBackground.setArtwork(bitmap)
    }

    fun deviceNameVisibility(stockVisible: Boolean): Int {
        val fallback = if (stockVisible) View.VISIBLE else View.GONE
        val style = transitionLayout?.getTag(R.id.petal_media_cover) as? com.android.systemui.petalos.PetalMediaStyle
            ?: com.android.systemui.petalos.PetalMediaStyle.Stock
        return when (style) {
            com.android.systemui.petalos.PetalMediaStyle.Stock -> fallback
            com.android.systemui.petalos.PetalMediaStyle.Lockscreen -> View.GONE
            com.android.systemui.petalos.PetalMediaStyle.LargeProgress,
            com.android.systemui.petalos.PetalMediaStyle.Mini ->
                if (PetalConfig.isQsSkinEnabled(context)) View.GONE else fallback
            com.android.systemui.petalos.PetalMediaStyle.Split ->
                if (PetalConfig.isQsSkinEnabled(context)) View.VISIBLE else fallback
        }
    }

    // pill swaps equalizer <-> label on expand; fade the outgoing piece instead of teleporting it
    private fun fadeSeamless(toSplit: Boolean, animate: Boolean) {
        val root = transitionLayout ?: return
        val button = root.findViewById<View>(R.id.media_seamless_button)
        val equalizer = root.findViewById<View>(R.id.petal_media_equalizer)
        button?.animate()?.cancel()
        equalizer?.animate()?.cancel()
        if (!animate) {
            button?.alpha = 1f
            equalizer?.alpha = 1f
            return
        }
        val fadeOutIn = { v: View? ->
            v?.animate()?.setDuration(120L)?.alpha(0f)?.withEndAction {
                v?.animate()?.setDuration(180L)?.alpha(1f)?.start()
            }?.start()
        }
        // one of the two is GONE depending on split; fade the visible piece only
        fadeOutIn(if (toSplit) equalizer else button)
    }

    private fun petalArtworkFlowStrength(style: com.android.systemui.petalos.PetalMediaStyle): Float = when (style) {
        com.android.systemui.petalos.PetalMediaStyle.LargeProgress -> 1f
        com.android.systemui.petalos.PetalMediaStyle.Lockscreen,
        com.android.systemui.petalos.PetalMediaStyle.Stock,
        com.android.systemui.petalos.PetalMediaStyle.Split,
        com.android.systemui.petalos.PetalMediaStyle.Mini -> 0f
    }

    private fun petalGlassStrength(style: com.android.systemui.petalos.PetalMediaStyle): Float = when (style) {
        com.android.systemui.petalos.PetalMediaStyle.LargeProgress,
        com.android.systemui.petalos.PetalMediaStyle.Mini -> 0f
        com.android.systemui.petalos.PetalMediaStyle.Stock,
        com.android.systemui.petalos.PetalMediaStyle.Split,
        com.android.systemui.petalos.PetalMediaStyle.Lockscreen -> 1f
    }

    private fun updatePetalOutputControls(split: Boolean) {
        val root = transitionLayout ?: return
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        root.findViewById<android.widget.LinearLayout>(R.id.media_seamless)?.apply {
            setPadding(0, 0, 0, 0)
            gravity = android.view.Gravity.CENTER
        }
        root.findViewById<android.widget.LinearLayout>(R.id.media_seamless_button)?.apply {
            gravity = android.view.Gravity.CENTER
            setPadding(px(6), 0, px(6), 0)
            layoutParams?.width = ViewGroup.LayoutParams.MATCH_PARENT
            layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
            backgroundTintList = android.content.res.ColorStateList.valueOf(0x22FFFFFF)
        }
        root.findViewById<android.widget.ImageView>(R.id.media_seamless_image)?.apply {
            layoutParams?.width = px(16)
            layoutParams?.height = px(16)
            alpha = 1f
            visibility = if (split) View.VISIBLE else View.GONE
        }
        root.findViewById<com.android.systemui.island.render.EqualizerView>(R.id.petal_media_equalizer)?.apply {
            visibility = if (split) View.GONE else View.VISIBLE
            alpha = 1f
            active = seekBarViewModel.progress.value?.playing == true
        }
        root.findViewById<android.widget.TextView>(R.id.media_seamless_text)?.apply {
            textSize = 11f
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 1f
            visibility = View.VISIBLE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                if (split) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (split) 1f else 0f,
            ).apply { marginStart = px(4) }
        }
    }

    // Grab the host's state with its actual size override.
    private fun obtainViewStateForLocation(@MediaLocation location: Int): TransitionViewState? {
        val mediaHostState = mediaHostStatesManager.mediaHostStates[location] ?: return null
        MediaControlsInComposeFlag.assertInLegacyMode()

        val viewState = obtainViewState(mediaHostState)
        if (viewState != null) {
            // update the size of the viewstate for the location with the override
            updateViewStateSize(viewState, location, tmpState)
            return tmpState
        }
        return viewState
    }

    private fun updateFontPerLocation(viewHolder: MediaViewHolder?, location: Int) {
        when (location) {
            MediaHierarchyManager.LOCATION_LOCKSCREEN ->
                viewHolder?.updateFontFamily(Typeface.create("sans-serif", Typeface.BOLD),
                    Typeface.create("sans-serif", Typeface.NORMAL), labelMediumTF)
            MediaHierarchyManager.LOCATION_COMMUNAL_HUB ->
                viewHolder?.updateFontFamily(headlineSmallTF, titleMediumTF, labelMediumTF)
            else ->
                viewHolder?.updateFontFamily(titleMediumEmphasizedTF, bodyMediumTF, labelMediumTF)
        }
    }

    private fun MediaViewHolder.updateFontFamily(
        titleTF: Typeface,
        artistTF: Typeface,
        menuTF: Typeface,
    ) {
        gutsViewHolder.gutsText.setTypeface(menuTF)
        gutsViewHolder.dismissText.setTypeface(menuTF)
        gutsViewHolder.cancelText.setTypeface(menuTF)
        titleText.setTypeface(titleTF)
        artistText.setTypeface(artistTF)
        seamlessText.setTypeface(menuTF)
    }

    /**
     * Notify that the location is changing right now and a [setCurrentState] change is imminent.
     * This updates the width the view will me measured with.
     */
    fun onLocationPreChange(
        viewHolder: MediaViewHolder?,
        @MediaLocation newLocation: Int,
        @MediaLocation prevLocation: Int,
    ) {
        isFontUpdateAllowed =
            isFontUpdateAllowed ||
                MediaHierarchyManager.LOCATION_COMMUNAL_HUB == newLocation ||
                MediaHierarchyManager.LOCATION_COMMUNAL_HUB == prevLocation
        if (isFontUpdateAllowed) {
            updateFontPerLocation(viewHolder, newLocation)
            isFontUpdateAllowed = false
        }
        obtainViewStateForLocation(newLocation)?.let { layoutController.setMeasureState(it) }
    }

    /** Request that the next state change should be animated with the given parameters. */
    fun animatePendingStateChange(duration: Long, delay: Long) {
        animateNextStateChange = true
        animationDuration = duration
        animationDelay = delay
    }

    /** Clear all existing measurements and refresh the state to match the view. */
    fun refreshState() =
        traceSection("MediaViewController#refreshState") {
            MediaControlsInComposeFlag.assertInLegacyMode()
            // Let's clear all of our measurements and recreate them!
            viewStates.clear()
            if (firstRefresh) {
                // This is the first bind, let's ensure we pre-cache all measurements. Otherwise
                // We'll just load these on demand.
                ensureAllMeasurements()
                firstRefresh = false
            }
            setCurrentState(
                currentStartLocation,
                currentEndLocation,
                currentTransitionProgress,
                applyImmediately = true,
            )
        }

    @VisibleForTesting
    protected open fun loadAnimator(
        context: Context,
        animId: Int,
        motionInterpolator: Interpolator?,
        vararg targets: View?,
    ): AnimatorSet {
        val animators = ArrayList<Animator>()
        for (target in targets) {
            val animator = AnimatorInflater.loadAnimator(context, animId) as AnimatorSet
            animator.childAnimations[0].interpolator = motionInterpolator
            animator.setTarget(target)
            animators.add(animator)
        }
        val result = AnimatorSet()
        result.playTogether(animators)
        return result
    }
}

/** An internal key for the cache of mediaViewStates. This is a subset of the full host state. */
private data class CacheKey(
    var widthMeasureSpec: Int = -1,
    var heightMeasureSpec: Int = -1,
    var expansion: Float = 0.0f,
    var gutsVisible: Boolean = false,
    var expandedMatchesParentHeight: Boolean = false,
    var petalStyle: com.android.systemui.petalos.PetalMediaStyle =
        com.android.systemui.petalos.PetalMediaStyle.Stock,
)
