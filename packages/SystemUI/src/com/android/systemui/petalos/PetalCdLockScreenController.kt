/*
 * Copyright (C) 2026 petalOS
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

package com.android.systemui.petalos

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.petalos.config.PetalConfig

// 3d cd player on the lockscreen. drives the raw MediaSession, ignores the media pipeline
@SysUISingleton
class PetalCdLockScreenController
@Inject
constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    @Main private val mainExecutor: DelayableExecutor,
    private val keyguardRootView: KeyguardRootView,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val shadeInteractor: ShadeInteractor,
) : CoreStartable {

    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val contentResolver = context.contentResolver

    // other petal bits watch this to dodge the cd
    private val cdShowingState = MutableStateFlow(false)
    val cdShowing: StateFlow<Boolean> = cdShowingState

    private val state = mutableStateOf(PetalCdState())
    private val composeView =
        ComposeView(context).apply {
            setContent {
                PetalCdMediaPlayer(
                    state = state.value,
                    onPlayPause = ::onPlayPause,
                    onPrevious = ::onPrevious,
                    onNext = ::onNext,
                    onSeek = ::onSeek,
                )
            }
        }

    private var attached = false
    private var enabled = false
    private var shadeExpanded = false
    private var trackedController: MediaController? = null
    private var artGeneration = 0

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refreshEnabled()
        }

    private val stateListener =
        object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) = updateVisibility()
            override fun onDozingChanged(isDozing: Boolean) = updateVisibility()
        }

    private val keyguardStateListener =
        object : KeyguardStateController.Callback {
            override fun onKeyguardGoingAwayChanged() = updateVisibility()
            override fun onKeyguardShowingChanged() = updateVisibility()
        }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onSessionsChanged(controllers.orEmpty())
        }

    private val controllerCallback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = refreshFromController()

            override fun onPlaybackStateChanged(playbackState: PlaybackState?) =
                refreshFromController()
        }

    private val ticker =
        object : Runnable {
            override fun run() {
                refreshPosition()
                if (state.value.playing && composeView.visibility == View.VISIBLE) {
                    handler.postDelayed(this, TICK_MS)
                }
            }
        }

    // bridge crossfade started, poke the disc
    private val bridgeTransitionReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                state.value = state.value.copy(transitionPulse = SystemClock.uptimeMillis())
            }
        }

    override fun start() {
        context.registerReceiver(
            bridgeTransitionReceiver,
            android.content.IntentFilter(ACTION_BRIDGE_TRANSITION),
            Context.RECEIVER_EXPORTED,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_LOCK_CD_PLAYER),
            false,
            settingsObserver,
        )
        statusBarStateController.addCallback(stateListener)
        keyguardStateController.addCallback(keyguardStateListener)
        scope.launch { shadeInteractor.anyExpansion.collect { onShadeExpansionChanged(it > 0f) } }
        mainExecutor.execute {
            attachView()
            refreshEnabled()
        }
    }

    private fun attachView() {
        if (attached) return
        val root = keyguardRootView as? ConstraintLayout ?: return
        val density = context.resources.displayMetrics.density
        val margin = (16f * density).toInt()
        val lp =
            ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                )
                .apply {
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    verticalBias = 0.56f
                    marginStart = margin
                    marginEnd = margin
                    // edge to edge looks gross on big screens, clamp it
                    matchConstraintMaxWidth = (360f * density).toInt()
                }
        // stable id, the blueprint binder leaves known petal layers alone
        composeView.id = com.android.systemui.res.R.id.petal_cd_player
        // Draw above the depth clock (90px) and subject layer (100px).
        composeView.elevation = 120f
        composeView.visibility = View.GONE
        root.addView(composeView, lp)
        attached = true
    }

    // depth controller asks this so its cutout can get out of the way
    val isCdShowing: Boolean
        get() = composeView.visibility == View.VISIBLE

    private val cdStateListeners = mutableListOf<(Boolean) -> Unit>()

    fun addCdStateListener(listener: (Boolean) -> Unit) {
        cdStateListeners.add(listener)
        listener(isCdShowing)
    }

    private fun refreshEnabled() {
        val now = PetalConfig.isLockCdPlayerEnabled(context)
        if (now == enabled) return
        enabled = now
        if (enabled) {
            // cover art and this both grab the lockscreen, so kill cover
            Settings.System.putInt(contentResolver, PetalConfig.KEY_LOCK_MEDIA_COVER, 0)
            // we draw it ourselves, hide the stock player
            Settings.Secure.putInt(
                contentResolver,
                Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                0,
            )
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
            onSessionsChanged(
                runCatching { sessionManager.getActiveSessions(null).orEmpty() }
                    .getOrDefault(emptyList())
            )
        } else {
            // only give the lockscreen back if cover art isn't using it
            if (!PetalConfig.isLockMediaCoverEnabled(context)) {
                Settings.Secure.putInt(
                    contentResolver,
                    Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                    1,
                )
            }
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
            trackedController?.unregisterCallback(controllerCallback)
            trackedController = null
            state.value = PetalCdState()
            stopTicker()
        }
        updateVisibility()
    }

    private fun onSessionsChanged(controllers: List<MediaController>) {
        if (!enabled) return
        val playing =
            controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull()
        if (trackedController != playing) {
            trackedController?.unregisterCallback(controllerCallback)
            trackedController = playing
            playing?.registerCallback(controllerCallback)
        }
        refreshFromController()
    }

    private fun refreshFromController() {
        val controller = trackedController
        if (controller == null) {
            state.value = PetalCdState()
            updateVisibility()
            return
        }
        val metadata = controller.metadata
        val playback = controller.playbackState
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val actions = playback?.actions ?: 0L
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        state.value =
            state.value.copy(
                hasMedia = true,
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist =
                    metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                playing = playing,
                positionMs = playback?.let { positionOf(it) } ?: 0L,
                durationMs = duration,
                canSeek = duration > 0 && (actions and PlaybackState.ACTION_SEEK_TO) != 0L,
                canPrevious = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
                canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            )
        if (metadata != null) loadArtwork(metadata)
        updateVisibility()
    }

    private fun refreshPosition() {
        val playback = trackedController?.playbackState ?: return
        state.value = state.value.copy(positionMs = positionOf(playback))
    }

    private fun positionOf(playback: PlaybackState): Long {
        val base = playback.position
        if (playback.state != PlaybackState.STATE_PLAYING) return base
        val elapsed = SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime
        return base + (elapsed * playback.playbackSpeed).toLong()
    }

    private fun loadArtwork(metadata: MediaMetadata) {
        val embedded =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (embedded != null) {
            state.value = state.value.copy(artwork = embedded.asImageBitmap())
            return
        }
        val uriString =
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (uriString == null) {
            state.value = state.value.copy(artwork = null)
            return
        }
        val generation = ++artGeneration
        scope.launch(Dispatchers.IO) {
            val bitmap = decodeArtwork(Uri.parse(uriString))
            mainExecutor.execute {
                if (generation == artGeneration) {
                    state.value = state.value.copy(artwork = bitmap?.asImageBitmap())
                }
            }
        }
    }

    private fun decodeArtwork(uri: Uri): Bitmap? =
        runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
            .onFailure { Log.w(TAG, "Failed to decode cd artwork", it) }
            .getOrNull()

    private fun updateVisibility() {
        if (!attached) return
        val onKeyguard =
            statusBarStateController.state == StatusBarState.KEYGUARD &&
                !statusBarStateController.isDozing
        // a paused session should not pin the cd on the lockscreen forever
        val visible =
            enabled &&
                state.value.hasMedia &&
                isPlayingOrBuffering() &&
                onKeyguard &&
                !shadeExpanded &&
                !keyguardStateController.isKeyguardGoingAway
        val wasShowing = composeView.visibility == View.VISIBLE
        composeView.visibility = if (visible) View.VISIBLE else View.GONE
        cdShowingState.value = visible
        if (wasShowing != visible) {
            // tell depth to drop or bring back the cutout
            for (l in cdStateListeners) l(visible)
        }
        if (visible && state.value.playing) startTicker() else stopTicker()
    }

    // buffering counts as "playing" here, only a real pause hides the cd
    private fun isPlayingOrBuffering(): Boolean {
        val playbackState = trackedController?.playbackState?.state ?: return false
        return playbackState == PlaybackState.STATE_PLAYING ||
            playbackState == PlaybackState.STATE_BUFFERING
    }

    private fun onShadeExpansionChanged(expanded: Boolean) {
        shadeExpanded = expanded
        updateVisibility()
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    private fun stopTicker() = handler.removeCallbacks(ticker)

    private fun onPlayPause() {
        val controller = trackedController ?: return
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    private fun onPrevious() {
        trackedController?.transportControls?.skipToPrevious()
    }

    private fun onNext() {
        trackedController?.transportControls?.skipToNext()
    }

    private fun onSeek(fraction: Float) {
        val controller = trackedController ?: return
        val duration = state.value.durationMs
        if (duration > 0) {
            controller.transportControls.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("PetalCdLockScreenController: enabled=$enabled visible=${composeView.visibility}")
        pw.println("  track=${state.value.title} / ${state.value.artist}")
    }

    companion object {
        private const val TAG = "PetalCdPlayer"
        private const val TICK_MS = 500L
        private const val ACTION_BRIDGE_TRANSITION = "org.rab1d.bridge.action.TRANSITION"
    }
}
