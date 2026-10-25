/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import androidx.compose.runtime.staticCompositionLocalOf

enum class PetalMediaStyle { Stock, LargeProgress, Mini, Lockscreen, Split }

val LocalPetalMediaStyle = staticCompositionLocalOf { PetalMediaStyle.Stock }
