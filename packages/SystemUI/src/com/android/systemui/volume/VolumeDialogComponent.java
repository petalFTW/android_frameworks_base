/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.VolumePolicy;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.WindowManager.LayoutParams;

import com.android.internal.R;
import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.petalos.PetalOverlayHost;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.tuner.TunerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Implementation of VolumeComponent backed by the new volume dialog.
 */
@SysUISingleton
public class VolumeDialogComponent implements VolumeComponent, TunerService.Tunable,
        VolumeDialogControllerImpl.UserActivityListener{

    public static final String VOLUME_DOWN_SILENT = "sysui_volume_down_silent";
    public static final String VOLUME_UP_SILENT = "sysui_volume_up_silent";
    public static final String VOLUME_SILENT_DO_NOT_DISTURB = "sysui_do_not_disturb";

    private final boolean mDefaultVolumeDownToEnterSilent;
    public final boolean mDefaultVolumeUpToExitSilent;
    public static final boolean DEFAULT_DO_NOT_DISTURB_WHEN_SILENT = false;

    private static final Intent ZEN_SETTINGS =
            new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);
    private static final Intent ZEN_PRIORITY_SETTINGS =
            new Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS);

    protected final Context mContext;
    private final VolumeDialogControllerImpl mController;
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_LOCALE
            | ActivityInfo.CONFIG_ASSETS_PATHS | ActivityInfo.CONFIG_UI_MODE);
    private final KeyguardViewMediator mKeyguardViewMediator;
    private final ActivityStarter mActivityStarter;
    private final PetalOverlayHost mPetalHost;
    private VolumeDialog mDialog;
    private VolumePolicy mVolumePolicy;
    private VolumeDialogController.State mLastState;
    private float mLastShownFraction = -1f;
    private boolean mHasShownVolume = false;

    @Inject
    public VolumeDialogComponent(
            Context context,
            KeyguardViewMediator keyguardViewMediator,
            ActivityStarter activityStarter,
            VolumeDialogControllerImpl volumeDialogController,
            DemoModeController demoModeController,
            PluginDependencyProvider pluginDependencyProvider,
            ExtensionController extensionController,
            TunerService tunerService,
            VolumeDialog volumeDialog) {
        mContext = context;
        mKeyguardViewMediator = keyguardViewMediator;
        mActivityStarter = activityStarter;
        mController = volumeDialogController;
        mController.setUserActivityListener(this);
        // petalOS: drive the bezel-anchored volume overlay from the controller's
        // show/state/dismiss callbacks instead of the stock volume panel (whose
        // show path is suppressed in VolumeDialogImpl#showH).
        mPetalHost = new PetalOverlayHost(context);
        // petalOS: scrubbing the volume HUD sets the stream level directly.
        mPetalHost.setOnVolumeScrubListener(new PetalOverlayHost.OnVolumeScrubListener() {
            @Override
            public void onVolumeScrub(float fraction) {
                if (mLastState == null) return;
                VolumeDialogController.StreamState ss =
                        mLastState.states.get(mLastState.activeStream);
                if (ss == null) return;
                int level = ss.levelMin + Math.round(fraction * (ss.levelMax - ss.levelMin));
                level = Math.max(ss.levelMin, Math.min(ss.levelMax, level));
                mController.setStreamVolume(mLastState.activeStream, level, true);
            }

            @Override
            public void onVolumeScrubEnd() {
                // no-op; the host re-arms the auto-dismiss timer.
            }
        });
        mController.addCallback(mPetalVolumeCallbacks, new Handler(context.getMainLooper()));
        // Allow plugins to reference the VolumeDialogController.
        pluginDependencyProvider.allowPluginDependency(VolumeDialogController.class);
        extensionController.newExtension(VolumeDialog.class)
                .withPlugin(VolumeDialog.class)
                .withDefault(() -> volumeDialog)
                .withCallback(dialog -> {
                    if (mDialog != null) {
                        mDialog.destroy();
                    }
                    mDialog = dialog;
                    mDialog.init(LayoutParams.TYPE_VOLUME_OVERLAY, mVolumeDialogCallback);
                }).build();


        mDefaultVolumeDownToEnterSilent = mContext.getResources()
                .getBoolean(R.bool.config_volume_down_to_enter_silent);
        mDefaultVolumeUpToExitSilent = mContext.getResources()
                .getBoolean(R.bool.config_volume_up_to_exit_silent);

        mVolumePolicy = new VolumePolicy(
                mDefaultVolumeDownToEnterSilent,  // volumeDownToEnterSilent
                mDefaultVolumeUpToExitSilent,  // volumeUpToExitSilent
                DEFAULT_DO_NOT_DISTURB_WHEN_SILENT,  // doNotDisturbWhenSilent
                400    // vibrateToSilentDebounce
        );

        applyConfiguration();

        tunerService.addTunable(this, VOLUME_DOWN_SILENT, VOLUME_UP_SILENT,
                VOLUME_SILENT_DO_NOT_DISTURB);
        demoModeController.addCallback(this);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        boolean volumeDownToEnterSilent = mVolumePolicy.volumeDownToEnterSilent;
        boolean volumeUpToExitSilent = mVolumePolicy.volumeUpToExitSilent;
        boolean doNotDisturbWhenSilent = mVolumePolicy.doNotDisturbWhenSilent;

        if (VOLUME_DOWN_SILENT.equals(key)) {
            volumeDownToEnterSilent =
                TunerService.parseIntegerSwitch(newValue, mDefaultVolumeDownToEnterSilent);
        } else if (VOLUME_UP_SILENT.equals(key)) {
            volumeUpToExitSilent =
                TunerService.parseIntegerSwitch(newValue, mDefaultVolumeUpToExitSilent);
        } else if (VOLUME_SILENT_DO_NOT_DISTURB.equals(key)) {
            doNotDisturbWhenSilent =
                TunerService.parseIntegerSwitch(newValue, DEFAULT_DO_NOT_DISTURB_WHEN_SILENT);
        }

        setVolumePolicy(volumeDownToEnterSilent, volumeUpToExitSilent, doNotDisturbWhenSilent,
                mVolumePolicy.vibrateToSilentDebounce);
    }

    private void setVolumePolicy(boolean volumeDownToEnterSilent, boolean volumeUpToExitSilent,
            boolean doNotDisturbWhenSilent, int vibrateToSilentDebounce) {
        mVolumePolicy = new VolumePolicy(volumeDownToEnterSilent, volumeUpToExitSilent,
                doNotDisturbWhenSilent, vibrateToSilentDebounce);
        mController.setVolumePolicy(mVolumePolicy);
    }

    void setEnableDialogs(boolean volumeUi, boolean safetyWarning) {
        mController.setEnableDialogs(volumeUi, safetyWarning);
    }

    @Override
    public void onUserActivity() {
        mKeyguardViewMediator.userActivity();
    }

    private void applyConfiguration() {
        mController.setVolumePolicy(mVolumePolicy);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mConfigChanges.applyNewConfig(mContext.getResources())) {
            mController.mCallbacks.onConfigurationChanged();
        }
    }

    @Override
    public void dismissNow() {
        mController.dismiss();
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        // noop
    }

    @Override
    public List<String> demoCommands() {
        List<String> s = new ArrayList<>();
        s.add(DemoMode.COMMAND_VOLUME);
        return s;
    }

    @Override
    public void register() {
        mController.register();
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
    }

    private void startSettings(Intent intent) {
        mActivityStarter.startActivity(intent, true /* onlyProvisioned */, true /* dismissShade */);
    }

    private final VolumeDialogImpl.Callback mVolumeDialogCallback = new VolumeDialogImpl.Callback() {
        @Override
        public void onZenSettingsClicked() {
            startSettings(ZEN_SETTINGS);
        }

        @Override
        public void onZenPrioritySettingsClicked() {
            startSettings(ZEN_PRIORITY_SETTINGS);
        }
    };

    private final VolumeDialogController.Callbacks mPetalVolumeCallbacks =
            new VolumeDialogController.Callbacks() {
        @Override
        public void onShowRequested(int reason, boolean keyguardLocked, int lockTaskModeState) {
            updateVolumeOverlay(true);
        }

        @Override
        public void onDismissRequested(int reason) {
            mPetalHost.dismissVolume();
        }

        @Override
        public void onStateChanged(VolumeDialogController.State state) {
            boolean wasShowing = mPetalHost.isVolumeShowing();
            mLastState = state;
            if (wasShowing) {
                // The overlay is up: push the fresh level/mute into it so the fill pill
                // tracks the volume keys. Without this the bar freezes on the value it
                // was opened with.
                updateVolumeOverlay(true);
            }
        }

        @Override
        public void onLayoutDirectionChanged(int layoutDirection) {
        }

        @Override
        public void onConfigurationChanged() {
        }

        @Override
        public void onShowVibrateHint() {
        }

        @Override
        public void onShowSilentHint() {
        }

        @Override
        public void onScreenOff() {
            mPetalHost.dismissVolume();
        }

        @Override
        public void onShowSafetyWarning(int flags) {
        }

        @Override
        public void onAccessibilityModeChanged(Boolean showA11yStream) {
        }

        @Override
        public void onCaptionComponentStateChanged(Boolean isComponentEnabled,
                Boolean fromTooltip) {
        }

        @Override
        public void onCaptionEnabledStateChanged(Boolean isEnabled, Boolean checkBeforeSwitch) {
        }

        @Override
        public void onShowCsdWarning(int csdWarning, int durationMs) {
        }

        @Override
        public void onVolumeChangedFromKey() {
        }
    };

    /**
     * Shows (or refreshes) the petal volume overlay from the latest controller state.
     * Re-arms the auto-dismiss, and fires the over-limit shake when a press did not
     * move the level while already parked at an end of the range.
     */
    private void updateVolumeOverlay(boolean allowShake) {
        if (mLastState == null) {
            return;
        }
        VolumeDialogController.StreamState streamState =
                mLastState.states.get(mLastState.activeStream);
        if (streamState == null) {
            return;
        }
        int range = streamState.levelMax - streamState.levelMin;
        float fraction = range > 0
                ? (float) (streamState.level - streamState.levelMin) / (float) range
                : 0f;
        fraction = Math.max(0f, Math.min(1f, fraction));
        boolean atLimit = fraction <= 0f || fraction >= 1f;
        boolean shake = allowShake && mHasShownVolume && atLimit
                && Math.abs(fraction - mLastShownFraction) < 1e-6f;
        mPetalHost.showVolume(streamState.level, streamState.levelMin,
                streamState.levelMax, streamState.muted, shake);
        mLastShownFraction = fraction;
        mHasShownVolume = true;
    }

}
