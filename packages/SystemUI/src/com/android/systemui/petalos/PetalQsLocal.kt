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

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import org.petalos.config.PetalConfig

// optional media slot in the tile grid
val LocalPetalMediaRow =
    staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

data class PetalQsPreferences(
    val provided: Boolean = false,
    val enabled: Boolean = false,
    val reach: String = PetalConfig.QS_REACH_BALANCED,
    val emission: Int = PetalConfig.DEFAULT_QS_EMISSION,
    val motionEnabled: Boolean = true,
)

val LocalPetalQsPreferences = staticCompositionLocalOf { PetalQsPreferences() }
val LocalPetalNarrowMedia = staticCompositionLocalOf { false }
val LocalPetalMediaGlass = staticCompositionLocalOf { false }
val LocalPetalMediaReveal = staticCompositionLocalOf<PetalQsReveal?> { null }

@Composable
fun rememberPetalQsPreferences(): PetalQsPreferences {
    val context = LocalContext.current
    fun read() = PetalQsPreferences(
        provided = true,
        enabled = PetalConfig.isQsSkinEnabled(context),
        reach = PetalConfig.getQsReachMode(context),
        emission = PetalConfig.getQsEmission(context),
        motionEnabled = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f,
    )
    var preferences by remember(context) { mutableStateOf(read()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                preferences = read()
            }
        }
        val resolver = context.contentResolver
        listOf(
            PetalConfig.KEY_QS_SKIN_ENABLED,
            PetalConfig.KEY_QS_REACH_MODE,
            PetalConfig.KEY_QS_EMISSION,
            "petal_qs_active_intensity",
        ).forEach { resolver.registerContentObserver(Settings.System.getUriFor(it), false, observer) }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer
        )
        // Read after subscribing; Hub can change this while we're opening.
        preferences = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return preferences
}

@Composable
fun petalQsPreferences(): PetalQsPreferences =
    LocalPetalQsPreferences.current.takeIf { it.provided } ?: rememberPetalQsPreferences()
