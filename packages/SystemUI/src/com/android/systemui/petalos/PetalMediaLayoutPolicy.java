/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos;

/** Pixel budgets and gesture opacity, independent of a measured host's current presentation. */
public final class PetalMediaLayoutPolicy {
    private PetalMediaLayoutPolicy() { }

    public static boolean useCompact(boolean currentlyCompact, int notificationHeight,
            int availableHeight, int largePanelHeight, int recoveryMargin) {
        if (notificationHeight <= 0) return false;
        long required = (long) Math.max(0, notificationHeight) + Math.max(0, largePanelHeight);
        if (currentlyCompact) required += Math.max(0, recoveryMargin);
        return required > Math.max(0, availableHeight);
    }

    public static float shadeOpacity(float expansion) {
        return Float.isFinite(expansion) ? Math.max(0f, Math.min(1f, expansion)) : 0f;
    }
}
