/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.petalos;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

// Accent disc gets the sheen, skips stay bare.
public final class PetalMediaBubbleDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    // Reuse the light when the actual circle hasn't moved.
    private final RectF scratch = new RectF();
    private RadialGradient topLight;
    private float topLightSide = -1f;
    private float topLightX;
    private float topLightY;
    private final boolean primary;
    private final boolean dark;
    private final float density;
    private final int accent;
    private float sheen = -1f;

    public PetalMediaBubbleDrawable(boolean primary, boolean dark, float density, int accent) {
        this.primary = primary;
        this.dark = dark;
        this.density = density;
        this.accent = accent;
    }

    public void setSheenProgress(float progress) {
        sheen = progress;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        rect.set(bounds);
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.isEmpty()) return;
        // skip buttons get no plate at all: just the glyph
        if (!primary) return;

        // Wide buttons still get a proper disc.
        float side = Math.min(rect.width(), rect.height());
        float left = rect.centerX() - side / 2f;
        float top = rect.centerY() - side / 2f;
        float radius = side / 2f;
        scratch.set(left, top, left + side, top + side);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(accent);
        canvas.drawRoundRect(scratch, radius, radius, paint);

        // soft top light so it reads as a lit tile
        if (topLight == null || topLightSide != side
                || topLightX != scratch.centerX() || topLightY != scratch.centerY()) {
            topLight = new RadialGradient(scratch.centerX(), scratch.centerY(), side * 0.78f,
                    new int[]{0x30FFFFFF, 0x00000000}, null, Shader.TileMode.CLAMP);
            topLightSide = side;
            topLightX = scratch.centerX();
            topLightY = scratch.centerY();
        }
        paint.setShader(topLight);
        canvas.drawRoundRect(scratch, radius, radius, paint);
        paint.setShader(null);

        // rim
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(0x4Dffffff);
        canvas.drawRoundRect(left + density / 2, top + density / 2,
                left + side - density / 2, top + side - density / 2, radius, radius, paint);

        if (sheen >= 0f && sheen < 1f) {
            float x = left + (side + side) * sheen - side;
            int save = canvas.save();
            canvas.clipRect(scratch);
            paint.setStyle(Paint.Style.FILL);
            // sheen only lives for the 480ms sweep, not worth caching
            paint.setShader(new LinearGradient(x, scratch.centerY(), x + side, scratch.centerY(),
                    new int[]{Color.TRANSPARENT, 0x47ffffff, Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(scratch, radius, radius, paint);
            canvas.restoreToCount(save);
        }
        paint.setShader(null);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
