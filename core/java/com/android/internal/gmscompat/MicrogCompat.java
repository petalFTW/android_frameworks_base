/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.gmscompat;

import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.ext.PackageId;

import com.android.internal.util.HexDump;

/** @hide */
public final class MicrogCompat {
    public static final String SIGNER_SHA256 =
            "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165";
    private static final byte[] SIGNER_DIGEST = HexDump.hexStringToByteArray(SIGNER_SHA256);

    private MicrogCompat() {}

    public static boolean isTrustedGsfProxy(String packageName, SigningDetails signingDetails) {
        if (!PackageId.GSF_NAME.equals(packageName) || signingDetails == null
                || signingDetails == SigningDetails.UNKNOWN) return false;
        // Check the real APK signer, not the Google cert microG presents to clients.
        Signature[] signatures = signingDetails.getSignatures();
        return signatures != null && signatures.length == 1
                && signingDetails.hasSha256Certificate(SIGNER_DIGEST);
    }
}
