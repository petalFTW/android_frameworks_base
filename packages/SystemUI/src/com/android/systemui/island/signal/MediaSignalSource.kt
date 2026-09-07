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

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.settings.IslandSettings
import javax.inject.Inject

/**
 * Tracks active media sessions via [MediaSessionManager] and emits MEDIA signals (§11.2).
 *
 * [MediaSessionManager.OnActiveSessionsChangedListener] only fires when the *set* of active
 * sessions changes — not when a session's playback state changes. Music apps routinely
 * register their session while its [PlaybackState] is still NONE and only transition to
 * PLAYING afterwards, so the blob must also react to per-controller
 * [MediaController.Callback.onPlaybackStateChanged] events (bug: media blob appeared for
 * video apps but never for music).
 */
class MediaSignalSource @Inject constructor(
    @Application private val context: Context,
    private val settings: IslandSettings,
    private val router: SignalRouter,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var currentId: String? = null
    private var controllers: List<MediaController> = emptyList()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            reevaluate()
        }
    }

    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        controllers = list.orEmpty()
        for (controller in controllers) {
            controller.registerCallback(controllerCallback, handler)
        }
        reevaluate()
    }

    fun start() {
        msm.addOnActiveSessionsChangedListener(listener, null, handler)
    }

    private fun reevaluate() {
        if (!settings.mediaEnabled()) return
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        // Only a session that is actually playing (or buffering) keeps the blob alive. A session
        // left in a stopped/none state after its app is swiped away from recents must not keep the
        // media blob on screen, so there is deliberately no `controllers.firstOrNull()` fallback.
        val chosen = playing
            ?: controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_BUFFERING
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
        currentId = id
        router.emit(
            IslandSignal(
                id = id,
                kind = SignalKind.MEDIA,
                cluster = Cluster.RIGHT,
                priority = if (isPlaying) 70 else 65,
                initialForm = if (isPlaying) Form.CAPSULE else Form.CHIP,
                ttlMs = 0L,
                payload = chosen,
            ),
        )
    }
}
