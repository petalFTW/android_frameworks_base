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

package com.android.systemui.island.signal

import android.app.Notification
import android.app.Person
import android.content.Context
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.CallPayload
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import javax.inject.Inject

/**
 * Merges cellular call state (via [TelephonyManager]) with the dialer's CallStyle notification for
 * caller identity and accept/decline intents (§11.3).
 */
class CallSignalSource @Inject constructor(
    @Application private val context: Context,
    private val notifPipeline: NotifPipeline,
    private val router: SignalRouter,
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private var callState = TelephonyManager.CALL_STATE_IDLE
    private var callStyle: Notification? = null
    private var callStyleKey: String? = null

    private val telephonyCallback =
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                callState = state
                update()
            }
        }

    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) = onCallStyle(entry)
            override fun onEntryUpdated(entry: NotificationEntry) = onCallStyle(entry)
            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                if (entry.key == callStyleKey) {
                    callStyle = null
                    callStyleKey = null
                    update()
                }
            }
        }

    fun start() {
        notifPipeline.addCollectionListener(collectionListener)
        runCatching {
            telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
        }
    }

    private fun onCallStyle(entry: NotificationEntry) {
        val n = entry.sbn.notification
        if (!n.isStyle(Notification.CallStyle::class.java)) return
        callStyle = n
        callStyleKey = entry.key
        update()
    }

    private fun update() {
        val id = "call:telecom"
        when (callState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val payload = buildPayload(incoming = true)
                router.emit(
                    IslandSignal(
                        id = id,
                        kind = SignalKind.CALL_INCOMING,
                        cluster = Cluster.LEFT,
                        priority = 100,
                        initialForm = Form.EXPANDED,
                        ttlMs = 0L,
                        payload = payload,
                    ),
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val payload = buildPayload(incoming = false)
                router.emit(
                    IslandSignal(
                        id = id,
                        kind = SignalKind.CALL_ONGOING,
                        cluster = Cluster.LEFT,
                        priority = 90,
                        initialForm = Form.CAPSULE,
                        ttlMs = 0L,
                        payload = payload,
                    ),
                )
            }
            else -> router.removeSignal(id)
        }
    }

    private fun buildPayload(incoming: Boolean): CallPayload {
        val n = callStyle
        val extras = n?.extras
        val person = extras?.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        val name = person?.name?.toString() ?: "Call"
        val subtitle = if (incoming) "Incoming call" else "Ongoing call"
        return CallPayload(
            name = name,
            subtitle = subtitle,
            isIncoming = incoming,
            answerIntent = extras?.getParcelable(
                Notification.EXTRA_ANSWER_INTENT, android.app.PendingIntent::class.java
            ),
            declineIntent = extras?.getParcelable(
                Notification.EXTRA_DECLINE_INTENT, android.app.PendingIntent::class.java
            ),
            hangUpIntent = extras?.getParcelable(
                Notification.EXTRA_HANG_UP_INTENT, android.app.PendingIntent::class.java
            ),
        )
    }
}
