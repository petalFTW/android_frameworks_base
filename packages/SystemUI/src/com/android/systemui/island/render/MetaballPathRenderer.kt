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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Fallback metaball renderer (§8.4): builds the smooth union of the island's edge circle and the
 * droplet via the classic tangent/bezier construction. Runs everywhere; visually ~85% of the shader.
 */
class MetaballPathRenderer {
    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Draws the metaball union of a rounded box and a droplet. When the droplet is close enough to
     * the box's leading edge it bulges and fuses; otherwise the two are drawn separately.
     */
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
        rimWidth: Float,
    ) {
        if (dropR <= 0f) {
            // no droplet: just the box
            fillPaint.color = tintColor
            path.reset()
            path.addRoundRect(box, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.drawPath(path, fillPaint)
            drawRim(canvas, rimColor, rimWidth)
            return
        }

        // The island's edge circle sits at the box's leading edge (approximated by the corner).
        val edgeX = box.left + cornerRadius
        val edgeY = box.centerY()
        val edgeR = cornerRadius

        fillPaint.color = tintColor
        path.reset()
        path.addRoundRect(box, cornerRadius, cornerRadius, Path.Direction.CW)
        path.addCircle(edgeX, edgeY, edgeR, Path.Direction.CW)
        if (dropR > 0f) path.addCircle(dropCx, dropCy, dropR, Path.Direction.CW)
        canvas.drawPath(path, fillPaint)

        // A soft union isn't achievable with a plain path; when the droplet overlaps the edge
        // circle we additionally draw a connecting neck so they read as one blob.
        val d = hypot(dropCx - edgeX, dropCy - edgeY)
        if (d < edgeR + dropR + k && d > 0.001f) {
            drawNeck(canvas, edgeX, edgeY, edgeR, dropCx, dropCy, dropR, d, tintColor)
        }
        drawRim(canvas, rimColor, rimWidth)
    }

    private fun drawNeck(
        canvas: Canvas,
        ax: Float, ay: Float, ar: Float,
        bx: Float, by: Float, br: Float,
        d: Float,
        tintColor: Int,
    ) {
        val u1 = acos(((ar - br) / d).coerceIn(-1f, 1f))
        val pi = PI.toFloat()
        val angle = atan2(by - ay, bx - ax)
        val p1x = ax + ar * cos(angle + u1)
        val p1y = ay + ar * sin(angle + u1)
        val p2x = ax + ar * cos(angle - u1)
        val p2y = ay + ar * sin(angle - u1)
        val p3x = bx + br * cos(angle + pi - u1)
        val p3y = by + br * sin(angle + pi - u1)
        val p4x = bx + br * cos(angle - pi + u1)
        val p4y = by + br * sin(angle - pi + u1)

        val handle = min(ar, br) * 2.4f
        val nx = cos(angle)
        val ny = sin(angle)

        val neck = Path().apply {
            moveTo(p1x, p1y)
            cubicTo(p1x + nx * handle, p1y + ny * handle, p3x + nx * handle, p3y + ny * handle, p3x, p3y)
            // outer arc around B from p3 to p4
            val startAngleB = Math.toDegrees(atan2(p3y - by, p3x - bx).toDouble()).toFloat()
            val endAngleB = Math.toDegrees(atan2(p4y - by, p4x - bx).toDouble()).toFloat()
            var sweepB = endAngleB - startAngleB
            while (sweepB < 0f) sweepB += 360f
            while (sweepB >= 360f) sweepB -= 360f
            val rectB = RectF(bx - br, by - br, bx + br, by + br)
            arcTo(rectB, startAngleB, sweepB, false)
            cubicTo(p4x + nx * handle, p4y + ny * handle, p2x + nx * handle, p2y + ny * handle, p2x, p2y)
            val startAngleA = Math.toDegrees(atan2(p2y - ay, p2x - ax).toDouble()).toFloat()
            val endAngleA = Math.toDegrees(atan2(p1y - ay, p1x - ax).toDouble()).toFloat()
            var sweepA = endAngleA - startAngleA
            while (sweepA < 0f) sweepA += 360f
            while (sweepA >= 360f) sweepA -= 360f
            val rectA = RectF(ax - ar, ay - ar, ax + ar, ay + ar)
            arcTo(rectA, startAngleA, sweepA, false)
            close()
        }
        fillPaint.color = tintColor
        canvas.drawPath(neck, fillPaint)
    }

    private fun drawRim(canvas: Canvas, rimColor: Int, rimWidth: Float) {
        // A simple rim stroke of the fill path. The shader path does this more precisely.
        rimPaint.color = rimColor
        rimPaint.strokeWidth = rimWidth
        canvas.drawPath(path, rimPaint)
    }
}
