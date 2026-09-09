/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.island.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.SystemChipPayload
import com.android.systemui.island.settings.IslandSettings
import com.android.systemui.res.R
import javax.inject.Inject

class ExtraKeySignalSource @Inject constructor(
    @Application private val context: Context,
    private val settings: IslandSettings,
    private val router: SignalRouter,
) {
    private var started = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!settings.enabled.value) return
            val action = intent.getStringExtra("action") ?: return
            val visual = when (action) {
                "MENU" -> R.string.petal_key_menu to R.drawable.ic_menu
                "APP_SWITCH" -> R.string.petal_key_app_switch to R.drawable.petal_key_recents
                "SEARCH" -> R.string.petal_key_search to R.drawable.ic_mic_26dp
                "VOICE_SEARCH" -> R.string.petal_key_voice_search to R.drawable.ic_mic_26dp
                "IN_APP_SEARCH" -> R.string.petal_key_in_app_search to R.drawable.petal_key_search
                "LAUNCH_CAMERA" -> R.string.petal_key_launch_camera to R.drawable.ic_camera
                "SLEEP" -> R.string.petal_key_sleep to R.drawable.ic_power_saver
                "LAST_APP" -> R.string.petal_key_last_app to R.drawable.petal_key_recents
                "SPLIT_SCREEN" -> R.string.petal_key_split_screen to R.drawable.petal_key_split
                "KILL_APP" -> R.string.petal_key_kill_app to R.drawable.ic_close
                "PLAY_PAUSE_MUSIC" -> R.string.petal_key_play_pause_music to R.drawable.petal_key_play_pause
                "SCREENSHOT" -> R.string.petal_key_screenshot to R.drawable.petal_key_screenshot
                "PARTIAL_SCREENSHOT" -> R.string.petal_key_partial_screenshot to R.drawable.petal_key_screenshot
                "FLASHLIGHT" -> R.string.petal_key_flashlight to R.drawable.petal_key_flashlight
                "NEXT_TRACK" -> R.string.petal_key_next_track to R.drawable.ic_media_next
                "PREV_TRACK" -> R.string.petal_key_prev_track to R.drawable.ic_media_prev
                "VOLUME_UP" -> R.string.petal_key_volume_up to R.drawable.ic_volume_media
                "VOLUME_DOWN" -> R.string.petal_key_volume_down to R.drawable.ic_volume_media_low
                "NOTIFICATIONS" -> R.string.petal_key_notifications to R.drawable.ic_notifications_alert
                "RINGER_MODE" -> when (intent.getIntExtra("ringer_mode", -1)) {
                    AudioManager.RINGER_MODE_NORMAL ->
                        R.string.petal_key_ring to R.drawable.ic_volume_ringer
                    AudioManager.RINGER_MODE_VIBRATE ->
                        R.string.petal_key_vibrate to R.drawable.ic_volume_ringer_vibrate
                    AudioManager.RINGER_MODE_SILENT ->
                        R.string.petal_key_silent to R.drawable.ic_volume_ringer_mute
                    else -> return
                }
                else -> return
            }
            router.emit(IslandSignal(
                id = "extra_key",
                kind = SignalKind.EXTRA_KEY,
                cluster = Cluster.RIGHT,
                priority = 85,
                initialForm = Form.CAPSULE,
                ttlMs = 1600L,
                payload = SystemChipPayload(
                    label = context.getString(visual.first),
                    accent = 0xFF64D2FF.toInt(),
                    iconRes = visual.second,
                ),
            ))
        }
    }

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(
            receiver,
            IntentFilter("org.petalos.action.EXTRA_KEY_FEEDBACK"),
            android.Manifest.permission.STATUS_BAR_SERVICE,
            null,
            Context.RECEIVER_EXPORTED,
        )
    }
}
