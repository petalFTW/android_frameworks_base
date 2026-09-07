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

import android.content.Context
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.petalos.config.PetalConfig

/** Smoked glass with a faint blue active tint. */
object PetalQsSkin {

    /** Whether the petal quick settings skin should be applied. */
    @JvmStatic fun isEnabled(context: Context): Boolean = PetalConfig.isQsSkinEnabled(context)

    // Neutral glass belongs to the same palette as Petal's volume and power controls.
    internal val CardColor = Color(0xCC0C0C0E)
    internal val GlyphLight = Color(0xFFF5F5F8)
    internal val GlyphDark = Color(0xFF161618)
    internal val GlyphMuted = Color(0xFFB9BAC4)
    internal val AccentBlue = Color(0xFF3B7BFF)

    // One dark veil keeps the header and controls legible even over a white wallpaper.
    internal val FrostTint = Color(0xFF14141B)
    internal const val FrostScrimAlpha = 0xB8
    internal val CardLight = Color(0xF2EDF0FF)
    internal val ActiveGlass = Color(0xD82A303B)
    internal val TileGlassDark = Color(0xB833333D)
    internal val TileGlassBorder = Color(0x30FFFFFF)
    internal val TileGlassBorderBrush =
        Brush.linearGradient(
            colors = listOf(Color(0x54FFFFFF), Color(0x1CFFFFFF), Color(0x0CFFFFFF))
        )

    // Keep the blue glow faint.
    internal fun Modifier.activeTileGlow(active: Boolean): Modifier =
        if (!active) this else drawWithCache {
            val bloom = Brush.radialGradient(
                colors = listOf(Color(0x3078ACFF), Color(0x183B7BFF), Color.Transparent),
                center = Offset(size.width * 0.38f, size.height * 0.82f),
                radius = maxOf(size.width * 0.85f, size.height * 1.15f),
            )
            val sheen = Brush.verticalGradient(
                colors = listOf(Color(0x0C8CCBFF), Color.Transparent, Color(0x0A2358DA))
            )
            onDrawWithContent {
                drawRect(bloom)
                drawRect(sheen)
                drawContent()
            }
        }

    // Motion: restrained scale pulse for shade entry/exit and touch feedback.
    // -------------------------------------------------------------------------
    internal const val PulseHiddenScale = 0.98f
    internal const val PulsePressedScale = 0.975f
    internal const val PulseDamping = 0.9f
    internal const val PulseStiffness = 620f
    internal val TileGlyphOnDark = Color(0xFFF5F5F8)
    internal val TileGlyphOnDarkMuted = Color(0xFFC5C5CF)
    internal val TileGlyphUnavailable = Color(0xFF9797A3)

    // -------------------------------------------------------------------------
    // Shapes.
    // -------------------------------------------------------------------------
    /** Corner radius shared by connectivity cards and sliders. */
    internal val TileCornerRadius = 24.dp
    /** Corner radius of the square-ish rounded cards (media / vertical slider style). */
    internal val TileCardCornerRadius = 24.dp
    /** Corner radius of the icon box inside large tiles (circle). */
    internal val IconBoxCornerRadius = 24.dp
    /** Size of the compact rounded-square controls. */
    internal val IconTileSize = 56.dp
    /** Intermediate pill used by an expanded tile while QS is still collapsed. */
    internal val CompactExpandedTileWidth = 132.dp
    internal val CompactExpandedTileHeight = 60.dp
    /** Glyph size shared by compact and expanded controls. */
    internal val IconTileIconSize = 25.dp
    internal val ConnectivityTileHeight = 68.dp
    internal val MediaRowHeight = 136.dp

    /**
     * petalOS: maximum number of rows of normal (circle) tiles per quick settings page. Pages are
     * swiped horizontally, and the large connectivity pills plus the media/brightness/volume row
     * stay fixed on every page.
     */
    internal const val petalQsPageRows = 2

    /** Allow captions and connection details to grow with the user's font size. */
    @Composable
    internal fun compactTileHeight() =
        maxOf(
            connectivityTileHeight(),
            IconTileSize + 4.dp + with(LocalDensity.current) { 16.sp.toDp() },
        )

    @Composable
    internal fun connectivityTileHeight() =
        maxOf(ConnectivityTileHeight, 16.dp + with(LocalDensity.current) { 34.sp.toDp() })

    @Composable
    internal fun mediaRowHeight() =
        maxOf(MediaRowHeight, 72.dp + with(LocalDensity.current) { 50.sp.toDp() })

    /** Consistent soft corners across compact controls, connectivity cards and media. */
    fun tileShape(iconOnly: Boolean, handlesSecondaryClick: Boolean = false): RoundedCornerShape =
        when {
            iconOnly -> RoundedCornerShape(20.dp)
            handlesSecondaryClick -> RoundedCornerShape(TileCornerRadius)
            else -> RoundedCornerShape(TileCardCornerRadius)
        }

    /** Shape of the icon box inside a large tile: always a circle in the petal look. */
    fun iconShape(): RoundedCornerShape = RoundedCornerShape(IconBoxCornerRadius)

    /**
     * Tile palette in the petal look for the given tile state.
     *
     * @param state one of [STATE_ACTIVE], [STATE_INACTIVE] or [STATE_UNAVAILABLE].
     * @param handlesSecondaryClick whether the tile is a dual target tile.
     * @param iconOnly whether the tile renders icon-only (no labels).
     */
    fun tileColors(state: Int, handlesSecondaryClick: Boolean, iconOnly: Boolean): TileSkinColors {
        val hasToggleTarget = handlesSecondaryClick && !iconOnly
        return when (state) {
            STATE_ACTIVE ->
                TileSkinColors(
                    background = ActiveGlass,
                    iconBackground = if (hasToggleTarget) Color(0x28102040) else Color.Transparent,
                    label = GlyphLight,
                    secondaryLabel = Color(0xFFE0EAFF),
                    icon = GlyphLight,
                )
            STATE_UNAVAILABLE ->
                TileSkinColors(
                    background = Color(0xB826262F),
                    iconBackground = Color.Transparent,
                    label = TileGlyphUnavailable,
                    secondaryLabel = TileGlyphUnavailable,
                    icon = TileGlyphUnavailable,
                )
            else ->
                TileSkinColors(
                    background = TileGlassDark,
                    iconBackground = if (hasToggleTarget) Color(0x14FFFFFF) else Color.Transparent,
                    label = TileGlyphOnDark,
                    secondaryLabel = TileGlyphOnDarkMuted,
                    icon = TileGlyphOnDark,
                )
        }
    }
}

/** Petal-styled palette for a quick settings tile. */
data class TileSkinColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)
