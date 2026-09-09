/*
 * Copyright (C) 2024 The Android Open Source Project
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

@file:OptIn(ExperimentalFoundationApi::class)

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import android.content.Context
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.provider.Settings
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.trace
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.animation.Expandable
import com.android.compose.animation.bounceable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.mechanics.compose.modifier.verticalFadeContentReveal
import com.android.mechanics.compose.modifier.verticalTactileSurfaceReveal
import com.android.systemui.Flags
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModel
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModelFactoryProvider
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.petalos.PetalQsSkin
import com.android.systemui.petalos.PetalQsSkin.activeTileGlow
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.ui.compose.BounceableInfo
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelMoreDetails
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import org.petalos.config.PetalConfig

@Composable
fun TileLazyGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        state = state,
        columns = columns,
        verticalArrangement = spacedBy(CommonTileDefaults.TileArrangementPadding),
        horizontalArrangement = spacedBy(CommonTileDefaults.TileArrangementPadding),
        contentPadding = contentPadding,
        modifier = modifier,
        content = content,
    )
}

private val TileViewModel.traceName
    get() = spec.toString().takeLast(Trace.MAX_SECTION_NAME_LEN)

// This composable function is responsible for rendering a tile based on the provided
@Composable
fun ContentScope.Tile(
    tile: TileViewModel,
    iconOnly: Boolean,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    bounceableInfo: BounceableInfo?,
    tileHapticsViewModelFactoryProvider: TileHapticsViewModelFactoryProvider,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    requestToggleTextFeedback: (TileSpec) -> Unit = {},
    detailsViewModel: DetailsViewModel?,
    enableRevealEffect: Boolean = false,
    petalExpandToPill: Boolean = false,
) {
    trace(tile.traceName) {
        val currentBounceableInfo by rememberUpdatedState(bounceableInfo)
        val resources = resources()

        // Use produce state because [QSTile.State] doesn't have well defined equals (due to
        val uiState by
            produceState(tile.currentState.toUiState(resources), tile, resources) {
                tile.state.collect { value = it.toUiState(resources) }
            }
        val isClickable = uiState.state != STATE_UNAVAILABLE

        val icon by
            produceState(tile.currentState.toIconProvider(), tile) {
                tile.state.collect { value = it.toIconProvider() }
            }

        // petalOS: observe the skin toggle so flipping it in petalOS Hub immediately restyles
        val skinEnabled = petalSkinEnabled()
        val fallbackInteractionSource = remember(tile.spec) { MutableInteractionSource() }
        val resolvedInteractionSource = interactionSource ?: fallbackInteractionSource
        val isPressed by resolvedInteractionSource.collectIsPressedAsState()
        val colors =
            TileDefaults.getColorForState(uiState, iconOnly && !petalExpandToPill, skinEnabled)
        val hapticsViewModel: TileHapticsViewModel? =
            rememberViewModel(traceName = "TileHapticsViewModel") {
                tileHapticsViewModelFactoryProvider.getHapticsViewModelFactory()?.create(tile)
            }

        // Petal compact controls draw a rounded-square surface and a caption beneath it.
        val petalIconTile = iconOnly && skinEnabled

        // TODO(b/361789146): Draw the shapes instead of clipping
        val isDualTarget = uiState.handlesSecondaryClick
        val tileShape by
            TileDefaults.animateTileShapeAsState(
                uiState.state,
                iconOnly = iconOnly,
                skinEnabled = skinEnabled,
                handlesSecondaryClick = isDualTarget,
            )
        val animatedColor by animateColorAsState(colors.background, label = "QSTileBackgroundColor")

        val surfaceRevealModifier: Modifier
        val contentRevealModifier: Modifier
        if (enableRevealEffect) {
            val marginBottom =
                with(LocalDensity.current) { QuickSettingsShade.Dimensions.Padding.toPx() }
            surfaceRevealModifier =
                Modifier.verticalTactileSurfaceReveal(deltaY = marginBottom, label = tile.traceName)
            contentRevealModifier =
                Modifier.verticalFadeContentReveal(deltaY = marginBottom, label = tile.traceName)
        } else {
            surfaceRevealModifier = Modifier
            contentRevealModifier = Modifier
        }

        // The shade owns entry/exit motion. Listening changes at the QQS/QS handoff are
        val pressScale by
            animateFloatAsState(
                targetValue = if (skinEnabled && isPressed) PetalQsSkin.PulsePressedScale else 1f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 900f),
                label = "PetalTilePressPulse",
            )
        val petalPulseModifier =
            if (skinEnabled) {
                Modifier.graphicsLayer {
                    val combinedScale = pressScale
                    scaleX = combinedScale
                    scaleY = combinedScale
                }
            } else {
                Modifier
            }

        TileExpandable(
            // petalOS: for compact tiles the card is drawn by the content itself, so the
            color = { if (petalIconTile) Color.Transparent else animatedColor },
            shape = tileShape,
            squishiness = squishiness,
            hapticsViewModel = hapticsViewModel,
            clipToShape = !petalIconTile,
            // petalOS: thin light outline rim on the large glass cards (concept sheen edge).
            borderColor = if (skinEnabled) PetalQsSkin.TileGlassBorder else Color.Transparent,
            borderWidth = if (skinEnabled) 1.dp else 0.dp,
            modifier =
                modifier
                    .then(petalPulseModifier)
                    .then(surfaceRevealModifier)
                    // petalOS: evaluated inline (not via a plain Modifier factory lambda) so the
                    .then(
                        if (!petalIconTile) {
                            Modifier.borderOnFocus(
                                color = MaterialTheme.colorScheme.secondary,
                                cornerSize = tileShape.topEnd,
                            )
                        } else {
                            Modifier
                        }
                    )
                    .fillMaxWidth()
                    .thenIf(currentBounceableInfo != null) {
                        Modifier.bounceable(
                            currentBounceableInfo!!.bounceable,
                            currentBounceableInfo!!.previousTile,
                            currentBounceableInfo!!.nextTile,
                            orientation = Orientation.Horizontal,
                            bounceEnd = currentBounceableInfo!!.bounceEnd,
                        )
                    },
        ) { expandable ->
            // Use main click on long press for small, available dual target tiles.
            val useLongClickToSettings = !(iconOnly && isDualTarget && isClickable)
            val longClick: (() -> Unit)? =
                {
                        hapticsViewModel?.setTileInteractionState(
                            TileHapticsViewModel.TileInteractionState.LONG_CLICKED
                        )

                        if (useLongClickToSettings) {
                            tile.settingsClick(expandable)
                        } else {
                            tile.mainClick(expandable)
                        }
                    }
                    .takeIf { !useLongClickToSettings || uiState.handlesLongClick }

            // Bounce the tile's container if it is toggleable and is not a large
            val bounceContainer = uiState.isToggleable && (iconOnly || !isDualTarget)
            val contentBounceable =
                remember(currentBounceableInfo) {
                    currentBounceableInfo?.bounceable ?: BounceableTileViewModel()
                }
            TileContainer(
                interactionSource =
                    if (skinEnabled) resolvedInteractionSource
                    else interactionSource.takeIf { bounceContainer },
                onClick = onClick@{
                        if (!isClickable) return@onClick

                        val hasDetails =
                            QsDetailedView.isEnabled &&
                                detailsViewModel?.onTileClicked(tile.spec) == true
                        if (hasDetails) return@onClick

                        // For those tile's who doesn't have a detailed view, process with
                        if (iconOnly && isDualTarget) {
                            tile.toggleClick()
                        } else {
                            tile.mainClick(expandable)
                        }

                        // Side effects of the click
                        hapticsViewModel?.setTileInteractionState(
                            TileHapticsViewModel.TileInteractionState.CLICKED
                        )

                        if (!skinEnabled)
                            coroutineScope.launch {
                                // Bounce the tile's container if it is toggleable and is not a
                                if (bounceContainer) {
                                    // Only bounce the container ourselves if a BounceableInfo was
                                    currentBounceableInfo?.bounceable?.animateContainerBounce()
                                } else {
                                    contentBounceable.animateContentBounce(iconOnly)
                                }
                            }
                        if (uiState.isToggleable && iconOnly) {
                            // And show footer text feedback for icons
                            requestToggleTextFeedback(tile.spec)
                        }
                    },
                onLongClick = longClick,
                accessibilityUiState = uiState.accessibilityUiState,
                iconOnly = iconOnly,
                isDualTarget = isDualTarget,
                modifier = contentRevealModifier.activeTileGlow(
                    skinEnabled && !petalIconTile && uiState.state == STATE_ACTIVE
                ),
                petalExpanded = petalExpandToPill,
            ) {
                val iconProvider: Context.() -> Icon = { getTileIcon(icon = icon) }
                if (petalIconTile) {
                    // petalOS: compact controls show captions; expanded cards show connection
                    PetalIconTileContent(
                        iconProvider = iconProvider,
                        colors = colors,
                        label = uiState.label,
                        secondaryLabel = uiState.secondaryLabel,
                        expandToPill = petalExpandToPill,
                        active = uiState.state == STATE_ACTIVE,
                        accessibilityUiState = uiState.accessibilityUiState,
                        modifier =
                            Modifier.align(Alignment.Center).bounceScale {
                                contentBounceable.iconBounceScale
                            },
                    )
                } else if (iconOnly) {
                    SmallTileContent(
                        iconProvider = iconProvider,
                        color = colors.icon,
                        modifier =
                            Modifier.align(Alignment.Center).bounceScale {
                                contentBounceable.iconBounceScale
                            },
                    )
                } else {
                    val iconShape by
                        TileDefaults.animateIconShapeAsState(
                            uiState.state,
                            skinEnabled = skinEnabled,
                        )
                    val secondaryClick: (() -> Unit)? =
                        {
                                hapticsViewModel?.setTileInteractionState(
                                    TileHapticsViewModel.TileInteractionState.CLICKED
                                )
                                tile.toggleClick()
                            }
                            .takeIf { isDualTarget }
                    if (skinEnabled) {
                        PetalConnectivityTileContent(
                            iconProvider = iconProvider,
                            colors = colors,
                            label = uiState.label,
                            secondaryLabel = uiState.secondaryLabel,
                            toggleClick = secondaryClick.takeIf { isClickable },
                            onLongClick = longClick,
                            accessibilityUiState = uiState.accessibilityUiState,
                        )
                    } else {
                        LargeTileContent(
                            label = uiState.label,
                            secondaryLabel = uiState.secondaryLabel,
                            iconProvider = iconProvider,
                            sideDrawable = uiState.sideDrawable,
                            colors = colors,
                            iconShape = iconShape,
                            toggleClick = secondaryClick,
                            onLongClick = longClick,
                            accessibilityUiState = uiState.accessibilityUiState,
                            squishiness = squishiness,
                            isVisible = isVisible,
                            textScale = { contentBounceable.textBounceScale },
                            modifier =
                                Modifier.largeTilePadding(isDualTarget = uiState.handlesLongClick),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PetalConnectivityTileContent(
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    label: String,
    secondaryLabel: String?,
    toggleClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
) {
    val focusBorderColor = MaterialTheme.colorScheme.secondary
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(PetalQsSkin.connectivityTileHeight())
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Preserve the stock separate toggle target and its switch semantics.
        Box(
            modifier =
                Modifier.size(48.dp)
                    .clip(PetalQsSkin.iconShape())
                    .background(colors.iconBackground)
                    .thenIf(toggleClick != null) {
                        Modifier.borderOnFocus(focusBorderColor, PetalQsSkin.iconShape().topEnd)
                            .combinedClickable(onClick = toggleClick!!, onLongClick = onLongClick)
                            .semantics {
                                contentDescription = accessibilityUiState.contentDescription
                                stateDescription = accessibilityUiState.stateDescription
                                accessibilityUiState.toggleableState?.let { toggleableState = it }
                                role = Role.Switch
                            }
                    },
            contentAlignment = Alignment.Center,
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { PetalQsSkin.IconTileIconSize },
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = label,
                style = TextStyle(color = colors.label, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, lineHeight = 18.sp),
                autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!secondaryLabel.isNullOrEmpty()) {
                BasicText(
                    text = secondaryLabel,
                    style = TextStyle(color = colors.secondaryLabel, fontSize = 12.sp,
                        lineHeight = 16.sp),
                    autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The same card geometry is used on both sides of the QQS/QS scene handoff. */
@Composable
private fun PetalIconTileContent(
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    label: String,
    secondaryLabel: String?,
    expandToPill: Boolean,
    active: Boolean,
    accessibilityUiState: AccessibilityUiState,
    modifier: Modifier = Modifier,
) {
    if (expandToPill) {
        val shape = PetalQsSkin.tileShape(false, true)
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.background)
                    .activeTileGlow(active)
                    .border(1.dp, PetalQsSkin.TileGlassBorderBrush, shape)
        ) {
            // QQS keeps its existing whole-card toggle action. The visual icon target,
            PetalConnectivityTileContent(
                iconProvider = iconProvider,
                colors = colors,
                label = label,
                secondaryLabel = secondaryLabel,
                toggleClick = null,
                onLongClick = null,
                accessibilityUiState = accessibilityUiState,
            )
        }
        return
    }
    val shape = PetalQsSkin.tileShape(true)
    Column(
        modifier = modifier.fillMaxWidth().height(PetalQsSkin.compactTileHeight()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier.size(PetalQsSkin.IconTileSize)
                    .clip(shape)
                    .background(colors.background)
                    .activeTileGlow(active)
                    .border(1.dp, PetalQsSkin.TileGlassBorderBrush, shape),
            contentAlignment = Alignment.Center,
        ) {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { PetalQsSkin.IconTileIconSize },
            )
        }
        BasicText(
            text = label,
            style = TextStyle(color = PetalQsSkin.TileGlyphOnDark, fontSize = 12.sp,
                lineHeight = 16.sp, textAlign = TextAlign.Center),
            autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics {},
        )
    }
}

@Composable
private fun TileExpandable(
    color: () -> Color,
    shape: Shape,
    squishiness: () -> Float,
    hapticsViewModel: TileHapticsViewModel?,
    modifier: Modifier = Modifier,
    clipToShape: Boolean = true,
    // petalOS: optional glass sheen outline applied over the clipped shape edge.
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = 0.dp,
    content: @Composable (Expandable) -> Unit,
) {
    Expandable(
        controller = rememberExpandableController(color = color, shape = shape),
        // petalOS: compact tiles draw their own card + label and must not be clipped
        modifier =
            (if (clipToShape) {
                    modifier.clip(shape).verticalSquish(squishiness)
                } else {
                    modifier
                })
                // petalOS: draw the outline after the clip so it follows the card's rounded rim.
                .then(
                    if (clipToShape && borderWidth > 0.dp) {
                        Modifier.border(borderWidth, PetalQsSkin.TileGlassBorderBrush, shape)
                    } else {
                        Modifier
                    }
                ),
        useModifierBasedImplementation = true,
    ) {
        content(hapticsViewModel?.createStateAwareExpandable(it) ?: it)
    }
}

@Composable
fun TileContainer(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    iconOnly: Boolean,
    isDualTarget: Boolean,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    petalExpanded: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .height(
                    if (com.android.systemui.petalos.PetalQsSkin.isEnabled(LocalContext.current)) {
                        if (iconOnly && !petalExpanded) PetalQsSkin.compactTileHeight()
                        else PetalQsSkin.connectivityTileHeight()
                    } else {
                        TileHeight
                    }
                )
                .fillMaxWidth()
                .tileCombinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                    accessibilityUiState = accessibilityUiState,
                    iconOnly = iconOnly,
                    isDualTarget = isDualTarget,
                    interactionSource = interactionSource,
                )
                .tileTestTag(iconOnly),
        content = content,
    )
}

@Composable
fun LargeStaticTile(
    uiState: TileUiState,
    iconProvider: IconProvider,
    modifier: Modifier = Modifier,
) {
    val skinEnabled = petalSkinEnabled()
    val colors =
        TileDefaults.getColorForState(
            uiState = uiState,
            iconOnly = false,
            skinEnabled = skinEnabled,
        )

    Box(
        modifier
            .clip(
                TileDefaults.animateTileShapeAsState(
                        state = uiState.state,
                        skinEnabled = skinEnabled,
                    )
                    .value
            )
            .background(colors.background)
            .height(TileHeight)
            .largeTilePadding()
    ) {
        LargeTileContent(
            label = uiState.label,
            secondaryLabel = "",
            iconProvider = { getTileIcon(icon = iconProvider) },
            sideDrawable = null,
            colors = colors,
            squishiness = { 1f },
        )
    }
}

private fun Context.getTileIcon(icon: IconProvider): Icon {
    return icon.icon?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(this), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

fun tileHorizontalArrangement(): Arrangement.Horizontal {
    return spacedBy(space = CommonTileDefaults.TileArrangementPadding, alignment = Alignment.Start)
}

@Composable
fun Modifier.tileCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    interactionSource: MutableInteractionSource?,
    iconOnly: Boolean,
    isDualTarget: Boolean,
): Modifier {
    val longPressLabel =
        if (iconOnly && isDualTarget) longPressLabelMoreDetails() else longPressLabelSettings()
    return combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = accessibilityUiState.clickLabel,
            onLongClickLabel = longPressLabel,
            hapticFeedbackEnabled = !Flags.msdlFeedback(),
            interactionSource = interactionSource,
        )
        .semantics {
            val accessibilityRole =
                if (iconOnly && isDualTarget) {
                    Role.Switch
                } else {
                    accessibilityUiState.accessibilityRole
                }
            if (accessibilityRole == Role.Switch) {
                accessibilityUiState.toggleableState?.let { toggleableState = it }
            }
            role = accessibilityRole
            stateDescription = accessibilityUiState.stateDescription
        }
        .thenIf(iconOnly) {
            Modifier.semantics { contentDescription = accessibilityUiState.contentDescription }
        }
}

data class TileColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)

private object TileDefaults {
    val ActiveIconCornerRadius = 16.dp
    val ActiveTileCornerRadius = 24.dp

    /** An active tile uses the active color as background */
    @Composable
    @ReadOnlyComposable
    fun activeTileColors(): TileColors =
        TileColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onPrimary,
            secondaryLabel = MaterialTheme.colorScheme.onPrimary,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    /** An active tile with dual target only show the active color on the icon */
    @Composable
    @ReadOnlyComposable
    fun activeDualTargetTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onPrimary,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveDualTargetTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = LocalAndroidColorScheme.current.surfaceEffect2,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun inactiveTileColors(): TileColors =
        TileColors(
            background = LocalAndroidColorScheme.current.surfaceEffect1,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    @ReadOnlyComposable
    fun unavailableTileColors(): TileColors {
        val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .18f)
        val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
        return TileColors(
            background = surfaceColor,
            iconBackground = surfaceColor,
            label = onSurfaceVariantColor,
            secondaryLabel = onSurfaceVariantColor,
            icon = onSurfaceVariantColor,
        )
    }

    @Composable
    @ReadOnlyComposable
    fun getColorForState(
        uiState: TileUiState,
        iconOnly: Boolean,
        skinEnabled: Boolean,
    ): TileColors {
        // petalOS: when the quick settings skin is enabled, use the petal card palette
        if (skinEnabled) {
            val skin =
                PetalQsSkin.tileColors(
                    state = uiState.state,
                    handlesSecondaryClick = uiState.handlesSecondaryClick,
                    iconOnly = iconOnly,
                )
            return TileColors(
                background = skin.background,
                iconBackground = skin.iconBackground,
                label = skin.label,
                secondaryLabel = skin.secondaryLabel,
                icon = skin.icon,
            )
        }
        return when (uiState.state) {
            STATE_ACTIVE -> {
                if (uiState.handlesSecondaryClick && !iconOnly) {
                    activeDualTargetTileColors()
                } else {
                    activeTileColors()
                }
            }

            STATE_INACTIVE -> {
                if (uiState.handlesSecondaryClick && !iconOnly) {
                    inactiveDualTargetTileColors()
                } else {
                    inactiveTileColors()
                }
            }

            else -> unavailableTileColors()
        }
    }

    @Composable
    fun animateIconShapeAsState(state: Int, skinEnabled: Boolean): State<RoundedCornerShape> {
        // petalOS: the icon box inside large tiles is always a circle in the petal look.
        if (skinEnabled) {
            return remember { mutableStateOf(PetalQsSkin.iconShape()) }
        }
        return animateShapeAsState(
            state = state,
            activeCornerRadius = ActiveIconCornerRadius,
            label = "QSTileCornerRadius",
        )
    }

    @Composable
    fun animateTileShapeAsState(
        state: Int,
        iconOnly: Boolean = false,
        skinEnabled: Boolean,
        handlesSecondaryClick: Boolean = false,
    ): State<RoundedCornerShape> {
        // Petal shares soft corners across compact controls and expanded cards.
        if (skinEnabled) {
            return remember(handlesSecondaryClick) {
                mutableStateOf(
                    PetalQsSkin.tileShape(
                        iconOnly = iconOnly,
                        handlesSecondaryClick = handlesSecondaryClick,
                    )
                )
            }
        }
        return animateShapeAsState(
            state = state,
            activeCornerRadius = ActiveTileCornerRadius,
            label = "QSTileIconCornerRadius",
        )
    }

    @Composable
    fun animateShapeAsState(
        state: Int,
        activeCornerRadius: Dp,
        label: String,
    ): State<RoundedCornerShape> {
        val animatedCornerRadius by
            animateDpAsState(
                targetValue =
                    if (state == STATE_ACTIVE) {
                        activeCornerRadius
                    } else {
                        InactiveCornerRadius
                    },
                label = label,
            )

        return remember {
            val corner =
                object : CornerSize {
                    override fun toPx(shapeSize: Size, density: Density): Float {
                        return with(density) { animatedCornerRadius.toPx() }
                    }
                }
            mutableStateOf(RoundedCornerShape(corner))
        }
    }
}

// A composable function that returns the [Resources]. It will be recomposed when [Configuration]
@Composable
@ReadOnlyComposable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalResources.current
}

// petalOS: whether the quick settings skin is enabled, observed via a [ContentObserver] on the
@Composable
private fun petalSkinEnabled(): Boolean {
    val context = LocalContext.current
    // Bumped by the ContentObserver whenever the setting changes; forces the read below to
    var invalidation by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    invalidation++
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_QS_SKIN_ENABLED),
            /* notifyForDescendants = */ false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    // The generation read below subscribes this recompose scope to skin setting changes; the
    return PetalConfig.isQsSkinEnabled(context) xor (invalidation < 0)
}
