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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.petalos.config.PetalConfig

/** smoked glass, faint blue when active */
object PetalQsSkin {

    /** true when the petal qs skin is on */
    @JvmStatic fun isEnabled(context: Context): Boolean = PetalConfig.isQsSkinEnabled(context)

    // emission 0..1 from the hub slider
    @Composable
    @ReadOnlyComposable
    internal fun emission(): Float =
        PetalConfig.getQsEmission(LocalContext.current) / 100f

    // matches the volume and power overlay palette
    internal val CardColor = Color(0xCC0C0C0E)
    internal val GlyphLight = Color(0xFFF5F5F8)
    internal val GlyphDark = Color(0xFF161618)
    internal val GlyphMuted = Color(0xFFB9BAC4)
    internal val AccentBlue = Color(0xFF3B7BFF)

    // dark veil so text survives a white wallpaper
    internal val FrostTint = Color(0xFF14141B)
    internal const val FrostScrimAlpha = 0xB8
    internal val CardLight = Color(0xF2EDF0FF)
    // active tile goes blue as emission climbs
    internal val ActiveGlassDim = Color(0xE0323846)
    internal val ActiveGlassHot = Color(0xFF2F63E6)
    internal val TileGlassDark = Color(0xB833333D)
    internal val TileGlassBorder = Color(0x30FFFFFF)
    internal val TileGlassBorderBrush =
        Brush.linearGradient(
            colors = listOf(Color(0x54FFFFFF), Color(0x1CFFFFFF), Color(0x0CFFFFFF))
        )

    // the glow follows the emission slider
    internal fun Modifier.activeTileGlow(active: Boolean, emission: Float = 1f): Modifier =
        if (!active || emission <= 0.01f) this else drawWithCache {
            val e = emission.coerceIn(0f, 1f)
            val bloom = Brush.radialGradient(
                colors = listOf(
                    Color(0x8078ACFF).copy(alpha = 0x80 / 255f * e),
                    Color(0x403B7BFF).copy(alpha = 0x40 / 255f * e),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.38f, size.height * 0.82f),
                radius = maxOf(size.width * 0.95f, size.height * 1.25f),
            )
            val sheen = Brush.verticalGradient(
                colors = listOf(
                    Color(0x1A8CCBFF).copy(alpha = 0x1A / 255f * e),
                    Color.Transparent,
                    Color(0x142358DA).copy(alpha = 0x14 / 255f * e),
                )
            )
            onDrawWithContent {
                drawRect(bloom)
                drawRect(sheen)
                drawContent()
            }
        }

    // subtle scale pulse on open/close and press
    // -------------------------------------------------------------------------
    internal const val PulseHiddenScale = 0.98f
    internal const val PulsePressedScale = 0.975f
    internal const val PulseDamping = 0.9f
    internal const val PulseStiffness = 620f
    internal val TileGlyphOnDark = Color(0xFFF5F5F8)
    internal val TileGlyphOnDarkMuted = Color(0xFFC5C5CF)
    internal val TileGlyphUnavailable = Color(0xFF9797A3)

    // shapes
    /** shared corner radius for cards and sliders */
    internal val TileCornerRadius = 24.dp
    /** radius for the media and slider cards */
    internal val TileCardCornerRadius = 24.dp
    /** icon box radius, full circle */
    internal val IconBoxCornerRadius = 24.dp
    /** compact tile size */
    internal val IconTileSize = 56.dp
    /** pill size before the shade expands */
    internal val CompactExpandedTileWidth = 132.dp
    internal val CompactExpandedTileHeight = 60.dp
    /** glyph size for all tiles */
    internal val IconTileIconSize = 25.dp
    internal val ConnectivityTileHeight = 68.dp
    internal val MediaRowHeight = 136.dp

    // two circle rows per page, pills and media fixed
    internal const val petalQsPageRows = 2

    /** scale with the user's font size */
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

    /** one shape for compact, connectivity and media */
    fun tileShape(iconOnly: Boolean, handlesSecondaryClick: Boolean = false): RoundedCornerShape =
        when {
            iconOnly -> RoundedCornerShape(20.dp)
            handlesSecondaryClick -> RoundedCornerShape(TileCornerRadius)
            else -> RoundedCornerShape(TileCardCornerRadius)
        }

    /** icon box is always a circle here */
    fun iconShape(): RoundedCornerShape = RoundedCornerShape(IconBoxCornerRadius)

    // colors per tile state
    fun tileColors(
        state: Int,
        handlesSecondaryClick: Boolean,
        iconOnly: Boolean,
        emission: Float = 1f,
    ): TileSkinColors {
        val e = emission.coerceIn(0f, 1f)
        val hasToggleTarget = handlesSecondaryClick && !iconOnly
        return when (state) {
            STATE_ACTIVE -> {
                // active tiles used to blend into the dark ones
                val bg = lerp(ActiveGlassDim, ActiveGlassHot, e)
                TileSkinColors(
                    background = bg,
                    iconBackground = if (hasToggleTarget) {
                        lerp(Color(0x1FFFFFFF), Color(0x33FFFFFF), e)
                    } else Color.Transparent,
                    label = GlyphLight,
                    secondaryLabel = lerp(TileGlyphOnDarkMuted, Color(0xFFDCE6FF), e),
                    icon = GlyphLight,
                )
            }
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

/** colors for one petal qs tile */
data class TileSkinColors(
    val background: Color,
    val iconBackground: Color,
    val label: Color,
    val secondaryLabel: Color,
    val icon: Color,
)
