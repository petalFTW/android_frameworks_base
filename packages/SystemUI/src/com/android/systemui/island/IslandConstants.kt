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

/**
 * Non-overridable constants for the liquid-drop dynamic island.
 *
 * Everything here is a density-independent pixel value (converted at use sites with
 * [TypedValue.applyDimension]) or a millisecond duration. Geometric values that may need to be
 * overridden per-device live in `dimens.xml`; see [IslandGeometry].
 */
object IslandConstants {
    // Metaball / liquid-drop
    const val METABALL_K_MAX_DP = 22f
    const val METABALL_BREAK_DISTANCE_DP = 64f
    const val DROPLET_BASE_RADIUS_DP = 14f
    const val DROPLET_EMERGE_RADIUS_DP = 14f
    const val DROPLET_EMERGE_MIN_DP = 10f
    const val DRAG_COMMIT_THRESHOLD_DP = 56f
    const val RUBBER_BAND_FACTOR = 0.55f

    // Timing (ms)
    const val DUR_EMERGE = 420L
    const val DUR_EXPAND = 380L
    const val DUR_COLLAPSE = 260L
    const val DUR_DISSOLVE = 220L
    const val DUR_MORPH = 220L
    const val DUR_CONTENT_OUT = 120L
    const val DUR_CONTENT_IN = 160L
    const val DUR_SPECULAR = 700L
    const val DUR_BULGE_SETTLE = 140L
    const val DUR_NEON = 1500L

    // Dwell / auto-hide (ms)
    const val DWELL_NOTIF = 3000L
    const val DWELL_NOTIF_AUTO_EXPAND = 600L
    const val DWELL_NOTIF_EXPANDED = 3000L
    const val DWELL_NOTIF_COLLAPSED = 2000L
    const val DWELL_CHARGING = 2000L
    const val DWELL_VOLUME = 1500L
    const val MEDIA_PAUSED_AUTOHIDE = 30_000L

    /** How long a finished (100%) progress blob lingers before fading out. */
    const val PROGRESS_DONE_DWELL_MS = 2500L

    // Spring parameters (stiffness, damping ratio)
    const val SPRING_EXPAND_STIFFNESS = 380f
    const val SPRING_EXPAND_DAMPING = 0.78f
    const val SPRING_COLLAPSE_STIFFNESS = 520f
    const val SPRING_COLLAPSE_DAMPING = 0.90f
    const val SPRING_EMERGE_STIFFNESS = 300f
    const val SPRING_EMERGE_DAMPING = 0.68f
    const val SPRING_DRAG_STIFFNESS = 900f
    const val SPRING_DRAG_DAMPING = 1.00f
    const val SPRING_DROP_STIFFNESS = 240f
    const val SPRING_DROP_DAMPING = 0.62f

    // Equalizer
    val BAR_OMEGA = floatArrayOf(5.1f, 6.3f, 4.4f, 7.0f, 5.7f, 6.9f)
    const val BAR_PHASE_SEED = 1.7f

    // Blur
    const val BLUR_BEHIND_RADIUS_DP = 24f
    const val RIM_WIDTH_DP = 1f
}
