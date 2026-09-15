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
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.PathParser;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import com.android.systemui.res.R;

import java.util.List;

// power capsule that springs from the power-key edge
public class PetalPowerMenuView extends View implements Choreographer.FrameCallback {

    // locked-in dp sizes
    private static final float SIZE_SCALE = 1.35f;
    private static final float CW = 40f * SIZE_SCALE;        // base width
    private static final float RAD = 20f * SIZE_SCALE;       // corner radius, container adds 6
    private static final float GLOW = 2f;                    // glow spread
    private static final float JOINT = 34f * SIZE_SCALE;     // how deep the joint curves
    // anchor comes from PetalUiConfig at runtime

    // spring constants
    private static final float SPRING_STIFFNESS = 380f;
    private static final float SPRING_DAMPING = 0.52f;
    private static final long STAGGER_MS = 70L;

    // long press tuning
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

    // button arms, i.e. turns gold
    public interface OnArmListener {
        void onArmChanged(int index, boolean armed);
    }

    private final float mDensity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // read once when the overlay is built
    private final boolean mEdgeLeft;
    private final float mAnchorFraction;

    // rotation drives the canvas transform
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
    private final AccessibilityHelper mAccessibilityHelper;
    private final Paint mFocusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        mAccessibilityHelper = new AccessibilityHelper();
        ViewCompat.setAccessibilityDelegate(this, mAccessibilityHelper);
        setFocusableInTouchMode(true);
        mFocusPaint.setColor(0xFFFFFFFF);
        mFocusPaint.setStyle(Paint.Style.STROKE);
        mFocusPaint.setStrokeWidth(2f * mDensity);
    }

    public void refreshRotation() {
        if (getDisplay() != null) {
            mRotation = getDisplay().getRotation();
        }
        updateHitRects();
        mAccessibilityHelper.invalidateRoot();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // size change means rotation, grab it again
        if (getDisplay() != null) {
            mRotation = getDisplay().getRotation();
        }
        updateHitRects();
        mAccessibilityHelper.invalidateRoot();
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

    // open, stagger the buttons downward
    public void show() {
        mHandler.removeCallbacksAndMessages(null);
        mShowing = true;
        updateHitRects();
        mAccessibilityHelper.invalidateRoot();
        requestFocus();
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

    // close, stagger the buttons back up
    public void dismiss() {
        mHandler.removeCallbacksAndMessages(null);
        mShowing = false;
        mAccessibilityHelper.invalidateRoot();
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
            if (!s.resting()) {
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
        } else {
            mLastFrameNanos = 0L;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mHandler.removeCallbacksAndMessages(null);
        Choreographer.getInstance().removeFrameCallback(this);
        mFrameScheduled = false;
        mLastFrameNanos = 0L;
        super.onDetachedFromWindow();
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
            return; // all the way in, nothing to draw
        }

        boolean landscape = PetalUtils.isLandscapeRotation(mRotation);
        // draw everything as if portrait
        float spaceW = landscape ? getHeight() : getWidth();
        float spaceH = landscape ? getWidth() : getHeight();
        boolean edgeLeft = mEdgeLeft;
        float uprightAngle = PetalUtils.glyphUprightAngle(mRotation);

        int saveOrientation = canvas.save();
        PetalUtils.applyOrientationTransform(canvas, mRotation,
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
        float scX = PetalUtils.lerp(0f, 1f, PetalUtils.clamp(maxo, 0f, 1.15f));
        float scY = PetalUtils.lerp(0.5f, 1f, PetalUtils.clamp(maxo, 0f, 1f));
        float jointDepth = JOINT * mDensity * scX;
        float xTail = (JOINT * 0.5f + 10f) * mDensity * scX;

        // body and joint share one path, flipped on the left
        mBodyPaint.setColor(PetalUtils.COLOR_DIALOG);
        mBodyPaint.setStyle(Paint.Style.FILL);
        mBodyPaint.setAlpha((int) (255f * opacity));
        mBodyPaint.setShadowLayer(24f * mDensity, 0f, 10f * mDensity, 0x6B000000);
        canvas.drawPath(
                PetalUtils.jointPath(edgeLeft, cy, w * scX, h * scY, cRad * scX, jointDepth,
                        xTail, edgeX),
                mBodyPaint);
        mBodyPaint.clearShadowLayer();

        // buttons are in container space, scaled with the body
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

            if (mAccessibilityHelper.getKeyboardFocusedVirtualViewId() == i
                    || mAccessibilityHelper.getAccessibilityFocusedVirtualViewId() == i) {
                canvas.drawRoundRect(mSquircleRect, sqRadius, sqRadius, mFocusPaint);
            }
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
            // undo the rotation to keep glyphs upright
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
        // invert the touch point to match rotated hit rects
        float[] p = PetalUtils.invertOrientationTransform(mRotation,
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
            case MotionEvent.ACTION_CANCEL:
                abortLongPress();
                mPressedIndex = -1;
                mLongPressFired = false;
                return true;
            case MotionEvent.ACTION_UP: {
                boolean fired = mLongPressFired;
                if (mLongPressArmed && mPressedIndex >= 0) {
                    abortLongPress();
                }
                int idx = indexForTap(x, y);
                if (!fired && idx >= 0 && idx == mPressedIndex && mListener != null) {
                    selectOption(idx);
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
            // already armed so a second hold cancels
            mArmedIndex = -1;
            mGoldTarget[idx] = 0f;
            if (mArmListener != null) {
                mArmListener.onArmChanged(idx, false);
            }
        } else {
            // turn it gold and wait for the confirm tap
            if (mArmedIndex >= 0) {
                mGoldTarget[mArmedIndex] = 0f;
            }
            mArmedIndex = idx;
            mGoldTarget[idx] = 1f;
            if (mArmListener != null) {
                mArmListener.onArmChanged(idx, true);
            }
        }
        mAccessibilityHelper.invalidateRoot();
        ensureFrame();
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        return mAccessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP && mDismissListener != null) {
                mDismissListener.onDismissRequested();
            }
            return true;
        }
        return mAccessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        if (mAccessibilityHelper != null) {
            mAccessibilityHelper.onFocusChanged(gainFocus, direction, previous);
        }
    }

    private boolean selectOption(int index) {
        if (!mShowing || mListener == null || index < 0 || index > 2) return false;
        if (index == 2) {
            mListener.onRestartSystemUi();
        } else if (mArmedIndex == index) {
            mArmedIndex = -1;
            mGoldTarget[index] = 0f;
            if (index == 0) mListener.onPowerOffLongPress();
            else mListener.onRebootLongPress();
        } else if (index == 0) {
            mListener.onPowerOff();
        } else {
            mListener.onReboot();
        }
        return true;
    }

    private CharSequence optionLabel(int index) {
        if (index == 2) return getContext().getString(R.string.petal_accessibility_restart_systemui);
        if (mArmedIndex == index) {
            return getContext().getString(index == 0
                    ? R.string.petal_accessibility_bootloader
                    : R.string.petal_accessibility_recovery);
        }
        return getContext().getString(index == 0
                ? com.android.internal.R.string.global_action_power_off
                : com.android.internal.R.string.global_action_restart);
    }

    private void updateHitRects() {
        boolean landscape = PetalUtils.isLandscapeRotation(mRotation);
        float spaceW = landscape ? getHeight() : getWidth();
        float spaceH = landscape ? getWidth() : getHeight();
        float size = CW * 0.92f * mDensity;
        float gap = Math.max(8f * SIZE_SCALE, CW * 0.16f) * mDensity;
        float pad = Math.max(9f * SIZE_SCALE, CW * 0.16f) * mDensity;
        float left = mEdgeLeft ? pad : spaceW - size - pad;
        float top = spaceH * mAnchorFraction - (3f * size + 2f * gap) / 2f;
        for (int i = 0; i < 3; i++) {
            mHitRects[i].set(left, top, left + size, top + size);
            top += size + gap;
        }
    }

    private Rect screenBounds(int index) {
        RectF r = mHitRects[index];
        RectF rotated = new RectF();
        switch (mRotation) {
            case android.view.Surface.ROTATION_90:
                rotated.set(r.top, getHeight() - r.right, r.bottom, getHeight() - r.left);
                break;
            case android.view.Surface.ROTATION_180:
                rotated.set(getWidth() - r.right, getHeight() - r.bottom,
                        getWidth() - r.left, getHeight() - r.top);
                break;
            case android.view.Surface.ROTATION_270:
                rotated.set(getWidth() - r.bottom, r.left, getWidth() - r.top, r.right);
                break;
            default:
                rotated.set(r);
        }
        Rect result = new Rect();
        rotated.roundOut(result);
        return result;
    }

    private final class AccessibilityHelper extends ExploreByTouchHelper {
        AccessibilityHelper() {
            super(PetalPowerMenuView.this);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            if (!mShowing) return INVALID_ID;
            float[] point = PetalUtils.invertOrientationTransform(mRotation,
                    getWidth(), getHeight(), x, y);
            int index = indexForTap(point[0], point[1]);
            return index >= 0 ? index : INVALID_ID;
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> ids) {
            if (mShowing) {
                for (int i = 0; i < 3; i++) ids.add(i);
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int index, AccessibilityNodeInfoCompat node) {
            node.setClassName(android.widget.Button.class.getName());
            node.setContentDescription(optionLabel(index));
            node.setBoundsInParent(screenBounds(index));
            node.setEnabled(mShowing);
            node.setClickable(true);
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
            if (index < 2) {
                node.setLongClickable(true);
                node.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        AccessibilityNodeInfoCompat.ACTION_LONG_CLICK,
                        getContext().getString(mArmedIndex == index
                                ? R.string.petal_accessibility_cancel_advanced
                                : index == 0 ? R.string.petal_accessibility_arm_bootloader
                                        : R.string.petal_accessibility_arm_recovery)));
            }
        }

        @Override
        protected boolean onPerformActionForVirtualView(int index, int action, Bundle args) {
            if (!mShowing || index < 0 || index > 2) return false;
            if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
                return selectOption(index);
            }
            if (action == AccessibilityNodeInfoCompat.ACTION_LONG_CLICK && index < 2) {
                mPressedIndex = index;
                mLongPressArmed = true;
                fireLongPress();
                mPressedIndex = -1;
                mLongPressFired = false;
                return true;
            }
            return false;
        }

        @Override
        protected void onVirtualViewKeyboardFocusChanged(int index, boolean hasFocus) {
            invalidate();
        }
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
