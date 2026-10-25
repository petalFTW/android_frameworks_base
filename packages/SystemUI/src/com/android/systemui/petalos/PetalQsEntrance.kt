/*
 * Copyright (C) 2026 petalOS
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

package com.android.systemui.petalos

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// Finger owns the cascade, not some timer racing ahead of it.
class PetalQsEntranceState {
    var shadeProgress by mutableFloatStateOf(0f)
    var qsProgress by mutableFloatStateOf(0f)
}

val LocalPetalQsEntranceState = staticCompositionLocalOf { PetalQsEntranceState() }
val PetalQsFadeInEasing = CubicBezierEasing(.18f, 0f, .22f, 1f)

// No point dropping this lot before they're visible.
private const val QS_VISIBLE_START = 0.30f

// Leave enough runway for the last tile to land.
private const val TILE_STAGGER = 0.10f
private const val TILE_DURATION = 0.45f
private const val TILE_FALL_DURATION = 0.35f

// swipe-down + fade entrance
private const val QS_FALL_DP = 72f
private const val QS_BASE_SCALE = 0.78f

// Pages sweep sideways; opening still drops straight down.
private const val QS_PAGE_SWEEP_DP = 140f

// qqs strip
private const val QQS_FALL_DP = 18f
private const val QQS_BASE_SCALE = 0.90f

// Keep travel maths separate from the glass reveal.
@Composable
fun Modifier.petalQsTileEntrance(
    index: Int,
    phase: Int = 0,
    mini: Boolean = false,
    pageProgress: (() -> Float)? = null,
    pageSlide: (() -> Float)? = null,
    side: Float = 0f,
    count: Int = 0,
    reveal: PetalQsReveal? = null,
): Modifier {
    val state = LocalPetalQsEntranceState.current
    val preferences = petalQsPreferences()
    return this.graphicsLayer {
        if (preferences.enabled && !preferences.motionEnabled) {
            alpha = 1f
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            return@graphicsLayer
        }
        if (mini) {
            val e = smooth(state.shadeProgress.coerceIn(0f, 1f))
            alpha = reveal?.surfaceAlpha ?: e
            translationY = -QQS_FALL_DP.dp.toPx() * (1f - e)
            val s = QQS_BASE_SCALE + (1f - QQS_BASE_SCALE) * e
            scaleX = s
            scaleY = s
        } else {
            val raw = state.qsProgress.coerceIn(0f, 1f)
            val open = ((raw - QS_VISIBLE_START) / (1f - QS_VISIBLE_START)).coerceIn(0f, 1f)
            val page = pageProgress?.invoke()?.coerceIn(0f, 1f) ?: 1f
            // settle only when both the open and the page motion are done
            val wave = minOf(open, page)
            val pageDriven = pageProgress != null && page < open
            val fall = QS_FALL_DP
            val base = QS_BASE_SCALE
            val p = tileProgress(wave, index, count, falling = !pageDriven)
            val e = smooth(p)
            // fade a touch ahead of the fall so each tile is visible while it drops
            alpha = reveal?.surfaceAlpha ?: smooth((p * 1.5f).coerceIn(0f, 1f))
            if (pageDriven) {
                // Negate the page offset so the tiles trail the swipe.
                val dir = (pageSlide?.invoke() ?: 0f).coerceIn(-1f, 1f)
                translationX = -dir * QS_PAGE_SWEEP_DP.dp.toPx() * (1f - e)
                translationY = 0f
            } else {
                // opening: straight down
                translationX = 0f
                translationY = -fall.dp.toPx() * (1f - e)
            }
            val s = base + (1f - base) * e
            scaleX = s
            scaleY = s
        }
    }
}

private fun smooth(p: Float): Float = p * p * (3f - 2f * p)

// Same bloody stagger for the drop and the glass.
private fun tileProgress(
    progress: Float,
    index: Int,
    count: Int,
    falling: Boolean,
): Float {
    // More tiles shouldn't make these things drop like bloody bullets.
    val duration = if (falling && count > 1) TILE_FALL_DURATION else TILE_DURATION
    val stagger = if (count > 1) (1f - duration) / (count - 1) else TILE_STAGGER
    val delay = (index.coerceAtLeast(0) * stagger).coerceAtMost(1f - duration)
    return ((progress.coerceIn(0f, 1f) - delay) / duration).coerceIn(0f, 1f)
}

class PetalQsReveal internal constructor(
    private val progress: () -> Float,
    private val mini: Boolean,
    private val motionEnabled: () -> Boolean,
) {
    private var previous = Float.NaN
    private var opening = true
    private var anchor = 0f
    private val offsets = FloatArray(3)
    private val values = FloatArray(3)

    private fun target(p: Float, channel: Int): Float {
        val legacy = if (mini) p else (p * 1.5f).coerceIn(0f, 1f)
        if (!opening) return smooth(legacy)
        return when (channel) {
            0 -> smooth((if (mini) p * 1.12f else p * 1.65f).coerceIn(0f, 1f))
            1 -> smooth(legacy)
            else -> smooth(((legacy - .03f) / .97f).coerceIn(0f, 1f))
        }
    }

    private fun sample(): FloatArray {
        if (!motionEnabled()) {
            values.fill(1f)
            previous = Float.NaN
            return values
        }
        val p = progress().coerceIn(0f, 1f)
        if (p == previous) return values
        if (previous.isNaN()) {
            opening = true
            anchor = 0f
            offsets.fill(0f)
        }
        val nextOpening = if (previous.isNaN()) true else p > previous
        if (!previous.isNaN() && nextOpening != opening) {
            opening = nextOpening
            anchor = previous
            // Reverse from what was actually drawn, not a different curve's alpha.
            for (channel in values.indices) offsets[channel] = values[channel] - target(anchor, channel)
        }
        val remaining = if (opening) {
            (1f - p) / (1f - anchor).coerceAtLeast(.001f)
        } else {
            p / anchor.coerceAtLeast(.001f)
        }
        for (channel in values.indices) {
            values[channel] = (target(p, channel) + offsets[channel] * remaining.coerceIn(0f, 1f))
                .coerceIn(0f, 1f)
        }
        values[1] = minOf(values[1], values[0])
        values[2] = minOf(values[2], values[1])
        previous = p
        return values
    }

    val surfaceAlpha: Float get() = sample()[0]
    val contentAlpha: Float get() = sample().let { if (it[0] > 0f) it[1] / it[0] else 0f }
    val detailAlpha: Float get() = sample().let { if (it[1] > 0f) it[2] / it[1] else 0f }
}

@Composable
fun rememberPetalQsReveal(
    index: Int = 0,
    mini: Boolean = false,
    count: Int = 0,
    pageProgress: (() -> Float)? = null,
): PetalQsReveal? {
    val state = LocalPetalQsEntranceState.current
    val preferences = petalQsPreferences()
    val page = rememberUpdatedState(pageProgress)
    val motion = rememberUpdatedState(preferences.motionEnabled)
    val reveal = remember(state, index, mini, count) {
        PetalQsReveal(
            progress = {
                if (mini) state.shadeProgress else {
                    val open = ((state.qsProgress - QS_VISIBLE_START) / (1f - QS_VISIBLE_START))
                        .coerceIn(0f, 1f)
                    val pageProgress = page.value?.invoke()?.coerceIn(0f, 1f) ?: 1f
                    val wave = minOf(open, pageProgress)
                    tileProgress(wave, index, count, falling = pageProgress >= open)
                }
            },
            mini = mini,
            motionEnabled = { motion.value },
        )
    }
    return reveal.takeIf { preferences.enabled }
}

fun Modifier.petalQsContentReveal(reveal: PetalQsReveal?): Modifier =
    if (reveal == null) this else graphicsLayer { alpha = reveal.contentAlpha }

fun Modifier.petalQsDetailReveal(reveal: PetalQsReveal?): Modifier =
    if (reveal == null) this else graphicsLayer { alpha = reveal.detailAlpha }

fun Modifier.petalQsSurfaceReveal(reveal: PetalQsReveal?): Modifier =
    if (reveal == null) this else graphicsLayer { alpha = reveal.surfaceAlpha }
