/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package android.app;

import android.annotation.IntDef;
import android.annotation.FlaggedApi;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Uncached reported state; keep this out of actual access checks. @hide */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@FlaggedApi(Flags.FLAG_PETAL_REPORTED_STATE_OVERRIDES)
public final class PetalStateOverrides {
    public static final int BLUETOOTH = 1;
    public static final int LOCATION = 2;
    public static final int BATTERY_OPTIMIZATION = 3;
    public static final int BACKGROUND = 4;
    public static final int STANDBY = 5;
    public static final int LOCATION_PERMISSION = 6;

    /** @hide */
    @IntDef({BLUETOOTH, LOCATION, BATTERY_OPTIMIZATION, BACKGROUND, STANDBY,
            LOCATION_PERMISSION})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    private PetalStateOverrides() {}

    /** Returns the calling app's current presentation override for its own user. */
    @RequiresNoPermission
    public static boolean isEnabled(@State int state) {
        return isEnabledForPackage(state, null, UserHandle.myUserId());
    }

    /** Self-query only; null picks the caller's sole package. */
    @RequiresNoPermission
    public static boolean isEnabledForPackage(@State int state, @Nullable String packageName,
            int userId) {
        // Also protects system processes serving an app's Binder transaction from applying a lie.
        if (!Process.isApplicationUid(Process.myUid())) return false;
        if (!Flags.petalReportedStateOverrides()) return false;
        try {
            return ActivityManager.getService().isPetalStateOverrideEnabled(
                    state, packageName, userId);
        } catch (RemoteException | RuntimeException e) {
            return false;
        }
    }
}
