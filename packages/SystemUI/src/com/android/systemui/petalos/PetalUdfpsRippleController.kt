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

package com.android.systemui.petalos

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.screenshot.ImageCapture
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.PrintWriter
import java.util.concurrent.Executors
import javax.inject.Inject

// fingerprint splash. bends the live lockscreen with a runtime shader, then keeps
// riding a little overlay so the ripple survives the unlock into home
@SysUISingleton
class PetalUdfpsRippleController
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainExecutor: DelayableExecutor,
    private val keyguardRootView: KeyguardRootView,
    private val imageCapture: ImageCapture,
) {

    private val shader: RuntimeShader? =
        try {
            RuntimeShader(RIPPLE_AGSL).apply {
                setFloatUniform("u_density", context.resources.displayMetrics.density)
            }
        } catch (t: Throwable) {
            null
        }

    // the wallpaper lives in another window we cannot sample, so the overlay copy
    // feeds off one grabbed frame instead of live content
    private val overlayShader: RuntimeShader? =
        try {
            RuntimeShader(RIPPLE_AGSL).apply {
                setFloatUniform("u_density", context.resources.displayMetrics.density)
            }
        } catch (t: Throwable) {
            null
        }

    private var rippleOverlay: View? = null
    private var overlayPaint: Paint? = null
    private var grabbed: Bitmap? = null
    private val capture = Executors.newSingleThreadExecutor()

    // one in a hundred unlocks gets a little buzz-tune instead of the usual splash
    private val vibrator: Vibrator? =
        try {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?: context.getSystemService(Vibrator::class.java)
        } catch (t: Throwable) {
            null
        }

    private var animator: ValueAnimator? = null
    private var cx = 0f
    private var cy = 0f
    private val durationMs = 900L

    // finger landed on the sensor, kick the water off
    fun splash(anchor: View) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        splashAt(loc[0] + anchor.width / 2f, loc[1] + anchor.height / 2f)
    }

    // the udfps overlay pilfers the touch so the keyguard icon never sees it,
    // controller pokes us with raw screen coords instead
    fun splashAt(screenX: Float, screenY: Float) {
        if (android.provider.Settings.System.getIntForUser(
                context.contentResolver, "petal_udfps_ripple", 1,
                android.os.UserHandle.USER_CURRENT) != 1) return
        val sh = shader ?: return
        if (animator?.isRunning == true) {
            return
        }
        // shader p is in the root's local pixels, not screen pixels
        val loc = IntArray(2)
        keyguardRootView.getLocationOnScreen(loc)
        cx = screenX - loc[0]
        cy = screenY - loc[1]
        val w = keyguardRootView.width.toFloat()
        val h = keyguardRootView.height.toFloat()
        if (w <= 0f || h <= 0f) {
            return
        }
        primeUniforms(sh, w, h, 0f)
        keyguardRootView.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "u_content"))
        grabFrameForOverlay(w, h)
        maybePlayEasterEgg()
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener { update(it.animatedValue as Float) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            keyguardRootView.setRenderEffect(null)
                            dropOverlay()
                        }
                    }
                )
                start()
            }
    }

    // grab the real frame once (wallpaper included) and paint it back through the
    // shader, but only inside the crest band. everything else stays live, so the
    // unlock transition and the home screen show through around the rippling ring
    private fun grabFrameForOverlay(w: Float, h: Float) {
        if (overlayShader == null) {
            return
        }
        try {
            capture.execute {
                val frame =
                    try {
                        imageCapture.captureDisplay(Display.DEFAULT_DISPLAY, null)
                    } catch (t: Throwable) {
                        null
                    }
                if (frame == null) {
                    return@execute
                }
                mainExecutor.execute { attachOverlay(frame, w, h) }
            }
        } catch (t: Throwable) {
            // no overlay, foreground ripple carries on alone
        }
    }

    private fun attachOverlay(frame: Bitmap, w: Float, h: Float) {
        val osh = overlayShader ?: return
        if (animator?.isRunning != true || rippleOverlay != null) {
            return
        }
        try {
            val wm = context.getSystemService(WindowManager::class.java) ?: return
            osh.setInputShader(
                "u_content", BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            primeUniforms(osh, w, h, 1f)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = osh }
            val v =
                object : View(context) {
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                    }
                }
            val lp =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT)
            lp.setTitle("PetalUdfpsRipple")
            lp.setFitInsetsTypes(0)
            lp.setTrustedOverlay()
            wm.addView(v, lp)
            grabbed = frame
            overlayPaint = paint
            rippleOverlay = v
        } catch (t: Throwable) {
            // window got refused, stay foreground only
            rippleOverlay = null
        }
    }

    private fun dropOverlay() {
        val v = rippleOverlay ?: return
        rippleOverlay = null
        overlayPaint = null
        grabbed = null
        try {
            context.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (t: Throwable) {
            // window already ripped out
        }
    }

    private fun primeUniforms(sh: RuntimeShader, w: Float, h: Float, band: Float) {
        sh.setFloatUniform("u_center", cx, cy)
        sh.setFloatUniform("u_res", w, h)
        sh.setFloatUniform("u_time", 0f)
        sh.setFloatUniform("u_radius", 0f)
        sh.setFloatUniform("u_amp", 0f)
        sh.setFloatUniform("u_alpha", 1f)
        sh.setFloatUniform("u_band", band)
    }

    private fun update(t: Float) {
        val eased = t * t * (3f - 2f * t)
        val fade = (1f - ((t - 0.55f) / 0.45f).coerceIn(0f, 1f))
        val pulse = kotlin.math.sin(Math.PI * t).toFloat()
        val reach = context.resources.displayMetrics.density * 260f
        val amp = context.resources.displayMetrics.density * 11f * pulse
        val time = t * durationMs
        val radius = reach * eased
        // uniform tweaks after setRenderEffect never landed on this box, the
        // effect just kept rendering the values it was born with. wallpaperpicker
        // remakes the effect every frame instead, so thats the game now
        shader?.let {
            frameUniforms(it, time, radius, amp, fade)
            keyguardRootView.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(it, "u_content"))
        }
        overlayShader?.let {
            frameUniforms(it, time, radius, amp, fade)
            rippleOverlay?.invalidate()
        }
        // a view RenderEffect only re-runs on draw, so kick it every frame
        keyguardRootView.invalidate()
    }

    private fun frameUniforms(
        sh: RuntimeShader,
        time: Float,
        radius: Float,
        amp: Float,
        alpha: Float,
    ) {
        sh.setFloatUniform("u_time", time)
        sh.setFloatUniform("u_radius", radius)
        sh.setFloatUniform("u_amp", amp)
        sh.setFloatUniform("u_alpha", alpha)
    }

    // fingers crossed
    private fun maybePlayEasterEgg() {
        if (kotlin.random.Random.nextInt(EASTER_EGG_CHANCE) != 0) {
            return
        }
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) {
            return
        }
        try {
            val effect =
                if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(EASTER_EGG_TIMINGS, EASTER_EGG_AMPS, -1)
                } else {
                    // no amplitude control, keep the rhythm and lose the contour
                    VibrationEffect.createWaveform(EASTER_EGG_TIMINGS, -1)
                }
            vib.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } catch (t: Throwable) {
            // no surprises today
        }
    }

    fun dump(pw: PrintWriter) {
        pw.println("PetalUdfpsRippleController: running=${animator?.isRunning}")
    }

    companion object {
        // 1 in EASTER_EGG_CHANCE fingerprint presses gets a little bonus
        private const val EASTER_EGG_CHANCE = 100
        // sm64 game start: E5 E5 E5 C5 E5 G5, alternating off/on durations in ms
        private val EASTER_EGG_TIMINGS =
            longArrayOf(0, 143, 0, 143, 143, 143, 143, 143, 0, 143, 143, 429)
        // one amp per buzz, higher notes hit harder so the contour carries
        private val EASTER_EGG_AMPS = intArrayOf(225, 225, 225, 200, 225, 255)

        // expanding rings that bend the lockscreen apart, plus a faint crest
        private const val RIPPLE_AGSL =
            """
uniform shader u_content;
uniform vec2 u_center;
uniform vec2 u_res;
uniform float u_time;
uniform float u_radius;
uniform float u_amp;
uniform float u_alpha;
uniform float u_density;
uniform float u_band;
vec4 main(vec2 p) {
    float d = length(p - u_center);
    vec2 dir = d > 0.0001 ? (p - u_center) / d : vec2(0.0);
    float w = u_density * 11.0;
    float gap = 54.0 * u_density;
    float front = 0.0;
    for (float i = 0.0; i < 4.0; i += 1.0) {
        float r = max(u_radius - i * gap, 1.0);
        float x = (d - r) / w;
        front += exp(-x * x) * (1.0 - i * 0.22);
    }
    float disp = front * u_amp * u_alpha;
    float ca = 0.04 + 0.20 * front;
    vec2 sa = clamp(p + dir * disp * (1.0 + ca), vec2(0.0), u_res);
    vec2 sb = clamp(p + dir * disp, vec2(0.0), u_res);
    vec2 sc = clamp(p + dir * disp * (1.0 - ca), vec2(0.0), u_res);
    vec4 a = u_content.eval(sa);
    vec4 b = u_content.eval(sb);
    vec4 c = u_content.eval(sc);
    // soft crest highlight, the displacement does the rest
    float shine = clamp(front, 0.0, 1.0) * u_alpha * 0.09;
    float band = clamp(front * u_alpha, 0.0, 1.0);
    vec4 px = vec4(a.r + shine, b.g + shine, c.b + shine, b.a);
    if (u_band > 0.5) {
        // snapshot copy, only the moving crest is opaque, the rest stays live
        px = vec4((a.r + shine) * band, (b.g + shine) * band, (c.b + shine) * band, band);
    }
    return px;
}
"""
    }
}
