/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.ui.composable

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Easings
import com.android.compose.modifiers.thenIf
import com.android.internal.R
import com.android.systemui.bouncer.ui.composable.MotionTestKeys.dotAppearFadeIn
import com.android.systemui.bouncer.ui.composable.MotionTestKeys.dotAppearMoveUp
import com.android.systemui.bouncer.ui.composable.MotionTestKeys.dotScaling
import com.android.systemui.bouncer.ui.composable.MotionTestKeys.entryCompleted
import com.android.systemui.bouncer.ui.viewmodel.PatternBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PatternDotViewModel
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.petalos.PetalPatternStyle
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues

// Draw the pattern grid.
@Composable
@VisibleForTesting
fun PatternBouncer(
    viewModel: PatternBouncerViewModel,
    centerDotsVertically: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    DisposableEffect(Unit) { onDispose { viewModel.onHidden() } }

    val colCount = viewModel.columnCount
    val rowCount = viewModel.rowCount

    val idleDotColor = Color(PetalPatternStyle.DOT_COLOR)
    val activeDotColor = Color(PetalPatternStyle.ACTIVE_COLOR)
    val dotRadius = with(density) { (DOT_DIAMETER_DP / 2).dp.toPx() }
    val lineColor = Color(PetalPatternStyle.ACTIVE_COLOR)
    val lineStrokeWidth = with(density) { LINE_STROKE_WIDTH_DP.dp.toPx() }

    // All dots that should be rendered on the grid.
    val dots: List<PatternDotViewModel> by viewModel.dots.collectAsStateWithLifecycle()
    // The most recently selected dot, if the user is currently dragging.
    val currentDot: PatternDotViewModel? by viewModel.currentDot.collectAsStateWithLifecycle()
    // The dots selected so far, if the user is currently dragging.
    val selectedDots: List<PatternDotViewModel> by
        viewModel.selectedDots.collectAsStateWithLifecycle()
    val isInputEnabled: Boolean by viewModel.isInputEnabled.collectAsStateWithLifecycle()
    val isAnimationEnabled: Boolean by viewModel.isPatternVisible.collectAsStateWithLifecycle()
    val animateFailure: Boolean by viewModel.animateFailure.collectAsStateWithLifecycle()

    // Map of animatables for the scale of each dot, keyed by dot.
    val dotScalingAnimatables = remember(dots) { dots.associateWith { Animatable(1f) } }
    // Track each incoming line by its destination dot.
    val lineFadeOutAnimatables = remember(dots) { dots.associateWith { Animatable(1f) } }
    val lineFadeOutAnimationDurationMs =
        integerResource(R.integer.lock_pattern_line_fade_out_duration)
    val lineFadeOutAnimationDelayMs = integerResource(R.integer.lock_pattern_line_fade_out_delay)

    val dotAppearFadeInAnimatables = remember(dots) { dots.associateWith { Animatable(0f) } }
    val dotAppearMoveUpAnimatables = remember(dots) { dots.associateWith { Animatable(0f) } }
    val dotAppearMaxOffsetPixels =
        remember(dots) {
            dots.associateWith { dot -> with(density) { (80 + (20 * dot.y)).dp.toPx() } }
        }

    var entryAnimationCompleted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showEntryAnimation(dotAppearFadeInAnimatables, dotAppearMoveUpAnimatables)
        entryAnimationCompleted = true
    }

    val view = LocalView.current

    // When the current dot is changed, we need to update our animations.
    LaunchedEffect(currentDot, isAnimationEnabled) {
        // Vibrate when a dot is selected.
        if (currentDot != null) {
            viewModel.performDotFeedback(view)
        }

        if (!isAnimationEnabled) {
            return@LaunchedEffect
        }

        // Make sure that the current dot is scaled up while the other dots are scaled back down.
        dotScalingAnimatables.entries.forEach { (dot, animatable) ->
            val isSelected = dot == currentDot
            // Let the animation finish when the current dot changes.
            scope.launch {
                if (isSelected) {
                    animatable.animateTo(
                        targetValue = (SELECTED_DOT_DIAMETER_DP / DOT_DIAMETER_DP.toFloat()),
                        animationSpec =
                            tween(
                                durationMillis = SELECTED_DOT_REACTION_ANIMATION_DURATION_MS,
                                easing = Easings.StandardAccelerate,
                            ),
                    )
                } else {
                    animatable.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            tween(
                                durationMillis = SELECTED_DOT_RETRACT_ANIMATION_DURATION_MS,
                                easing = Easings.StandardDecelerate,
                            ),
                    )
                }
            }
        }

        selectedDots.forEach { dot ->
            lineFadeOutAnimatables[dot]?.let { line ->
                if (!line.isRunning) {
                    // Let the animation finish when the current dot changes.
                    scope.launch {
                        if (dot == currentDot) {
                            // Reset the current line fade.
                            line.snapTo(1f)
                        } else {
                            // Fade earlier lines.
                            line.animateTo(
                                targetValue = 0f,
                                animationSpec =
                                    tween(
                                        durationMillis = lineFadeOutAnimationDurationMs,
                                        delayMillis = lineFadeOutAnimationDelayMs,
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }

    // Show the failure animation if the user entered the wrong input.
    LaunchedEffect(animateFailure) {
        if (animateFailure) {
            showFailureAnimation(dots = dots, scalingAnimatables = dotScalingAnimatables)
            viewModel.onFailureAnimationShown()
        }
    }

    // This is the position of the input pointer. Calculated in context of bouncer Box.
    var inputPosition: Offset? by remember { mutableStateOf(null) }
    // Calculated in context of Canvas inside the Box.
    var gridCoordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    // Calculated in context of Canvas inside the Box.
    var offset: Offset by remember { mutableStateOf(Offset.Zero) }
    var scale: Float by remember { mutableFloatStateOf(1f) }
    // This is the size of the drawing area, in dips.
    val dotDrawingArea =
        remember(colCount, rowCount) {
            DpSize(
                // Include the outer horizontal spacing.
                width = (262 * colCount / 2).dp,
                // Include the outer vertical spacing.
                height = (262 * rowCount / 2).dp,
            )
        }

    Box(
        modifier =
            modifier.fillMaxWidth().thenIf(isInputEnabled) {
                Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            viewModel.onDown()
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start ->
                                inputPosition = start
                                viewModel.onDragStart()
                            },
                            onDragEnd = {
                                inputPosition = null
                                if (isAnimationEnabled) {
                                    lineFadeOutAnimatables.values.forEach { animatable ->
                                        // Let the animation finish when the current dot changes.
                                        scope.launch { animatable.animateTo(1f) }
                                    }
                                }
                                viewModel.onDragEnd()
                            },
                        ) { change, _ ->
                            inputPosition = change.position
                            change.position.minus(offset).div(scale).let {
                                viewModel.onDrag(
                                    xPx =
                                        it.x -
                                            ((size.width - dotDrawingArea.width.roundToPx()) / 2),
                                    yPx = it.y,
                                    containerSizePx = dotDrawingArea.width.roundToPx(),
                                )
                            }
                        }
                    }
            }
    ) {
        AndroidView(
            factory = { context ->
                android.view.View(context).apply {
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    PetalPatternStyle.applyGlass(this)
                }
            },
            modifier = Modifier.width(dotDrawingArea.width)
                .height(dotDrawingArea.height)
                .align(Alignment.Center),
        )
        Canvas(
            Modifier.sysuiResTag("bouncer_pattern_root")
                .width(dotDrawingArea.width)
                .height(dotDrawingArea.height)
                // Keep the trailing line inside the grid.
                .clipToBounds()
                .align(Alignment.Center)
                .onGloballyPositioned { coordinates -> gridCoordinates = coordinates }
                .motionTestValues {
                    entryAnimationCompleted exportAs entryCompleted
                    dotAppearFadeInAnimatables.map { it.value.value } exportAs dotAppearFadeIn
                    dotAppearMoveUpAnimatables.map { it.value.value } exportAs dotAppearMoveUp
                    dotScalingAnimatables.map { it.value.value } exportAs dotScaling
                }
        ) {
            gridCoordinates?.let { nonNullCoordinates ->
                val containerSize = nonNullCoordinates.size
                if (containerSize.width <= 0 || containerSize.height <= 0) {
                    return@let
                }

                val horizontalSpacing = containerSize.width.toFloat() / colCount
                val verticalSpacing = containerSize.height.toFloat() / rowCount
                val spacing = min(horizontalSpacing, verticalSpacing)
                val horizontalOffset =
                    offset(
                        availableSize = containerSize.width,
                        spacingPerDot = spacing,
                        dotCount = colCount,
                        isCentered = true,
                    )
                val verticalOffset =
                    offset(
                        availableSize = containerSize.height,
                        spacingPerDot = spacing,
                        dotCount = rowCount,
                        isCentered = centerDotsVertically,
                    )
                offset = Offset(horizontalOffset, verticalOffset)
                scale = (colCount * spacing) / containerSize.width

                if (isAnimationEnabled) {
                    // Draw lines between dots.
                    selectedDots.forEachIndexed { index, dot ->
                        if (index > 0) {
                            val previousDot = selectedDots[index - 1]
                            val lineFadeOutAnimationProgress =
                                lineFadeOutAnimatables[previousDot]!!.value
                            val startLerp = 1 - lineFadeOutAnimationProgress
                            val from =
                                pixelOffset(previousDot, spacing, horizontalOffset, verticalOffset)
                            val to = pixelOffset(dot, spacing, horizontalOffset, verticalOffset)
                            val lerpedFrom =
                                Offset(
                                    x = from.x + (to.x - from.x) * startLerp,
                                    y = from.y + (to.y - from.y) * startLerp,
                                )
                            drawLine(
                                start = lerpedFrom,
                                end = to,
                                cap = StrokeCap.Round,
                                alpha = lineFadeOutAnimationProgress * lineAlpha(spacing),
                                color = lineColor,
                                strokeWidth = lineStrokeWidth,
                            )
                        }
                    }

                    // Draw the trailing line to the pointer.
                    inputPosition?.let { lineEndInParent ->
                        val lineEnd = lineEndInParent.minus(nonNullCoordinates.positionInParent())
                        currentDot?.let { dot ->
                            val from = pixelOffset(dot, spacing, horizontalOffset, verticalOffset)
                            val lineLength =
                                sqrt((from.y - lineEnd.y).pow(2) + (from.x - lineEnd.x).pow(2))
                            drawLine(
                                start = from,
                                end = lineEnd,
                                cap = StrokeCap.Round,
                                alpha = lineAlpha(spacing, lineLength),
                                color = lineColor,
                                strokeWidth = lineStrokeWidth,
                            )
                        }
                    }
                }

                // Draw each dot on the grid.
                dots.forEach { dot ->
                    val initialOffset = checkNotNull(dotAppearMaxOffsetPixels[dot])
                    val appearOffset =
                        (1 - checkNotNull(dotAppearMoveUpAnimatables[dot]).value) * initialOffset
                    drawCircle(
                        center =
                            pixelOffset(
                                dot,
                                spacing,
                                horizontalOffset,
                                verticalOffset + appearOffset,
                            ),
                        color =
                            if (isAnimationEnabled && dot == currentDot) {
                                activeDotColor
                            } else {
                                idleDotColor
                            }.copy(alpha = checkNotNull(dotAppearFadeInAnimatables[dot]).value),
                        radius = dotRadius * checkNotNull(dotScalingAnimatables[dot]).value,
                    )
                }
            }
        }
    }
}

private suspend fun showEntryAnimation(
    dotAppearFadeInAnimatables: Map<PatternDotViewModel, Animatable<Float, AnimationVector1D>>,
    dotAppearMoveUpAnimatables: Map<PatternDotViewModel, Animatable<Float, AnimationVector1D>>,
) {
    coroutineScope {
        dotAppearFadeInAnimatables.forEach { (dot, animatable) ->
            launch {
                animatable.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            delayMillis = 33 * dot.y,
                            durationMillis = 450,
                            easing = Easings.LegacyDecelerate,
                        ),
                )
            }
        }
        dotAppearMoveUpAnimatables.forEach { (dot, animatable) ->
            launch {
                animatable.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        tween(
                            delayMillis = 0,
                            durationMillis = 450 + (33 * dot.y),
                            easing = Easings.StandardDecelerate,
                        ),
                )
            }
        }
    }
}

/** Returns an [Offset] representation of the given [dot], in pixel coordinates. */
private fun pixelOffset(
    dot: PatternDotViewModel,
    spacing: Float,
    horizontalOffset: Float,
    verticalOffset: Float,
): Offset {
    return Offset(
        x = dot.x * spacing + spacing / 2 + horizontalOffset,
        y = dot.y * spacing + spacing / 2 + verticalOffset,
    )
}

// Fade the line near its starting dot.
private fun lineAlpha(gridSpacing: Float, lineLength: Float = gridSpacing): Float {
    // Fade in as the pointer leaves the dot.
    return ((lineLength / gridSpacing - 0.3f) * 4f).coerceIn(0f, 1f)
}

private suspend fun showFailureAnimation(
    dots: List<PatternDotViewModel>,
    scalingAnimatables: Map<PatternDotViewModel, Animatable<Float, AnimationVector1D>>,
) {
    val dotsByRow =
        buildList<MutableList<PatternDotViewModel>> {
            dots.forEach { dot ->
                val rowIndex = dot.y
                while (size <= rowIndex) {
                    add(mutableListOf())
                }
                get(rowIndex).add(dot)
            }
        }

    coroutineScope {
        dotsByRow.forEachIndexed { rowIndex, rowDots ->
            rowDots.forEach { dot ->
                scalingAnimatables[dot]?.let { dotScaleAnimatable ->
                    launch {
                        dotScaleAnimatable.animateTo(
                            targetValue =
                                FAILURE_ANIMATION_DOT_DIAMETER_DP / DOT_DIAMETER_DP.toFloat(),
                            animationSpec =
                                tween(
                                    durationMillis =
                                        FAILURE_ANIMATION_DOT_SHRINK_ANIMATION_DURATION_MS,
                                    delayMillis =
                                        rowIndex * FAILURE_ANIMATION_DOT_SHRINK_STAGGER_DELAY_MS,
                                    easing = Easings.Linear,
                                ),
                        )

                        dotScaleAnimatable.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                tween(
                                    durationMillis =
                                        FAILURE_ANIMATION_DOT_REVERT_ANIMATION_DURATION,
                                    easing = Easings.Standard,
                                ),
                        )
                    }
                }
            }
        }
    }
}

// Position the grid within the available space.
private fun offset(
    availableSize: Int,
    spacingPerDot: Float,
    dotCount: Byte,
    isCentered: Boolean = false,
): Float {
    val default = availableSize - spacingPerDot * dotCount
    return if (isCentered) {
        default / 2
    } else {
        default
    }
}

private const val DOT_DIAMETER_DP = PetalPatternStyle.DOT_SIZE_DP
private const val SELECTED_DOT_DIAMETER_DP = PetalPatternStyle.ACTIVE_DOT_SIZE_DP
private const val SELECTED_DOT_REACTION_ANIMATION_DURATION_MS = 83
private const val SELECTED_DOT_RETRACT_ANIMATION_DURATION_MS = 750
private const val LINE_STROKE_WIDTH_DP = PetalPatternStyle.PATH_WIDTH_DP
private const val FAILURE_ANIMATION_DOT_DIAMETER_DP = (DOT_DIAMETER_DP * 0.81f).toInt()
private const val FAILURE_ANIMATION_DOT_SHRINK_ANIMATION_DURATION_MS = 50
private const val FAILURE_ANIMATION_DOT_SHRINK_STAGGER_DELAY_MS = 33
private const val FAILURE_ANIMATION_DOT_REVERT_ANIMATION_DURATION = 617

@VisibleForTesting
object MotionTestKeys {
    val entryCompleted = MotionTestValueKey<Boolean>("PinBouncer::entryAnimationCompleted")
    val dotAppearFadeIn = MotionTestValueKey<List<Float>>("PinBouncer::dotAppearFadeIn")
    val dotAppearMoveUp = MotionTestValueKey<List<Float>>("PinBouncer::dotAppearMoveUp")
    val dotScaling = MotionTestValueKey<List<Float>>("PinBouncer::dotScaling")
}
