/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PetalMediaLayoutPolicyTest {
    @Test
    public void notificationsAndLargePanelAreCountedOnce() {
        assertFalse(PetalMediaLayoutPolicy.useCompact(false, 200, 800, 500, 24));
        assertTrue(PetalMediaLayoutPolicy.useCompact(false, 350, 800, 500, 24));
    }

    @Test
    public void compactModeNeedsRecoveryRoomBeforeGrowingAgain() {
        assertTrue(PetalMediaLayoutPolicy.useCompact(true, 290, 800, 500, 24));
        assertFalse(PetalMediaLayoutPolicy.useCompact(true, 270, 800, 500, 24));
    }

    @Test
    public void slimReturnsToLargeAsSoonAsLargeFitsAgain() {
        // On-device 2026-10-26: Silent collapsed to 832px; 832 + 1782 fits in 2688.
        assertFalse(PetalMediaLayoutPolicy.useCompact(true, 832, 2688, 1782, 0));
        assertTrue(PetalMediaLayoutPolicy.useCompact(true, 1378, 2688, 1782, 0));
    }

    @Test
    public void noNotificationsDoesNotForceCompactMode() {
        assertFalse(PetalMediaLayoutPolicy.useCompact(true, 0, 100, 500, 24));
    }

    @Test
    public void mediaOpacityUsesTheSameGestureEndpointsAndIntermediateValue() {
        assertEquals(0f, PetalMediaLayoutPolicy.shadeOpacity(0f), 0);
        assertEquals(.5f, PetalMediaLayoutPolicy.shadeOpacity(.5f), 0);
        assertEquals(1f, PetalMediaLayoutPolicy.shadeOpacity(1f), 0);
        assertEquals(0f, PetalMediaLayoutPolicy.shadeOpacity(Float.NaN), 0);
    }
}
