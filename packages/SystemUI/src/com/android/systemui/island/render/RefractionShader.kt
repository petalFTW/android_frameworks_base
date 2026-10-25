/*
 * Copyright (C) 2026 The petalOS Project
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

package com.android.systemui.island.render

import android.graphics.RenderEffect
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import com.android.internal.graphics.drawable.BackgroundBlurDrawable

// The island bends other windows; media bends its own artwork.
class RefractionShader {
    private val artworkShader by lazy { runCatching { RuntimeShader(ARTWORK_AGSL) }.getOrNull() }
    private data class ArtworkSpec(
        val width: Int, val height: Int, val density: Float, val radius: Float,
        val blur: Float, val strength: Float,
    )
    private var artworkSpec: ArtworkSpec? = null
    private var cachedArtworkEffect: RenderEffect? = null
    private var mediaFlowFailed = false
    private val mediaFlowShader by lazy {
        runCatching { RuntimeShader(MEDIA_FLOW_AGSL) }.getOrElse {
            mediaFlowFailed = true
            Log.w("PetalMediaFlow", "Flow shader failed to compile", it)
            null
        }
    }
    private data class MediaFlowSpec(
        val width: Int, val height: Int, val density: Float,
        val left: Float, val top: Float, val artWidth: Float, val artHeight: Float,
        val feather: Float, val depth: Float, val blur: Float, val clearFrost: Boolean,
    )
    private var mediaFlowSpec: MediaFlowSpec? = null
    private var cachedMediaFlowEffect: RenderEffect? = null

    fun mediaFlowEffect(
        width: Int,
        height: Int,
        density: Float,
        artwork: RectF,
        feather: Float,
        depth: Float,
        blurRadius: Float,
        clearFrost: Boolean = false,
    ): RenderEffect? {
        if (width <= 0 || height <= 0 || artwork.isEmpty || depth <= 0f) return null
        val spec = MediaFlowSpec(width, height, density, artwork.left, artwork.top,
            artwork.width(), artwork.height(), feather, depth, blurRadius, clearFrost)
        if (mediaFlowSpec == spec) return cachedMediaFlowEffect
        val blur = RenderEffect.createBlurEffect(blurRadius.coerceAtLeast(1f),
            blurRadius.coerceAtLeast(1f), Shader.TileMode.CLAMP)
        val flow = if (mediaFlowFailed) null else mediaFlowShader
        val effect = if (flow != null) runCatching {
            flow.setFloatUniform("size", width.toFloat(), height.toFloat())
            flow.setFloatUniform("artwork", artwork.left, artwork.top, artwork.width(), artwork.height())
            flow.setFloatUniform("feather", feather.coerceAtLeast(1f))
            flow.setFloatUniform("depth", depth)
            flow.setFloatUniform("density", density)
            flow.setFloatUniform("smoke", if (clearFrost) 0f else 1f)
            RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(flow, "frostedInput"), blur)
        }.getOrElse {
            mediaFlowFailed = true
            Log.w("PetalMediaFlow", "Flow shader setup failed", it)
            null
        } else null
        cachedMediaFlowEffect = effect ?: run {
            val seam = artwork.bottom
            val start = seam - feather * 1.3f
            val end = seam + depth
            val span = (end - start).coerceAtLeast(1f)
            val mask = LinearGradient(0f, start, 0f, end,
                intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, (feather * 0.3f / span).coerceIn(0.001f, 0.2f), 0.25f, 1f),
                Shader.TileMode.CLAMP)
            val shaded = RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.70f, 0.70f, 0.70f, 1f) }), blur)
            RenderEffect.createBlendModeEffect(shaded, RenderEffect.createShaderEffect(mask), BlendMode.DST_IN)
        }
        mediaFlowSpec = spec
        return cachedMediaFlowEffect
    }

    fun artworkEffect(
        w: Int,
        h: Int,
        density: Float,
        radius: Float,
        blurRadius: Float,
        strengthScale: Float,
    ): RenderEffect? {
        if (w <= 0 || h <= 0) return null
        if (strengthScale <= 0f && blurRadius <= 0f) return null
        val spec = ArtworkSpec(w, h, density, radius, blurRadius, strengthScale)
        if (artworkSpec == spec) return cachedArtworkEffect
        val lens = artworkShader
        if (lens == null) {
            cachedArtworkEffect = if (blurRadius > 0f) {
                RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
            } else null
            artworkSpec = spec
            return cachedArtworkEffect
        }
        lens.setFloatUniform("center", w * .5f, h * .5f)
        lens.setFloatUniform("halfSize", w * .5f, h * .5f)
        lens.setFloatUniform("radius", radius.coerceIn(0f, minOf(w, h) * .5f))
        lens.setFloatUniform("strength", REFRACT_STRENGTH_DP * density * strengthScale)
        lens.setFloatUniform("bandWidth", bandFor(w, h, density).coerceAtLeast(.001f))
        lens.setFloatUniform("blurRadius", blurRadius)
        cachedArtworkEffect = RenderEffect.createRuntimeShaderEffect(lens, "originalInput")
        artworkSpec = spec
        return cachedArtworkEffect
    }

    fun applyTo(
        backdrop: BackgroundBlurDrawable,
        w: Int,
        h: Int,
        density: Float,
        strengthScale: Float,
        chromaScale: Float,
    ) {
        if (w <= 0 || h <= 0 || strengthScale <= 0f) {
            clear(backdrop)
            return
        }
        val band = bandFor(w, h, density)
        backdrop.setRefraction(
            REFRACT_STRENGTH_DP * density * strengthScale,
            band,
            CHROMA_MAX_DP * density * chromaScale,
        )
    }

    fun clear(backdrop: BackgroundBlurDrawable) {
        backdrop.setRefraction(0f, 0f, 0f)
    }

    fun bandFor(w: Int, h: Int, density: Float): Float =
        minOf(minOf(w, h) * REFRACT_BAND_FRACTION, REFRACT_BAND_MAX_DP * density)

    companion object {
        // Gaussian blur first, then a smooth liquid fade. No spaced-out sample grid.
        private const val MEDIA_FLOW_AGSL = """
            uniform shader frostedInput;
            uniform float2 size;
            uniform float4 artwork;
            uniform float feather;
            uniform float depth;
            uniform float density;
            uniform float smoke;

            half4 main(float2 xy) {
                float seam = artwork.y + artwork.w;
                float start = seam - feather;
                if (xy.y < start - feather * 0.3) return half4(0.0);
                float u = (xy.x - artwork.x) / max(artwork.z, 1.0);
                float amount = smoothstep(seam, seam + depth, xy.y);
                float wave = sin(u * 8.0 + 0.6) * sin(u * 5.0 - 0.3);
                float2 bend = float2(sin(u * 7.0) * 2.0 * density,
                                    wave * 3.0 * density) * amount;
                float2 sampleAt = clamp(xy + bend, float2(0.5), size - 0.5);
                half4 color = frostedInput.eval(sampleAt);
                float upper = smoothstep(start - feather * 0.3, start, xy.y);
                float end = seam + depth + wave * depth * 0.06;
                float lower = 1.0 - smoothstep(seam + min(feather * 0.5, depth * 0.15), end, xy.y);
                float shade = mix(0.76, 0.52, smoothstep(start, seam + 70.0 * density, xy.y));
                // clear frost keeps full colour; smoked glass dims toward the controls
                shade = mix(1.0, shade, smoke);
                return half4(color.rgb * half(shade), color.a) * half(upper * lower);
            }
        """

        // Same shoulder and normal as RenderEngine's island lens, minus chromatic splitting.
        private const val ARTWORK_AGSL = """
            uniform shader originalInput;
            uniform float2 center;
            uniform float2 halfSize;
            uniform float radius;
            uniform float strength;
            uniform float bandWidth;
            uniform float blurRadius;

            float2 bounded(float2 xy) {
                return clamp(xy, float2(0.5), halfSize * 2.0 - 0.5);
            }

            half4 frostAt(float2 xy) {
                if (blurRadius <= 0.0) return originalInput.eval(bounded(xy));
                half4 color = half4(0.0);
                for (int y = -2; y <= 2; y++) {
                    float wy = y == 0 ? 6.0 : (abs(y) == 1 ? 4.0 : 1.0);
                    for (int x = -2; x <= 2; x++) {
                        float wx = x == 0 ? 6.0 : (abs(x) == 1 ? 4.0 : 1.0);
                        color += originalInput.eval(bounded(
                                xy + float2(float(x), float(y)) * blurRadius * 0.5))
                                * half(wx * wy / 256.0);
                    }
                }
                return color;
            }

            half4 main(float2 xy) {
                if (strength <= 0.0) return frostAt(xy);
                float2 p = xy - center;
                float2 inner = halfSize - max(radius, bandWidth);
                if (abs(p.x) <= inner.x && abs(p.y) <= inner.y) return frostAt(xy);
                float2 q = abs(p) - halfSize + radius;
                float2 outside = max(q, 0.0);
                float outsideLength = length(outside);
                float d = outsideLength + min(max(q.x, q.y), 0.0) - radius;
                float u = clamp(1.0 + d / bandWidth, 0.0, 1.0);
                float rim = smoothstep(0.0, 1.0, u);
                if (rim <= 0.001) return frostAt(xy);
                float2 normal = outsideLength > 0.001 ? outside / outsideLength
                        : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
                normal *= sign(p);
                float bend = 0.6 * u / sqrt(max(1.0 - 0.64 * u * u, 0.36));
                half4 lens = originalInput.eval(bounded(xy + normal * bend * strength));
                if (rim == 1.0) return lens;
                return mix(frostAt(xy), lens, half(rim));
            }
        """

        // 30dp capsule gets a whisper (~6dp), big cards cap at 18dp
        private const val REFRACT_BAND_FRACTION = 0.20f
        private const val REFRACT_BAND_MAX_DP = 18f
        // peak pop-out pull at the sidewall, driven by the settings slider
        private const val REFRACT_STRENGTH_DP = 24f
        // peak chromatic split at the sidewall, driven by the settings slider
        private const val CHROMA_MAX_DP = 5f
    }
}
