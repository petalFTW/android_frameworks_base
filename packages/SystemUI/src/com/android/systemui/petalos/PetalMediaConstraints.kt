/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.res.R

// Keep the live action flags; just shuffle the bloody geometry.
object PetalMediaConstraints {
    fun create(context: Context, original: ConstraintSet, state: MediaHostState): ConstraintSet {
        val layout = ConstraintSet().apply { clone(original) }
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val mini = state.petalStyle == PetalMediaStyle.Mini
        val lock = state.petalStyle == PetalMediaStyle.Lockscreen
        val split = state.petalStyle == PetalMediaStyle.Split
        val width = (state.measurementInput?.width ?: px(300)) / density
        val narrowMini = mini && width < 260
        val narrowSplit = split && width < 280
        val miniArtWidth = if (narrowMini) 56 else 76
        val miniButtonSize = if (narrowMini) 40 else 48
        val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val textHeight = (48 * scale).toInt()
        val timeHeight = ((if (lock) 18 else 14) * scale).toInt().coerceAtLeast(15)
        val lockArtWidth = (width * 0.265f).toInt().coerceIn(68, 106)
        val lockSweep = (width * 0.13f).toInt().coerceAtMost(52)
        val lockHeaderTop = (lockSweep * 0.85f).toInt() + 4
        val lockHeaderHeight = maxOf(lockArtWidth, textHeight)
        // Same gutter, otherwise the track looks drunk.
        val lockInset = (width * 0.055f).toInt().coerceIn(16, 24)
        val splitArtWidth = ((width - 32) * 0.36f).toInt().coerceIn(64, 128)
        val splitHeaderHeight = maxOf(splitArtWidth, textHeight + 12)
        val buttonSize = if (lock && width < 320) 40 else if (narrowSplit) 36 else 48
        val playSize = if (lock) {
            (width * 0.20f).toInt().coerceIn(56, 80)
        } else if (mini) miniButtonSize else if (split) {
            if (narrowSplit) 44 else 48
        } else 60
        val artHeight = when {
            mini -> 88
            lock -> lockArtWidth
            split -> splitArtWidth
            else -> (width * .31f).toInt().coerceIn(88, 176)
        }
        val textTop = when {
            mini -> 16
            lock -> lockHeaderTop + (lockHeaderHeight - textHeight) / 2
            split -> 16 + ((splitHeaderHeight - textHeight) / 2).coerceAtLeast(0)
            else -> artHeight + 12
        }
        val splitProgressTop = 16 + splitHeaderHeight + 8
        val progressTop = when {
            mini -> textTop + textHeight + 56
            lock -> lockHeaderTop + lockHeaderHeight + 4
            split -> splitProgressTop
            else -> textTop + textHeight + 4
        }
        val transportTop = when {
            mini -> textTop + textHeight + 4
            lock -> progressTop + 36 + timeHeight + 12
            split -> progressTop + 24 + timeHeight + 6
            else -> progressTop + 24 + timeHeight + 4
        }
        val generic = MediaViewHolder.genericButtonIds.filter {
            original.getVisibility(it) != View.GONE
        }
        val primary = original.getVisibility(R.id.actionPlayPause) != View.GONE
        // 05 keeps three buttons; lockscreen and split get the flanking pair
        val inlineExtras = primary && (lock || split)
        val columns = ((width - 16) / 48).toInt().coerceIn(1, 5)
        val extras = (generic.size - if (inlineExtras) 2 else 0).coerceAtLeast(0)
        val extraRows = if (mini || split || (state.petalStyle == PetalMediaStyle.LargeProgress && primary)) {
            0
        } else (extras + columns - 1) / columns
        val splitOutputTop = transportTop + playSize + 8
        val height = when {
            mini -> maxOf(124, progressTop + 16)
            split -> splitOutputTop + 40 + 12
            lock -> transportTop + playSize + 12 + lockSweep + extraRows * 48
            else -> transportTop + 76 + extraRows * 48
        }

        fun clear(id: Int) {
            listOf(ConstraintSet.LEFT, ConstraintSet.RIGHT, ConstraintSet.START, ConstraintSet.END,
                ConstraintSet.TOP, ConstraintSet.BOTTOM, ConstraintSet.BASELINE).forEach {
                layout.clear(id, it)
            }
        }
        fun top(id: Int, y: Int, h: Int) {
            clear(id)
            // The controls were hugging the top, give the buggers some balance.
            val offset = if (lock && id !in MediaViewHolder.backgroundIds) 14 else 0
            layout.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, px(y + offset))
            layout.constrainHeight(id, px(h))
        }
        fun full(id: Int, inset: Int = 16) {
            layout.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
            layout.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, px(inset))
            layout.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, px(inset))
        }
        fun show(id: Int, visible: Boolean) {
            layout.setVisibility(id, if (visible) View.VISIBLE else View.GONE)
            layout.setAlpha(id, if (visible) 1f else 0f)
        }
        MediaViewHolder.backgroundIds.forEach {
            top(it, 0, height)
            full(it, 0)
        }
        layout.setAlpha(R.id.album_art, 0f)
        top(R.id.petal_media_cover, if (lock) lockHeaderTop else if (mini || split) 16 else 0, artHeight)
        if (mini) {
            layout.constrainWidth(R.id.petal_media_cover, px(miniArtWidth))
            layout.connect(R.id.petal_media_cover, ConstraintSet.START,
                ConstraintSet.PARENT_ID, ConstraintSet.START, px(16))
        } else if (lock) {
            layout.constrainWidth(R.id.petal_media_cover, px(lockArtWidth))
            layout.connect(R.id.petal_media_cover, ConstraintSet.START,
                ConstraintSet.PARENT_ID, ConstraintSet.START, px(lockInset))
        } else if (split) {
            // split artwork parks on the left, full height of the upper zone
            layout.constrainWidth(R.id.petal_media_cover, px(splitArtWidth))
            layout.connect(R.id.petal_media_cover, ConstraintSet.START,
                ConstraintSet.PARENT_ID, ConstraintSet.START, px(16))
        } else full(R.id.petal_media_cover, 0)
        show(R.id.petal_media_cover, true)
        for ((id, y) in listOf(R.id.header_title to textTop,
            R.id.header_artist to textTop + (24 * scale).toInt())) {
            top(id, y, (24 * scale).toInt())
            full(id)
            if (mini) {
                layout.connect(id, ConstraintSet.START, R.id.petal_media_cover,
                    ConstraintSet.END, px(12))
                layout.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID,
                    ConstraintSet.END, px(48))
            } else if (lock) {
                layout.connect(id, ConstraintSet.START, R.id.petal_media_cover,
                    ConstraintSet.END, px(if (width < 320) 12 else 18))
                layout.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID,
                    ConstraintSet.END, px(lockInset))
            } else if (split) {
                layout.connect(id, ConstraintSet.START, R.id.petal_media_cover,
                    ConstraintSet.END, px(12))
                layout.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID,
                    ConstraintSet.END, px(16))
            }
        }
        show(R.id.icon, false)
        show(R.id.media_explicit_indicator, false)
        top(R.id.petal_media_ribbon_waveform, lockHeaderTop + (lockHeaderHeight - 32) / 2, 32)
        layout.constrainWidth(R.id.petal_media_ribbon_waveform, px(36))
        layout.connect(R.id.petal_media_ribbon_waveform, ConstraintSet.END,
            ConstraintSet.PARENT_ID, ConstraintSet.END, px(lockInset))
        show(R.id.petal_media_ribbon_waveform, false)
        if (split) {
            top(R.id.media_seamless, splitOutputTop, 40)
            full(R.id.media_seamless)
            show(R.id.petal_media_output, false)
        } else {
            show(R.id.petal_media_output, false)
            top(R.id.media_seamless, 4, 48)
            layout.constrainWidth(R.id.media_seamless, px(48))
            layout.connect(R.id.media_seamless, ConstraintSet.END, ConstraintSet.PARENT_ID,
                ConstraintSet.END, px(8))
        }
        if (lock) show(R.id.media_seamless, false)
        show(R.id.device_suggestion_container, false)
        top(R.id.media_progress_bar, progressTop, if (mini) 12 else 24)
        full(R.id.media_progress_bar, if (lock) lockInset else 16)
        show(R.id.media_scrubbing_elapsed_time, false)
        show(R.id.media_scrubbing_total_time, false)
        for ((id, side) in listOf(R.id.petal_media_elapsed to ConstraintSet.LEFT,
            R.id.petal_media_duration to ConstraintSet.RIGHT)) {
            top(id, progressTop + (if (lock) 36 else 24), timeHeight)
            layout.constrainWidth(id, px((56 * scale).toInt()))
            layout.connect(id, side, ConstraintSet.PARENT_ID, side, px(lockInset))
            show(id, !mini)
        }
        val play = R.id.actionPlayPause
        top(play, transportTop, playSize)
        layout.constrainWidth(play, px(playSize))
        if (mini) {
            layout.connect(play, ConstraintSet.START, R.id.petal_media_cover,
                ConstraintSet.END, px(12))
            layout.connect(play, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        } else {
            layout.connect(play, ConstraintSet.LEFT, ConstraintSet.PARENT_ID, ConstraintSet.LEFT)
            layout.connect(play, ConstraintSet.RIGHT, ConstraintSet.PARENT_ID, ConstraintSet.RIGHT)
        }
        for ((id, side) in listOf(R.id.actionPrev to ConstraintSet.RIGHT,
            R.id.actionNext to ConstraintSet.LEFT)) {
            val size = if (mini) miniButtonSize else buttonSize
            top(id, transportTop + (playSize - size) / 2, size)
            layout.constrainWidth(id, px(size))
            layout.connect(id, side, play,
                if (side == ConstraintSet.RIGHT) ConstraintSet.LEFT else ConstraintSet.RIGHT,
                px(if (mini) 0 else if (split || lock) 4 else 12))
        }
        generic.forEachIndexed { index, id ->
            when {
                // 05 and mini keep it to three buttons
                mini || (state.petalStyle == PetalMediaStyle.LargeProgress && primary) -> show(id, false)
                inlineExtras && index < 2 -> {
                    top(id, transportTop + (playSize - buttonSize) / 2, buttonSize)
                    layout.constrainWidth(id, px(buttonSize))
                    val side = if (index == 0) ConstraintSet.RIGHT else ConstraintSet.LEFT
                    val neighbour = if (index == 0) R.id.actionPrev else R.id.actionNext
                    layout.connect(id, side, neighbour,
                        if (index == 0) ConstraintSet.LEFT else ConstraintSet.RIGHT,
                        px(if (split || lock) 4 else 8))
                }
                split && !primary -> {
                    top(id, transportTop + (playSize - buttonSize) / 2, buttonSize)
                    layout.constrainWidth(id, px(buttonSize))
                    val rowWidth = generic.size * buttonSize + (generic.size - 1).coerceAtLeast(0) * 4
                    val start = ((width - rowWidth) / 2).toInt().coerceAtLeast(0)
                    layout.connect(id, ConstraintSet.LEFT, ConstraintSet.PARENT_ID,
                        ConstraintSet.LEFT, px(start + index * (buttonSize + 4)))
                }
                split -> show(id, false)
                else -> {
                    val extra = index - if (inlineExtras) 2 else 0
                    top(id, transportTop + 64 + extra / columns * 48, 48)
                    layout.constrainWidth(id, px(48))
                    layout.connect(id, ConstraintSet.LEFT, ConstraintSet.PARENT_ID,
                        ConstraintSet.LEFT, px(8 + extra % columns * 48))
                }
            }
        }
        return layout
    }
}
