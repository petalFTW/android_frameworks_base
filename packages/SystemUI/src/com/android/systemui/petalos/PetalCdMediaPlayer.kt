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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

// state the controller feeds in
data class PetalCdState(
    val hasMedia: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val artwork: ImageBitmap? = null,
    val canSeek: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    // bumped every bridge transition so the disc does its chroma thing
    val transitionPulse: Long = 0L,
)

private val Ink = Color(0xFFF4F4F7)
private val InkDim = Color(0xB8F4F4F7)
private val TrackInk = Color(0x38FFFFFF)
private val CaseRim = Color(0x33FFFFFF)

@Composable
fun PetalCdMediaPlayer(
    state: PetalCdState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // square, 64% wide, actually centered now
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = maxWidth * 0.64f
            CdAssembly(state, side)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = state.title.ifEmpty { state.artist },
            color = Ink,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        if (state.title.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = state.artist,
                color = InkDim,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            IosScrubber(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                enabled = state.canSeek && state.durationMs > 0,
                onSeek = onSeek,
            )
        }
        Spacer(Modifier.height(14.dp))
        IosControls(state, onPlayPause, onPrevious, onNext)
    }
}

@Composable
private fun CdAssembly(state: PetalCdState, side: Dp) {
    val transition = rememberInfiniteTransition(label = "cd")
    // the 3d spin. slow yaw, smaller pitch
    val yaw by
        transition.animateFloat(
            initialValue = -15f,
            targetValue = 15f,
            animationSpec =
                infiniteRepeatable(tween(4600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "cdYaw",
        )
    val pitch by
        transition.animateFloat(
            initialValue = 7f,
            targetValue = -7f,
            animationSpec =
                infiniteRepeatable(tween(5600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "cdPitch",
        )
    // flip over now and then to show the back
    val flip = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(9000)
            flip.animateTo(180f, tween(900, easing = FastOutSlowInEasing))
            delay(4800)
            flip.animateTo(360f, tween(900, easing = FastOutSlowInEasing))
            flip.snapTo(0f)
        }
    }
    val degrees = flip.value % 360f
    val showBack = degrees > 90f && degrees < 270f
    val densityValue = LocalDensity.current.density
    Box(
        modifier =
            Modifier.size(side).graphicsLayer {
                rotationY = yaw + flip.value
                rotationX = pitch
                cameraDistance = 10f * densityValue
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showBack) {
            // rotate the back so it isn't mirrored
            Box(Modifier.size(side).graphicsLayer { rotationY = 180f }) {
                CaseBack(state, side)
            }
        } else {
            CaseFront(state, side)
        }
    }
}

@Composable
private fun CaseFront(state: PetalCdState, side: Dp) {
    val radius = side * 0.08f
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(radius))) {
        val artwork = state.artwork
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF2A2F45), Color(0xFF171A26)))
                        )
            )
        }
        // the disc floats over the case
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            RefractionDisc(state, side * 0.74f)
        }
        // glass rim + gloss
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .border(1.dp, CaseRim, RoundedCornerShape(radius))
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x22FFFFFF),
                            0.28f to Color.Transparent,
                            1f to Color(0x33000000),
                        ),
                        RoundedCornerShape(radius),
                    )
        )
    }
}

// spinning glass disc. art on top, refraction rings and chroma rim over it
@Composable
private fun RefractionDisc(state: PetalCdState, side: Dp) {
    val spin = rememberInfiniteTransition(label = "disc")
    val angle by
        spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
            label = "discAngle",
        )
    val sweep by
        spin.animateFloat(
            initialValue = 0f,
            targetValue = -360f,
            animationSpec = infiniteRepeatable(tween(8600, easing = LinearEasing)),
            label = "discSweep",
        )
    // chroma flash + alpha dip on a bridge transition
    val chroma = remember { Animatable(0f) }
    val dip = remember { Animatable(0f) }
    LaunchedEffect(state.transitionPulse) {
        if (state.transitionPulse == 0L) return@LaunchedEffect
        chroma.snapTo(1f)
        dip.snapTo(1f)
        launch { chroma.animateTo(0f, tween(1500, easing = FastOutSlowInEasing)) }
        dip.animateTo(0f, tween(1500, easing = FastOutSlowInEasing))
    }
    val chromaVal = chroma.value
    val dipVal = dip.value
    val artwork = state.artwork

    Box(
        modifier =
            Modifier.size(side)
                .graphicsLayer {
                    // only spin while playing, feels dead otherwise
                    rotationZ = if (state.playing) angle else 0f
                    alpha = 1f - 0.5f * dipVal
                    scaleX = 1f - 0.04f * dipVal
                    scaleY = 1f - 0.04f * dipVal
                }
                .shadow(10.dp, CircleShape, clip = false)
                .clip(CircleShape),
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier =
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(
                            listOf(Color(0xFF3A4060), Color(0xFF181B28))
                        )
                    )
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f

            // chromatic rim. rgb rings sit offset so the edge fringes, pulse widens it
            val off = (1.dp.toPx() + 2.4.dp.toPx() * chromaVal)
            val rimAlpha = ((0x30 + 0x90 * chromaVal).toInt()).coerceAtMost(0xC0)
            rotate(0f) {
                drawCircle(
                    Color(rimAlpha.shl(24) or 0x00FF6060.toInt()),
                    radius = r - 0.5f,
                    center = Offset(c.x - off, c.y + off * 0.4f),
                    blendMode = BlendMode.Plus,
                )
                drawCircle(
                    Color(rimAlpha.shl(24) or 0x0060FF9B.toInt()),
                    radius = r - 0.5f,
                    center = c,
                    blendMode = BlendMode.Plus,
                )
                drawCircle(
                    Color(rimAlpha.shl(24) or 0x0060A0FF.toInt()),
                    radius = r - 0.5f,
                    center = Offset(c.x + off, c.y - off * 0.4f),
                    blendMode = BlendMode.Plus,
                )
            }

            // refraction rings, each drifting at its own speed so the light bends
            val ringSpecs = listOf(
                0.34f to 1.9f,
                0.52f to -1.3f,
                0.70f to 0.8f,
                0.86f to -0.5f,
            )
            for ((frac, speed) in ringSpecs) {
                rotate(sweep * speed + frac * 320f) {
                    drawArc(
                        color = Color((0x2E + (0x50 * chromaVal).toInt()).coerceAtMost(0x90)
                            .shl(24) or 0x00FFFFFF.toInt()),
                        startAngle = 12f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(c.x - r * frac, c.y - r * frac),
                        size = Size(r * frac * 2f, r * frac * 2f),
                        style = Stroke(width = (0.9f + frac).dp.toPx() * 0.7f),
                    )
                }
            }

            // rotating specular comet
            rotate(sweep * 1.6f) {
                drawCircle(
                    brush =
                        Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.02f to Color(0x30FFFFFF),
                            0.06f to Color(0x14FFFFFF),
                            0.5f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    radius = r,
                    center = c,
                )
            }

            // outer glass rim
            drawCircle(
                Color(0x59FFFFFF),
                radius = r - 0.6f,
                center = c,
                style = Stroke(width = 1.1.dp.toPx()),
            )

            // spindle
            drawCircle(Color(0xE6141620), radius = r * 0.11f, center = c)
            drawCircle(
                Color(0x73FFFFFF),
                radius = r * 0.11f,
                center = c,
                style = Stroke(width = 1.dp.toPx() * 0.8f),
            )
            drawCircle(Color(0xB3FFFFFF), radius = r * 0.035f, center = c)
            // clear plastic ring around the hole, like a real disc
            drawCircle(
                Color(0x2EFFFFFF),
                radius = r * 0.20f,
                center = c,
                style = Stroke(width = r * 0.055f),
            )
        }
    }
}

@Composable
private fun CaseBack(state: PetalCdState, side: Dp) {
    val radius = side * 0.08f
    Box(
        modifier =
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(radius))
                .background(Brush.linearGradient(listOf(Color(0xFF1B1D25), Color(0xFF0C0D12))))
                .border(1.dp, CaseRim, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = side * 0.12f),
        ) {
            Text(
                text = state.title.ifEmpty { state.artist },
                color = Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (state.title.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.artist,
                    color = InkDim,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(18.dp))
            // fake track lines so the back looks legit
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(0.72f),
            ) {
                repeat(4) { index ->
                    Box(
                        modifier =
                            Modifier.fillMaxWidth(if (index == 3) 0.6f else 1f)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(Color(0x22FFFFFF))
                    )
                }
            }
        }
    }
}

@Composable
private fun IosScrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val progress =
        if (dragging) dragFraction
        else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragging) (dragFraction * durationMs).roundToLong() else positionMs
    val remainingMs = (durationMs - shownMs).coerceAtLeast(0L)

    val barModifier =
        Modifier.fillMaxWidth()
            .height(20.dp)
            .then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.4f })
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek(dragFraction)
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = barModifier) {
            val h = 4.dp.toPx()
            val cy = size.height / 2f
            val r = CornerRadius(h / 2f)
            drawRoundRect(
                color = TrackInk,
                topLeft = Offset(0f, cy - h / 2f),
                size = Size(size.width, h),
                cornerRadius = r,
            )
            drawRoundRect(
                color = Ink,
                topLeft = Offset(0f, cy - h / 2f),
                size = Size(size.width * progress, h),
                cornerRadius = r,
            )
            drawCircle(color = Ink, radius = 6.dp.toPx(), center = Offset(size.width * progress, cy))
        }
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownMs), color = InkDim, fontSize = 12.sp)
            Text("-" + formatTime(remainingMs), color = InkDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun IosControls(
    state: PetalCdState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(38.dp),
    ) {
        Icon(
            painter = painterResource(android.R.drawable.ic_media_previous),
            contentDescription = null,
            tint = if (state.canPrevious) Ink else Color(0x55FFFFFF),
            modifier = Modifier.size(30.dp).clickable(enabled = state.canPrevious) { onPrevious() },
        )
        Box(
            modifier =
                Modifier.size(66.dp)
                    .clip(CircleShape)
                    .background(Color(0x22FFFFFF))
                    .border(1.dp, CaseRim, CircleShape)
                    .clickable { onPlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (state.playing) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    ),
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(34.dp),
            )
        }
        Icon(
            painter = painterResource(android.R.drawable.ic_media_next),
            contentDescription = null,
            tint = if (state.canNext) Ink else Color(0x55FFFFFF),
            modifier = Modifier.size(30.dp).clickable(enabled = state.canNext) { onNext() },
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
