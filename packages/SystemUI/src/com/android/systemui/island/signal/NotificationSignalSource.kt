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
import android.service.notification.StatusBarNotification
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.NotificationPayload
import com.android.systemui.island.settings.IslandSettings
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import javax.inject.Inject

/**
 * Turns posted notifications into island signals (§11.1). Emits to [SignalRouter].
 */
class NotificationSignalSource @Inject constructor(
    @Application private val context: android.content.Context,
    private val notifPipeline: NotifPipeline,
    private val settings: IslandSettings,
    private val router: SignalRouter,
) {
    private val packageManager: PackageManager = context.packageManager

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
                router.removeSignal("notif:${entry.key}")
            }
        }

    fun start() {
        notifPipeline.addCollectionListener(listener)
    }

    private fun maybeEmit(entry: NotificationEntry) {
        val sbn = entry.sbn
        val n = sbn.notification
        android.util.Log.d(TAG, "maybeEmit key=${entry.key} imp=${entry.ranking.importance}")

        // Filtering rules from §11.1.
        val isCallStyle = n.isStyle(Notification.CallStyle::class.java)
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && !isCallStyle) return
        if (sbn.isGroup && n.isGroupSummary) return
        if (entry.ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (entry.ranking.isSuspended) return
        if (entry.ranking.channel?.importance == NotificationManager.IMPORTANCE_NONE) return
        if (sbn.packageName in settings.blockedPackages()) return
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

        val iconColor = if (n.color != Notification.COLOR_DEFAULT) n.color else Color.WHITE

        val high = entry.ranking.importance >= NotificationManager.IMPORTANCE_HIGH

        val payload = NotificationPayload(
            key = key,
            appLabel = appLabel,
            title = title,
            text = text,
            smallIcon = n.smallIcon,
            iconColor = iconColor,
            contentIntent = n.contentIntent,
            whenMillis = n.`when`,
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

        router.emit(signal)
        if (high) router.markHeadsUpHandled(key)
        android.util.Log.d(TAG, "emitted notif signal high=$high")
    }

    companion object {
        private const val TAG = "IslandNotif"
    }
}
