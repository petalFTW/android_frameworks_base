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
import android.content.res.Configuration
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.island.render.LiquidGlassDrawable
import org.petalos.config.PetalConfig

/** smoked glass, faint blue when active. flips to a frosted white set in light mode */
object PetalQsSkin {

    /** true when the petal qs skin is on */
    @JvmStatic fun isEnabled(context: Context): Boolean = PetalConfig.isQsSkinEnabled(context)

    /** night check for the java side, drawables and views cant read compose locals */
    @JvmStatic
    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** night check inside compose */
    @Composable
    @ReadOnlyComposable
    internal fun dark(): Boolean =
        (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

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
    // see-through fill, the rest comes from the island glass renderer
    internal const val GlassFillAlpha = 0.52f
    internal val TileGlassRimTop = Color(0x99FFFFFF)
    internal val TileGlassRimBottom = Color(0x14FFFFFF)
    internal val TileGlassBorderBrush =
        Brush.linearGradient(
            colors = listOf(Color(0x54FFFFFF), Color(0x1CFFFFFF), Color(0x0CFFFFFF))
        )

    // light mirror of the dark palette, frosted white instead of smoked charcoal
    internal val CardColorLight = Color(0xCCE6E8F0)
    internal val CardFillLight = Color(0xF22C2E36)
    internal val GlyphOnLight = Color(0xFF17171A)
    internal val GlyphOnLightMuted = Color(0xFF565663)
    internal val GlyphUnavailableLight = Color(0xFF8A8A95)
    internal val FrostTintLight = Color(0xFFEDEFF5)
    internal const val FrostScrimAlphaLight = 0xC4
    internal val TileGlassLight = Color(0xB8FBFBFE)
    internal val TileGlassBorderLight = Color(0x1A000000)
    internal val TileGlassRimTopLight = Color(0xFFFFFFFF)
    internal val TileGlassRimBottomLight = Color(0x1F000000)
    internal val TileGlassBorderBrushLight =
        Brush.linearGradient(
            colors = listOf(Color(0x99FFFFFF), Color(0x40FFFFFF), Color(0x1AFFFFFF))
        )
    internal val ActiveGlassDimLight = Color(0xE0BFCBE6)

    /** frosted panel tint, dark vs light */
    internal fun frostTint(dark: Boolean): Color = if (dark) FrostTint else FrostTintLight

    /** scrim alpha for the frosted panel */
    internal fun frostScrimAlpha(dark: Boolean): Int =
        if (dark) FrostScrimAlpha else FrostScrimAlphaLight

    /** glass card fill */
    internal fun tileGlass(dark: Boolean): Color = if (dark) TileGlassDark else TileGlassLight

    /** thin rim colour on the glass cards */
    internal fun tileGlassBorder(dark: Boolean): Color =
        if (dark) TileGlassBorder else TileGlassBorderLight

    /** gradient rim brush */
    internal fun tileGlassBorderBrush(dark: Boolean): Brush =
        if (dark) TileGlassBorderBrush else TileGlassBorderBrushLight

    /** main glyph colour */
    internal fun glyphOn(dark: Boolean): Color = if (dark) TileGlyphOnDark else GlyphOnLight

    /** secondary glyph colour */
    internal fun glyphOnMuted(dark: Boolean): Color =
        if (dark) TileGlyphOnDarkMuted else GlyphOnLightMuted

    /** end of the light mirror */

    // the glow follows the emission slider
    internal fun Modifier.activeTileGlow(
        active: Boolean,
        emission: Float = 1f,
        dark: Boolean = true,
    ): Modifier =
        if (!active || emission <= 0.01f) this else drawWithCache {
            val e = emission.coerceIn(0f, 1f)
            // keep the light bloom a touch weaker so it doesnt wash the frosted cards
            val bloomTop = if (dark) Color(0x8078ACFF) else Color(0x5C3B7BFF)
            val bloomMid = if (dark) Color(0x403B7BFF) else Color(0x332F63E6)
            val sheenTop = if (dark) Color(0x1A8CCBFF) else Color(0x148CCBFF)
            val sheenBottom = if (dark) Color(0x142358DA) else Color(0x102358DA)
            val bloom = Brush.radialGradient(
                colors = listOf(
                    bloomTop.copy(alpha = bloomTop.alpha * e),
                    bloomMid.copy(alpha = bloomMid.alpha * e),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.38f, size.height * 0.82f),
                radius = maxOf(size.width * 0.95f, size.height * 1.25f),
            )
            val sheen = Brush.verticalGradient(
                colors = listOf(
                    sheenTop.copy(alpha = sheenTop.alpha * e),
                    Color.Transparent,
                    sheenBottom.copy(alpha = sheenBottom.alpha * e),
                )
            )
            onDrawWithContent {
                drawRect(bloom)
                drawRect(sheen)
                drawContent()
            }
        }

    // proper see-through glass, nicked from the island renderer
    internal fun Modifier.liquidGlass(
        cornerRadius: Dp,
        tint: Color,
        dark: Boolean = true,
    ): Modifier = drawWithCache {
        val rimTop = if (dark) TileGlassRimTop else TileGlassRimTopLight
        val rimBottom = if (dark) TileGlassRimBottom else TileGlassRimBottomLight
        val glass =
            LiquidGlassDrawable().apply {
                setCornerRadius(cornerRadius.toPx())
                setRimWidth(1.1f * density)
                setTintColor(tint.copy(alpha = GlassFillAlpha).toArgb())
                setRim(rimTop.toArgb(), rimBottom.toArgb())
                setGrainAlpha(if (dark) 0x16 else 0x10)
                // sheen way down and parked near the rim light, that mid band looked fake as hell
                setSheenStrength(0x55)
                setSpecularProgress(0.16f)
            }
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                glass.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                glass.draw(canvas.nativeCanvas)
            }
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
        dark: Boolean = true,
    ): TileSkinColors {
        val e = emission.coerceIn(0f, 1f)
        val hasToggleTarget = handlesSecondaryClick && !iconOnly
        val glyph = if (dark) GlyphLight else GlyphOnLight
        return when (state) {
            STATE_ACTIVE -> {
                // active tiles used to blend into the dark ones
                val bg = lerp(if (dark) ActiveGlassDim else ActiveGlassDimLight, ActiveGlassHot, e)
                TileSkinColors(
                    background = bg,
                    iconBackground = if (hasToggleTarget) {
                        lerp(
                            if (dark) Color(0x1FFFFFFF) else Color(0x14000000),
                            if (dark) Color(0x33FFFFFF) else Color(0x24000000),
                            e,
                        )
                    } else Color.Transparent,
                    label = glyph,
                    secondaryLabel = lerp(
                        if (dark) TileGlyphOnDarkMuted else GlyphOnLightMuted,
                        if (dark) Color(0xFFDCE6FF) else Color(0xFF1E3E8C),
                        e,
                    ),
                    icon = glyph,
                )
            }
            STATE_UNAVAILABLE ->
                TileSkinColors(
                    background = if (dark) Color(0xB826262F) else Color(0xB8E0E2EA),
                    iconBackground = Color.Transparent,
                    label = if (dark) TileGlyphUnavailable else GlyphUnavailableLight,
                    secondaryLabel = if (dark) TileGlyphUnavailable else GlyphUnavailableLight,
                    icon = if (dark) TileGlyphUnavailable else GlyphUnavailableLight,
                )
            else ->
                TileSkinColors(
                    background = tileGlass(dark),
                    iconBackground = if (hasToggleTarget) {
                        if (dark) Color(0x14FFFFFF) else Color(0x14000000)
                    } else Color.Transparent,
                    label = glyphOn(dark),
                    secondaryLabel = glyphOnMuted(dark),
                    icon = glyphOn(dark),
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
