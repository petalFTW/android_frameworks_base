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

// petalOS 3D depth lock screen: draws the wallpaper's segmented subject above the keyguard clock
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
) : CoreStartable {

    companion object {
        private const val TAG = "PetalDepth"
        /** Fallback parallax offset in dp for the subject layer. */
        private const val DEFAULT_TILT_DP = 10f
        /** Match the seamless unlock fade so the depth layers land as home appears. */
        private const val FADE_OUT_MS = 260L
        /** Tag guard so updateVisibility spam can't stack fades on the same layer. */
        private const val TAG_FADING = "petal_fading"
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

    // set when the unlock sequence kicks off, hides the depth layers with a fade instead of
    private var keyguardGoingAway = false

    /** Whether the petal 3D clock is currently shown (drives the stock clock hide). */
    private var petalClockVisible = false

    // stock clock faces get bound lazily, so re-assert the hide on every layout or it races
    private val clockLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        reassertStockClockHide()
    }

    // --- settings -------------------------------------------------------------------------

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) = refreshFromSettings()
        }

    // --- keyguard / shade state -----------------------------------------------------------

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) = updateVisibility()
        override fun onDozingChanged(isDozing: Boolean) = updateVisibility()
    }

    private val keyguardStateListener = object : KeyguardStateController.Callback {
        override fun onKeyguardGoingAwayChanged() {
            keyguardGoingAway = keyguardStateController.isKeyguardGoingAway
            updateVisibility()
        }
    }

    // --- tilt parallax ----------------------------------------------------------------------

    private var parallaxEnabled = true
    /** Parallax strength in dp, configurable from Depth Studio. */
    private var tiltMaxDp = DEFAULT_TILT_DP
    private var tiltX = 0f
    private var tiltY = 0f
    private var tiltTargetX = 0f
    private var tiltTargetY = 0f
    private var tiltFrameScheduled = false
    private var sensorRegistered = false
    private var baselineRoll: Float? = null
    private var baselinePitch: Float? = null

    private val tiltRunnable = object : Runnable {
        override fun run() {
            tiltFrameScheduled = false
            // Critically-damped-ish easing toward the target.
            tiltX += (tiltTargetX - tiltX) * 0.15f
            tiltY += (tiltTargetY - tiltY) * 0.15f
            subjectView.setTilt(tiltX, tiltY)
            // Both layers must use the same filtered sample. Feeding the clock the raw sensor
            clockView.setSubjectTilt(tiltX, tiltY)
            if (Math.abs(tiltTargetX - tiltX) > 0.1f || Math.abs(tiltTargetY - tiltY) > 0.1f) {
                scheduleTiltFrame()
            }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Parallax is cosmetic; a bad reading must never take down SystemUI.
            runCatching { handleTiltEvent(event) }
        }

        private fun handleTiltEvent(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
                event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
            // Map device tilt to small px offsets on screen.
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val values = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, values)
            val roll = values[2]
            val pitch = values[1]
            if (baselineRoll == null || baselinePitch == null) {
                baselineRoll = roll
                baselinePitch = pitch
                return
            }
            val density = context.resources.displayMetrics.density
            val maxPx = tiltMaxDp * density
            // Work relative to the pose at registration and ignore tiny sensor noise. This makes
            fun normalized(delta: Float): Float {
                val deadZone = 0.018f
                val d = if (kotlin.math.abs(delta) < deadZone) 0f else delta
                return (d.coerceIn(-0.28f, 0.28f) / 0.28f)
            }
            tiltTargetX = normalized(roll - baselineRoll!!) * maxPx
            tiltTargetY = normalized(pitch - baselinePitch!!) * maxPx
            scheduleTiltFrame()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun scheduleTiltFrame() {
        if (!tiltFrameScheduled) {
            tiltFrameScheduled = true
            mainHandler.post(tiltRunnable)
        }
    }

    // --- lifecycle ---------------------------------------------------------------------------

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
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_ANCHOR), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_X), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_CLOCK_SCALE), false, settingsObserver)
        resolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_DEPTH_TILT_DP), false, settingsObserver)

        statusBarStateController.addCallback(stateListener)
        keyguardStateController.addCallback(keyguardStateListener)
        scope.launch {
            // The legacy expanded-window flag is also true on the ordinary lock screen.
            shadeInteractor.anyExpansion.collect { onShadeExpansionChanged(it > 0f) }
        }
        // Album art hides the subject, while the depth clock stays.
        mediaCoverActive = mediaCover.isCoverApplied()
        mediaCover.addCoverStateListener { applied ->
            mediaCoverActive = applied
            updateVisibility()
        }
        mainExecutor.execute {
            clockView.attachToRoot(keyguardRootView)
            subjectView.attachToRoot(keyguardRootView)
            attached = true
            if (keyguardRootView.viewTreeObserver.isAlive) {
                keyguardRootView.viewTreeObserver.addOnGlobalLayoutListener(clockLayoutListener)
            }
            refreshFromSettings()
        }
    }

    private fun refreshFromSettings() {
        enabled = PetalConfig.isDepthEnabled(context)
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
        // PetalConfig.DISABLED (-1) means "automatic"; translate to TRANSPARENT which the clock
        val clockColor = PetalConfig.getDepthClockColor(context)
        clockView.setAccentColor(
            if (clockColor == PetalConfig.DISABLED) Color.TRANSPARENT else clockColor
        )
        clockView.setDateEnabled(PetalConfig.isDepthClockDateEnabled(context))

        if (!enabled || uri == null) {
            loadRequest++
            subjectView.setSubject(null)
            clockView.setBackdrop(null)
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
            // torn/in-flight file or transient decode failure: retry, else the subject is gone
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
            mainExecutor.execute {
                if (enabled && generation == cutoutGeneration && request == loadRequest) {
                    subjectView.setSubject(bitmap)
                    clockView.setBackdrop(frost)
                }
                updateVisibility()
            }
        }
    }

    private fun decodeCutout(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)?.takeIf { bitmap ->
                // old exports lost alpha and covered the whole wallpaper in black
                bitmap.hasAlpha().also { valid ->
                    if (!valid) Log.w(TAG, "Ignoring opaque cutout; reselect foreground in Depth Studio")
                }
            }
        }
    }.onFailure { Log.w(TAG, "Failed to load cutout $uri", it) }.getOrNull()

    /** Subject and clock show only on the lock screen, awake, with the shade fully collapsed. */
    private fun updateVisibility() {
        if (!attached) return
        if (keyguardGoingAway && PetalConfig.isSeamlessUnlockEnabled(context)
                && !statusBarStateController.isDozing) {
            // The parent owns this fade; a second fade makes the cutout vanish early.
            stopParallax(resetTilt = false)
            clockView.stopTicking()
            reassertStockClockHide()
            return
        }
        val onKeyguard =
            statusBarStateController.state == StatusBarState.KEYGUARD &&
                !statusBarStateController.isDozing
        val visible = enabled && subjectView.hasSubject && onKeyguard && !shadeExpanded &&
            !mediaCoverActive && !keyguardGoingAway

        // unlock started: fade out with the keyguard so the depth layers dissolve into home
        if (keyguardGoingAway && subjectView.visibility == android.view.View.VISIBLE) {
            fadeOutLayer(subjectView)
        } else if (subjectView.visibility == android.view.View.VISIBLE) {
            // unlock got cancelled or state flipped back: stop the fade, its end action GONEs
            subjectView.tag = null
            subjectView.animate().cancel()
            subjectView.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            subjectView.setAlpha(1f)
        } else if (visible) {
            subjectView.visibility = android.view.View.VISIBLE
            subjectView.setAlpha(1f)
        } else if (!keyguardGoingAway) {
            subjectView.visibility = android.view.View.GONE
            subjectView.setAlpha(1f)
        }
        // while goingAway, leave the fading subject alone, null alpha snaps it back to opaque

        // Keep the depth clock over album art.
        val clockVisible = enabled && clockStyle > 0 && onKeyguard && !shadeExpanded &&
            !keyguardGoingAway
        petalClockVisible = clockVisible
        if (clockView.visibility == android.view.View.VISIBLE) {
            if (clockVisible) {
                clockView.tag = null
                clockView.animate().cancel()
                clockView.setAlpha(1f)
            } else {
                fadeOutLayer(clockView)
            }
        } else if (clockVisible && !keyguardGoingAway) {
            // this fucking thing never came back once GONE, clock died after the first hide
            clockView.tag = null
            clockView.animate().cancel()
            clockView.visibility = android.view.View.VISIBLE
            clockView.setAlpha(1f)
        } else if (!clockVisible && !keyguardGoingAway) {
            clockView.visibility = android.view.View.GONE
            clockView.setAlpha(1f)
        }
        if (clockVisible) clockView.startTicking() else clockView.stopTicking()
        if (visible && parallaxEnabled && !keyguardGoingAway) startParallax() else stopParallax()
        reassertStockClockHide()
    }

    // fade to zero, then drop visibility, done on the keyguard window's own animator clock
    private fun fadeOutLayer(layer: android.view.View) {
        if (layer.tag == TAG_FADING) return
        layer.tag = TAG_FADING
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

    // re-assert the hide on every layout too, stock clock faces bind lazily and race us
    private fun reassertStockClockHide() {
        if (!attached) return
        // keep stock hidden while the petal clock is still dissolving, it ghosts through mid fade
        val hideStock = petalClockVisible ||
            (keyguardGoingAway && clockView.visibility == android.view.View.VISIBLE)
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
        // stock date smartspace + the legacy KeyguardSliceView date bleed through otherwise
        for (dateId in intArrayOf(
            sharedR.id.date_smartspace_view, sharedR.id.date_smartspace_view_large
        )) {
            val date = keyguardRootView.findViewById<android.view.View>(dateId) ?: continue
            val target = if (hideStock) android.view.View.GONE else android.view.View.VISIBLE
            if (date.visibility != target) {
                date.visibility = target
            }
        }
        // no smartspace = AOSP falls back to KeyguardSliceView for the date, hide it too
        val sliceDate = keyguardRootView.findViewById<android.view.View>(R.id.keyguard_slice_view)
        if (sliceDate != null) {
            val sliceTarget = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
            if (sliceDate.visibility != sliceTarget) {
                sliceDate.visibility = sliceTarget
            }
        }
    }

    /** Called by the shade listener plumbing below. */
    fun onShadeExpansionChanged(expanded: Boolean) {
        shadeExpanded = expanded
        updateVisibility()
    }

    // --- parallax ------------------------------------------------------------------------------

    private fun startParallax() {
        if (sensorRegistered) return
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        // Sampling barely matters for a subtle effect; ~15Hz is plenty.
        baselineRoll = null
        baselinePitch = null
        sensorRegistered = sm.registerListener(
            sensorListener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopParallax(resetTilt: Boolean = true) {
        if (sensorRegistered) {
            context.getSystemService(SensorManager::class.java)?.unregisterListener(sensorListener)
        }
        sensorRegistered = false
        baselineRoll = null
        baselinePitch = null
        mainHandler.removeCallbacks(tiltRunnable)
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
        pw.println("  enabled=$enabled generation=$cutoutGeneration shadeExpanded=$shadeExpanded")
        pw.println(
            "  subject=${subjectView.subjectForDump()?.width}x${subjectView.subjectForDump()?.height}" +
                " visibility=${subjectView.visibility}")
    }
}
