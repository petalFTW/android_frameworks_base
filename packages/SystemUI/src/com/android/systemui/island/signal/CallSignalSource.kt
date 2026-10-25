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

// Call apps all post this stuff differently, because of course they do.
class CallSignalSource @Inject constructor(
    @Application private val context: Context,
    private val notifPipeline: NotifPipeline,
    private val router: SignalRouter,
) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private var started = false
    private var callState = TelephonyManager.CALL_STATE_IDLE
    private val callNotifications = linkedMapOf<String, NotificationEntry>()

    private val telephonyCallback =
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                callState = state
                update()
            }
        }

    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) {
                onCallNotification(entry)
            }
            override fun onEntryUpdated(entry: NotificationEntry) {
                onCallNotification(entry)
            }
            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                if (callNotifications.remove(entry.key) != null) update()
            }
        }

    fun start() {
        if (started) return
        started = true
        notifPipeline.addCollectionListener(collectionListener)
        notifPipeline.allNotifs.forEach(::onCallNotification)
        runCatching {
            telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
        }
    }

    private fun onCallNotification(entry: NotificationEntry) {
        val n = entry.sbn.notification
        val isActiveCall = n.isStyle(Notification.CallStyle::class.java) ||
            (n.category == Notification.CATEGORY_CALL &&
                (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.fullScreenIntent != null))
        if (!isActiveCall || n.isGroupSummary) {
            if (callNotifications.remove(entry.key) != null) update()
            return
        }
        callNotifications.remove(entry.key)
        callNotifications[entry.key] = entry
        update()
    }

    private fun update() {
        val id = "call:telecom"
        val defaultDialer = context.getSystemService(android.telecom.TelecomManager::class.java)
            ?.defaultDialerPackage
        val dialerEntry = callNotifications.values.lastOrNull {
            it.sbn.packageName == defaultDialer || it.sbn.packageName == DIALER_PKG
        }
        val entry = if (callState != TelephonyManager.CALL_STATE_IDLE) {
            dialerEntry
        } else {
            callNotifications.values.lastOrNull()
        }
        val n = entry?.sbn?.notification
        val styleState = when (n?.extras?.getInt(Notification.EXTRA_CALL_TYPE)) {
            Notification.CallStyle.CALL_TYPE_INCOMING -> TelephonyManager.CALL_STATE_RINGING
            Notification.CallStyle.CALL_TYPE_ONGOING -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> when {
                n == null -> TelephonyManager.CALL_STATE_IDLE
                n.fullScreenIntent != null &&
                    !n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ->
                    TelephonyManager.CALL_STATE_RINGING
                else -> TelephonyManager.CALL_STATE_OFFHOOK
            }
        }
        val effectiveState = if (callState != TelephonyManager.CALL_STATE_IDLE) callState else styleState
        when (effectiveState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val payload = buildPayload(entry, incoming = true)
                router.emit(
                    IslandSignal(
                        id = id,
                        kind = SignalKind.CALL_INCOMING,
                        cluster = Cluster.LEFT,
                        priority = 100,
                        initialForm = Form.CAPSULE,
                        ttlMs = 0L,
                        payload = payload,
                    ),
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val payload = buildPayload(entry, incoming = false)
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

    private fun buildPayload(entry: NotificationEntry?, incoming: Boolean): CallPayload {
        val n = entry?.sbn?.notification
        val extras = n?.extras
        val person = extras?.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        val packageName = entry?.sbn?.packageName
        val appLabel = packageName?.let {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
            }.getOrNull()
        }
        val name = resolveCallerName(extras, person, appLabel)
        val subtitle = if (incoming) "Incoming call" else "Ongoing call"
        return CallPayload(
            sourceNotification = entry?.sbn,
            contentIntent = n?.contentIntent,
            name = name,
            subtitle = subtitle,
            isIncoming = incoming,
            allowTelecomFallback = packageName == null || packageName == DIALER_PKG ||
                packageName == context.getSystemService(android.telecom.TelecomManager::class.java)
                    ?.defaultDialerPackage,
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

    // "Unknown" isn't a bloody contact name; keep digging.
    private fun resolveCallerName(
        extras: android.os.Bundle?,
        person: Person?,
        appLabel: String?,
    ): String {
        val people = extras?.getParcelableArrayList(
            Notification.EXTRA_PEOPLE_LIST, Person::class.java
        ) ?: emptyList()
        val callers = listOfNotNull(person) + people
        val names = callers.mapNotNull { it.name?.toString()?.trim() }
        names.firstOrNull { isCallerLabel(it) && !looksLikeNumber(it) }?.let { return it }

        val labels = listOfNotNull(
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim(),
            extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim(),
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim(),
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim(),
        ) + (extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString().trim() } ?: emptyList())
        labels.firstOrNull {
            isCallerLabel(it) && !it.equals(appLabel, ignoreCase = true) && !looksLikeNumber(it)
        }?.let { return it }
        val numbers = callers.mapNotNull { it.uri } + names + labels +
            (extras?.getStringArray(Notification.EXTRA_PEOPLE)?.toList() ?: emptyList())
        val number = numbers.asSequence()
            .map { android.net.Uri.decode(it.removePrefix("tel:")).trim() }
            .firstOrNull(::looksLikeNumber)
        if (number != null) {
            lookupContactName(number)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return number ?: "Unknown"
    }

    private fun isCallerLabel(value: String): Boolean = value.isNotBlank() &&
        value.lowercase(java.util.Locale.ROOT) !in NON_CALLER_LABELS

    private fun looksLikeNumber(s: String): Boolean {
        val number = android.net.Uri.decode(s.removePrefix("tel:")).trim()
        return number.any { it.isDigit() } &&
            number.all { it.isDigit() || " +()-#*.N".contains(it) }
    }

    private fun lookupContactName(number: String): String? {
        if (number.isEmpty() || number.none { it.isDigit() }) return null
        return runCatching {
            val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI
                .buildUpon().appendPath(number).build()
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private companion object {
        const val DIALER_PKG = "com.android.dialer"
        val NON_CALLER_LABELS = setOf(
            "unknown", "unknown caller", "unknown number", "incoming call", "ongoing call",
            "outgoing call", "calling", "calling…", "calling...", "call in progress",
            "tap to return to call", "incoming voice call", "incoming video call",
            "voice call", "video call",
        )
    }
}
