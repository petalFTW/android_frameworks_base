/*
 * Copyright (C) 2025 The Android Open Source Project
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

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.systemui.media.remedia.ui.compose

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults.colors
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.fastRoundToInt
import com.android.compose.PlatformButton
import com.android.compose.PlatformIconButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.animation.scene.transitions
import com.android.compose.gesture.effect.rememberOffsetOverscrollEffect
import com.android.compose.gesture.overscrollToDismiss
import com.android.compose.modifiers.thenIf
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.common.ui.compose.byLayoutId
import com.android.systemui.common.ui.compose.load
import com.android.systemui.common.ui.compose.singleton
import com.android.systemui.communal.ui.compose.extensions.detectLongPressGesture
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaColorScheme
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.viewmodel.MediaCardGutsViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaCardViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaDeviceChipViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaNavigationViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaPlayPauseActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaSecondaryActionViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaSettingsButtonViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.petalos.PetalQsSkin
import com.android.systemui.petalos.LocalPetalNarrowMedia
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import com.android.systemui.petalos.LocalPetalQsPreferences
import com.android.systemui.petalos.LocalPetalMediaGlass
import com.android.systemui.petalos.LocalPetalMediaReveal
import com.android.systemui.petalos.LocalPetalMediaStyle
import com.android.systemui.petalos.PetalMediaStyle
import com.android.systemui.petalos.petalQsContentReveal
import com.android.systemui.island.render.RefractionShader
import com.android.systemui.island.render.LiquidGlassDrawable
import org.petalos.config.PetalConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Media host picks the layout and whether swiping can dismiss it.
@Composable
fun Media(
    viewModelFactory: MediaViewModel.Factory,
    presentationStyle: MediaPresentationStyle,
    behavior: MediaUiBehavior,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: MediaViewModel =
        rememberViewModel("Media.viewModel") {
            viewModelFactory.create(
                context = context,
                carouselVisibility = behavior.carouselVisibility,
            )
        }

    CardCarousel(
        viewModel = viewModel,
        presentationStyle = presentationStyle,
        behavior = behavior,
        onDismissed = onDismissed,
        modifier = modifier,
    )
}

// Cards share the host's swipe policy.
@Composable
private fun CardCarousel(
    viewModel: MediaViewModel,
    presentationStyle: MediaPresentationStyle,
    behavior: MediaUiBehavior,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = viewModel.isCarouselVisible, modifier = modifier) {
        CardCarouselContent(
            viewModel = viewModel,
            presentationStyle = presentationStyle,
            behavior = behavior,
            onDismissed = onDismissed,
        )
    }
}

@Composable
private fun CardCarouselContent(
    viewModel: MediaViewModel,
    presentationStyle: MediaPresentationStyle,
    behavior: MediaUiBehavior,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState { viewModel.cards.size }
    LaunchedEffect(viewModel.currentIndex) {
        if (viewModel.currentIndex != pagerState.currentPage) {
            pagerState.scrollToPage(viewModel.currentIndex)
        }
    }
    LaunchedEffect(pagerState.currentPage) { viewModel.onCardSelected(pagerState.currentPage) }
    var isFalseTouchDetected: Boolean by
        remember(behavior.isCarouselScrollFalseTouch) { mutableStateOf(false) }
    val isSwipingEnabled = behavior.isCarouselScrollingEnabled && !isFalseTouchDetected

    val roundedCornerShape = RoundedCornerShape(32.dp)

    Box(
        modifier =
            modifier.clip(roundedCornerShape).pointerInput(behavior) {
                if (behavior.isCarouselScrollFalseTouch != null) {
                    awaitEachGesture {
                        awaitFirstDown(false, PointerEventPass.Initial)
                        isFalseTouchDetected = behavior.isCarouselScrollFalseTouch.invoke()
                    }
                }
            }
    ) {
        @Composable
        fun PagerContent(overscrollEffect: OverscrollEffect? = null) {
            Box(modifier = Modifier.sysuiResTag(MediaRes.MEDIA_CAROUSEL_SCROLLER)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.sysuiResTag(MediaRes.MEDIA_CAROUSEL),
                    userScrollEnabled = isSwipingEnabled,
                    pageSpacing = 8.dp,
                    beyondViewportPageCount = 1,
                    key = { index: Int -> viewModel.cards[index].key },
                    overscrollEffect = overscrollEffect ?: rememberOffsetOverscrollEffect(),
                ) { pageIndex: Int ->
                    Card(
                        viewModel = viewModel.cards[pageIndex],
                        presentationStyle = presentationStyle,
                        modifier =
                            Modifier.clip(roundedCornerShape).sysuiResTag(MediaRes.MEDIA_CONTROLS),
                    )
                }

                if (pagerState.pageCount > 1) {
                    PagerDots(
                        pagerState = pagerState,
                        activeColor = Color(0xffdee0ff),
                        nonActiveColor = Color(0xffa7a9ca),
                        dotSize = 6.dp,
                        spaceSize = 6.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    )
                }
            }
        }

        if (behavior.isCarouselDismissible) {
            Box(
                modifier =
                    Modifier.overscrollToDismiss(
                        orientation = Orientation.Horizontal,
                        enabled = isSwipingEnabled,
                        onDismissed = onDismissed,
                    )
            ) {
                PagerContent()
            }
        } else {
            val overscrollEffect = rememberOffsetOverscrollEffect()
            SwipeToReveal(
                foregroundContent = { PagerContent(overscrollEffect) },
                foregroundContentEffect = overscrollEffect,
                revealedContent = { revealAmount ->
                    RevealedContent(
                        viewModel = viewModel.settingsButtonViewModel,
                        revealAmount = revealAmount,
                    )
                },
                isSwipingEnabled = isSwipingEnabled,
            )
        }
    }

    LaunchedEffect(viewModel.scrollToFirst) {
        if (viewModel.scrollToFirst && viewModel.cards.isNotEmpty()) {
            pagerState.animateScrollToPage(0)
            viewModel.onScrollToFirstCard()
        }
    }
}

/** Renders the UI of a single media card. */
@Composable
private fun Card(
    viewModel: MediaCardViewModel,
    presentationStyle: MediaPresentationStyle,
    modifier: Modifier = Modifier,
) {
    val petalStyle = LocalPetalMediaStyle.current
    if (petalStyle == PetalMediaStyle.Lockscreen ||
        (petalStyle != PetalMediaStyle.Stock && PetalQsSkin.isEnabled(LocalContext.current))) {
        PetalConceptCard(viewModel, petalStyle, modifier)
        return
    }
    val stlState =
        rememberMutableSceneTransitionLayoutState(
            initialScene = presentationStyle.toScene(),
            transitions = Media.Transitions,
        )

    val colorScheme = rememberAnimatedColorScheme(viewModel.colorScheme)

    // Each time the presentation style changes, animate to the corresponding scene.
    LaunchedEffect(presentationStyle) {
        stlState.setTargetScene(targetScene = presentationStyle.toScene(), animationScope = this)
    }

    Box(modifier) {
        if (stlState.currentScene != Media.Scenes.Compact) {
            CardBackground(
                image = viewModel.background,
                colorScheme = colorScheme,
                modifier = Modifier.matchParentSize().sysuiResTag(MediaRes.BACKGROUND),
            )
        }

        Expandable(
            controller =
                rememberExpandableController(color = Color.Transparent, shape = RectangleShape),
            useModifierBasedImplementation = true,
        ) {
            key(stlState) {
                SceneTransitionLayout(state = stlState) {
                    scene(Media.Scenes.Default) {
                        CardForeground(
                            expandable = it,
                            viewModel = viewModel,
                            colorScheme = colorScheme,
                            threeRows = true,
                            fillHeight = false,
                        )
                    }

                    scene(Media.Scenes.Large) {
                        CardForeground(
                            expandable = it,
                            viewModel = viewModel,
                            colorScheme = colorScheme,
                            threeRows = true,
                            fillHeight = true,
                        )
                    }

                    scene(Media.Scenes.Compressed) {
                        CardForeground(
                            expandable = it,
                            viewModel = viewModel,
                            colorScheme = colorScheme,
                            threeRows = false,
                            fillHeight = false,
                        )
                    }

                    scene(Media.Scenes.Compact) {
                        CompactCardForeground(expandable = it, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun PetalConceptCard(
    viewModel: MediaCardViewModel,
    style: PetalMediaStyle,
    modifier: Modifier = Modifier,
) {
    val target = if (style == PetalMediaStyle.Mini) Media.Scenes.Compressed else Media.Scenes.Default
    val scenes = rememberMutableSceneTransitionLayoutState(target, Media.Transitions)
    LaunchedEffect(target) { scenes.setTargetScene(targetScene = target, animationScope = this) }
    val context = LocalContext.current
    val accent = Color(context.getColor(android.R.color.system_accent1_500))
    val glyph = Color.White
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.clip(shape)
        .border(1.dp, PetalQsSkin.tileGlassBorderBrush(true), shape)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                android.view.View(context).apply {
                    // tile glass fill, matches PetalQsSkin
                    setBackgroundColor(0x5F33333D.toInt())
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height,
                                24f * view.resources.displayMetrics.density)
                        }
                    }
                    clipToOutline = true
                    com.android.systemui.petalos.PetalMediaBackdrop.setEnabled(this, true)
                }
            },
            modifier = Modifier.matchParentSize(),
            onRelease = { com.android.systemui.petalos.PetalMediaBackdrop.setEnabled(it, false) },
        )
        Expandable(
            controller = rememberExpandableController(color = Color.Transparent, shape = shape),
            useModifierBasedImplementation = true,
        ) {
            SceneTransitionLayout(state = scenes) {
                scene(Media.Scenes.Default) {
                    CompositionLocalProvider(
                        LocalPetalMediaStyle provides if (style == PetalMediaStyle.Lockscreen) {
                            PetalMediaStyle.Lockscreen
                        } else PetalMediaStyle.LargeProgress,
                    ) {
                        PetalConceptForeground(it, viewModel, style == PetalMediaStyle.Lockscreen,
                            mini = false, accent = accent, glyph = glyph)
                    }
                }
                scene(Media.Scenes.Compressed) {
                    CompositionLocalProvider(LocalPetalMediaStyle provides PetalMediaStyle.Mini) {
                        PetalConceptForeground(it, viewModel, lock = false,
                            mini = true, accent = accent, glyph = glyph)
                    }
                }
            }
        }
        if (viewModel.guts.isVisible) {
            val colors = rememberAnimatedColorScheme(viewModel.colorScheme)
            CardGuts(viewModel.guts, colors, Modifier.matchParentSize())
        }
    }
}

@Composable
private fun ContentScope.PetalConceptForeground(
    expandable: Expandable,
    viewModel: MediaCardViewModel,
    lock: Boolean,
    mini: Boolean,
    accent: Color,
    glyph: Color,
) {
    Column(Modifier.fillMaxWidth().combinedClickable(
        onClick = { viewModel.onClick(expandable) },
        onLongClick = viewModel.onLongClick,
        onClickLabel = viewModel.onClickLabel,
    )) {
        if (mini) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PetalConceptArtwork(viewModel.background, Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Metadata(viewModel.title, viewModel.subtitle, glyph, Modifier.weight(1f))
                        SecondaryAction(viewModel.outputSwitcherChipButton, resId = MediaRes.OUTPUT_CHIP,
                            element = Media.Elements.OutputSwitcherButton)
                    }
                    PetalConceptTransport(viewModel, accent, mini = true)
                }
            }
            PetalConceptProgress(viewModel.navigation, accent, mini = true)
        } else {
            Box(Modifier.fillMaxWidth().thenIf(lock) { Modifier.padding(16.dp) }) {
                PetalConceptArtwork(viewModel.background,
                    Modifier.fillMaxWidth().height(if (lock) 156.dp else 108.dp)
                        .thenIf(lock) { Modifier.clip(RoundedCornerShape(16.dp)) })
                SecondaryAction(viewModel.outputSwitcherChipButton, resId = MediaRes.OUTPUT_CHIP,
                    element = Media.Elements.OutputSwitcherButton,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = .25f), CircleShape))
            }
            Metadata(viewModel.title, viewModel.subtitle, glyph,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (lock) 0.dp else 8.dp))
            PetalConceptProgress(viewModel.navigation, accent, mini = false)
            PetalConceptTransport(viewModel, accent, mini = false)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ContentScope.PetalConceptArtwork(image: Icon?, modifier: Modifier) {
    Element(Media.Elements.Artwork, modifier) {
        val bitmap = (image as? Icon.Loaded)?.asImageBitmap()
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer)) {
                Icon(painterResource(android.R.drawable.ic_media_play), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp).align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun ContentScope.PetalConceptTransport(viewModel: MediaCardViewModel, accent: Color, mini: Boolean) {
    val extras = viewModel.additionalActions.filterIsInstance<MediaSecondaryActionViewModel.Action>()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly) {
                if (!mini && extras.isNotEmpty() && !LocalPetalNarrowMedia.current) {
                    SecondaryAction(extras.first(), resId = "action0",
                        element = Media.Elements.additionalActionButton(0))
                }
                SecondaryAction(viewModel.previousAction, resId = MediaRes.PREV_BTN,
                    element = Media.Elements.PrevButton)
                PlayPauseAction(viewModel.playPauseAction, accent,
                    Color(PetalQsSkin.glyphOnBackground(accent.toArgb())), { 30.dp })
                SecondaryAction(viewModel.nextAction, resId = MediaRes.NEXT_BTN,
                    element = Media.Elements.NextButton)
                if (!mini && extras.size > 1 && !LocalPetalNarrowMedia.current) {
                    SecondaryAction(extras[1], resId = "action1",
                        element = Media.Elements.additionalActionButton(1))
                }
            }
            if (!mini) {
                val remaining = if (LocalPetalNarrowMedia.current) extras else extras.drop(2)
                remaining.chunked(4).forEachIndexed { row, actions ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        actions.forEachIndexed { column, action ->
                            val index = row * 4 + column + if (LocalPetalNarrowMedia.current) 0 else 2
                            SecondaryAction(action, resId = "action$index",
                                element = Media.Elements.additionalActionButton(index))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentScope.PetalConceptProgress(viewModel: MediaNavigationViewModel, accent: Color, mini: Boolean) {
    val navigation = viewModel as? MediaNavigationViewModel.Showing ?: return
    if (mini) {
        Element(Media.Elements.SeekBarSlider, Modifier.fillMaxWidth().height(3.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.White.copy(alpha = .18f))
                drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width * navigation.progress, size.height))
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Navigation(viewModel, isSeekBarVisible = true, areActionsVisible = false,
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(navigation.elapsedTime, color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
                Text(navigation.duration, color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun rememberAnimatedColorScheme(colorScheme: MediaColorScheme?): AnimatedColorScheme {
    val petalSkin = PetalQsSkin.isEnabled(LocalContext.current)
    val petalDark = PetalQsSkin.dark()
    val primaryColor = if (petalSkin) {
        if (petalDark) Color(0xFFE1E5ED) else Color(0xFF2C2E36)
    } else colorScheme?.primary ?: MaterialTheme.colorScheme.primaryFixed
    val onPrimaryColor = if (petalSkin) {
        if (petalDark) Color(0xFF202127) else Color(0xFFF5F5F8)
    } else colorScheme?.onPrimary ?: MaterialTheme.colorScheme.onPrimaryFixed
    val backgroundColor = colorScheme?.background ?: MaterialTheme.colorScheme.onSurface
    val animatedPrimary by animateColorAsState(targetValue = primaryColor)
    val animatedOnPrimary by animateColorAsState(targetValue = onPrimaryColor)
    val animatedBackground by animateColorAsState(targetValue = backgroundColor)

    return remember {
        object : AnimatedColorScheme {
            override val primary: Color
                get() = animatedPrimary

            override val onPrimary: Color
                get() = animatedOnPrimary

            override val background: Color
                get() = animatedBackground
        }
    }
}

// Content and guts share bounds, otherwise the menu jumps about.
@Composable
private fun ContentScope.CardForeground(
    expandable: Expandable,
    viewModel: MediaCardViewModel,
    colorScheme: AnimatedColorScheme,
    threeRows: Boolean,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    // Crossfade can't measure both bits the way this needs.
    if (LocalPetalNarrowMedia.current) {
        // don't give scene elements the hidden menu's zero-origin bounds
        val density = LocalDensity.current
        var contentHeight by remember { mutableIntStateOf(0) }
        Box(modifier) {
            if (!viewModel.guts.isVisible) {
                CardForegroundContent(expandable, viewModel, threeRows, fillHeight, colorScheme,
                    Modifier.onSizeChanged { contentHeight = it.height })
            } else {
                val height = with(density) { contentHeight.toDp() }.coerceAtLeast(232.dp)
                CardGuts(viewModel.guts, colorScheme, Modifier.fillMaxWidth().height(height))
            }
        }
        return
    }
    val gutsAlphaAnimatable = remember { Animatable(0f) }
    val isGutsVisible = viewModel.guts.isVisible
    LaunchedEffect(isGutsVisible) { gutsAlphaAnimatable.animateTo(if (isGutsVisible) 1f else 0f) }

    // Measure the player even when the guts cover it.
    Layout(
        content = {
            CardForegroundContent(
                expandable = expandable,
                viewModel = viewModel,
                threeRows = threeRows,
                fillHeight = fillHeight,
                colorScheme = colorScheme,
                modifier =
                    Modifier.layoutId(Media.LayoutId.CardForeground)
                        .petalQsContentReveal(LocalPetalMediaReveal.current).graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = 1f - gutsAlphaAnimatable.value
                    },
            )

            CardGuts(
                viewModel = viewModel.guts,
                colorScheme = colorScheme,
                modifier =
                    Modifier.layoutId(Media.LayoutId.CardGuts).graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = gutsAlphaAnimatable.value
                    },
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val measurableByLayoutId = measurables.byLayoutId<Media.LayoutId>()
        val contentPlaceable =
            measurableByLayoutId[Media.LayoutId.CardForeground]!!.measure(constraints)
        // Same bounds, no surprise resizing.
        val gutsPlaceable =
            measurableByLayoutId[Media.LayoutId.CardGuts]!!.measure(
                Constraints.fixed(contentPlaceable.width, contentPlaceable.height)
            )

        layout(contentPlaceable.measuredWidth, contentPlaceable.measuredHeight) {
            if (!viewModel.guts.isVisible || gutsAlphaAnimatable.isRunning) {
                contentPlaceable.place(0, 0)
            }
            if (viewModel.guts.isVisible || gutsAlphaAnimatable.isRunning) {
                gutsPlaceable.place(0, 0)
            }
        }
    }
}

@Composable
private fun ContentScope.CardForegroundContent(
    expandable: Expandable,
    viewModel: MediaCardViewModel,
    threeRows: Boolean,
    fillHeight: Boolean,
    colorScheme: AnimatedColorScheme,
    modifier: Modifier = Modifier,
) {
    if (LocalPetalNarrowMedia.current) {
        PetalNarrowCardForeground(expandable, viewModel, colorScheme, modifier)
        return
    }
    Column(
        modifier =
            modifier.combinedClickable(
                onClick = { viewModel.onClick(expandable) },
                onLongClick = viewModel.onLongClick,
                onClickLabel = viewModel.onClickLabel,
            )
    ) {
        // Always add the first/top row, regardless of presentation style.
        Box(modifier = Modifier.fillMaxWidth()) {
            // Icon.
            Icon(
                icon = viewModel.icon,
                tint = colorScheme.primary,
                modifier =
                    Modifier.align(Alignment.TopStart)
                        .padding(top = 16.dp, start = 16.dp)
                        .size(24.dp)
                        .clip(CircleShape),
            )

            var cardMaxWidth: Int by remember { mutableIntStateOf(0) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        // Save card width so the output chip doesn't hog it.
                        .layout { measurable, constraints ->
                            cardMaxWidth = constraints.maxWidth
                            val placeable = measurable.measure(constraints)

                            layout(placeable.measuredWidth, placeable.measuredHeight) {
                                placeable.place(0, 0)
                            }
                        },
            ) {
                AnimatedVisibility(
                    visible = viewModel.deviceSuggestionChip != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    rememberLastNonNull(viewModel.deviceSuggestionChip)?.let {
                        DeviceChip(
                            viewModel = it,
                            style =
                                DeviceChipStyle(
                                    fillColor = Color.Transparent,
                                    contentColor = colorScheme.primary,
                                    borderColor = colorScheme.primary,
                                ),
                            modifier =
                                Modifier.fractionalMaxWidth(
                                        containerMaxWidth = cardMaxWidth,
                                        fraction = 0.5f,
                                    )
                                    .sysuiResTag(MediaRes.SUGGESTED_DEVICE_CHIP),
                        )
                    }
                }

                DeviceChip(
                    viewModel = viewModel.outputSwitcherChip,
                    style =
                        DeviceChipStyle(
                            fillColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary,
                        ),
                    modifier =
                        Modifier
                            // Leave room for the app icon and suggested device.
                            .fractionalMaxWidth(containerMaxWidth = cardMaxWidth, fraction = 0.4f)
                            .padding(end = 16.dp)
                            .sysuiResTag(MediaRes.OUTPUT_CHIP),
                )
            }
        }

        // Spare height goes here, controls stay near the bottom.
        if (fillHeight) {
            Spacer(Modifier.weight(1f))
        }

        if (threeRows) {
            // Three row presentation style.
            //
            // Second row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            ) {
                Metadata(
                    title = viewModel.title,
                    subtitle = viewModel.subtitle,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )

                if (viewModel.actionButtonLayout == MediaCardActionButtonLayout.WithPlayPause) {
                    AnimatedVisibility(visible = viewModel.playPauseAction != null) {
                        PlayPauseAction(
                            viewModel = viewModel.playPauseAction,
                            buttonColor = colorScheme.primary,
                            iconColor = colorScheme.onPrimary,
                            buttonCornerRadius = { isPlaying -> if (isPlaying) 16.dp else 48.dp },
                        )
                    }
                } else {
                    Spacer(Modifier.size(width = 0.dp, height = 48.dp))
                }
            }

            // Third row.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                ) {
                    Navigation(
                        viewModel = viewModel.navigation,
                        isSeekBarVisible = true,
                        areActionsVisible =
                            viewModel.actionButtonLayout ==
                                MediaCardActionButtonLayout.WithPlayPause,
                        modifier = Modifier.weight(1f),
                    )

                    viewModel.additionalActions.fastForEachIndexed { index, action ->
                        SecondaryAction(
                            viewModel = action,
                            resId = "action$index",
                            element = Media.Elements.additionalActionButton(index),
                        )
                    }
                }
            }
        } else {
            // Two row presentation style.
            //
            // Bottom row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Metadata(
                    title = viewModel.title,
                    subtitle = viewModel.subtitle,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Navigation(
                        viewModel = viewModel.navigation,
                        isSeekBarVisible = false,
                        areActionsVisible =
                            viewModel.actionButtonLayout ==
                                MediaCardActionButtonLayout.WithPlayPause,
                        modifier = Modifier.padding(end = 8.dp),
                    )

                    if (
                        viewModel.actionButtonLayout ==
                            MediaCardActionButtonLayout.SecondaryActionsOnly
                    ) {
                        viewModel.additionalActions.fastForEachIndexed { index, action ->
                            SecondaryAction(
                                viewModel = action,
                                resId = "action$index",
                                element = Media.Elements.additionalActionButton(index),
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }

                if (viewModel.actionButtonLayout == MediaCardActionButtonLayout.WithPlayPause) {
                    AnimatedVisibility(visible = viewModel.playPauseAction != null) {
                        PlayPauseAction(
                            viewModel = viewModel.playPauseAction,
                            buttonColor = colorScheme.primary,
                            iconColor = colorScheme.onPrimary,
                            buttonCornerRadius = { isPlaying -> if (isPlaying) 16.dp else 48.dp },
                        )
                    }
                } else {
                    Spacer(Modifier.size(width = 0.dp, height = 48.dp))
                }
            }
        }
    }
}

// The side sliders stole some width; give playback its own row.
@Composable
private fun ContentScope.PetalNarrowCardForeground(
    expandable: Expandable,
    viewModel: MediaCardViewModel,
    colorScheme: AnimatedColorScheme,
    modifier: Modifier = Modifier,
) {
    val glyph = PetalQsSkin.glyphOn(PetalQsSkin.dark())
    Column(
        modifier = modifier.fillMaxWidth()
            .combinedClickable(
                onClick = { viewModel.onClick(expandable) },
                onLongClick = viewModel.onLongClick,
                onClickLabel = viewModel.onClickLabel,
            )
             .padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon = viewModel.icon,
                tint = glyph,
                modifier = Modifier.size(24.dp).clip(CircleShape),
            )
            Spacer(Modifier.size(8.dp))
            DeviceChip(
                viewModel = viewModel.outputSwitcherChip,
                style = DeviceChipStyle(colorScheme.primary, colorScheme.onPrimary),
                modifier = Modifier.weight(1f).sysuiResTag(MediaRes.OUTPUT_CHIP),
                topPadding = 0.dp,
            )
        }
        AnimatedVisibility(
            visible = viewModel.deviceSuggestionChip != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            rememberLastNonNull(viewModel.deviceSuggestionChip)?.let {
                DeviceChip(
                    viewModel = it,
                    style = DeviceChipStyle(
                        fillColor = Color.Transparent,
                        contentColor = glyph,
                        borderColor = glyph,
                    ),
                    modifier = Modifier.fillMaxWidth().sysuiResTag(MediaRes.SUGGESTED_DEVICE_CHIP),
                    topPadding = 0.dp,
                )
            }
        }
        Metadata(
            title = viewModel.title,
            subtitle = viewModel.subtitle,
            color = glyph,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                val navigation = viewModel.navigation as? MediaNavigationViewModel.Showing
                val primaryActions =
                    viewModel.actionButtonLayout == MediaCardActionButtonLayout.WithPlayPause
                if (primaryActions && navigation != null) {
                    SecondaryAction(
                        navigation.left,
                        resId = MediaRes.PREV_BTN,
                        element = Media.Elements.PrevButton,
                    )
                }
                if (primaryActions) {
                    AnimatedVisibility(visible = viewModel.playPauseAction != null) {
                        PlayPauseAction(
                            viewModel = viewModel.playPauseAction,
                            buttonColor = colorScheme.primary,
                            iconColor = colorScheme.onPrimary,
                            buttonCornerRadius = { 24.dp },
                        )
                    }
                }
                if (primaryActions && navigation != null) {
                    SecondaryAction(
                        navigation.right,
                        resId = MediaRes.NEXT_BTN,
                        element = Media.Elements.NextButton,
                    )
                }
            }
            Navigation(
                viewModel = viewModel.navigation,
                isSeekBarVisible = true,
                areActionsVisible = false,
                modifier = Modifier.fillMaxWidth(),
            )
            viewModel.additionalActions.chunked(3).forEachIndexed { rowIndex, actions ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    actions.forEachIndexed { column, action ->
                        val index = rowIndex * 3 + column
                        SecondaryAction(
                            viewModel = action,
                            resId = "action$index",
                            element = Media.Elements.additionalActionButton(index),
                        )
                    }
                }
            }
        }
    }
}

// Compact host gets one row, no guts.
@Composable
private fun ContentScope.CompactCardForeground(
    expandable: Expandable,
    viewModel: MediaCardViewModel,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clickable(
                    onClick = { viewModel.onClick(expandable) },
                    onClickLabel = viewModel.onClickLabel,
                )
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
    ) {
        Icon(
            icon = viewModel.icon,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )

        Metadata(
            title = viewModel.title,
            subtitle = viewModel.subtitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        SecondaryAction(
            viewModel = viewModel.outputSwitcherChipButton,
            resId = MediaRes.OUTPUT_CHIP,
            element = Media.Elements.OutputSwitcherButton,
            iconColor = MaterialTheme.colorScheme.onSurface,
        )

        val rightAction = (viewModel.navigation as? MediaNavigationViewModel.Showing)?.right
        if (rightAction != null) {
            SecondaryAction(
                viewModel = rightAction,
                resId = MediaRes.NEXT_BTN,
                element = Media.Elements.NextButton,
                iconColor = MaterialTheme.colorScheme.onSurface,
            )
        }

        AnimatedVisibility(visible = viewModel.playPauseAction != null) {
            PlayPauseAction(
                viewModel = viewModel.playPauseAction,
                buttonColor = MaterialTheme.colorScheme.primaryContainer,
                iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                buttonCornerRadius = { isPlaying -> if (isPlaying) 16.dp else 24.dp },
            )
        }
    }
}

/** Renders the background of a card, loading the artwork and showing an overlay on top of it. */
@Composable
private fun CardBackground(
    image: Icon?,
    colorScheme: AnimatedColorScheme,
    modifier: Modifier = Modifier,
) {
    val glassSettings = petalMediaGlassSettings()
    val blurScale = glassSettings.blur
    val petalSkin = PetalQsSkin.isEnabled(LocalContext.current)
    val petalDark = PetalQsSkin.dark()
    val qsGlass = LocalPetalQsPreferences.current.enabled || LocalPetalMediaGlass.current
    Crossfade(targetState = image, modifier = modifier) { imageOrNull ->
        val backgroundImage = (imageOrNull as? Icon.Loaded)?.asImageBitmap()
        if (backgroundImage != null) {
            if (qsGlass) {
                val pixelDensity = LocalDensity.current
                val lens = remember(backgroundImage) { RefractionShader() }
                var bounds by remember(backgroundImage) { mutableStateOf(IntSize.Zero) }
                val effect = remember(backgroundImage, bounds, pixelDensity, glassSettings) {
                    lens.artworkEffect(
                        w = bounds.width,
                        h = bounds.height,
                        density = pixelDensity.density,
                        radius = with(pixelDensity) { 32.dp.toPx() },
                        blurRadius = with(pixelDensity) {
                            (PetalConfig.QS_MEDIA_ART_BLUR_MAX_DP * blurScale).dp.toPx()
                        },
                        strengthScale = glassSettings.refraction,
                    )?.asComposeRenderEffect()
                }
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = backgroundImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                            .onSizeChanged { bounds = it }
                            .graphicsLayer { renderEffect = effect },
                    )
                    Box(
                        Modifier.matchParentSize().drawWithCache {
                            val glass = LiquidGlassDrawable().apply {
                                setCornerRadius(32.dp.toPx())
                                setTintColor(PetalQsSkin.frostTint(petalDark).copy(alpha = .76f).toArgb())
                                setRimWidth(1.dp.toPx())
                                setRim(
                                    if (petalDark) 0x30FFFFFF else 0x66FFFFFF,
                                    if (petalDark) 0x0AFFFFFF else 0x14000000,
                                )
                                setGrainAlpha(if (petalDark) 0x14 else 0x10)
                                setSlab(
                                    lens.bandFor(size.width.toInt(), size.height.toInt(), pixelDensity.density),
                                    glassSettings.refraction,
                                )
                            }
                            // Keep labels out of the lens; only the art gets bent.
                            onDrawBehind {
                                drawIntoCanvas {
                                    glass.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                                    glass.draw(it.nativeCanvas)
                                }
                            }
                        }
                    )
                }
                return@Crossfade
            }
            // Loaded art.
            val gradientBaseColor = colorScheme.background
            Image(
                bitmap = backgroundImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.fillMaxSize()
                        .thenIf(petalSkin && blurScale > 0f) {
                            Modifier.graphicsLayer {
                                val radius =
                                    (PetalConfig.QS_MEDIA_ART_BLUR_MAX_DP * blurScale).dp.toPx()
                                renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                            }
                        }
                        .drawWithContent {
                        // Draw the content (loaded art).
                        drawContent()

                        if (image != null) {
                            if (petalSkin) {
                                // charcoal veil: the card reads as petal glass, art only tints it
                                val frost = PetalQsSkin.frostTint(petalDark)
                                drawRect(
                                    brush =
                                        Brush.radialGradient(
                                            0f to frost.copy(alpha = 0.80f),
                                            1f to frost.copy(alpha = 0.92f),
                                            center = size.center,
                                            radius = max(size.width, size.height) / 2,
                                        )
                                )
                            } else {
                                // Then draw the overlay.
                                drawRect(
                                    brush =
                                        Brush.radialGradient(
                                            0f to gradientBaseColor.copy(alpha = 0.65f),
                                            1f to gradientBaseColor.copy(alpha = 0.75f),
                                            center = size.center,
                                            radius = max(size.width, size.height) / 2,
                                        )
                                )
                            }
                        }
                    },
            )
        } else {
            // Placeholder.
            Box(
                Modifier
                    .background(
                        if (petalSkin) PetalQsSkin.frostTint(petalDark) else colorScheme.background
                    )
                    .fillMaxSize()
            )
        }
    }
}

private data class MediaGlassSettings(val blur: Float, val refraction: Float)

@Composable
private fun petalMediaGlassSettings(): MediaGlassSettings {
    val context = LocalContext.current
    fun read() = MediaGlassSettings(
        PetalConfig.getQsMediaArtBlurScale(context),
        PetalConfig.getQsMediaRefractionScale(context),
    )
    var scale by remember(context) {
        mutableStateOf(read())
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = read()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_QS_MEDIA_ART_BLUR), false, observer,
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_QS_MEDIA_REFRACTION), false, observer,
        )
        scale = read()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return scale
}

/** A QS-tile surface: glass when idle, accent + glow when active, matching the tile grid. */
@Composable
private fun petalTileSurface(active: Boolean, dark: Boolean): Modifier {
    val emission = PetalQsSkin.emission()
    val colors =
        PetalQsSkin.tileColors(
            state =
                if (active) android.service.quicksettings.Tile.STATE_ACTIVE
                else android.service.quicksettings.Tile.STATE_INACTIVE,
            handlesSecondaryClick = false,
            iconOnly = true,
            emission = emission,
            dark = dark,
        )
    val shape = RoundedCornerShape(PetalQsSkin.TileCornerRadius)
    val glass =
        with(PetalQsSkin) {
            Modifier.liquidGlass(PetalQsSkin.TileCornerRadius, colors.background, dark, colors.glassFill)
        }
    val glow = with(PetalQsSkin) { Modifier.activeTileGlow(active, emission, dark) }
    return Modifier.clip(shape)
        .then(glass)
        .then(glow)
        .border(1.dp, PetalQsSkin.tileGlassBorderBrush(dark), shape)
}

/** The glyph colour for a tile surface in the given active state. */
@Composable
private fun petalTileGlyph(active: Boolean, dark: Boolean): Color =
    PetalQsSkin.tileColors(
            state =
                if (active) android.service.quicksettings.Tile.STATE_ACTIVE
                else android.service.quicksettings.Tile.STATE_INACTIVE,
            handlesSecondaryClick = false,
            iconOnly = true,
            emission = PetalQsSkin.emission(),
            dark = dark,
        )
        .icon

/** A transport glyph given depth: a soft dark extrusion under a solid cap, no backing plate. */
@Composable
private fun Petal3dIcon(
    painter: Painter,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 30.dp,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.32f),
            modifier = Modifier.size(glyphSize).offset(y = 2.dp),
        )
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(glyphSize),
        )
    }
}

/** Quiet glass circle for the small transport buttons, same fill family as the qs icon tiles. */
private fun Modifier.petalMediaGlassCircle(radius: Dp, dark: Boolean): Modifier {
    return drawWithCache {
        val r = radius.toPx()
        val shape = Path().apply {
            val corner = CornerRadius(r)
            addRoundRect(RoundRect(0f, 0f, size.width, size.height,
                corner, corner, corner, corner))
        }
        onDrawWithContent {
            drawPath(shape, PetalQsSkin.tileGlass(dark))
            // no rim: the transport buttons are plain glass
            drawContent()
        }
    }
}

/**
 * Play/pause fill: an accent disc while playing (same violet family as active tiles), quiet
 * glass when paused. A soft highlight sweep runs across on toggle.
 */
private fun Modifier.petalMediaPlayFill(
    playing: Boolean,
    accent: Color,
    dark: Boolean,
    sweep: Float = -1f,
): Modifier {
    return drawWithCache {
        val r = size.minDimension / 2f
        val shape = Path().apply {
            val corner = CornerRadius(r)
            addRoundRect(RoundRect(0f, 0f, size.width, size.height,
                corner, corner, corner, corner))
        }
        onDrawWithContent {
            if (playing) {
                drawPath(shape, accent)
                drawPath(shape, Color.White.copy(alpha = 0.30f),
                    style = Stroke(width = 1.dp.toPx()))
            } else {
                drawPath(shape, PetalQsSkin.tileGlass(dark))
                drawPath(
                    shape,
                    if (dark) Color.White.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.10f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawContent()
            if (sweep in 0f..1f) {
                val x = (size.width + size.height) * sweep - size.height
                clipPath(shape) {
                    drawRect(Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                        start = Offset(x, 0f), end = Offset(x + size.height, 0f),
                    ))
                }
            }
        }
    }
}

// Host chooses seeking, transport buttons, or both.
@Composable
private fun ContentScope.Navigation(
    viewModel: MediaNavigationViewModel,
    isSeekBarVisible: Boolean,
    areActionsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val hostView = LocalView.current
    when (viewModel) {
        is MediaNavigationViewModel.Showing -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier,
            ) {
                if (areActionsVisible) {
                    SecondaryAction(
                        viewModel = viewModel.left,
                        modifier = Modifier.sysuiResTag(MediaRes.PREV_BTN),
                        element = Media.Elements.PrevButton,
                    )
                }

                val interactionSource = remember { MutableInteractionSource() }
                val petalSkin = PetalQsSkin.isEnabled(LocalContext.current) ||
                    LocalPetalMediaStyle.current != PetalMediaStyle.Stock
                val petalDark = PetalQsSkin.dark()
                // petal: the seek bar borrows the qs accent so playback ties into the tile glow
                val seekActive =
                    if (petalSkin) {
                        Color(LocalContext.current.getColor(android.R.color.system_accent1_500))
                    } else {
                        Color.White
                    }
                val colors =
                    colors(
                        activeTrackColor = seekActive,
                        inactiveTrackColor = Color.White.copy(alpha = if (petalSkin) 0.22f else 0.3f),
                        thumbColor = Color.White,
                    )
                if (isSeekBarVisible) {
                    // To allow the seek bar slider to fade in and out, it's tagged as an element.
                    Element(key = Media.Elements.SeekBarSlider, modifier = Modifier.weight(1f)) {
                        val sliderDragDelta = remember {
                            // No recomposition for a delta only the callback needs.
                            object {
                                var value = Offset.Zero
                            }
                        }
                        Slider(
                            interactionSource = interactionSource,
                            value = viewModel.progress,
                            onValueChange = { progress -> viewModel.onScrubChange(progress) },
                            onValueChangeFinished = {
                                viewModel.onScrubFinished(sliderDragDelta.value)
                            },
                            colors = colors,
                            thumb = {
                                SeekBarThumb(interactionSource = interactionSource, colors = colors)
                            },
                            track = { sliderState ->
                                SeekBarTrack(
                                    sliderState = sliderState,
                                    // petal: straight bar, the squiggle fights the glass language
                                    isSquiggly = viewModel.isSquiggly && !petalSkin,
                                    colors = colors,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clearAndSetSemantics {
                                        contentDescription = viewModel.contentDescription
                                    }
                                    .pointerInput(hostView) {
                                        try {
                                            awaitPointerEventScope {
                                                var down: PointerInputChange? = null
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    when (event.type) {
                                                        PointerEventType.Press -> {
                                                            hostView.parent?.requestDisallowInterceptTouchEvent(true)
                                                            sliderDragDelta.value = Offset.Zero
                                                            down = event.changes.last()
                                                        }
                                                        PointerEventType.Move -> {
                                                            val change = event.changes.last()
                                                            val start = down
                                                            if (start != null && change.id == start.id) {
                                                                sliderDragDelta.value = change.position - start.position
                                                            }
                                                        }
                                                        PointerEventType.Release -> {
                                                            if (event.changes.none { it.pressed }) {
                                                                hostView.parent?.requestDisallowInterceptTouchEvent(false)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } finally {
                                            hostView.parent?.requestDisallowInterceptTouchEvent(false)
                                        }
                                    },
                        )
                    }
                }

                if (areActionsVisible) {
                    SecondaryAction(
                        viewModel = viewModel.right,
                        modifier = Modifier.sysuiResTag(MediaRes.NEXT_BTN),
                        element = Media.Elements.NextButton,
                    )
                }
            }
        }

        is MediaNavigationViewModel.Hidden -> Unit
    }
}

/** Renders the thumb of the seek bar. */
@Composable
private fun SeekBarThumb(
    interactionSource: MutableInteractionSource,
    colors: SliderColors,
    modifier: Modifier = Modifier,
) {
    if (LocalPetalMediaStyle.current != PetalMediaStyle.Stock) {
        Spacer(modifier.size(12.dp).background(colors.thumbColor, CircleShape))
        return
    }
    val interactions = remember { mutableStateListOf<Interaction>() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> interactions.add(interaction)
                is PressInteraction.Release -> interactions.remove(interaction.press)
                is PressInteraction.Cancel -> interactions.remove(interaction.press)
                is DragInteraction.Start -> interactions.add(interaction)
                is DragInteraction.Stop -> interactions.remove(interaction.start)
                is DragInteraction.Cancel -> interactions.remove(interaction.start)
            }
        }
    }

    Spacer(
        modifier
            .size(width = 4.dp, height = 16.dp)
            .hoverable(interactionSource = interactionSource)
            .background(color = colors.thumbColor, shape = RoundedCornerShape(16.dp))
    )
}

/**
 * Renders the track of the seek bar.
 *
 * If [isSquiggly] is `true`, the part to the left of the thumb will animate a squiggly line that
 * oscillates up and down. The [waveLength] and [amplitude] control the geometry of the squiggle and
 * the [waveSpeedDpPerSec] controls the speed by which it seems to "move" horizontally.
 */
@Composable
private fun SeekBarTrack(
    sliderState: SliderState,
    isSquiggly: Boolean,
    colors: SliderColors,
    modifier: Modifier = Modifier,
    waveLength: Dp = 20.dp,
    amplitude: Dp = 3.dp,
    waveSpeedDpPerSec: Dp = 8.dp,
) {
    if (LocalPetalMediaStyle.current != PetalMediaStyle.Stock) {
        Canvas(modifier.height(6.dp)) {
            drawLine(colors.inactiveTrackColor, Offset(0f, center.y), Offset(size.width, center.y),
                strokeWidth = size.height, cap = StrokeCap.Round)
            drawLine(colors.activeTrackColor, Offset(0f, center.y),
                Offset(size.width * sliderState.value.coerceIn(0f, 1f), center.y),
                strokeWidth = size.height, cap = StrokeCap.Round)
        }
        return
    }
    // Animating the amplitude allows the squiggle to gradually grow to its full height or shrink
    // back to a flat line as needed.
    val animatedAmplitude by
        animateDpAsState(
            targetValue = if (isSquiggly) amplitude else 0.dp,
            label = "SeekBarTrack.amplitude",
        )

    // This animates the horizontal movement of the squiggle.
    val animatedWaveOffset = remember { Animatable(0f) }

    LaunchedEffect(isSquiggly) {
        if (isSquiggly) {
            animatedWaveOffset.snapTo(0f)
            animatedWaveOffset.animateTo(
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation =
                            tween(
                                durationMillis = (1000 * (waveLength / waveSpeedDpPerSec)).toInt(),
                                easing = LinearEasing,
                            ),
                        repeatMode = RepeatMode.Restart,
                    ),
            )
        }
    }

    // Render the track.
    Canvas(modifier = modifier) {
        val thumbPositionPx = size.width * sliderState.value

        // The squiggly part before the thumb.
        if (thumbPositionPx > 0) {
            val amplitudePx = amplitude.toPx()
            val animatedAmplitudePx = animatedAmplitude.toPx()
            val waveLengthPx = waveLength.toPx()

            val path =
                Path().apply {
                    val halfWaveLengthPx = waveLengthPx / 2
                    val halfWaveCount = (thumbPositionPx / halfWaveLengthPx).toInt()

                    repeat(halfWaveCount + 3) { index ->
                        // Draw a half wave (either a hill or a valley shape starting and ending on
                        // the horizontal center).
                        relativeQuadraticTo(
                            // The control point for the bezier curve is on top of the peak of the
                            // hill or the very center bottom of the valley shape.
                            dx1 = halfWaveLengthPx / 2,
                            dy1 = if (index % 2 == 0) -animatedAmplitudePx else animatedAmplitudePx,
                            // Advance horizontally, half a wave length at a time.
                            dx2 = halfWaveLengthPx,
                            dy2 = 0f,
                        )
                    }
                }

            // Now that the squiggle is rendered a bit past the thumb, clip off the part that passed
            // the thumb. It's easier to clip the extra squiggle than to figure out the bezier curve
            // for part of a hill/valley.
            clipRect(
                left = 0f,
                top = -amplitudePx,
                right = thumbPositionPx,
                bottom = amplitudePx * 2,
            ) {
                translate(left = -waveLengthPx * animatedWaveOffset.value, top = 0f) {
                    // Actually render the squiggle.
                    drawPath(
                        path = path,
                        color = colors.activeTrackColor,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        // The flat line after the thumb.
        drawLine(
            color = colors.inactiveTrackColor,
            start = Offset(thumbPositionPx, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Renders the internal "guts" of a card. */
@Composable
private fun CardGuts(
    viewModel: MediaCardGutsViewModel,
    colorScheme: AnimatedColorScheme,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.pointerInput(Unit) { detectLongPressGesture { viewModel.onLongClick() } }
    ) {
        // Settings button.
        Icon(
            icon = checkNotNull(viewModel.settingsButton.icon),
            modifier =
                Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp).clickable {
                    viewModel.settingsButton.onClick()
                },
            tint = Color.White,
        )

        //  Content.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier.align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 32.dp, top = 16.dp),
        ) {
            Text(
                text = viewModel.text,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 14.sp,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformButton(
                    onClick = viewModel.primaryAction.onClick,
                    modifier = Modifier.sysuiResTag(MediaRes.HIDE_BTN),
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                ) {
                    Text(
                        text = checkNotNull(viewModel.primaryAction.text),
                        color = colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 14.sp,
                    )
                }

                viewModel.secondaryAction?.let { button ->
                    PlatformOutlinedButton(
                        onClick = button.onClick,
                        border = BorderStroke(width = 1.dp, color = colorScheme.primary),
                    ) {
                        Text(
                            text = checkNotNull(button.text),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Renders the metadata labels of a track. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentScope.Metadata(
    title: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (LocalPetalNarrowMedia.current) {
        // this small card needs its own bounds, not the compact scene's old offset
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = color, fontSize = 16.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sysuiResTag(MediaRes.TITLE))
            Text(text = subtitle, color = color.copy(alpha = .78f), fontSize = 12.sp,
                lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sysuiResTag(MediaRes.ARTIST))
        }
        return
    }
    // This element can be animated when switching between scenes inside a media card.
    Element(key = Media.Elements.Metadata, modifier = modifier) {
        // When the title and/or subtitle change, crossfade between the old and the new.
        Crossfade(targetState = title to subtitle, label = "Labels.crossfade") { (title, subtitle)
            ->
            Column {
                Text(
                    text = title,
                    modifier = Modifier.sysuiResTag(MediaRes.TITLE),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = color,
                    maxLines = if (LocalPetalNarrowMedia.current) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = subtitle,
                    modifier = Modifier.sysuiResTag(MediaRes.ARTIST),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DeviceChip(
    viewModel: MediaDeviceChipViewModel,
    style: DeviceChipStyle,
    modifier: Modifier = Modifier,
    topPadding: Dp = 16.dp,
) {
    // Tiny-looking chip, still a 48dp target. Ripple stays inside the glass.
    val petalSkin = PetalQsSkin.isEnabled(LocalContext.current)
    val petalDark = PetalQsSkin.dark()
    val chipFill = if (petalSkin) {
        if (petalDark) Color(0xCC292B32) else Color(0xCCE7E9F0)
    } else style.fillColor
    val chipContent = if (petalSkin) {
        if (petalDark) Color(0xFFF5F5F8) else Color(0xFF202127)
    } else style.contentColor
    val clickInteractionSource = remember { MutableInteractionSource() }
    Expandable(
        controller =
            rememberExpandableController(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            ),
        modifier = modifier.padding(top = topPadding, bottom = 0.dp),
        useModifierBasedImplementation = true,
    ) {
        Box(
            modifier =
                Modifier.heightIn(min = 48.dp).clickable(
                    interactionSource = clickInteractionSource,
                    indication = null,
                ) {
                    viewModel.onClick(it)
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(chipFill)
                        .thenIf(style.borderColor != null) {
                            Modifier.border(
                                width = 1.dp,
                                color = style.borderColor!!,
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                        .indication(clickInteractionSource, ripple())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (viewModel.isConnecting) {
                    CircularProgressIndicator(
                        color = chipContent,
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.dp,
                    )
                } else {
                    Icon(
                        icon = viewModel.icon,
                        tint = chipContent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                AnimatedVisibility(visible = viewModel.text != null) {
                    rememberLastNonNull(viewModel.text)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = chipContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Renders the primary action of media controls: the play/pause button. */
@Composable
private fun ContentScope.PlayPauseAction(
    viewModel: MediaPlayPauseActionViewModel?,
    buttonColor: Color,
    iconColor: Color,
    buttonCornerRadius: (isPlaying: Boolean) -> Dp,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null) return

    val petalSkin = PetalQsSkin.isEnabled(LocalContext.current)
    val concept = LocalPetalMediaStyle.current != PetalMediaStyle.Stock
    val buttonSize =
        if (concept) {
            val side = if (LocalPetalMediaStyle.current == PetalMediaStyle.Mini) 48.dp else 60.dp
            DpSize(side, side)
        } else if (petalSkin) DpSize(width = 56.dp, height = 56.dp)
        else DpSize(width = 72.dp, height = 48.dp)
    val petalDark = PetalQsSkin.dark()
    val petalPlaying = viewModel.state != MediaSessionState.Paused
    // match the qs icon tiles: glass when idle, accent + glow when playing
    val petalIconColor = if (petalSkin && !concept) petalTileGlyph(petalPlaying, petalDark) else iconColor
    val petalSurface = if (petalSkin && !concept) petalTileSurface(petalPlaying, petalDark) else Modifier
    val cornerRadius: Dp by
        animateDpAsState(
            targetValue = if (concept) 30.dp else if (petalSkin) 24.dp
                else buttonCornerRadius(viewModel.state != MediaSessionState.Paused),
            label = "PlayPauseAction.cornerRadius",
        )
    // This element can be animated when switching between scenes inside a media card.
    Element(key = Media.Elements.PlayPauseButton, modifier = modifier) {
        PlatformButton(
            onClick = viewModel.onClick ?: {},
            enabled = viewModel.onClick != null,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (petalSkin && !concept) Color.Transparent else buttonColor),
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier.size(buttonSize).then(petalSurface),
        ) {
            when (viewModel.state) {
                is MediaSessionState.Playing,
                is MediaSessionState.Paused -> {
                    val painterOrNull =
                        when (viewModel.icon) {
                            // TODO(b/399860531): load this expensive-to-load animated vector
                            //  drawable off the main thread.
                            is Icon.Resource ->
                                rememberAnimatedVectorPainter(
                                    animatedImageVector =
                                        AnimatedImageVector.animatedVectorResource(
                                            id = viewModel.icon.resId
                                        ),
                                    atEnd = viewModel.state == MediaSessionState.Playing,
                                )
                            is Icon.Loaded -> rememberDrawablePainter(viewModel.icon.drawable)
                            null -> null
                        }
                    painterOrNull?.let { painter ->
                        Icon(
                            painter = painter,
                            contentDescription = viewModel.icon?.contentDescription?.load(),
                            tint = petalIconColor,
                            modifier = Modifier.size(24.dp).sysuiResTag(MediaRes.PLAY_PAUSE_BTN),
                        )
                    }
                }
                is MediaSessionState.Buffering -> {
                    CircularProgressIndicator(color = petalIconColor, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * Renders an icon button for an action that's not the play/pause action.
 *
 * If [element] is provided, the secondary action element will be able to animate when switching
 * between scenes inside a media card.
 */
@Composable
private fun ContentScope.SecondaryAction(
    viewModel: MediaSecondaryActionViewModel,
    modifier: Modifier = Modifier,
    resId: String = MediaRes.EMPTY_STRING,
    element: ElementKey? = null,
    iconColor: Color = Color.White,
) {
    if (viewModel !is MediaSecondaryActionViewModel.None && element != null) {
        Element(key = element, modifier = modifier) {
            SecondaryActionContent(viewModel = viewModel, iconColor = iconColor, resId = resId)
        }
    } else {
        SecondaryActionContent(
            viewModel = viewModel,
            iconColor = iconColor,
            resId = resId,
            modifier = modifier,
        )
    }
}

/** The content of a [SecondaryAction]. */
@Composable
private fun SecondaryActionContent(
    viewModel: MediaSecondaryActionViewModel,
    iconColor: Color,
    resId: String,
    modifier: Modifier = Modifier,
) {
    val petalSkin = PetalQsSkin.isEnabled(LocalContext.current)
    val petalDark = PetalQsSkin.dark()

    // petal: just the glyph, given depth, with no backing plate
    if (petalSkin) {
        when (viewModel) {
            is MediaSecondaryActionViewModel.Action -> {
                val icon = viewModel.icon
                if (icon != null) {
                    val painter =
                        when (icon) {
                            is Icon.Resource -> painterResource(icon.resId)
                            is Icon.Loaded -> rememberDrawablePainter(icon.drawable)
                        }
                    Box(
                        modifier =
                            modifier
                                .size(48.dp)
                                .clickable(
                                    enabled = viewModel.onClick != null,
                                    onClick = { viewModel.onClick?.invoke() },
                                )
                                .sysuiResTag(resId),
                        contentAlignment = Alignment.Center,
                    ) {
                        Petal3dIcon(
                            painter = painter,
                            contentDescription = icon.contentDescription?.load(),
                            tint = PetalQsSkin.glyphOn(petalDark),
                            glyphSize = 32.dp,
                        )
                    }
                }
            }
            is MediaSecondaryActionViewModel.ReserveSpace ->
                Spacer(modifier = modifier.size(48.dp))
            is MediaSecondaryActionViewModel.None -> Unit
        }
        return
    }

    val sharedModifier = modifier.size(48.dp).padding(13.dp)
    val colors = IconButtonDefaults.iconButtonColors(contentColor = iconColor)
    when (viewModel) {
        is MediaSecondaryActionViewModel.Action ->
            when (viewModel.icon) {
                is Icon.Resource ->
                    PlatformIconButton(
                        onClick = viewModel.onClick ?: {},
                        modifier = sharedModifier.sysuiResTag(resId),
                        enabled = viewModel.onClick != null,
                        colors = colors,
                        iconResource = viewModel.icon.resId,
                        contentDescription = viewModel.icon.contentDescription?.load(),
                    )

                is Icon.Loaded ->
                    PlatformIconButton(
                        onClick = viewModel.onClick ?: {},
                        modifier = sharedModifier.sysuiResTag(resId),
                        enabled = viewModel.onClick != null,
                        colors = colors,
                        iconDrawable = viewModel.icon.drawable,
                        contentDescription = viewModel.icon.contentDescription?.load(),
                    )
            }

        is MediaSecondaryActionViewModel.ReserveSpace -> Spacer(modifier = sharedModifier)

        is MediaSecondaryActionViewModel.None -> Unit
    }
}

/**
 * Renders the revealed content on the sides of the horizontal pager.
 *
 * @param revealAmount A callback that can return the amount of revealing done. This value will be
 *   in a range slightly larger than `-1` to `+1` where `1` is fully revealed on the left-hand side,
 *   `-1` is fully revealed on the right-hand side, and `0` is not revealed at all. Numbers lower
 *   than `-1` or greater than `1` are possible when the overscroll effect adds additional pixels of
 *   offset.
 */
@Composable
private fun RevealedContent(
    viewModel: MediaSettingsButtonViewModel,
    revealAmount: () -> Float,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding = 18.dp

    // This custom layout's purpose is only to place the icon in the center of the revealed content,
    // taking into account the amount of reveal.
    Layout(
        content = {
            Icon(
                icon = viewModel.icon,
                modifier =
                    Modifier.layoutId(Media.LayoutId.CardRevealedContent)
                        .size(48.dp)
                        .padding(12.dp)
                        .graphicsLayer {
                            alpha = abs(revealAmount()).fastCoerceIn(0f, 1f)
                            rotationZ = revealAmount() * 90
                        }
                        .clickable { viewModel.onClick() },
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeable =
            measurables.singleton(Media.LayoutId.CardRevealedContent).measure(constraints)

        val totalWidth =
            min(horizontalPadding.roundToPx() * 2 + placeable.measuredWidth, constraints.maxWidth)

        layout(totalWidth, constraints.maxHeight) {
            coordinates?.size?.let { size ->
                val reveal = revealAmount()
                val x =
                    if (reveal >= 0f) {
                        ((size.width * abs(reveal)) - placeable.measuredWidth) / 2
                    } else {
                        size.width * (1 - abs(reveal) / 2) - placeable.measuredWidth / 2
                    }

                placeable.place(
                    x = x.fastRoundToInt(),
                    y = (size.height - placeable.measuredHeight) / 2,
                )
            }
        }
    }
}

/** Enumerates all supported media presentation styles. */
enum class MediaPresentationStyle {
    /** The "normal" 3-row carousel look. */
    Default,
    /** Similar to [Default] but with full height. Used in communal hub. */
    Large,
    /** Similar to [Default] but not as tall (2-row carousel look). */
    Compressed,
    /** A special single-row treatment that fits nicely in quick settings. */
    Compact,
}

data class MediaUiBehavior(
    val isCarouselDismissible: Boolean = true,
    val isCarouselScrollingEnabled: Boolean = true,
    val carouselVisibility: MediaCarouselVisibility = MediaCarouselVisibility.WhenNotEmpty,
    /**
     * If provided, this callback will be consulted at the beginning of each carousel scroll gesture
     * to see if the falsing system thinks that it's a false touch. If it then returns `true`, the
     * scroll will be canceled.
     */
    val isCarouselScrollFalseTouch: (() -> Boolean)? = null,
)

@Stable
private interface AnimatedColorScheme {
    val primary: Color
    val onPrimary: Color
    val background: Color
}

private object MediaRes {
    const val EMPTY_STRING = ""
    const val BACKGROUND = "album_art"
    const val PLAY_PAUSE_BTN = "actionPlayPause"
    const val NEXT_BTN = "actionNext"
    const val PREV_BTN = "actionPrev"
    const val MEDIA_CAROUSEL_SCROLLER = "media_carousel_scroller"
    const val MEDIA_CAROUSEL = "media_carousel"
    const val MEDIA_CONTROLS = "qs_media_controls"
    const val OUTPUT_CHIP = "media_seamless"
    const val SUGGESTED_DEVICE_CHIP = "device_suggestion_button"
    const val TITLE = "header_title"
    const val ARTIST = "header_artist"
    const val HIDE_BTN = "dismiss"
}

object Media {

    /**
     * Scenes.
     *
     * The implementation uses a [SceneTransitionLayout] to smoothly animate transitions between
     * different card layouts. Each card layout is identified as its own "scene" and the STL
     * framework takes care of animating the layouts and their elements as the card morphs between
     * scenes.
     */
    object Scenes {
        /** The "normal" 3-row carousel look. */
        val Default = SceneKey("default")
        /** Similar to [Default] but with full height. Used in communal hub. */
        val Large = SceneKey("large")
        /** Similar to [Default] but not as tall (2-row carousel look). */
        val Compressed = SceneKey("compressed")
        /** A special single-row treatment that fits nicely in quick settings. */
        val Compact = SceneKey("compact")
    }

    /** Definitions of how scene changes are transition-animated. */
    val Transitions = transitions {
        from(Scenes.Default, to = Scenes.Compact) {}
        from(Scenes.Default, to = Scenes.Large) {}
        from(Scenes.Default, to = Scenes.Compressed) { fade(Elements.SeekBarSlider) }
        from(Scenes.Compact, to = Scenes.Compressed) { fade(Elements.SeekBarSlider) }
    }

    /**
     * Element keys.
     *
     * Composables that are wrapped in [ContentScope.Element] with one of these as their `key`
     * parameter will automatically be picked up by the STL transition animation framework and will
     * be animated from their bounds in the original scene to their bounds in the destination scene.
     *
     * In addition, tagging such elements with a key allows developers to customize the transition
     * animations even further.
     */
    object Elements {
        val Artwork = ElementKey("petal_artwork")
        val PlayPauseButton = ElementKey("play_pause")
        val Metadata = ElementKey("metadata")
        val PrevButton = ElementKey("prev")
        val NextButton = ElementKey("next")
        val SeekBarSlider = ElementKey("seek_bar_slider")
        val OutputSwitcherButton = ElementKey("output_switcher")
        val mediaCarousel = ElementKey("media_carousel")

        fun additionalActionButton(index: Int): ElementKey {
            val name = "additional_action_$index"
            return ElementKey(debugName = name, identity = name)
        }
    }

    enum class LayoutId {
        CardForeground,
        CardGuts,
        CardRevealedContent,
    }
}

private fun MediaPresentationStyle.toScene(): SceneKey {
    return when (this) {
        MediaPresentationStyle.Default -> Media.Scenes.Default
        MediaPresentationStyle.Large -> Media.Scenes.Large
        MediaPresentationStyle.Compressed -> Media.Scenes.Compressed
        MediaPresentationStyle.Compact -> Media.Scenes.Compact
    }
}

/** Allows to set the maxWidth constraint as a fractional value. */
private fun Modifier.fractionalMaxWidth(containerMaxWidth: Int, fraction: Float): Modifier {
    return layout { measurable, constraints ->
        val placeable =
            measurable.measure(
                constraints.copy(
                    maxWidth =
                        min((containerMaxWidth * fraction).fastRoundToInt(), constraints.maxWidth)
                )
            )

        layout(placeable.measuredWidth, placeable.measuredHeight) { placeable.place(0, 0) }
    }
}

@Composable
fun <T> rememberLastNonNull(value: T?): T? {
    val ref = remember { Ref<T?>() }
    ref.value = value ?: ref.value
    return ref.value
}

private data class DeviceChipStyle(
    val fillColor: Color,
    val contentColor: Color,
    val borderColor: Color? = null,
)
