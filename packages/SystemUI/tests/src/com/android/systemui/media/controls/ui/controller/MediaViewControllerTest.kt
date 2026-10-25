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

package com.android.systemui.media.controls.ui.controller

import android.animation.AnimatorSet
import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.platform.test.annotations.DisableFlags
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.LiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.CachingIconView
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.media.controls.ui.view.GutsViewHolder
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel
import com.android.systemui.res.R
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffectView
import com.android.systemui.surfaceeffects.ripple.MultiRippleView
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseView
import com.android.systemui.util.animation.MeasurementInput
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.animation.TransitionViewState
import com.android.systemui.util.animation.WidgetState
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.floatThat
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
@DisableSceneContainer
@DisableFlags(Flags.FLAG_MEDIA_CONTROLS_IN_COMPOSE)
class MediaViewControllerTest : SysuiTestCase() {
    private val mediaHostStateHolder = MediaHost.MediaHostStateHolder()
    private val configurationController =
        com.android.systemui.statusbar.phone.ConfigurationControllerImpl(context)
    private var player = TransitionLayout(context, /* attrs */ null, /* defStyleAttr */ 0)
    private lateinit var mediaHostStatesManager: MediaHostStatesManager
    private lateinit var seekBar: SeekBar
    private lateinit var multiRippleView: MultiRippleView
    private lateinit var turbulenceNoiseView: TurbulenceNoiseView
    private lateinit var loadingEffectView: LoadingEffectView
    private lateinit var settings: ImageButton
    private lateinit var cancel: View
    private lateinit var cancelText: TextView
    private lateinit var dismiss: FrameLayout
    private lateinit var dismissText: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var explicitIndicator: CachingIconView
    private lateinit var seamless: ViewGroup
    private lateinit var seamlessButton: View
    private lateinit var seamlessIcon: ImageView
    private lateinit var seamlessText: TextView
    private lateinit var scrubbingElapsedTimeView: TextView
    private lateinit var scrubbingTotalTimeView: TextView
    private lateinit var actionPlayPause: ImageButton
    private lateinit var actionNext: ImageButton
    private lateinit var actionPrev: ImageButton
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var seamlessBackground: RippleDrawable
    @Mock private lateinit var albumView: ImageView
    @Mock lateinit var logger: MediaViewLogger
    @Mock private lateinit var mockViewState: TransitionViewState
    @Mock private lateinit var mockCopiedState: TransitionViewState
    @Mock private lateinit var detailWidgetState: WidgetState
    @Mock private lateinit var controlWidgetState: WidgetState
    @Mock private lateinit var seekBarViewModel: SeekBarViewModel
    @Mock private lateinit var seekBarData: LiveData<SeekBarViewModel.Progress>
    @Mock private lateinit var viewHolder: MediaViewHolder
    @Mock private lateinit var view: TransitionLayout
    @Mock private lateinit var mockAnimator: AnimatorSet
    @Mock private lateinit var gutsViewHolder: GutsViewHolder
    @Mock private lateinit var gutsText: TextView

    private val delta = 0.1F

    private lateinit var mediaViewController: MediaViewController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mediaHostStatesManager = MediaHostStatesManager(dumpManager)
        mediaViewController =
            object :
                MediaViewController(
                    context,
                    configurationController,
                    mediaHostStatesManager,
                    logger,
                    seekBarViewModel,
                ) {
                override fun loadAnimator(
                    context: Context,
                    animId: Int,
                    motionInterpolator: Interpolator?,
                    vararg targets: View?,
                ): AnimatorSet {
                    return mockAnimator
                }
            }
        initGutsViewHolderMocks()
        initMediaViewHolderMocks()
    }

    @Test
    fun testOrientationChanged_heightOfPlayerIsUpdated() {
        val newConfig = Configuration()

        mediaViewController.attach(player)
        // Change the height to see the effect of orientation change.
        MediaViewHolder.backgroundIds.forEach { id ->
            mediaViewController.expandedLayout.getConstraint(id).layout.mHeight = 10
        }
        newConfig.orientation = ORIENTATION_LANDSCAPE
        configurationController.onConfigurationChanged(newConfig)

        MediaViewHolder.backgroundIds.forEach { id ->
            assertTrue(
                mediaViewController.expandedLayout.getConstraint(id).layout.mHeight ==
                    context.resources.getDimensionPixelSize(
                        R.dimen.qs_media_session_height_expanded
                    )
            )
        }
    }

    @Test
    fun testObtainViewState_applySquishFraction_toPlayerTransitionViewState_height() {
        mediaViewController.attach(player)
        player.measureState =
            TransitionViewState().apply {
                this.height = 100
                this.measureHeight = 100
            }
        mediaHostStateHolder.expansion = 1f
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        mediaHostStateHolder.measurementInput =
            MeasurementInput(widthMeasureSpec, heightMeasureSpec)

        // Test no squish
        mediaHostStateHolder.squishFraction = 1f
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.height == 100)
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.measureHeight == 100)

        // Test half squish
        mediaHostStateHolder.squishFraction = 0.5f
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.height == 50)
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.measureHeight == 100)
    }

    @Test
    fun testObtainViewState_expandedMatchesParentHeight() {
        mediaViewController.attach(player)
        player.measureState =
            TransitionViewState().apply {
                this.height = 100
                this.measureHeight = 100
            }
        mediaHostStateHolder.expandedMatchesParentHeight = true
        mediaHostStateHolder.expansion = 1f
        mediaHostStateHolder.measurementInput =
            MeasurementInput(
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            )

        // Assign the height of each expanded layout
        MediaViewHolder.backgroundIds.forEach { id ->
            mediaViewController.expandedLayout.getConstraint(id).layout.mHeight = 100
        }

        mediaViewController.obtainViewState(mediaHostStateHolder)

        // Verify height of each expanded layout is updated to match constraint
        MediaViewHolder.backgroundIds.forEach { id ->
            assertTrue(
                mediaViewController.expandedLayout.getConstraint(id).layout.mHeight ==
                    ConstraintSet.MATCH_CONSTRAINT
            )
        }
    }

    @Test
    fun petalLockscreen_shortHostKeepsTransportInsideCard() {
        val holder = MediaViewHolder.create(android.view.LayoutInflater.from(context), FrameLayout(context))
        mediaViewController.attach(holder.player)
        val density = context.resources.displayMetrics.density
        val oldHeight = (184 * density).toInt()
        val state = MediaHost.MediaHostStateHolder().apply {
            expansion = 1f
            petalStyle = com.android.systemui.petalos.PetalMediaStyle.Lockscreen
            measurementInput = MeasurementInput(
                View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(oldHeight, View.MeasureSpec.EXACTLY),
            )
        }

        val result = mediaViewController.obtainViewState(state)!!
        assertTrue(result.measureHeight > oldHeight)
        for (id in listOf(R.id.actionPlayPause, R.id.actionPrev, R.id.actionNext,
            R.id.media_progress_bar, R.id.petal_media_elapsed, R.id.petal_media_duration)) {
            val widget = result.widgetStates.getValue(id)
            assertTrue(widget.y + widget.height <= result.height)
        }
        val play = result.widgetStates.getValue(R.id.actionPlayPause)
        assertTrue(!play.gone && play.alpha > 0f)
    }

    @Test
    fun petalLockscreen_pendingQsAnimationAppliesLockscreenGeometryImmediately() {
        assertLockscreenGeometryAfterQsAnimation(startAnimation = false)
    }

    @Test
    fun petalLockscreen_ribbonContentsFitInsideSweptSurface() {
        val holder = MediaViewHolder.create(android.view.LayoutInflater.from(context), FrameLayout(context))
        mediaViewController.attach(holder.player)
        for (id in MediaViewHolder.genericButtonIds) {
            mediaViewController.expandedLayout.setVisibility(id,
                if (id == R.id.action0 || id == R.id.action1) View.VISIBLE else View.GONE)
        }
        val density = context.resources.displayMetrics.density
        for (width in listOf(280, 364, 400)) {
            val state = MediaHost.MediaHostStateHolder().apply {
                expansion = 1f
                petalStyle = com.android.systemui.petalos.PetalMediaStyle.Lockscreen
                measurementInput = MeasurementInput(
                    View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((184 * density).toInt(), View.MeasureSpec.EXACTLY),
                )
            }
            val result = mediaViewController.obtainViewState(state)!!
            val bitmap = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            try {
                val ribbon = com.android.systemui.petalos.PetalMediaRibbonDrawable(density)
                ribbon.setBounds(0, 0, result.width, result.height)
                ribbon.draw(Canvas(bitmap))
                assertThat(Color.alpha(bitmap.getPixel(0, 0))).isEqualTo(0)
                assertThat(Color.alpha(bitmap.getPixel(result.width - 1, result.height - 1))).isEqualTo(0)
                // A straight fill edge used to leave the curved rim hanging in mid-air.
                val bandHeights = listOf(0.15f, 0.35f, 0.65f, 0.85f).map { fraction ->
                    val x = (result.width * fraction).toInt()
                    val filled = (0 until result.height).filter { y ->
                        Color.alpha(bitmap.getPixel(x, y)) > 0
                    }
                    filled.last() - filled.first()
                }
                assertThat(bandHeights.max() - bandHeights.min()).isAtMost((3 * density).toInt() + 2)
                for (id in listOf(R.id.petal_media_cover, R.id.header_title, R.id.header_artist,
                    R.id.petal_media_ribbon_waveform, R.id.action0, R.id.actionPrev,
                    R.id.actionPlayPause, R.id.actionNext, R.id.action1)) {
                    val widget = result.widgetStates.getValue(id)
                    assertThat(widget.gone).isFalse()
                    val left = widget.x.toInt()
                    val top = widget.y.toInt()
                    val right = left + widget.width - 1
                    val bottom = top + widget.height - 1
                    assertThat(left).isAtLeast(0)
                    assertThat(right).isLessThan(result.width)
                    assertThat(top).isAtLeast(0)
                    assertThat(bottom).isLessThan(result.height)
                    for ((x, y) in listOf(left to top, right to top, left to bottom, right to bottom)) {
                        assertThat(Color.alpha(bitmap.getPixel(x, y))).isGreaterThan(0)
                    }
                }
                val cover = result.widgetStates.getValue(R.id.petal_media_cover)
                assertThat(cover.width).isEqualTo(cover.height)
                assertThat(result.widgetStates.getValue(R.id.media_seamless).gone).isTrue()
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun petalLockscreen_outlineDoesNotReenterThroughBackgroundInvalidation() {
        val holder = MediaViewHolder.create(android.view.LayoutInflater.from(context), FrameLayout(context))
        mediaViewController.sizeChangedListener = {}
        mediaViewController.attach(holder.player)
        val density = context.resources.displayMetrics.density
        val location = MediaHierarchyManager.LOCATION_LOCKSCREEN
        mediaHostStatesManager.mediaHostStates[location] = MediaHost.MediaHostStateHolder().apply {
            expansion = 1f
            petalStyle = com.android.systemui.petalos.PetalMediaStyle.Lockscreen
            measurementInput = MeasurementInput(
                View.MeasureSpec.makeMeasureSpec((364 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((184 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
        }
        mediaViewController.setCurrentState(MediaHierarchyManager.LOCATION_UNKNOWN,
            location, 1f, applyImmediately = true)
        val background = holder.player.background
        val oldBounds = Rect(background.bounds)
        val oldCallback = background.callback
        var invalidations = 0
        background.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                invalidations++
                check(invalidations < 2) { "Outline calculation recursively invalidated the background" }
                holder.player.outlineProvider.getOutline(holder.player, Outline())
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) {}
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }
        try {
            val outline = Outline()
            holder.player.outlineProvider.getOutline(holder.player, outline)
            holder.player.setLeftTopRightBottom(0, 0, holder.player.width + 10, holder.player.height + 10)
            holder.player.outlineProvider.getOutline(holder.player, outline)
            assertThat(outline.isEmpty).isFalse()
            assertThat(outline.canClip()).isTrue()
            assertThat(invalidations).isEqualTo(0)
            assertThat(background.bounds).isEqualTo(oldBounds)
        } finally {
            background.callback = oldCallback
            mediaViewController.onDestroy()
        }
    }

    @Test
    fun petalLockscreen_runningQsAnimationAppliesLockscreenGeometryImmediately() {
        assertLockscreenGeometryAfterQsAnimation(startAnimation = true)
    }

    @Test
    fun petalLockscreen_missingMeasurementDoesNotConsumeStyleChange() {
        assertLockscreenGeometryAfterQsAnimation(startAnimation = false, missingMeasurement = true)
    }

    private fun assertLockscreenGeometryAfterQsAnimation(
        startAnimation: Boolean,
        missingMeasurement: Boolean = false,
    ) {
        val holder = MediaViewHolder.create(android.view.LayoutInflater.from(context), FrameLayout(context))
        mediaViewController.sizeChangedListener = {}
        mediaViewController.attach(holder.player)
        val density = context.resources.displayMetrics.density
        val qs = MediaHost.MediaHostStateHolder().apply {
            expansion = 1f
            measurementInput = MeasurementInput(
                View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((184 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
        }
        val lockscreen = qs.copy().apply {
            petalStyle = com.android.systemui.petalos.PetalMediaStyle.Lockscreen
        }
        val qsLocation = MediaHierarchyManager.LOCATION_QS
        val lockscreenLocation = MediaHierarchyManager.LOCATION_LOCKSCREEN
        val unknown = MediaHierarchyManager.LOCATION_UNKNOWN
        mediaHostStatesManager.mediaHostStates[qsLocation] = qs
        mediaHostStatesManager.mediaHostStates[lockscreenLocation] = lockscreen
        val expected = mediaViewController.obtainViewState(lockscreen)!!
        mediaViewController.setCurrentState(unknown, qsLocation, 1f, applyImmediately = true)
        assertThat(holder.player.height).isNotEqualTo(expected.height)

        mediaViewController.animatePendingStateChange(1000L, 0L)
        if (startAnimation) {
            mediaViewController.setCurrentState(unknown, qsLocation, 1f, applyImmediately = false)
        }
        if (missingMeasurement) {
            lockscreen.measurementInput = null
            mediaViewController.setCurrentState(unknown, lockscreenLocation, 1f, applyImmediately = false)
            lockscreen.measurementInput = qs.measurementInput?.copy()
        }
        mediaViewController.setCurrentState(unknown, lockscreenLocation, 1f, applyImmediately = false)

        assertThat(holder.player.height).isEqualTo(expected.height)
        for (id in listOf(R.id.petal_media_cover, R.id.header_title, R.id.actionPlayPause)) {
            val child = holder.player.findViewById<View>(id)
            val widget = expected.widgetStates.getValue(id)
            assertThat(child.width).isEqualTo(widget.width)
            assertThat(child.height).isEqualTo(widget.height)
            assertThat(child.top).isEqualTo(widget.y.toInt())
        }
        mediaViewController.onDestroy()
    }

    @Test
    fun petalSplit_sideSlidersLeaveReadableMetadataAndDisjointControls() {
        val resolver = context.contentResolver
        val key = org.petalos.config.PetalConfig.KEY_QS_SKIN_ENABLED
        val previous = android.provider.Settings.System.getString(resolver, key)
        try {
            android.provider.Settings.System.putInt(resolver, key, 1)
            val holder = MediaViewHolder.create(android.view.LayoutInflater.from(context), FrameLayout(context))
            holder.titleText.text = "Jimmy Cooks"
            holder.artistText.text = "Drake"
            mediaViewController.attach(holder.player)
            for (id in MediaViewHolder.genericButtonIds) {
                mediaViewController.expandedLayout.setVisibility(id,
                    if (id == R.id.action0 || id == R.id.action1) View.VISIBLE else View.GONE)
            }
            val density = context.resources.displayMetrics.density
            for (width in listOf(219, 331)) {
                val state = MediaHost.MediaHostStateHolder().apply {
                    expansion = 1f
                    petalStyle = com.android.systemui.petalos.PetalMediaStyle.Split
                    measurementInput = MeasurementInput(
                        View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((184 * density).toInt(), View.MeasureSpec.EXACTLY),
                    )
                }
                val result = mediaViewController.obtainViewState(state)!!
                fun widget(id: Int) = result.widgetStates.getValue(id)
                val title = widget(R.id.header_title)
                assertTrue(title.width >= 96 * density)
                val art = widget(R.id.petal_media_cover)
                val progress = widget(R.id.media_progress_bar)
                assertTrue(progress.y >= art.y + art.height)
                val actions = listOf(R.id.action0, R.id.actionPrev, R.id.actionPlayPause,
                    R.id.actionNext, R.id.action1).map(::widget)
                for ((left, right) in actions.zipWithNext()) {
                    assertTrue(left.x + left.width <= right.x)
                }
                for (action in actions) {
                    assertTrue(action.x >= 0 && action.x + action.width <= result.width)
                }
                val device = widget(R.id.media_seamless)
                val output = widget(R.id.petal_media_output)
                assertTrue(output.gone)
                assertTrue(device.width >= (width - 32) * density - 2)
                assertTrue(device.y + device.height <= result.height)
                assertTrue(actions.maxOf { it.y + it.height } <= device.y)
            }
        } finally {
            android.provider.Settings.System.putString(resolver, key, previous)
        }
    }

    @Test
    fun testSquishViewState_applySquishFraction_toTransitionViewState_alpha_forMediaPlayer() {
        whenever(mockViewState.copy()).thenReturn(mockCopiedState)
        whenever(mockCopiedState.widgetStates)
            .thenReturn(
                mutableMapOf(
                    R.id.media_progress_bar to controlWidgetState,
                    R.id.header_artist to detailWidgetState,
                )
            )
        whenever(mockCopiedState.measureHeight).thenReturn(200)
        // detail widgets occupy [90, 100]
        whenever(detailWidgetState.y).thenReturn(90F)
        whenever(detailWidgetState.height).thenReturn(10)
        whenever(detailWidgetState.alpha).thenReturn(1F)
        // control widgets occupy [150, 170]
        whenever(controlWidgetState.y).thenReturn(150F)
        whenever(controlWidgetState.height).thenReturn(20)
        whenever(controlWidgetState.alpha).thenReturn(1F)
        // in current bezier, when the progress reach 0.38, the result will be 0.5
        mediaViewController.squishViewState(mockViewState, 181.4F / 200F)
        verify(controlWidgetState).alpha = floatThat { kotlin.math.abs(it - 0.5F) < delta }
        verify(detailWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
        mediaViewController.squishViewState(mockViewState, 200F / 200F)
        verify(controlWidgetState).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
        verify(detailWidgetState, times(2)).alpha = floatThat { kotlin.math.abs(it - 1.0F) < delta }
    }

    @Test
    fun testSquishViewState_applySquishFraction_toTransitionViewState_alpha_invisibleElements() {
        whenever(mockViewState.copy()).thenReturn(mockCopiedState)
        whenever(mockCopiedState.widgetStates)
            .thenReturn(
                mutableMapOf(
                    R.id.media_progress_bar to controlWidgetState,
                    R.id.header_artist to detailWidgetState,
                )
            )
        whenever(mockCopiedState.measureHeight).thenReturn(200)
        // detail widgets occupy [90, 100]
        whenever(detailWidgetState.y).thenReturn(90F)
        whenever(detailWidgetState.height).thenReturn(10)
        whenever(detailWidgetState.alpha).thenReturn(0F)
        // control widgets occupy [150, 170]
        whenever(controlWidgetState.y).thenReturn(150F)
        whenever(controlWidgetState.height).thenReturn(20)
        whenever(controlWidgetState.alpha).thenReturn(0F)
        // Verify that alpha remains 0 throughout squishing
        mediaViewController.squishViewState(mockViewState, 181.4F / 200F)
        verify(controlWidgetState, never()).alpha = floatThat { it > 0 }
        verify(detailWidgetState, never()).alpha = floatThat { it > 0 }
        mediaViewController.squishViewState(mockViewState, 200F / 200F)
        verify(controlWidgetState, never()).alpha = floatThat { it > 0 }
        verify(detailWidgetState, never()).alpha = floatThat { it > 0 }
    }

    private fun initGutsViewHolderMocks() {
        settings = ImageButton(context)
        cancel = View(context)
        cancelText = TextView(context)
        dismiss = FrameLayout(context)
        dismissText = TextView(context)
        whenever(gutsViewHolder.gutsText).thenReturn(gutsText)
        whenever(gutsViewHolder.settings).thenReturn(settings)
        whenever(gutsViewHolder.cancel).thenReturn(cancel)
        whenever(gutsViewHolder.cancelText).thenReturn(cancelText)
        whenever(gutsViewHolder.dismiss).thenReturn(dismiss)
        whenever(gutsViewHolder.dismissText).thenReturn(dismissText)
    }

    private fun initMediaViewHolderMocks() {
        titleText = TextView(context)
        artistText = TextView(context)
        explicitIndicator = CachingIconView(context).also { it.id = R.id.media_explicit_indicator }
        seamless = FrameLayout(context)
        seamless.foreground = seamlessBackground
        seamlessButton = View(context)
        seamlessIcon = ImageView(context)
        seamlessText = TextView(context)
        seekBar = SeekBar(context).also { it.id = R.id.media_progress_bar }

        actionPlayPause = ImageButton(context).also { it.id = R.id.actionPlayPause }
        actionPrev = ImageButton(context).also { it.id = R.id.actionPrev }
        actionNext = ImageButton(context).also { it.id = R.id.actionNext }
        scrubbingElapsedTimeView =
            TextView(context).also { it.id = R.id.media_scrubbing_elapsed_time }
        scrubbingTotalTimeView = TextView(context).also { it.id = R.id.media_scrubbing_total_time }

        multiRippleView = MultiRippleView(context, null)
        turbulenceNoiseView = TurbulenceNoiseView(context, null)
        loadingEffectView = LoadingEffectView(context, null)

        whenever(viewHolder.player).thenReturn(view)
        whenever(view.context).thenReturn(context)
        whenever(viewHolder.albumView).thenReturn(albumView)
        whenever(albumView.foreground).thenReturn(Mockito.mock(Drawable::class.java))
        whenever(viewHolder.titleText).thenReturn(titleText)
        whenever(viewHolder.artistText).thenReturn(artistText)
        whenever(viewHolder.explicitIndicator).thenReturn(explicitIndicator)
        whenever(seamlessBackground.getDrawable(0))
            .thenReturn(Mockito.mock(GradientDrawable::class.java))
        whenever(viewHolder.seamless).thenReturn(seamless)
        whenever(viewHolder.seamlessButton).thenReturn(seamlessButton)
        whenever(viewHolder.seamlessIcon).thenReturn(seamlessIcon)
        whenever(viewHolder.seamlessText).thenReturn(seamlessText)
        whenever(viewHolder.seekBar).thenReturn(seekBar)
        whenever(viewHolder.scrubbingElapsedTimeView).thenReturn(scrubbingElapsedTimeView)
        whenever(viewHolder.scrubbingTotalTimeView).thenReturn(scrubbingTotalTimeView)
        whenever(viewHolder.gutsViewHolder).thenReturn(gutsViewHolder)
        whenever(seekBarViewModel.progress).thenReturn(seekBarData)

        // Action buttons
        whenever(viewHolder.actionPlayPause).thenReturn(actionPlayPause)
        whenever(viewHolder.getAction(R.id.actionNext)).thenReturn(actionNext)
        whenever(viewHolder.getAction(R.id.actionPrev)).thenReturn(actionPrev)
        whenever(viewHolder.getAction(R.id.actionPlayPause)).thenReturn(actionPlayPause)

        whenever(viewHolder.multiRippleView).thenReturn(multiRippleView)
        whenever(viewHolder.turbulenceNoiseView).thenReturn(turbulenceNoiseView)
        whenever(viewHolder.loadingEffectView).thenReturn(loadingEffectView)
    }
}
