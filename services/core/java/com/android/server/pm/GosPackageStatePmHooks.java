package com.android.server.pm;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.pm.GosPackageState;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.pm.PackageManagerShellCommand;
import com.android.server.pm.pkg.PackageStateInternal;

import java.io.File;

import static android.content.pm.GosPackageState.EDITOR_FLAG_KILL_UID_AFTER_APPLY;
import static android.content.pm.GosPackageState.EDITOR_FLAG_NOTIFY_UID_AFTER_APPLY;
import static android.content.pm.GosPackageState.NONE;
import static com.android.server.pm.GosPackageStateUtils.parseFlag;

public class GosPackageStatePmHooks {
    private static final String TAG = "GosPackageStatePmHooks";

    private final PackageManagerService pkgManager;
    private final GosPackageStateStore store;

    GosPackageStatePmHooks(PackageManagerService pkgManager) {
        this.pkgManager = pkgManager;
        this.store = new GosPackageStateStore(new File(
                Environment.getDataSystemDirectory(), "gos_pkg_state.xml"));
    }

    void init() {
        GosPackageStatePermissions.init(pkgManager);
    }

    @NonNull
    GosPackageState getUnfiltered(String packageName, int userId) {
        return store.get(packageName, userId);
    }

    @NonNull
    GosPackageState getFiltered(int callingUid, int callingPid, String packageName, int userId) {
        Computer pmComputer = pkgManager.snapshotComputer();
        PackageStateInternal packageState = pmComputer.getPackageStates().get(packageName);
        if (packageState == null) {
            // the package was likely racily uninstalled
            return NONE;
        }
        return getFiltered(pmComputer, packageState, store.get(packageName, userId),
                callingUid, callingPid, userId);
    }

    @NonNull
    private static GosPackageState getFiltered(Computer pmComputer,
                                      PackageStateInternal packageState, GosPackageState gosPs,
                                      int callingUid, int callingPid, int userId) {
        final int appId = packageState.getAppId();

        GosPackageStatePermission permission =
                GosPackageStatePermissions.get(callingUid, callingPid, appId, userId, false);
        if (permission == null) {
            return NONE;
        }
        return permission.filterRead(gosPs);
    }

    boolean set(final int callingUid, final int callingPid,
                       String packageName, int userId,
                       GosPackageState update, int editorFlags) {
        final int appId;
        final int uid;
        final GosPackageState updatedGosPs;

        final PackageManagerService pm = pkgManager;
        synchronized (pm.mLock) {
            PackageSetting packageSetting = pm.mSettings.getPackageLPr(packageName);
            if (packageSetting == null) {
                Slog.d(TAG, "set: no packageSetting for " + packageName);
                return false;
            }

            appId = packageSetting.getAppId();

            // Packages with this appId use the "android.uid.system" sharedUserId, which is
            // expensive to deal with due to the large number of packages that it includes. These
            // packages have no need for GosPackageState.
            if (appId == Process.SYSTEM_UID) {
                Slog.d(TAG, "set: appId of " + packageName + " == SYSTEM_UID");
                return false;
            }

            GosPackageStatePermission permission = GosPackageStatePermissions.get(
                    callingUid, callingPid, appId, userId, true);

            if (permission == null) {
                Slog.d(TAG, "no write permission");
                return false;
            }

            GosPackageState currentGosPs = store.get(packageName, userId);
            updatedGosPs = permission.filterWrite(currentGosPs, update);

            store.put(packageName, userId, updatedGosPs);

            uid = UserHandle.getUid(userId, appId);
        }
        Slog.i(TAG, "set: callingUid: " + callingUid + ", uid: " + uid
                + ", pkgName: " + packageName + ", value: " + updatedGosPs);

        if ((editorFlags & EDITOR_FLAG_KILL_UID_AFTER_APPLY) != 0) {
            final long token = Binder.clearCallingIdentity();
            try {
                ActivityManager.getService().killUid(appId, userId, "GosPackageStateChange");
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
        // EDITOR_FLAG_NOTIFY_UID_AFTER_APPLY is intentionally ignored: the live GosPackageState
        // change-callback path (dispatchGosPackageStateCallbacks) is not ported.
        return true;
    }

    /** @see PackageManagerService.IPackageManagerImpl#clearApplicationUserData */
    public static void onClearApplicationUserData(PackageManagerService pm, String packageName,
            int userId) {
        pm.gosPackageStatePmHooks.store.remove(packageName, userId);
    }

    static int runShellCommand(PackageManagerShellCommand cmd) {
        String packageName = cmd.getNextArgRequired();
        int userId = Integer.parseInt(cmd.getNextArgRequired());

        GosPackageState.Editor ed = GosPackageState.edit(packageName, userId);

        for (;;) {
            String arg = cmd.getNextArg();
            if (arg == null) {
                if (!ed.apply()) {
                    return 1;
                }
                return 0;
            }
            switch (arg) {
                case "add-flag", "clear-flag" ->
                    ed.setFlagState(parseFlag(cmd.getNextArgRequired()), "add-flag".equals(arg));
                case "add-package-flag", "clear-package-flag" ->
                    ed.setPackageFlagState(Integer.parseInt(cmd.getNextArgRequired()),
                            "add-package-flag".equals(arg));
                case "set-storage-scopes" ->
                    ed.setStorageScopes(getByteArrArg(cmd));
                case "set-contact-scopes" ->
                    ed.setContactScopes(getByteArrArg(cmd));
                case "set-kill-uid-after-apply" ->
                    ed.setKillUidAfterApply(Boolean.parseBoolean(cmd.getNextArgRequired()));
                default ->
                    throw new IllegalArgumentException(arg);
            }
        }
    }

    @Nullable
    private static byte[] getByteArrArg(ShellCommand cmd) {
        String s = cmd.getNextArgRequired();
        return "null".equals(s) ? null : libcore.util.HexEncoding.decode(s);
    }
}
