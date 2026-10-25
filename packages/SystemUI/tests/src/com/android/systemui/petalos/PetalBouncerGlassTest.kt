/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

@SmallTest
@RunWith(AndroidJUnit4::class)
class PetalBouncerGlassTest : SysuiTestCase() {
    @Test
    @UiThreadTest
    fun fadeAppliesToTheGlassRimAndSheen() {
        val view = View(context).apply { layout(0, 0, 300, 300) }
        PetalBouncerGlass.install(view)
        val background = requireNotNull(view.background).apply {
            bounds = Rect(0, 0, 300, 300)
            alpha = 128
        }
        val canvas = mock(Canvas::class.java)

        background.draw(canvas)

        verify(canvas).saveLayerAlpha(null, 128)
    }

    @Test
    @UiThreadTest
    fun fullyDismissedPanelDrawsNothing() {
        val view = View(context).apply { layout(0, 0, 300, 300) }
        PetalBouncerGlass.install(view)
        val background = requireNotNull(view.background).apply {
            bounds = Rect(0, 0, 300, 300)
            alpha = 0
        }
        val canvas = mock(Canvas::class.java)

        background.draw(canvas)

        verifyNoInteractions(canvas)
    }
}
