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
    // petalOS releasekey (vendor/petal-priv/keys/releasekey): trust a locally-built GSF proxy
    // signed with this ROM's own key the same as the official microG build.
    public static final String PETAL_SIGNER_SHA256 =
            "47b6410445edc223c4973833372ab13091100f0c4dad4cf53e7e430e1aaa5edc";
    private static final byte[] PETAL_SIGNER_DIGEST = HexDump.hexStringToByteArray(PETAL_SIGNER_SHA256);
    // petalOS platform key (vendor/petal-priv/keys/platform): a platform-signed microG build is trusted too.
    public static final String PETAL_PLATFORM_SHA256 =
            "3a7f40e62b50b5b5b9a64679aba8a86ed8cfa465138105bfda731fa7f64d3a3e";
    private static final byte[] PETAL_PLATFORM_DIGEST = HexDump.hexStringToByteArray(PETAL_PLATFORM_SHA256);

    private MicrogCompat() {}

    public static boolean isTrustedGsfProxy(String packageName, SigningDetails signingDetails) {
        if (!PackageId.GSF_NAME.equals(packageName) || signingDetails == null
                || signingDetails == SigningDetails.UNKNOWN) return false;
        // Check the real APK signer, not the Google cert microG presents to clients.
        Signature[] signatures = signingDetails.getSignatures();
        return signatures != null && signatures.length == 1
                && (signingDetails.hasSha256Certificate(SIGNER_DIGEST)
                        || signingDetails.hasSha256Certificate(PETAL_SIGNER_DIGEST)
                        || signingDetails.hasSha256Certificate(PETAL_PLATFORM_DIGEST));
    }
}
