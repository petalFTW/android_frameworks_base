/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.petalos

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal object PetalCdScratchAudio {
    suspend fun play(velocity: () -> Float) = withContext(Dispatchers.Default) {
        val minimum = AudioTrack.getMinBufferSize(
            PetalCdScratchSynth.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "Scratch output unavailable: $minimum" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(PetalCdScratchSynth.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, 1920))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Scratch output not initialized" }
            val synth = PetalCdScratchSynth()
            val samples = ShortArray(240)
            track.play()
            while (true) {
                currentCoroutineContext().ensureActive()
                synth.render(samples, velocity())
                var offset = 0
                while (offset < samples.size) {
                    currentCoroutineContext().ensureActive()
                    // blocking writes would hang around after the lockscreen disappears
                    val written = track.write(samples, offset, samples.size - offset,
                        AudioTrack.WRITE_NON_BLOCKING)
                    check(written >= 0) { "Scratch output failed: $written" }
                    if (written == 0) delay(2) else offset += written
                }
                if (synth.isSilent && abs(velocity()) < PetalCdScratchSynth.MIN_SPEED) break
            }
        } finally {
            track.release()
        }
    }
}
