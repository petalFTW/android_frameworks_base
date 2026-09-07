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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.PathParser;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * petalOS power menu: a near-black capsule that springs out of the power-key edge (right bezel),
 * joined to the bezel by the same concave fluid fillet. Inside are three neon squircle buttons
 * (Power-off / Reboot / Reboot SystemUI) that pop in with a top-to-bottom stagger.
 *
 * <p>All values are LOCKED to the web prototype (1 dp == 1 prototype unit).
 */
public class PetalPowerMenuView extends View implements Choreographer.FrameCallback {

    // ---- locked geometry (dp) ----
    // The prototype was tuned on a tall slim concept screen; on real hardware the
    // locked values read too small, so every dimension is scaled up uniformly.
    // Tweak SIZE_SCALE alone to resize the menu (proportions stay prototype-exact).
    private static final float SIZE_SCALE = 1.35f;
    private static final float CW = 40f * SIZE_SCALE;        // reference width
    private static final float RAD = 20f * SIZE_SCALE;       // corner radius (container = rad + 6)
    private static final float GLOW = 2f;                    // neon glow radius
    private static final float JOINT = 34f * SIZE_SCALE;     // joint curve depth
    // Default anchor (power key centre) now lives in PetalUiConfig; the user can
    // reposition the edge + start point from PetalSettings > UI.

    // ---- locked springs ----
    private static final float SPRING_STIFFNESS = 820f;
    private static final float SPRING_DAMPING = 0.60f;
    private static final long STAGGER_MS = 70L;

    // ---- long-press ----
    private static final long LONG_PRESS_TIMEOUT_MS = 450L;
    private static final float POP_STIFFNESS = 900f;
    private static final float POP_DAMPING = 0.30f;
    private static final float POP_SNAP = 0.80f;

    private static final float MAX_DT = 0.05f;

    private static final int[] COLORS = {
            PetalUtils.COLOR_POWER_OFF, PetalUtils.COLOR_REBOOT, PetalUtils.COLOR_SYSUI,
    };
    private static final int[] GLOWS = {
            PetalUtils.COLOR_POWER_OFF_GLOW, PetalUtils.COLOR_REBOOT_GLOW, PetalUtils.COLOR_SYSUI_GLOW,
    };

    public interface OnOptionSelectedListener {
        void onPowerOff();
        void onReboot();
        void onRestartSystemUi();
        void onPowerOffLongPress();
        void onRebootLongPress();
    }

    public interface OnDismissListener {
        void onDismissRequested();
    }

    /** Notified when a button enters or leaves the armed (gold) state. */
    public interface OnArmListener {
        void onArmChanged(int index, boolean armed);
    }

    private final float mDensity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // User-tunable placement (read once per overlay instance).
    private final boolean mEdgeLeft;
    private final float mAnchorFraction;

    /** Current display rotation; the drawing frame is rotated to match (landscape support). */
    private int mRotation = android.view.Surface.ROTATION_0;

    private final PetalSpring[] mSprings = new PetalSpring[3];
    private final PetalSpring[] mPop = new PetalSpring[3];
    private final float[] mGold = new float[3];
    private final float[] mGoldTarget = new float[3];
    private boolean mShowing = false;

    private int mPressedIndex = -1;
    private boolean mLongPressArmed = false;
    private boolean mLongPressFired = false;
    private int mArmedIndex = -1;

    private boolean mFrameScheduled = false;
    private long mLastFrameNanos = 0L;

    private OnOptionSelectedListener mListener;
    private OnDismissListener mDismissListener;
    private OnArmListener mArmListener;

    private final Runnable mLongPressRunnable = this::fireLongPress;

    private final Paint mBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSquirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mSquircleRect = new RectF();
    private final RectF[] mHitRects = new RectF[3];

    private final Path mPowerPath = PathParser.createPathFromPathData(
            "M12 3v9M6.3 6.8a8 8 0 1 0 11.4 0");
    private final Path mRebootPath = PathParser.createPathFromPathData(
            "M19 12a7 7 0 1 1-2.4-5.3M18.5 3.4V7h-3.6");
    private final Path mSysuiArc = PathParser.createPathFromPathData(
            "M15.4 12a3.4 3.4 0 1 1-1-2.4");
    private final Path mSysuiArrow = PathParser.createPathFromPathData(
            "M15.4 8.4V11h-2.6");

    public PetalPowerMenuView(Context context) {
        this(context, null);
    }

    public PetalPowerMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mEdgeLeft = PetalUiConfig.isPowerEdgeLeft(context);
        mAnchorFraction = PetalUiConfig.getPowerAnchorFraction(context);
        mRotation = getDisplay() != null ? getDisplay().getRotation()
                : android.view.Surface.ROTATION_0;
        for (int i = 0; i < 3; i++) {
            mSprings[i] = new PetalSpring(0f);
            mPop[i] = new PetalSpring(1f);
            mHitRects[i] = new RectF();
        }
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The window resizes when the device rotates; re-read the rotation so the menu hugs
        // the physical power-key rail in every orientation.
        if (getDisplay() != null) {
            mRotation = getDisplay().getRotation();
        }
    }

    public void setOnOptionSelectedListener(OnOptionSelectedListener listener) {
        mListener = listener;
    }

    public void setOnDismissListener(OnDismissListener listener) {
        mDismissListener = listener;
    }

    public void setOnArmListener(OnArmListener listener) {
        mArmListener = listener;
    }

    /** Pop the menu out of the bezel, buttons staggering top to bottom. */
    public void show() {
        mHandler.removeCallbacksAndMessages(null);
        mShowing = true;
        mPressedIndex = -1;
        mLongPressArmed = false;
        mLongPressFired = false;
        mArmedIndex = -1;
        for (int i = 0; i < 3; i++) {
            mGold[i] = 0f;
            mGoldTarget[i] = 0f;
            mPop[i].snap(1f);
            final int idx = i;
            mHandler.postDelayed(() -> {
                mSprings[idx].set(1f);
                ensureFrame();
            }, idx * STAGGER_MS);
        }
        ensureFrame();
    }

    /** Retract the menu back into the bezel, buttons staggering bottom to top. */
    public void dismiss() {
        mHandler.removeCallbacksAndMessages(null);
        mShowing = false;
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            mHandler.postDelayed(() -> {
                mSprings[idx].set(0f);
                ensureFrame();
            }, (2 - idx) * 45L);
        }
        ensureFrame();
    }

    private void ensureFrame() {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;

        if (mLastFrameNanos == 0L) {
            mLastFrameNanos = frameTimeNanos;
        }
        float dt = (frameTimeNanos - mLastFrameNanos) / 1_000_000_000f;
        mLastFrameNanos = frameTimeNanos;
        dt = Math.min(dt, MAX_DT);

        for (PetalSpring s : mSprings) {
            s.step(dt, SPRING_STIFFNESS, SPRING_DAMPING);
        }
        for (PetalSpring p : mPop) {
            p.step(dt, POP_STIFFNESS, POP_DAMPING);
        }
        float goldStep = Math.min(1f, dt * 18f);
        for (int i = 0; i < 3; i++) {
            mGold[i] += (mGoldTarget[i] - mGold[i]) * goldStep;
        }

        invalidate();

        boolean anyMoving = false;
        for (PetalSpring s : mSprings) {
            if (!s.resting() || s.value > 0.002f) {
                anyMoving = true;
            }
        }
        for (PetalSpring p : mPop) {
            if (!p.resting()) {
                anyMoving = true;
            }
        }
        for (int i = 0; i < 3; i++) {
            if (Math.abs(mGold[i] - mGoldTarget[i]) > 0.001f) {
                anyMoving = true;
            }
        }
        if (anyMoving) {
            ensureFrame();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float maxo = 0f;
        for (PetalSpring s : mSprings) {
            maxo = Math.max(maxo, s.value);
        }
        boolean settled = true;
        for (PetalSpring s : mSprings) {
            if (!s.resting()) {
                settled = false;
            }
        }
        if (!mShowing && settled && maxo < 0.002f) {
            return; // fully retracted
        }

        boolean landscape = PetalUtils.isLandscapeRotation(mRotation);
        // Drawing space: in landscape the portrait-space frame is rotated onto the physical
        // power-key rail's screen edge.
        float spaceW = landscape ? getHeight() : getWidth();
        float spaceH = landscape ? getWidth() : getHeight();
        boolean edgeLeft = landscape || mEdgeLeft;
        float uprightAngle = PetalUtils.glyphUprightAngle(mRotation, mEdgeLeft);

        int saveOrientation = canvas.save();
        PetalUtils.applyOrientationTransform(canvas, mRotation, mEdgeLeft,
                getWidth(), getHeight());

        float sqSize = CW * 0.92f * mDensity;
        float gap = Math.max(8f * SIZE_SCALE * mDensity, CW * 0.16f * mDensity);
        float padc = Math.max(9f * SIZE_SCALE * mDensity, CW * 0.16f * mDensity);
        float w = sqSize + 2f * padc;
        float h = 3f * sqSize + 2f * gap + 2f * padc;
        float cy = spaceH * mAnchorFraction;
        float edgeX = edgeLeft ? 0f : spaceW;
        float containerLeft = edgeLeft ? 0f : edgeX - w;
        float cRad = (RAD + 6f) * mDensity;
        float sqRadius = RAD * 0.66f * mDensity;

        float opacity = PetalUtils.clamp(maxo * 2f, 0f, 1f);
        float scX = PetalUtils.lerp(0f, 1f, PetalUtils.clamp(maxo, 0f, 1f));
        float scY = PetalUtils.lerp(0.5f, 1f, PetalUtils.clamp(maxo, 0f, 1f));
        float jointDepth = JOINT * mDensity * scX;
        float xTail = (JOINT * 0.5f + 10f) * mDensity * scX;

        // Dark body + joint as one filled path (bezel-anchored, mirrored for the left edge),
        // with a drop shadow.
        mBodyPaint.setColor(PetalUtils.COLOR_DIALOG);
        mBodyPaint.setStyle(Paint.Style.FILL);
        mBodyPaint.setAlpha((int) (255f * opacity));
        mBodyPaint.setShadowLayer(24f * mDensity, 0f, 10f * mDensity, 0x6B000000);
        canvas.drawPath(
                PetalUtils.jointPath(edgeLeft, cy, w * scX, h * scY, cRad * scX, jointDepth,
                        xTail, edgeX),
                mBodyPaint);
        mBodyPaint.clearShadowLayer();

        // Buttons live in the container coordinate space, scaled to match the body.
        int save = canvas.save();
        canvas.scale(scX, scY, edgeX, cy);

        float left = containerLeft + padc;
        for (int i = 0; i < 3; i++) {
            float s = mSprings[i].value;
            float o = PetalUtils.clamp(s, 0f, 1.2f);
            float scale = PetalUtils.lerp(0.3f, 1f, o);
            float tx = PetalUtils.lerp(20f, 0f, o) * mDensity * (edgeLeft ? -1f : 1f);
            float alpha = PetalUtils.clamp(o * 1.6f, 0f, 1f);
            float g = GLOW * mDensity * PetalUtils.clamp(o, 0f, 1f);

            float top = cy - h / 2f + padc + i * (sqSize + gap);
            float sqCx = left + sqSize / 2f;
            float sqCy = top + sqSize / 2f;
            mHitRects[i].set(left, top, left + sqSize, top + sqSize);

            int saveSq = canvas.save();
            canvas.translate(sqCx + tx, sqCy);
            canvas.scale(scale * mPop[i].value, scale * mPop[i].value);

            float half = sqSize / 2f;
            mSquircleRect.set(-half, -half, half, half);

            float gold = PetalUtils.clamp(mGold[i], 0f, 1f);
            int baseColor = PetalUtils.mix(COLORS[i], PetalUtils.COLOR_GOLD, gold);
            int glowColor = PetalUtils.mix(GLOWS[i], PetalUtils.COLOR_GOLD_GLOW, gold);

            float ang = (float) Math.toRadians(160.0);
            float dx = (float) Math.sin(ang);
            float dy = (float) -Math.cos(ang);
            LinearGradient gradient = new LinearGradient(
                    -dx * half, -dy * half, dx * half, dy * half,
                    new int[] { baseColor, PetalUtils.shade(baseColor, -26) },
                    null, Shader.TileMode.CLAMP);

            mSquirclePaint.setShader(gradient);
            mSquirclePaint.setAlpha((int) (255f * alpha));
            mSquirclePaint.setShadowLayer(g * 2.2f, 0f, 0f, PetalUtils.alpha(glowColor, 0x66));
            canvas.drawRoundRect(mSquircleRect, sqRadius, sqRadius, mSquirclePaint);
            mSquirclePaint.clearShadowLayer();
            mSquirclePaint.setShader(null);

            drawGlyph(canvas, i, sqSize * 0.5f, alpha, uprightAngle);

            canvas.restoreToCount(saveSq);
        }

        canvas.restoreToCount(save);
        canvas.restoreToCount(saveOrientation);
    }

    private void drawGlyph(Canvas canvas, int index, float size, float alpha, float uprightAngle) {
        mGlyphPaint.setColor(PetalUtils.alpha(0xFFFFFFFF, (int) (255f * alpha)));
        mGlyphPaint.setStyle(Paint.Style.STROKE);
        mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlyphPaint.setStrokeJoin(Paint.Join.ROUND);

        float scale = size / 24f;
        int save = canvas.save();
        canvas.translate(-12f * scale, -12f * scale);
        canvas.scale(scale, scale);
        if (uprightAngle != 0f) {
            // Counter-rotate the glyph so it stays screen-upright in landscape.
            canvas.rotate(uprightAngle, 12f, 12f);
        }

        switch (index) {
            case 0:
                mGlyphPaint.setStrokeWidth(2.4f);
                canvas.drawPath(mPowerPath, mGlyphPaint);
                break;
            case 1:
                mGlyphPaint.setStrokeWidth(2.4f);
                canvas.drawPath(mRebootPath, mGlyphPaint);
                break;
            case 2:
            default:
                mGlyphPaint.setStrokeWidth(1.8f);
                canvas.drawRoundRect(new RectF(3.4f, 3.4f, 20.6f, 20.6f), 4.6f, 4.6f,
                        mGlyphPaint);
                canvas.drawPath(mSysuiArc, mGlyphPaint);
                canvas.drawPath(mSysuiArrow, mGlyphPaint);
                break;
        }

        canvas.restoreToCount(save);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Touch coordinates are in screen space; hit rects live in (possibly rotated) drawing
        // space, so map the point first (bug: buttons were untappable in landscape).
        float[] p = PetalUtils.invertOrientationTransform(mRotation, mEdgeLeft,
                getWidth(), getHeight(), event.getX(), event.getY());
        float x = p[0];
        float y = p[1];
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int idx = indexForTap(x, y);
                mPressedIndex = idx;
                mLongPressFired = false;
                if (idx == 0 || idx == 1) {
                    mLongPressArmed = true;
                    if (mArmedIndex != idx) {
                        mGoldTarget[idx] = 1f;
                    }
                    mHandler.removeCallbacks(mLongPressRunnable);
                    mHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
                    ensureFrame();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mLongPressArmed && mPressedIndex >= 0 && indexForTap(x, y) != mPressedIndex) {
                    abortLongPress();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean fired = mLongPressFired;
                if (mLongPressArmed && mPressedIndex >= 0) {
                    abortLongPress();
                }
                int idx = indexForTap(x, y);
                if (!fired && idx >= 0 && idx == mPressedIndex && mListener != null) {
                    if (idx == 2) {
                        mListener.onRestartSystemUi();
                    } else if (mArmedIndex == idx) {
                        mGoldTarget[idx] = 0f;
                        mArmedIndex = -1;
                        if (idx == 0) {
                            mListener.onPowerOffLongPress();
                        } else {
                            mListener.onRebootLongPress();
                        }
                    } else {
                        if (idx == 0) {
                            mListener.onPowerOff();
                        } else {
                            mListener.onReboot();
                        }
                    }
                } else if (!fired && idx < 0) {
                    if (mDismissListener != null) {
                        mDismissListener.onDismissRequested();
                    } else {
                        dismiss();
                    }
                }
                mPressedIndex = -1;
                mLongPressFired = false;
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void abortLongPress() {
        if (mLongPressArmed && mPressedIndex >= 0) {
            mHandler.removeCallbacks(mLongPressRunnable);
            mLongPressArmed = false;
            if (mArmedIndex != mPressedIndex) {
                mGoldTarget[mPressedIndex] = 0f;
            }
            ensureFrame();
        }
    }

    private void fireLongPress() {
        if (!mLongPressArmed || mPressedIndex < 0) {
            return;
        }
        mLongPressArmed = false;
        mLongPressFired = true;
        int idx = mPressedIndex;
        mPop[idx].snap(POP_SNAP);
        mPop[idx].set(1f);
        if (mArmedIndex == idx) {
            // Already armed: holding again returns the button to normal.
            mArmedIndex = -1;
            mGoldTarget[idx] = 0f;
            if (mArmListener != null) {
                mArmListener.onArmChanged(idx, false);
            }
        } else {
            // Arm the button (turn gold, wait for a confirming tap).
            if (mArmedIndex >= 0) {
                mGoldTarget[mArmedIndex] = 0f;
            }
            mArmedIndex = idx;
            mGoldTarget[idx] = 1f;
            if (mArmListener != null) {
                mArmListener.onArmChanged(idx, true);
            }
        }
        ensureFrame();
    }

    private int indexForTap(float x, float y) {
        for (int i = 0; i < 3; i++) {
            if (mHitRects[i].contains(x, y)) {
                return i;
            }
        }
        return -1;
    }
}
