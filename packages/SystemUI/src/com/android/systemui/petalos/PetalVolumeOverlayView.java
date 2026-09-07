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
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.PathParser;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * petalOS volume HUD: a near-black capsule that springs out of the physical volume-key edge
 * (left bezel), joined to the bezel by a deep concave fluid fillet (capsule + joint drawn as a
 * single shape). A white fill pill grows/shrinks with the level; a speaker glyph is pinned in the
 * bottom nub. Grey fill + red mute glyph when muted, red fill at max, and a decaying vertical
 * jiggle when a press is attempted past an end.
 *
 * <p>All values are LOCKED to the web prototype (1 dp == 1 prototype unit).
 */
public class PetalVolumeOverlayView extends View implements Choreographer.FrameCallback {

    // ---- locked geometry (dp) ----
    // The prototype was tuned on a tall slim concept screen; on real hardware the
    // locked values read too small, so every dimension is scaled up uniformly.
    // Tweak SIZE_SCALE alone to resize the HUD (proportions stay prototype-exact).
    private static final float SIZE_SCALE = 1.35f;
    static final float CW = 34f * SIZE_SCALE;        // capsule width
    static final float CH = 160f * SIZE_SCALE;       // capsule length
    static final float RAD = 14f * SIZE_SCALE;       // front corner radius
    static final float JOINT = 34f * SIZE_SCALE;     // joint curve depth
    // Max spring overshoot of the open scale (sx) used when sizing the host window.
    private static final float OVERSHOOT = 1.15f;
    // Shadow blur + offset, shake amplitude and safety padding (dp) for the window frame.
    private static final float SHADOW_DP = 34f;
    private static final float SHAKE_DP = 5.5f;
    private static final float PAD_DP = 8f;
    // Default anchor (volume rocker centre) now lives in PetalUiConfig; the user
    // can reposition the edge + start point from PetalSettings > UI. The host window is
    // positioned at the anchor; the view draws relative to its own window centre.

    // ---- locked springs ----
    private static final float SPRING_STIFFNESS = 900f;
    private static final float SPRING_DAMPING = 0.78f;
    private static final float FILL_DAMPING = 0.88f; // emerge damping + 0.10

    private static final long SHAKE_MS = 440L;
    private static final float MAX_DT = 0.05f;

    private final float mDensity;

    // User-tunable placement (read once per overlay instance).
    private final boolean mEdgeLeft;

    /** Current display rotation; the drawing frame is rotated to match (landscape support). */
    private int mRotation = android.view.Surface.ROTATION_0;

    private final PetalSpring mOpen = new PetalSpring(0f);
    private final PetalSpring mFill = new PetalSpring(0f);

    /** Notified when the user taps anywhere on the HUD to dismiss it. */
    public interface OnDismissListener {
        void onDismissRequested();
    }

    /** Notified as the user drags their finger along the capsule to scrub the volume. */
    public interface OnScrubListener {
        void onScrub(float fraction);
        void onScrubEnd();
    }

    private OnDismissListener mDismissListener;
    private OnScrubListener mScrubListener;

    private float mLevel = 0f;   // target level fraction 0..1
    private boolean mMuted = false;
    private boolean mShowing = false;
    private boolean mHasShown = false;
    /** True between ACTION_DOWN and UP/CANCEL while the user is scrubbing the capsule. */
    private boolean mScrubbing = false;

    private boolean mFrameScheduled = false;
    private long mLastFrameNanos = 0L;
    private long mShakeStartNanos = Long.MIN_VALUE / 2;
    private float mShakeDp = 0f;

    private final Paint mBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mSpeakerBody = PathParser.createPathFromPathData(
            "M4 9.5v5h3.2L12 18.5v-13L7.2 9.5H4Z");
    private final Path mSpeakerWaves = PathParser.createPathFromPathData(
            "M15.5 9.2a4 4 0 0 1 0 5.6M17.9 6.8a7.4 7.4 0 0 1 0 10.4");
    private final Path mMuteX = PathParser.createPathFromPathData(
            "M16 9l5 6M21 9l-5 6");

    public PetalVolumeOverlayView(Context context) {
        this(context, null);
    }

    public PetalVolumeOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mEdgeLeft = PetalUiConfig.isVolumeEdgeLeft(context);
        mRotation = getDisplay() != null ? getDisplay().getRotation()
                : android.view.Surface.ROTATION_0;
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Depth (portrait-space width) of the HUD drawing frame, in px. */
    static int hudDepthPx(float density) {
        return Math.round((CW * OVERSHOOT + SHADOW_DP + PAD_DP) * density);
    }

    /** Length (portrait-space height) of the HUD drawing frame, in px. */
    static int hudLengthPx(float density) {
        return Math.round((CH + 2f * JOINT * OVERSHOOT + SHAKE_DP + SHADOW_DP + PAD_DP) * density);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The window resizes when the device rotates; re-read the rotation so the HUD hugs
        // the physical button rail in every orientation.
        if (getDisplay() != null) {
            mRotation = getDisplay().getRotation();
        }
    }

    /** Update the volume level (fraction 0..1) and mute state. */
    public void setVolume(float fraction, boolean muted) {
        mMuted = muted;
        mLevel = PetalUtils.clamp(fraction, 0f, 1f);
        mFill.set(mMuted ? 0f : mLevel);
        ensureFrame();
    }

    public void setOnDismissListener(OnDismissListener listener) {
        mDismissListener = listener;
    }

    public void setOnScrubListener(OnScrubListener listener) {
        mScrubListener = listener;
    }

    /** Spring the capsule out of the bezel edge. */
    public void show() {
        mShowing = true;
        mOpen.set(1f);
        if (!mHasShown) {
            mHasShown = true;
            mFill.snap(mMuted ? 0f : mLevel);
        }
        ensureFrame();
    }

    /** Retract the capsule back into the bezel edge. */
    public void dismiss() {
        mShowing = false;
        mOpen.set(0f);
        ensureFrame();
    }

    /** Trigger the over-limit decaying vertical jiggle. */
    public void shake() {
        mShakeStartNanos = System.nanoTime();
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

        mOpen.step(dt, SPRING_STIFFNESS, SPRING_DAMPING);
        mFill.step(dt, SPRING_STIFFNESS, FILL_DAMPING);

        long elapsedMs = (frameTimeNanos - mShakeStartNanos) / 1_000_000L;
        mShakeDp = 0f;
        if (elapsedMs >= 0 && elapsedMs < SHAKE_MS) {
            mShakeDp = (float) (Math.sin(elapsedMs / 1000.0 * 60.0) * 5.5
                    * (1.0 - elapsedMs / (double) SHAKE_MS));
        }

        invalidate();

        boolean animating = !mOpen.resting() || mOpen.value > 0.002f
                || !mFill.resting()
                || (frameTimeNanos - mShakeStartNanos) < SHAKE_MS * 1_000_000L;
        if (animating) {
            ensureFrame();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mShowing && mOpen.resting() && mOpen.value < 0.002f) {
            return; // fully retracted
        }

        boolean landscape = PetalUtils.isLandscapeRotation(mRotation);
        // Drawing space: in landscape the portrait-space frame is rotated onto the physical
        // button rail's screen edge, so the "width/height" swap below matches the space the
        // capsule is drawn in.
        float spaceW = landscape ? getHeight() : getWidth();
        float spaceH = landscape ? getWidth() : getHeight();
        boolean edgeLeft = landscape || mEdgeLeft;

        int saveOrientation = canvas.save();
        PetalUtils.applyOrientationTransform(canvas, mRotation, mEdgeLeft,
                getWidth(), getHeight());

        float o = PetalUtils.clamp(mOpen.value, 0f, 1.15f);
        float sx = PetalUtils.lerp(0f, 1f, o);
        float sy = PetalUtils.lerp(0.5f, 1f, PetalUtils.clamp(o, 0f, 1f));
        float opacity = PetalUtils.clamp(o * 2.4f, 0f, 1f);

        // The capsule is anchored to the vertical centre of the (sized) host window; the
        // window itself is placed at the configured rocker anchor by PetalOverlayHost.
        float cy = spaceH * 0.5f;
        float w = CW * mDensity;
        float h = CH * mDensity;
        float edgeX = edgeLeft ? 0f : spaceW;
        float capsuleLeft = edgeLeft ? 0f : edgeX - w;
        float rad = Math.min(RAD * mDensity, w / 2f);
        float jointDepth = JOINT * mDensity * sx;
        float xTail = (JOINT * 0.5f + 10f) * mDensity * sx;

        canvas.save();
        canvas.translate(0f, mShakeDp * mDensity);

        // Dark body + joint as a single filled path, with a soft drop shadow.
        mBodyPaint.setColor(PetalUtils.COLOR_DIALOG);
        mBodyPaint.setStyle(Paint.Style.FILL);
        mBodyPaint.setAlpha((int) (255f * opacity));
        mBodyPaint.setShadowLayer(24f * mDensity, 0f, 10f * mDensity, 0x6B000000);
        canvas.drawPath(
                PetalUtils.jointPath(edgeLeft, cy, w * sx, h * sy, rad * sx, jointDepth, xTail,
                        edgeX),
                mBodyPaint);
        mBodyPaint.clearShadowLayer();

        // Fill pill + glyph live in the capsule coordinate space, scaled to match the body.
        int save = canvas.save();
        canvas.scale(sx, sy, edgeX, cy);

        float pad = Math.max(5f * mDensity, w * 0.11f);
        float nub = w - 2f * pad;
        float trackH = h - 2f * pad;
        float level = PetalUtils.clamp(mFill.value, 0f, 1f);
        float fillHeight = nub + level * (trackH - nub);

        boolean muted = mMuted || mLevel <= 1e-6f;
        boolean atMax = !muted && mLevel >= 1f - 1e-6f;

        int fillColor = muted ? PetalUtils.COLOR_FILL_MUTED
                : atMax ? PetalUtils.COLOR_FILL_MAX : PetalUtils.COLOR_FILL_WHITE;
        int glowColor = muted ? 0x338E8E93 : atMax ? 0x55FF453A : 0x2EFFFFFF;

        // Whichever physical rail the HUD occupies, landscape controls should read in normal
        // screen order: speaker/zero on the left and increasing volume toward the right. The
        // bottom-edge transform reverses drawing-space Y, so anchor its content at the opposite
        // end of the otherwise symmetric capsule.
        boolean reverseContent = landscape
                && !PetalUtils.edgeIsTopInLandscape(mRotation, mEdgeLeft);
        float capsuleTop = cy - h / 2f;
        float capsuleBottom = cy + h / 2f;
        float left = capsuleLeft + pad;
        float right = capsuleLeft + w - pad;
        float fillAnchor = reverseContent ? capsuleTop + pad : capsuleBottom - pad;
        float top = reverseContent ? fillAnchor : fillAnchor - fillHeight;
        float bottom = reverseContent ? fillAnchor + fillHeight : fillAnchor;

        mFillPaint.setColor(fillColor);
        mFillPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setAlpha((int) (255f * opacity));
        mFillPaint.setShadowLayer(20f * mDensity, 0f, 0f, glowColor);
        canvas.drawRoundRect(left, top, right, bottom, nub / 2f, nub / 2f, mFillPaint);
        mFillPaint.clearShadowLayer();

        float iconW = w * 0.42f;
        float iconCx = capsuleLeft + w / 2f;
        float iconCy = reverseContent ? fillAnchor + nub / 2f : fillAnchor - nub / 2f;
        boolean covered = fillHeight > pad + iconW * 1.35f;
        int glyphColor = muted ? PetalUtils.COLOR_FILL_MAX
                : covered ? PetalUtils.COLOR_GLYPH_DARK : PetalUtils.COLOR_GLYPH_LIGHT;
        drawSpeaker(canvas, iconCx, iconCy, iconW, glyphColor, muted, opacity,
                PetalUtils.glyphUprightAngle(mRotation, mEdgeLeft));

        canvas.restoreToCount(save);
        canvas.restore();
        canvas.restoreToCount(saveOrientation);
    }

    private void drawSpeaker(Canvas canvas, float cx, float cy, float size, int color,
            boolean muted, float opacity, float uprightAngle) {
        float scale = size / 24f;
        int save = canvas.save();
        canvas.translate(cx - 12f * scale, cy - 12f * scale);
        canvas.scale(scale, scale);
        if (uprightAngle != 0f) {
            // Counter-rotate the glyph so it stays screen-upright when the HUD is rotated onto
            // the physical rail edge in landscape.
            canvas.rotate(uprightAngle, 12f, 12f);
        }

        mGlyphPaint.setColor(color);
        mGlyphPaint.setAlpha((int) (255f * opacity));
        mGlyphPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(mSpeakerBody, mGlyphPaint);

        mGlyphPaint.setStyle(Paint.Style.STROKE);
        mGlyphPaint.setStrokeWidth(1.9f);
        mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(muted ? mMuteX : mSpeakerWaves, mGlyphPaint);

        canvas.restoreToCount(save);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // The host window only covers the HUD frame, so touches here are on the capsule:
        // tap/hold scrubs the volume rather than dismissing the HUD (bug: tapping the volume
        // dialog dismissed it instead of adjusting volume). Dismissal happens through the
        // auto-dismiss timer or ACTION_OUTSIDE when the user taps past the window frame.
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            if (mScrubbing) {
                // petalOS bug fix: the finger left the HUD frame mid-drag. Never dismiss in that
                // case — the stream keeps flowing (the window is a trusted overlay now); just
                // close out the scrub so the auto-dismiss timer is re-armed and the HUD stays
                // fully usable for a fresh drag.
                mScrubbing = false;
                if (mScrubListener != null) {
                    mScrubListener.onScrubEnd();
                }
                return true;
            }
            if (mDismissListener != null) {
                mDismissListener.onDismissRequested();
            }
            return true;
        }
        if (mScrubListener != null) {
            return handleScrub(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && mDismissListener != null) {
            mDismissListener.onDismissRequested();
        }
        return true;
    }

    private boolean handleScrub(MotionEvent event) {
        float fraction = fractionForScreenPoint(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mScrubbing = true;
                mScrubListener.onScrub(fraction);
                return true;
            case MotionEvent.ACTION_MOVE:
                mScrubListener.onScrub(fraction);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mScrubbing = false;
                mScrubListener.onScrub(fraction);
                mScrubListener.onScrubEnd();
                return true;
            default:
                return true;
        }
    }

    /**
     * Maps a raw screen-space touch point to a volume fraction (0 = capsule bottom/nub,
     * 1 = capsule top), inverting the landscape rotation so scrubbing works in any orientation.
     */
    private float fractionForScreenPoint(float x, float y) {
        float[] p = PetalUtils.invertOrientationTransform(mRotation, mEdgeLeft,
                getWidth(), getHeight(), x, y);
        float drawingY = p[1];

        boolean landscape = PetalUtils.isLandscapeRotation(mRotation);
        float spaceH = landscape ? getWidth() : getHeight();
        float cy = spaceH * 0.5f;
        float h = CH * mDensity;
        float pad = Math.max(5f * mDensity, CW * mDensity * 0.11f);
        float trackH = h - 2f * pad;
        if (trackH <= 0f) {
            return 0f;
        }
        boolean reverseContent = landscape
                && !PetalUtils.edgeIsTopInLandscape(mRotation, mEdgeLeft);
        if (reverseContent) {
            float topY = cy - h / 2f + pad;
            return PetalUtils.clamp((drawingY - topY) / trackH, 0f, 1f);
        }
        float bottomY = cy + h / 2f - pad;
        // Bottom of the portrait track is fraction 0, top is fraction 1. In landscape the
        // branch above selects the opposite drawing-space end when needed, yielding screen-LTR.
        return PetalUtils.clamp((bottomY - drawingY) / trackH, 0f, 1f);
    }
}
