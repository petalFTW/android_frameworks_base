/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.view.WindowManager;

import com.android.systemui.Dependency;
import com.android.systemui.island.settings.IslandSettings;
import com.android.systemui.tuner.TunerService;

import org.petalos.config.PetalConfig;

import java.util.ArrayList;
import java.util.function.Consumer;

/** Main-thread, shared appearance state. No settings/provider calls in notification drawing. */
public final class NotificationStyleController implements TunerService.Tunable {
    private static NotificationStyleController sInstance;
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final PowerManager mPowerManager;
    private final ArrayList<Runnable> mListeners = new ArrayList<>();
    private int mStyle = PetalConfig.DEFAULT_NOTIFICATION_STYLE;
    private boolean mLegacyGlass = true;
    private boolean mBlurEnabled;
    private boolean mInteractive;
    private boolean mPowerSave;
    private final Consumer<Boolean> mBlurListener = enabled -> {
        mBlurEnabled = enabled;
        notifyChanged();
    };
    private final BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshPower();
            notifyChanged();
        }
    };

    private NotificationStyleController(Context context) {
        mContext = context.getApplicationContext();
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mPowerManager = mContext.getSystemService(PowerManager.class);
    }

    public static NotificationStyleController get(Context context) {
        if (sInstance == null) sInstance = new NotificationStyleController(context);
        return sInstance;
    }

    public void addListener(Runnable listener) {
        if (mListeners.contains(listener)) return;
        boolean start = mListeners.isEmpty();
        mListeners.add(listener);
        if (start) {
            refreshPower();
            mBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
            Dependency.get(TunerService.class).addTunable(this,
                    PetalConfig.KEY_NOTIFICATION_STYLE, IslandSettings.KEY_GLASS_NOTIFICATIONS);
            mWindowManager.addCrossWindowBlurEnabledListener(mContext.getMainExecutor(),
                    mBlurListener);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            mContext.registerReceiver(mPowerReceiver, filter);
        }
        listener.run();
    }

    public void removeListener(Runnable listener) {
        if (!mListeners.remove(listener) || !mListeners.isEmpty()) return;
        Dependency.get(TunerService.class).removeTunable(this);
        mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        mContext.unregisterReceiver(mPowerReceiver);
    }

    private void refreshPower() {
        mInteractive = mPowerManager.isInteractive();
        mPowerSave = mPowerManager.isPowerSaveMode();
    }

    @Override
    public void onTuningChanged(String key, String value) {
        if (PetalConfig.KEY_NOTIFICATION_STYLE.equals(key)) {
            mStyle = parseStyle(value);
        } else if (IslandSettings.KEY_GLASS_NOTIFICATIONS.equals(key)) {
            mLegacyGlass = !"0".equals(value);
        }
        notifyChanged();
    }

    // unset = silk now, garbage still falls back to the stock look
    static int parseStyle(String value) {
        if (value == null) return PetalConfig.DEFAULT_NOTIFICATION_STYLE;
        try {
            int style = Integer.parseInt(value);
            return style >= 0 && style <= 2 ? style : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void notifyChanged() {
        for (Runnable listener : new ArrayList<>(mListeners)) listener.run();
    }

    public int getStyle() { return mStyle; }
    public boolean hasFinish() { return mStyle != 0 || mLegacyGlass; }
    public boolean canRenderBackdrop() {
        return mStyle == PetalConfig.NOTIFICATION_STYLE_ISLAND_GLASS
                && mBlurEnabled && mInteractive && !mPowerSave;
    }
}
