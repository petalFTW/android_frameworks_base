/*
 * Copyright (C) 2026 petalOS
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

package com.android.systemui.keyguard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PointF
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.View
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardViewController
import com.android.systemui.biometrics.domain.interactor.FingerprintPropertyInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import org.petalos.config.PetalConfig
import javax.inject.Inject

// lock fades fast, wallpaper catches up slowly
@SysUISingleton
class PetalSeamlessUnlockController
@Inject
constructor(
    @Application private val context: Context,
    private val keyguardViewController: KeyguardViewController,
    private val fingerprintPropertyInteractor: FingerprintPropertyInteractor,
) {
    private var animator: ValueAnimator? = null
    private var fadingView: View? = null
    private var restoreOnCancel = true
    private var wallpaperProgress = 0f

    // scale from the fingerprint sensor so it feels connected
    private val pivot = PointF(0f, 0f)

    private var lastTargets: Array<RemoteAnimationTarget>? = null
    private var lastOpening: Array<RemoteAnimationTarget>? = null
    private var lastClosing: Array<RemoteAnimationTarget>? = null

    fun isEnabled(): Boolean = PetalConfig.isSeamlessUnlockEnabled(context)

    fun isAnimating(): Boolean = animator?.isRunning == true

    fun resetWallpaperProgress() {
        wallpaperProgress = 0f
    }

    // sensor position, or centre if we don't have one
    private fun resolvePivot() {
        val loc = fingerprintPropertyInteractor.sensorLocation.value
        if (loc.centerX > 0f && loc.centerY > 0f) {
            pivot.set(loc.centerX, loc.centerY)
            return
        }
        val dm = context.resources.displayMetrics
        pivot.set(dm.widthPixels / 2f, dm.heightPixels / 2f)
    }

    // shift by p*(1-s) to keep the pivot fixed
    private fun applySurface(
        transaction: SurfaceControl.Transaction,
        target: RemoteAnimationTarget,
        scale: Float,
        alpha: Float,
    ) {
        if (!target.leash.isValid) return
        val s = scale
        transaction
            .setMatrix(target.leash, s, 0f, 0f, s)
            .setPosition(
                target.leash,
                target.screenSpaceBounds.left + pivot.x * (1f - s),
                target.screenSpaceBounds.top + pivot.y * (1f - s),
            )
            .setAlpha(target.leash, alpha)
    }

    // fast to the peak, slow back down
    private fun zoomCurve(t: Float): Float =
        if (t <= ZOOM_PEAK_T) {
            Interpolators.ACCELERATE.getInterpolation(t / ZOOM_PEAK_T)
        } else {
            1f -
                Interpolators.STANDARD_DECELERATE
                    .getInterpolation((t - ZOOM_PEAK_T) / (1f - ZOOM_PEAK_T))
        }

    private fun applyUnlockTransforms(
        t: Float,
        targets: Array<RemoteAnimationTarget>?,
        opening: Array<RemoteAnimationTarget>?,
        closing: Array<RemoteAnimationTarget>?,
    ) {
        val curve = zoomCurve(t)
        val wScale = 1f + (WALLPAPER_PEAK_SCALE - 1f) * curve
        val lScale = 1f - (1f - LAUNCHER_DIP_SCALE) * curve
        SurfaceControl.Transaction().use { transaction ->
            val openingValid = opening.orEmpty().filter { it.leash.isValid }
            val closingValid = closing.orEmpty().filter { it.leash.isValid }
            openingValid.forEach { applySurface(transaction, it, wScale, 1f) }
            val hasHome = openingValid.any { o ->
                closingValid.any { !it.leash.isSameSurface(o.leash) }
            }
            closingValid.forEach { wallpaper ->
                val shared =
                    openingValid.any { it.leash.isSameSurface(wallpaper.leash) }
                val alpha =
                    if (shared) 1f
                    else if (hasHome) 1f - t
                    else 1f - wallpaperProgress
                applySurface(transaction, wallpaper, wScale, alpha.coerceIn(0f, 1f))
                // put the lock wallpaper above home
                openingValid.firstOrNull { o -> !o.leash.isSameSurface(wallpaper.leash) }
                    ?.let { home ->
                        transaction.setRelativeLayer(wallpaper.leash, home.leash, 1)
                    }
            }
            targets.orEmpty().filter { it.leash.isValid }.forEach {
                applySurface(transaction, it, lScale, 1f)
            }
            transaction.apply()
        }
    }

    // reset all the leashes or things get stuck
    private fun restoreIdentity() {
        val all = listOfNotNull(lastTargets, lastOpening, lastClosing)
            .flatMap { it.toList() }
            .filter { it.leash.isValid }
        SurfaceControl.Transaction().use { transaction ->
            all.forEach { target ->
                transaction
                    .setMatrix(target.leash, 1f, 0f, 0f, 1f)
                    .setPosition(
                        target.leash,
                        target.screenSpaceBounds.left.toFloat(),
                        target.screenSpaceBounds.top.toFloat(),
                    )
                    .setAlpha(target.leash, 1f)
            }
            transaction.apply()
        }
    }

    fun playCannedAnimation(
        targets: Array<RemoteAnimationTarget>,
        openingWallpapers: Array<RemoteAnimationTarget>?,
        closingWallpapers: Array<RemoteAnimationTarget>?,
        finish: () -> Unit,
    ) {
        cancel()
        resolvePivot()
        lastTargets = targets
        lastOpening = openingWallpapers
        lastClosing = closingWallpapers
        val keyguardView: View = keyguardViewController.viewRootImpl.view
        fadingView = keyguardView
        val startingAlpha = keyguardView.alpha
        applyUnlockTransforms(0f, targets, openingWallpapers, closingWallpapers)
        val nextAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MASTER_DURATION_MS
            interpolator = Interpolators.STANDARD_DECELERATE
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                // use the raw fraction so the fade length is exact
                val fadeT =
                    Interpolators.STANDARD_DECELERATE
                        .getInterpolation(
                            (animation.animatedFraction / KEYGUARD_FADE_FRACTION)
                                .coerceIn(0f, 1f))
                keyguardView.alpha = startingAlpha * (1f - fadeT)
                applyUnlockTransforms(t, targets, openingWallpapers, closingWallpapers)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (cancelled) {
                        if (restoreOnCancel) {
                            wallpaperProgress = 0f
                            restoreIdentity()
                        } else {
                            applyUnlockTransforms(
                                1f, targets, openingWallpapers, closingWallpapers)
                        }
                        keyguardView.alpha = 1f
                        fadingView = null
                    } else {
                        // stays here until the exit runs
                        finish()
                    }
                }
            })
        }
        animator = nextAnimator
        nextAnimator.start()
    }

    // called while swiping up
    fun setWallpaperProgress(
        progress: Float,
        openingWallpapers: Array<RemoteAnimationTarget>?,
        closingWallpapers: Array<RemoteAnimationTarget>?,
    ) {
        wallpaperProgress = progress.coerceIn(0f, 1f)
        resolvePivot()
        lastOpening = openingWallpapers
        lastClosing = closingWallpapers
        val t = Interpolators.STANDARD_DECELERATE.getInterpolation(wallpaperProgress)
        applyUnlockTransforms(t, null, openingWallpapers, closingWallpapers)
    }

    fun cancel(showKeyguard: Boolean = true) {
        restoreOnCancel = showKeyguard
        animator?.cancel()
        animator = null
        fadingView?.alpha = 1f
        fadingView = null
        restoreOnCancel = true
    }

    companion object {
        private const val MASTER_DURATION_MS = 480L

        /** lock ui is gone by this point in the animation */
        private const val KEYGUARD_FADE_FRACTION = 0.625f
        private const val ZOOM_PEAK_T = 0.22f
        private const val WALLPAPER_PEAK_SCALE = 1.1f
        private const val LAUNCHER_DIP_SCALE = 0.96f
    }
}
