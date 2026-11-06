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

    // Set when the QQS player flips slim <-> large; the stack's next top-padding change then
    // animates over the same 360ms the player morphs in, instead of jumping or lagging.
    @Volatile private var styleFlipPendingUntil = 0L

    @JvmStatic
    fun onPlayerStyleFlip() {
        styleFlipPendingUntil = android.os.SystemClock.uptimeMillis() + FLIP_WINDOW_MS
    }

    /** True once per flip, if the padding change arrives within a frame or two of it. */
    @JvmStatic
    fun consumeStyleFlip(): Boolean {
        val pending = android.os.SystemClock.uptimeMillis() <= styleFlipPendingUntil
        styleFlipPendingUntil = 0L
        return pending
    }

    private const val FLIP_WINDOW_MS = 250L
}
