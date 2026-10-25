/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.statusbar.notification.stack;

import android.animation.ValueAnimator;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.app.animation.Interpolators;
import com.android.systemui.keyguard.ui.view.FingerprintBlobView;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

// Real cards, just tucked under each other instead of that bloody pill.
final class PetalNotificationCollection {
    private final NotificationStackScrollLayout mHost;
    private final ArrayList<ExpandableNotificationRow> mRows = new ArrayList<>();
    private final Map<View, Integer> mAccessibility = new IdentityHashMap<>();
    private final ArrayList<ExpandableNotificationRow> mTransformedRows = new ArrayList<>();
    private final RectF mTouchBounds = new RectF();
    private final int[] mHostPosition = new int[2];
    private final int[] mPlayerPosition = new int[2];
    private final int[] mTmpPosition = new int[2];
    private final float mDensity;
    private final float mTouchSlop;
    private MediaContainerView mMedia;
    private View mCdPlayer;
    private View mFingerprint;
    private View mIndication;
    private float mSafeTop;
    private float mSafeBottom;
    private float mFrozenTop;
    private float mExpandedTop;
    private float mFingerprintTop;
    private boolean mCdShowing;
    private boolean mActive;
    private boolean mExpanded;
    private float mProgress;
    private float mScroll;
    private float mMaxScroll;
    private float mShadeProgress;
    private float mDownX;
    private float mDownY;
    private float mLastY;
    private boolean mTracking;
    private boolean mOwnsTouch;
    private boolean mConsumed;
    private boolean mCancelledChild;
    private boolean mTap;
    private ValueAnimator mAnimator;
    private final View.OnLayoutChangeListener mCdLayoutListener = this::onCdLayoutChanged;

    private void onCdLayoutChanged(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            mHost.onPetalCollectionChanged();
        }
    }

    PetalNotificationCollection(NotificationStackScrollLayout host) {
        mHost = host;
        mDensity = host.getResources().getDisplayMetrics().density;
        mTouchSlop = ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
        host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) { }

            @Override
            public void onViewDetachedFromWindow(View view) {
                reset();
            }
        });
    }

    void setCdPlayer(View player, boolean showing) {
        if (mCdPlayer != player) {
            restoreCd();
            if (mCdPlayer != null) mCdPlayer.removeOnLayoutChangeListener(mCdLayoutListener);
            mCdPlayer = player;
            if (player != null) player.addOnLayoutChangeListener(mCdLayoutListener);
        }
        mCdShowing = showing;
        if (showing) applyCdFade();
        mHost.requestChildrenUpdate();
    }

    boolean refresh(AmbientState ambient) {
        mMedia = null;
        mRows.clear();
        for (int i = 0; i < mHost.getChildCount(); i++) {
            View child = mHost.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            if (child instanceof MediaContainerView media && media.getIntrinsicHeight() > 0) {
                mMedia = media;
            } else if (child instanceof ExpandableNotificationRow row
                    && !row.isRemoved() && !row.isDismissed() && !row.hasNoContentHeight()) {
                mRows.add(row);
            }
        }
        // The player vanishes first; don't drop the cards halfway through the drag.
        boolean moving = ambient.getFractionToShade() > 0f
                || ambient.isSwipingUp() || ambient.isBouncerInTransit();
        boolean active = !SceneContainerFlag.isEnabled()
                && ambient.isOnKeyguard() && !ambient.isDozing()
                && ambient.getDozeAmount() == 0f && ambient.getHideAmount() == 0f
                && !ambient.isBouncerShowing()
                && (moving || mHost.getQsExpansionFraction() == 0f)
                && (mMedia != null || mCdShowing || (mActive && moving && mFrozenTop > 0f))
                && !mRows.isEmpty();
        if (!active && mActive) reset();
        mActive = active;
        if (active) updateSafeBounds(ambient);
        return active;
    }

    // Find the lock blob and the charging line so the cards stop crashing into them.
    private void updateSafeBounds(AmbientState ambient) {
        // Keep the old values while the keyguard is moving, live bounds make the stack wobble.
        if (mSafeTop > 0f && (mProgress > 0f || isAnimating()
                || ambient.getFractionToShade() > 0f
                || ambient.isSwipingUp() || ambient.isBouncerInTransit())) return;
        View root = mHost.getRootView();
        if (root == null) return;
        mSafeTop = 0f;
        mSafeBottom = 0f;
        mFingerprintTop = 0f;
        if (mFingerprint == null || !mFingerprint.isAttachedToWindow()) {
            mFingerprint = root.findViewById(R.id.device_entry_icon_fg);
            if (mFingerprint == null) mFingerprint = root.findViewById(R.id.device_entry_icon_view);
        }
        if (mIndication == null || !mIndication.isAttachedToWindow()) {
            mIndication = root.findViewById(R.id.keyguard_indication_area);
        }
        mHost.getLocationOnScreen(mHostPosition);
        if (mFingerprint != null && mFingerprint.isShown() && mFingerprint.getHeight() > 0) {
            mFingerprint.getLocationOnScreen(mTmpPosition);
            float size = Math.max(mFingerprint.getMeasuredWidth(),
                    mFingerprint.getMeasuredHeight());
            if (mFingerprint instanceof FingerprintBlobView blob) {
                size = Math.max(size, blob.getMinimumGlassSizePx());
            }
            // Glass can spill outside the view box, so add whatever hangs off the bottom.
            float spill = Math.max(0f, size - mFingerprint.getHeight()) / 2f;
            mFingerprintTop = mTmpPosition[1] - mHostPosition[1] - spill - 8f * mDensity;
            mSafeTop = mTmpPosition[1] - mHostPosition[1] + mFingerprint.getHeight()
                    + spill + 8f * mDensity;
        }
        if (mIndication != null && mIndication.isShown()) {
            mIndication.getLocationOnScreen(mTmpPosition);
            float indicationTop = mTmpPosition[1] - mHostPosition[1];
            if (indicationTop > mHost.getHeight() / 2f) {
                mSafeBottom = indicationTop - 8f * mDensity;
            }
        }
    }

    boolean isActive() {
        return mActive;
    }

    boolean isAnimating() {
        return mAnimator != null && mAnimator.isRunning();
    }

    boolean ownsTouch() {
        return mOwnsTouch;
    }

    private void reset() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        mExpanded = false;
        mProgress = 0f;
        mScroll = 0f;
        mFrozenTop = 0f;
        mExpandedTop = 0f;
        mShadeProgress = 0f;
        mSafeTop = 0f;
        mSafeBottom = 0f;
        mFingerprintTop = 0f;
        if (mTracking && mHost.getParent() != null) {
            mHost.getParent().requestDisallowInterceptTouchEvent(false);
        }
        mTracking = false;
        mOwnsTouch = false;
        mConsumed = false;
        mTouchBounds.setEmpty();
        restoreCd();
        for (Map.Entry<View, Integer> entry : mAccessibility.entrySet()) {
            entry.getKey().setImportantForAccessibility(entry.getValue());
        }
        mAccessibility.clear();
        for (ExpandableNotificationRow row : mTransformedRows) {
            row.getViewState().cancelAnimations(row);
            row.setScaleX(1f);
            row.setScaleY(1f);
            row.getViewState().setScaleX(1f);
            row.getViewState().setScaleY(1f);
        }
        mTransformedRows.clear();
        mActive = false;
    }

    private void restoreCd() {
        if (mCdPlayer == null) return;
        mCdPlayer.setAlpha(1f);
        if (mCdShowing) mCdPlayer.setVisibility(View.VISIBLE);
    }

    private void applyCdFade() {
        if (mCdPlayer == null || !mCdShowing) return;
        mCdPlayer.setAlpha(playerAlpha());
        // Invisible glass still eats taps, so get it out of the way.
        mCdPlayer.setVisibility(playerAlpha() == 0f ? View.INVISIBLE : View.VISIBLE);
    }

    private float playerAlpha() {
        // Fade out before the cards travel; come back only after they've landed.
        return 1f - Math.min(1f, mProgress / .25f);
    }

    private float cardProgress() {
        return Math.max(0f, (mProgress - .25f) / .75f);
    }

    float getHeight(AmbientState ambient, boolean minimum) {
        if (!refresh(ambient)) return -1f;
        float frontHeight = mRows.get(0).getMinHeight(true);
        float compact = compactTop(ambient) - ambient.getStackY() + frontHeight
                + Math.min(2, mRows.size() - 1) * 8f * mDensity;
        // Unfolding is local scrolling, not a new panel height. Resizing moved the clock too.
        return Math.max(0f, compact);
    }

    private float compactTop(AmbientState ambient) {
        boolean moving = ambient.getFractionToShade() > 0f
                || ambient.isSwipingUp() || ambient.isBouncerInTransit();
        // Player and stack don't always slide together, so freeze the geometry mid gesture.
        if ((moving || mProgress > 0f || isAnimating()) && mFrozenTop > 0f) return mFrozenTop;
        float top;
        if (mCdShowing) {
            mHost.getLocationOnScreen(mHostPosition);
            mCdPlayer.getLocationOnScreen(mPlayerPosition);
            top = mPlayerPosition[1] - mHostPosition[1] + mCdPlayer.getHeight();
        } else if (mMedia != null) {
            top = mMedia.getViewState().getYTranslation() + mMedia.getIntrinsicHeight();
        } else {
            top = ambient.getStackY();
        }
        top += 12f * mDensity;
        if (mSafeTop > 0f) top = Math.max(top, mSafeTop);
        if (!moving) mFrozenTop = top;
        return top;
    }

    void updateStates(AmbientState ambient) {
        if (!refresh(ambient)) return;
        float top = ambient.getStackY() + 12f * mDensity;
        if (!mCdShowing && mMedia != null) {
            ExpandableViewState mediaState = mMedia.getViewState();
            top = mediaState.getYTranslation();
            mediaState.cancelAnimations(mMedia);
            mediaState.setAlpha(mediaState.getAlpha() * playerAlpha());
            mediaState.hidden = playerAlpha() == 0f;
        }
        float compactTop = compactTop(ambient);
        if (mExpandedTop == 0f || (mProgress == 0f && !isAnimating()
                && ambient.getFractionToShade() == 0f && !ambient.isSwipingUp()
                && !ambient.isBouncerInTransit())) {
            mExpandedTop = top;
        }
        top = mExpandedTop;
        float progress = cardProgress();
        float shade = Math.max(0f, Math.min(1f, ambient.getFractionToShade()));
        mShadeProgress = Math.max(shade, mHost.getQsExpansionFraction());
        float frontHeight = mRows.get(0).getMinHeight(true);
        float bottom = Math.min(mHost.getHeight() - 24f * mDensity,
                ambient.getInnerHeight() + ambient.getStackY());
        // Don't let the list crawl over the charging line.
        if (mSafeBottom > top) bottom = Math.min(bottom, mSafeBottom);
        if (bottom <= top) bottom = mHost.getHeight() - 24f * mDensity;
        float expandedBottom = mFingerprintTop > top ? Math.min(bottom, mFingerprintTop) : bottom;
        float listHeight = 0f;
        for (ExpandableNotificationRow row : mRows) {
            listHeight += row.getIntrinsicHeight() + 8f * mDensity;
        }
        mMaxScroll = Math.max(0f, listHeight - (expandedBottom - top));
        mScroll = Math.min(mScroll, mMaxScroll);
        float expandedY = top - mScroll;
        mTouchBounds.setEmpty();
        for (int i = 0; i < mRows.size(); i++) {
            ExpandableNotificationRow row = mRows.get(i);
            if (!mTransformedRows.contains(row)) mTransformedRows.add(row);
            ExpandableViewState state = row.getViewState();
            state.cancelAnimations(row);
            float shadeY = state.getYTranslation();
            float shadeAlpha = state.getAlpha();
            int shadeHeight = state.height;
            boolean shadeHidden = state.hidden;
            boolean shadeInShelf = state.inShelf;
            int shadeClipTop = state.clipTopAmount;
            int shadeClipBottom = state.clipBottomAmount;
            float scale = lerp(1f - Math.min(i, 2) * .09f, 1f, progress);
            int height = Math.round(lerp(row.getMinHeight(true), row.getIntrinsicHeight(),
                    progress));
            float compactY = compactTop + frontHeight + Math.min(i, 2) * 8f * mDensity
                    - height * scale;
            float y = lerp(compactY, expandedY, progress);
            float clipTop = top;
            float clipBottom = bottom;
            if (mFingerprintTop > top && y < mSafeTop && y + height * scale > mFingerprintTop) {
                if (y + height * scale / 2f >= (mFingerprintTop + mSafeTop) / 2f) {
                    clipTop = Math.max(clipTop, mSafeTop);
                } else {
                    clipBottom = Math.min(clipBottom, mFingerprintTop);
                }
            }
            if (progress == 1f) clipBottom = Math.min(clipBottom, expandedBottom);
            row.setPivotX(row.getWidth() / 2f);
            row.setPivotY(0f);
            state.setYTranslation(lerp(y, shadeY, shade));
            state.setScaleX(lerp(scale, 1f, shade));
            state.setScaleY(lerp(scale, 1f, shade));
            state.setZTranslation(lerp((3 - Math.min(i, 3)) * 4f * mDensity,
                    0f, Math.max(progress, shade)));
            state.height = Math.round(lerp(height, shadeHeight, shade));
            state.inShelf = shade == 1f && shadeInShelf;
            state.clipTopAmount = Math.round(lerp(
                    Math.max(0f, (clipTop - y) / scale), shadeClipTop, shade));
            state.clipBottomAmount = Math.round(lerp(
                    Math.max(0f, (y + height * scale - clipBottom) / scale), shadeClipBottom, shade));
            state.hidden = shade == 1f ? shadeHidden : (i >= 3 && progress == 0f && shade == 0f)
                    || (shade == 0f && (y >= clipBottom || y + height * scale <= clipTop
                    || state.clipTopAmount + state.clipBottomAmount >= height));
            state.setAlpha(state.hidden ? 0f : lerp(i < 3 ? 1f : progress, shadeAlpha, shade));
            state.location = state.hidden ? ExpandableViewState.LOCATION_BOTTOM_STACK_HIDDEN
                    : ExpandableViewState.LOCATION_MAIN_AREA;
            row.setTopOverlap(0);
            row.setBottomOverlap(0);
            boolean behind = !mExpanded && i > 0 && shade == 0f;
            if (behind) {
                mAccessibility.putIfAbsent(row, row.getImportantForAccessibility());
                row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            } else if (mAccessibility.containsKey(row)) {
                row.setImportantForAccessibility(mAccessibility.remove(row));
            }
            if (!state.hidden) {
                float left = row.getLeft() + row.getWidth() * (1f - scale) / 2f;
                mTouchBounds.union(left, Math.max(top, y), left + row.getWidth() * scale,
                        Math.min(bottom, y + height * scale));
            }
            expandedY += row.getIntrinsicHeight() + 8f * mDensity;
        }
        if (ambient.getShelf() != null) {
            ExpandableViewState shelfState = ambient.getShelf().getViewState();
            shelfState.hidden |= shade == 0f;
            shelfState.setAlpha(shelfState.getAlpha() * shade);
        }
        applyCdFade();
    }

    private void setExpanded(boolean expanded) {
        if (!mActive || mExpanded == expanded) return;
        mExpanded = expanded;
        mScroll = 0f;
        if (mAnimator != null) mAnimator.cancel();
        for (ExpandableNotificationRow row : mRows) row.getViewState().cancelAnimations(row);
        if (mMedia != null) mMedia.getViewState().cancelAnimations(mMedia);
        mAnimator = ValueAnimator.ofFloat(mProgress, expanded ? 1f : 0f);
        mAnimator.setDuration(320);
        mAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mAnimator.addUpdateListener(animation -> {
            mProgress = (float) animation.getAnimatedValue();
            mHost.onPetalCollectionChanged();
        });
        mAnimator.start();
        mHost.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent
                .TYPE_WINDOW_CONTENT_CHANGED);
    }

    boolean onTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mTracking = mActive && mShadeProgress == 0f
                    && mTouchBounds.contains(event.getX(), event.getY());
            // Keep the whole gesture; the old drag helper was nicking its tail.
            mOwnsTouch = mTracking;
            mConsumed = mTracking && !mExpanded;
            mCancelledChild = mConsumed;
            mTap = true;
            mDownX = event.getX();
            mDownY = mLastY = event.getY();
            if (mTracking && mHost.getParent() != null) {
                mHost.getParent().requestDisallowInterceptTouchEvent(true);
            }
        }
        if (!mTracking) return false;
        float dy = event.getY() - mDownY;
        float dx = event.getX() - mDownX;
        if (action == MotionEvent.ACTION_POINTER_DOWN) mTap = false;
        if (action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(dx) > mTouchSlop || Math.abs(dy) > mTouchSlop) mTap = false;
            if (mExpanded && Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx)) {
                mConsumed = true;
                if (dy > 3f * mTouchSlop && mScroll == 0f) {
                    setExpanded(false);
                } else {
                    mScroll = Math.max(0f, Math.min(mMaxScroll,
                            mScroll + mLastY - event.getY()));
                    mHost.requestChildrenUpdate();
                }
            }
            mLastY = event.getY();
        }
        boolean consumed = mConsumed;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && !mExpanded && mTap && mConsumed) setExpanded(true);
            mTracking = false;
            if (mHost.getParent() != null) {
                mHost.getParent().requestDisallowInterceptTouchEvent(false);
            }
        }
        return consumed;
    }

    boolean shouldCancelChildTouch() {
        if (!mConsumed || mCancelledChild) return false;
        mCancelledChild = true;
        return true;
    }

    void addAccessibilityAction(AccessibilityNodeInfo info) {
        if (!mActive) return;
        info.addAction(mExpanded ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
    }

    boolean performAccessibilityAction(int action) {
        if (!mActive) return false;
        if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
            setExpanded(true);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
            setExpanded(false);
            return true;
        }
        return false;
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }
}
