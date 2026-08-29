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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.SystemChipPayload
import com.android.systemui.island.settings.IslandSettings
import javax.inject.Inject

private const val ACCENT_TORCH = 0xFFFFD60A.toInt()

/**
 * Reacts to torch state changes (§11.4). Only the back-facing flash triggers the island; tapping
 * the chip toggles the torch off.
 */
class TorchSignalSource @Inject constructor(
    @Application private val context: Context,
    private val settings: IslandSettings,
    private val router: SignalRouter,
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val signalId = "torch"

    private val torchCallback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (enabled && isBackFlash(cameraId)) emitTorch(cameraId)
                else if (!enabled) router.removeSignal(signalId)
            }
        }

    fun start() {
        runCatching { cameraManager.registerTorchCallback(torchCallback, handler) }
    }

    private fun emitTorch(cameraId: String) {
        if (!settings.torchEnabled()) return
        router.emit(
            IslandSignal(
                id = signalId,
                kind = SignalKind.TORCH,
                cluster = Cluster.RIGHT,
                priority = 80,
                initialForm = Form.CAPSULE,
                ttlMs = 0L,
                payload = SystemChipPayload(
                    label = "Torch",
                    accent = ACCENT_TORCH,
                    toggleAction = {
                        runCatching { cameraManager.setTorchMode(cameraId, false) }
                    },
                ),
            ),
        )
    }

    private fun isBackFlash(cameraId: String): Boolean {
        return runCatching {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(false)
    }
}
