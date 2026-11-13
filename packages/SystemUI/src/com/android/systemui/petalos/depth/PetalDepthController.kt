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

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ViewTreeObserver
import com.android.app.animation.Interpolators
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.petalos.PetalLockScreenMediaCover
import com.android.systemui.shared.R as sharedR
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.petalos.config.PetalConfig

// 3d lockscreen, subject drawn over the clock
@SysUISingleton
class PetalDepthController @Inject constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Main private val mainExecutor: DelayableExecutor,
    private val keyguardRootView: KeyguardRootView,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val shadeInteractor: ShadeInteractor,
    private val mediaCover: PetalLockScreenMediaCover,
    private val cdPlayer: com.android.systemui.petalos.PetalCdLockScreenController,
    private val keyguardRepository: com.android.systemui.keyguard.data.repository.KeyguardRepository,
) : CoreStartable {

    companion object {
        private const val TAG = "PetalDepth"
        // default parallax amount in dp
        private const val DEFAULT_TILT_DP = 10f
        // should match the seamless unlock fade
        private const val FADE_OUT_MS = 260L
        // stops a second fade from stacking
        private const val TAG_FADING = "petal_fading"
        // tag for the unlock lead animation
        private const val TAG_LEADING = "petal_leading"
        private const val SUBJECT_LEAD_SCALE = 1.08f
        private const val SUBJECT_LEAD_MS = 300L
        // how long the lockscreen player takes to slide back after qs lets go
        private const val SPACING_SETTLE_MS = 420L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val subjectView = PetalDepthSubjectView(context)
    private val clockView = PetalDepthClockView(context)
    private var attached = false
    private var enabled = false
    private var clockStyle = 0
    private var cutoutGeneration = 0
    private var loadRequest = 0
    private var shadeExpanded = false
    private var mediaCoverActive = false

    // the depth wallpaper's own frost/liquid, swapped back in when the sleeve goes away
    private var wallFrost: Bitmap? = null
    private var wallLiquid: Bitmap? = null
    private var coverBackdropFor: Bitmap? = null

    // cd player showing, cutout steps aside until it leaves
    private var cdPlayerActive = false

    // unlock in progress, fade instead of hiding
    private var keyguardGoingAway = false

    // true when our clock is showing
    private var petalClockVisible = false

    // stock clock rebinds late, keep re-hiding it
    private val clockLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        reassertStockClockHide()
        // a ConstraintSet off some other section can stomp the z, put it back
        if (subjectView.translationZ != PetalDepthSubjectView.DEPTH_Z_SUBJECT) {
            subjectView.translationZ = PetalDepthSubjectView.DEPTH_Z_SUBJECT
        }
        if (clockView.translationZ != PetalDepthSubjectView.DEPTH_Z_CLOCK) {
            clockView.translationZ = PetalDepthSubjectView.DEPTH_Z_CLOCK
        }
    }
    private val mediaPosition = IntArray(2)
    private val clockPosition = IntArray(2)
    private val mediaSpacingListener = ViewTreeObserver.OnPreDrawListener {
        updateMediaSpacing()
        true
    }

    // when the shade last let go. the player slides home for a bit after, measuring it mid-slide
    // shrank/shoved the clock about every time you peeked qs on the lockscreen
    private var shadeReleasedAt = 0L
    private val settleSpacing = Runnable { updateMediaSpacing() }

    private fun updateMediaSpacing() {
        // Keep the settled clock size while QS borrows the player.
        if (petalClockVisible && shadeExpanded) return
        if (android.os.SystemClock.uptimeMillis() - shadeReleasedAt < SPACING_SETTLE_MS) return
        val stack = keyguardRootView.rootView.findViewById<android.view.ViewGroup>(
            R.id.notification_stack_scroller)
        var bottom = Int.MAX_VALUE
        if (petalClockVisible && !shadeExpanded && !cdPlayerActive && stack != null) {
            for (index in 0 until stack.childCount) {
                val child = stack.getChildAt(index)
                if (child is com.android.systemui.statusbar.notification.stack.MediaContainerView &&
                    child.visibility != android.view.View.GONE && child.height > 0) {
                    val player = child.findViewById<com.android.systemui.util.animation.TransitionLayout>(
                        R.id.qs_media_controls)
                    if (player?.getTag(R.id.petal_media_cover) !=
                        com.android.systemui.petalos.PetalMediaStyle.Lockscreen ||
                        !child.isShown || child.actualHeight <= 0 ||
                        child.actualHeight < (player?.measureState?.measureHeight ?: 0)) return
                    child.getLocationOnScreen(mediaPosition)
                    clockView.getLocationOnScreen(clockPosition)
                    // Leave a bit of air above the glass, for fuck's sake.
                    bottom = mediaPosition[1] - clockPosition[1] -
                        (24f * context.resources.displayMetrics.density).toInt()
                    break
                }
            }
        }
        clockView.setContentBottom(bottom)
    }

    // --- settings -----------------------------------------------------------------------------

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) = refreshFromSettings()
        }

    // --- lock / shade state ---------------------------------------------------------------

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) = updateVisibility()
        override fun onDozingChanged(isDozing: Boolean) = updateVisibility()

        // the real wake/sleep curve, so aod <-> lock morphs instead of swapping clocks
        override fun onDozeAmountChanged(linear: Float, eased: Float) = applyDozeAmount(eased)
    }

    private var dozeAmount = 0f

    private fun applyDozeAmount(amount: Float) {
        dozeAmount = amount
        clockView.setDozeAmount(amount)
        // cutout melts away going to aod, no subject burning into the oled all night
        if (subjectView.tag == null) subjectView.alpha = 1f - amount
    }

    private val keyguardStateListener = object : KeyguardStateController.Callback {
        override fun onKeyguardGoingAwayChanged() {
            keyguardGoingAway = keyguardStateController.isKeyguardGoingAway
            updateVisibility()
        }
    }

    // --- tilt --------------------------------------------------------------------------

    // off till settings say otherwise, same default as PetalConfig
    private var parallaxEnabled = false
    // tilt strength in dp, set in depth studio
    private var tiltMaxDp = DEFAULT_TILT_DP
    private var tiltX = 0f
    private var tiltY = 0f
    private var tiltTargetX = 0f
    private var tiltTargetY = 0f
    private var tiltFrameScheduled = false
    private var sensorRegistered = false
    private var lastTiltFrame = 0L
    private var tiltRotation = -1
    private val tiltMatrix = FloatArray(9)
    private val displayTiltMatrix = FloatArray(9)
    private var baselineTiltMatrix: FloatArray? = null

    private val tiltRunnable = object : Runnable {
        override fun run() {
            tiltFrameScheduled = false
            // ease toward the target each frame
            val now = android.os.SystemClock.uptimeMillis()
            val dt = if (lastTiltFrame == 0L) 16L else (now - lastTiltFrame).coerceIn(1L, 64L)
            lastTiltFrame = now
            val blend = 1f - kotlin.math.exp(-dt / 100f)
            tiltX += (tiltTargetX - tiltX) * blend
            tiltY += (tiltTargetY - tiltY) * blend
            subjectView.setTilt(tiltX, tiltY)
            // feed the same filtered value to both
            clockView.setSubjectTilt(tiltX, tiltY)
            if (Math.abs(tiltTargetX - tiltX) > 0.1f || Math.abs(tiltTargetY - tiltY) > 0.1f) {
                scheduleTiltFrame()
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // don't let a bad sensor reading crash sysui
            runCatching { handleTiltEvent(event) }
        }

        private fun handleTiltEvent(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
                event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
            // turn rotation vector into small px offsets
            if (!sensorRegistered || !parallaxEnabled || event.values.any { !it.isFinite() }) return
            SensorManager.getRotationMatrixFromVector(tiltMatrix, event.values)
            val rotation = subjectView.display?.rotation ?: android.view.Surface.ROTATION_0
            val axes = when (rotation) {
                android.view.Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                android.view.Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                android.view.Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(tiltMatrix, axes.first, axes.second, displayTiltMatrix)
            if (displayTiltMatrix.any { !it.isFinite() }) return
            if (tiltRotation != rotation) {
                tiltRotation = rotation
                baselineTiltMatrix = null
                tiltTargetX = 0f
                tiltTargetY = 0f
                scheduleTiltFrame()
            }
            val baseline = baselineTiltMatrix
            if (baseline == null) {
                baselineTiltMatrix = displayTiltMatrix.copyOf()
                return
            }
            // Euler pitch went wonky upright; relative rotation behaves.
            val r02 = baseline[0] * displayTiltMatrix[2] +
                baseline[3] * displayTiltMatrix[5] + baseline[6] * displayTiltMatrix[8]
            val r12 = baseline[1] * displayTiltMatrix[2] +
                baseline[4] * displayTiltMatrix[5] + baseline[7] * displayTiltMatrix[8]
            val r22 = baseline[2] * displayTiltMatrix[2] +
                baseline[5] * displayTiltMatrix[5] + baseline[8] * displayTiltMatrix[8]
            val roll = kotlin.math.atan2(r02, r22)
            val pitch = -kotlin.math.atan2(r12, r22)
            val density = context.resources.displayMetrics.density
            val maxPx = tiltMaxDp * density
            // measure from the pose at registration
            fun normalized(delta: Float): Float {
                val deadZone = 0.018f
                val wrapped = kotlin.math.atan2(kotlin.math.sin(delta), kotlin.math.cos(delta))
                val magnitude = ((kotlin.math.abs(wrapped) - deadZone) / (0.28f - deadZone))
                    .coerceIn(0f, 1f)
                return kotlin.math.sign(wrapped) * magnitude
            }
            tiltTargetX = normalized(roll) * maxPx
            tiltTargetY = normalized(pitch) * maxPx
            scheduleTiltFrame()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun scheduleTiltFrame() {
        if (!tiltFrameScheduled) {
            tiltFrameScheduled = true
            subjectView.postOnAnimation(tiltRunnable)
        }
    }

    // --- start and dump --------------------------------------------------------------------------

    override fun start() {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshFromSettings()
            }
        }, IntentFilter(Intent.ACTION_USER_UNLOCKED), null, mainHandler)
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_ENABLED), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CUTOUT_URI), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_GENERATION), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_PARALLAX), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_STYLE), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_STACK), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_COLOR), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_DATE), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_COLON), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_ANCHOR), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_X), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_SCALE), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_TILT_DP), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_NOTIFICATION_START), false, settingsObserver)

        statusBarStateController.addCallback(stateListener)
        keyguardStateController.addCallback(keyguardStateListener)
        scope.launch {
            // this flag is true on the normal lockscreen too
            shadeInteractor.anyExpansion.collect { onShadeExpansionChanged(it > 0f) }
        }
        scope.launch {
            keyguardRepository.dozeTimeTick.collect {
                if (petalClockVisible) clockView.onDozeTimeTick()
            }
        }
        // album art hides the subject but not the clock
        mediaCoverActive = mediaCover.isCoverApplied()
        mediaCover.addCoverStateListener { applied ->
            mediaCoverActive = applied
            applyBackdrops()
            updateVisibility()
        }
        // cd player does the same, cutout comes back when it goes
        cdPlayer.addCdStateListener { showing ->
            cdPlayerActive = showing
            updateVisibility()
        }
        mainExecutor.execute {
            clockView.attachToRoot(keyguardRootView)
            subjectView.attachToRoot(keyguardRootView)
            attached = true
            if (keyguardRootView.viewTreeObserver.isAlive) {
                keyguardRootView.viewTreeObserver.addOnGlobalLayoutListener(clockLayoutListener)
                keyguardRootView.viewTreeObserver.addOnPreDrawListener(mediaSpacingListener)
            }
            refreshFromSettings()
        }
    }

    private fun refreshFromSettings() {
        enabled = PetalConfig.isDepthEnabled(context)
        PetalDepthNotificationPosition.update(enabled, PetalConfig.getDepthNotificationStartPct(context))
        parallaxEnabled = PetalConfig.isDepthParallaxEnabled(context)
        val generation = PetalConfig.getDepthGeneration(context)
        val uri = PetalConfig.getDepthCutoutUri(context)

        clockStyle = PetalConfig.getDepthClockStyle(context)
        clockView.setStyle(clockStyle)
        clockView.setStackVertical(PetalConfig.isDepthClockStackVertical(context))
        clockView.setAnchor(PetalConfig.getDepthClockAnchorPct(context) / 100f)
        clockView.setAnchorX(PetalConfig.getDepthClockXPct(context) / 100f)
        clockView.setScale(PetalConfig.getDepthClockScalePct(context) / 100f)
        tiltMaxDp = PetalConfig.getDepthTiltDp(context).toFloat()
        // -1 means auto, so use transparent
        val clockColor = PetalConfig.getDepthClockColor(context)
        clockView.setAccentColor(
            if (clockColor == PetalConfig.DISABLED) Color.TRANSPARENT else clockColor
        )
        clockView.setDateEnabled(PetalConfig.isDepthClockDateEnabled(context))
        clockView.setColonEnabled(PetalConfig.isDepthClockColonEnabled(context, false))

        if (!enabled || uri == null) {
            loadRequest++
            subjectView.setSubject(null)
            wallFrost = null
            wallLiquid = null
            coverBackdropFor = null
            clockView.setBackdrop(null)
            clockView.setLiquidBackdrop(null)
            updateVisibility()
            stopParallax()
            return
        }

        if (generation != cutoutGeneration || !subjectView.hasSubject) {
            cutoutGeneration = generation
            loadCutout(Uri.parse(uri))
        }
        updateVisibility()
    }

    private fun loadCutout(uri: Uri) {
        val generation = cutoutGeneration
        val request = ++loadRequest
        bgScope.launch {
            var bitmap = decodeCutout(uri)
            // decode failed on half-written files, retry
            var attempts = 0
            while (bitmap == null && attempts < 3) {
                attempts++
                Log.w(TAG, "Cutout decode failed (attempt $attempts/3), retrying")
                Thread.sleep(500L * attempts)
                bitmap = decodeCutout(uri)
            }
            val frost = runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse("content://org.petalos.depth/frost.png"))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            // Liquid wants the real paper, not the frost.
            val liquid = runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse("content://org.petalos.depth/liquid.png"))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            mainExecutor.execute {
                if (enabled && generation == cutoutGeneration && request == loadRequest) {
                    subjectView.setSubject(bitmap)
                    wallFrost = frost
                    wallLiquid = liquid
                    coverBackdropFor = null
                    applyBackdrops()
                }
                updateVisibility()
            }
        }
    }

    // sleeve up = frost/liquid look through the album art, otherwise the depth wallpaper
    private fun applyBackdrops() {
        val cover = if (mediaCoverActive) mediaCover.currentCover() else null
        if (cover == null) {
            coverBackdropFor = null
            clockView.setBackdrop(wallFrost)
            clockView.setLiquidBackdrop(wallLiquid)
            return
        }
        if (cover === coverBackdropFor) return
        coverBackdropFor = cover
        bgScope.launch {
            // cheap blur: shrink to a smudge then let the shader's linear filter stretch it back
            val frost = runCatching {
                val small = Bitmap.createScaledBitmap(cover,
                    (cover.width / 24).coerceAtLeast(2), (cover.height / 24).coerceAtLeast(2), true)
                Bitmap.createScaledBitmap(small, cover.width / 4, cover.height / 4, true)
            }.getOrNull()
            mainExecutor.execute {
                if (coverBackdropFor !== cover) return@execute
                clockView.setBackdrop(frost ?: cover)
                clockView.setLiquidBackdrop(cover)
            }
        }
    }

    private fun decodeCutout(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)?.takeIf { bitmap ->
                // no alpha means a black square, reject it
                bitmap.hasAlpha().also { valid ->
                    if (!valid) Log.w(TAG, "Ignoring opaque cutout; reselect foreground in Depth Studio")
                }
            }
        }
    }.onFailure { Log.w(TAG, "Failed to load cutout $uri", it) }.getOrNull()

    // lockscreen only. the shade used to nuke this the second you twitched the pull
    // down, which left a black hole where the wallpaper should be. leave it up.
    private fun updateVisibility() {
        if (!attached) return
        clockView.setDepthApplied(enabled && subjectView.hasSubject)
        // listener callbacks get dropped across unlock/doze churn, so re-read the
        // real state every time instead of trusting whatever we last heard
        keyguardGoingAway = keyguardStateController.isKeyguardGoingAway
        mediaCoverActive = mediaCover.isCoverApplied()
        cdPlayerActive = cdPlayer.isCdShowing
        if (keyguardGoingAway && PetalConfig.isSeamlessUnlockEnabled(context)
                && !statusBarStateController.isDozing) {
            stopParallax(resetTilt = false)
            clockView.stopTicking()
            reassertStockClockHide()
            // home gets no cutout, so melt the depth shit out on the way up
            morphOutForUnlock(subjectView, lead = true)
            // twins on both screens = sink this one, home lifts its own back up from there
            if (PetalConfig.isDepthHomeEnabled(context) && PetalConfig.areDepthClocksAligned(context)) {
                handOffClock()
            } else {
                morphOutForUnlock(clockView, lead = false)
            }
            return
        }
        val dozing = statusBarStateController.isDozing
        // aod counts as keyguard now, the depth clock carries through it
        val onKeyguard = statusBarStateController.state == StatusBarState.KEYGUARD
        val visible = enabled && subjectView.hasSubject && onKeyguard &&
            !mediaCoverActive && !cdPlayerActive && !keyguardGoingAway

        // unlocking, fade into home
        if (keyguardGoingAway && subjectView.visibility == android.view.View.VISIBLE) {
            fadeOutLayer(subjectView)
        } else if (subjectView.visibility == android.view.View.VISIBLE) {
            // cancelled, reset or it stays skewed
            subjectView.tag = null
            subjectView.animate().cancel()
            subjectView.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            subjectView.setAlpha(1f - dozeAmount)
            subjectView.setScaleX(1f)
            subjectView.setScaleY(1f)
        } else if (visible) {
            // reset the tag or the next unlock won't animate
            subjectView.tag = null
            subjectView.visibility = android.view.View.VISIBLE
            subjectView.setAlpha(1f - dozeAmount)
            subjectView.setScaleX(1f)
            subjectView.setScaleY(1f)
        } else if (!keyguardGoingAway) {
            subjectView.visibility = android.view.View.GONE
            subjectView.setAlpha(1f)
        }
        // mid-fade, leave it alone

        // Keep the clock visible beneath the CD player; only the cutout is suppressed.
        val clockVisible = enabled && clockStyle > 0 && onKeyguard &&
            !keyguardGoingAway
        petalClockVisible = clockVisible
        if (clockView.visibility == android.view.View.VISIBLE) {
            if (clockVisible) {
                clockView.tag = null
                clockView.animate().cancel()
                clockView.animate().setUpdateListener(null)
                clockView.translationY = 0f
                clockView.setAlpha(1f)
            } else {
                fadeOutLayer(clockView)
            }
        } else if (clockVisible && !keyguardGoingAway) {
            // wouldn't reappear after GONE, no idea why
            clockView.tag = null
            clockView.animate().cancel()
            clockView.animate().setUpdateListener(null)
            clockView.translationY = 0f
            clockView.visibility = android.view.View.VISIBLE
            clockView.setAlpha(1f)
        } else if (!clockVisible && !keyguardGoingAway) {
            clockView.visibility = android.view.View.GONE
            clockView.setAlpha(1f)
        }
        if (clockVisible) clockView.startTicking() else clockView.stopTicking()
        clockView.setDozeAmount(dozeAmount)
        // no gyro on aod, thats just burning battery to wobble a dim outline
        if (visible && parallaxEnabled && !keyguardGoingAway && !dozing) startParallax()
        else stopParallax()
        reassertStockClockHide()
    }

    // fade out then set GONE
    private fun fadeOutLayer(layer: android.view.View) {
        if (layer.tag == TAG_FADING) return
        layer.tag = TAG_FADING
        layer.animate().setUpdateListener(null)
        layer.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .withEndAction {
                layer.tag = null
                layer.visibility = android.view.View.GONE
                layer.setAlpha(1f)
            }
            .start()
    }

    // lock clock sinks a hair, no fade. keyguard fading takes it away, home's twin is already under it
    private fun handOffClock() {
        if (clockView.tag == TAG_LEADING) return
        if (clockView.visibility != android.view.View.VISIBLE) return
        clockView.tag = TAG_LEADING
        clockView.animate().cancel()
        val startX = tiltX
        val startY = tiltY
        val drop = PetalConfig.DEPTH_HANDOFF_DROP_DP * context.resources.displayMetrics.density
        runCatching {
            context.sendBroadcastAsUser(
                Intent(PetalConfig.ACTION_DEPTH_CLOCK_HANDOFF)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                android.os.UserHandle.CURRENT,
            )
        }.onFailure { Log.w(TAG, "clock handoff broadcast failed", it) }
        clockView.animate()
            .translationY(drop)
            .setDuration(PetalConfig.DEPTH_HANDOFF_SINK_MS)
            .setInterpolator(Interpolators.STANDARD_ACCELERATE)
            .setUpdateListener { a ->
                val k = 1f - a.animatedFraction
                clockView.setSubjectTilt(startX * k, startY * k)
            }
            .withEndAction {
                clockView.tag = null
                clockView.visibility = android.view.View.GONE
                clockView.setAlpha(1f)
                clockView.translationY = 0f
                clockView.setSubjectTilt(0f, 0f)
                clockView.animate().setUpdateListener(null)
            }
            .start()
    }

    // subject floats up a touch then evaporates with the lock ui
    private fun morphOutForUnlock(layer: android.view.View, lead: Boolean) {
        if (layer.tag == TAG_LEADING) return
        if (layer.visibility != android.view.View.VISIBLE) return
        layer.tag = TAG_LEADING
        layer.animate().cancel()
        layer.animate().setUpdateListener(null)
        if (lead) {
            layer.pivotX = layer.width / 2f
            layer.pivotY = layer.height / 2f
        }
        layer.animate()
            .scaleX(if (lead) SUBJECT_LEAD_SCALE else 1f)
            .scaleY(if (lead) SUBJECT_LEAD_SCALE else 1f)
            .alpha(0f)
            .setDuration(SUBJECT_LEAD_MS)
            .setInterpolator(Interpolators.STANDARD_DECELERATE)
            .withEndAction {
                layer.tag = null
                layer.visibility = android.view.View.GONE
                layer.setAlpha(1f)
                layer.scaleX = 1f
                layer.scaleY = 1f
            }
            .start()
    }

    // runs each layout, stock clock keeps rebinding
    private fun reassertStockClockHide() {
        if (!attached) return
        // depth clock picked = stock never gets a look in. fingerprint off aod flips the state away
        // from KEYGUARD a beat before goingAway lands, and the old rule unhid lineage's clock right there
        val hideStock = petalClockVisible || (enabled && clockStyle > 0) ||
            (keyguardGoingAway && enabled)
        val targetVisibility = if (hideStock) android.view.View.INVISIBLE else android.view.View.VISIBLE
        val large = keyguardRootView.findViewById<android.view.View>(
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)
        val small = keyguardRootView.findViewById<android.view.View>(
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL)
        for (clock in listOfNotNull(large, small)) {
            if (clock.visibility != targetVisibility) {
                clock.visibility = targetVisibility
            }
        }
        // hide the date too or it shows through
        for (dateId in intArrayOf(
            sharedR.id.date_smartspace_view, sharedR.id.date_smartspace_view_large
        )) {
            val date = keyguardRootView.findViewById<android.view.View>(dateId) ?: continue
            val target = if (hideStock) android.view.View.GONE else android.view.View.VISIBLE
            if (date.visibility != target) {
                date.visibility = target
            }
        }
        // AOSP puts the date in a slice view
        val sliceDate = keyguardRootView.findViewById<android.view.View>(R.id.keyguard_slice_view)
        if (sliceDate != null) {
            val sliceTarget = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
            if (sliceDate.visibility != sliceTarget) {
                sliceDate.visibility = sliceTarget
            }
        }
    }

    // shade listener callback
    fun onShadeExpansionChanged(expanded: Boolean) {
        if (shadeExpanded && !expanded) {
            shadeReleasedAt = android.os.SystemClock.uptimeMillis()
            clockView.removeCallbacks(settleSpacing)
            clockView.postDelayed(settleSpacing, SPACING_SETTLE_MS + 16)
        }
        shadeExpanded = expanded
        updateVisibility()
    }

    // --- parallax -------------------------------------------------------------------------

    private fun startParallax() {
        if (sensorRegistered) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        // UI rate is plenty here
        baselineTiltMatrix = null
        sensorRegistered = sm.registerListener(
            sensorListener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopParallax(resetTilt: Boolean = true) {
        if (sensorRegistered) {
            context.getSystemService(SensorManager::class.java)?.unregisterListener(sensorListener)
        }
        sensorRegistered = false
        baselineTiltMatrix = null
        subjectView.removeCallbacks(tiltRunnable)
        lastTiltFrame = 0L
        tiltFrameScheduled = false
        if (!resetTilt) return
        tiltTargetX = 0f
        tiltTargetY = 0f
        tiltX = 0f
        tiltY = 0f
        subjectView.setTilt(0f, 0f)
        clockView.setSubjectTilt(0f, 0f)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("PetalDepthController:")
        pw.println("  bouncerClock=${clockView.bouncerStateForDump()}")
        pw.println("  enabled=$enabled generation=$cutoutGeneration shadeExpanded=$shadeExpanded")
        pw.println(
            "  attached=$attached goingAway=$keyguardGoingAway cover=$mediaCoverActive" +
                " cd=$cdPlayerActive dozing=${statusBarStateController.isDozing}" +
                " state=${statusBarStateController.state}")
        pw.println(
            "  subject=${subjectView.subjectForDump()?.width}x${subjectView.subjectForDump()?.height}" +
                " visibility=${subjectView.visibility}")
    }
}
