/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.petalos

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PetalCdScratchSynthTest : SysuiTestCase() {
    @Test
    fun stoppedDiscProducesSilence() {
        val synth = PetalCdScratchSynth()
        val samples = ShortArray(4800)
        synth.render(samples, 0f)
        assertTrue(samples.all { it == 0.toShort() })
        assertTrue(synth.isSilent)
    }

    @Test
    fun directionChangesTheGrooveTraversal() {
        val forward = settledSamples(360f)
        val backward = settledSamples(-360f)
        assertTrue(energy(forward) > 0.0)
        assertTrue(energy(backward) > 0.0)
        assertFalse(forward.contentEquals(backward))
        assertFalse(forward.indices.all { forward[it].toInt() == -backward[it].toInt() })
    }

    @Test
    fun fasterScratchHasMoreEnergy() {
        assertTrue(energy(settledSamples(720f)) > energy(settledSamples(180f)))
    }

    @Test
    fun releaseAndInvalidVelocitySettleToSilence() {
        for (stop in floatArrayOf(0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val synth = PetalCdScratchSynth()
            synth.render(ShortArray(4800), 720f)
            assertFalse(synth.isSilent)
            synth.render(ShortArray(24000), stop)
            val tail = ShortArray(240)
            synth.render(tail, stop)
            assertTrue(synth.isSilent)
            assertTrue(tail.all { it == 0.toShort() })
        }
    }

    @Test
    fun extremeGestureSpeedIsBounded() {
        assertArrayEquals(settledSamples(PetalCdScratchSynth.MAX_SPEED), settledSamples(Float.MAX_VALUE))
        assertArrayEquals(settledSamples(-PetalCdScratchSynth.MAX_SPEED), settledSamples(-Float.MAX_VALUE))
        val samples = settledSamples(PetalCdScratchSynth.MAX_SPEED)
        assertTrue(samples.all { it > Short.MIN_VALUE && it < Short.MAX_VALUE })
    }

    private fun settledSamples(speed: Float): ShortArray {
        val synth = PetalCdScratchSynth()
        synth.render(ShortArray(4800), speed)
        return ShortArray(4800).also { synth.render(it, speed) }
    }

    private fun energy(samples: ShortArray): Double =
        samples.sumOf { it.toDouble() * it.toDouble() } / samples.size
}
