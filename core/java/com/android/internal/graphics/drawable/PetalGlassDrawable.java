/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.graphics.drawable;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;

import java.util.function.Consumer;

/** @hide */
public final class PetalGlassDrawable extends GradientDrawable
        implements View.OnAttachStateChangeListener {
    private final View mView;
    private final WindowManager mWindowManager;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final float[] mRadii = new float[8];
    private final Consumer<Boolean> mBlurListener = enabled -> invalidateView();
    private RuntimeShader mShader;
    private boolean mBackdropEnabled;
    private LinearGradient mSheen;
    private int mAccent;
    private final float[] mRequestedRadii = new float[8];
    private BackgroundBlurDrawable mBackdrop;
    private final float mInset;
    private boolean mGeometryDirty = true;
    private int mShaderColor;
    private float mDensity;

    public PetalGlassDrawable(View view, float radiusDp, float insetDp) {
        this(view, radiusDp, insetDp, true);
    }

    public PetalGlassDrawable(View view, float radiusDp, float insetDp, boolean backdropEnabled) {
        mView = view;
        mWindowManager = view.getContext().getSystemService(WindowManager.class);
        mDensity = view.getResources().getDisplayMetrics().density;
        mInset = insetDp * mDensity;
        setGlassCorners(radiusDp * mDensity, radiusDp * mDensity);
        boolean dark = (view.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setColor(dark ? 0xA8101012 : 0xC2F3F3F6);
        mBackdropEnabled = backdropEnabled;
        if (backdropEnabled) createShader();
        view.addOnAttachStateChangeListener(this);
        if (view.isAttachedToWindow()) onViewAttachedToWindow(view);
    }

    private void createShader() {
        if (mShader != null) return;
        try {
            mShader = new RuntimeShader(SLAB);
        } catch (IllegalArgumentException e) {
            mShader = null;
        }
        mGeometryDirty = true;
    }

    public void setBackdropEnabled(boolean enabled) {
        if (mBackdropEnabled == enabled) return;
        mBackdropEnabled = enabled;
        if (enabled) {
            createShader();
            if (mView.isAttachedToWindow()) onViewAttachedToWindow(mView);
        } else {
            onViewDetachedFromWindow(mView);
        }
        mGeometryDirty = true;
        invalidateView();
    }

    public void setGlassCorners(float edge, float far) {
        edge = Math.max(0f, edge);
        far = Math.max(0f, far);
        if (mRequestedRadii[0] == edge && mRequestedRadii[2] == far
                && mRequestedRadii[4] == far && mRequestedRadii[6] == edge) return;
        mRequestedRadii[0] = mRequestedRadii[1] = mRequestedRadii[6] = mRequestedRadii[7] = edge;
        mRequestedRadii[2] = mRequestedRadii[3] = mRequestedRadii[4] = mRequestedRadii[5] = far;
        super.setCornerRadii(mRequestedRadii);
        mGeometryDirty = true;
    }

    @Override public void setCornerRadii(float[] radii) {
        super.setCornerRadii(radii);
        for (int i = 0; i < 8; i++) {
            mRequestedRadii[i] = radii == null ? 0f : Math.max(0f, radii[i]);
        }
        mGeometryDirty = true;
    }

    @Override public void setCornerRadius(float radius) {
        super.setCornerRadius(radius);
        java.util.Arrays.fill(mRequestedRadii, Math.max(0f, radius));
        mGeometryDirty = true;
    }

    private void invalidateView() { mView.invalidate(); }

    public static void applyToWindow(Window window) {
        View decor = window.getDecorView();
        Rect padding = new Rect();
        if (decor.getBackground() != null) decor.getBackground().getPadding(padding);
        android.content.res.TypedArray attrs = decor.getContext().obtainStyledAttributes(
                new int[]{android.R.attr.dialogCornerRadius});
        float density = decor.getResources().getDisplayMetrics().density;
        float radius = attrs.getDimension(0, 28f * density);
        attrs.recycle();
        PetalGlassDrawable glass = new PetalGlassDrawable(decor, radius / density, 0f);
        // keep the old gutters or the popup jumps sideways
        window.setBackgroundDrawable(new InsetDrawable(glass,
                padding.left, padding.top, padding.right, padding.bottom));
    }

    @Override public void onViewAttachedToWindow(View view) {
        if (!mBackdropEnabled || mBackdrop != null || view.getViewRootImpl() == null) return;
        mBackdrop = view.getViewRootImpl().createBackgroundBlurDrawable();
        mWindowManager.addCrossWindowBlurEnabledListener(view.getContext().getMainExecutor(),
                mBlurListener);
        view.invalidate();
    }

    @Override public void onViewDetachedFromWindow(View view) {
        if (mBackdrop == null) return;
        mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        mBackdrop.setVisible(false, false);
        mBackdrop.setBlurRadius(0);
        mBackdrop.setRefraction(0f, 0f, 0f);
        mBackdrop = null;
    }

    @Override protected void onBoundsChange(android.graphics.Rect bounds) {
        super.onBoundsChange(bounds);
        mGeometryDirty = true;
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override public void draw(Canvas canvas) {
        if (!isVisible() || getAlpha() == 0 || getBounds().isEmpty()) {
            if (mBackdrop != null) mBackdrop.setVisible(false, false);
            return;
        }
        float density = mView.getResources().getDisplayMetrics().density;
        if (mDensity != density) {
            mDensity = density;
            mGeometryDirty = true;
        }
        if (mGeometryDirty) {
            mRect.set(getBounds());
            mRect.inset(mInset, mInset);
            float limit = Math.max(0f, Math.min(mRect.width(), mRect.height()) / 2f);
            for (int i = 0; i < 8; i++) mRadii[i] = Math.min(mRequestedRadii[i], limit);
            mPath.reset();
            mPath.addRoundRect(mRect, mRadii, Path.Direction.CW);
            if (!mBackdropEnabled) {
                mAccent = mView.getContext().getColor(android.R.color.system_accent1_500);
                int softAccent = mView.getContext().getColor(android.R.color.system_accent1_300);
                boolean dark = (mView.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                mSheen = new LinearGradient(mRect.left, mRect.top, mRect.left, mRect.bottom,
                        new int[]{(softAccent & 0xFFFFFF) | (dark ? 0x26000000 : 0x1C000000),
                                Color.TRANSPARENT,
                                (mAccent & 0xFFFFFF) | (dark ? 0x1F000000 : 0x17000000)},
                        new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP);
            }
            if (mShader != null) {
                mShader.setFloatUniform("center", mRect.centerX(), mRect.centerY());
                mShader.setFloatUniform("halfSize", mRect.width() / 2f, mRect.height() / 2f);
                mShader.setFloatUniform("radii", mRadii[0], mRadii[2], mRadii[6], mRadii[4]);
                mShader.setFloatUniform("bandWidth", Math.max(.001f,
                        Math.min(Math.min(mRect.width(), mRect.height()) * .2f, 18f * density)));
            }
            mGeometryDirty = false;
        }
        boolean blur = mBackdrop != null && canvas.isHardwareAccelerated()
                && mWindowManager.isCrossWindowBlurEnabled() && !mRect.isEmpty();
        if (mBackdrop != null) {
            mBackdrop.setVisible(blur, false);
            if (blur) {
                int left = Math.round(mRect.left);
                int top = Math.round(mRect.top);
                int right = Math.round(mRect.right);
                int bottom = Math.round(mRect.bottom);
                // bounds don't need poking every damn frame
                Rect old = mBackdrop.getBounds();
                if (old.left != left || old.top != top || old.right != right || old.bottom != bottom) {
                    mBackdrop.setBounds(left, top, right, bottom);
                }
                mBackdrop.setCornerRadius(mRadii[0], mRadii[2], mRadii[6], mRadii[4]);
                mBackdrop.setBlurRadius(Math.round(18f * density));
                mBackdrop.setRefraction(12f * density,
                        Math.min(Math.min(mRect.width(), mRect.height()) * .2f, 18f * density),
                        1.75f * density);
                mBackdrop.setAlpha(Math.round(getAlpha() * mView.getAlpha()));
                mBackdrop.draw(canvas);
            }
        }
        int color = getColor() == null ? 0xA8101012 : getColor().getDefaultColor();
        if (mShader != null && blur) {
            if (mShaderColor != color) {
                mShaderColor = color;
                mShader.setColorUniform("tint", color);
                mShader.setFloatUniform("surface", Color.luminance(color) > .5f ? 1f : 0f);
            }
            mPaint.setShader(mShader);
            mPaint.setColor(Color.WHITE);
            mPaint.setAlpha(getAlpha());
        } else {
            mPaint.setShader(null);
            mPaint.setColor(color);
            mPaint.setAlpha(Math.round((blur ? Color.alpha(color) : 242) * getAlpha() / 255f));
        }
        canvas.drawPath(mPath, mPaint);
        if (!mBackdropEnabled) {
            mPaint.setShader(null);
            mPaint.setColor((mAccent & 0xFFFFFF) | 0x18000000);
            mPaint.setAlpha(Math.round(24f * getAlpha() / 255f));
            canvas.drawPath(mPath, mPaint);
            mPaint.setShader(mSheen);
            mPaint.setAlpha(getAlpha());
            canvas.drawPath(mPath, mPaint);
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(density);
            mPaint.setColor(0x44FFFFFF);
            mPaint.setAlpha(Math.round(68f * getAlpha() / 255f));
            canvas.drawPath(mPath, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    // same island shoulder, no ticking animation to babysit it
    private static final String SLAB = """
            uniform float2 center;
            uniform float2 halfSize;
            uniform float4 radii;
            uniform float bandWidth;
            uniform float surface;
            layout(color) uniform half4 tint;
            half4 main(float2 xy) {
                float2 p = xy - center;
                float radius = p.y < 0.0 ? (p.x < 0.0 ? radii.x : radii.y)
                                        : (p.x < 0.0 ? radii.z : radii.w);
                float2 inner = halfSize - max(radius, bandWidth);
                if (abs(p.x) <= inner.x && abs(p.y) <= inner.y) {
                    return half4(tint.rgb * tint.a, tint.a);
                }
                float2 q = abs(p) - halfSize + radius;
                float2 outside = max(q, 0.0);
                float outsideLength = length(outside);
                float d = outsideLength + min(max(q.x, q.y), 0.0) - radius;
                float u = clamp(1.0 + d / bandWidth, 0.0, 1.0);
                float shoulder = smoothstep(0.0, 1.0, u);
                if (shoulder == 0.0) return half4(tint.rgb * tint.a, tint.a);
                float2 edgeNormal = outsideLength > 0.001 ? outside / outsideLength
                        : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
                edgeNormal *= sign(p);
                float3 n = float3(edgeNormal * u, sqrt(max(1.0 - u * u, 0.0)));
                float3 lightDir = normalize(float3(-0.45, -0.60, 0.80));
                float3 halfway = normalize(lightDir + float3(0.0, 0.0, 1.0));
                float reflection = pow(max(dot(n, halfway), 0.0), 18.0);
                float facing = dot(edgeNormal, normalize(float2(-0.45, -0.60)));
                float fresnel = u * u * u * u;
                float darkMode = 1.0 - surface;
                float highlight = shoulder * (mix(0.12, 0.20, darkMode) * reflection
                        + mix(0.06, 0.10, darkMode) * fresnel * max(facing, 0.0));
                float shade = shoulder * mix(0.045, 0.13, darkMode) * max(-facing, 0.0);
                half a = tint.a * half(1.0 - mix(0.18, 0.55, darkMode) * shoulder);
                half4 col = half4(tint.rgb * a, a);
                half darkAlpha = half(clamp(shade, 0.0, 0.22));
                col = half4(col.rgb * (1.0 - darkAlpha), darkAlpha + col.a * (1.0 - darkAlpha));
                half lightAlpha = half(clamp(highlight, 0.0, 0.32));
                return half4(half3(lightAlpha) + col.rgb * (1.0 - lightAlpha),
                        lightAlpha + col.a * (1.0 - lightAlpha));
            }
            """;
}
