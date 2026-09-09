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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Glass slider with live changes and a release callback. */
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
    iconTint: androidx.compose.ui.graphics.Color = PetalQsSkin.GlyphDark,
) {
    // NaN means the slider is idle.
    var dragFraction by remember { mutableFloatStateOf(Float.NaN) }
    val shown = (if (dragFraction.isNaN()) value else dragFraction).coerceIn(0f, 1f)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val horizontal = orientation == Orientation.Horizontal
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var axisSize by remember { mutableFloatStateOf(1f) }
    val dragState = rememberDraggableState { delta ->
        val base = if (dragFraction.isNaN()) value else dragFraction
        val direction = if (horizontal && !rtl) 1f else -1f
        val next = (base + direction * delta / axisSize).coerceIn(0f, 1f)
        dragFraction = next
        onValueChange(next)
    }
    val pillShape = RoundedCornerShape(PetalQsSkin.TileCornerRadius)

    fun commit() {
        val frac = if (dragFraction.isNaN()) value else dragFraction
        dragFraction = Float.NaN
        currentOnCommit(frac.coerceIn(0f, 1f))
    }

    Box(
        modifier =
            modifier
                .width(width)
                .height(height)
                .onSizeChanged { size ->
                    axisSize =
                        (if (horizontal) size.width else size.height).toFloat().coerceAtLeast(1f)
                }
                .clip(pillShape)
                .background(PetalQsSkin.TileGlassDark)
                // petalOS: glass sheen edge on the pill rim.
                .border(1.dp, PetalQsSkin.TileGlassBorderBrush, pillShape)
                .draggable(
                    orientation = orientation,
                    state = dragState,
                    onDragStopped = { commit() },
                )
                .pointerInput(orientation, rtl) {
                    detectTapGestures { offset ->
                        val frac =
                            if (horizontal) {
                                val x = offset.x / size.width.toFloat().coerceAtLeast(1f)
                                if (rtl) 1f - x else x
                            } else {
                                1f - (offset.y / size.height.toFloat().coerceAtLeast(1f))
                            }
                        dragFraction = frac.coerceIn(0f, 1f)
                        currentOnValueChange(dragFraction)
                        commit()
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
        // Light content zone anchored to the bottom, sized by the fill fraction.
        Box(
            modifier =
                Modifier.align(if (horizontal) Alignment.CenterStart else Alignment.BottomCenter)
                    .then(
                        if (horizontal) Modifier.fillMaxHeight().fillMaxWidth(shown)
                        else Modifier.fillMaxWidth().fillMaxHeight(shown)
                    )
                    .background(PetalQsSkin.CardLight)
        )
        IconButton(
            onClick = onIconClick,
            modifier =
                Modifier.align(if (horizontal) Alignment.CenterStart else Alignment.BottomCenter)
                    .size(if (horizontal) height else width),
        ) {
            // Use plain vector icons; level lists crash painterResource.
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = iconActionDescription,
                tint = iconTint,
                // A small pearl backing keeps the glyph legible even at zero fill.
                modifier =
                    Modifier.size(32.dp)
                        .background(
                            PetalQsSkin.CardLight,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .padding(6.dp),
            )
        }
    }
}
