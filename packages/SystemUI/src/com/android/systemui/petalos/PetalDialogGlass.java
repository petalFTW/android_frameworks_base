/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos;

import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.systemui.island.render.LiquidGlassDrawable;
import com.android.systemui.island.render.RefractionShader;
import org.petalos.config.PetalConfig;

import java.util.function.Consumer;

// keep the bezel black, let the bit sticking out bend the backdrop
final class PetalDialogGlass implements View.OnAttachStateChangeListener {
    private final View mView;
    private final WindowManager mWindowManager;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private final LiquidGlassDrawable mGlass = new LiquidGlassDrawable();
    private final RefractionShader mLens = new RefractionShader();
    private BackgroundBlurDrawable mBackdrop;
    private LinearGradient mGradient;
    private boolean mLeft;
    private boolean mEnabled;
    private final Consumer<Boolean> mBlurListener = enabled -> invalidateView();
    private final ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    PetalDialogGlass(View view) {
        mView = view;
        mWindowManager = view.getContext().getSystemService(WindowManager.class);
        mGlass.setTintColor(0x12FFFFFF);
        mGlass.setRim(0x55FFFFFF, 0x16FFFFFF);
        mGlass.setGrainAlpha(5);
        mGlass.setSpecularProgress(.35f);
        mView.addOnAttachStateChangeListener(this);
    }

    private void invalidateView() { mView.invalidate(); }

    private void refresh() {
        mEnabled = Settings.System.getIntForUser(mView.getContext().getContentResolver(),
                PetalConfig.KEY_DIALOG_STYLE, 0, android.os.UserHandle.USER_CURRENT) == 1;
        if (!mEnabled && mBackdrop != null) mBackdrop.setVisible(false, false);
        mView.invalidate();
    }

    @Override public void onViewAttachedToWindow(View view) {
        mBackdrop = view.getViewRootImpl().createBackgroundBlurDrawable();
        view.getContext().getContentResolver().registerContentObserver(
                Settings.System.getUriFor(PetalConfig.KEY_DIALOG_STYLE), false, mObserver,
                android.os.UserHandle.USER_ALL);
        mWindowManager.addCrossWindowBlurEnabledListener(view.getContext().getMainExecutor(),
                mBlurListener);
        refresh();
    }

    @Override public void onViewDetachedFromWindow(View view) {
        view.getContext().getContentResolver().unregisterContentObserver(mObserver);
        mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        if (mBackdrop != null) {
            mBackdrop.setVisible(false, false);
            mBackdrop.setBlurRadius(0);
            mLens.clear(mBackdrop);
            mBackdrop = null;
        }
    }

    boolean draw(Canvas canvas, Path body, boolean left, float edgeX, float cy,
            float width, float height, float radius, float opacity) {
        if (!mEnabled || mBackdrop == null || !canvas.isHardwareAccelerated()
                || !mWindowManager.isCrossWindowBlurEnabled()) {
            if (mBackdrop != null) mBackdrop.setVisible(false, false);
            return false;
        }
        if (width <= 0f || height <= 0f || opacity <= 0f) {
            mBackdrop.setVisible(false, false);
            return true;
        }
        float x = left ? edgeX : edgeX - width;
        if (mGradient == null || mLeft != left || mBounds.left != x
                || mBounds.top != cy - height / 2f || mBounds.width() != width
                || mBounds.height() != height) {
            mLeft = left;
            mBounds.set(x, cy - height / 2f, x + width, cy + height / 2f);
            float inner = left ? x + width : x;
            mGradient = new LinearGradient(edgeX, cy, inner, cy,
                    new int[]{Color.BLACK, 0xF2000000, 0x18000000},
                    new float[]{0f, .16f, 1f}, Shader.TileMode.CLAMP);
        }
        float density = mView.getResources().getDisplayMetrics().density;
        int w = Math.max(1, Math.round(width));
        int h = Math.max(1, Math.round(height));
        mBackdrop.setVisible(true, false);
        int leftPx = Math.round(x);
        int topPx = Math.round(mBounds.top);
        int rightPx = Math.round(x + width);
        int bottomPx = Math.round(mBounds.bottom);
        android.graphics.Rect old = mBackdrop.getBounds();
        if (old.left != leftPx || old.top != topPx || old.right != rightPx
                || old.bottom != bottomPx) {
            mBackdrop.setBounds(leftPx, topPx, rightPx, bottomPx);
        }
        mBackdrop.setCornerRadius(radius);
        mBackdrop.setBlurRadius(Math.round(18f * density));
        mBackdrop.setAlpha(Math.round(255f * opacity));
        mLens.applyTo(mBackdrop, w, h, density,
                Math.min(1f, width / (48f * density)), .35f);
        int save = canvas.save();
        canvas.clipPath(body);
        mBackdrop.draw(canvas);
        mGlass.setBounds(mBackdrop.getBounds());
        mGlass.setCornerRadius(radius);
        mGlass.setRimWidth(density);
        mGlass.setSlab(mLens.bandFor(w, h, density), .65f);
        mGlass.setTintColor(Math.round(18f * opacity) << 24 | 0xFFFFFF);
        mGlass.setRim(Math.round(85f * opacity) << 24 | 0xFFFFFF,
                Math.round(22f * opacity) << 24 | 0xFFFFFF);
        mGlass.setGrainAlpha(Math.round(5f * opacity));
        mGlass.setSheenStrength(Math.round(255f * opacity));
        mGlass.draw(canvas);
        // paint this last or the sheen turns the bezel grey, duh
        mPaint.setShader(mGradient);
        mPaint.setAlpha(Math.round(255f * opacity));
        canvas.drawPath(body, mPaint);
        mPaint.setShader(null);
        canvas.restoreToCount(save);
        return true;
    }
}
