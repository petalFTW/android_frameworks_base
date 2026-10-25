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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.android.settingslib.display.BrightnessUtils
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.res.R
import kotlinx.coroutines.launch
import org.petalos.config.PetalConfig

// The sliders are back on the sides where they belong.
@Composable
fun PetalMediaRow(
    brightnessViewModel: BrightnessSliderViewModel,
    media: @Composable () -> Unit,
    mediaVisible: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reveal = rememberPetalQsReveal()
    var dragging by remember { mutableStateOf(emptySet<String>()) }
    val preferences = petalQsPreferences()
    var reach by remember { mutableStateOf(preferences.reach) }
    LaunchedEffect(preferences.reach, dragging) {
        if (dragging.isEmpty()) reach = preferences.reach
    }

    val minGamma = BrightnessUtils.GAMMA_SPACE_MIN.toFloat()
    val maxGamma = BrightnessUtils.GAMMA_SPACE_MAX.toFloat()
    // gamma comes in as -1 until it loads
    val gamma = brightnessViewModel.currentBrightness.value

    // volume pulled straight from audiomanager
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

    val density = LocalDensity.current
    val fontScale = LocalConfiguration.current.fontScale
    val layoutDirection = LocalLayoutDirection.current
    var mediaHeight by remember(density, fontScale) { mutableStateOf(272.dp) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sideSliders = mediaVisible && maxWidth >= 320.dp && fontScale <= 1.3f
        val sliderHeight = if (sideSliders) mediaHeight else 48.dp
        val orientation = if (sideSliders) Orientation.Vertical else Orientation.Horizontal
        val brightness: @Composable (Modifier) -> Unit = { sliderModifier ->
            PetalVerticalSliderPill(
                value = brightnessFraction,
                height = sliderHeight,
                width = 40.dp,
                orientation = orientation,
                onDraggingChanged = {
                    dragging = if (it) dragging + "brightness" else dragging - "brightness"
                },
                reveal = reveal,
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
                modifier = sliderModifier,
            )
        }
        val volumeSlider: @Composable (Modifier) -> Unit = { sliderModifier ->
            PetalVerticalSliderPill(
                value = volumeFraction,
                height = sliderHeight,
                width = 40.dp,
                orientation = orientation,
                onDraggingChanged = {
                    dragging = if (it) dragging + "volume" else dragging - "volume"
                },
                reveal = reveal,
                iconRes = if (volume <= 0) R.drawable.ic_speaker_mute else R.drawable.ic_speaker_on,
                contentDescriptionText = stringResource(R.string.petal_qs_media_volume),
                onValueChange = ::updateVolume,
                onCommit = ::updateVolume,
                onIconClick = {
                    // unmute goes back to the old level
                    val next =
                        if (volume > 0) {
                            volumeBeforeMute = volume
                            0
                        } else {
                            volumeBeforeMute.coerceIn(0, maxVolume)
                        }
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                    volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                },
                iconActionDescription =
                    stringResource(
                        if (volume > 0) R.string.volume_panel_hint_mute
                        else R.string.volume_panel_hint_unmute,
                        stringResource(R.string.petal_qs_media_volume),
                    ),
                modifier = sliderModifier,
            )
        }
        val mediaCard: @Composable (Modifier) -> Unit = { cardModifier ->
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
            Box(
                cardModifier.clip(shape)
                    .petalQsSurfaceReveal(reveal)
                    .onSizeChanged { mediaHeight = with(density) { it.height.toDp() } }
            ) {
                CompositionLocalProvider(
                    LocalPetalNarrowMedia provides sideSliders,
                    LocalPetalMediaGlass provides true,
                     LocalPetalMediaReveal provides reveal,
                    LocalPetalMediaStyle provides
                        if (compact) PetalMediaStyle.Mini else PetalMediaStyle.LargeProgress,
                ) { media() }
            }
        }
        if (sideSliders) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()) {
                    if (reach != PetalConfig.QS_REACH_RIGHT) brightness(Modifier)
                    if (reach == PetalConfig.QS_REACH_LEFT) volumeSlider(Modifier)
                    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                        mediaCard(Modifier.weight(1f))
                    }
                    if (reach == PetalConfig.QS_REACH_RIGHT) brightness(Modifier)
                    if (reach != PetalConfig.QS_REACH_LEFT) volumeSlider(Modifier)
                }
            }
        } else {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mediaVisible) mediaCard(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    brightness(Modifier.weight(1f))
                    volumeSlider(Modifier.weight(1f))
                }
            }
        }
    }
}
