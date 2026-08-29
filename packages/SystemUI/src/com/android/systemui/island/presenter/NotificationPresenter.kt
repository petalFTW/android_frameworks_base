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
import android.graphics.drawable.Icon
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.island.IslandGeometry

/** Display data extracted from a notification by [NotificationSignalSource]. */
data class NotificationPayload(
    val key: String,
    val appLabel: String,
    val title: CharSequence?,
    val text: CharSequence?,
    val smallIcon: Icon?,
    val iconColor: Int,
    val contentIntent: PendingIntent?,
    val whenMillis: Long,
)

/**
 * Renders a notification: collapsed is a circular app icon + bold label; expanded is sender, bold
 * message (up to 2 lines, centred) and a timestamp bottom-right (§1.7, §4.5).
 */
class NotificationPresenter(
    private val context: Context,
    private val geometry: IslandGeometry,
    private val payload: NotificationPayload,
) : IslandPresenter {

    private var textView: TextView? = null

    override fun bindCollapsed(container: ViewGroup): Int {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(geometry.capsulePaddingStart, 0, geometry.capsulePaddingEnd, 0)
        }
        val icon = ImageView(context).apply {
            val d = payload.smallIcon?.loadDrawable(context)
            if (d != null) {
                setImageDrawable(d)
                setColorFilter(payload.iconColor.takeIf { it != 0 } ?: 0xFFFFFFFF.toInt())
            }
        }
        val lp = LinearLayout.LayoutParams(geometry.iconSize, geometry.iconSize)
        lp.gravity = Gravity.CENTER_VERTICAL
        row.addView(icon, lp)

        val label = TextView(context).apply {
            text = payload.appLabel
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val textLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = geometry.capsuleGap }
        row.addView(label, textLp)
        container.addView(row)

        val labelWidth = label.paint.measureText(payload.appLabel.toString()).toInt()
        val width = geometry.capsulePaddingStart + geometry.iconSize + geometry.capsuleGap +
            labelWidth + geometry.capsulePaddingEnd
        return width
    }

    override fun bindExpanded(container: ViewGroup) {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(geometry.expandedPadding, geometry.expandedPadding,
                geometry.expandedPadding, geometry.expandedPadding)
        }

        val sender = TextView(context).apply {
            text = payload.appLabel
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.6f
            gravity = Gravity.CENTER
        }
        column.addView(sender)

        textView = TextView(context).apply {
            text = payload.title?.takeIf { it.isNotBlank() } ?: payload.text
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        column.addView(textView)

        val timestamp = TextView(context).apply {
            text = formatTime(payload.whenMillis)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.6f
            gravity = Gravity.END
        }
        column.addView(timestamp)

        container.addView(column)
    }

    override fun onPrimaryAction(): Boolean {
        payload.contentIntent?.let { pi ->
            runCatching { pi.send() }
        }
        return payload.contentIntent != null
    }

    override fun onDismiss() {
        // The notification is cancelled by the signal source / router.
    }

    override fun onDestroy() {
        textView = null
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        val now = System.currentTimeMillis()
        val diff = now - millis
        return when {
            diff < 60_000L -> "now"
            diff < 3_600_000L -> "${diff / 60_000L}m"
            diff < 86_400_000L -> "${diff / 3_600_000L}h"
            else -> android.text.format.DateUtils.getRelativeTimeSpanString(millis, now, 0L)
                .toString()
        }
    }
}
