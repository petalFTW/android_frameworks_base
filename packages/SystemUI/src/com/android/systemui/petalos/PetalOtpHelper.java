/*
 * Copyright (C) 2026 petalOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.petalos;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import com.android.systemui.res.R;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * petalOS one-time-password detection + copy. Notifications whose text carries an OTP-looking
 * code get a system-injected "Copy code" action (shade rows via NotificationTemplateViewWrapper
 * and island cards via NotificationPresenter).
 */
public final class PetalOtpHelper {

    /**
     * 5-8 digit groups. A single space / NBSP / hyphen / dot between digits is consumed so the
     * candidate boundaries are well-defined, but any candidate containing punctuation is then
     * disavowed in {@link #extract}: separators glued onto a digit run usually indicate logcat
     * timestamps or ids, not a code. Four-digit groups are ignored: too many false positives
     * (years, quantities).
     */
    private static final Pattern OTP_PATTERN = Pattern.compile(
            "(?<![0-9\\p{L}])(\\d[ \\u00A0\\-.]?){4,7}\\d(?![0-9\\p{L}])");

    /** Words that typically accompany a one-time password. Matched case-insensitively. */
    private static final Pattern OTP_HINT = Pattern.compile(
            "code|otp|pin|passcode|password|verif|confirm|auth|login|token",
            Pattern.CASE_INSENSITIVE);

    private PetalOtpHelper() {}

    /** Returns the first OTP-looking code found in {@code texts}, or null when none. */
    public static String extract(CharSequence... texts) {
        if (texts == null) return null;
        for (int i = 0; i < texts.length; i++) {
            CharSequence text = texts[i];
            if (text == null || text.length() == 0 || text.length() > 2000) continue;
            boolean hasHint = OTP_HINT.matcher(text).find();
            Matcher m = OTP_PATTERN.matcher(text);
            while (m.find()) {
                String code = m.group();
                // Disavow any candidate that carries punctuation: a separator inside the
                // match almost always means it spans unrelated tokens (logcat timestamps,
                // version strings, ids) rather than a human-readable "123 456" code. Only
                // a contiguous digit run is trusted.
                if (!TextUtils.isDigitsOnly(code)) continue;
                // A digit group without any supporting wording is only trusted when it is a
                // standalone 6-digit code (the overwhelmingly common OTP length).
                if (!hasHint && code.length() != 6) continue;
                android.util.Log.d("PetalOtp", "matched '" + code + "' in field " + i);
                return code;
            }
        }
        return null;
    }

    /** Copies {@code code} to the clipboard and confirms with a toast. */
    public static void copyToClipboard(Context context, String code) {
        ClipboardManager cm = context.getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("otp", code));
        }
        Toast.makeText(
                context,
                context.getString(R.string.petal_otp_copied, code),
                Toast.LENGTH_SHORT
        ).show();
    }
}