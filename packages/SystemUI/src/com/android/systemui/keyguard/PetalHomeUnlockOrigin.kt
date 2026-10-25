/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard

import android.content.res.Resources
import android.graphics.PointF
import android.hardware.biometrics.BiometricSourceType
import android.view.Surface
import android.view.WindowManager
import com.android.systemui.biometrics.domain.interactor.FingerprintPropertyInteractor
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_DISMISS_BOUNCER
import dagger.Lazy
import javax.inject.Inject

class PetalHomeUnlockOrigin @Inject constructor(
    @Main private val resources: Resources,
    private val windowManager: WindowManager,
    private val fingerprintProperties: FingerprintPropertyInteractor,
    private val biometricUnlockController: Lazy<BiometricUnlockController>,
) {
    fun sendTo(launcher: ILauncherUnlockAnimationController) {
        val origin = resolve()
        launcher.setUnlockOrigin(origin.x, origin.y)
    }

    private fun resolve(): PointF {
        val biometric = biometricUnlockController.get()
        val fingerprintUnlock = biometric.biometricType == BiometricSourceType.FINGERPRINT &&
            (biometric.isBiometricUnlock || biometric.mode == MODE_DISMISS_BOUNCER)
        if (!fingerprintUnlock) return PointF(0.5f, 1f)

        val sensor = fingerprintProperties.sensorLocation.value
        if (sensor.centerX <= 0f || sensor.centerY <= 0f) return PointF(0.5f, 1f)

        val metrics = resources.displayMetrics
        val rotation = windowManager.defaultDisplay.rotation
        val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val naturalWidth = if (sideways) metrics.heightPixels else metrics.widthPixels
        val naturalHeight = if (sideways) metrics.widthPixels else metrics.heightPixels
        val x = (sensor.centerX / naturalWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        val y = (sensor.centerY / naturalHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        // Sensor coords are natural portrait, the launcher might not be.
        return when (rotation) {
            Surface.ROTATION_90 -> PointF(y, 1f - x)
            Surface.ROTATION_180 -> PointF(1f - x, 1f - y)
            Surface.ROTATION_270 -> PointF(1f - y, x)
            else -> PointF(x, y)
        }
    }
}
