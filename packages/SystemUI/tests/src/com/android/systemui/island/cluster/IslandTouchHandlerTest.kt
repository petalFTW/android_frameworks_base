/*
 * Copyright (C) 2026 The petalOS Project
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

package com.android.systemui.island.cluster

import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.island.IslandGeometry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class IslandTouchHandlerTest : SysuiTestCase() {
    @Mock private lateinit var view: IslandView
    @Mock private lateinit var geometry: IslandGeometry
    @Mock private lateinit var callbacks: IslandView.Callbacks
    private lateinit var handler: IslandTouchHandler
    private var time = 1000L
    private var distance = 0f

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(view.context).thenReturn(mContext)
        whenever(view.callbacks).thenReturn(callbacks)
        whenever(view.onGestureDragStart(anyBoolean())).thenReturn(true)
        handler = IslandTouchHandler(view, geometry)
        distance = ViewConfiguration.get(mContext).scaledTouchSlop * 4f
    }

    @After
    fun tearDownGesture() {
        handler.cancel()
    }

    @Test
    fun verticalDrag_movingViewDoesNotChangeHeldFingerDisplacement() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f, 20f + distance)
        dispatch(MotionEvent.ACTION_MOVE, 20f, 20f + distance, translationY = distance)
        dispatch(MotionEvent.ACTION_MOVE, 20f, 20f + distance, translationY = distance)

        verify(view, times(3)).onDragUpdate(distance)
        verify(view, times(1)).onGestureDragStart(false)
    }

    @Test
    fun horizontalDrag_rubberBandedViewDoesNotFeedBackIntoDisplacement() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f + distance, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f + distance, 20f, translationX = distance * .5f)
        dispatch(MotionEvent.ACTION_MOVE, 20f + distance, 20f, translationX = distance * .5f)

        verify(view, times(3)).onHorizontalDragUpdate(distance)
        verify(view, times(1)).onGestureDragStart(true)
    }

    @Test
    fun interceptingRelease_keepsDragUntilTouchConsumesIt() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f, 20f + distance)
        val intercepted = dispatch(MotionEvent.ACTION_UP, 20f, 20f + distance,
            translationY = distance)

        assertThat(intercepted).isTrue()
        verify(view).onDragEnd(distance)
    }

    @Test
    fun cancelledDrag_settlesWithoutCommittingItsDisplacement() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f, 20f + distance)
        dispatch(MotionEvent.ACTION_CANCEL, 20f, 20f + distance, translationY = distance)
        dispatch(MotionEvent.ACTION_UP, 20f, 20f + distance, translationY = distance)

        verify(view, times(1)).onDragEnd(0f)
        verify(view, never()).onDragEnd(distance)
    }

    @Test
    fun diagonalPullDown_withMoreSidewaysTravelExpandsVertically() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f + distance * 1.5f, 20f + distance)
        dispatch(MotionEvent.ACTION_UP, 20f + distance * 1.5f, 20f + distance)

        verify(view).onGestureDragStart(false)
        verify(view).onDragEnd(distance)
        verify(view, never()).onGestureDragStart(true)
    }

    @Test
    fun diagonalPullDown_toTheLeftAlsoExpandsVertically() {
        dispatch(MotionEvent.ACTION_DOWN, 200f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 200f - distance * 1.5f, 20f + distance)

        verify(view).onGestureDragStart(false)
        verify(view).onDragUpdate(distance)
    }

    @Test
    fun smallSidewaysStart_waitsForDownwardIntent() {
        val slop = distance / 4f
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f + slop * 1.5f, 20f + slop * 0.5f)
        verify(view, never()).onGestureDragStart(anyBoolean())

        dispatch(MotionEvent.ACTION_MOVE, 20f + distance * 1.5f, 20f + distance)

        verify(view).onGestureDragStart(false)
        verify(view, never()).onGestureDragStart(true)
    }

    @Test
    fun mostlyHorizontalSwipe_stillChoosesDismissAxis() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f + distance, 20f + distance * 0.25f)

        verify(view).onGestureDragStart(true)
        verify(view).onHorizontalDragUpdate(distance)
    }

    @Test
    fun diagonalMove_afterAxisSelectionDoesNotSwitchAxes() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f, 20f + distance)
        dispatch(MotionEvent.ACTION_MOVE, 20f + distance * 3f, 20f + distance,
            translationY = distance)

        verify(view, times(2)).onDragUpdate(distance)
        verify(view, times(1)).onGestureDragStart(false)
        verify(view, never()).onGestureDragStart(true)
    }

    @Test
    fun activeFingerLift_keepsDisplacementForReplacementFinger() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f + distance, 20f)
        dispatchPointers(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(0 to (20f + distance), 1 to (20f + distance * 3f)),
        )
        dispatchPointers(MotionEvent.ACTION_POINTER_UP,
            listOf(0 to (20f + distance), 1 to (20f + distance * 3f)))
        dispatchPointers(MotionEvent.ACTION_MOVE, listOf(1 to (20f + distance * 3f)))

        verify(view, times(2)).onHorizontalDragUpdate(distance)
        verify(view, times(1)).onGestureDragStart(true)
    }

    @Test
    fun quickVerticalFlick_releasePastSlopStillCommitsDrag() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_UP, 20f, 20f + distance)

        verify(view).onGestureDragStart(false)
        verify(view).onDragEnd(distance)
        verify(view, never()).performClickableChildAt(20f, 20f + distance)
    }

    @Test
    fun interceptedMove_updatesDragEvenWhenDispatchOnlyCancelsTheChild() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        time += 16L
        val event = MotionEvent.obtain(1000L, time, MotionEvent.ACTION_MOVE,
            20f, 20f + distance, 0)
        try {
            handler.onDispatchTouchEvent(event)
            assertThat(handler.onInterceptTouchEvent(event)).isTrue()
            // Android cancels the child's touch here instead of calling our onTouchEvent.
            handler.onDispatchTouchEventFinished(event)
        } finally {
            event.recycle()
        }

        verify(view).onDragUpdate(distance)
    }

    @Test
    fun release_updatesFinalVerticalDistanceBeforeEndingDrag() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f, 20f + distance)
        dispatch(MotionEvent.ACTION_UP, 20f, 20f + distance * 2f)

        val order = org.mockito.Mockito.inOrder(view)
        order.verify(view).onDragUpdate(distance)
        order.verify(view).onDragUpdate(distance * 2f)
        order.verify(view).onDragEnd(distance * 2f)
    }

    @Test
    fun touchHold_lastsUntilRelease() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_MOVE, 20f, 20f + distance)
        verify(callbacks).onUserTouchChanged(view, true)
        verify(callbacks, never()).onUserTouchChanged(view, false)

        dispatch(MotionEvent.ACTION_UP, 20f, 20f + distance)

        verify(callbacks).onUserTouchChanged(view, false)
    }

    @Test
    fun cancelledTouch_releasesDwellHold() {
        dispatch(MotionEvent.ACTION_DOWN, 20f, 20f)
        dispatch(MotionEvent.ACTION_CANCEL, 20f, 20f)

        verify(callbacks).onUserTouchChanged(view, false)
    }

    private fun dispatch(
        action: Int,
        rawX: Float,
        rawY: Float,
        translationX: Float = 0f,
        translationY: Float = 0f,
    ): Boolean {
        time += 16L
        val event = MotionEvent.obtain(1000L, time, action, rawX, rawY, 0)
        // Move the damn frame under a held finger, like the reported flicker.
        event.offsetLocation(-translationX, -translationY)
        return dispatch(event)
    }

    private fun dispatchPointers(action: Int, pointers: List<Pair<Int, Float>>) {
        time += 16L
        val properties = pointers.map { (id, _) ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = pointers.map { (_, rawX) ->
            MotionEvent.PointerCoords().apply {
                x = rawX
                y = 20f
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        dispatch(MotionEvent.obtain(1000L, time, action, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0))
    }

    private fun dispatch(event: MotionEvent): Boolean {
        return try {
            handler.onDispatchTouchEvent(event)
            val intercepted = handler.onInterceptTouchEvent(event)
            handler.onTouchEvent(event)
            handler.onDispatchTouchEventFinished(event)
            intercepted
        } finally {
            event.recycle()
        }
    }
}
