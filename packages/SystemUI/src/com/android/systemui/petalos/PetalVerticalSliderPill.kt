/*
 * Copyright (C) 2026 petalOS
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

package com.android.systemui.petalos

import android.service.quicksettings.Tile.STATE_ACTIVE
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// slider that reports live and on release
@Composable
fun PetalVerticalSliderPill(
    value: Float,
    iconRes: Int,
    contentDescriptionText: String,
    onValueChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 55.dp,
    orientation: Orientation = Orientation.Vertical,
    height: Dp = PetalQsSkin.MediaRowHeight,
    onIconClick: () -> Unit = {},
    iconActionDescription: String = contentDescriptionText,
    iconTint: Color = Color.Unspecified,
    onDraggingChanged: (Boolean) -> Unit = {},
    reveal: PetalQsReveal? = null,
) {
    val dark = PetalQsSkin.dark()
    // pull from the tile palette so the pills stop looking like a stock progress bar
    val tileColors =
        PetalQsSkin.tileColors(
            state = STATE_ACTIVE,
            handlesSecondaryClick = true,
            iconOnly = false,
            emission = PetalQsSkin.emission(),
            dark = dark,
        )
    val fillColor = tileColors.background
    val iconBoxColor = tileColors.iconBackground
    val resolvedIconTint = if (iconTint == Color.Unspecified) {
        tileColors.icon
    } else {
        iconTint
    }
    // NaN while not dragging
    var dragFraction by remember { mutableFloatStateOf(Float.NaN) }
    val shown = (if (dragFraction.isNaN()) value else dragFraction).coerceIn(0f, 1f)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnDraggingChanged by rememberUpdatedState(onDraggingChanged)
    val view = LocalView.current
    DisposableEffect(Unit) {
        onDispose { currentOnDraggingChanged(false) }
    }
    val horizontal = orientation == Orientation.Horizontal
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val pillShape = RoundedCornerShape(PetalQsSkin.TileCornerRadius)

    Box(
        modifier =
            modifier
                .width(if (horizontal) width else maxOf(width, 48.dp))
                .height(height)
                .pointerInput(orientation, rtl) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // claim the lane before the shade thinks this is a collapse swipe
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        currentOnDraggingChanged(true)
                        fun update(position: androidx.compose.ui.geometry.Offset) {
                            val fraction = if (horizontal) {
                                val x = position.x / size.width.toFloat().coerceAtLeast(1f)
                                if (rtl) 1f - x else x
                            } else 1f - position.y / size.height.toFloat().coerceAtLeast(1f)
                            dragFraction = fraction.coerceIn(0f, 1f)
                            currentOnValueChange(dragFraction)
                        }
                        down.consume()
                        update(down.position)
                        try {
                            val released = drag(down.id) { change ->
                                change.consume()
                                update(change.position)
                            }
                            if (released) currentOnCommit(dragFraction)
                        } finally {
                            dragFraction = Float.NaN
                            currentOnDraggingChanged(false)
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                .semantics {
                    contentDescription = contentDescriptionText
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                    setProgress { requested ->
                        val next = requested.coerceIn(0f, 1f)
                        onValueChange(next)
                        currentOnCommit(next)
                        true
                    }
                }
    ) {
        Box(
            modifier =
                Modifier.align(Alignment.Center)
                    .petalQsSurfaceReveal(reveal)
                    .then(
                        if (horizontal) Modifier.fillMaxWidth().height(40.dp)
                        else Modifier.width(width).fillMaxHeight()
                    )
                    .clip(pillShape)
                    .background(PetalQsSkin.tileGlass(dark))
                    .border(1.dp, PetalQsSkin.tileGlassBorderBrush(dark), pillShape)
        ) {
            // Skinny glass, proper touch lane. No fiddly little target.
            Box(
                modifier =
                    Modifier.align(if (horizontal) Alignment.CenterStart else Alignment.BottomCenter)
                        .then(
                            if (horizontal) Modifier.fillMaxHeight().fillMaxWidth(shown)
                            else Modifier.fillMaxWidth().fillMaxHeight(shown)
                        )
                        .background(fillColor)
            )
        }
        IconButton(
            onClick = onIconClick,
            modifier =
                Modifier.align(if (horizontal) Alignment.CenterStart else Alignment.BottomCenter)
                    .petalQsSurfaceReveal(reveal)
                    .petalQsContentReveal(reveal)
                    .size(48.dp),
        ) {
            // keep to vector icons, level lists crash painter
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = iconActionDescription,
                tint = resolvedIconTint,
                // backing box matches the tiles now, not a white lollipop
                modifier =
                    Modifier.size(32.dp)
                        .background(
                            iconBoxColor,
                            PetalQsSkin.iconShape(),
                        )
                        .padding(6.dp),
            )
        }
    }
}
