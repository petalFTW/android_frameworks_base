/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.media.controls.ui.view

import android.platform.test.annotations.DisableFlags
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.controls.ui.controller.MediaCarouselController
import com.android.systemui.media.controls.ui.controller.MediaCarouselControllerLogger
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.controller.MediaHostStatesManager
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@DisableFlags(Flags.FLAG_MEDIA_CONTROLS_IN_COMPOSE)
class MediaHostTest : SysuiTestCase() {
    private val dataManager = mock(MediaDataManager::class.java)
    private val hierarchy = mock(MediaHierarchyManager::class.java)
    private val carousel = mock(MediaCarouselController::class.java)
    private val states = mock(MediaHostStatesManager::class.java)
    private lateinit var hostView: UniqueObjectHostView

    @Before
    fun setUp() {
        hostView = UniqueObjectHostView(mContext)
        whenever(hierarchy.register(any())).thenReturn(hostView)
        whenever(carousel.isLockedAndHidden()).thenReturn(true)
    }

    @Test
    fun hiddenLockscreenSetting_doesNotHideQuickSettingsMedia() {
        whenever(dataManager.hasActiveMedia()).thenReturn(true)
        val host = createHost(MediaHierarchyManager.LOCATION_QQS)

        assertThat(host.visible).isTrue()
        assertThat(hostView.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun hiddenLockscreenSetting_doesNotHideExpandedQuickSettingsMedia() {
        whenever(dataManager.hasActiveMedia()).thenReturn(true)
        val host = createHost(MediaHierarchyManager.LOCATION_QS)

        assertThat(host.visible).isTrue()
    }

    @Test
    fun hiddenLockscreenSetting_stillHidesLockscreenHost() {
        whenever(dataManager.hasActiveMedia()).thenReturn(true)
        val host = createHost(MediaHierarchyManager.LOCATION_LOCKSCREEN)

        assertThat(host.visible).isFalse()
        assertThat(hostView.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun mediaBecomesActive_updatesDetachedHostWithoutExpandCollapse() {
        val host = createHost(MediaHierarchyManager.LOCATION_QQS)
        assertThat(host.visible).isFalse()
        val listener = ArgumentCaptor.forClass(MediaDataManager.Listener::class.java)
        verify(dataManager).addListener(listener.capture())
        whenever(dataManager.hasActiveMedia()).thenReturn(true)

        listener.value.onMediaDataLoaded("track", null, mock(MediaData::class.java), true)

        assertThat(host.visible).isTrue()
    }

    private fun createHost(location: Int): MediaHost =
        MediaHost(
            MediaHost.MediaHostStateHolder(),
            hierarchy,
            dataManager,
            states,
            carousel,
            mock(MediaCarouselControllerLogger::class.java),
        ).apply {
            showsOnlyActiveMedia = true
            init(location)
        }
}
