/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.widget;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

// The finger stays exact. Only the bit of liquid behind it gets dragged about.
final class LiquidPatternRenderer {
    private static final long SETTLE_MS = 420;
    private final Path mStrand = new Path();
    private final Paint mShine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private float mX;
    private float mY;
    private float mVelocityX;
    private float mVelocityY;
    private long mLastFrame;
    private boolean mMoving;

    LiquidPatternRenderer(float density) {
        mDensity = density;
        mShine.setStyle(Paint.Style.STROKE);
        mShine.setStrokeCap(Paint.Cap.ROUND);
        mShine.setStrokeJoin(Paint.Join.ROUND);
    }

    void reset() {
        mLastFrame = 0;
        mVelocityX = 0f;
        mVelocityY = 0f;
        mMoving = false;
    }

    void update(float x, float y, long now, boolean tracking) {
        if (mLastFrame == 0 || !tracking || !ValueAnimator.areAnimatorsEnabled()) {
            mX = x;
            mY = y;
            mVelocityX = mVelocityY = 0f;
            mMoving = false;
        } else {
            float remaining = Math.min(0.05f, Math.max(0f, (now - mLastFrame) / 1000f));
            // Small steps keep high-refresh and dropped frames from turning this into jelly hell.
            while (remaining > 0f) {
                float dt = Math.min(remaining, 1f / 240f);
                mVelocityX += ((x - mX) * 700f - mVelocityX * 38f) * dt;
                mVelocityY += ((y - mY) * 700f - mVelocityY * 38f) * dt;
                mX += mVelocityX * dt;
                mY += mVelocityY * dt;
                remaining -= dt;
            }
            mMoving = Math.hypot(x - mX, y - mY) > 0.15f * mDensity ||
                    Math.hypot(mVelocityX, mVelocityY) > 2f * mDensity;
            if (!mMoving) {
                mX = x;
                mY = y;
                mVelocityX = mVelocityY = 0f;
            }
        }
        mLastFrame = now;
    }

    boolean draw(Canvas canvas, Paint paint, float startX, float startY,
            float endX, float endY, long age, boolean trailing) {
        float dx = endX - startX;
        float dy = endY - startY;
        float length = (float) Math.hypot(dx, dy);
        if (length < 0.01f) return false;
        boolean animated = ValueAnimator.areAnimatorsEnabled();
        float normalX = -dy / length;
        float normalY = dx / length;
        float limit = Math.min(12f * mDensity, length * 0.12f);
        float bend = 0f;
        if (animated) {
            if (trailing) {
                bend = (mX - endX) * normalX + (mY - endY) * normalY;
            } else if (age < SETTLE_MS) {
                bend = (float) (Math.sin(age / 52f) * Math.exp(-age / 115f)) * limit;
            }
        }
        bend = Math.max(-limit, Math.min(limit, bend));
        mStrand.rewind();
        mStrand.moveTo(startX, startY);
        mStrand.cubicTo(startX + dx * 0.32f + normalX * bend,
                startY + dy * 0.32f + normalY * bend,
                startX + dx * 0.72f + normalX * bend * 0.6f,
                startY + dy * 0.72f + normalY * bend * 0.6f, endX, endY);

        float width = paint.getStrokeWidth();
        float stretch = trailing && animated
                ? Math.min(0.45f, (float) Math.hypot(mVelocityX, mVelocityY) / (3000f * mDensity))
                : 0f;
        paint.setStrokeWidth(width * (1f + stretch));
        canvas.drawPath(mStrand, paint);
        mShine.setColor(Color.WHITE);
        mShine.setAlpha(Math.round(paint.getAlpha() * 0.32f));
        mShine.setStrokeWidth(Math.max(0.5f * mDensity, width * 0.28f));
        canvas.drawPath(mStrand, mShine);
        paint.setStrokeWidth(width);
        return animated && (trailing ? mMoving : age < SETTLE_MS);
    }
}
