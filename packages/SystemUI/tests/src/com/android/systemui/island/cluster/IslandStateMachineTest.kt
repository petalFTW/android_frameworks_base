/*
 * Copyright (C) 2026 The petalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.island.cluster

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class IslandStateMachineTest {
    private val clock = FakeSystemClock()
    private val executor = FakeExecutor(clock)
    private val machine = IslandStateMachine(Cluster.LEFT, executor)
    @Mock private lateinit var listener: IslandStateMachine.Listener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        machine.listener = listener
    }

    @Test
    fun touchHold_preventsExpiryUntilRelease() {
        machine.accept(transientSignal())
        machine.holdTouch(true)

        clock.advanceTime(10_000L)
        verify(listener, never()).onDismiss()

        machine.holdTouch(false)
        clock.advanceTime(1500L)
        verify(listener).onDismiss()
        assertThat(machine.current).isNull()
    }

    @Test
    fun notificationSwipe_doesNotAutoExpandOrCollapseWhileTouchIsHeld() {
        machine.accept(transientSignal().copy(kind = SignalKind.NOTIFICATION))
        machine.holdTouch(true)

        clock.advanceTime(10_000L)
        verify(listener, never()).onExpand()
        machine.userExpand()
        verify(listener).onExpand()

        clock.advanceTime(10_000L)
        verify(listener, never()).onCollapse()
        verify(listener, never()).onDismiss()
    }

    @Test
    fun notificationUpdate_doesNotRearmExpiryDuringTouch() {
        val signal = transientSignal()
        machine.accept(signal)
        machine.holdTouch(true)
        machine.accept(signal)

        clock.advanceTime(10_000L)

        verify(listener, never()).onDismiss()
        assertThat(machine.current).isEqualTo(signal)
    }

    @Test
    fun expandedSwipeRelease_startsExpandedDwellWithoutExpandingAgain() {
        machine.accept(transientSignal().copy(kind = SignalKind.NOTIFICATION))
        machine.holdTouch(true)
        machine.userExpand()
        machine.holdTouch(false)

        clock.advanceTime(600L)
        verify(listener, times(1)).onExpand()
        verify(listener, never()).onCollapse()

        clock.advanceTime(2400L)
        verify(listener).onCollapse()
    }

    @Test
    fun manuallyCollapsedNotification_doesNotReexpandOnTouchRelease() {
        machine.accept(transientSignal().copy(kind = SignalKind.NOTIFICATION))
        machine.holdTouch(true)
        machine.userExpand()
        machine.notifyTransitionComplete(IslandState.EXPANDED)

        machine.userCollapse()
        machine.holdTouch(false)
        machine.notifyTransitionComplete(IslandState.COLLAPSED)
        clock.advanceTime(600L)

        verify(listener, times(1)).onExpand()
        assertThat(machine.state).isEqualTo(IslandState.COLLAPSED)

        clock.advanceTime(1400L)
        verify(listener).onDismiss()
        assertThat(machine.current).isNull()
    }

    @Test
    fun manuallyCollapsedNotification_updateDoesNotRearmAutoExpand() {
        val signal = transientSignal().copy(kind = SignalKind.NOTIFICATION)
        machine.accept(signal)
        machine.userExpand()
        machine.notifyTransitionComplete(IslandState.EXPANDED)
        machine.userCollapse()
        machine.notifyTransitionComplete(IslandState.COLLAPSED)

        machine.accept(signal)
        clock.advanceTime(600L)

        verify(listener, times(1)).onExpand()
        assertThat(machine.state).isEqualTo(IslandState.COLLAPSED)
    }

    @Test
    fun queuedNotification_afterManualCollapse_canAutoExpand() {
        val sticky = transientSignal().copy(kind = SignalKind.NOTIFICATION, ttlMs = 0L)
        machine.accept(sticky)
        machine.userExpand()
        machine.notifyTransitionComplete(IslandState.EXPANDED)
        machine.userCollapse()
        machine.notifyTransitionComplete(IslandState.COLLAPSED)
        machine.accept(sticky.copy(id = "notification:next", ttlMs = 1500L))

        machine.userDismiss()
        clock.advanceTime(600L)

        verify(listener, times(2)).onExpand()
        assertThat(machine.current?.id).isEqualTo("notification:next")
    }

    @Test
    fun dismissedIsland_ignoresLateSizeCompletion() {
        machine.accept(transientSignal())
        machine.userDismiss()

        machine.notifyTransitionComplete(IslandState.EXPANDED)

        assertThat(machine.state).isEqualTo(IslandState.HIDDEN)
        assertThat(machine.current).isNull()
    }

    @Test
    fun releasingTouch_keepsReplyDismissalHold() {
        machine.accept(transientSignal())
        machine.holdDismissal(true)
        machine.holdTouch(true)
        machine.holdTouch(false)

        clock.advanceTime(10_000L)
        verify(listener, never()).onDismiss()

        machine.holdDismissal(false)
        clock.advanceTime(1500L)
        verify(listener).onDismiss()
    }

    private fun transientSignal() = IslandSignal(
        id = "volume:test",
        kind = SignalKind.VOLUME,
        cluster = Cluster.LEFT,
        priority = 10,
        initialForm = Form.CAPSULE,
        ttlMs = 1500L,
    )
}
