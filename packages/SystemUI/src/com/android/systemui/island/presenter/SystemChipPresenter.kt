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

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.island.IslandGeometry

// label and accent for a simple chip
data class SystemChipPayload(
    val label: String,
    val accent: Int,
    val toggleAction: (() -> Unit)? = null,
    val iconRes: Int = 0,
)

// chip with an icon and label
class SystemChipPresenter(
    private val context: Context,
    private val geometry: IslandGeometry,
    private val payload: SystemChipPayload,
) : IslandPresenter {

    override fun bindCollapsed(container: ViewGroup): Int {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(geometry.capsulePaddingStart, 0, geometry.capsulePaddingEnd, 0)
        }
        val dot = if (payload.iconRes != 0) ImageView(context).apply {
            setImageResource(payload.iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(payload.accent)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else TextView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(payload.accent)
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(geometry.iconSize, geometry.iconSize))

        val label = TextView(context).apply {
            text = payload.label
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        row.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = geometry.capsuleGap },
        )
        container.addView(row)

        val labelWidth = label.paint.measureText(payload.label).toInt()
        return geometry.capsulePaddingStart + geometry.iconSize + geometry.capsuleGap +
            labelWidth + geometry.capsulePaddingEnd
    }

    override fun bindExpanded(container: ViewGroup) {
        bindCollapsed(container)
    }

    override fun tint(): IslandTint = IslandTint(accent = payload.accent)

    override fun onPrimaryAction(): Boolean {
        payload.toggleAction?.invoke()
        return payload.toggleAction != null
    }

    override fun onDestroy() = Unit
}
