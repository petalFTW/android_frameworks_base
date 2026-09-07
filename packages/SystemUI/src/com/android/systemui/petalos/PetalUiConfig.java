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

package com.android.systemui.petalos;

import android.content.Context;
import android.provider.Settings;

import org.petalos.config.PetalConfig;

/**
 * User-tunable petalOS overlay geometry.
 *
 * <p>Values are persisted into Settings.System by the PetalHub app
 * (vendor/petal/apps/PetalHub, {@code org.petalos.config.PetalConfig}).
 * SystemUI reads them here when it builds the volume / power overlays, so a
 * change applies the next time the overlay is shown. Keep key names and value
 * semantics in sync with {@code org.petalos.config.PetalConfig}.
 */
public final class PetalUiConfig {

    public static final String KEY_POWER_EDGE = "petal_ui_power_edge";            // 0=left, 1=right
    public static final String KEY_POWER_ANCHOR_PCT = "petal_ui_power_anchor_pct"; // 0..100
    public static final String KEY_VOLUME_EDGE = "petal_ui_volume_edge";           // 0=left, 1=right
    public static final String KEY_VOLUME_ANCHOR_PCT = "petal_ui_volume_anchor_pct"; // 0..100

    // Locked defaults (handover spec): power key on the right, volume rocker on
    // the left, both anchored to their physical button centres.
    private static final float DEFAULT_POWER_ANCHOR_FRACTION = 205f / 620f;
    private static final float DEFAULT_VOLUME_ANCHOR_FRACTION = 178f / 620f;

    private PetalUiConfig() {}

    // ------------------------------------------------------------------
    // Power / volume overlay placement
    // ------------------------------------------------------------------

    /** True when the power menu springs from the left bezel. */
    public static boolean isPowerEdgeLeft(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_POWER_EDGE, 1) == 0;
    }

    /** Vertical anchor of the power-menu centre, as a fraction of screen height. */
    public static float getPowerAnchorFraction(Context context) {
        return anchorFraction(context, KEY_POWER_ANCHOR_PCT, DEFAULT_POWER_ANCHOR_FRACTION);
    }

    /** True when the volume HUD springs from the left bezel. */
    public static boolean isVolumeEdgeLeft(Context context) {
        return Settings.System.getInt(context.getContentResolver(), KEY_VOLUME_EDGE, 0) == 0;
    }

    /** Vertical anchor of the volume-HUD centre, as a fraction of screen height. */
    public static float getVolumeAnchorFraction(Context context) {
        return anchorFraction(context, KEY_VOLUME_ANCHOR_PCT, DEFAULT_VOLUME_ANCHOR_FRACTION);
    }

    public static boolean isExtraKeyEdgeLeft(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
                PetalConfig.KEY_EXTRA_KEY_EDGE, 0) == 0;
    }

    public static float getExtraKeyAnchorFraction(Context context) {
        return anchorFraction(context, PetalConfig.KEY_EXTRA_KEY_ANCHOR_PCT, 0.25f);
    }

    private static float anchorFraction(Context context, String key, float defaultFraction) {
        int pct = Settings.System.getInt(context.getContentResolver(), key, -1);
        if (pct < 0 || pct > 100) {
            return defaultFraction;
        }
        return pct / 100f;
    }
}
