/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.PetalStateOverrides;

import org.json.JSONObject;
import org.junit.Test;

/** Exercises the actual parser, including fail-closed policy handling and size boundaries. */
public class PetalSpoofConfigTest {
    private static final String PACKAGE = "com.example.dashcam";
    private static final String SIGNER = "a".repeat(64);

    private JSONObject entry() throws Exception {
        return new JSONObject().put("uid", 10023).put("installed", 1234567890123L)
                .put("signer", SIGNER).put("idle_exempt", false)
                .put("modes", new JSONObject().put("bluetooth", 1).put("location", 2));
    }

    private String policy(JSONObject entry) throws Exception {
        return new JSONObject().put("version", 1)
                .put("packages", new JSONObject().put(PACKAGE, entry)).toString();
    }

    @Test
    public void emptyPolicyReportsTruth() {
        assertTrue(PetalSpoofConfig.parse(null).isEmpty());
        assertTrue(PetalSpoofConfig.parse("").isEmpty());
    }

    @Test
    public void validModesAndIdentityRoundTrip() throws Exception {
        final PetalSpoofConfig config = PetalSpoofConfig.parse(policy(entry()));
        final PetalSpoofConfig.Entry entry = config.get(PACKAGE);
        assertEquals(10023, entry.uid);
        assertEquals(1234567890123L, entry.firstInstallTime);
        assertEquals(SIGNER, entry.signer);
        assertEquals(PetalSpoofConfig.ALWAYS, entry.mode(PetalStateOverrides.BLUETOOTH));
        assertEquals(PetalSpoofConfig.WHILE_ACTIVE, entry.mode(PetalStateOverrides.LOCATION));
        assertEquals(PetalSpoofConfig.OFF, entry.mode(PetalStateOverrides.BACKGROUND));
        assertEquals(PetalSpoofConfig.OFF, entry.mode(-1));
        assertFalse(entry.idleExempt);
        final PetalSpoofConfig.Entry roundTrip = PetalSpoofConfig.parse(config.serialize()).get(PACKAGE);
        assertEquals(entry.signer, roundTrip.signer);
        assertEquals(entry.firstInstallTime, roundTrip.firstInstallTime);
        assertEquals(entry.mode(PetalStateOverrides.LOCATION), roundTrip.mode(PetalStateOverrides.LOCATION));
    }

    @Test
    public void realIdleExemptionIsIndependentOfReportedModes() throws Exception {
        final PetalSpoofConfig.Entry entry = PetalSpoofConfig.parse(policy(entry()
                .put("modes", new JSONObject()).put("idle_exempt", true))).get(PACKAGE);
        assertTrue(entry.idleExempt);
        for (int token = 1; token <= PetalStateOverrides.LOCATION_PERMISSION; token++) {
            assertEquals(PetalSpoofConfig.OFF, entry.mode(token));
        }
    }

    @Test
    public void malformedOrUnsupportedVersionFailsClosed() {
        for (String raw : new String[] {"{", "[]", "{}", "{\"version\":2,\"packages\":{}}",
                "{\"version\":\"1\",\"packages\":{}}"}) {
            assertTrue(raw, PetalSpoofConfig.parse(raw).isEmpty());
        }
    }

    @Test
    public void oversizedPolicyFailsClosed() {
        assertTrue(PetalSpoofConfig.parse(" ".repeat(PetalSpoofConfig.MAX_LENGTH + 1)).isEmpty());
    }

    @Test
    public void excessiveNestingFailsClosedBeforeRecursiveParsing() throws Exception {
        final String raw = "{\"version\":1,\"packages\":{},\"unknown\":"
                + "[".repeat(10000) + "0" + "]".repeat(10000) + "}";
        assertTrue(PetalSpoofConfig.parse(raw).isEmpty());
    }

    @Test
    public void bracesInsideQuotedStringsAreNotCountedAsNesting() throws Exception {
        final JSONObject root = new JSONObject(policy(entry()));
        root.put("unknown", "[[[[[[[[[[[\\\"quoted\\\"]]]]]]]]]]]");
        assertFalse(PetalSpoofConfig.parse(root.toString()).isEmpty());
    }

    @Test
    public void invalidModeDoesNotBecomeGrant() throws Exception {
        for (Object bad : new Object[] {-1, 4, "1", true, JSONObject.NULL, 1.5}) {
            assertTrue(PetalSpoofConfig.parse(policy(entry().put("modes",
                    new JSONObject().put("bluetooth", bad)))).isEmpty());
        }
    }

    @Test
    public void recentsAndBackgroundLocationRoundTrip() throws Exception {
        final PetalSpoofConfig config = PetalSpoofConfig.parse(policy(entry().put("modes",
                new JSONObject().put("bluetooth", 3).put("location_permission", 3))));
        final PetalSpoofConfig.Entry saved = PetalSpoofConfig.parse(config.serialize()).get(PACKAGE);
        assertEquals(PetalSpoofConfig.WHILE_IN_RECENTS,
                saved.mode(PetalStateOverrides.BLUETOOTH));
        assertEquals(PetalSpoofConfig.WHILE_IN_RECENTS,
                saved.mode(PetalStateOverrides.LOCATION_PERMISSION));
    }

    @Test
    public void unknownPermissionTokenFailsClosed() throws Exception {
        assertTrue(PetalSpoofConfig.parse(policy(entry().put("modes",
                new JSONObject().put("camera", 1)))).isEmpty());
    }

    @Test
    public void coreIsolatedAndSandboxUidsFailClosed() throws Exception {
        for (int uid : new int[] {-1, 0, 1000, 20000, 99000, 101000}) {
            assertTrue(PetalSpoofConfig.parse(policy(entry().put("uid", uid))).isEmpty());
        }
    }

    @Test
    public void secondaryUserUidIsPreserved() throws Exception {
        assertEquals(1010023, PetalSpoofConfig.parse(policy(entry().put("uid", 1010023)))
                .get(PACKAGE).uid);
    }

    @Test
    public void identityCannotBeCoercedFromStrings() throws Exception {
        assertTrue(PetalSpoofConfig.parse(policy(entry().put("uid", "10023"))).isEmpty());
        assertTrue(PetalSpoofConfig.parse(policy(entry().put("installed", "12345"))).isEmpty());
        assertTrue(PetalSpoofConfig.parse(policy(entry().put("installed", 0))).isEmpty());
        assertTrue(PetalSpoofConfig.parse(policy(entry().put("signer", "not-a-certificate"))).isEmpty());
    }

    @Test
    public void exemptionCannotBeCoercedFromString() throws Exception {
        assertTrue(PetalSpoofConfig.parse(policy(entry().put("idle_exempt", "true"))).isEmpty());
    }

    @Test
    public void invalidPackageInvalidatesWholePolicy() throws Exception {
        final JSONObject packages = new JSONObject().put(PACKAGE, entry()).put("../bad", entry());
        final PetalSpoofConfig config = PetalSpoofConfig.parse(new JSONObject()
                .put("version", 1).put("packages", packages).toString());
        assertTrue(config.isEmpty());
        assertNull(config.get(PACKAGE));
    }

    @Test
    public void packageLimitIsEnforced() throws Exception {
        final JSONObject packages = new JSONObject();
        for (int i = 0; i <= PetalSpoofConfig.MAX_PACKAGES; i++) {
            packages.put("com.example.p" + i, entry());
        }
        assertTrue(PetalSpoofConfig.parse(new JSONObject().put("version", 1)
                .put("packages", packages).toString()).isEmpty());
    }
}
