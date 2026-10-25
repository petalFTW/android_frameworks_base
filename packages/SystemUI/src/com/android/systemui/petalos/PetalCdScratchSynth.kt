/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.petalos

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

internal class PetalCdScratchSynth {
    private var phase = 0.0
    private var speed = 0.0
    private var amplitude = 0.0
    private var previous = 0.0
    private var highPass = 0.0
    private var lowPass = 0.0
    private val speedResponse = 1.0 - exp(-1.0 / (SAMPLE_RATE * .008))
    private val attack = 1.0 - exp(-1.0 / (SAMPLE_RATE * .01))
    private val release = 1.0 - exp(-1.0 / (SAMPLE_RATE * .03))
    private val lowPassResponse = 1.0 - exp(-2.0 * PI * 6500.0 / SAMPLE_RATE)

    val isSilent: Boolean
        get() = abs(speed) < MIN_SPEED && amplitude < .0001

    fun render(output: ShortArray, signedVelocity: Float) {
        val target = if (signedVelocity.isFinite())
            signedVelocity.coerceIn(-MAX_SPEED, MAX_SPEED).toDouble() else 0.0
        for (i in output.indices) {
            speed += (target - speed) * speedResponse
            val magnitude = abs(speed)
            val level = ((magnitude - MIN_SPEED) / (magnitude + 180.0))
                .coerceIn(0.0, 1.0) * .23
            amplitude += (level - amplitude) * if (level > amplitude) attack else release

            // one fake groove; reverse really reads the same thing backwards
            phase += speed / (360.0 * SAMPLE_RATE)
            phase -= floor(phase)
            val position = phase * 4096.0
            val index = floor(position).toInt()
            val fraction = position - index
            val grain = grain(index) * (1.0 - fraction) + grain(index + 1) * fraction
            val tooth = phase * 23.0 - floor(phase * 23.0)
            val pressure = .35 + .65 * exp(-tooth * 5.0)
            val chirp = sin(2.0 * PI * (phase * 384.0 + 9.0 * sin(2.0 * PI * phase * 3.0)))
            val overtone = sin(2.0 * PI * phase * 768.0 + .6)
            val raw = (chirp * .55 + overtone * .18 + grain * .27) * pressure

            highPass = raw - previous + .985 * highPass
            previous = raw
            lowPass += (highPass - lowPass) * lowPassResponse
            output[i] = (lowPass * amplitude * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
    }

    private fun grain(index: Int): Double {
        var bits = index and 4095
        bits = (bits xor (bits ushr 16)) * 0x45d9f3b
        bits = (bits xor (bits ushr 16)) * 0x45d9f3b
        bits = bits xor (bits ushr 16)
        return ((bits ushr 8) and 65535) / 32767.5 - 1.0
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val MIN_SPEED = 12f
        const val MAX_SPEED = 1440f
    }
}
