/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import android.content.pm.SigningDetails;
import android.content.pm.Signature;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/** Identity checks must remain valid even if lifecycle broadcasts were missed. */
public class PetalSpoofIdentityTest {
    private static final String NAME = "com.example.dashcam";
    private Context mContext;
    private PackageManager mPm;
    private PackageInfo mPackage;
    private PetalSpoofConfig.Entry mEntry;

    @Before
    public void setup() throws Exception {
        mContext = mock(Context.class);
        mPm = mock(PackageManager.class);
        when(mContext.getPackageManager()).thenReturn(mPm);
        mPackage = new PackageInfo();
        mPackage.packageName = NAME;
        mPackage.applicationInfo = new ApplicationInfo();
        mPackage.applicationInfo.packageName = NAME;
        mPackage.applicationInfo.uid = 10023;
        mPackage.firstInstallTime = 12345;
        mPackage.signingInfo = new SigningInfo(new SigningDetails(
                new Signature[] {new Signature("aa")}, 3, null, null));
        when(mPm.getPackageInfo(NAME, PackageManager.GET_SIGNING_CERTIFICATES)).thenReturn(mPackage);
        when(mPm.getPackagesForUid(10023)).thenReturn(new String[] {NAME});
        when(mPm.hasSigningCertificate(eq(NAME), any(byte[].class),
                eq(PackageManager.CERT_INPUT_SHA256))).thenReturn(true);
        final JSONObject entry = new JSONObject().put("uid", 10023).put("installed", 12345)
                .put("signer", "a".repeat(64)).put("idle_exempt", true)
                .put("modes", new JSONObject().put("bluetooth", 1));
        mEntry = PetalSpoofConfig.parse(new JSONObject().put("version", 1)
                .put("packages", new JSONObject().put(NAME, entry)).toString()).get(NAME);
    }

    @Test
    public void originalInstallMatches() { assertTrue(PetalSpoofConfig.matches(mContext, NAME, mEntry)); }

    @Test
    public void backgroundLocationNeedsRequestedPermissionAndForegroundGrant() throws Exception {
        when(mPm.getPackageInfo(NAME, PackageManager.GET_PERMISSIONS)).thenReturn(mPackage);
        when(mPm.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mPm.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertFalse(PetalSpoofConfig.canReportBackgroundLocation(mContext, NAME));
        mPackage.requestedPermissions = new String[] {
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION};
        assertFalse(PetalSpoofConfig.canReportBackgroundLocation(mContext, NAME));
        when(mPm.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, NAME))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        assertTrue(PetalSpoofConfig.canReportBackgroundLocation(mContext, NAME));
        mPackage.requestedPermissions = new String[] {
                android.Manifest.permission.ACCESS_COARSE_LOCATION};
        assertFalse(PetalSpoofConfig.canReportBackgroundLocation(mContext, NAME));
    }

    @Test
    public void revokedForegroundLocationStopsPermissionReport() throws Exception {
        when(mPm.getPackageInfo(NAME, PackageManager.GET_PERMISSIONS)).thenReturn(mPackage);
        mPackage.requestedPermissions = new String[] {
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION};
        when(mPm.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mPm.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, NAME))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        assertTrue(PetalSpoofConfig.canReportBackgroundLocation(mContext, NAME));
        when(mPm.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        assertFalse(PetalSpoofConfig.canReportBackgroundLocation(mContext, NAME));
    }

    @Test
    public void reinstallDoesNotInheritPolicy() {
        mPackage.firstInstallTime++;
        assertFalse(PetalSpoofConfig.matches(mContext, NAME, mEntry));
    }

    @Test
    public void uidReuseDoesNotInheritPolicy() {
        mPackage.applicationInfo.uid = 10024;
        when(mPm.getPackagesForUid(10024)).thenReturn(new String[] {NAME});
        assertFalse(PetalSpoofConfig.matches(mContext, NAME, mEntry));
    }

    @Test
    public void signerMismatchDoesNotInheritPolicy() {
        when(mPm.hasSigningCertificate(eq(NAME), any(byte[].class),
                eq(PackageManager.CERT_INPUT_SHA256))).thenReturn(false);
        assertFalse(PetalSpoofConfig.matches(mContext, NAME, mEntry));
    }

    @Test
    public void sharedUidIsUnsupported() {
        when(mPm.getPackagesForUid(10023)).thenReturn(new String[] {NAME, "com.example.other"});
        assertFalse(PetalSpoofConfig.matches(mContext, NAME, mEntry));
    }

    @Test
    public void systemTargetIsUnsupported() {
        mPackage.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        assertFalse(PetalSpoofConfig.matches(mContext, NAME, mEntry));
    }

    @Test(expected = PackageManager.NameNotFoundException.class)
    public void missingInstallIdentityCannotBeRegistered() throws Exception {
        mPackage.firstInstallTime = 0;
        PetalSpoofConfig.eligiblePackage(mContext, NAME);
    }

    @Test(expected = PackageManager.NameNotFoundException.class)
    public void differentSolePackageCannotBeRegisteredAsTarget() throws Exception {
        when(mPm.getPackagesForUid(10023)).thenReturn(new String[] {"com.example.other"});
        PetalSpoofConfig.eligiblePackage(mContext, NAME);
    }

    @Test(expected = PackageManager.NameNotFoundException.class)
    public void gmsTargetCannotSelfRegister() throws Exception {
        when(mPm.getPackageInfo("com.google.android.gms", PackageManager.GET_SIGNING_CERTIFICATES))
                .thenReturn(mPackage);
        when(mPm.getPackagesForUid(10023)).thenReturn(new String[] {"com.google.android.gms"});
        PetalSpoofConfig.eligiblePackage(mContext, "com.google.android.gms");
    }
}
