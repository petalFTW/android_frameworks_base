/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard

import android.hardware.biometrics.BiometricSourceType
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.domain.interactor.FingerprintPropertyInteractor
import com.android.systemui.shared.customization.data.SensorLocation
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.BiometricUnlockController.MODE_DISMISS_BOUNCER
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class PetalHomeUnlockOriginTest : SysuiTestCase() {
    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var display: Display
    @Mock private lateinit var fingerprint: FingerprintPropertyInteractor
    @Mock private lateinit var biometric: BiometricUnlockController
    @Mock private lateinit var launcher: ILauncherUnlockAnimationController
    private lateinit var origin: PetalHomeUnlockOrigin
    private val location = MutableStateFlow(SensorLocation(0, 0, 0))

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(windowManager.defaultDisplay).thenReturn(display)
        whenever(display.rotation).thenReturn(Surface.ROTATION_0)
        whenever(fingerprint.sensorLocation).thenReturn(location)
        origin = PetalHomeUnlockOrigin(context.resources, windowManager, fingerprint, { biometric })
    }

    @Test
    fun credentialUnlock_usesBottomCentre() {
        origin.sendTo(launcher)

        verify(launcher).setUnlockOrigin(0.5f, 1f)
    }

    @Test
    fun fingerprintUnlock_usesSensorCentre() {
        val metrics = context.resources.displayMetrics
        location.value = SensorLocation(metrics.widthPixels / 2, metrics.heightPixels * 3 / 4, 20)
        whenever(biometric.biometricType).thenReturn(BiometricSourceType.FINGERPRINT)
        whenever(biometric.isBiometricUnlock).thenReturn(true)

        origin.sendTo(launcher)

        verify(launcher).setUnlockOrigin(
            location.value.centerX / metrics.widthPixels,
            location.value.centerY / metrics.heightPixels,
        )
    }

    @Test
    fun fingerprintAtBouncer_stillUsesSensorCentre() {
        val metrics = context.resources.displayMetrics
        location.value = SensorLocation(metrics.widthPixels / 2, metrics.heightPixels * 3 / 4, 20)
        whenever(biometric.biometricType).thenReturn(BiometricSourceType.FINGERPRINT)
        whenever(biometric.mode).thenReturn(MODE_DISMISS_BOUNCER)

        origin.sendTo(launcher)

        verify(launcher).setUnlockOrigin(
            location.value.centerX / metrics.widthPixels,
            location.value.centerY / metrics.heightPixels,
        )
    }

    @Test
    fun fingerprintThatOnlyWokeThePhone_doesNotOverrideCredentialOrigin() {
        whenever(biometric.biometricType).thenReturn(BiometricSourceType.FINGERPRINT)

        origin.sendTo(launcher)

        verify(launcher).setUnlockOrigin(0.5f, 1f)
    }

    @Test
    fun rotatedScreen_rotatesNaturalSensorLocation() {
        val metrics = context.resources.displayMetrics
        location.value = SensorLocation(metrics.heightPixels / 2, metrics.widthPixels * 3 / 4, 20)
        whenever(display.rotation).thenReturn(Surface.ROTATION_90)
        whenever(biometric.biometricType).thenReturn(BiometricSourceType.FINGERPRINT)
        whenever(biometric.isBiometricUnlock).thenReturn(true)

        origin.sendTo(launcher)

        verify(launcher).setUnlockOrigin(
            location.value.centerY / metrics.widthPixels,
            1f - location.value.centerX / metrics.heightPixels,
        )
    }

    @Test
    fun missingSensorLocation_fallsBackToBottom() {
        whenever(biometric.biometricType).thenReturn(BiometricSourceType.FINGERPRINT)
        whenever(biometric.isBiometricUnlock).thenReturn(true)

        origin.sendTo(launcher)

        verify(launcher).setUnlockOrigin(0.5f, 1f)
    }
}
