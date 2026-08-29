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

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.systemui.island.IslandConstants
import com.android.systemui.island.settings.AnimationMode

/**
 * Owns the spring / tween animations that change the island's size and alpha. In [AnimationMode.CLASSIC]
 * the springs are replaced with [PathInterpolator] [ValueAnimator]s at the same durations; in
 * [AnimationMode.NONE] values are applied instantly.
 */
class IslandAnimator(private val view: View) {

    var mode: AnimationMode = AnimationMode.DYNAMIC

    private val widthProp =
        object : FloatPropertyCompat<View>("islandWidth") {
            override fun getValue(v: View): Float =
                (v.layoutParams?.width ?: 0).toFloat()

            override fun setValue(v: View, value: Float) {
                val lp = v.layoutParams ?: return
                lp.width = value.toInt()
                v.requestLayout()
            }
        }

    private val heightProp =
        object : FloatPropertyCompat<View>("islandHeight") {
            override fun getValue(v: View): Float =
                (v.layoutParams?.height ?: 0).toFloat()

            override fun setValue(v: View, value: Float) {
                val lp = v.layoutParams ?: return
                lp.height = value.toInt()
                v.requestLayout()
            }
        }

    private var springW: SpringAnimation? = null
    private var springH: SpringAnimation? = null
    private var alphaAnimator: ValueAnimator? = null

    /** Invoked when a size animation settles at its final value. */
    var onSettled: (() -> Unit)? = null

    private val onEnd = DynamicAnimation.OnAnimationEndListener { _, _, _, _ ->
        view.requestLayout()
        onSettled?.invoke()
    }

    fun animateSize(targetW: Int, targetH: Int, expanding: Boolean) {
        when (mode) {
            AnimationMode.NONE -> {
                setSize(targetW, targetH)
            }
            AnimationMode.CLASSIC -> {
                animateSizeClassic(targetW, targetH, expanding)
            }
            AnimationMode.DYNAMIC -> {
                animateSizeSpring(targetW, targetH, expanding)
            }
        }
    }

    private fun animateSizeSpring(targetW: Int, targetH: Int, expanding: Boolean) {
        val (stiffness, damping) =
            if (expanding) {
                IslandConstants.SPRING_EXPAND_STIFFNESS to IslandConstants.SPRING_EXPAND_DAMPING
            } else {
                IslandConstants.SPRING_COLLAPSE_STIFFNESS to IslandConstants.SPRING_COLLAPSE_DAMPING
            }

        springW?.cancel()
        springW = SpringAnimation(view, widthProp)
            .setSpring(SpringForce().apply {
                this.stiffness = stiffness
                this.dampingRatio = damping
            })
            .addEndListener(onEnd)
        springW?.spring?.finalPosition = targetW.toFloat()
        springW?.start()

        springH?.cancel()
        springH = SpringAnimation(view, heightProp)
            .setSpring(SpringForce().apply {
                this.stiffness = stiffness
                this.dampingRatio = damping
            })
            .addEndListener(onEnd)
        springH?.spring?.finalPosition = targetH.toFloat()
        springH?.start()
    }

    private fun animateSizeClassic(targetW: Int, targetH: Int, expanding: Boolean) {
        val duration =
            if (expanding) IslandConstants.DUR_EXPAND else IslandConstants.DUR_COLLAPSE
        val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        val startW = view.layoutParams?.width ?: 0
        val startH = view.layoutParams?.height ?: 0

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { a ->
                val f = a.animatedFraction
                setSize(
                    (startW + (targetW - startW) * f).toInt(),
                    (startH + (targetH - startH) * f).toInt(),
                )
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onSettled?.invoke()
                    }
                },
            )
        }
        alphaAnimator?.cancel()
        alphaAnimator = animator
        animator.start()
    }

    fun animateAlpha(target: Float, durationMs: Long = 220L) {
        if (mode == AnimationMode.NONE) {
            view.alpha = target
            return
        }
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(view.alpha, target).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            addUpdateListener { a -> view.alpha = a.animatedValue as Float }
        }
        alphaAnimator?.start()
    }

    fun animateTranslationX(target: Float, durationMs: Long = 220L, onEnd: (() -> Unit)? = null) {
        if (mode == AnimationMode.NONE) {
            view.translationX = target
            onEnd?.invoke()
            return
        }
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(view.translationX, target).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            addUpdateListener { a -> view.translationX = a.animatedValue as Float }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd?.invoke()
                    }
                },
            )
        }
        alphaAnimator?.start()
    }

    fun cancelAll() {
        springW?.cancel()
        springH?.cancel()
        alphaAnimator?.cancel()
    }

    private fun setSize(w: Int, h: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            view.requestLayout()
        }
    }
}
