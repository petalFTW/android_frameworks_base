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

import com.android.systemui.island.presenter.IslandPresenter

// left slot: notifs and calls. right slot: media
enum class Cluster { LEFT, RIGHT }

// how big the island is drawn
enum class Form { CHIP, CAPSULE, EXPANDED }

// tells the router and presenters which binding to use
enum class SignalKind {
    NOTIFICATION,
    CALL_INCOMING,
    CALL_ONGOING,
    CALL_MISSED,
    ALARM,
    MEDIA,
    TORCH,
    CHARGING,
    VOLUME,
    SCREEN_RECORDING,
    HOTSPOT,
    CAST,
    DND,
    NFC,
    EXTRA_KEY,
}

// same id morphs instead of replacing
data class IslandSignal(
    val id: String,
    val kind: SignalKind,
    val cluster: Cluster,
    val priority: Int,
    val initialForm: Form,
    // ttl 0 means stay until the source ends it
    val ttlMs: Long,
    // kind-specific data, usually a status bar notif
    val payload: Any? = null,
) {
    val isSticky: Boolean get() = ttlMs == 0L
}

// builds presenters lazily so they can be dropped
fun interface PresenterFactory {
    fun create(context: android.content.Context, signal: IslandSignal): IslandPresenter
}
