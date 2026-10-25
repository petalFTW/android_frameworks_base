/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos.depth

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.petalos.config.DepthClockMaterials

@SmallTest
@RunWith(AndroidJUnit4::class)
class PetalDepthBouncerClockTest : SysuiTestCase() {
    @Test
    @UiThreadTest
    fun compactClockRequiresAnAppliedDepthWallpaperAndEnabledClock() {
        val clock = PetalDepthClockView(context)
        clock.setStyle(PetalDepthClockView.STYLE_SOLID)
        assertThat(clock.supportsBouncerClock).isFalse()

        clock.setDepthApplied(true)
        assertThat(clock.supportsBouncerClock).isTrue()

        clock.setStyle(0)
        assertThat(clock.supportsBouncerClock).isFalse()

        clock.setStyle(PetalDepthClockView.STYLE_SOLID)
        clock.setDepthApplied(false)
        assertThat(clock.supportsBouncerClock).isFalse()
    }

    @Test
    fun squareClocksUseTheirMatchingHorizontalMaterial() {
        assertThat(PetalDepthClockView.compactStyleFor(DepthClockMaterials.FROST_SQUARE))
            .isEqualTo(DepthClockMaterials.FROST)
        assertThat(PetalDepthClockView.compactStyleFor(DepthClockMaterials.SMOKE_SQUARE))
            .isEqualTo(DepthClockMaterials.SMOKE)
        assertThat(PetalDepthClockView.compactStyleFor(DepthClockMaterials.BLOOM_SQUARE))
            .isEqualTo(DepthClockMaterials.BLOOM)
        assertThat(PetalDepthClockView.compactStyleFor(DepthClockMaterials.LIQUID))
            .isEqualTo(DepthClockMaterials.LIQUID)
        assertThat(PetalDepthClockView.compactStyleFor(0)).isNull()
        assertThat(PetalDepthClockView.compactStyleFor(DepthClockMaterials.LAST + 1)).isNull()
    }

    @Test
    @UiThreadTest
    fun morphStartsWithExactlyTheOriginalClockForEveryStyleAndLayout() {
        val clock = PetalDepthClockView(context).apply {
            layout(0, 0, 540, 1200)
            setDepthApplied(true)
            visibility = View.VISIBLE
            startTicking()
            stopTicking()
        }
        val original = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888)
        val morph = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888)
        try {
            for (style in PetalDepthClockView.STYLE_SOLID..DepthClockMaterials.LAST) {
                clock.setStyle(style)
                for (stacked in listOf(false, true)) {
                    clock.setStackVertical(stacked)
                    original.eraseColor(android.graphics.Color.TRANSPARENT)
                    morph.eraseColor(android.graphics.Color.TRANSPARENT)
                    clock.draw(Canvas(original))
                    clock.drawStackedMorph(Canvas(morph), 0f,
                        android.graphics.RectF(100f, 300f, 500f, 380f))
                    assertThat(morph.sameAs(original)).isTrue()
                }
            }
        } finally {
            original.recycle()
            morph.recycle()
        }
    }

    @Test
    @UiThreadTest
    fun smokeHostKeepsTheOriginalWallpaperSamplingCoordinates() {
        val backdrop = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888)
        val original = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        val hosted = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        val clock = spy(PetalDepthClockView(context).apply {
            layout(0, 0, 540, 1200)
            setStyle(DepthClockMaterials.SMOKE)
            setDepthApplied(true)
            setDateEnabled(false)
            setBackdrop(backdrop)
        })
        val host = spy(View(context).apply { layout(100, 300, 500, 380) })
        doAnswer { call ->
            call.getArgument<IntArray>(0).apply {
                this[0] = 0
                this[1] = 0
            }
            null
        }.`when`(clock).getLocationOnScreen(any(IntArray::class.java))
        doAnswer { call ->
            call.getArgument<IntArray>(0).apply {
                this[0] = 100
                this[1] = 300
            }
            null
        }.`when`(host).getLocationOnScreen(any(IntArray::class.java))
        try {
            val paper = Canvas(backdrop)
            paper.drawColor(android.graphics.Color.RED)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
            paper.drawRect(0f, 300f, 540f, 1200f, paint)
            clock.setBouncerHost(host)
            val canvas = Canvas(original)
            canvas.translate(-100f, -300f)
            clock.drawStackedMorph(canvas, 1f,
                android.graphics.RectF(100f, 300f, 500f, 380f))

            assertThat(clock.drawBouncerClock(Canvas(hosted), host)).isTrue()
            assertThat(hosted.sameAs(original)).isTrue()
        } finally {
            backdrop.recycle()
            original.recycle()
            hosted.recycle()
        }
    }

    @Test
    @UiThreadTest
    fun firstTransparentAnimationFrameKeepsThePromptVisible() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val clock = spy(PetalDepthClockView(context).apply {
            layout(0, 0, 1080, 2400)
            setStyle(PetalDepthClockView.STYLE_SOLID)
            setDepthApplied(true)
        })
        doReturn(true).`when`(clock).isAttachedToWindow
        val host = View(context).apply { layout(100, 300, 500, 380) }
        try {
            clock.setBouncerHost(host)
            assertThat(clock.drawBouncerClock(mock(Canvas::class.java), host)).isFalse()
        } finally {
            clock.setDepthApplied(false)
        }
    }

    @Test
    @UiThreadTest
    fun missingPromptHeightKeepsThePromptFallback() {
        val clock = PetalDepthClockView(context).apply {
            layout(0, 0, 1080, 2400)
            setStyle(PetalDepthClockView.STYLE_SOLID)
            setDepthApplied(true)
        }
        val host = View(context).apply { layout(100, 300, 500, 300) }
        clock.setBouncerHost(host)
        assertThat(clock.drawBouncerClock(mock(Canvas::class.java), host)).isFalse()
    }

    @Test
    @UiThreadTest
    fun scrolledPromptHostDrawsClockInsideVisibleBounds() {
        val clock = PetalDepthClockView(context).apply {
            layout(0, 0, 1080, 2400)
            setDepthApplied(true)
        }
        val host = View(context).apply {
            layout(100, 300, 500, 380)
            // Single-line TextView centers its text in a VERY_WIDE layout.
            scrollTo(524000, 12)
        }
        val bitmap = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        val blank = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        try {
            clock.setBouncerHost(host)
            for (style in PetalDepthClockView.STYLE_SOLID..DepthClockMaterials.LAST) {
                clock.setStyle(style)
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                val canvas = Canvas(bitmap)
                // Match the canvas View supplies to onDraw after applying host scroll.
                canvas.translate(-host.scrollX.toFloat(), -host.scrollY.toFloat())

                assertThat(clock.drawBouncerClock(canvas, host)).isTrue()
                assertThat(bitmap.sameAs(blank)).isFalse()
            }
        } finally {
            bitmap.recycle()
            blank.recycle()
        }
    }

    @Test
    @UiThreadTest
    fun promptHostDrawsClockAndReleasesItForFallback() {
        val clock = PetalDepthClockView(context).apply {
            layout(0, 0, 1080, 2400)
            setStyle(PetalDepthClockView.STYLE_SOLID)
            setDepthApplied(true)
            setStackVertical(true)
        }
        val host = View(context).apply { layout(100, 300, 500, 380) }
        val bitmap = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        val blank = Bitmap.createBitmap(400, 80, Bitmap.Config.ARGB_8888)
        try {
            clock.setBouncerHost(host)
            assertThat(clock.drawBouncerClock(Canvas(bitmap), host)).isTrue()
            assertThat(bitmap.sameAs(blank)).isFalse()

            clock.releaseBouncerHost(host)
            assertThat(clock.drawBouncerClock(Canvas(bitmap), host)).isFalse()

            clock.setBouncerHost(host)
            clock.setDepthApplied(false)
            assertThat(clock.drawBouncerClock(Canvas(bitmap), host)).isFalse()
        } finally {
            bitmap.recycle()
            blank.recycle()
        }
    }
}
