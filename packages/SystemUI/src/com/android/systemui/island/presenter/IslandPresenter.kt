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

// content for one island; animating is the machine's job
interface IslandPresenter {
    // services the presenter asks the host for
    interface Host {
        // window focus, needed for inline reply
        fun setWindowFocusable(focusable: Boolean)

        // block auto-dismiss while something is held
        fun setDismissalHeld(held: Boolean)

        // launch an intent, dismissing keyguard first
        fun launchPendingIntent(pendingIntent: PendingIntent)
    }

    // bind the collapsed view; returns the width it wants
    fun bindCollapsed(container: ViewGroup): Int

    // bind the expanded card
    fun bindExpanded(container: ViewGroup)

    // wanted height, -1 means use the type default
    fun expandedHeightPx(): Int = -1

    // accent tint, null for the theme default
    fun tint(): IslandTint? = null

    // tap on the card; true if handled
    fun onPrimaryAction(): Boolean = false

    // swipe-up dismissal
    fun onDismiss() {}

    // free anything held when the signal goes away
    fun onDestroy() {}

    // callback for tint updates, e.g. new artwork
    fun setTintListener(listener: ((IslandTint?) -> Unit)?) {}

    // attach or detach the host
    fun setHost(host: Host?) {}
}

// tint pulled out of album art
data class IslandTint(
    @ColorInt val accent: Int,
    @ColorInt val onBackground: Int = 0xFFFFFFFF.toInt(),
    @ColorInt val background: Int? = null,
)
