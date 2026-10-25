/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Count pixels, not just the bloody notification badges.
object PetalMediaSpace {
    private val height = MutableStateFlow(0)
    val notificationHeight = height.asStateFlow()
    private val shadeHeight = MutableStateFlow(0)
    val availableShadeHeight = shadeHeight.asStateFlow()

    @JvmStatic
    fun setNotificationHeight(pixels: Int) {
        height.value = pixels.coerceAtLeast(0)
    }

    @JvmStatic
    fun setAvailableShadeHeight(pixels: Int) {
        shadeHeight.value = pixels.coerceAtLeast(0)
    }
}
