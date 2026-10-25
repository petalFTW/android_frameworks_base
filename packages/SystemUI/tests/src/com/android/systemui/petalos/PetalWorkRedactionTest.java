/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.res.Resources;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PetalWorkRedactionTest extends SysuiTestCase {
    @Test
    @UiThreadTest
    public void appOwnedTextViewDoesNotResolveSystemUiStrings() throws Exception {
        Context remote = mContext.createPackageContext("android", 0);
        assertThrows(Resources.NotFoundException.class,
                () -> remote.getText(R.string.petal_work_mode_redacted));
        TextView view = new TextView(remote);
        view.setText("Private message");
        CharSequence label = mContext.getText(R.string.petal_work_mode_redacted);
        PetalWorkRedaction.Mask mask = install(view, label);

        mask.apply();

        assertEquals(label.toString(), view.getContentDescription().toString());
        assertFalse(view.getText().toString().contains("Private"));
        mask.restore();
        assertEquals("Private message", view.getText().toString());
        assertNull(view.getContentDescription());
    }

    @Test
    @UiThreadTest
    public void restoreDetachesMaskBeforeReentrantTextCallbacks() {
        TextView view = new TextView(mContext);
        view.setText("Original text");
        view.setContentDescription("Original description");
        PetalWorkRedaction.Mask mask = install(view, "Redacted");
        mask.apply();
        int[] callbacks = {0};
        view.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (++callbacks[0] < 3) mask.restore();
            }
        });

        mask.restore();
        mask.restore();

        assertEquals(1, callbacks[0]);
        assertEquals("Original text", view.getText().toString());
        assertEquals("Original description", view.getContentDescription().toString());
        assertNull(view.getTag(R.id.petal_redacted_text));
    }

    @Test
    @UiThreadTest
    public void disableRestoresLatestNotificationUpdate() {
        TextView view = new TextView(mContext);
        view.setText("Old message");
        PetalWorkRedaction.Mask mask = install(view, "Redacted");
        mask.apply();

        view.setText("Updated message");

        assertFalse(view.getText().toString().contains("Updated"));
        mask.restore();
        assertEquals("Updated message", view.getText().toString());
        view.setText("After disabling");
        assertEquals("After disabling", view.getText().toString());
    }

    private static PetalWorkRedaction.Mask install(TextView view, CharSequence label) {
        PetalWorkRedaction.Mask mask = new PetalWorkRedaction.Mask(view, label);
        view.setTag(R.id.petal_redacted_text, mask);
        view.addTextChangedListener(mask);
        return mask;
    }
}
