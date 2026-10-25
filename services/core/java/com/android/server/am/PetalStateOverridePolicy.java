/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.am;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.SparseArray;

import com.android.internal.app.PetalSpoofConfig;

/** Only consulted by the explicit presentation-policy Binder entry, never by enforcement. */
final class PetalStateOverridePolicy {
    private final Context mContext;
    private final Object mLock = new Object();
    private final SparseArray<PetalSpoofConfig> mConfigs = new SparseArray<>();
    private final SparseArray<String> mRawConfigs = new SparseArray<>();
    private final SparseArray<Context> mUserContexts = new SparseArray<>();

    private void clearCaches() {
        synchronized (mLock) {
            mConfigs.clear();
            mRawConfigs.clear();
            mUserContexts.clear();
        }
    }

    PetalStateOverridePolicy(Context context, Handler handler) {
        mContext = context;
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.PETAL_SPOOFED_PACKAGES), false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        clearCaches();
                        // wake PetalPower even if it got killed
                        context.sendBroadcastAsUser(new Intent(PetalSpoofConfig.ACTION_POLICY_CHANGED)
                                .setPackage("org.petalos.power"), UserHandle.ALL,
                                android.Manifest.permission.WRITE_SECURE_SETTINGS);
                    }
                }, UserHandle.USER_ALL);
        final IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                clearCaches();
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                        && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        && intent.getData() != null) {
                    final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    if (uid >= 0) {
                        try {
                            PetalSpoofConfig.remove(mContext.createContextAsUser(
                                    UserHandle.of(UserHandle.getUserId(uid)), 0),
                                    intent.getData().getSchemeSpecificPart());
                        } catch (RuntimeException e) {
                            // user vanished mid-cleanup, don't take the server down
                            android.util.Slog.w("PetalStateOverrides", "Package cleanup failed", e);
                        }
                    }
                }
            }
        }, UserHandle.ALL, packages, null, handler);
        context.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                clearCaches();
            }
        }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_REMOVED), null, handler);
    }

    int mode(int uid, String target, int state) {
        final int userId = UserHandle.getUserId(uid);
        final Context userContext;
        final PetalSpoofConfig config;
        synchronized (mLock) {
            Context cachedContext = mUserContexts.get(userId);
            if (cachedContext == null) {
                cachedContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
                mUserContexts.put(userId, cachedContext);
            }
            userContext = cachedContext;
            // generation cache catches edits before the observer wakes up
            final String raw = Settings.Secure.getStringForUser(userContext.getContentResolver(),
                    Settings.Secure.PETAL_SPOOFED_PACKAGES, userId);
            PetalSpoofConfig cached = mConfigs.get(userId);
            if (cached == null || !java.util.Objects.equals(raw, mRawConfigs.get(userId))) {
                cached = PetalSpoofConfig.parse(raw);
                mConfigs.put(userId, cached);
                mRawConfigs.put(userId, raw);
            }
            config = cached;
        }
        if (config.isEmpty()) return PetalSpoofConfig.OFF;
        final String[] names = userContext.getPackageManager().getPackagesForUid(uid);
        if (names == null || names.length != 1 || (target != null && !target.equals(names[0]))) {
            return PetalSpoofConfig.OFF;
        }
        final PetalSpoofConfig.Entry entry = config.get(names[0]);
        if (entry == null || entry.uid != uid || entry.mode(state) == PetalSpoofConfig.OFF) {
            return PetalSpoofConfig.OFF;
        }
        if (state == android.app.PetalStateOverrides.LOCATION_PERMISSION
                && !PetalSpoofConfig.canReportBackgroundLocation(userContext, names[0])) {
            return PetalSpoofConfig.OFF;
        }
        return PetalSpoofConfig.matches(userContext, names[0], entry)
                ? entry.mode(state) : PetalSpoofConfig.OFF;
    }
}
