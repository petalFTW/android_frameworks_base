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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Shared petalOS drawing helpers and locked color constants (see the handover spec).
 */
public final class PetalUtils {

    private PetalUtils() {}

    // Body capsule + joint (one fill).
    public static final int COLOR_DIALOG = 0xFF0C0C0E;

    // Volume fill pill states.
    public static final int COLOR_FILL_WHITE = 0xFFFFFFFF;
    public static final int COLOR_FILL_MUTED = 0xFF8E8E93;
    public static final int COLOR_FILL_MAX = 0xFFFF453A;
    public static final int COLOR_GLYPH_DARK = 0xFF161618;
    public static final int COLOR_GLYPH_LIGHT = 0xEBFFFFFF;

    // Power squircle colors (base / neon glow).
    public static final int COLOR_POWER_OFF = 0xFFFF3B3B;
    public static final int COLOR_POWER_OFF_GLOW = 0xFFFF2D2D;
    public static final int COLOR_REBOOT = 0xFF34C759;
    public static final int COLOR_REBOOT_GLOW = 0xFF22E06A;
    public static final int COLOR_SYSUI = 0xFF3B7BFF;
    public static final int COLOR_SYSUI_GLOW = 0xFF4D7BFF;

    // Long-press "gold" confirmation color.
    public static final int COLOR_GOLD = 0xFFFFB300;
    public static final int COLOR_GOLD_GLOW = 0xFFFFC94D;

    public static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Darken/lighten a color by {@code amount} per channel (like the prototype's {@code shade}). */
    public static int shade(int color, int amount) {
        int r = clamp(Color.red(color) + amount, 0, 255);
        int g = clamp(Color.green(color) + amount, 0, 255);
        int b = clamp(Color.blue(color) + amount, 0, 255);
        return Color.rgb(r, g, b);
    }

    /** Linear-blend two colors by {@code t} (0 = {@code a}, 1 = {@code b}). */
    public static int mix(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Copy {@code color} with a new alpha channel. */
    public static int alpha(int color, int a) {
        return Color.argb(clamp(a, 0, 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Fire a predefined haptic effect (click/tick/heavy-click) on the touch vibrator. */
    public static void vibrate(Context context, int effectId) {
        if (context == null) {
            return;
        }
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        vibrator.vibrate(VibrationEffect.get(effectId),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
    }

    /**
     * True when the display rotation is one of the landscape ones. The bezel HUDs draw in a
     * fixed "portrait space" (vertical capsule anchored to the left/right edge); in landscape
     * the physical button rails map to the top/bottom screen edges instead, so the drawing is
     * rotated onto the correct edge (bug: volume/power dialogs sit in the wrong place in
     * landscape).
     */
    public static boolean isLandscapeRotation(int rotation) {
        return rotation == android.view.Surface.ROTATION_90
                || rotation == android.view.Surface.ROTATION_270;
    }

    /**
     * True when the physical rail maps to the screen TOP edge in landscape. With the device
     * rotated counter-clockwise (ROTATION_90) the phone's left rail faces up; rotated clockwise
     * (ROTATION_270) the right rail faces up.
     */
    public static boolean edgeIsTopInLandscape(int rotation, boolean physicalLeftRail) {
        // physicalLeftRail faces up iff the rail side matches the turn direction: left rail is up
        // on a counter-clockwise (ROTATION_90) turn, right rail is up on a clockwise (ROTATION_270)
        // turn. So the rail is at the top when (left rail) != (ROTATION_90).
        return physicalLeftRail != (rotation == android.view.Surface.ROTATION_90);
    }

    /**
     * Angle (degrees, clockwise) to counter-rotate a glyph so it stays screen-upright when the
     * HUD drawing frame is rotated onto the physical rail edge in landscape. 0 in portrait.
     */
    public static float glyphUprightAngle(int rotation, boolean physicalLeftRail) {
        if (!isLandscapeRotation(rotation)) {
            return 0f;
        }
        // Edge-top frame is rotated +90 (glyph top points left), edge-bottom -90 (top points
        // right); counter-rotate by the opposite amount around the glyph centre.
        return edgeIsTopInLandscape(rotation, physicalLeftRail) ? -90f : 90f;
    }

    /**
     * Transforms the canvas from "portrait drawing space" into the current orientation. In
     * landscape the drawing space edges map: drawing x=0 (bezel edge) to the screen top or
     * bottom edge, drawing +x to the inward depth direction.
     */
    public static void applyOrientationTransform(Canvas canvas, int rotation,
            boolean physicalLeftRail, float viewWidth, float viewHeight) {
        if (!isLandscapeRotation(rotation)) {
            return;
        }
        if (edgeIsTopInLandscape(rotation, physicalLeftRail)) {
            canvas.translate(viewWidth, 0f);
            canvas.rotate(90f);
        } else {
            canvas.translate(0f, viewHeight);
            canvas.rotate(-90f);
        }
    }

    /** Inverse of {@link #applyOrientationTransform} for mapping touch points to drawing space. */
    public static float[] invertOrientationTransform(int rotation, boolean physicalLeftRail,
            float viewWidth, float viewHeight, float x, float y) {
        if (!isLandscapeRotation(rotation)) {
            return new float[] {x, y};
        }
        if (edgeIsTopInLandscape(rotation, physicalLeftRail)) {
            return new float[] {y, viewWidth - x};
        }
        return new float[] {viewHeight - y, x};
    }

    /**
     * Builds the capsule body + its deep concave bezel joint as a single filled path.
     *
     * @param left    true for the left bezel (volume), false for the right bezel (power).
     * @param cy      vertical center of the capsule (px).
     * @param w       capsule width (px).
     * @param h       capsule height (px).
     * @param r0      front corner radius (px).
     * @param d       joint curve depth (px).
     * @param xTail   off-screen tail length so the closing edge is flush with the screen (px).
     * @param edgeX   x coordinate of the bezel edge (0 for left, screen width for right).
     */
    public static Path jointPath(boolean left, float cy, float w, float h, float r0, float d,
            float xTail, float edgeX) {
        float r = Math.max(0f, Math.min(r0, Math.min(w * 0.7f, h / 2f)));
        float yT = cy - h / 2f;
        float yB = cy + h / 2f;
        float wr = w - r;
        Path p = new Path();
        if (left) {
            p.moveTo(-xTail, yT - d);
            p.lineTo(0f, yT - d);
            p.quadTo(0f, yT, wr, yT);
            p.quadTo(w, yT, w, yT + r);
            p.lineTo(w, yB - r);
            p.quadTo(w, yB, wr, yB);
            p.quadTo(0f, yB, 0f, yB + d);
            p.lineTo(-xTail, yB + d);
            p.close();
        } else {
            float e = edgeX;
            float l = e - w;
            float lr = e - wr;
            p.moveTo(e + xTail, yT - d);
            p.lineTo(e, yT - d);
            p.quadTo(e, yT, lr, yT);
            p.quadTo(l, yT, l, yT + r);
            p.lineTo(l, yB - r);
            p.quadTo(l, yB, lr, yB);
            p.quadTo(e, yB, e, yB + d);
            p.lineTo(e + xTail, yB + d);
            p.close();
        }
        return p;
    }
}
