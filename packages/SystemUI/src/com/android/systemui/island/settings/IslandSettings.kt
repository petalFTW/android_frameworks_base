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

package com.android.systemui.island.settings

import android.content.Context
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Animation mode mirrors the three-option "Animation selection" setting. */
enum class AnimationMode { DYNAMIC, CLASSIC, NONE }

/**
 * Thin, reactive wrapper over the `island_*` Settings.Secure keys.
 */
@SysUISingleton
class IslandSettings @Inject constructor(
    @Application private val context: Context,
) {
    companion object {
        const val KEY_ENABLED = "island_enabled"
        const val KEY_ANIMATION_MODE = "island_animation_mode"
        const val KEY_LEFT_ENABLED = "island_left_enabled"
        const val KEY_RIGHT_ENABLED = "island_right_enabled"
        const val KEY_REPLACE_HEADS_UP = "island_replace_heads_up"
        const val KEY_SHOW_ON_LOCKSCREEN = "island_show_on_lockscreen"
        const val KEY_SHOW_ON_AOD = "island_show_on_aod"
        const val KEY_SHOW_IN_FULLSCREEN = "island_show_in_fullscreen"
        const val KEY_LANDSCAPE_MODE = "island_landscape_mode"
        const val KEY_NOTIF_DWELL_MS = "island_notif_dwell_ms"
        const val KEY_EXPAND_HIGH_IMPORTANCE = "island_expand_high_importance"
        const val KEY_MEDIA_ENABLED = "island_media_enabled"
        const val KEY_MEDIA_TINT = "island_media_tint"
        const val KEY_TORCH_ENABLED = "island_torch_enabled"
        const val KEY_CHARGING_ENABLED = "island_charging_enabled"
        const val KEY_VOLUME_ENABLED = "island_volume_enabled"
        const val KEY_BLOCKED_PACKAGES = "island_blocked_packages"
        const val KEY_LEFT_MARGIN_DP = "island_left_margin_dp"
        const val KEY_RIGHT_MARGIN_DP = "island_right_margin_dp"
        const val KEY_TOP_OFFSET_DP = "island_top_offset_dp"
        const val KEY_HAPTICS = "island_haptics"
        const val KEY_GLASS_NOTIFICATIONS = "island_glass_notifications"
    }

    private val _enabled = MutableStateFlow(isEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _animationMode = MutableStateFlow(animationMode())
    val animationMode: StateFlow<AnimationMode> = _animationMode.asStateFlow()

    private val observer =
        object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                _enabled.value = isEnabled()
                _animationMode.value = animationMode()
            }
        }

    fun start() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(KEY_ENABLED), false, observer
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(KEY_ANIMATION_MODE), false, observer
        )
    }

    fun isEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, 1) != 0

    fun animationMode(): AnimationMode =
        when (Settings.Secure.getInt(context.contentResolver, KEY_ANIMATION_MODE, 0)) {
            1 -> AnimationMode.CLASSIC
            2 -> AnimationMode.NONE
            else -> AnimationMode.DYNAMIC
        }

    fun leftEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_LEFT_ENABLED, 1) != 0

    fun rightEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_RIGHT_ENABLED, 1) != 0

    fun replaceHeadsUp(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_REPLACE_HEADS_UP, 1) != 0

    fun showOnLockscreen(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_SHOW_ON_LOCKSCREEN, 1) != 0

    fun showOnAod(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_SHOW_ON_AOD, 0) != 0

    fun showInFullscreen(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_SHOW_IN_FULLSCREEN, 0) != 0

    fun notifDwellMs(): Long =
        Settings.Secure.getLong(context.contentResolver, KEY_NOTIF_DWELL_MS, 3000L)

    fun expandHighImportance(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_EXPAND_HIGH_IMPORTANCE, 1) != 0

    fun mediaEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_MEDIA_ENABLED, 1) != 0

    fun mediaTint(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_MEDIA_TINT, 1) != 0

    fun torchEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_TORCH_ENABLED, 1) != 0

    fun chargingEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_CHARGING_ENABLED, 1) != 0

    fun volumeEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_VOLUME_ENABLED, 0) != 0

    fun haptics(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_HAPTICS, 1) != 0

    fun glassNotifications(): Boolean =
        Settings.Secure.getInt(context.contentResolver, KEY_GLASS_NOTIFICATIONS, 1) != 0

    fun leftMarginDp(): Int =
        Settings.Secure.getInt(context.contentResolver, KEY_LEFT_MARGIN_DP, 12)

    fun rightMarginDp(): Int =
        Settings.Secure.getInt(context.contentResolver, KEY_RIGHT_MARGIN_DP, 12)

    fun topOffsetDp(): Int =
        Settings.Secure.getInt(context.contentResolver, KEY_TOP_OFFSET_DP, 4)

    fun blockedPackages(): Set<String> =
        Settings.Secure.getString(context.contentResolver, KEY_BLOCKED_PACKAGES)
            ?.split(';')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
}
