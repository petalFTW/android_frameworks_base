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

// one of these per side. holds the current signal, a queue and the dwell timers
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

    // held while the user is typing a reply, no dwell timer runs then
    private var dismissalHeld = false
    private var touchHeld = false
    private var userCollapsed = false

    // flag so a transient only auto-hides once
    private var transientDismiss = false

    fun setNotifDwellMs(ms: Long) {
        notifDwellMs = ms
    }

    // no dwell timers while this is held
    fun holdDismissal(held: Boolean) {
        dismissalHeld = held
        if (held) {
            cancelDwell()
        } else {
            current?.let { armDwell(it) }
        }
    }

    fun holdTouch(held: Boolean) {
        if (touchHeld == held) return
        touchHeld = held
        if (held) {
            cancelDwell()
        } else if (state != IslandState.HIDDEN && state != IslandState.DISSOLVING) {
            current?.let { armDwell(it) }
        }
    }

    // returns true whenever we did something with the signal
    fun accept(signal: IslandSignal): Boolean {
        val cur = current
        if (cur == null) {
            show(signal)
            return true
        }
        // same id means it's an update, morph in place
        if (signal.id == cur.id) {
            current = signal
            val sameMediaSession = signal.kind == SignalKind.MEDIA &&
                signal.payload is android.media.session.MediaController &&
                (signal.payload as? android.media.session.MediaController)?.sessionToken ==
                    (cur.payload as? android.media.session.MediaController)?.sessionToken
            if (!sameMediaSession) listener?.onMorph(signal)
            armDwell(signal)
            return true
        }
        if (cur.id.startsWith("calendar:") && !dismissalHeld) {
            queue.removeAll { it.id == cur.id || it.id == signal.id }
            if (queue.size >= 3) queue.removeLast()
            queue.addFirst(cur)
            show(signal)
            return true
        }
        // let the key chip show, then put the sticky one back on top
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
        // sticky is showing, so the transient waits in line
        if (cur.isSticky && !signal.isSticky) {
            if (queue.size >= 3) queue.removeFirst()
            queue.addLast(signal)
            return true
        }
        // higher priority interrupts, everything else gets queued
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
        userCollapsed = false
        current = signal
        state = IslandState.EMERGING
        val form = if (signal.initialForm == Form.EXPANDED) Form.EXPANDED else Form.CAPSULE
        listener?.onShow(signal, form)
        armDwell(signal)
    }

    // tap, swipe or long press all land here
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

    // tap outside or back gesture
    fun userCollapse() {
        if (state != IslandState.EXPANDED && state != IslandState.EXPANDING) return
        cancelDwell()
        userCollapsed = true
        state = IslandState.COLLAPSING
        listener?.onCollapse()
        current?.let { armDwell(it) }
    }

    // swiped off screen
    fun userDismiss() {
        dismissalHeld = false
        dismiss()
    }

    fun dismiss() {
        dismissalHeld = false
        touchHeld = false
        cancelDwell()
        state = IslandState.DISSOLVING
        listener?.onDismiss()
        // show whatever was waiting behind it
        if (queue.isNotEmpty()) {
            show(queue.removeFirst())
        } else {
            current = null
            // the dissolve never calls back, so just mark it hidden
            state = IslandState.HIDDEN
        }
    }

    fun notifyTransitionComplete(targetState: IslandState) {
        if (current == null) return
        val expected = when (state) {
            IslandState.EMERGING -> targetState == IslandState.EXPANDED ||
                targetState == IslandState.COLLAPSED
            IslandState.EXPANDING -> targetState == IslandState.EXPANDED
            IslandState.COLLAPSING -> targetState == IslandState.COLLAPSED
            else -> false
        }
        if (expected) state = targetState
    }

    private fun armDwell(signal: IslandSignal) {
        cancelDwell()
        if (dismissalHeld || touchHeld) return
        if (signal.isSticky) return
        when (signal.kind) {
            SignalKind.NOTIFICATION -> {
                val payload = signal.payload as? NotificationPayload
                if (payload != null && (payload.progressMax > 0 || payload.progressIndeterminate)) {
                    if (payload.progressMax > 0 && payload.progressCurrent >= payload.progressMax) {
                        // at 100%? hold the pill a moment before it goes
                        dwellTimer = mainExecutor.executeDelayed(
                            { dismiss() },
                            IslandConstants.PROGRESS_DONE_DWELL_MS,
                        )
                    }
                    // still downloading, leave it up
                    return
                }
                armNotificationDwell()
            }
            SignalKind.CHARGING -> {
                // expanded for a bit, then collapsed, then gone
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
                // everything else uses the signal ttl
                val ttl = signal.ttlMs.takeIf { it > 0 } ?: notifDwellMs
                dwellTimer = mainExecutor.executeDelayed({ dismiss() }, ttl)
            }
        }
    }

    private fun armNotificationDwell() {
        // It's already open, don't replay the damn expand timer.
        if (state == IslandState.EXPANDED || state == IslandState.EXPANDING) {
            armNotificationCollapse()
            return
        }
        if (userCollapsed) {
            dwellTimer = mainExecutor.executeDelayed(
                { dismiss() },
                IslandConstants.DWELL_NOTIF_COLLAPSED,
            )
            return
        }
        dwellTimer = mainExecutor.executeDelayed({
            state = IslandState.EXPANDING
            listener?.onExpand()
            armNotificationCollapse()
        }, IslandConstants.DWELL_NOTIF_AUTO_EXPAND)
    }

    private fun armNotificationCollapse() {
        dwellTimer = mainExecutor.executeDelayed({
            state = IslandState.COLLAPSING
            listener?.onCollapse()
            dwellTimer = mainExecutor.executeDelayed(
                { dismiss() },
                IslandConstants.DWELL_NOTIF_COLLAPSED,
            )
        }, IslandConstants.DWELL_NOTIF_EXPANDED)
    }

    private fun cancelDwell() {
        dwellTimer?.run()
        dwellTimer = null
        transientDismiss = false
    }

    fun isShowing(): Boolean = current != null && state != IslandState.HIDDEN
}
