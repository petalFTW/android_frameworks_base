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
import android.view.ViewTreeObserver
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
        private var hiddenByIsland = false
        private var hostWasHidden = false

        // progress: 0 = island isn't over this side, 1 = fully covered. Applied every frame
        // from the island's own appearance, so the icons move in lockstep with the blob.
        fun applyProgress(progress: Float) {
            val p = progress.coerceIn(0f, 1f)
            val covered = p > 0.001f
            if (changeVisibility && view.visibility == View.GONE) {
                hiddenByIsland = false
                hostWasHidden = true
                this.covered = covered
                return
            }
            // A fullscreen app owns GONE/INVISIBLE independently of the island.
            // Never force its container visible merely because the blob moved away.
            if (changeVisibility && view.visibility != View.VISIBLE && !hiddenByIsland) {
                hostWasHidden = true
                this.covered = covered
                return
            }
            if (covered && hostWasHidden && changeVisibility) {
                // Status bar just returned from fullscreen: hide on this first frame.
                view.alpha = 0f
                view.visibility = View.INVISIBLE
                this.covered = true
                hiddenByIsland = true
                hostWasHidden = false
                return
            }
            hostWasHidden = false
            this.covered = covered
            if (p >= 1f && changeVisibility) {
                // fully covered: take the host out so it can't peek or eat touches
                view.alpha = 0f
                view.visibility = View.INVISIBLE
                hiddenByIsland = true
                return
            }
            if (changeVisibility && hiddenByIsland && p < 1f) {
                view.visibility = View.VISIBLE
                hiddenByIsland = false
            }
            if (!changeVisibility) {
                hiddenByIsland = false
            }
            view.alpha = 1f - p
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
    private var statusBarView: View? = null
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateCoverage(lastLeft, lastLeftProgress, lastRight, lastRightProgress)
        true
    }

    // kept so we can re-apply it when keyguard goes away
    private var lastLeft: Rect? = null
    private var lastRight: Rect? = null
    private var lastLeftProgress = 0f
    private var lastRightProgress = 0f

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            updateCoverage(lastLeft, lastLeftProgress, lastRight, lastRightProgress)
        }
    }

    override fun onStatusBarViewInitialized(component: HomeStatusBarComponent) {
        val view = component.getPhoneStatusBarView()
        statusBarView = view
        view.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        notificationShadeWindowView.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        startSide = view.findViewById<View?>(R.id.status_bar_start_side_content)?.let {
            SideContainer(it).apply { covered = it.visibility != View.VISIBLE }
        }
        endSide = view.findViewById<View?>(R.id.status_bar_end_side_content)?.let {
            SideContainer(it).apply { covered = it.visibility != View.VISIBLE }
        }
        keyguardStateController.addCallback(keyguardCallback)
    }

    override fun onStatusBarViewDestroyed(component: HomeStatusBarComponent) {
        statusBarView?.viewTreeObserver?.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(preDrawListener)
        notificationShadeWindowView.viewTreeObserver.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(preDrawListener)
        statusBarView = null
        keyguardStateController.removeCallback(keyguardCallback)
        keyguardStartSide?.applyProgress(0f)
        keyguardEndSide?.applyProgress(0f)
        startSide = null
        endSide = null
        keyguardStartSide = null
        keyguardEndSide = null
    }

    // hide a side by the blob's own appearance progress; larger island wins a side
    fun updateCoverage(left: Rect?, leftProgress: Float, right: Rect?, rightProgress: Float) {
        lastLeft = left
        lastRight = right
        lastLeftProgress = leftProgress
        lastRightProgress = rightProgress
        if (keyguardStateController.isShowing) {
            // keyguard has its own bar, only fade it, don't toggle visibility
            val keyguardStart = keyguardSide(R.id.keyguard_carrier_text, keyguardStartSide).also {
                keyguardStartSide = it
            }
            val keyguardEnd = keyguardSide(R.id.status_icon_area, keyguardEndSide).also {
                keyguardEndSide = it
            }
            keyguardStart?.applyProgress(
                progressFor(keyguardStart, left, leftProgress, right, rightProgress))
            keyguardEnd?.applyProgress(
                progressFor(keyguardEnd, left, leftProgress, right, rightProgress))
            return
        }
        keyguardStartSide?.applyProgress(0f)
        keyguardEndSide?.applyProgress(0f)
        val start = startSide ?: return
        val end = endSide ?: return
        start.applyProgress(progressFor(start, left, leftProgress, right, rightProgress))
        end.applyProgress(progressFor(end, left, leftProgress, right, rightProgress))
    }

    private fun progressFor(side: SideContainer, left: Rect?, leftProgress: Float,
            right: Rect?, rightProgress: Float): Float {
        var progress = 0f
        if (side.intersects(left)) progress = maxOf(progress, leftProgress)
        if (side.intersects(right)) progress = maxOf(progress, rightProgress)
        return progress
    }

    private fun keyguardSide(id: Int, current: SideContainer?): SideContainer? {
        val view = notificationShadeWindowView.findViewById<View?>(id) ?: return null
        return if (current?.view === view) current else SideContainer(view, changeVisibility = false)
    }
}
