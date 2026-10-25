/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.app;

import android.app.PetalStateOverrides;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import android.provider.Settings;
import android.util.ArrayMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

/** Shared spoof record, bounded so it can't eat the server. @hide */
public final class PetalSpoofConfig {
    public static final int OFF = 0;
    public static final int ALWAYS = 1;
    public static final int WHILE_ACTIVE = 2;
    public static final int WHILE_IN_RECENTS = 3;
    public static final int MAX_LENGTH = 65536;
    public static final int MAX_PACKAGES = 256;
    public static final String ACTION_POLICY_CHANGED = "org.petalos.power.action.SPOOF_POLICY_CHANGED";
    private static final String[] TOKENS = {
            "", "bluetooth", "location", "battery", "background", "standby",
            "location_permission"};

    /** Immutable identity-pinned package policy. */
    public static final class Entry {
        public final int uid;
        public final long firstInstallTime;
        public final String signer;
        public final boolean idleExempt;
        private final int[] mModes;

        private Entry(int uid, long firstInstallTime, String signer, boolean idleExempt,
                int[] modes) {
            this.uid = uid;
            this.firstInstallTime = firstInstallTime;
            this.signer = signer;
            this.idleExempt = idleExempt;
            mModes = modes.clone();
        }

        public int mode(int state) {
            return isState(state) ? mModes[state] : OFF;
        }

        private boolean empty() {
            if (idleExempt) return false;
            for (int mode : mModes) if (mode != OFF) return false;
            return true;
        }
    }

    private final ArrayMap<String, Entry> mEntries;

    private PetalSpoofConfig(ArrayMap<String, Entry> entries) {
        mEntries = entries;
    }

    public static boolean isState(int state) {
        return state >= PetalStateOverrides.BLUETOOTH
                && state <= PetalStateOverrides.LOCATION_PERMISSION;
    }

    public Entry get(String packageName) {
        return mEntries.get(packageName);
    }

    public boolean isEmpty() {
        return mEntries.isEmpty();
    }

    /** Invalid schemas fail closed, including invalid modes and identity fields. */
    public static PetalSpoofConfig parse(String raw) {
        final ArrayMap<String, Entry> entries = new ArrayMap<>();
        if (raw == null || raw.isEmpty()) return new PetalSpoofConfig(entries);
        try {
            if (raw.length() > MAX_LENGTH || !boundedNesting(raw)) {
                throw new JSONException("Oversized or excessively nested policy");
            }
            final JSONObject root = new JSONObject(raw);
            if (!Integer.valueOf(1).equals(root.get("version"))) {
                throw new JSONException("Unsupported version");
            }
            final JSONObject packages = root.getJSONObject("packages");
            if (packages.length() > MAX_PACKAGES) throw new JSONException("Too many packages");
            final Iterator<String> names = packages.keys();
            while (names.hasNext()) {
                final String name = names.next();
                if (name.length() > 255 || !name.matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")) {
                    throw new JSONException("Invalid package");
                }
                final JSONObject obj = packages.getJSONObject(name);
                if (!(obj.get("uid") instanceof Integer)
                        || !(obj.get("installed") instanceof Integer
                        || obj.get("installed") instanceof Long)) {
                    throw new JSONException("Invalid numeric identity");
                }
                final int uid = obj.getInt("uid");
                final long installed = obj.getLong("installed");
                final String signer = obj.getString("signer");
                if (!Process.isApplicationUid(uid) || installed <= 0
                        || !signer.matches("[0-9a-f]{64}")) {
                    throw new JSONException("Invalid identity");
                }
                final Object idle = obj.get("idle_exempt");
                if (!(idle instanceof Boolean)) throw new JSONException("Invalid exemption");
                final JSONObject modes = obj.getJSONObject("modes");
                final int[] values = new int[TOKENS.length];
                final Iterator<String> keys = modes.keys();
                while (keys.hasNext()) {
                    final String key = keys.next();
                    int state = 0;
                    for (int i = 1; i < TOKENS.length; i++) {
                        if (TOKENS[i].equals(key)) state = i;
                    }
                    final Object mode = modes.get(key);
                    if (state == 0 || !(mode instanceof Integer)
                             || (int) mode < OFF || (int) mode > WHILE_IN_RECENTS) {
                        throw new JSONException("Invalid mode");
                    }
                    values[state] = (int) mode;
                }
                entries.put(name, new Entry(uid, installed, signer, (boolean) idle, values));
            }
        } catch (JSONException | RuntimeException e) {
            entries.clear();
        }
        return new PetalSpoofConfig(entries);
    }

    // stop nested JSON from blowing the damn Binder stack
    private static boolean boundedNesting(String raw) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                if (++depth > 8) return false;
            } else if (c == '}' || c == ']') {
                if (--depth < 0) return false;
            }
        }
        return depth == 0 && quote == 0;
    }

    public static PetalSpoofConfig read(Context context) {
        return parse(Settings.Secure.getStringForUser(context.getContentResolver(),
                Settings.Secure.PETAL_SPOOFED_PACKAGES, context.getUserId()));
    }

    public static PackageInfo eligiblePackage(Context context, String packageName)
            throws PackageManager.NameNotFoundException {
        final PackageManager pm = context.getPackageManager();
        final PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        final ApplicationInfo app = info.applicationInfo;
        final String[] siblings = app == null ? null : pm.getPackagesForUid(app.uid);
        if (app == null || info.firstInstallTime <= 0 || !Process.isApplicationUid(app.uid) || app.isSystemApp()
                || app.isUpdatedSystemApp() || app.isSignedWithPlatformKey()
                || android.app.compat.gms.GmsCompat.isEnabledFor(app)
                || siblings == null || siblings.length != 1 || !packageName.equals(siblings[0])
                || info.signingInfo == null
                || info.signingInfo.hasMultipleSigners()
                || info.signingInfo.getApkContentsSigners() == null
                || info.signingInfo.getApkContentsSigners().length != 1
                || excludedPackage(packageName)) {
            throw new PackageManager.NameNotFoundException("Unsupported spoof target");
        }
        return info;
    }

    private static boolean excludedPackage(String name) {
        return name.equals("com.google.android.gms") || name.equals("com.android.vending")
                || name.equals("com.google.android.gsf")
                || name.equals("com.google.android.googlequicksearchbox")
                || name.startsWith("app.grapheneos.gmscompat")
                || name.startsWith("org.petalos.gmscompat");
    }

    public static boolean matches(Context context, String name, Entry entry) {
        if (entry == null) return false;
        try {
            final PackageInfo info = eligiblePackage(context, name);
            if (info.applicationInfo.uid != entry.uid || info.firstInstallTime != entry.firstInstallTime) {
                return false;
            }
            final byte[] digest = new byte[32];
            for (int i = 0; i < digest.length; i++) {
                digest[i] = (byte) Integer.parseInt(entry.signer.substring(i * 2, i * 2 + 2), 16);
            }
            // Accept legitimate signing rotation, but not a same-name replacement by another signer.
            return context.getPackageManager().hasSigningCertificate(name, digest,
                    PackageManager.CERT_INPUT_SHA256);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    public boolean isIdleExempt(Context context, String name) {
        final Entry entry = get(name);
        return entry != null && entry.idleExempt && matches(context, name, entry);
    }

    /** Serialized whole-record writes are only accepted through WRITE_SECURE_SETTINGS. */
    public static synchronized boolean setMode(Context context, String name, int state, int mode)
            throws PackageManager.NameNotFoundException {
        if (!isState(state) || mode < OFF || mode > WHILE_IN_RECENTS) {
            throw new IllegalArgumentException("Unknown state or mode");
        }
        return update(context, name, state, mode, null);
    }

    public static synchronized boolean setIdleExempt(Context context, String name, boolean exempt)
            throws PackageManager.NameNotFoundException {
        return update(context, name, 0, 0, exempt);
    }

    public static boolean canReportBackgroundLocation(Context context, String name) {
        final PackageManager pm = context.getPackageManager();
        try {
            final PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions == null || !java.util.Arrays.asList(
                    info.requestedPermissions).contains(
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return false;
            return pm.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, name)
                    == PackageManager.PERMISSION_GRANTED
                    || pm.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, name)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean update(Context context, String name, int state, int mode, Boolean exempt)
            throws PackageManager.NameNotFoundException {
        final PackageInfo info = eligiblePackage(context, name);
        final PetalSpoofConfig config = read(context);
        Entry old = config.get(name);
        if (!matches(context, name, old)) old = null;
        final int[] modes = old == null ? new int[TOKENS.length] : old.mModes.clone();
        if (state != 0) modes[state] = mode;
        final Entry changed = new Entry(info.applicationInfo.uid, info.firstInstallTime,
                signerDigest(info.signingInfo.getApkContentsSigners()[0]),
                exempt != null ? exempt : old != null && old.idleExempt, modes);
        if (changed.empty()) config.mEntries.remove(name);
        else config.mEntries.put(name, changed);
        if (config.mEntries.size() > MAX_PACKAGES) return false;
        final String raw = config.serialize();
        return raw.length() <= MAX_LENGTH && Settings.Secure.putStringForUser(
                context.getContentResolver(), Settings.Secure.PETAL_SPOOFED_PACKAGES,
                raw, context.getUserId());
    }

    /** Remove a package on final uninstall; install-time pinning also covers missed broadcasts. */
    public static synchronized void remove(Context context, String name) {
        final PetalSpoofConfig config = read(context);
        if (config.mEntries.remove(name) != null) {
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.PETAL_SPOOFED_PACKAGES, config.serialize(), context.getUserId());
        }
    }

    public String serialize() {
        try {
            final JSONObject packages = new JSONObject();
            for (int i = 0; i < mEntries.size(); i++) {
                final Entry entry = mEntries.valueAt(i);
                final JSONObject modes = new JSONObject();
                for (int state = 1; state < TOKENS.length; state++) {
                    if (entry.mode(state) != OFF) modes.put(TOKENS[state], entry.mode(state));
                }
                packages.put(mEntries.keyAt(i), new JSONObject().put("uid", entry.uid)
                        .put("installed", entry.firstInstallTime).put("signer", entry.signer)
                        .put("idle_exempt", entry.idleExempt).put("modes", modes));
            }
            return new JSONObject().put("version", 1).put("packages", packages).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signerDigest(Signature signer) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray());
            final StringBuilder result = new StringBuilder(64);
            for (byte b : digest) {
                result.append(Character.forDigit((b >>> 4) & 15, 16));
                result.append(Character.forDigit(b & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
