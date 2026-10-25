/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.internal.gmscompat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.ext.PackageId;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MicrogCompatTest {
    // Public signing cert from microG's official GsfProxy 0.1.0 release.
    private static final String CERT =
            "MIIC7TCCAdWgAwIBAgIEJv+gCTANBgkqhkiG9w0BAQsFADAnMQswCQYDVQQGEwJE"
            + "RTEYMBYGA1UEChMPTk9HQVBQUyBQcm9qZWN0MB4XDTEyMTAwNjEyMDUzMloXDTM3"
            + "MDkzMDEyMDUzMlowJzELMAkGA1UEBhMCREUxGDAWBgNVBAoTD05PR0FQUFMgUHJv"
            + "amVjdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJqNKlM2sOqq2Jzk"
            + "R4KMd1OxV0WbeeMhXcliykj1jCzXZQ32fS3XvaCIDGgnkfMrNcUE5D53tDw+TlQf"
            + "huNagpOlT7RuaxavVNOk7aRY8afIvBt0eYYcpwQzNxgOQAednNzLfgUa2ptsiMns"
            + "Y1VB4uvwhCUhwwJMgm9v1ttv0RfHToWdWvTbBESJZatUabcc5xmTmgbvMFgPUP6/"
            + "lsR0p9Jlu2P4aoIv97ZD3mt26WahhVPChYQWzzMJ3SQng3S92CtEBO9vfxIs7JOF"
            + "k1H8bl6pR+POudZzdP6XDlk+XNBckF4dJPWlSE9Kre92bkmK32T3zwS93WAq6BN7"
            + "bupAci0CAwEAAaMhMB8wHQYDVR0OBBYEFBELeqnryECyA5n2mkMfTbpqxCpkMA0G"
            + "CSqGSIb3DQEBCwUAA4IBAQAHwyrYkzSc+GlS+1pJz9ybE/XjyACuzney5+DpyD40"
            + "BS8UDzV+x+b0tDLcHtVCIYoUg1rNLfLe6n79P9Xo8cNOH7OexqQnxub0F4tgmzaQ"
            + "QKwfiES3ifNpTcZA3gbkSyR6/tEWNxc/NvWIYXD6/XSVQEmFjGCWMI/JPBvE3VaF"
            + "+nofmCpCLyo7NrqoyVAEdM8q+Rw5y+wbyJjRAZTTaKpekfETfsEVCHwxli2Pds0S"
            + "DSjCSc929McPW6oIxwpyNM5BI74IDO54lHdAGWXP5Te5JO82dH6MrKYt/v3RpiiN"
            + "yxxP0qqmExp60lTpdCAiz9WX0spcZgzp5B/1N+WkBB43";

    private static SigningDetails details(Signature... signers) {
        return new SigningDetails(signers, SigningDetails.SignatureSchemeVersion.JAR, null, null);
    }

    @Test
    public void officialProxySignerIsAllowed() {
        assertTrue(MicrogCompat.isTrustedGsfProxy(PackageId.GSF_NAME,
                details(new Signature(Base64.decode(CERT, Base64.DEFAULT)))));
    }

    @Test
    public void packageNameAloneDoesNotBypassBlock() {
        byte[] other = Base64.decode(CERT, Base64.DEFAULT);
        other[other.length - 1] ^= 1;
        assertFalse(MicrogCompat.isTrustedGsfProxy(PackageId.GSF_NAME, details(new Signature(other))));
        assertFalse(MicrogCompat.isTrustedGsfProxy(PackageId.GSF_NAME, SigningDetails.UNKNOWN));
        assertFalse(MicrogCompat.isTrustedGsfProxy(PackageId.GSF_NAME, null));
    }

    @Test
    public void trustedSignerDoesNotExemptOtherPackages() {
        assertFalse(MicrogCompat.isTrustedGsfProxy("other.package",
                details(new Signature(Base64.decode(CERT, Base64.DEFAULT)))));
    }

    @Test
    public void additionalUntrustedSignerIsRejected() {
        Signature trusted = new Signature(Base64.decode(CERT, Base64.DEFAULT));
        assertFalse(MicrogCompat.isTrustedGsfProxy(PackageId.GSF_NAME,
                details(trusted, new Signature(new byte[]{1, 2, 3}))));
    }
}
