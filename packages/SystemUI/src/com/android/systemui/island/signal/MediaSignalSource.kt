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
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.settings.IslandSettings
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// watches media sessions and each controller, the listener alone misses play/pause
class MediaSignalSource @Inject constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    private val settings: IslandSettings,
    private val router: SignalRouter,
    private val notifPipeline: NotifPipeline,
    private val statusBarModeRepository: StatusBarModeRepositoryStore,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var currentId: String? = null
    private var controllers: List<MediaController> = emptyList()
    private var activeControllers: List<MediaController> = emptyList()
    private var fullscreen = false
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            reevaluate()
        }
    }

    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        activeControllers = list.orEmpty()
        refreshControllers()
    }

    private val notificationListener = object : NotifCollectionListener {
        override fun onEntryAdded(entry: NotificationEntry) = refreshControllers()
        override fun onEntryUpdated(entry: NotificationEntry) = refreshControllers()
        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) = refreshControllers()
    }

    private fun refreshControllers() {
        // A player can publish a media session without taking audio focus. Its
        // notification token is an authoritative fallback to the active list.
        val notificationControllers = notifPipeline.allNotifs.mapNotNull { entry ->
            val token = entry.sbn.notification.extras.getParcelable(
                Notification.EXTRA_MEDIA_SESSION,
                MediaSession.Token::class.java) ?: return@mapNotNull null
            runCatching { MediaController(context, token) }.getOrNull()
        }
        val candidates = (activeControllers + notificationControllers).distinctBy { it.sessionToken }
        val old = controllers.associateBy { it.sessionToken }
        val tokens = candidates.map { it.sessionToken }.toSet()
        controllers.filter { it.sessionToken !in tokens }.forEach { it.unregisterCallback(controllerCallback) }
        controllers = candidates.map { candidate ->
            old[candidate.sessionToken] ?: candidate.also { it.registerCallback(controllerCallback, handler) }
        }
        reevaluate()
    }

    fun start() {
        if (started) return
        started = true
        notifPipeline.addCollectionListener(notificationListener)
        msm.addOnActiveSessionsChangedListener(listener, null, handler)
        listener.onActiveSessionsChanged(msm.getActiveSessions(null))
        // hide the blob in fullscreen, no media chip over a video
        scope.launch {
            val repo = statusBarModeRepository.forDisplay(Display.DEFAULT_DISPLAY)
                ?: return@launch
            repo.isInFullscreenMode.collect { full ->
                fullscreen = full
                if (full) {
                    currentId?.let { router.removeSignal(it) }
                    currentId = null
                } else {
                    reevaluate()
                }
            }
        }
    }

    private fun reevaluate() {
        if (!settings.mediaEnabled() || fullscreen) {
            currentId?.let { router.removeSignal(it) }
            currentId = null
            return
        }
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        // Prefer playback, then a loading session, then paused transport controls.
        val chosen = playing
            ?: controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_BUFFERING ||
                    it.playbackState?.state == PlaybackState.STATE_CONNECTING
            }
            ?: controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PAUSED
            }

        if (chosen == null) {
            currentId?.let { router.removeSignal(it) }
            currentId = null
            return
        }

        val isPlaying = chosen.playbackState?.state == PlaybackState.STATE_PLAYING
        val id = "media:${chosen.packageName}"
        val isNew = id != currentId
        if (isNew) currentId?.let { router.removeSignal(it) }
        currentId = id

        // if automix wants to ask, open expanded so the prompt is visible
        val cr = context.contentResolver
        val ask = android.provider.Settings.System.getInt(cr, "petal_bridge_ask", 0) == 1
        val automix = android.provider.Settings.System.getInt(cr, "petal_bridge_enabled", 0) == 1
        val form = when {
            ask && automix && isNew -> Form.EXPANDED
            isPlaying -> Form.CAPSULE
            else -> Form.CHIP
        }

        router.emit(
            IslandSignal(
                id = id,
                kind = SignalKind.MEDIA,
                cluster = Cluster.RIGHT,
                priority = if (isPlaying) 70 else 65,
                initialForm = form,
                ttlMs = 0L,
                payload = chosen,
            ),
        )
    }
}
