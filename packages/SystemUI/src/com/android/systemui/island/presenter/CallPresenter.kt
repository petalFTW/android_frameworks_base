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

package com.android.systemui.island.presenter

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.island.IslandGeometry
import com.android.systemui.island.render.EqualizerView

// name and call intents, scraped from the dialer notif
data class CallPayload(
    val name: String,
    val subtitle: String,
    val isIncoming: Boolean,
    val answerIntent: PendingIntent?,
    val declineIntent: PendingIntent?,
    val hangUpIntent: PendingIntent?,
    val startTime: Long = 0L,
)

private const val ACCENT_CALL = 0xFF32D74B.toInt()
private const val ACCENT_DECLINE = 0xFFFF3B30.toInt()

// call card: bars when collapsed, avatar and buttons when open
class CallPresenter(
    private val context: Context,
    private val geometry: IslandGeometry,
    private val payload: CallPayload,
) : IslandPresenter {

    private var equalizer: EqualizerView? = null
    private val telecomManager = context.getSystemService(android.telecom.TelecomManager::class.java)

    private fun answerCall() {
        if (payload.answerIntent?.sendSafe() == true) return
        // no intent from the dialer, fall back to telecom
        runCatching { telecomManager?.acceptRingingCall() }
    }

    private fun declineCall() {
        if (payload.declineIntent?.sendSafe() == true) return
        if (payload.hangUpIntent?.sendSafe() == true) return
        runCatching { telecomManager?.endCall() }
    }

    private fun hangUpCall() {
        if (payload.hangUpIntent?.sendSafe() == true) return
        if (payload.declineIntent?.sendSafe() == true) return
        runCatching { telecomManager?.endCall() }
    }

    override fun bindCollapsed(container: ViewGroup): Int {
        equalizer = EqualizerView(context).apply {
            barCount = 3
            setMetrics(
                geometry.equalizerBarWidth,
                geometry.equalizerGap,
                geometry.equalizerMaxHeight,
            )
            setBarColor(ACCENT_CALL)
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            (geometry.equalizerMaxHeight * 1.2f).toInt(),
            Gravity.CENTER or Gravity.END,
        ).apply { marginEnd = geometry.capsulePaddingEnd }
        container.addView(equalizer, lp)
        return geometry.callCapsuleWidth
    }

    override fun bindExpanded(container: ViewGroup) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(geometry.expandedPadding, geometry.expandedPadding,
                geometry.expandedPadding, geometry.expandedPadding)
        }

        // just the first letter in a circle
        val avatar = TextView(context).apply {
            text = payload.name.firstOrNull()?.uppercase() ?: "?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF5B6BF0.toInt())
            }
        }
        val avatarLp = LinearLayout.LayoutParams(geometry.dp(64f), geometry.dp(64f))
        row.addView(avatar, avatarLp)

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val name = TextView(context).apply {
            text = payload.name.ifBlank { "Incoming call" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        textColumn.addView(name)
        val sub = TextView(context).apply {
            text = payload.subtitle
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.6f
            maxLines = 1
        }
        textColumn.addView(sub)
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = geometry.dp(12f)
            },
        )

        // incoming offers decline/accept, ongoing only hangs up
        if (payload.isIncoming) {
            val decline = callButton(ACCENT_DECLINE, android.R.drawable.sym_call_missed) {
                declineCall()
            }
            val accept = callButton(ACCENT_CALL, android.R.drawable.sym_call_incoming) {
                answerCall()
            }
            row.addView(decline)
            row.addView(
                accept,
                LinearLayout.LayoutParams(geometry.dp(44f), geometry.dp(44f)).apply {
                    marginStart = geometry.dp(6f)
                },
            )
        } else {
            val hangUp = callButton(ACCENT_DECLINE, android.R.drawable.sym_call_missed) {
                hangUpCall()
            }
            row.addView(hangUp)
        }

        container.addView(row)
    }

    override fun expandedHeightPx(): Int = geometry.expandedHeightCall

    override fun tint(): IslandTint = IslandTint(accent = ACCENT_CALL)

    override fun onPrimaryAction(): Boolean {
        // tapping a call card just collapses it
        return false
    }

    override fun onDestroy() {
        equalizer = null
    }

    private fun callButton(color: Int, glyph: Int, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(glyph)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            setPadding(geometry.dp(12f), geometry.dp(12f), geometry.dp(12f), geometry.dp(12f))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(geometry.dp(44f), geometry.dp(44f))
        }

    private fun PendingIntent.sendSafe(): Boolean = runCatching { send() }.isSuccess
}
