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
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.VibrationEffect;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;

// Hosts the petalOS volume overlay and power menu as full-screen, bezel-anchored
public class PetalOverlayHost {

    private static final long VOLUME_DISMISS_DELAY_MS = 1600L;
    private static final long DISMISS_TEARDOWN_MS = 900L;

    /** Notified on every power-menu show/hide transition, whatever caused it. */
    public interface OnPowerMenuVisibilityListener {
        void onPowerMenuVisibilityChanged(boolean showing);
    }

    /** Notified as the user scrubs the volume HUD (fraction 0..1). */
    public interface OnVolumeScrubListener {
        void onVolumeScrub(float fraction);
        void onVolumeScrubEnd();
    }

    private final WindowManager mWindowManager;
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private PetalVolumeOverlayView mVolumeView;
    private PetalPowerMenuView mPowerView;
    private boolean mPowerMenuShowing = false;
    private OnPowerMenuVisibilityListener mPowerVisibilityListener;
    private OnVolumeScrubListener mVolumeScrubListener;
    private GlobalActionsManager mGlobalActionsManager;
    private int mLastVolumeLevel = Integer.MIN_VALUE;

    private final Runnable mVolumeDismissRunnable = this::dismissVolume;

    private boolean mWatchingDisplay;
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (mContext.getDisplay() == null
                            || mContext.getDisplay().getDisplayId() != displayId) return;
                    if (mVolumeView != null) {
                        mVolumeView.refreshRotation();
                        mWindowManager.updateViewLayout(mVolumeView, volumeLayoutParams(false));
                    }
                    if (mPowerView != null) {
                        mPowerView.refreshRotation();
                        mWindowManager.updateViewLayout(mPowerView, powerLayoutParams(false));
                    }
                }
            };

    public PetalOverlayHost(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    private void watchDisplay() {
        if (!mWatchingDisplay) {
            mContext.getSystemService(DisplayManager.class)
                    .registerDisplayListener(mDisplayListener, mHandler);
            mWatchingDisplay = true;
        }
    }

    private void stopWatchingDisplayIfHidden() {
        if (mWatchingDisplay && mVolumeView == null && mPowerView == null) {
            mContext.getSystemService(DisplayManager.class)
                    .unregisterDisplayListener(mDisplayListener);
            mWatchingDisplay = false;
        }
    }

    public boolean isPowerMenuShowing() {
        return mPowerMenuShowing;
    }

    /** True while the volume overlay window is up (not during teardown). */
    public boolean isVolumeShowing() {
        return mVolumeView != null;
    }

    /** Registers the listener that observes power-menu show/hide transitions. */
    public void setOnPowerMenuVisibilityListener(OnPowerMenuVisibilityListener listener) {
        mPowerVisibilityListener = listener;
    }

    /** Registers the listener that receives volume-scrub updates. */
    public void setOnVolumeScrubListener(OnVolumeScrubListener listener) {
        mVolumeScrubListener = listener;
    }

    /** Supplies the framework-side manager that performs shutdown/reboot. */
    public void setGlobalActionsManager(GlobalActionsManager manager) {
        mGlobalActionsManager = manager;
    }

    /** Show (or refresh) the volume overlay with the given level and mute state. */
    public void showVolume(int level, int levelMin, int levelMax, boolean muted, boolean shake) {
        watchDisplay();
        if (mVolumeView == null) {
            mVolumeView = new PetalVolumeOverlayView(mContext);
            mVolumeView.setOnDismissListener(() -> dismissVolume());
            mVolumeView.setOnScrubListener(new PetalVolumeOverlayView.OnScrubListener() {
                @Override
                public void onScrub(float fraction) {
                    // Keep the HUD up while the user is dragging.
                    mHandler.removeCallbacks(mVolumeDismissRunnable);
                    if (mVolumeScrubListener != null) {
                        mVolumeScrubListener.onVolumeScrub(fraction);
                    }
                }

                @Override
                public void onScrubEnd() {
                    if (mVolumeScrubListener != null) {
                        mVolumeScrubListener.onVolumeScrubEnd();
                    }
                    mHandler.removeCallbacks(mVolumeDismissRunnable);
                    mHandler.postDelayed(mVolumeDismissRunnable, volumeDismissDelay());
                }
            });
            mWindowManager.addView(mVolumeView, volumeLayoutParams(false));
        } else {
            // Re-position the HUD if the display rotated between volume adjustments.
            WindowManager.LayoutParams desired = volumeLayoutParams(false);
            WindowManager.LayoutParams current =
                    (WindowManager.LayoutParams) mVolumeView.getLayoutParams();
            if (current.x != desired.x || current.y != desired.y
                    || current.width != desired.width || current.height != desired.height) {
                current.x = desired.x;
                current.y = desired.y;
                current.width = desired.width;
                current.height = desired.height;
                try {
                    mWindowManager.updateViewLayout(mVolumeView, current);
                } catch (IllegalArgumentException ignored) {
                    // view not attached yet
                }
            }
        }
        if (level != mLastVolumeLevel) {
            mLastVolumeLevel = level;
            PetalUtils.vibrate(mContext, VibrationEffect.EFFECT_TICK);
        }
        int range = levelMax - levelMin;
        float fraction = range > 0 ? (float) (level - levelMin) / (float) range : 0f;
        mVolumeView.setVolume(fraction, muted, range);
        mVolumeView.show();
        if (shake) {
            mVolumeView.shake();
        }

        // Re-arm the auto-dismiss on every adjustment so the overlay stays up while the
        mHandler.removeCallbacks(mVolumeDismissRunnable);
        mHandler.postDelayed(mVolumeDismissRunnable, volumeDismissDelay());
    }

    private int volumeDismissDelay() {
        AccessibilityManager manager = mContext.getSystemService(AccessibilityManager.class);
        return manager.getRecommendedTimeoutMillis((int) VOLUME_DISMISS_DELAY_MS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    public void dismissVolume() {
        mHandler.removeCallbacks(mVolumeDismissRunnable);
        if (mVolumeView != null) {
            PetalUtils.vibrate(mContext, VibrationEffect.EFFECT_CLICK);
            mVolumeView.dismiss();
            // Restore touch pass-through immediately while the out animation plays.
            mWindowManager.updateViewLayout(mVolumeView, volumeLayoutParams(true));
            PetalVolumeOverlayView v = mVolumeView;
            mVolumeView = null;
            v.postDelayed(() -> {
                try {
                    mWindowManager.removeView(v);
                } catch (IllegalArgumentException ignored) {
                    // already removed
                }
            }, DISMISS_TEARDOWN_MS);
        }
        stopWatchingDisplayIfHidden();
    }

    /** Show the power menu. */
    public void showPowerMenu() {
        watchDisplay();
        if (mPowerView != null) {
            return;
        }
        mPowerView = new PetalPowerMenuView(mContext);
        mPowerView.setOnDismissListener(() -> dismissPowerMenu());
        mPowerView.setOnArmListener((index, armed) -> {
            PetalUtils.vibrate(mContext, armed
                    ? VibrationEffect.EFFECT_HEAVY_CLICK
                    : VibrationEffect.EFFECT_CLICK);
        });
        mPowerView.setOnOptionSelectedListener(new PetalPowerMenuView.OnOptionSelectedListener() {
            @Override public void onPowerOff() {
                dismissPowerMenu();
                if (mGlobalActionsManager != null) {
                    mGlobalActionsManager.shutdown();
                }
            }
            @Override public void onReboot() {
                dismissPowerMenu();
                if (mGlobalActionsManager != null) {
                    mGlobalActionsManager.reboot(false, null);
                }
            }
            @Override public void onRestartSystemUi() {
                dismissPowerMenu();
                Process.killProcess(Process.myPid());
            }
            @Override public void onPowerOffLongPress() {
                dismissPowerMenu();
                if (mGlobalActionsManager != null) {
                    mGlobalActionsManager.reboot(false, PowerManager.REBOOT_BOOTLOADER);
                }
            }
            @Override public void onRebootLongPress() {
                dismissPowerMenu();
                if (mGlobalActionsManager != null) {
                    mGlobalActionsManager.reboot(false, PowerManager.REBOOT_RECOVERY);
                }
            }
        });
        mWindowManager.addView(mPowerView, powerLayoutParams(false));
        mPowerView.show();
        PetalUtils.vibrate(mContext, VibrationEffect.EFFECT_CLICK);
        mPowerMenuShowing = true;
        if (mPowerVisibilityListener != null) {
            mPowerVisibilityListener.onPowerMenuVisibilityChanged(true);
        }
    }

    public void dismissPowerMenu() {
        mPowerMenuShowing = false;
        if (mPowerVisibilityListener != null) {
            mPowerVisibilityListener.onPowerMenuVisibilityChanged(false);
        }
        if (mPowerView != null) {
            PetalUtils.vibrate(mContext, VibrationEffect.EFFECT_CLICK);
            mPowerView.dismiss();
            // Restore touch pass-through immediately while the out animation plays.
            mWindowManager.updateViewLayout(mPowerView, powerLayoutParams(true));
            PetalPowerMenuView v = mPowerView;
            mPowerView = null;
            v.postDelayed(() -> {
                try {
                    mWindowManager.removeView(v);
                } catch (IllegalArgumentException ignored) {
                    // already removed
                }
            }, DISMISS_TEARDOWN_MS);
        }
        stopWatchingDisplayIfHidden();
    }

    private WindowManager.LayoutParams volumeLayoutParams(boolean notTouchable) {
        // petalOS bug fix: the volume HUD window used to be MATCH_PARENT touchable, which
        final float density = mContext.getResources().getDisplayMetrics().density;
        final int depth = PetalVolumeOverlayView.hudDepthPx(density);
        final int length = PetalVolumeOverlayView.hudLengthPx(density);

        final boolean edgeLeft = PetalUiConfig.isVolumeEdgeLeft(mContext);
        final int rotation = mContext.getDisplay() != null
                ? mContext.getDisplay().getRotation()
                : android.view.Surface.ROTATION_0;
        final boolean landscape = PetalUtils.isLandscapeRotation(rotation);
        final int screenW = mContext.getResources().getDisplayMetrics().widthPixels;
        final int screenH = mContext.getResources().getDisplayMetrics().heightPixels;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                landscape ? length : depth,
                landscape ? depth : length,
                WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | (notTouchable ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE : 0),
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        lp.setTitle("PetalOSVolumeHud");
        lp.setFitInsetsTypes(0);
        // petalOS bug fix: without the trusted-overlay input privilege the HUD's drag stream can
        lp.setTrustedOverlay();

        final float anchorFraction = PetalUiConfig.getVolumeAnchorFraction(mContext);
        if (landscape) {
            float screenAnchor = rotation == android.view.Surface.ROTATION_90
                    ? anchorFraction : 1f - anchorFraction;
            lp.x = clamp(Math.round(screenW * screenAnchor - length / 2f), 0,
                    Math.max(0, screenW - length));
            lp.y = PetalUtils.edgeIsTopInLandscape(rotation, edgeLeft) ? 0 : screenH - depth;
        } else {
            boolean upsideDown = rotation == android.view.Surface.ROTATION_180;
            float screenAnchor = upsideDown ? 1f - anchorFraction : anchorFraction;
            lp.y = clamp(Math.round(screenH * screenAnchor - length / 2f), 0,
                    Math.max(0, screenH - length));
            lp.x = edgeLeft != upsideDown ? 0 : screenW - depth;
        }
        return lp;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private WindowManager.LayoutParams powerLayoutParams(boolean notTouchable) {
        // TYPE_STATUS_BAR_SUB_PANEL layers above the keyguard, matching the stock
        return baseLayoutParams(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL, notTouchable);
    }

    private WindowManager.LayoutParams baseLayoutParams(int type, boolean notTouchable) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | (notTouchable ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE : 0)
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.setTrustedOverlay();
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        lp.setTitle("PetalOSSystemDialog");
        lp.setFitInsetsTypes(0);
        return lp;
    }
}
