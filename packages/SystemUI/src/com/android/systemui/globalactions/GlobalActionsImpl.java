/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.globalactions;

import static android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS;

import android.content.Context;
import android.util.Log;

import com.android.systemui.petalos.PetalOverlayHost;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

public class GlobalActionsImpl implements GlobalActions, CommandQueue.Callbacks {

    private static final String TAG = "PetalGlobalActions";

    private final Context mContext;
    private final KeyguardStateController mKeyguardStateController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final CommandQueue mCommandQueue;
    private final GlobalActionsDialogLite mGlobalActionsDialog;
    private final PetalOverlayHost mPetalHost;
    private boolean mDisabled;
    private ShutdownUi mShutdownUi;
    private ShadeController mShadeController;
    private GlobalActionsManager mManager;
    private boolean mReportedShown;

    @Inject
    public GlobalActionsImpl(Context context, CommandQueue commandQueue,
            GlobalActionsDialogLite globalActionsDialog,
            KeyguardStateController keyguardStateController,
            DeviceProvisionedController deviceProvisionedController,
            ShadeController shadeController,
            ShutdownUi shutdownUi) {
        mContext = context;
        mGlobalActionsDialog = globalActionsDialog;
        mPetalHost = new PetalOverlayHost(context);
        mKeyguardStateController = keyguardStateController;
        mDeviceProvisionedController = deviceProvisionedController;
        mCommandQueue = commandQueue;
        mCommandQueue.addCallback(this);
        mShutdownUi = shutdownUi;
        mShadeController = shadeController;
        mPetalHost.setOnPowerMenuVisibilityListener(showing -> {
            if (showing) {
                // Tell PhoneWindowManager we came up. Without this it fires a 5 s
                // watchdog (framework GlobalActions$mShowTimeout) that concludes
                // SystemUI failed and opens the legacy AOSP power menu on top.
                if (!mReportedShown && mManager != null) {
                    mReportedShown = true;
                    mManager.onGlobalActionsShown();
                }
            } else if (mReportedShown && mManager != null) {
                mReportedShown = false;
                mManager.onGlobalActionsHidden();
            }
        });
    }

    @Override
    public void destroy() {
        mCommandQueue.removeCallback(this);
        mGlobalActionsDialog.destroy();
        mPetalHost.dismissPowerMenu();
    }

    @Override
    public void showGlobalActions(GlobalActionsManager manager) {
        if (mDisabled) return;
        Log.d(TAG, "show");
        mManager = manager;
        mPetalHost.setGlobalActionsManager(manager);
        mPetalHost.showPowerMenu(); // idempotent when already up
    }

    @Override
    public void showShutdownUi(boolean isReboot, String reason, boolean rebootCustom) {
        mShutdownUi.showShutdownUi(isReboot, reason, rebootCustom);
        mShadeController.instantCollapseShade();
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_GLOBAL_ACTIONS) != 0;
        if (displayId != mContext.getDisplayId() || disabled == mDisabled) return;
        mDisabled = disabled;
        if (disabled) {
            Log.d(TAG, "hide (DISABLE2_GLOBAL_ACTIONS)");
            mPetalHost.dismissPowerMenu();
        }
    }
}
