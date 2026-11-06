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
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.island.IslandGeometry
import com.android.systemui.island.render.IslandGlyph
import com.android.systemui.island.render.IslandGlyphView
import com.android.systemui.island.render.IslandLevelMeter
import kotlin.math.roundToInt

data class TorchPayload(
    val accent: Int,
    // 1 means the flash can't dim, no card then
    val maxLevel: Int,
    val currentLevel: () -> Int,
    val setLevel: (Int) -> Unit,
    val turnOff: () -> Unit,
)

// torch chip that opens into a square with a brightness meter
class TorchPresenter(
    private val context: Context,
    private val geometry: IslandGeometry,
    private val payload: TorchPayload,
) : IslandPresenter {

    private val chip = SystemChipPresenter(
        context,
        geometry,
        SystemChipPayload(
            label = "Torch",
            accent = payload.accent,
            toggleAction = payload.turnOff,
            glyph = IslandGlyph.TORCH,
        ),
    )

    override fun bindCollapsed(container: ViewGroup): Int = chip.bindCollapsed(container)

    override fun bindExpanded(container: ViewGroup) {
        val dark = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val onSurface = if (dark) 0xFFFFFFFF.toInt() else 0xFF101012.toInt()
        val pad = geometry.dp(CARD_PADDING_DP)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        side.addView(
            IslandGlyphView(context, IslandGlyph.TORCH, payload.accent),
            LinearLayout.LayoutParams(geometry.dp(38f), geometry.dp(38f)),
        )
        val percent = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(onSurface)
            maxLines = 1
        }
        side.addView(
            percent,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f).apply {
                topMargin = geometry.dp(4f)
            },
        )
        val off = TextView(context).apply {
            text = "Off"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(onSurface)
            background = GradientDrawable().apply {
                cornerRadius = geometry.dp(17f).toFloat()
                setColor(onSurface and 0x00FFFFFF or 0x24000000)
            }
            isClickable = true
            setOnClickListener { payload.turnOff() }
        }
        side.addView(
            off,
            LinearLayout.LayoutParams(geometry.dp(62f), geometry.dp(34f)),
        )
        row.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val meter = IslandLevelMeter(
            context,
            payload.maxLevel,
            payload.accent,
            trackColor = onSurface and 0x00FFFFFF or 0x24000000,
            notchColor = onSurface and 0x00FFFFFF or 0x30000000,
        ).apply {
            level = payload.currentLevel()
            contentDescription = "Torch brightness"
            onLevelChanged = { next ->
                percent.text = percentOf(next)
                payload.setLevel(next)
            }
        }
        percent.text = percentOf(meter.level)
        row.addView(
            meter,
            LinearLayout.LayoutParams(geometry.dp(46f), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        container.addView(row)
    }

    private fun percentOf(level: Int): String =
        "${(level * 100f / payload.maxLevel).roundToInt()}%"

    override fun canExpand(): Boolean = payload.maxLevel > 1

    override fun showsMiniStatus(): Boolean = false

    override fun expandedWidthPx(): Int = geometry.dp(CARD_SIZE_DP)

    override fun expandedHeightPx(): Int = geometry.dp(CARD_SIZE_DP)

    override fun tint(): IslandTint = chip.tint()

    override fun onPrimaryAction(): Boolean = chip.onPrimaryAction()

    private companion object {
        const val CARD_SIZE_DP = 164f
        const val CARD_PADDING_DP = 16f
    }
}
