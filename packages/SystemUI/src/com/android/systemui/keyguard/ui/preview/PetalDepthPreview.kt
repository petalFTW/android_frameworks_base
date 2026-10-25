/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.keyguard.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import com.android.systemui.petalos.depth.PetalDepthClockView
import com.android.systemui.petalos.depth.PetalDepthSubjectView
import org.petalos.config.PetalConfig

// shows the actual depth lockscreen in the customization preview, clock and all
object PetalDepthPreview {
    private const val TAG = "PetalDepthPreview"
    private val handler = Handler(Looper.getMainLooper())

    fun isEnabled(context: Context): Boolean =
        PetalConfig.isDepthEnabled(context) && PetalConfig.getDepthCutoutUri(context) != null

    fun attach(context: Context, root: ViewGroup) {
        val uri = PetalConfig.getDepthCutoutUri(context)?.let { Uri.parse(it) } ?: return
        val subject = PetalDepthSubjectView(context)
        val clock = PetalDepthClockView(context)
        applyClock(context, clock)
        clock.attachToRoot(root)
        subject.attachToRoot(root)
        clock.startTicking()
        // decode off the main thread or the preview janks
        val thread = Thread {
            val cutout = decode(context, uri)
            val frost = runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse("content://org.petalos.depth/frost.png"))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            val liquid = runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse("content://org.petalos.depth/liquid.png"))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            handler.post {
                subject.setSubject(cutout)
                clock.setBackdrop(frost)
                clock.setLiquidBackdrop(liquid)
            }
        }
        thread.name = "PetalDepthPreview"
        thread.start()
    }

    private fun applyClock(context: Context, clock: PetalDepthClockView) {
        clock.setStyle(PetalConfig.getDepthClockStyle(context))
        clock.setStackVertical(PetalConfig.isDepthClockStackVertical(context))
        clock.setAnchor(PetalConfig.getDepthClockAnchorPct(context) / 100f)
        clock.setAnchorX(PetalConfig.getDepthClockXPct(context) / 100f)
        clock.setScale(PetalConfig.getDepthClockScalePct(context) / 100f)
        val color = PetalConfig.getDepthClockColor(context)
        clock.setAccentColor(if (color == PetalConfig.DISABLED) 0 else color)
        clock.setDateEnabled(PetalConfig.isDepthClockDateEnabled(context))
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.onFailure { Log.w(TAG, "Failed to load depth cutout $uri", it) }.getOrNull()
}
