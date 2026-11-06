/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.Flags.notificationRowTransparency;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Dumpable;
import com.android.systemui.island.render.LiquidGlassDrawable;
import com.android.systemui.island.render.RefractionShader;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.systemui.petalos.NotificationStyleController;
import com.android.systemui.petalos.NotificationSilkDrawable;
import com.android.systemui.common.shared.colors.SurfaceEffectColors;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.shared.NotificationAddXOnHoverToDismiss;
import com.android.systemui.util.DrawableDumpKt;

import java.io.PrintWriter;
import java.util.Arrays;

// A view that can be used for both the dimmed and normal background of an notification.
public class NotificationBackgroundView extends View implements Dumpable,
        ExpandableNotificationRow.DismissButtonTargetVisibilityListener {

    private final boolean mDontModifyCorners;
    private Drawable mBackground;
    private final LiquidGlassDrawable mGlassFinish = new LiquidGlassDrawable();
    private final RefractionShader mRefraction = new RefractionShader();
    private final NotificationStyleController mStyleController;
    private final NotificationSilkDrawable mSilk;
    private final Runnable mStyleListener = this::onStyleChanged;
    private BackgroundBlurDrawable mBackdrop;
    private Runnable mAppearanceChangedListener;
    private boolean mAggregatedVisible;
    private boolean mUsesParentMaterial;
    private float mRequestedTopRadius;
    private float mRequestedBottomRadius;
    private final Path mMaterialClip = new Path();
    private final RectF mMaterialBounds = new RectF();
    private boolean mMaterialClipDirty = true;
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mSilkAccent;
    private int mClipTopAmount;
    private int mTopOverlap;
    private int mBottomOverlap;
    private int mClipBottomAmount;
    private int mTintColor;
    @Nullable private Integer mRippleColor;
    private final float[] mCornerRadii = new float[8];
    private final float[] mFocusOverlayCornerRadii = new float[8];
    private float mFocusOverlayStroke = 0;
    private boolean mBottomIsRounded;
    private boolean mBottomAmountClips = true;
    private int mActualHeight = -1;
    private int mActualWidth = -1;
    private boolean mExpandAnimationRunning;
    private int mExpandAnimationWidth = -1;
    private int mExpandAnimationHeight = -1;
    private int mDrawableAlpha = 255;
    private final ColorStateList mLightColoredStatefulColors;
    private final ColorStateList mDarkColoredStatefulColors;
    private int mNormalColor;
    private final int convexR = 9;
    private final int concaveR = 22;

    // True only if the dismiss button is visible.
    private boolean mDrawDismissButtonCutout = false;

    public NotificationBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mStyleController = NotificationStyleController.get(context);
        mSilk = new NotificationSilkDrawable(getResources().getDisplayMetrics().density);
        mSilkAccent = context.getColor(android.R.color.system_accent1_200);
        mDontModifyCorners = getResources().getBoolean(R.bool.config_clipNotificationsToOutline);
        mLightColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_light);
        mDarkColoredStatefulColors = getResources().getColorStateList(
                R.color.notification_state_color_dark);
        if (notificationRowTransparency()) {
            mNormalColor = SurfaceEffectColors.surfaceEffect1(getContext());
        } else  {
            mNormalColor = mContext.getColor(
                    com.android.internal.R.color.materialColorSurfaceContainerHigh);
        }
        mFocusOverlayStroke = getResources().getDimension(R.dimen.notification_focus_stroke_width);
    }

    public void setAppearanceChangedListener(Runnable listener) {
        mAppearanceChangedListener = listener;
    }

    public boolean hasPetalFinish() {
        return mStyleController.hasFinish();
    }

    /** Expanded app groups have one material owner; children may retain app-colored content. */
    public void setUsesParentMaterial(boolean usesParentMaterial) {
        if (mUsesParentMaterial == usesParentMaterial) return;
        mUsesParentMaterial = usesParentMaterial;
        if (usesParentMaterial) releaseBackdrop();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStyleController.addListener(mStyleListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        mStyleController.removeListener(mStyleListener);
        releaseBackdrop();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        mAggregatedVisible = visible;
        onStyleChanged();
    }

    private void releaseBackdrop() {
        if (mBackdrop == null) return;
        mBackdrop.setVisible(false, false);
        mBackdrop.setBlurRadius(0);
        mRefraction.clear(mBackdrop);
        mBackdrop.setCallback(null);
        mBackdrop = null;
    }

    private void onStyleChanged() {
        if (!mAggregatedVisible || !mStyleController.canRenderBackdrop()) releaseBackdrop();
        setRadius(mRequestedTopRadius, mRequestedBottomRadius);
        if (mBackground != null) setTint(mTintColor);
        if (mAppearanceChangedListener != null) mAppearanceChangedListener.run();
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        mSilkAccent = getContext().getColor(android.R.color.system_accent1_200);
        invalidate();
    }

    @Override
    public void onTargetVisibilityChanged(boolean targetVisible) {
        if (NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode()) {
            return;
        }

        if (mDrawDismissButtonCutout != targetVisible) {
            mDrawDismissButtonCutout = targetVisible;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float clipTop = Math.max(mClipTopAmount, mTopOverlap);
        int clipBottomAmount = Math.max(mClipBottomAmount, mBottomOverlap);
        if (clipTop + clipBottomAmount < getActualHeight() || mExpandAnimationRunning) {
            canvas.save();
            if (!mExpandAnimationRunning) {
                canvas.clipRect(0, clipTop, getWidth(),
                        getActualHeight() - clipBottomAmount);
            }

            if (!NotificationAddXOnHoverToDismiss.isEnabled()) {
                draw(canvas, mBackground);
                canvas.restore();
                return;
            }

            Rect backgroundBounds = null;
            if (mBackground != null || mDrawDismissButtonCutout) {
                backgroundBounds = calculateBackgroundBounds();
            }

            if (mDrawDismissButtonCutout) {
                canvas.clipPath(calculateDismissButtonCutoutPath(backgroundBounds));
            }

            if (mBackground != null) {
                mBackground.setBounds(backgroundBounds);
                drawBackdrop(canvas, mBackground);
                mBackground.draw(canvas);
                drawGlassFinish(canvas, mBackground);
            }

            canvas.restore();
        }
    }

    private Path calculateDismissButtonCutoutPath(Rect backgroundBounds) {
        // TODO(b/365585705): Adapt to RTL after the UX design is finalized.

        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        Path path = new Path();

        final int left = backgroundBounds.left;
        final int right = backgroundBounds.right;
        final int top = backgroundBounds.top;
        final int bottom = backgroundBounds.bottom;

        // Generate the path clockwise from the left-top corner.
        path.moveTo(left, top);
        path.lineTo(right - 2 * convexR - concaveR, top);
        path.quadTo(right - convexR - concaveR, top, right - convexR - concaveR,
                top + convexR);
        path.quadTo(right - convexR - concaveR, top + convexR + concaveR, right - convexR,
                top + convexR + concaveR);
        path.quadTo(right, top + convexR + concaveR, right, top + 2 * convexR + concaveR);
        path.lineTo(right, bottom);
        path.lineTo(left, bottom);
        path.lineTo(left, top);

        return path;
    }

    private Rect calculateBackgroundBounds() {
        NotificationAddXOnHoverToDismiss.isUnexpectedlyInLegacyMode();

        int top = 0;
        int bottom = getActualHeight();
        if (mBottomIsRounded
                && mBottomAmountClips
                && !mExpandAnimationRunning) {
            bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
        }
        final boolean alignedToRight = isAlignedToRight();
        final int width = getWidth();
        final int actualWidth = getActualWidth();

        int left = alignedToRight ? width - actualWidth : 0;
        int right = alignedToRight ? width : actualWidth;

        if (mExpandAnimationRunning) {
            // Horizontally center this background view inside of the container
            left = (int) ((width - actualWidth) / 2.0f);
            right = (int) (left + actualWidth);
        }

        return new Rect(left, top, right, bottom);
    }

    // @return Whether the background view should be right-aligned.
    protected boolean isAlignedToRight() {
        return isLayoutRtl();
    }

    private void draw(Canvas canvas, Drawable drawable) {
        NotificationAddXOnHoverToDismiss.assertInLegacyMode();

        if (drawable != null) {
            int top = 0;
            int bottom = getActualHeight();
            if (mBottomIsRounded
                    && mBottomAmountClips
                    && !mExpandAnimationRunning) {
                bottom -= Math.max(mClipBottomAmount, mBottomOverlap);
            }

            final boolean alignedToRight = isAlignedToRight();
            final int width = getWidth();
            final int actualWidth = getActualWidth();

            int left = alignedToRight ? width - actualWidth : 0;
            int right = alignedToRight ? width : actualWidth;

            if (mExpandAnimationRunning) {
                // Horizontally center this background view inside of the container
                left = (int) ((width - actualWidth) / 2.0f);
                right = (int) (left + actualWidth);
            }
            drawable.setBounds(left, top, right, bottom);
            drawBackdrop(canvas, drawable);
            drawable.draw(canvas);
            drawGlassFinish(canvas, drawable);
        }
    }

    private void drawGlassFinish(Canvas canvas, Drawable background) {
        if (mUsesParentMaterial || !petalGlassNotificationsEnabled()) return;
        boolean dark = ContrastColorUtil.isColorDark(mTintColor == 0 ? mNormalColor : mTintColor);
        if (mStyleController.getStyle() == 2) {
            mSilk.draw(canvas, background.getBounds(), mCornerRadii, mSilkAccent, dark,
                    background.getAlpha());
            return;
        }
        mGlassFinish.setBounds(background.getBounds());
        mGlassFinish.setCornerRadius(mStyleController.getStyle() == 0
                ? getResources().getDimension(R.dimen.island_corner_expanded)
                : Math.max(mCornerRadii[0], mCornerRadii[4]));
        boolean lens = mBackdrop != null && canvas.isHardwareAccelerated();
        mGlassFinish.setSlab(lens ? mRefraction.bandFor(background.getBounds().width(),
                background.getBounds().height(), getResources().getDisplayMetrics().density)
                : 0f, lens ? .65f : 0f);
        mGlassFinish.setRimWidth(getResources().getDisplayMetrics().density);
        mGlassFinish.setGrainAlpha(dark ? 0x14 : 0x10);
        mGlassFinish.setRim(dark ? 0x59FFFFFF : 0x99FFFFFF,
                dark ? 0x14FFFFFF : 0x26000000);
        Rect bounds = background.getBounds();
        int save = canvas.save();
        if (mStyleController.getStyle() != 0) {
            updateMaterialClip(bounds);
            canvas.clipPath(mMaterialClip);
        }
        if (background.getAlpha() < 255) {
            canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    background.getAlpha());
        }
        mGlassFinish.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void drawBackdrop(Canvas canvas, Drawable background) {
        if (mUsesParentMaterial) {
            releaseBackdrop();
            return;
        }
        if (!canvas.isHardwareAccelerated() || !mAggregatedVisible
                || !mStyleController.canRenderBackdrop() || getViewRootImpl() == null
                || background.getAlpha() == 0) {
            releaseBackdrop();
            if (!canvas.isHardwareAccelerated() && mStyleController.getStyle() == 1) {
                updateMaterialClip(background.getBounds());
                mFallbackPaint.setColor((mTintColor == 0 ? mNormalColor : mTintColor)
                        | 0xff000000);
                mFallbackPaint.setAlpha(background.getAlpha());
                canvas.drawPath(mMaterialClip, mFallbackPaint);
            }
            return;
        }
        if (mBackdrop == null) {
            mBackdrop = getViewRootImpl().createBackgroundBlurDrawable();
        }
        Rect bounds = background.getBounds();
        if (!bounds.equals(mBackdrop.getBounds())) mBackdrop.setBounds(bounds);
        mBackdrop.setCornerRadius(mCornerRadii[0], mCornerRadii[2],
                mCornerRadii[6], mCornerRadii[4]);
        mBackdrop.setAlpha(background.getAlpha());
        float density = getResources().getDisplayMetrics().density;
        mBackdrop.setBlurRadius((int) (20 * density));
        mRefraction.applyTo(mBackdrop, bounds.width(), bounds.height(), density, .65f, .35f);
        // Clip the finish and base by the same live group/expansion geometry.
        updateMaterialClip(bounds);
        int save = canvas.save();
        canvas.clipPath(mMaterialClip);
        mBackdrop.draw(canvas);
        canvas.restoreToCount(save);
    }

    private void updateMaterialClip(Rect bounds) {
        if (!mMaterialClipDirty && mMaterialBounds.left == bounds.left
                && mMaterialBounds.top == bounds.top && mMaterialBounds.right == bounds.right
                && mMaterialBounds.bottom == bounds.bottom) return;
        mMaterialBounds.set(bounds);
        mMaterialClip.reset();
        mMaterialClip.addRoundRect(mMaterialBounds, mCornerRadii, Path.Direction.CW);
        mMaterialClipDirty = false;
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == mBackground;
    }

    @Override
    protected void drawableStateChanged() {
        setState(getDrawableState());
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (mBackground != null) {
            mBackground.setHotspot(x, y);
        }
    }

    // Stateful colors are colors that will overlay on the notification original color when one of hover states, pressed states or other similar states is activated.
    private void setStatefulColors() {
        if (mTintColor != mNormalColor) {
            ColorStateList newColor = ContrastColorUtil.isColorDark(mTintColor)
                    ? mDarkColoredStatefulColors : mLightColoredStatefulColors;
            ((GradientDrawable) getStatefulBackgroundLayer().mutate()).setColor(newColor);
        }
    }

    // Sets a background drawable.
    public void setCustomBackground(Drawable background) {
        if (mBackground != null) {
            mBackground.setCallback(null);
            unscheduleDrawable(mBackground);
        }
        mBackground = background;
        mRippleColor = null;
        mBackground.mutate();
        if (mBackground != null) {
            mBackground.setCallback(this);
            setTint(mTintColor);
        }
        if (mBackground instanceof RippleDrawable) {
            ((RippleDrawable) mBackground).setForceSoftware(true);
        }
        updateBackgroundRadii();
        invalidate();
    }

    public void setCustomBackground(int drawableResId) {
        final Drawable d = mContext.getDrawable(drawableResId);
        setCustomBackground(d);
    }

    public Drawable getBaseBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(0);
    }

    private Drawable getStatefulBackgroundLayer() {
        return ((LayerDrawable) mBackground).getDrawable(1);
    }

    public void setTint(int tintColor) {
        Drawable baseLayer = getBaseBackgroundLayer();
        if (notificationRowTransparency() || petalGlassNotificationsEnabled()) {
            baseLayer.mutate().setTintList(null);
            int surface = tintColor == Color.TRANSPARENT ? mNormalColor : tintColor;
            if (mStyleController.getStyle() != 0) {
                // Opaque fallback on software canvases, AOD, Battery Saver, or disabled blur.
                int alpha = mStyleController.canRenderBackdrop() && isHardwareAccelerated()
                        ? 0xcc : (mStyleController.getStyle() == 2 ? 0xf2 : 0xff);
                surface = (surface & 0x00ffffff) | (alpha << 24);
            }
            ((GradientDrawable) baseLayer).setColor(surface);

        } else {
            baseLayer.mutate().setTintMode(PorterDuff.Mode.SRC_ATOP);
            baseLayer.setTint(tintColor);
        }
        mTintColor = tintColor;
        setStatefulColors();
        invalidate();
    }

    public void setActualHeight(int actualHeight) {
        if (mExpandAnimationRunning) {
            return;
        }
        mActualHeight = actualHeight;
        invalidate();
    }

    private int getActualHeight() {
        if (mExpandAnimationRunning && mExpandAnimationHeight > -1) {
            return mExpandAnimationHeight;
        } else if (mActualHeight > -1) {
            return mActualHeight;
        }
        return getHeight();
    }

    public void setActualWidth(int actualWidth) {
        mActualWidth = actualWidth;
    }

    private int getActualWidth() {
        if (mExpandAnimationRunning && mExpandAnimationWidth > -1) {
            return mExpandAnimationWidth;
        } else if (mActualWidth > -1) {
            return mActualWidth;
        }
        return getWidth();
    }

    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
        invalidate();
    }

    // Sets the overlap on the top of the view with other views.
    public void setTopOverlap(int topOverlap) {
        mTopOverlap = topOverlap;
        invalidate();
    }

    // Sets the overlap on the bottom of the view with other views.
    public void setBottomOverlap(int bottomOverlap) {
        mBottomOverlap = bottomOverlap;
        invalidate();
    }

    public void setClipBottomAmount(int clipBottomAmount) {
        mClipBottomAmount = clipBottomAmount;
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {

        // Prevents this view from creating a layer when alpha is animating.
        return false;
    }

    public void setState(int[] drawableState) {
        if (mBackground != null && mBackground.isStateful()) {
            mBackground.setState(drawableState);
        }
    }

    public void setRippleColor(int color) {
        if (mBackground instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) mBackground;
            ripple.setColor(ColorStateList.valueOf(color));
            mRippleColor = color;
        } else {
            mRippleColor = null;
        }
    }

    public void setDrawableAlpha(int drawableAlpha) {
        mDrawableAlpha = drawableAlpha;
        if (mExpandAnimationRunning) {
            return;
        }
        mBackground.setAlpha(drawableAlpha);
    }

    // Sets the current top and bottom radius for this background.
    public void setRadius(float topRoundness, float bottomRoundness) {
        mRequestedTopRadius = topRoundness;
        mRequestedBottomRadius = bottomRoundness;
        // Keep the damn island corners.
        if (petalGlassNotificationsEnabled() && mStyleController.getStyle() == 0) {
            float glassRadius = getResources().getDimensionPixelSize(
                    R.dimen.island_corner_expanded);
            topRoundness = glassRadius;
            bottomRoundness = glassRadius;
        }
        if (topRoundness == mCornerRadii[0] && bottomRoundness == mCornerRadii[4]) {
            return;
        }
        mBottomIsRounded = bottomRoundness != 0.0f;
        mMaterialClipDirty = true;
        mCornerRadii[0] = topRoundness;
        mCornerRadii[1] = topRoundness;
        mCornerRadii[2] = topRoundness;
        mCornerRadii[3] = topRoundness;
        mCornerRadii[4] = bottomRoundness;
        mCornerRadii[5] = bottomRoundness;
        mCornerRadii[6] = bottomRoundness;
        mCornerRadii[7] = bottomRoundness;
        updateBackgroundRadii();
    }

    /** True when the petalOS glass-notification restyle is enabled. */
    private boolean petalGlassNotificationsEnabled() {
        return mStyleController.hasFinish();
    }

    public void setBottomAmountClips(boolean clips) {
        if (clips != mBottomAmountClips) {
            mBottomAmountClips = clips;
            invalidate();
        }
    }

    private void updateBackgroundRadii() {
        if (mDontModifyCorners) {
            return;
        }
        if (mBackground instanceof LayerDrawable layerDrawable) {
            int numberOfLayers = layerDrawable.getNumberOfLayers();
            for (int i = 0; i < numberOfLayers; i++) {
                GradientDrawable gradientDrawable = (GradientDrawable) layerDrawable.getDrawable(i);
                gradientDrawable.setCornerRadii(mCornerRadii);
            }
            updateFocusOverlayRadii(layerDrawable);
        }
    }

    private void updateFocusOverlayRadii(LayerDrawable background) {
        GradientDrawable overlay =
                (GradientDrawable) background.findDrawableByLayerId(
                        R.id.notification_focus_overlay);
        for (int i = 0; i < mCornerRadii.length; i++) {
            // Stop the damn corners peeking through.
            mFocusOverlayCornerRadii[i] = Math.max(0, mCornerRadii[i] - mFocusOverlayStroke);
        }
        overlay.setCornerRadii(mFocusOverlayCornerRadii);
    }

    /** Set the current expand animation size. */
    public void setExpandAnimationSize(int width, int height) {
        mExpandAnimationHeight = height;
        mExpandAnimationWidth = width;
        invalidate();
    }

    public void setExpandAnimationRunning(boolean running) {
        mExpandAnimationRunning = running;
        if (mBackground instanceof LayerDrawable) {
            GradientDrawable gradientDrawable =
                    (GradientDrawable) ((LayerDrawable) mBackground).getDrawable(0);
            // Speed optimization: disable AA if transfer mode is not SRC_OVER. AA is not easy to
            // spot during animation anyways.
            gradientDrawable.setAntiAlias(!running);
        }
        if (!mExpandAnimationRunning) {
            setDrawableAlpha(mDrawableAlpha);
        }
        invalidate();
    }

    @Override
    public void dump(PrintWriter pw, @NonNull String[] args) {
        pw.println("mDontModifyCorners: " + mDontModifyCorners);
        pw.println("mClipTopAmount: " + mClipTopAmount);
        pw.println("mClipBottomAmount: " + mClipBottomAmount);
        pw.println("mTopOverlap: " + mTopOverlap);
        pw.println("mBottomOverlap: " + mBottomOverlap);
        pw.println("mCornerRadii: " + Arrays.toString(mCornerRadii));
        pw.println("mBottomIsRounded: " + mBottomIsRounded);
        pw.println("mBottomAmountClips: " + mBottomAmountClips);
        pw.println("mActualWidth: " + mActualWidth);
        pw.println("mActualHeight: " + mActualHeight);
        pw.println("mTintColor: " + hexColorString(mTintColor));
        pw.println("mRippleColor: " + hexColorString(mRippleColor));
        pw.println("mBackground: " + DrawableDumpKt.dumpToString(mBackground));
    }

    /** create a concise dump of this view's colors */
    public String toDumpString() {
        return "<NotificationBackgroundView"
                + " tintColor=" + hexColorString(mTintColor)
                + " rippleColor=" + hexColorString(mRippleColor)
                + " bgColor=" + DrawableDumpKt.getSolidColor(mBackground)
                + ">";

    }
}
