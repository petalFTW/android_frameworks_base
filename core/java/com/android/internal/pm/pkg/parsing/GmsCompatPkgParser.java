package com.android.internal.pm.pkg.parsing;

import android.Manifest;
import android.app.compat.gms.GmsCompat;
import android.content.pm.ServiceInfo;
import android.ext.PackageId;
import android.os.Bundle;

import com.android.internal.gmscompat.GmsCompatApp;
import com.android.internal.gmscompat.client.GmsCompatClientService;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.component.ParsedServiceImpl;
import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal parse-time injection of {@link GmsCompatClientService} into GMS packages and their
 * clients. Kept deliberately small (no full parse-hooks registry); this is the only component
 * that must be injected at parse time.
 */
public final class GmsCompatPkgParser {
    private GmsCompatPkgParser() {}

    public static ParsedService maybeCreateClientService(ParsingPackage pkg) {
        Bundle metadata = pkg.getMetaData();
        boolean isGmsCoreClient = metadata != null
                && metadata.containsKey("com.google.android.gms.version");

        if (!isGmsCoreClient) {
            return null;
        }

        ParsedServiceImpl s = createService(pkg, GmsCompatClientService.class.getName());
        s.setPermission(GmsCompatApp.SIGNATURE_PROTECTED_PERMISSION);

        String pkgName = pkg.getPackageName();
        if (GmsCompat.canBeEnabledFor(pkgName)) {
            // Use a separate process to avoid deadlocks in early process init.
            s.setProcessName(GmsCompat.gmsCompatProcessNameForPackage(pkgName));
        }

        return s;
    }

    private static ParsedServiceImpl createService(ParsingPackage pkg, String className) {
        var s = new ParsedServiceImpl();
        s.setPackageName(pkg.getPackageName());
        s.setName(className);
        s.setProcessName(pkg.getProcessName());
        s.setDirectBootAware(pkg.isPartiallyDirectBootAware());
        s.setExported(true);
        return s;
    }

    /**
     * Rewrite {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED} to
     * {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_SPECIAL_USE} for sandboxed GMS packages.
     * The systemExempted type requires privileged permissions that an unprivileged sandboxed
     * GMS app doesn't hold, while specialUse only needs the normal FOREGROUND_SERVICE_SPECIAL_USE
     * permission that {@link #extraUsesPermissions} injects.
     */
    public static void amendParsedService(String pkgName, ParsedServiceImpl s) {
        if (!GmsCompat.canBeEnabledFor(pkgName)) {
            return;
        }

        if (s.getForegroundServiceType() == ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) {
            s.setForegroundServiceType(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }
    }

    /**
     * Additional uses-permissions injected into sandboxed GMS packages at parse time.
     */
    public static List<ParsedUsesPermissionImpl> extraUsesPermissions(String pkgName) {
        if (!GmsCompat.canBeEnabledFor(pkgName)) {
            return null;
        }

        var res = new ArrayList<ParsedUsesPermissionImpl>();
        res.add(createUsesPerm(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE));

        if (PackageId.PLAY_STORE_NAME.equals(pkgName)) {
            res.add(createUsesPerm(Manifest.permission.REQUEST_INSTALL_PACKAGES));
            res.add(createUsesPerm(Manifest.permission.REQUEST_DELETE_PACKAGES));
            res.add(createUsesPerm(Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION));
        } else if (PackageId.GMS_CORE_NAME.equals(pkgName)) {
            res.add(createUsesPerm(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS));
            res.add(createUsesPerm(Manifest.permission.READ_PHONE_NUMBERS));
        }

        return res;
    }

    private static ParsedUsesPermissionImpl createUsesPerm(String perm) {
        return new ParsedUsesPermissionImpl(perm, 0, Collections.emptySet());
    }

    /**
     * Skip permission definitions that are already declared by the preinstalled GmsCompat app.
     * These permissions were moved out of GSF/GmsCore into the GmsCompat app; if a stock GSF or
     * GmsCore APK redeclares them, the install fails with INSTALL_FAILED_DUPLICATE_PERMISSION.
     */
    public static boolean shouldSkipPermissionDefinition(String pkgName, ParsedPermission p) {
        String name = p.getName();
        switch (name) {
            // These permissions are declared in GmsCompat app instead. They were moved there
            // because of an issue with permissions that have "normal" protectionLevel. If the
            // app that declares a "normal" permission is installed after an app that requests
            // that permission, the permission will not be granted. GmsCompat app is a preinstalled
            // app, it's always present.
            case "com.google.android.c2dm.permission.RECEIVE":
            case "com.google.android.providers.gsf.permission.READ_GSERVICES":
            // This permission is declared in GSF on regular Android. It was moved to GmsCompat app
            // to avoid the need to install GSF, which misbehaves on SDK 35+ due to signature
            // mismatch between itself and GmsCore (GSF and GmsCore use a sharedUid on regular
            // Android).
            case "com.google.android.c2dm.permission.SEND":
                return PackageId.GMS_CORE_NAME.equals(pkgName)
                        || PackageId.GSF_NAME.equals(pkgName);
            case "androidx.core.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION":
                // GSF declares this with protectionLevel="signature", which conflicts with other
                // preinstalled apps that declare the same permission. It isn't used for anything,
                // so removing it is safe.
                return PackageId.GSF_NAME.equals(pkgName);
            default:
                return false;
        }
    }
}
