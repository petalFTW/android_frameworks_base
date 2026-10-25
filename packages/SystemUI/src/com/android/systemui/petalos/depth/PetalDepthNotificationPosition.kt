/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos.depth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.petalos.config.DepthNotificationPlacement

// Depth's settings observer publishes; the notification layout collects.
object PetalDepthNotificationPosition {
    data class Placement(val enabled: Boolean, val startPercent: Int)

    private val current = MutableStateFlow(Placement(false, DepthNotificationPlacement.AUTOMATIC))
    val placement = current.asStateFlow()

    fun update(enabled: Boolean, startPercent: Int) {
        current.value = Placement(enabled, DepthNotificationPlacement.clampPercent(startPercent))
    }
}
