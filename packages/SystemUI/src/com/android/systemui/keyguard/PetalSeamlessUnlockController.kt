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
import android.view.RemoteAnimationTarget
import android.view.SurfaceControl
import android.view.View
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardViewController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import org.petalos.config.PetalConfig
import javax.inject.Inject

// Fade the lock screen over a fully drawn home wallpaper.
@SysUISingleton
class PetalSeamlessUnlockController
@Inject
constructor(
    @Application private val context: Context,
    private val keyguardViewController: KeyguardViewController,
) {
    private var animator: ValueAnimator? = null
    private var fadingView: View? = null
    private var restoreOnCancel = true
    private var wallpaperProgress = 0f

    fun isEnabled(): Boolean = PetalConfig.isSeamlessUnlockEnabled(context)

    fun isAnimating(): Boolean = animator?.isRunning == true

    fun resetWallpaperProgress() {
        wallpaperProgress = 0f
    }

    fun playCannedAnimation(
        targets: Array<RemoteAnimationTarget>,
        openingWallpapers: Array<RemoteAnimationTarget>?,
        closingWallpapers: Array<RemoteAnimationTarget>?,
        finish: () -> Unit,
    ) {
        cancel()
        val keyguardView: View = keyguardViewController.viewRootImpl.view
        fadingView = keyguardView
        val startingAlpha = keyguardView.alpha
        val startingWallpaperProgress = wallpaperProgress
        SurfaceControl.Transaction().use { transaction ->
            targets.filter { it.leash.isValid }.forEach { target ->
                transaction.setAlpha(target.leash, 1f)
                    .setMatrix(target.leash, 1f, 0f, 0f, 1f)
                    .setPosition(target.leash, target.screenSpaceBounds.left.toFloat(),
                        target.screenSpaceBounds.top.toFloat())
            }
            transaction.apply()
        }
        setWallpaperProgress(startingWallpaperProgress, openingWallpapers, closingWallpapers)
        val nextAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_DURATION_MS
            interpolator = Interpolators.STANDARD_DECELERATE
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                keyguardView.alpha = startingAlpha * (1f - progress)
                setWallpaperProgress(
                    startingWallpaperProgress + (1f - startingWallpaperProgress) * progress,
                    openingWallpapers, closingWallpapers)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (cancelled) {
                        setWallpaperProgress(if (restoreOnCancel) 0f else 1f,
                            openingWallpapers, closingWallpapers)
                    } else {
                        // Keep it faded until the deferred exit finishes.
                        finish()
                    }
                    if (cancelled) {
                        keyguardView.alpha = 1f
                        fadingView = null
                    }
                }
            })
        }
        animator = nextAnimator
        nextAnimator.start()
    }

    fun setWallpaperProgress(
        progress: Float,
        openingWallpapers: Array<RemoteAnimationTarget>?,
        closingWallpapers: Array<RemoteAnimationTarget>?,
    ) {
        wallpaperProgress = progress.coerceIn(0f, 1f)
        val opening = openingWallpapers.orEmpty().filter { it.leash.isValid }
        val closing = closingWallpapers.orEmpty().filter { it.leash.isValid }
        SurfaceControl.Transaction().use { transaction ->
            opening.forEach { transaction.setAlpha(it.leash, 1f) }
            closing.forEach { wallpaper ->
                val home = opening.firstOrNull { !it.leash.isSameSurface(wallpaper.leash) }
                if (home != null) {
                    transaction.setRelativeLayer(wallpaper.leash, home.leash, 1)
                        .setAlpha(wallpaper.leash, 1f - progress.coerceIn(0f, 1f))
                } else {
                    val shared = opening.any { it.leash.isSameSurface(wallpaper.leash) }
                    transaction.setAlpha(wallpaper.leash,
                        if (shared) 1f else 1f - wallpaperProgress)
                }
            }
            transaction.apply()
        }
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
        private const val FADE_DURATION_MS = 360L
    }
}
