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

/** Petal glass tiles with a violet active state and matched light and dark surfaces. */
object PetalQsSkin {

    /** true when the petal qs skin is on */
    @JvmStatic fun isEnabled(context: Context): Boolean = PetalConfig.isQsSkinEnabled(context)

    @Composable
    internal fun enabled(): Boolean = petalQsPreferences().enabled

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
        LocalPetalQsPreferences.current.let {
            if (it.provided) it.emission / 100f
            else PetalConfig.getQsEmission(LocalContext.current) / 100f
        }

    // matches the volume and power overlay palette
    internal val CardColor = Color(0xE6222430)
    internal val GlyphLight = Color(0xFFF5F5F8)
    internal val GlyphDark = Color(0xFF161618)
    internal val GlyphMuted = Color(0xFFB9BAC4)

    // dark veil so text survives a white wallpaper
    internal val FrostTint = Color(0xFF0A0B10)
    internal const val FrostScrimAlpha = 0xB8
    internal val CardLight = Color(0xF2ECE7FF)
    // Active tiles move toward the shared petal accent as emission climbs.
    internal val ActiveGlassDim = Color(0xE033343D)
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
    internal val CardColorLight = Color(0xEAF7F6FA)
    internal val CardFillLight = Color(0xF2191B23)
    internal val GlyphOnLight = Color(0xFF17171A)
    internal val GlyphOnLightMuted = Color(0xFF565663)
    internal val GlyphUnavailableLight = Color(0xFF8A8A95)
    internal val FrostTintLight = Color(0xFFF6F6FA)
    internal const val FrostScrimAlphaLight = 0xC4
    internal val TileGlassLight = Color(0xB8FBFBFE)
    internal val TileGlassBorderLight = Color(0x1A000000)
    internal val TileGlassRimTopLight = Color(0xFFFFFFFF)
    internal val TileGlassRimBottomLight = Color(0x1F000000)
    internal val TileGlassBorderBrushLight =
        Brush.linearGradient(
            colors = listOf(Color(0x99FFFFFF), Color(0x40FFFFFF), Color(0x1AFFFFFF))
        )
    internal val ActiveGlassDimLight = Color(0xE0ECECF1)

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
    @Composable
    internal fun Modifier.activeTileGlow(
        active: Boolean,
        emission: Float = 1f,
        dark: Boolean = true,
    ): Modifier {
        val context = LocalContext.current
        val accent = Color(context.getColor(android.R.color.system_accent1_500))
        val softAccent = Color(context.getColor(android.R.color.system_accent1_300))
        return if (!active || emission <= 0.01f) this else drawWithCache {
            val e = (0.45f + 0.55f * emission.coerceIn(0f, 1f))
            // keep the light bloom a touch weaker so it doesnt wash the frosted cards
            val bloomTop = softAccent.copy(alpha = if (dark) 0.62f else 0.46f)
            val bloomMid = accent.copy(alpha = if (dark) 0.34f else 0.26f)
            val sheenTop = softAccent.copy(alpha = if (dark) 0.15f else 0.11f)
            val sheenBottom = accent.copy(alpha = if (dark) 0.12f else 0.09f)
            val bloom = Brush.radialGradient(
                colors = listOf(
                    bloomTop.copy(alpha = bloomTop.alpha * e),
                    bloomMid.copy(alpha = bloomMid.alpha * e),
                    Color.Transparent,
                ),
                center = Offset(
                    size.width * 0.38f,
                    size.height * 0.82f,
                ),
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
    }

    // proper see-through glass, nicked from the island renderer
    @Composable
    internal fun Modifier.liquidGlass(
        cornerRadius: Dp,
        tint: Color,
        dark: Boolean = true,
        fillAlpha: Float = GlassFillAlpha,
    ): Modifier {
        val glassModifier = drawWithCache {
            val rimTop = if (dark) TileGlassRimTop else TileGlassRimTopLight
            val rimBottom = if (dark) TileGlassRimBottom else TileGlassRimBottomLight
            val glass =
                LiquidGlassDrawable().apply {
                    setCornerRadius(cornerRadius.toPx())
                    setRimWidth(1.1f * density)
                    setTintColor(tint.copy(alpha = fillAlpha).toArgb())
                    setRim(rimTop.toArgb(), rimBottom.toArgb())
                    setGrainAlpha(if (dark) 0x16 else 0x10)
                    // That bright mid-band looked fake as hell.
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
        return glassModifier
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
    internal val IconBoxCornerRadius = 28.dp
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
            iconOnly -> RoundedCornerShape(22.dp)
            handlesSecondaryClick -> RoundedCornerShape(TileCornerRadius)
            else -> RoundedCornerShape(TileCardCornerRadius)
        }

    /** icon box is always a circle here */
    fun iconShape(): RoundedCornerShape = RoundedCornerShape(IconBoxCornerRadius)

    // monet pastels are watered down by the time they hit the glass, this puts the colour back
    internal fun punch(accent: Color, dark: Boolean): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
        hsv[1] = (hsv[1] * 1.45f + 0.06f).coerceAtMost(1f)
        hsv[2] =
            if (dark) hsv[2] * 0.9f
            else (hsv[2] * 0.96f).coerceAtMost(0.78f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** punched accent as an argb int, for the java media player side */
    @JvmStatic
    fun mediaAccent(context: Context, dark: Boolean): Int =
        punch(Color(context.getColor(android.R.color.system_accent1_500)), dark).toArgb()

    /** dark or light glyph for a given background argb, for the java media player side */
    @JvmStatic
    fun glyphOnBackground(bgArgb: Int): Int =
        if (android.graphics.Color.luminance(bgArgb) > 0.42f) GlyphDark.toArgb()
        else GlyphLight.toArgb()

    // colors per tile state
    @Composable
    @ReadOnlyComposable
    fun tileColors(
        state: Int,
        handlesSecondaryClick: Boolean,
        iconOnly: Boolean,
        emission: Float = 1f,
        dark: Boolean = true,
    ): TileSkinColors {
        // ThemeOverlayController supplies this resource from the Hub's Monet seed.
        val context = LocalContext.current
        val accent = punch(
            Color(context.getColor(android.R.color.system_accent1_500)),
            dark,
        )
        val softAccent = punch(Color(context.getColor(android.R.color.system_accent1_300)), dark)
        // even with the hub slider at zero active tiles keep some colour, full grey looked dead
        val e = (0.4f + 0.6f * emission.coerceIn(0f, 1f))
        val hasToggleTarget = handlesSecondaryClick && !iconOnly
        return when (state) {
            STATE_ACTIVE -> {
                // active tiles used to blend into the dark ones
                val bg = lerp(
                    if (dark) ActiveGlassDim else ActiveGlassDimLight,
                    accent,
                    e.coerceAtLeast(0.55f),
                )
                val activeGlyph = if (android.graphics.Color.luminance(bg.toArgb()) > 0.42f) {
                    GlyphDark
                } else {
                    GlyphLight
                }
                TileSkinColors(
                    background = bg,
                    iconBackground = if (hasToggleTarget) {
                        lerp(
                            if (dark) Color(0x1FFFFFFF) else Color(0x14000000),
                            if (dark) Color(0x40FFFFFF) else Color(0x2E000000),
                            e,
                        )
                    } else Color.Transparent,
                    label = activeGlyph,
                    secondaryLabel = activeGlyph.copy(alpha = 0.76f),
                    icon = activeGlyph,
                    glassFill = if (dark) 0.78f else 0.7f,
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
    // how much tint the glass renderer lays down, active tiles need more or the colour dies
    val glassFill: Float = PetalQsSkin.GlassFillAlpha,
)
