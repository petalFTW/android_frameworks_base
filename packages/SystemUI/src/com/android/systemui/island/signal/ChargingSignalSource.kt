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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.SystemChipPayload
import com.android.systemui.island.settings.IslandSettings
import javax.inject.Inject

private const val ACCENT_CHARGING = 0xFF30D158.toInt()

/** pops a charging chip when you plug in */
class ChargingSignalSource @Inject constructor(
    @Application private val context: Context,
    private val settings: IslandSettings,
    private val router: SignalRouter,
) {
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_POWER_CONNECTED) onCharging()
            }
        }

    fun start() {
        context.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_POWER_CONNECTED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun onCharging() {
        if (!settings.chargingEnabled()) return
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        router.emit(
            IslandSignal(
                id = "charging",
                kind = SignalKind.CHARGING,
                cluster = Cluster.RIGHT,
                priority = 50,
                initialForm = Form.CAPSULE,
                ttlMs = 1L,
                payload = SystemChipPayload("$level% \u00b7 Charging", ACCENT_CHARGING),
            ),
        )
    }
}
