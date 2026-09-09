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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.res.R
import kotlinx.coroutines.launch

/** Full-width media followed by a pair of horizontal brightness and volume controls. */
@Composable
fun PetalMediaRow(
    brightnessViewModel: BrightnessSliderViewModel,
    media: @Composable () -> Unit,
    mediaVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sliderHeight = 56.dp

    val minGamma = BrightnessUtils.GAMMA_SPACE_MIN.toFloat()
    val maxGamma = BrightnessUtils.GAMMA_SPACE_MAX.toFloat()
    // Live gamma brightness from the slider view model (-1 until the hydrator emits).
    val gamma = brightnessViewModel.currentBrightness.value

    // Media volume: read through AudioManager, refreshed on VOLUME_CHANGED_ACTION broadcasts.
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var maxVolume by remember {
        mutableIntStateOf(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }
    var volumeBeforeMute by remember { mutableIntStateOf(volume.coerceAtLeast(1)) }
    DisposableEffect(Unit) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                }
            }
        context.registerReceiver(receiver, IntentFilter(AudioManager.VOLUME_CHANGED_ACTION))
        onDispose { context.unregisterReceiver(receiver) }
    }

    val brightnessFraction =
        if (gamma < 0) 0.5f else (gamma - minGamma) / (maxGamma - minGamma).coerceAtLeast(1f)
    val volumeFraction = if (maxVolume <= 0) 0f else volume.toFloat() / maxVolume.toFloat()

    fun updateBrightness(frac: Float, commit: Boolean) {
        val value =
            (minGamma + frac * (maxGamma - minGamma))
                .toInt()
                .coerceIn(minGamma.toInt(), maxGamma.toInt())
        scope.launch {
            val brightness = GammaBrightness(value)
            brightnessViewModel.onDrag(
                if (commit) Drag.Stopped(brightness) else Drag.Dragging(brightness)
            )
        }
    }

    fun updateVolume(frac: Float) {
        val next = (frac * maxVolume).toInt().coerceIn(0, maxVolume)
        if (next != volume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.fillMaxWidth()) {
        val mediaShape =
            androidx.compose.foundation.shape.RoundedCornerShape(PetalQsSkin.TileCardCornerRadius)
        if (mediaVisible) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(mediaShape)
                    .background(PetalQsSkin.TileGlassDark)
                    .border(1.dp, PetalQsSkin.TileGlassBorderBrush, mediaShape)
            ) {
                media()
            }
        }
        // Short horizontal controls leave the media card its full usable width.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PetalVerticalSliderPill(
                value = brightnessFraction,
                height = sliderHeight,
                orientation = Orientation.Horizontal,
                iconRes =
                    if (brightnessViewModel.autoMode) {
                        R.drawable.ic_qs_brightness_auto_on
                    } else {
                        R.drawable.ic_qs_brightness_auto_off
                    },
                contentDescriptionText = stringResource(R.string.accessibility_brightness),
                onValueChange = { updateBrightness(it, commit = false) },
                onCommit = { updateBrightness(it, commit = true) },
                onIconClick = { brightnessViewModel.onIconClick() },
                iconActionDescription =
                    stringResource(
                        if (brightnessViewModel.autoMode) R.string.petal_qs_disable_auto_brightness
                        else R.string.petal_qs_enable_auto_brightness
                    ),
                modifier = Modifier.weight(1f),
            )
            PetalVerticalSliderPill(
                value = volumeFraction,
                height = sliderHeight,
                orientation = Orientation.Horizontal,
                iconRes = if (volume <= 0) R.drawable.ic_speaker_mute else R.drawable.ic_speaker_on,
                contentDescriptionText = stringResource(R.string.petal_qs_media_volume),
                onValueChange = ::updateVolume,
                onCommit = ::updateVolume,
                onIconClick = {
                    // Restore the user's previous level after muting from this button.
                    val next =
                        if (volume > 0) {
                            volumeBeforeMute = volume
                            0
                        } else {
                            volumeBeforeMute.coerceIn(0, maxVolume)
                        }
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                },
                iconActionDescription =
                    stringResource(
                        if (volume > 0) R.string.volume_panel_hint_mute
                        else R.string.volume_panel_hint_unmute,
                        stringResource(R.string.petal_qs_media_volume),
                    ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
