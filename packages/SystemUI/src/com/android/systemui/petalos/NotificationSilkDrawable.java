/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/** Static satin lighting. Geometry and gradients are rebuilt only when their inputs change. */
public final class NotificationSilkDrawable {
    private final Paint mSurface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final float[] mRadii = new float[8];
    private int mAccent;
    private boolean mDark;
    private boolean mDirty = true;

    public NotificationSilkDrawable(float density) {
        mEdge.setStyle(Paint.Style.STROKE);
        mEdge.setStrokeWidth(density);
    }

    public void draw(Canvas canvas, Rect bounds, float[] radii, int accent, boolean dark,
            int alpha) {
        if (mRect.left != bounds.left || mRect.top != bounds.top
                || mRect.right != bounds.right || mRect.bottom != bounds.bottom
                || mAccent != accent || mDark != dark) {
            mRect.set(bounds);
            mAccent = accent;
            mDark = dark;
            mDirty = true;
        }
        for (int i = 0; i < radii.length; i++) {
            if (mRadii[i] != radii[i]) {
                mRadii[i] = radii[i];
                mDirty = true;
            }
        }
        if (mDirty) {
            mPath.reset();
            mPath.addRoundRect(mRect, mRadii, Path.Direction.CW);
            // Theme-derived accent is deliberately low-alpha, keeping content contrast stable.
            int tint = (accent & 0x00ffffff) | 0x10000000;
            mSurface.setShader(new LinearGradient(mRect.left, mRect.top,
                    mRect.right, mRect.bottom, new int[] {dark ? 0x0cffffff : 0x30ffffff,
                            tint, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
            mEdge.setShader(new LinearGradient(mRect.left, mRect.top,
                    mRect.right, mRect.bottom, new int[] {dark ? 0x50ffffff : 0x90ffffff,
                            0x08ffffff, dark ? 0x12ffffff : 0x10000000},
                    new float[] {0f, .38f, 1f}, Shader.TileMode.CLAMP));
            mDirty = false;
        }
        mSurface.setAlpha(alpha);
        mEdge.setAlpha(alpha);
        canvas.drawPath(mPath, mSurface);
        canvas.drawPath(mPath, mEdge);
    }
}
