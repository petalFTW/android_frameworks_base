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

package com.android.systemui.island

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationShadeWindowView
import com.android.systemui.statusbar.core.StatusBarInitializer.StatusBarViewLifecycleListener
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarComponent
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * Hides status bar icons whenever an island blob is visible, preventing icons from ghosting
 * through the glass surface. Both start-side (clock + notification icons) and end-side (system
 * icons) are hidden as a unit: if ANY island is showing, ALL icons are hidden.
 *
 * Tracks the status bar view via [StatusBarViewLifecycleListener] (registered in
 * [com.android.systemui.island.dagger.IslandModule]).
 */
@SysUISingleton
class StatusBarIconHider @Inject constructor(
    private val keyguardStateController: KeyguardStateController,
    private val notificationShadeWindowView: NotificationShadeWindowView,
) : StatusBarViewLifecycleListener {

    private class SideContainer(
        val view: View,
        private val changeVisibility: Boolean = true,
    ) {
        var covered = false

        /**
         * Applies the desired covered state. Always animates the view *toward* the target
         * (alpha 0 when covered, alpha 1 when not), no matter what state a previous animation
         * was interrupted in. The old logic only acted when the view was fully in the opposite
         * state, so cancelling a fade-out mid-flight (island alpha crossing the coverage
         * threshold during a dissolve) left the icons stuck at a partial alpha — or invisible
         * when a stale end-action fired. Cancelling the animator before re-evaluating and then
         * always driving alpha to the target handles every interruption.
         */
        fun applyCovered(covered: Boolean) {
            this.covered = covered
            // Fast paths: nothing to do when the view already sits exactly at the target state.
            if (covered && (!changeVisibility || view.visibility == View.INVISIBLE) &&
                view.alpha == 0f) return
            if (!covered && (!changeVisibility || view.visibility == View.VISIBLE) &&
                view.alpha == 1f) return
            // Always cancel first: a stale withEndAction from a previous fade must not fire
            // after we've re-evaluated (cancel() skips the end action, so no snap risk).
            view.animate().cancel()
            if (covered) {
                // Keep the view VISIBLE during the fade so getBoundsOnScreen stays honest, and
                // only mark it INVISIBLE once the fade genuinely completes.
                if (changeVisibility) view.visibility = View.VISIBLE
                if (view.alpha > 0f) {
                    view.animate()
                        .alpha(0f)
                        .setDuration(fadeDuration(view.alpha))
                        .withEndAction {
                            if (this.covered && changeVisibility) {
                                view.visibility = View.INVISIBLE
                            }
                        }
                        .start()
                } else {
                    if (changeVisibility) view.visibility = View.INVISIBLE
                }
            } else {
                if (changeVisibility && view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                    view.alpha = 0f
                }
                if (view.alpha < 1f) {
                    view.animate()
                        .alpha(1f)
                        .setDuration(fadeDuration(view.alpha))
                        .start()
                }
            }
        }

        /** Scale the fade duration by the remaining distance so short hops aren't slow. */
        private fun fadeDuration(currentAlpha: Float): Long {
            val remaining = if (covered) currentAlpha else 1f - currentAlpha
            return (FADE_MS * remaining.coerceIn(0f, 1f)).toLong().coerceAtLeast(16L)
        }

        fun intersects(island: Rect?): Boolean {
            island ?: return false
            if (view.width <= 0 || view.height <= 0) return false
            val r = Rect()
            view.getBoundsOnScreen(r)
            return Rect.intersects(r, island)
        }
    }

    private var startSide: SideContainer? = null
    private var endSide: SideContainer? = null
    private var keyguardStartSide: SideContainer? = null
    private var keyguardEndSide: SideContainer? = null

    /** Last reported island coverage, re-applied when the keyguard goes away. */
    private var lastLeft: Rect? = null
    private var lastRight: Rect? = null

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            updateCoverage(lastLeft, lastRight)
        }
    }

    override fun onStatusBarViewInitialized(component: HomeStatusBarComponent) {
        val view = component.getPhoneStatusBarView()
        startSide = view.findViewById<View?>(R.id.status_bar_start_side_content)?.let {
            SideContainer(it).apply { covered = it.visibility != View.VISIBLE }
        }
        endSide = view.findViewById<View?>(R.id.status_bar_end_side_content)?.let {
            SideContainer(it).apply { covered = it.visibility != View.VISIBLE }
        }
        keyguardStateController.addCallback(keyguardCallback)
    }

    override fun onStatusBarViewDestroyed(component: HomeStatusBarComponent) {
        keyguardStateController.removeCallback(keyguardCallback)
        keyguardStartSide?.applyCovered(false)
        keyguardEndSide?.applyCovered(false)
        startSide = null
        endSide = null
        keyguardStartSide = null
        keyguardEndSide = null
    }

    /**
     * Update icon hiding from the current island coverage.
     *
     * Each side is hidden if either island's bounds intersect it.
     *
     * @param left screen bounds of the start-cluster island, or null when it isn't visible
     * @param right screen bounds of the end-cluster island, or null when it isn't visible
     */
    fun updateCoverage(left: Rect?, right: Rect?) {
        lastLeft = left
        lastRight = right
        if (keyguardStateController.isShowing) {
            // The lock screen draws a separate status bar. Use alpha-only hiding here so
            // restoring island coverage never overrides the keyguard controller's own
            // visibility decisions (for example, a carrier label that should remain hidden).
            val keyguardStart = keyguardSide(R.id.keyguard_carrier_text, keyguardStartSide).also {
                keyguardStartSide = it
            }
            val keyguardEnd = keyguardSide(R.id.status_icon_area, keyguardEndSide).also {
                keyguardEndSide = it
            }
            keyguardStart?.let {
                it.applyCovered(it.intersects(left) || it.intersects(right))
            }
            keyguardEnd?.let {
                it.applyCovered(it.intersects(left) || it.intersects(right))
            }
            return
        }
        keyguardStartSide?.applyCovered(false)
        keyguardEndSide?.applyCovered(false)
        val start = startSide ?: return
        val end = endSide ?: return
        start.applyCovered(start.intersects(left) || start.intersects(right))
        end.applyCovered(end.intersects(left) || end.intersects(right))
    }

    private fun keyguardSide(id: Int, current: SideContainer?): SideContainer? {
        val view = notificationShadeWindowView.findViewById<View?>(id) ?: return null
        return if (current?.view === view) current else SideContainer(view, changeVisibility = false)
    }

    companion object {
        private const val FADE_MS = 120L
    }
}
