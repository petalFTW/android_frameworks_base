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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader

/**
 * Preferred metaball renderer (§8.2): an AGSL smooth-union SDF of a rounded box and a droplet,
 * producing the liquid "drop fused to the capsule" look. Gated on API 33+; use
 * [MetaballPathRenderer] below that.
 */
class MetaballShaderRenderer {
    private val shader = RuntimeShader(SHADER_SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@MetaballShaderRenderer.shader }

    fun draw(
        canvas: Canvas,
        box: RectF,
        cornerRadius: Float,
        dropCx: Float,
        dropCy: Float,
        dropR: Float,
        k: Float,
        tintColor: Int,
        rimColor: Int,
    ) {
        shader.setFloatUniform("uSize", box.width(), box.height())
        shader.setFloatUniform(
            "uBox", box.centerX(), box.centerY(), box.width() / 2f, box.height() / 2f
        )
        shader.setFloatUniform("uBoxRadius", cornerRadius)
        shader.setFloatUniform("uDrop", dropCx, dropCy, dropR)
        shader.setFloatUniform("uK", k)
        shader.setFloatUniform("uColor", premul(tintColor))
        shader.setFloatUniform("uRim", premul(rimColor))

        val pad = k + 4f
        canvas.drawRect(
            box.left - pad,
            box.top - pad,
            box.right + pad,
            box.bottom + pad,
            paint,
        )
    }

    private fun premul(color: Int): FloatArray {
        val a = Color.alpha(color) / 255f
        return floatArrayOf(
            Color.red(color) / 255f * a,
            Color.green(color) / 255f * a,
            Color.blue(color) / 255f * a,
            a,
        )
    }

    companion object {
        private val SHADER_SOURCE =
            """
            uniform float2  uSize;
            uniform float4  uBox;
            uniform float   uBoxRadius;
            uniform float3  uDrop;
            uniform float   uK;
            uniform float4  uColor;
            uniform float4  uRim;

            float sdRoundBox(float2 p, float2 b, float r) {
                float2 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }
            float sdCircle(float2 p, float r) { return length(p) - r; }

            float smin(float a, float b, float k) {
                if (k <= 0.0) return min(a, b);
                float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
                return mix(b, a, h) - k * h * (1.0 - h);
            }

            half4 main(float2 fragCoord) {
                float dBox  = sdRoundBox(fragCoord - uBox.xy, uBox.zw, uBoxRadius);
                float dDrop = sdCircle(fragCoord - uDrop.xy, uDrop.z);
                float d     = smin(dBox, dDrop, uK);

                float aa    = 1.0;
                float alpha = 1.0 - smoothstep(-aa, aa, d);

                float rimW  = 1.5;
                float rim   = smoothstep(-rimW, 0.0, d) * (1.0 - smoothstep(0.0, aa, d));

                half4 col = half4(uColor) * alpha;
                col = col + half4(uRim) * rim * (1.0 - alpha * 0.35);
                return col;
            }
            """.trimIndent()
    }
}
