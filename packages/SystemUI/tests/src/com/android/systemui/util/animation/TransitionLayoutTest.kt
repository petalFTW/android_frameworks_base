/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.util.animation

import android.testing.TestableLooper
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class TransitionLayoutTest : SysuiTestCase() {
    private fun clippedState(id: Int) = TransitionViewState().apply {
        width = 200
        height = 80
        measureWidth = 200
        measureHeight = 80
        widgetStates[id] = WidgetState(
            x = 50f, y = 10f, width = 40, height = 40,
            measureWidth = 120, measureHeight = 40, alpha = 1f,
        )
    }

    @Test
    fun measureState_reportsLatestTargetHeightAfterHostChanges() {
        val parent = TransitionLayout(context, null)
        val compact = TransitionViewState().apply {
            measureWidth = 400
            measureHeight = 180
        }
        parent.measureState = compact
        val large = TransitionViewState().apply {
            measureWidth = 400
            measureHeight = 300
        }

        parent.measureState = large

        assertThat(parent.measureState.measureHeight).isEqualTo(300)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
        )
        assertThat(parent.measuredHeight).isEqualTo(parent.measureState.measureHeight)
    }

    @Test
    fun unmeasuredText_isMeasuredBeforeClipping() {
        val parent = TransitionLayout(context, null)
        val text = TextView(context).apply {
            id = View.generateViewId()
            this.text = "Now playing"
        }
        parent.addView(text)
        assertThat(text.layout).isNull()

        parent.setState(clippedState(text.id))

        assertThat(text.layout).isNotNull()
        assertThat(text.clipBounds!!.left).isEqualTo(0)
        assertThat(text.clipBounds!!.width()).isEqualTo(40)
    }

    @Test
    fun invalidatedTextLayout_isRebuiltWithUnchangedMeasuredDimensions() {
        val parent = TransitionLayout(context, null)
        val text = TextView(context).apply {
            id = View.generateViewId()
            this.text = "Now playing"
        }
        parent.addView(text)
        text.measure(
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY),
        )
        text.layout(0, 0, 120, 40)
        assertThat(text.layout).isNotNull()
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, text.textSize + 2f)
        assertThat(text.layout).isNull()

        parent.setState(clippedState(text.id))

        assertThat(text.layout).isNotNull()
        assertThat(text.clipBounds!!.width()).isEqualTo(40)
    }

    @Test
    fun rtlText_keepsRightAlignedClippingAfterMeasurement() {
        val parent = TransitionLayout(context, null)
        val text = TextView(context).apply {
            id = View.generateViewId()
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            this.text = "مرحبا"
        }
        parent.addView(text)

        parent.setState(clippedState(text.id))

        assertThat(text.clipBounds!!.left).isEqualTo(80)
        assertThat(text.left + text.clipBounds!!.left).isEqualTo(50)
    }

    @Test
    fun detachedPendingRestore_isRescheduledAfterAnotherHostMeasurement() {
        val parent = TransitionLayout(context, null)
        val cover = ImageView(context).apply { id = View.generateViewId() }
        parent.addView(cover)
        parent.setState(clippedState(cover.id))
        val input = MeasurementInput(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        val otherHost = ConstraintSet().apply {
            clone(parent)
            constrainWidth(cover.id, 120)
            constrainHeight(cover.id, 40)
            connect(cover.id, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT, 8)
            connect(cover.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 12)
        }
        parent.calculateViewState(input, otherHost)
        parent.javaClass.getDeclaredMethod("onDetachedFromWindow").apply {
            isAccessible = true
        }.invoke(parent)

        otherHost.connect(cover.id, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT, 24)
        parent.calculateViewState(input, otherHost)
        assertThat(cover.left).isEqualTo(24)
        assertThat(cover.width).isEqualTo(120)
        parent.viewTreeObserver.dispatchOnPreDraw()

        assertThat(cover.left).isEqualTo(50)
        assertThat(cover.top).isEqualTo(10)
        assertThat(cover.width).isEqualTo(40)
        assertThat(cover.clipBounds!!.width()).isEqualTo(40)
    }
}
