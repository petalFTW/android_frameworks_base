/*
 * Copyright (C) 2026 The petalOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.island.signal

import android.app.Notification
import android.app.Person
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.CallPayload
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class CallSignalSourceTest : SysuiTestCase() {
    @Mock private lateinit var sourceContext: Context
    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var router: SignalRouter
    private lateinit var listener: NotifCollectionListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(pipeline.allNotifs).thenReturn(emptyList())
        CallSignalSource(sourceContext, pipeline, router).start()
        val captor = ArgumentCaptor.forClass(NotifCollectionListener::class.java)
        verify(pipeline).addCollectionListener(captor.capture())
        listener = captor.value
    }

    @Test
    fun legacyVoipCall_usesTextWhenTitleIsUnknown() {
        listener.onEntryAdded(callEntry(1, "Unknown", "Alice"))

        val signal = emittedSignal()
        assertThat(signal.kind).isEqualTo(SignalKind.CALL_ONGOING)
        val payload = signal.payload as CallPayload
        assertThat(payload.name).isEqualTo("Alice")
        assertThat(payload.allowTelecomFallback).isFalse()
    }

    @Test
    fun unknownPerson_fallsBackToTheirTelephoneUri() {
        val entry = callEntry(1, "Unknown", "Ongoing call")
        entry.sbn.notification.extras.putParcelable(
            Notification.EXTRA_CALL_PERSON,
            Person.Builder().setName("Unknown").setUri("tel:%2B15551234567").build(),
        )

        listener.onEntryAdded(entry)

        assertThat((emittedSignal().payload as CallPayload).name).isEqualTo("+15551234567")
    }

    @Test
    fun removingLatestCall_restoresOtherActiveCaller() {
        listener.onEntryAdded(callEntry(1, "Alice"))
        val second = callEntry(2, "Bob")
        listener.onEntryAdded(second)
        listener.onEntryRemoved(second, 0)

        assertThat((emittedSignal(3).payload as CallPayload).name).isEqualTo("Alice")
    }

    @Test
    fun callNotificationBecomesOrdinary_removesCallSignal() {
        listener.onEntryAdded(callEntry(1, "Alice"))
        val ordinary = callEntry(1, "Finished")
        ordinary.sbn.notification.category = Notification.CATEGORY_STATUS
        ordinary.sbn.notification.flags = 0

        listener.onEntryUpdated(ordinary)

        verify(router).removeSignal("call:telecom")
    }

    private fun callEntry(id: Int, title: String, text: String = ""): NotificationEntry {
        val notification = Notification().apply {
            category = Notification.CATEGORY_CALL
            flags = Notification.FLAG_ONGOING_EVENT
            extras.putCharSequence(Notification.EXTRA_TITLE, title)
            extras.putCharSequence(Notification.EXTRA_TEXT, text)
        }
        return NotificationEntryBuilder().setPkg("test.voip").setId(id)
            .setNotification(notification).build()
    }

    private fun emittedSignal(count: Int = 1): IslandSignal {
        val captor = ArgumentCaptor.forClass(IslandSignal::class.java)
        verify(router, times(count)).emit(captor.capture())
        return captor.value
    }
}
