/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.server.pm;

import android.content.pm.SigningDetails;
import android.os.Bundle;
import com.android.server.pm.pkg.AndroidPackage;

// only the inert ROM shells get this escape hatch, never a running provider
final class PetalServicesStubs {
    private static final String MARKER = "org.petalos.services.PLACEHOLDER";
    private static final String[] GOOGLE_CERTS = {
            "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053",
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
            "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00",
    };

    private PetalServicesStubs() {}

    static boolean canReplace(PackageSetting setting, SigningDetails signer) {
        if (setting == null || !setting.isSystem() || setting.isPrivileged()
                || setting.isUpdatedSystemApp() || setting.hasSharedUser()) return false;
        return isPlaceholder(setting.getPkg()) && isProviderSigned(setting.getPackageName(), signer);
    }

    static boolean isPlaceholder(AndroidPackage pkg) {
        if (pkg == null || pkg.getLongVersionCode() != 1 || pkg.getSharedUserId() != null) {
            return false;
        }
        String module;
        switch (pkg.getPackageName()) {
            case "com.google.android.gms": module = "PetalGmsStub"; break;
            case "com.android.vending": module = "PetalPlayStoreStub"; break;
            default: return false;
        }
        String path = pkg.getBaseApkPath();
        if (!path.equals("/product/app/" + module + "/" + module + ".apk")
                && !path.equals("/system/product/app/" + module + "/" + module + ".apk")) {
            return false;
        }
        Bundle metadata = pkg.getMetaData();
        return metadata != null && metadata.getBoolean(MARKER, false);
    }

    private static boolean isProviderSigned(String packageName, SigningDetails signer) {
        if (signer == null || signer == SigningDetails.UNKNOWN) return false;
        if (ComputerEngine.isOfficialMicrogSignature(signer)) return true;
        if ("com.google.android.gms".equals(packageName) && signer.hasSha256Certificate(
                libcore.util.HexEncoding.decode(
                        "5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402"))) {
            return true;
        }
        for (String cert : GOOGLE_CERTS) {
            if (signer.hasSha256Certificate(libcore.util.HexEncoding.decode(cert))) return true;
        }
        return false;
    }
}
