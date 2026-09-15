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
import android.view.Display
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.settings.IslandSettings
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
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
    private val statusBarModeRepository: StatusBarModeRepositoryStore,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var currentId: String? = null
    private var controllers: List<MediaController> = emptyList()
    private var fullscreen = false
    private var started = false

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
        if (started) return
        started = true
        msm.addOnActiveSessionsChangedListener(listener, null, handler)
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
        if (!settings.mediaEnabled()) return
        if (fullscreen) {
            currentId?.let { router.removeSignal(it) }
            currentId = null
            return
        }
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        // only playing or buffering counts, a stale paused session would pin the blob forever
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
        val isNew = id != currentId
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
