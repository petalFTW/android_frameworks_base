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
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.IslandNotifAction
import com.android.systemui.island.presenter.NotificationPayload
import com.android.systemui.island.settings.IslandSettings
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.row.OnUserInteractionCallback
import javax.inject.Inject

// turns notifications into island signals
@SysUISingleton
class NotificationSignalSource @Inject constructor(
    @Application private val context: android.content.Context,
    private val notifPipeline: NotifPipeline,
    private val onUserInteractionCallback: OnUserInteractionCallback,
    private val settings: IslandSettings,
    private val router: SignalRouter,
) {
    private val packageManager: PackageManager = context.packageManager

    // progress notifs never send a clean done, so track their keys here
    private val progressKeys = mutableSetOf<String>()

    private val listener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) {
                android.util.Log.d(TAG, "onEntryAdded key=${entry.key} pkg=${entry.sbn.packageName}")
                maybeEmit(entry)
            }

            override fun onEntryUpdated(entry: NotificationEntry) {
                maybeEmit(entry)
            }

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                progressKeys.remove(entry.key)
                router.removeSignal("notif:${entry.key}")
                router.clearHeadsUpHandled(entry.key)
            }
        }

    fun start() {
        notifPipeline.addCollectionListener(listener)
    }

    // swiped away on the island, cancel it in the shade too
    fun cancelFromIsland(key: String) {
        val entry = notifPipeline.getEntry(key) ?: return
        runCatching {
            onUserInteractionCallback
                .registerFutureDismissal(entry, NotificationListenerService.REASON_CANCEL)
                .run()
        }.onFailure {
            android.util.Log.w(TAG, "Failed to cancel $key from island", it)
        }
    }

    private fun maybeEmit(entry: NotificationEntry) {
        val sbn = entry.sbn
        val n = sbn.notification
        android.util.Log.d(TAG, "maybeEmit key=${entry.key} imp=${entry.ranking.importance}")

        // user opened it, don't pop it back up when the app re-posts
        if (router.isRecentlyOpened(entry.key)) {
            android.util.Log.d(TAG, "suppressing recently-opened notification ${entry.key}")
            return
        }

        // progress shows even for ongoing or silent notifs
        val hasProgress = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 ||
            n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        if (hasProgress) {
            progressKeys.add(entry.key)
        } else if (progressKeys.contains(entry.key)) {
            emitSyntheticProgressCompletion(entry.key, entry)
        }

        // the final download update gets filtered out, so force it
        val completingProgressUpdate = !hasProgress && progressKeys.contains(entry.key)
        if (completingProgressUpdate) {
            progressKeys.remove(entry.key)
        }

        // filters, per the spec
        val isCallStyle = n.isStyle(Notification.CallStyle::class.java)
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && !isCallStyle && !hasProgress &&
            !completingProgressUpdate
        ) {
            return
        }
        if (sbn.isGroup && n.isGroupSummary) return
        // progress also blobs for silent notifications on petalOS
        if (entry.ranking.importance < NotificationManager.IMPORTANCE_DEFAULT && !hasProgress &&
            !completingProgressUpdate
        ) {
            return
        }
        if (entry.ranking.isSuspended) return
        if (entry.ranking.channel?.importance == NotificationManager.IMPORTANCE_NONE) return
        if (sbn.packageName in settings.blockedPackages()) return
        // system and SystemUI notifs never reach the island
        if (isSystemNotification(sbn)) return
        // media notifs belong to the media source
        val mediaToken = n.extras.getParcelable(
            Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java
        )
        if (mediaToken != null) return

        val key = entry.key
        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(sbn.packageName)

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
        val subText = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)

        // latest message sender wins, otherwise DMs show your own name
        val person = latestMessageSender(n)
            ?: n.extras.getParcelable(
                Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java
            )

        val actions = n.actions.orEmpty().take(3).mapNotNull { a ->
            val intent = a.actionIntent ?: return@mapNotNull null
            val actionTitle = a.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            IslandNotifAction(actionTitle, a.getIcon(), intent, a.remoteInputs)
        }

        val iconColor = if (n.color != Notification.COLOR_DEFAULT) n.color else Color.WHITE

        val high = entry.ranking.importance >= NotificationManager.IMPORTANCE_HIGH

        val payload = NotificationPayload(
            key = key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            sender = person?.name,
            title = title,
            text = text,
            subText = subText,
            smallIcon = n.smallIcon,
            largeIcon = person?.getIcon() ?: n.getLargeIcon(),
            iconColor = iconColor,
            contentIntent = n.contentIntent,
            actions = actions,
            // progress lives in extras, not on fields
            progressCurrent = n.extras.getInt(Notification.EXTRA_PROGRESS),
            progressMax = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX),
            progressIndeterminate = n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            whenMillis = n.`when`,
            otpCode = com.android.systemui.petalos.PetalOtpHelper.extract(title, text, subText),
        )

        val signal = IslandSignal(
            id = "notif:$key",
            kind = SignalKind.NOTIFICATION,
            cluster = Cluster.LEFT,
            priority = if (high) 60 else 40,
            // starts as a capsule, the state machine does the rest
            initialForm = Form.CAPSULE,
            ttlMs = 1L, // dwell handled in the state machine
            payload = payload,
        )

        // we're replacing the heads-up, so mark it handled before emitting
        router.markHeadsUpHandled(key)
        router.emit(signal)
        android.util.Log.d(TAG, "emitted notif signal high=$high")
    }

    // no real completion is coming, synthesize one so it can fade
    private fun emitSyntheticProgressCompletion(key: String, entry: NotificationEntry) {
        progressKeys.remove(key)
        val sbn = entry.sbn
        val n = sbn.notification
        val payload = NotificationPayload(
            key = key,
            packageName = sbn.packageName,
            appLabel = runCatching {
                val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
                packageManager.getApplicationLabel(ai).toString()
            }.getOrDefault(sbn.packageName),
            sender = null,
            title = n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "Download complete",
            text = n.extras.getCharSequence(Notification.EXTRA_TEXT),
            subText = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            smallIcon = n.smallIcon,
            largeIcon = n.getLargeIcon(),
            iconColor = if (n.color != Notification.COLOR_DEFAULT) n.color else Color.WHITE,
            contentIntent = n.contentIntent,
            actions = emptyList(),
            progressCurrent = 0,
            progressMax = 0,
            progressIndeterminate = false,
            whenMillis = n.`when`,
            otpCode = null,
        )
        val signal = IslandSignal(
            id = "notif:$key",
            kind = SignalKind.NOTIFICATION,
            cluster = Cluster.LEFT,
            priority = 40,
            initialForm = Form.CAPSULE,
            ttlMs = 1L,
            payload = payload,
        )
        router.markHeadsUpHandled(key)
        router.emit(signal)
        android.util.Log.d(TAG, "emitted synthetic progress completion for $key")
    }

    // last message sender, null when it's not a messaging style
    private fun latestMessageSender(n: Notification): android.app.Person? {
        if (!n.isStyle(Notification.MessagingStyle::class.java)) return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        )
        // messages are oldest first, so take the last
        return messages?.lastOrNull()?.senderPerson
    }

    /** android or our own package */
    private fun isSystemNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        return pkg == "android" || pkg == context.packageName
    }

    companion object {
        private const val TAG = "IslandNotif"
    }
}
