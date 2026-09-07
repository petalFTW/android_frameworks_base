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

/**
 * Turns posted notifications into island signals (§11.1). Emits to [SignalRouter].
 */
@SysUISingleton
class NotificationSignalSource @Inject constructor(
    @Application private val context: android.content.Context,
    private val notifPipeline: NotifPipeline,
    private val onUserInteractionCallback: OnUserInteractionCallback,
    private val settings: IslandSettings,
    private val router: SignalRouter,
) {
    private val packageManager: PackageManager = context.packageManager

    /**
     * petalOS bug fix: keys of notifications that were last emitted as progress blobs
     * (downloads/uploads). A finished download is almost never delivered as "progress reaches
     * max": the app either re-posts the same key as a "complete" notification with the progress
     * extras cleared and FLAG_ONGOING_EVENT still set, or posts it on a silent channel. Both of
     * those updates get filtered out below, which left the island stuck showing the last
     * in-flight percentage forever (an in-flight progress blob has no dwell timer by design).
     * Remembering these keys lets us (a) let the final "complete" update through so the state
     * machine morphs onto it and runs its normal dwell + fade, and (b) emit a synthetic final
     * update if the completion notification never surfaces at all.
     */
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

    /**
     * Removes the notification from the shade after the user swiped it away on the island
     * (bug: swiping/dismissing an expanded island notification should also remove it from the
     * notification center). Uses the same pipeline dismissal the shade swipe uses.
     */
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

        // A notification the user just opened (primary-action tap) may be removed + re-posted by
        // its app while it marks itself read; skip it so the island doesn't re-emerge as if the
        // message just arrived again.
        if (router.isRecentlyOpened(entry.key)) {
            android.util.Log.d(TAG, "suppressing recently-opened notification ${entry.key}")
            return
        }

        // Progress notifications (downloads/uploads) surface as persistent blobs even when they
        // are posted ongoing and/or silent.
        val hasProgress = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 ||
            n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        if (hasProgress) {
            progressKeys.add(entry.key)
        } else if (progressKeys.contains(entry.key)) {
            emitSyntheticProgressCompletion(entry.key, entry)
        }

        // petalOS bug fix: a previously-progress notification being updated to its finished
        // state ("Download complete") usually sheds its progress extras, keeps
        // FLAG_ONGOING_EVENT and/or drops to a silent channel — every one of which is filtered
        // below. Let that final update through so the blob morphs to it and the state machine
        // arms its normal notification dwell (show a beat, then fade) instead of showing the
        // last in-flight percentage forever.
        val completingProgressUpdate = !hasProgress && progressKeys.contains(entry.key)
        if (completingProgressUpdate) {
            progressKeys.remove(entry.key)
        }

        // Filtering rules from §11.1.
        val isCallStyle = n.isStyle(Notification.CallStyle::class.java)
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && !isCallStyle && !hasProgress &&
            !completingProgressUpdate
        ) {
            return
        }
        if (sbn.isGroup && n.isGroupSummary) return
        // petalOS: progress blobs also show for silent (low-importance) notifications.
        if (entry.ranking.importance < NotificationManager.IMPORTANCE_DEFAULT && !hasProgress &&
            !completingProgressUpdate
        ) {
            return
        }
        if (entry.ranking.isSuspended) return
        if (entry.ranking.channel?.importance == NotificationManager.IMPORTANCE_NONE) return
        if (sbn.packageName in settings.blockedPackages()) return
        // System notifications (Android system / SystemUI itself) never show on the island.
        if (isSystemNotification(sbn)) return
        // Media notifications are handled by MediaSignalSource.
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

        // Messaging notifications carry a Person for the sender; prefer their identity for the
        // headline and avatar (§11.1). The most recent message's sender is the most reliable:
        // EXTRA_MESSAGING_PERSON is the conversation person and can point at the current user
        // rather than the actual sender (e.g. Instagram DMs), which would show "me" instead of
        // the person who messaged.
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
            // A16: progress lives in extras (see EXTRA_PROGRESS docs), not on fields.
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
            // Emerge as the small capsule, then auto-expand, dwell, collapse, fade.
            initialForm = Form.CAPSULE,
            ttlMs = 1L, // transient; dwell is handled by the state machine
            payload = payload,
        )

        // The island is replacing this notification's heads-up regardless of importance; mark it
        // handled before emitting so the heads-up coordinator can see it once the list is built.
        router.markHeadsUpHandled(key)
        router.emit(signal)
        android.util.Log.d(TAG, "emitted notif signal high=$high")
    }

    /**
     * petalOS bug fix: called on re-add of a key we still track as an in-flight progress blob.
     * If that key is coming back without progress extras, the app finished the download by
     * removing + re-posting (or clearing the progress extras), and none of the normal paths
     * (morph-to-100% / entry-removed) will fire for the old blob. Emit a completion update for
     * the old id so the state machine morphs onto the finished state and dwells + fades out
     * instead of sticking on the last in-flight percentage forever.
     */
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

    /**
     * Returns the [android.app.Person] who sent the most recent [Notification.MessagingStyle]
     * message, or null when the notification isn't a messaging style or has no messages.
     */
    private fun latestMessageSender(n: Notification): android.app.Person? {
        if (!n.isStyle(Notification.MessagingStyle::class.java)) return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        )
        // Messages are stored in chronological order, so the last one is the newest.
        return messages?.lastOrNull()?.senderPerson
    }

    /** True for notifications posted by the Android system or SystemUI itself. */
    private fun isSystemNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        return pkg == "android" || pkg == context.packageName
    }

    companion object {
        private const val TAG = "IslandNotif"
    }
}
