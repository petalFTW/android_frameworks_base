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

package com.android.systemui.island

import android.content.Context
import android.util.TypedValue
import android.view.WindowInsets
import android.view.WindowManager
import com.android.systemui.res.R

/**
 * Density-independent geometry for the island. All values come from `dimens.xml` (§4), converted
 * to pixels once here.
 */
class IslandGeometry(context: Context, windowManager: WindowManager) {

    private val res = context.resources

    fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics).toInt()

    val chipSize: Int = res.getDimensionPixelSize(R.dimen.island_chip_size)
    val capsuleHeight: Int = res.getDimensionPixelSize(R.dimen.island_capsule_height)
    val capsuleMinWidth: Int = res.getDimensionPixelSize(R.dimen.island_capsule_min_width)
    val capsuleMaxWidth: Int = res.getDimensionPixelSize(R.dimen.island_capsule_max_width)
    val callCapsuleWidth: Int = res.getDimensionPixelSize(R.dimen.island_call_capsule_width)
    val expandedMaxWidth: Int = res.getDimensionPixelSize(R.dimen.island_expanded_max_width)
    val expandedSideMargin: Int = res.getDimensionPixelSize(R.dimen.island_expanded_side_margin)
    val expandedHeightNotif: Int = res.getDimensionPixelSize(R.dimen.island_expanded_height_notif)
    val expandedHeightCall: Int = res.getDimensionPixelSize(R.dimen.island_expanded_height_call)
    val expandedHeightMedia: Int = res.getDimensionPixelSize(R.dimen.island_expanded_height_media)
    val expandedHeightMediaMax: Int =
        res.getDimensionPixelSize(R.dimen.island_expanded_height_media_max)
    val topOffset: Int = res.getDimensionPixelSize(R.dimen.island_top_offset)
    val cornerCapsule: Float = res.getDimension(R.dimen.island_corner_capsule)
    val cornerExpanded: Float = res.getDimension(R.dimen.island_corner_expanded)
    val elevation: Float = res.getDimension(R.dimen.island_elevation)
    val capsulePaddingStart: Int = res.getDimensionPixelSize(R.dimen.island_capsule_padding_start)
    val capsulePaddingEnd: Int = res.getDimensionPixelSize(R.dimen.island_capsule_padding_end)
    val iconSize: Int = res.getDimensionPixelSize(R.dimen.island_icon_size)
    val capsuleGap: Int = res.getDimensionPixelSize(R.dimen.island_capsule_gap)
    val expandedPadding: Int = res.getDimensionPixelSize(R.dimen.island_expanded_padding)
    val artSize: Int = res.getDimensionPixelSize(R.dimen.island_art_size)
    val artRadius: Float = res.getDimension(R.dimen.island_art_radius)
    val equalizerBarWidth: Float = res.getDimension(R.dimen.island_equalizer_bar_width)
    val equalizerGap: Float = res.getDimension(R.dimen.island_equalizer_gap)
    val equalizerMaxHeight: Float = res.getDimension(R.dimen.island_equalizer_max_height)
    val equalizerMaxHeightExpanded: Float =
        res.getDimension(R.dimen.island_equalizer_max_height_expanded)
    val dropletBaseRadius: Int = res.getDimensionPixelSize(R.dimen.island_droplet_base_radius)

    val screenWidth: Int = context.resources.displayMetrics.widthPixels

    /** Vertical placement: top inset from the cutout or system bars. */
    val statusBarTopInset: Int = run {
        val insets = windowManager.maximumWindowMetrics.windowInsets
        val cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
        val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        maxOf(cutout.top, bars.top)
    }

    val statusBarHeight: Int = res.getDimensionPixelSize(
        com.android.internal.R.dimen.status_bar_height
    )

    /**
     * The island's top Y edge in collapsed form: vertically centred on the status bar when it is
     * tall enough, otherwise `topInset + topOffset`.
     */
    val collapsedTopY: Int =
        if (statusBarHeight >= dp(32f)) {
            (statusBarTopInset / 2 + dp(2f)) - capsuleHeight / 2
        } else {
            statusBarTopInset + topOffset
        }
}
