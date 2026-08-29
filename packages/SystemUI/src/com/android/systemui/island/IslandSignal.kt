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

/**
 * The two logical anchors of the island. LEFT owns notifications and calls, RIGHT owns media and
 * system events. Physical placement follows START/END so RTL locales mirror correctly.
 */
enum class Cluster { LEFT, RIGHT }

/** The size class an island renders in. */
enum class Form { CHIP, CAPSULE, EXPANDED }

/** Categorises a signal so the router and presenters know how to bind it. */
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
}

/**
 * An immutable input event from the system. Updates to an existing signal are delivered as a new
 * [IslandSignal] with the same [id]; the state machine treats that as a MORPH, not a replace.
 */
data class IslandSignal(
    val id: String,
    val kind: SignalKind,
    val cluster: Cluster,
    val priority: Int,
    val initialForm: Form,
    /** 0 means sticky (stays until the underlying condition ends). */
    val ttlMs: Long,
    /** Type-specific payload, e.g. a [android.service.notification.StatusBarNotification]. */
    val payload: Any? = null,
) {
    val isSticky: Boolean get() = ttlMs == 0L
}

/**
 * Creates a presenter for a signal. Kept as a factory so presenters (which hold media controllers,
 * pending intents, etc.) are created lazily and released deterministically.
 */
fun interface PresenterFactory {
    fun create(context: android.content.Context, signal: IslandSignal): IslandPresenter
}
