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
import android.service.notification.StatusBarNotification
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

// name and call intents, scraped from the dialer notif
data class CallPayload(
    val name: String,
    val subtitle: String,
    val isIncoming: Boolean,
    val answerIntent: PendingIntent?,
    val declineIntent: PendingIntent?,
    val hangUpIntent: PendingIntent?,
    val contentIntent: PendingIntent? = null,
    val startTime: Long = 0L,
    val allowTelecomFallback: Boolean = true,
    val sourceNotification: StatusBarNotification? = null,
)

private const val ACCENT_CALL = 0xFF32D74B.toInt()
private const val ACCENT_DECLINE = 0xFFFF3B30.toInt()

// Call icon and caller when collapsed; incoming actions when expanded.
class CallPresenter(
    private val context: Context,
    private val geometry: IslandGeometry,
    private val payload: CallPayload,
) : IslandPresenter {

    private var host: IslandPresenter.Host? = null
    private val redaction = IslandRedactionBinder(context,
        payload.sourceNotification?.packageName.orEmpty(), payload.sourceNotification)

    override fun setHost(host: IslandPresenter.Host?) { this.host = host }

    private val telecomManager = context.getSystemService(android.telecom.TelecomManager::class.java)

    private fun answerCall() {
        if (payload.answerIntent?.sendSafe() != true && payload.allowTelecomFallback) {
            runCatching { telecomManager?.acceptRingingCall() }
        }
        openCall()
    }

    private fun declineCall() {
        if (payload.declineIntent?.sendSafe() == true) return
        if (payload.hangUpIntent?.sendSafe() == true) return
        if (payload.allowTelecomFallback) runCatching { telecomManager?.endCall() }
    }

    private fun hangUpCall() {
        if (payload.hangUpIntent?.sendSafe() == true) return
        if (payload.declineIntent?.sendSafe() == true) return
        if (payload.allowTelecomFallback) runCatching { telecomManager?.endCall() }
    }

    override fun bindCollapsed(container: ViewGroup): Int {
        val iconSize = geometry.dp(20f)
        val gap = geometry.dp(8f)
        val padStart = geometry.capsulePaddingStart
        val padEnd = geometry.capsulePaddingEnd
        val label = payload.name.ifBlank { "Unknown" }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padStart, 0, padEnd, 0)
        }
        row.addView(ImageView(context).apply {
            setImageResource(android.R.drawable.sym_action_call)
            setColorFilter(ACCENT_CALL)
            contentDescription = payload.subtitle
        }, LinearLayout.LayoutParams(iconSize, iconSize))
        val name = TextView(context).apply {
            text = label
            keepIdentityWhenContentRedacted()
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // hug the text, a fixed 128dp bar looked like shit for a short name
        val maxText = (geometry.capsuleMaxWidth - padStart - padEnd - iconSize - gap)
            .coerceAtLeast(geometry.dp(24f))
        val textW = (name.paint.measureText(label).toInt() + geometry.dp(2f))
            .coerceAtMost(maxText)
        val rowW = (padStart + iconSize + gap + textW + padEnd)
            .coerceIn(geometry.capsuleMinWidth, geometry.capsuleMaxWidth)
        name.layoutParams = LinearLayout.LayoutParams(
            (rowW - padStart - padEnd - iconSize - gap).coerceAtLeast(0),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = gap }
        row.addView(name)
        redaction.bind(row)
        container.addView(row, FrameLayout.LayoutParams(rowW, ViewGroup.LayoutParams.MATCH_PARENT))
        return rowW
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
            keepIdentityWhenContentRedacted()
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
            setTextColor(Color.WHITE)
            text = payload.name.ifBlank { "Incoming call" }
            keepIdentityWhenContentRedacted()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        textColumn.addView(name)
        val sub = TextView(context).apply {
            setTextColor(Color.WHITE)
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
            if (payload.allowTelecomFallback || payload.declineIntent != null ||
                payload.hangUpIntent != null
            ) {
                row.addView(decline)
            }
            if (payload.allowTelecomFallback || payload.answerIntent != null) {
                row.addView(
                    accept,
                    LinearLayout.LayoutParams(geometry.dp(44f), geometry.dp(44f)).apply {
                        marginStart = geometry.dp(6f)
                    },
                )
            }
        } else if (payload.allowTelecomFallback || payload.hangUpIntent != null ||
            payload.declineIntent != null
        ) {
            val hangUp = callButton(ACCENT_DECLINE, android.R.drawable.sym_call_missed) {
                hangUpCall()
            }
            row.addView(hangUp)
        }

        redaction.bind(row)
        container.addView(row)
    }

    override fun expandedHeightPx(): Int = geometry.expandedHeightCall

    override fun tint(): IslandTint = IslandTint(accent = ACCENT_CALL)

    override fun onDestroy() {
        redaction.destroy()
    }

    override fun onPrimaryAction(): Boolean {
        if (payload.isIncoming && payload.allowTelecomFallback) return false
        openCall()
        return true
    }

    private fun openCall() {
        val intent = payload.contentIntent
        if (intent != null && host != null) {
            host?.launchPendingIntent(intent)
            return
        }
        if (intent?.sendSafe() == true) return
        if (payload.allowTelecomFallback) runCatching { telecomManager?.showInCallScreen(false) }
    }

    private fun callButton(color: Int, glyph: Int, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            contentDescription = if (color == ACCENT_CALL) "Answer call"
                else if (payload.isIncoming) "Decline call" else "End call"
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
