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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalView
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.exp

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
    val automixDurationMs: Int = 0,
)

private val Ink = Color(0xFFF4F4F7)
private val InkDim = Color(0xB8F4F4F7)
private val TrackInk = Color(0x38FFFFFF)
private val CaseRim = Color(0x33FFFFFF)

private class CdMotion {
    var angle by mutableFloatStateOf(0f)
    var speed by mutableFloatStateOf(15f)
    var held by mutableStateOf(false)
    var sparkle by mutableFloatStateOf(0f)
    var idleWeight by mutableFloatStateOf(1f)
    var lastTouchMs = 0L
    var scratched = false

    fun advance(dt: Float, playing: Boolean) {
        val target = if (playing) 15f else 0f
        val damping = exp(-dt / 1.6f)
        if (!held) {
            // integrate the coast exactly so 60 and 120 Hz don't feel different
            angle = (angle + target * dt + (speed - target) * 1.6f * (1f - damping)) % 360f
            speed = target + (speed - target) * damping
        } else if (android.os.SystemClock.uptimeMillis() - lastTouchMs > 80L) {
            // a parked finger isn't still scratching
            speed *= exp(-dt / .06f)
        }
        if (!held && abs(speed - target) < PetalCdScratchSynth.MIN_SPEED) scratched = false
        val idleTarget = if (held) 0f else 1f -
            (abs(speed - target) / 180f).coerceIn(0f, 1f)
        idleWeight += (idleTarget - idleWeight) * (1f - exp(-dt / .35f))
        sparkle = maxOf(sparkle * exp(-dt / .7f),
            (abs(speed - target) / 1000f).coerceIn(0f, .65f))
    }

    fun scratchVelocity(): Float = if (scratched) speed else 0f
}

@Composable
fun PetalCdMediaPlayer(
    state: PetalCdState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onScratchMotion: (Float) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // square, 60% wide, the disc hangs out the side of it
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = maxWidth * 0.60f
            CdAssembly(state, side, onPlayPause, onScratchMotion)
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
        IosControls(state, onPrevious, onNext)
    }
}

@Composable
private fun CdAssembly(
    state: PetalCdState,
    side: Dp,
    onPlayPause: () -> Unit,
    onScratchMotion: (Float) -> Unit,
) {
    val motion = remember { CdMotion() }
    val playing by rememberUpdatedState(state.playing)
    val scratchMotion by rememberUpdatedState(onScratchMotion)
    val hostView = LocalView.current
    DisposableEffect(motion, hostView) {
        onDispose {
            motion.held = false
            motion.scratched = false
            scratchMotion(0f)
            hostView.parent?.requestDisallowInterceptTouchEvent(false)
        }
    }
    LaunchedEffect(motion) {
        var previous = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            motion.advance(((now - previous) / 1_000_000_000f).coerceIn(0f, .05f), playing)
            scratchMotion(motion.scratchVelocity())
            previous = now
        }
    }
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
    val degrees = (flip.value + yaw * motion.idleWeight + 360f) % 360f
    val showBack = degrees > 90f && degrees < 270f
    val discDegrees = (flip.value + yaw * 1.6f * motion.idleWeight + 360f) % 360f
    val discBack = discDegrees > 90f && discDegrees < 270f

    // disc slides out of the sleeve when playing, hides back inside when paused
    val slide = remember { Animatable(if (state.playing) 1f else 0f) }
    LaunchedEffect(state.playing) {
        slide.animateTo(if (state.playing) 1f else 0f, tween(500, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(state.transitionPulse) {
        if (state.transitionPulse == 0L || !state.playing) return@LaunchedEffect
        // new track, give the disc a little kick out of the sleeve
        slide.snapTo(0.55f)
        slide.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }

    // ghost icon for play/pause, pops up on tap then fucks right off
    val flash = remember { Animatable(0f) }
    var flashKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(flashKey) {
        if (flashKey == 0) return@LaunchedEffect
        flash.snapTo(1f)
        flash.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
    }
    val toggle = {
        flashKey++
        onPlayPause()
    }

    val disc = side * 0.95f
    val discX = side / 2f + (side / 2f - disc * 0.10f) * slide.value
    val overhang = (discX + disc / 2f - side).coerceAtLeast(0.dp)
    // don't leave an empty bloody disc slot shifting the sleeve left
    val assemblyWidth = side + overhang
    val densityValue = LocalDensity.current.density
    val discCenter by rememberUpdatedState(
        with(LocalDensity.current) { Offset(discX.toPx(), side.toPx() / 2f) }
    )
    Box(
        modifier =
            Modifier.size(width = assemblyWidth, height = side)
                .graphicsLayer {
                    rotationY = yaw * motion.idleWeight + flip.value
                    rotationX = pitch * motion.idleWeight
                    cameraDistance = 10f * densityValue
                }
                // drag in the disc's flipped space, not the bloody screen's
                .pointerInput(motion) {
                    detectDragGestures(
                        onDragStart = {
                            motion.held = true
                            motion.speed = 0f
                            motion.scratched = false
                            motion.lastTouchMs = android.os.SystemClock.uptimeMillis()
                            hostView.parent?.requestDisallowInterceptTouchEvent(true)
                        },
                        onDragEnd = {
                            motion.held = false
                            hostView.parent?.requestDisallowInterceptTouchEvent(false)
                        },
                        onDragCancel = {
                            motion.held = false
                            motion.scratched = false
                            scratchMotion(0f)
                            hostView.parent?.requestDisallowInterceptTouchEvent(false)
                        },
                    ) { change, amount ->
                        val pivot = discCenter
                        val before = change.position - amount - pivot
                        val after = change.position - pivot
                        val delta = if (before.getDistance() < size.height * .12f ||
                            after.getDistance() < size.height * .12f) {
                            amount.x / size.width.coerceAtLeast(1) * 180f
                        } else {
                            val raw = (atan2(after.y, after.x) - atan2(before.y, before.x)) *
                                180f / Math.PI.toFloat()
                            (raw + 540f) % 360f - 180f
                        }
                        val dt = ((change.uptimeMillis - motion.lastTouchMs) / 1000f)
                            .coerceIn(.001f, .05f)
                        motion.lastTouchMs = change.uptimeMillis
                        motion.angle = (motion.angle + delta) % 360f
                        motion.scratched = true
                        val gain = 1f - exp(-dt / .035f)
                        motion.speed += ((delta / dt).coerceIn(-1440f, 1440f) - motion.speed) * gain
                        motion.sparkle = maxOf(motion.sparkle, .45f)
                        change.consume()
                    }
                },
    ) {
        // disc goes UNDER the sleeve, the case hides whatever is still inside it
        Box(Modifier.offset(x = discX - disc / 2f, y = (side - disc) / 2f)) {
            RefractionDisc(state, disc, discBack, yaw, pitch, motion)
        }
        if (showBack) {
            // whole thing is flipped here, so the art reads as the reverse
            Box(Modifier.size(side)) { CaseBack(state, side, toggle) }
        } else {
            Box(Modifier.size(side)) { CaseFront(state, side, toggle) }
        }
        if (flash.value > 0f) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter =
                        painterResource(
                            // show what is happening, not what tapping does
                            if (state.playing) android.R.drawable.ic_media_play
                            else android.R.drawable.ic_media_pause
                        ),
                    contentDescription = null,
                    tint = Ink.copy(alpha = 0.55f * flash.value),
                    modifier = Modifier.size(44.dp).offset(x = -overhang / 2f),
                )
            }
        }
    }
}

@Composable
private fun CaseFront(state: PetalCdState, side: Dp, onPlayPause: () -> Unit) {
    val radius = side * 0.08f
    Box(
        modifier =
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(radius))
                // the case itself is the play/pause button now, big center button is dead
                .clickable { onPlayPause() },
    ) {
        FadingArt(
            bitmap = state.artwork,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF2A2F45), Color(0xFF171A26)))
                        )
            )
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

// fade the new cover over the old one
@Composable
private fun FadingArt(
    bitmap: ImageBitmap?,
    contentDescription: String?,
    modifier: Modifier,
    placeholder: @Composable () -> Unit,
) {
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    var current by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(bitmap) {
        if (bitmap !== current) {
            previous = current
            current = bitmap
            if (previous != null && bitmap != null) {
                fade.snapTo(0f)
                fade.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
            } else {
                fade.snapTo(1f)
            }
        }
    }
    Box(modifier = modifier) {
        previous?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val c = current
        if (c != null) {
            Image(
                bitmap = c,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = fade.value },
            )
        } else {
            placeholder()
        }
    }
}

// spinning glass disc. art on top, refraction rings and chroma rim over it
@Composable
private fun RefractionDisc(
    state: PetalCdState,
    side: Dp,
    dataSide: Boolean,
    tiltY: Float,
    tiltX: Float,
    motion: CdMotion,
) {
    val density = LocalDensity.current.density
    val spin = rememberInfiniteTransition(label = "disc")
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
        if (state.automixDurationMs > 0) {
            // Keep the aura for the complete blend; do not obscure the artwork.
            dip.snapTo(0f)
            kotlinx.coroutines.delay((state.automixDurationMs - 600).toLong())
            chroma.animateTo(0f, tween(600))
        } else {
            dip.snapTo(1f)
            launch { chroma.animateTo(0f, tween(1500, easing = FastOutSlowInEasing)) }
            dip.animateTo(0f, tween(1500, easing = FastOutSlowInEasing))
        }
    }
    val chromaVal = chroma.value
    val dipVal = dip.value
    val artwork = state.artwork

    val edgeX = (-tiltY / 15f * 2.6f).dp
    val edgeY = (tiltX / 7f * 2.6f).dp
    Box(
        modifier =
            Modifier.size(side)
                .graphicsLayer {
                    // give the disc its own sway so it is not welded to the case
                    rotationY = tiltY * 0.6f * motion.idleWeight
                    rotationX = tiltX * 0.6f * motion.idleWeight
                    cameraDistance = 7f * density
                    alpha = 1f - 0.5f * dipVal
                    scaleX = 1f - 0.04f * dipVal
                    scaleY = 1f - 0.04f * dipVal
                },
    ) {
        // side wall: dark puck shoved off axis so the disc has some thickness
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .offset(x = edgeX, y = edgeY)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF23252F), Color(0xFF08080D))
                        ),
                        CircleShape,
                    )
        )
        Box(
            modifier =
                Modifier.fillMaxSize()
                    // spin on its own axle before the whole disc tilts
                    .graphicsLayer { rotationZ = motion.angle }
                    .shadow(6.dp, CircleShape, clip = false)
                    .clip(CircleShape),
        ) {
        // label side gets the art, the flip shows the bare data side instead
        if (!dataSide) {
            FadingArt(
                bitmap = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxSize().background(
                            Brush.radialGradient(
                                listOf(Color(0xFF3A4060), Color(0xFF181B28))
                            )
                        )
                )
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
             val r = size.minDimension / 2f
             val sparkle = motion.sparkle

            // little rim glints, not a disco ball slapped over the artwork
            for (i in 0 until 7) {
                val phase = sweep * .025f + i * 1.9f
                val intensity = ((sin(phase) - .65f) / .35f).coerceIn(0f, 1f) *
                    (.12f + sparkle * .65f)
                val theta = i * .8976f
                val point = c + Offset(cos(theta), sin(theta)) * (r * .86f)
                val span = (1.8f + sparkle * 2.8f).dp.toPx()
                val glint = Color.White.copy(alpha = intensity)
                drawLine(glint, point - Offset(span, 0f), point + Offset(span, 0f),
                    strokeWidth = .7.dp.toPx(), blendMode = BlendMode.Plus)
                drawLine(glint, point - Offset(0f, span), point + Offset(0f, span),
                    strokeWidth = .7.dp.toPx(), blendMode = BlendMode.Plus)
            }

            if (dataSide) {
                // dark chromatic arse-end of the disc
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            0f to Color(0xFF4A4470),
                            0.32f to Color(0xFF221F35),
                            0.72f to Color(0xFF121019),
                            1f to Color(0xFF07060B),
                            center = c,
                            radius = r,
                        ),
                    radius = r,
                    center = c,
                )
                // oil slick so it isn't a flat black puck
                drawCircle(
                    brush =
                        Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.14f to Color(0x2460A0FF),
                            0.3f to Color(0x16FF6060),
                            0.48f to Color(0x2050FFB0),
                            0.7f to Color(0x1A9F60FF),
                            0.88f to Color(0x18FFD060),
                            1f to Color.Transparent,
                        ),
                    radius = r,
                    center = c,
                    blendMode = BlendMode.Plus,
                )
                // laser written data, stupid thin rings
                val inner = r * 0.21f
                val tracks = 88
                val stepR = (r - inner) / tracks
                for (i in 0 until tracks) {
                    val band = ((i * 2654435761L) % 100L) / 100f
                    val a = (0x12 + (0x30 * band).toInt()).coerceAtMost(0x42)
                    drawCircle(
                        color = Color(0xE8, 0xEE, 0xFF, a),
                        radius = inner + stepR * i,
                        center = c,
                        style = Stroke(width = 0.6.dp.toPx()),
                    )
                }
                // brighter guide tracks so it reads as pressed media
                for (frac in listOf(0.34f, 0.58f, 0.8f)) {
                    drawCircle(
                        color = Color(0x33BFE8FF),
                        radius = inner + (r - inner) * frac,
                        center = c,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }

            if (state.automixDurationMs > 0 && chromaVal > 0f) {
                val auraColors = listOf(Color(0xFFFF6090), Color(0xFF9060FF),
                    Color(0xFF50DFFF), Color(0xFF70FFA0), Color(0xFFFF6090))
                rotate(sweep) {
                    for (ring in 1..5) {
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.sweepGradient(auraColors, c),
                            radius = r - ring.dp.toPx(),
                            center = c,
                            alpha = chromaVal * (0.22f - ring * 0.025f),
                            style = Stroke(width = (12 - ring).dp.toPx()),
                        )
                    }
                }
            }
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
}

@Composable
private fun CaseBack(state: PetalCdState, side: Dp, onPlayPause: () -> Unit) {
    val radius = side * 0.08f
    Box(
        modifier =
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(radius))
                // back is tappable too, the thing keeps flipping over every few seconds
                .clickable { onPlayPause() }
                .background(Brush.linearGradient(listOf(Color(0xFF1B1D25), Color(0xFF0C0D12))))
                .border(1.dp, CaseRim, RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        // cover art bleeding through the case, dimmed to hell. no text, its not a cassette
        state.artwork?.let { artwork ->
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f },
            )
        }
        // gloss so this side reads as glass too
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x16FFFFFF),
                            0.28f to Color.Transparent,
                            1f to Color(0x22000000),
                        )
                    )
        )
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
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    // prev/next only. play/pause lives on the case now, stop looking for it
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // wide gap so the fingerprint icon lands between them, not on top
        horizontalArrangement = Arrangement.spacedBy(104.dp),
    ) {
        Icon(
            painter = painterResource(android.R.drawable.ic_media_previous),
            contentDescription = null,
            tint = if (state.canPrevious) Ink else Color(0x55FFFFFF),
            modifier = Modifier.size(30.dp).clickable(enabled = state.canPrevious) { onPrevious() },
        )
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
