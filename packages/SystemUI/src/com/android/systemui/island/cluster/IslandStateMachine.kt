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

import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandConstants
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.NotificationPayload
import com.android.systemui.util.concurrency.DelayableExecutor

enum class IslandState { HIDDEN, EMERGING, COLLAPSED, EXPANDING, EXPANDED, COLLAPSING, DISSOLVING, DRAGGING, MORPHING }

/**
 * One state machine per cluster. Owns the current signal, a depth-3 queue, dwell timers and the
 * priority/transition rules of §6.
 */
class IslandStateMachine(
    val cluster: Cluster,
    private val mainExecutor: DelayableExecutor,
) {
    interface Listener {
        fun onShow(signal: IslandSignal, form: Form)
        fun onMorph(signal: IslandSignal)
        fun onExpand()
        fun onCollapse()
        fun onDismiss()
        fun onFormChange(form: Form)
    }

    var listener: Listener? = null

    var state: IslandState = IslandState.HIDDEN
        private set

    var current: IslandSignal? = null
        private set

    private val queue = ArrayDeque<IslandSignal>()
    private var dwellTimer: Runnable? = null
    private var notifDwellMs = IslandConstants.DWELL_NOTIF

    /**
     * While held (e.g. the user is typing an inline reply), no dwell timer is armed so the
     * blob stays on screen. A user-driven dismiss clears the hold.
     */
    private var dismissalHeld = false

    /** One-shot expiry for transient signals (charging/volume). */
    private var transientDismiss = false

    fun setNotifDwellMs(ms: Long) {
        notifDwellMs = ms
    }

    /** Prevents dwell timers from firing while [held] (e.g. inline reply in progress). */
    fun holdDismissal(held: Boolean) {
        dismissalHeld = held
        if (held) {
            cancelDwell()
        } else {
            current?.let { armDwell(it) }
        }
    }

    /** Deliver a signal. Returns true if it was accepted (shown, morphed or queued). */
    fun accept(signal: IslandSignal): Boolean {
        val cur = current
        if (cur == null) {
            show(signal)
            return true
        }
        // Same signal updating itself -> MORPH.
        if (signal.id == cur.id) {
            current = signal
            listener?.onMorph(signal)
            armDwell(signal)
            return true
        }
        // Let the key speak, then bring the sticky blob back.
        if (signal.kind == SignalKind.EXTRA_KEY && !dismissalHeld) {
            if (cur.isSticky) {
                queue.removeAll { it.id == cur.id }
                if (queue.size >= 3) queue.removeLast()
                queue.addFirst(cur)
            }
            show(signal)
            return true
        }
        if (cur.kind == SignalKind.EXTRA_KEY && signal.isSticky) {
            queue.removeAll { it.id == signal.id }
            if (queue.size >= 3) queue.removeLast()
            queue.addFirst(signal)
            return true
        }
        // A sticky signal is showing and a non-sticky one arrives -> queue it.
        if (cur.isSticky && !signal.isSticky) {
            if (queue.size >= 3) queue.removeFirst()
            queue.addLast(signal)
            return true
        }
        // Higher-priority wins; otherwise queue the loser.
        if (signal.priority >= cur.priority || !cur.isSticky) {
            show(signal)
        } else if (queue.size < 3) {
            queue.addLast(signal)
        }
        return true
    }

    fun remove(id: String): Boolean {
        if (current?.id == id) {
            dismiss()
            return true
        }
        val before = queue.size
        queue.removeAll { it.id == id }
        return queue.size != before
    }

    private fun show(signal: IslandSignal) {
        cancelDwell()
        current = signal
        state = IslandState.EMERGING
        val form = if (signal.initialForm == Form.EXPANDED) Form.EXPANDED else Form.CAPSULE
        listener?.onShow(signal, form)
        armDwell(signal)
    }

    /** User requested expand (tap / swipe down / long press). */
    fun userExpand() {
        if (state != IslandState.COLLAPSED && state != IslandState.EMERGING &&
            state != IslandState.COLLAPSING
        ) {
            return
        }
        cancelDwell()
        state = IslandState.EXPANDING
        listener?.onExpand()
        armDwell(current ?: return)
    }

    /** User requested collapse (tap background / tap outside). */
    fun userCollapse() {
        if (state != IslandState.EXPANDED && state != IslandState.EXPANDING) return
        state = IslandState.COLLAPSING
        listener?.onCollapse()
    }

    /** User dismissed (swipe up). */
    fun userDismiss() {
        dismissalHeld = false
        dismiss()
    }

    fun dismiss() {
        dismissalHeld = false
        cancelDwell()
        state = IslandState.DISSOLVING
        listener?.onDismiss()
        // Pop the next queued signal once this one is gone.
        if (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            current = next
            state = IslandState.EMERGING
            listener?.onShow(next, if (next.initialForm == Form.EXPANDED) Form.EXPANDED else Form.CAPSULE)
            armDwell(next)
        } else {
            current = null
            // petalOS bug fix: nothing ever reports the end of the view's dissolve animation
            // back here, so the machine used to park in DISSOLVING forever (visible as
            // "state=DISSOLVING signal=null" in the SystemUI dump). The machine is done with
            // this signal — the view animates out on its own — so go straight to HIDDEN.
            state = IslandState.HIDDEN
        }
    }

    fun notifyTransitionComplete(targetState: IslandState) {
        state = targetState
    }

    private fun armDwell(signal: IslandSignal) {
        cancelDwell()
        if (dismissalHeld) return
        if (signal.isSticky) return
        when (signal.kind) {
            SignalKind.NOTIFICATION -> {
                val payload = signal.payload as? NotificationPayload
                if (payload != null && (payload.progressMax > 0 || payload.progressIndeterminate)) {
                    if (payload.progressMax > 0 && payload.progressCurrent >= payload.progressMax) {
                        // Finished: give a beat for the 100% pill to read, then fade.
                        dwellTimer = mainExecutor.executeDelayed(
                            { dismiss() },
                            IslandConstants.PROGRESS_DONE_DWELL_MS,
                        )
                    }
                    // In-flight progress: no dwell. The blob persists (collapsed pill) until the
                    // notification is removed, completes, or another blob replaces it.
                    return
                }
                armNotificationDwell(signal)
            }
            SignalKind.CHARGING -> {
                // expanded 2s -> collapsed 2s -> hidden
                transientDismiss = false
                dwellTimer = mainExecutor.executeDelayed({
                    if (state == IslandState.EXPANDED) {
                        state = IslandState.COLLAPSING
                        listener?.onCollapse()
                        transientDismiss = true
                        dwellTimer = mainExecutor.executeDelayed({ dismiss() }, IslandConstants.DWELL_CHARGING)
                    } else {
                        dismiss()
                    }
                }, IslandConstants.DWELL_CHARGING)
            }
            SignalKind.VOLUME -> {
                dwellTimer = mainExecutor.executeDelayed({ dismiss() }, IslandConstants.DWELL_VOLUME)
            }
            else -> {
                // transient non-sticky: dismiss after the signal's ttl
                val ttl = signal.ttlMs.takeIf { it > 0 } ?: notifDwellMs
                dwellTimer = mainExecutor.executeDelayed({ dismiss() }, ttl)
            }
        }
    }

    private fun armNotificationDwell(signal: IslandSignal) {
        // Emerge as the small capsule, then auto-expand, dwell expanded, collapse, dwell, fade.
        dwellTimer = mainExecutor.executeDelayed({
            state = IslandState.EXPANDING
            listener?.onExpand()
            dwellTimer = mainExecutor.executeDelayed({
                state = IslandState.COLLAPSING
                listener?.onCollapse()
                dwellTimer = mainExecutor.executeDelayed(
                    { dismiss() },
                    IslandConstants.DWELL_NOTIF_COLLAPSED,
                )
            }, IslandConstants.DWELL_NOTIF_EXPANDED)
        }, IslandConstants.DWELL_NOTIF_AUTO_EXPAND)
    }

    private fun cancelDwell() {
        dwellTimer?.run()
        dwellTimer = null
        transientDismiss = false
    }

    fun isShowing(): Boolean = current != null && state != IslandState.HIDDEN
}
