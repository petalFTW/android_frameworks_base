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

// hides bar icons under a blob so they don't show through
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

        // always animate to target, a half-faded view can't get stuck
        fun applyCovered(covered: Boolean) {
            this.covered = covered
            // already where we want it, nothing to do
            if (covered && (!changeVisibility || view.visibility == View.INVISIBLE) &&
                view.alpha == 0f) return
            if (!covered && (!changeVisibility || view.visibility == View.VISIBLE) &&
                view.alpha == 1f) return
            // cancel first, or an old end action fires late
            view.animate().cancel()
            if (covered) {
                // stay visible while fading, bounds have to be real
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

        // shorter fade when there's less distance to cover
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

    // kept so we can re-apply it when keyguard goes away
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

    // hide a side if either island covers it
    fun updateCoverage(left: Rect?, right: Rect?) {
        lastLeft = left
        lastRight = right
        if (keyguardStateController.isShowing) {
            // keyguard has its own bar, only fade it, don't toggle visibility
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
