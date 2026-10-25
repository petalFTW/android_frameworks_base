/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos.depth

import android.graphics.Canvas
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.android.keyguard.BouncerKeyguardMessageArea
import com.android.systemui.Flags
import com.android.systemui.res.R

// Only the boring prompt gets swapped. Errors still have something useful to say.
internal class PetalBouncerClock(private val view: BouncerKeyguardMessageArea) {
    private var clock: PetalDepthClockView? = null
    private var observer: ViewTreeObserver? = null
    private var tickScheduled = false
    private var lastRendered: Boolean? = null
    private var tallHost = false
    private val tick = Runnable {
        tickScheduled = false
        view.invalidate()
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        updateClock()
        true
    }

    fun attach() {
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
    }

    fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
        releaseClock()
        updateHostSize(false)
    }

    // input clears the prompt, but we sit above it and should stay put
    val isActive: Boolean
        get() = clock != null

    private fun inputViewShowing(): Boolean =
        view.rootView.findViewById<android.view.View>(R.id.lockPatternView)?.isShown == true ||
            view.rootView.findViewById<android.view.View>(R.id.pin_container)?.isShown == true

    private fun updateClock() {
        val promptId = if (Flags.revampedBouncerMessages()) R.id.bouncer_primary_message_area
            else R.id.bouncer_message_area
        // An empty prompt is INVISIBLE until the clock takes ownership. Check its parent
        // instead so it can be acquired again after input or a message reset.
        val hostShowing = !view.isDisabled && (view.parent as? View)?.isShown == true
        val inputsShowing = hostShowing && inputViewShowing()
        val noMessage = TextUtils.isEmpty(view.text) && inputsShowing
        val depthView = if (inputsShowing && view.id == promptId) {
            view.rootView.findViewById<PetalDepthClockView>(R.id.petal_depth_clock)
        } else null
        // Stay tall for the whole bouncer session, not just while attached, so error
        // messages don't make the header collapse and shove the pattern around.
        updateHostSize(depthView?.supportsBouncerClock == true)
        val normalPrompt = view.id == promptId && hostShowing && inputsShowing &&
            view.width > 0 && view.height > 0 &&
            (noMessage || DEFAULT_PROMPTS.any {
                TextUtils.equals(view.text, view.context.getString(it))
            })
        val candidate = if (normalPrompt) depthView?.takeIf { it.supportsBouncerClock } else null
        if (clock !== candidate) {
            Log.d("PetalBouncerClock", "attach=$candidate text=${view.text} id=${view.id} " +
                "shown=${view.isShown} size=${view.width}x${view.height} normal=$normalPrompt old=$clock")
            releaseClock()
            clock = candidate
            view.onDepthClockChanged()
            view.invalidate()
        }
        candidate?.setBouncerHost(view)
    }

    // The stock prompt line is one text line tall; that crams the compact clock into
    // something barely bigger than the prompt text was. TextView.onMeasure ignores
    // minimumHeight, so the layout height itself has to change.
    private fun updateHostSize(wantTall: Boolean) {
        if (wantTall == tallHost) return
        tallHost = wantTall
        val lp = view.layoutParams ?: return
        if (wantTall) {
            lp.height = (HOST_HEIGHT_DP * view.resources.displayMetrics.density).toInt()
            view.gravity = Gravity.CENTER_VERTICAL
        } else {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        view.requestLayout()
        view.invalidate()
    }

    private fun releaseClock() {
        clock?.releaseBouncerHost(view)
        clock = null
        view.removeCallbacks(tick)
        tickScheduled = false
    }

    fun draw(canvas: Canvas): Boolean {
        val activeClock = clock ?: return false
        val rendered = activeClock.drawBouncerClock(canvas, view)
        if (rendered != lastRendered) {
            lastRendered = rendered
            Log.d("PetalBouncerClock", "rendered=$rendered alpha=${view.alpha} " +
                "shown=${view.isShown} p=${activeClock.bouncerStateForDump()}")
        }
        if (!tickScheduled) {
            tickScheduled = true
            val delay = if (activeClock.bouncerAnimationRunning) 16L
                else 1000L - System.currentTimeMillis() % 1000L
            view.postDelayed(tick, delay)
        }
        return rendered
    }

    companion object {
        private const val HOST_HEIGHT_DP = 40

        private val DEFAULT_PROMPTS = intArrayOf(
            R.string.keyguard_enter_your_pin, R.string.keyguard_enter_your_pattern,
            R.string.keyguard_enter_pin, R.string.keyguard_enter_pattern,
            R.string.kg_unlock_with_pin_or_fp, R.string.kg_unlock_with_pattern_or_fp,
        )
    }
}
