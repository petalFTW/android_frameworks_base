package com.android.internal.pm.ext;

import android.content.pm.SigningDetails;
import android.ext.AppInfoExt;
import android.ext.AppInfoExtFlag;
import android.ext.PackageId;
import android.os.Bundle;
import android.util.Slog;

import com.android.internal.pm.parsing.pkg.PackageImpl;

import libcore.util.HexEncoding;

import static android.ext.PackageId.ANDROID_AUTO;
import static android.ext.PackageId.ANDROID_AUTO_NAME;
import static android.ext.PackageId.BUGLE;
import static android.ext.PackageId.BUGLE_NAME;
import static android.ext.PackageId.GMS_CORE;
import static android.ext.PackageId.GMS_CORE_NAME;
import static android.ext.PackageId.G_SEARCH_APP;
import static android.ext.PackageId.G_SEARCH_APP_NAME;
import static android.ext.PackageId.G_CARRIER_SETTINGS;
import static android.ext.PackageId.G_CARRIER_SETTINGS_NAME;
import static android.ext.PackageId.G_CAMERA;
import static android.ext.PackageId.G_CAMERA_NAME;
import static android.ext.PackageId.G_TEXT_TO_SPEECH;
import static android.ext.PackageId.G_TEXT_TO_SPEECH_NAME;
import static android.ext.PackageId.PLAY_STORE;
import static android.ext.PackageId.PLAY_STORE_NAME;
import static android.ext.PackageId.PIXEL_CAMERA_SERVICES;
import static android.ext.PackageId.PIXEL_CAMERA_SERVICES_NAME;
import static android.ext.PackageId.PIXEL_HEALTH;
import static android.ext.PackageId.PIXEL_HEALTH_NAME;
import static android.ext.PackageId.TYCHO;
import static android.ext.PackageId.TYCHO_NAME;
import static android.ext.PackageId.UNKNOWN;

/**
 * Lazy packageId assignment. Unlike the upstream parse-time approach, the packageId is computed
 * here, at ApplicationInfo-generation time, from cache-safe fields (package name, version code,
 * signing cert). This avoids the parse-time ext-field plumbing and its PackageCacher cache-boot
 * bug.
 */
public final class PackageIdResolver {
    private static final String TAG = "PackageIdResolver";

    private PackageIdResolver() {}

    public static AppInfoExt resolve(PackageImpl pkg) {
        int packageId = getPackageId(pkg);
        int flags = getExtFlags(pkg);

        if (packageId == UNKNOWN && flags == 0) {
            return AppInfoExt.DEFAULT;
        }
        return new AppInfoExt(packageId, flags, 0L);
    }

    private static int getExtFlags(PackageImpl pkg) {
        int flags = 0;

        Bundle metadata = pkg.getMetaData();
        if (metadata != null) {
            if (metadata.containsKey("com.google.android.gms.version")) {
                flags |= (1 << AppInfoExtFlag.HAS_GMSCORE_CLIENT_LIBRARY);
            }
        }

        return flags;
    }

    private static int getPackageId(PackageImpl pkg) {
        return switch (pkg.getPackageName()) {
            case GMS_CORE_NAME -> validate(pkg, GMS_CORE, 21_00_00_000L, mainGmsCerts());
            case PLAY_STORE_NAME -> validate(pkg, PLAY_STORE, 0L, mainGmsCerts());
            case G_SEARCH_APP_NAME -> validate(pkg, G_SEARCH_APP, 0L, mainGmsCerts());
            case G_CARRIER_SETTINGS_NAME -> validate(pkg, G_CARRIER_SETTINGS, 37L,
                    "c00409b6524658c2e8eb48975a5952959ea3707dd57bc50fd74d6249262f0e82");
            case G_CAMERA_NAME -> validate(pkg, G_CAMERA, 65820000L,
                    "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
                    "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00");
            case PIXEL_CAMERA_SERVICES_NAME -> validate(pkg, PIXEL_CAMERA_SERVICES, 124000L,
                    "226bb0439d6baeaa5a397c586e7031d8addfaec73c65be212f4a5dbfbf621b92");
            case ANDROID_AUTO_NAME -> validate(pkg, ANDROID_AUTO, 11_0_635014L,
                    "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295");
            case TYCHO_NAME -> validate(pkg, TYCHO, 3044673L,
                    "8c4e8f364cb132d41626f67749a6385605f51d365098c0cb5976eb5c1500a3ce");
            case G_TEXT_TO_SPEECH_NAME -> validate(pkg, G_TEXT_TO_SPEECH, 2104800_00L,
                    "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053");
            case PIXEL_HEALTH_NAME -> validate(pkg, PIXEL_HEALTH, 2224L,
                    "295499d8d0e93b7ed64f90e8cddffc12e3be23d8806f54e05d1abf415c37f5ba");
            case BUGLE_NAME -> validate(pkg, BUGLE, 273168063L,
                    "f7e73d4edc2b7f55eecd3f898d3cca7da9cbbf51694eba14f816e2eccf015035");

            default -> UNKNOWN;
        };
    }

    private static String[] mainGmsCerts() {
        return new String[] {
                // "bd32" SHA256withRSA issued in March 2020
                "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053",
                // "38d1" MD5withRSA issued in August 2008
                "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
                // "58e1" MD5withRSA issued in April 2008
                "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00",
        };
    }

    private static int validate(PackageImpl pkg, int packageId, long minVersionCode,
            String... validCertificatesSha256) {
        if (pkg.getLongVersionCode() < minVersionCode) {
            Slog.d(TAG, "minVersionCode check failed, pkgName " + pkg.getPackageName()
                    + ", pkgVersion: " + pkg.getLongVersionCode());
            return UNKNOWN;
        }

        SigningDetails signingDetails = pkg.getSigningDetails();
        if (signingDetails == SigningDetails.UNKNOWN) {
            return UNKNOWN;
        }

        for (String certSha256String : validCertificatesSha256) {
            byte[] validCertSha256 = HexEncoding.decode(certSha256String);
            if (signingDetails.hasSha256Certificate(validCertSha256)) {
                return packageId;
            }
        }

        Slog.d(TAG, "SigningDetails of " + pkg.getPackageName()
                + " don't contain any of known certificates");
        return UNKNOWN;
    }
}
