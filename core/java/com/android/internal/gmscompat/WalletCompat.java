/* SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.gmscompat;

import android.Manifest;
import android.app.compat.gms.GmsCompat;
import android.os.Binder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;

/**
 * Experimental, cooperative serial compatibility for Wallet's DroidGuard initialization.
 * This is not a security boundary between code sharing the GMS UID and does not grant
 * or restrict Android permissions. In particular, it does not scope an existing
 * READ_PRIVILEGED_PHONE_STATE grant. Do not use it as authorization in a system service.
 */
public final class WalletCompat {
    private static final String TAG = "GmcWalletCompat";
    static final String HANDLE_DESCRIPTOR =
            "com.google.android.gms.droidguard.internal.IDroidGuardHandle";
    static final String FLOW = "tapandpay_attestation";
    // Wire format and synchronous identifier collection verified against this APK and live trace.
    public static final long SUPPORTED_GMS_VERSION = 263234035L;
    private static final ThreadLocal<Boolean> serialScope = new ThreadLocal<>();

    /** Called only at an incoming Binder transaction boundary in sandboxed GMS. */
    public static boolean beginTransaction(Binder binder, int code, Parcel data, int callingUid) {
        boolean previous = Boolean.TRUE.equals(serialScope.get());
        // Mask outer scopes for reentrant incoming calls, including unrelated transactions.
        serialScope.remove();
        GmsCompatConfig config = GmsHooks.config();
        if (!GmsCompat.isEnabled() || !GmsCompat.isGmsCore() || callingUid != Process.myUid()
                || config == null || !config.walletAttestationSerialAccess
                || GmsCompat.appContext().getApplicationInfo().longVersionCode
                        != SUPPORTED_GMS_VERSION
                || code != 5 || !HANDLE_DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
            return previous;
        }
        if (isWalletInitialization(code, data)) serialScope.set(true);
        return previous;
    }

    static boolean isWalletInitialization(int code, Parcel data) {
        if (code != 5) return false;
        final int position = data.dataPosition();
        try {
            data.enforceInterface(HANDLE_DESCRIPTOR);
            return FLOW.equals(data.readString());
        } catch (RuntimeException e) {
            // Leave invalid transactions to the actual service; never grant on parse failure.
            return false;
        } finally {
            data.setDataPosition(position);
        }
    }

    /** Must run in finally, even when the service throws. */
    public static void endTransaction(boolean previous) {
        if (previous) serialScope.set(true);
        else serialScope.remove();
    }

    public static boolean allowRealSerial() {
        GmsCompatConfig config = GmsHooks.config();
        if (!GmsCompat.isEnabled() || !GmsCompat.isGmsCore()
                || !Boolean.TRUE.equals(serialScope.get())
                || config == null || !config.walletAttestationSerialAccess
                || !GmsCompat.hasPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)) {
            return false;
        }
        // Never log the serial, SSAID, challenge, or attestation output.
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Using Android serial service for Wallet attestation initialization");
        }
        return true;
    }

    private WalletCompat() {}
}
