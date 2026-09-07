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

package com.android.systemui.island.presenter

import android.app.PendingIntent
import android.view.ViewGroup
import androidx.annotation.ColorInt

/**
 * A presenter owns the content of one island for one event type. It inflates and binds views, and
 * owns its own listeners. [com.android.systemui.island.cluster.IslandView] owns only shape, motion
 * and touch.
 *
 * A presenter MUST NOT start an animation itself; it asks the state machine for a form change
 * instead.
 */
interface IslandPresenter {
    /**
     * Services a presenter needs from the island host: window focus control (inline reply needs
     * the IME), dismissal holds, and keyguard-safe PendingIntent launches.
     */
    interface Host {
        /** Makes the island window focusable so a text editor can take input. */
        fun setWindowFocusable(focusable: Boolean)

        /** Prevents the state machine from auto-dismissing the blob while [held]. */
        fun setDismissalHeld(held: Boolean)

        /**
         * Launches a PendingIntent through the system UI activity starter, dismissing the
         * keyguard if needed (matches what tapping a notification in the shade does).
         */
        fun launchPendingIntent(pendingIntent: PendingIntent)
    }

    /** Inflate/bind the collapsed capsule content. Return the measured content width in px. */
    fun bindCollapsed(container: ViewGroup): Int

    /** Inflate/bind the expanded card content. */
    fun bindExpanded(container: ViewGroup)

    /** Preferred expanded height in px, or -1 for the default for this signal type. */
    fun expandedHeightPx(): Int = -1

    /** Optional tint override; null = use the theme default. */
    fun tint(): IslandTint? = null

    /** Invoked on tap of the card background. Return true if handled. */
    fun onPrimaryAction(): Boolean = false

    /** Invoked on swipe-up dismissal. */
    fun onDismiss() {}

    /** Called on every state change and when the signal is retired. Release everything here. */
    fun onDestroy() {}

    /**
     * Registers a listener invoked whenever the presenter's [tint] changes (e.g. a media track
     * change derives a new accent from the fresh artwork). Pass null to unregister.
     */
    fun setTintListener(listener: ((IslandTint?) -> Unit)?) {}

    /** Injected host services; pass null to detach. */
    fun setHost(host: Host?) {}
}

/** Dynamic tint derived from album artwork. A null [background] means "use the theme default". */
data class IslandTint(
    @ColorInt val accent: Int,
    @ColorInt val onBackground: Int = 0xFFFFFFFF.toInt(),
    @ColorInt val background: Int? = null,
)
