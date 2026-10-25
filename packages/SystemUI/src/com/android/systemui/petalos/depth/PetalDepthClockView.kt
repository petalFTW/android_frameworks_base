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

package com.android.systemui.petalos.depth

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import com.android.app.animation.Interpolators
import com.android.systemui.res.R
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.petalos.config.DepthClockMaterials

// used by both the preview and the real lockscreen
class PetalDepthClockView(context: Context) : View(context) {

    companion object {
        const val STYLE_SOLID = 1
        const val STYLE_OUTLINE = 2
        const val STYLE_GRADIENT = 3
        const val STYLE_HYBRID = 4
        const val STYLE_BLOCKBUSTER = 5
        const val STYLE_NEON = 6
        const val STYLE_GOLD = 7
        const val STYLE_VOGUE = 8
        const val STYLE_MONOLITH = 9

        // clock trails the subject on purpose
        const val PARALLAX_RATE = 0.45f
        private const val TICK_MS = 1000L
        private const val TAG = "PetalBouncerClock"
        private const val DEBUG_BOUNCER = true

        internal fun compactStyleFor(style: Int): Int? = when (style) {
            DepthClockMaterials.FROST_SQUARE -> DepthClockMaterials.FROST
            DepthClockMaterials.SMOKE_SQUARE -> DepthClockMaterials.SMOKE
            DepthClockMaterials.BLOOM_SQUARE -> DepthClockMaterials.BLOOM
            in STYLE_SOLID..DepthClockMaterials.LAST -> style
            else -> null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val calendar = Calendar.getInstance()

    private val materials = org.petalos.config.DepthClockMaterials()
    private var backdrop: android.graphics.Bitmap? = null

    private var liquidBackdrop: android.graphics.Bitmap? = null

    fun setLiquidBackdrop(bitmap: android.graphics.Bitmap?) {
        liquidBackdrop = bitmap
        invalidate()
    }

    fun setBackdrop(bitmap: android.graphics.Bitmap?) {
        backdrop = bitmap
        invalidate()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var style = STYLE_SOLID
    private var vertical = false
    private var accentColor = Color.TRANSPARENT
    private var showDate = true
    private var twentyFourHour = true

    // vertical position, clamped 0.05-0.85
    private var anchor = 0.20f
    // horizontal center, clamped to 0.1-0.9
    private var anchorX = 0.5f
    // digit scale, clamped 0.6-1.6
    private var scale = 1f
    private var contentBottom = Int.MAX_VALUE
    private var depthApplied = false
    private var bouncerHost: WeakReference<View>? = null
    private var bouncerProgress = 0f
    private var bouncerAnimator: ValueAnimator? = null
    private val expandedBounds = RectF()
    private val bouncerBounds = RectF()
    private val hostBounds = RectF()
    private val rootPosition = IntArray(2)
    private val hostPosition = IntArray(2)
    private var lastCompactSecond = -1L
    private var lastBouncerDump = 0L
    private val expandedRunX = FloatArray(2)
    private val expandedRunY = FloatArray(2)
    private var expandedTextSize = 0f
    private var expandedDateX = 0f
    private var expandedDateY = 0f
    private var compactTextSize = 0f
    private var compactStartX = 0f
    private var compactBaseline = 0f
    private var hasExpandedColon = false

    internal val supportsBouncerClock: Boolean
        get() = depthApplied && compactStyleFor(style) != null

    internal val bouncerAnimationRunning: Boolean
        get() = bouncerAnimator?.isStarted == true

    internal fun bouncerStateForDump(): String =
        "style=$style applied=$depthApplied progress=$bouncerProgress animating=$bouncerAnimationRunning " +
            "clock=${width}x$height target=$bouncerBounds host=$hostBounds " +
            "textSize=$compactTextSize attached=$isAttachedToWindow"

    fun setDepthApplied(applied: Boolean) {
        if (depthApplied == applied) return
        depthApplied = applied
        if (!applied) {
            bouncerAnimator?.cancel()
            bouncerAnimator = null
            bouncerHost?.get()?.invalidate()
            bouncerHost = null
            bouncerProgress = 0f
            invalidate()
        }
    }

    internal fun setBouncerHost(host: View) {
        getLocationOnScreen(rootPosition)
        host.getLocationOnScreen(hostPosition)
        val left = hostPosition[0] - rootPosition[0]
        val top = hostPosition[1] - rootPosition[1]
        bouncerBounds.set((left + host.paddingLeft).toFloat(),
            (top + host.paddingTop).toFloat(),
            (left + host.width - host.paddingRight).toFloat(),
            (top + host.height - host.paddingBottom).toFloat())
        if (bouncerHost?.get() === host) return
        bouncerHost = WeakReference(host)
        animateBouncerTo(1f)
    }

    internal fun releaseBouncerHost(host: View) {
        if (bouncerHost?.get() !== host) return
        bouncerHost = null
        animateBouncerTo(0f)
    }

    private fun animateBouncerTo(target: Float) {
        bouncerAnimator?.cancel()
        bouncerAnimator = null
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow) {
            bouncerProgress = target
            invalidate()
            bouncerHost?.get()?.invalidate()
            return
        }
        bouncerAnimator = ValueAnimator.ofFloat(bouncerProgress, target).apply {
            duration = 280L
            interpolator = Interpolators.STANDARD_DECELERATE
            addUpdateListener {
                bouncerProgress = it.animatedValue as Float
                invalidate()
                bouncerHost?.get()?.invalidate()
            }
            start()
        }
    }

    // Borrow the real clock's material; another plain TextClock looked bloody wrong.
    internal fun drawBouncerClock(canvas: Canvas, host: View): Boolean {
        val compactStyle = compactStyleFor(style) ?: return false
        if (!supportsBouncerClock || bouncerHost?.get() !== host || bouncerBounds.isEmpty) return false
        if (bouncerProgress <= 0f && !bouncerAnimationRunning) {
            bouncerProgress = 1f
            invalidate()
        }
        val p = bouncerProgress
        if (!p.isFinite() || (255f * p).toInt() <= 0) return false
        hostBounds.set(host.paddingLeft.toFloat(), host.paddingTop.toFloat(),
            (host.width - host.paddingRight).toFloat(), (host.height - host.paddingBottom).toFloat())
        if (hostBounds.isEmpty) return false
        refreshBouncerTime()
        val text = measureCompactClock(compactStyle, hostBounds)
        if (!compactTextSize.isFinite() || compactTextSize <= 0f) return false
        val save = if (bouncerProgress < 1f) {
            canvas.saveLayerAlpha(null, (255f * bouncerProgress).toInt())
        } else canvas.save()
        // Single-line TextViews use a very wide text layout and can scroll by hundreds
        // of thousands of pixels to center it. View supplies a scrolled canvas, but
        // our clock is positioned in the visible host bounds, not that text layout.
        canvas.translate(host.scrollX.toFloat(), host.scrollY.toFloat())
        // The header has its own display list. Settled text belongs in its local coords.
        canvas.translate(-(bouncerBounds.left - host.paddingLeft),
            -(bouncerBounds.top - host.paddingTop))
        drawStackedMorph(canvas, p)
        canvas.restoreToCount(save)
        if (DEBUG_BOUNCER) dumpBouncer(text, p, host, canvas)
        return true
    }

    private fun hostChain(host: View): String {
        val chain = StringBuilder()
        var view: View? = host
        while (view != null) {
            chain.append(view.javaClass.simpleName).append("(a=").append(view.alpha)
                .append(",t=").append(view.translationY).append(",v=")
                .append(view.visibility).append(") ")
            view = view.parent as? View
        }
        return chain.toString()
    }

    private fun dumpBouncer(text: String, p: Float, host: View, canvas: Canvas) {
        val now = System.currentTimeMillis()
        if (now - lastBouncerDump < 3000L) return
        lastBouncerDump = now
        val clip = android.graphics.Rect()
        canvas.getClipBounds(clip)
        Log.d(TAG, "draw text=$text p=$p host=$hostBounds clip=$clip size=$compactTextSize " +
            "color=${Integer.toHexString(fillPaint.color)} alpha=${host.alpha} " +
            "shown=${host.isShown} backdrop=${backdrop != null} " +
            "start=$compactStartX base=$compactBaseline chain=${hostChain(host)}")
        runCatching {
            val w = hostBounds.width().toInt().coerceAtLeast(1)
            val h = hostBounds.height().toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            canvas.translate(-(bouncerBounds.left - host.paddingLeft),
                -(bouncerBounds.top - host.paddingTop))
            drawStackedMorph(canvas, p)
            val file = java.io.File(context.cacheDir, "petal-bouncer-clock.png")
            java.io.FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            Log.d(TAG, "dumped $file")
        }.onFailure { Log.w(TAG, "dump failed", it) }
    }

    private fun refreshBouncerTime() {
        val second = System.currentTimeMillis() / 1000L
        if (lastCompactSecond != second) {
            updateTime(invalidateView = false)
            lastCompactSecond = second
        }
    }

    private fun measureCompactClock(compactStyle: Int, bounds: RectF = bouncerBounds): String {
        val text = "$hourText:$minuteText"
        fillPaint.textSize = 100f
        val probe = textBounds(text)
        val maxSize = 44f * resources.displayMetrics.scaledDensity
        val bleed = when (compactStyle) {
            STYLE_NEON -> 0.56f
            STYLE_GOLD -> 0.28f
            STYLE_MONOLITH -> 0.18f
            else -> 0.08f
        }
        val size = minOf(maxSize,
            bounds.width() / (probe.width() / 100f + bleed).coerceAtLeast(0.01f),
            bounds.height() / (probe.height() / 100f + bleed).coerceAtLeast(0.01f))
        setTimeTextSize(size)
        val glyphs = textBounds(text)
        compactTextSize = size
        compactStartX = bounds.centerX() - glyphs.centerX()
        compactBaseline = bounds.centerY() - glyphs.centerY()
        return text
    }

    private fun setTimeTextSize(size: Float) {
        fillPaint.textSize = size
        strokePaint.textSize = size
        strokePaint.strokeWidth = size * 0.035f
    }

    private fun digitOffset(text: String, index: Int): Float {
        val tracking = if (style == STYLE_VOGUE) fillPaint.textSize * 0.18f else 0f
        return fillPaint.measureText(text, 0, index) + tracking * index -
            tracking * (text.length - 1) / 2f
    }

    private fun recordExpandedRun(index: Int, x: Float, baseline: Float) {
        expandedRunX[index] = x
        expandedRunY[index] = baseline
    }

    private fun morphValue(start: Float, end: Float, progress: Float): Float = when {
        progress <= 0f -> start
        progress >= 1f -> end
        else -> start + (end - start) * progress
    }

    // Transform the original runs, including their material coordinate space. Splitting
    // them into glyphs changes kerning, gradients and the wallpaper inside glass styles.
    internal fun drawStackedMorph(
        canvas: Canvas,
        progress: Float,
        targetBounds: RectF = bouncerBounds,
    ) {
        val compactStyle = compactStyleFor(style) ?: return
        if (targetBounds.isEmpty) return
        val p = progress.coerceIn(0f, 1f)
        refreshBouncerTime()
        drawExpandedClock(null)
        val text = measureCompactClock(compactStyle, targetBounds)
        if (expandedTextSize <= 0f) return
        val runScale = morphValue(1f, compactTextSize / expandedTextSize, p)
        val minuteTargetX = compactStartX + digitOffset(text, 3)
        fun drawRun(run: String, index: Int, targetX: Float) {
            val sourceX = expandedRunX[index]
            val sourceY = expandedRunY[index]
            val x = morphValue(sourceX, targetX, p)
            val y = morphValue(sourceY, compactBaseline, p)
            val save = canvas.save()
            canvas.translate(x, y)
            canvas.scale(runScale, runScale)
            canvas.translate(-sourceX, -sourceY)
            setTimeTextSize(expandedTextSize)
            drawStyled(canvas, run, sourceX, sourceY, style)
            canvas.restoreToCount(save)
        }
        drawRun(if (hasExpandedColon) "$hourText:" else hourText, 0, compactStartX)
        drawRun(minuteText, 1, minuteTargetX)
        if (!hasExpandedColon) {
            val colonAlpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
            if (colonAlpha > 0f) {
                setTimeTextSize(compactTextSize)
                val save = canvas.saveLayerAlpha(null, (255f * colonAlpha).toInt())
                drawStyled(canvas, ":", compactStartX + digitOffset(text, 2),
                    compactBaseline, compactStyle)
                canvas.restoreToCount(save)
            }
        }
        if (showDate && p < 1f) {
            val alpha = datePaint.alpha
            datePaint.alpha = (alpha * (1f - p)).toInt()
            canvas.drawText(dateText, expandedDateX, expandedDateY, datePaint)
            datePaint.alpha = alpha
        }
    }

    fun setContentBottom(bottom: Int) {
        if (contentBottom == bottom) return
        contentBottom = bottom
        invalidate()
    }

    private var hourText = ""
    private var minuteText = ""
    private var dateText = ""

    private var tiltX = 0f
    private var tiltY = 0f

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        id = R.id.petal_depth_clock
        elevation = 90f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        twentyFourHour = DateFormat.is24HourFormat(context)
        // feed the clock rubik, shrug if the resource is missing
        runCatching {
            org.petalos.config.DepthClockMaterials.setBaseTypeface(
                context.resources.getFont(R.font.petal_rubik)
            )
        }
        applyTypeface(style)
    }

    // every style gets its own font
    private fun applyTypeface(forStyle: Int) {
        val face =
            when (forStyle) {
                in org.petalos.config.DepthClockMaterials.HEAVY..org.petalos.config.DepthClockMaterials.LAST ->
                    org.petalos.config.DepthClockMaterials.typeface(forStyle)
                STYLE_BLOCKBUSTER, STYLE_MONOLITH ->
                    org.petalos.config.DepthClockMaterials.weighted(900, false)
                STYLE_VOGUE -> org.petalos.config.DepthClockMaterials.weighted(400, true)
                STYLE_GOLD -> org.petalos.config.DepthClockMaterials.weighted(700, true)
                STYLE_NEON -> org.petalos.config.DepthClockMaterials.weighted(300, false)
                else -> org.petalos.config.DepthClockMaterials.weighted(700, false)
            }
        fillPaint.typeface = face
        strokePaint.typeface = face
        datePaint.typeface = org.petalos.config.DepthClockMaterials.weighted(500, false)
    }

    fun setStyle(newStyle: Int) {
        style = newStyle.coerceIn(0, org.petalos.config.DepthClockMaterials.LAST)
        updateColors()
        applyTypeface(style)
        invalidate()
    }

    // vertical placement
    fun setAnchor(fraction: Float) {
        anchor = fraction.coerceIn(0.05f, 0.85f)
        invalidate()
    }

    fun setAnchorX(fraction: Float) {
        anchorX = fraction.coerceIn(0.1f, 0.9f)
        invalidate()
    }

    // how big the digits get
    fun setScale(factor: Float) {
        scale = factor.coerceIn(0.6f, 1.6f)
        invalidate()
    }

    fun setStackVertical(stackVertical: Boolean) {
        vertical = stackVertical
        invalidate()
    }

    fun setAccentColor(color: Int) {
        accentColor = color
        updateColors()
        invalidate()
    }

    fun setDateEnabled(enabled: Boolean) {
        showDate = enabled
        invalidate()
    }

    // clock only follows a fraction of the tilt
    fun setSubjectTilt(px: Float, py: Float) {
        val nx = px * PARALLAX_RATE
        val ny = py * PARALLAX_RATE
        if (tiltX != nx || tiltY != ny) {
            tiltX = nx
            tiltY = ny
            invalidate()
        }
    }

    fun startTicking() {
        updateTime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun stopTicking() {
        handler.removeCallbacks(ticker)
    }

    private fun updateTime(invalidateView: Boolean = true) {
        // boot's GMT can stick around in this damn calendar
        calendar.timeZone = TimeZone.getDefault()
        calendar.timeInMillis = System.currentTimeMillis()
        twentyFourHour = DateFormat.is24HourFormat(context)
        val h = if (twentyFourHour) calendar.get(Calendar.HOUR_OF_DAY)
        else {
            val h12 = calendar.get(Calendar.HOUR)
            if (h12 == 0) 12 else h12
        }
        hourText = String.format(Locale.US, "%02d", h)
        minuteText = String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE))
        dateText = DateFormat.format(
            context.getString(R.string.petal_depth_clock_date_format), calendar
        ).toString()
        if (invalidateView) invalidate()
    }

    private fun updateColors() {
        val base =
            when {
                accentColor != Color.TRANSPARENT -> accentColor
                style == STYLE_BLOCKBUSTER -> Color.rgb(255, 59, 48)
                style == STYLE_NEON -> Color.rgb(155, 232, 255)
                style == STYLE_GOLD -> Color.rgb(255, 201, 77)
                else -> Color.WHITE
            }
        fillPaint.color = base
        strokePaint.color = base
        datePaint.color = Color.argb(210, Color.red(base), Color.green(base), Color.blue(base))
    }

    // stretch across the keyguard root
    fun attachToRoot(root: android.view.ViewGroup) {
        val cl = root as androidx.constraintlayout.widget.ConstraintLayout
        // must be the stable res id or the blueprint binder wipes us
        id = R.id.petal_depth_clock
        val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }
        cl.addView(this, lp)
    }

    private val digitSpacing: Float
        get() = fillPaint.textSize * 0.12f

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        if (bouncerProgress <= 0f) {
            drawExpandedClock(canvas)
            return
        }
        if (bouncerProgress >= 1f) return
        val save = canvas.saveLayerAlpha(null, (255f * (1f - bouncerProgress)).toInt())
        if (!bouncerBounds.isEmpty) {
            drawStackedMorph(canvas, bouncerProgress)
        } else {
            // No host geometry yet; keep the expanded clock fading while it arrives.
            drawExpandedClock(canvas)
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        bouncerAnimator?.cancel()
        bouncerAnimator = null
        bouncerHost = null
        bouncerProgress = 0f
        stopTicking()
        super.onDetachedFromWindow()
    }

    private fun drawExpandedClock(canvas: Canvas?) {

        val rawHour = hourText
        val rawMinute = minuteText
        val square = org.petalos.config.DepthClockMaterials.isSquare(style)
        // square styles stack, hours over minutes
        val stack = vertical || square
        val useColon = !stack
        val hour = if (useColon) "$rawHour:" else rawHour
        val minute = rawMinute

        // size digits to fill the width
        val probe = Paint(fillPaint).apply { textSize = 100f }
        val unitWidth = if (stack) probe.measureText("00") else probe.measureText("00:00")
        val targetWidth = width * (when {
            square -> 0.92f
            stack -> 0.62f
            else -> 0.78f
        }) * scale
        val textSize = 100f * targetWidth / unitWidth.coerceAtLeast(1f)
        // cap the height so it fits
        val heightCap = height * 0.5f * scale / (when {
            square -> 2.1f
            stack -> 2.3f
            else -> 1.4f
        })
        val safeTop = 72f * resources.displayMetrics.density
        val bottom = minOf(height.toFloat(), contentBottom.toFloat())
        val available = (bottom - safeTop).coerceAtLeast(1f)
        val mediaCap = if (contentBottom == Int.MAX_VALUE) Float.MAX_VALUE
            else available / if (stack) 2.4f else 1.4f
        val finalTextSize = minOf(textSize, heightCap, mediaCap)
        expandedTextSize = finalTextSize

        setTimeTextSize(finalTextSize)
        datePaint.textSize = finalTextSize * 0.12f

        // dx/dy from the smoothed tilt
        val dx = tiltX
        val dy = tiltY


        val hh = textBounds(hour)
        val mm = textBounds(minute)
        val centerX = width * anchorX + dx
        if (stack) {
            val lineStep = finalTextSize * 1.25f
            val dateOffset = hh.top - datePaint.textSize * 0.5f - datePaint.fontMetrics.bottom
            val groupTop = if (showDate) minOf(hh.top, dateOffset + datePaint.fontMetrics.top) else hh.top
            val groupBottom = lineStep + mm.bottom
            val desiredBaseline = height * anchor - (hh.top + lineStep + mm.bottom) / 2f + dy
            val baseline = if (contentBottom == Int.MAX_VALUE) desiredBaseline else
                desiredBaseline.coerceIn(safeTop - groupTop,
                    maxOf(safeTop - groupTop, bottom - groupBottom))
            expandedBounds.set(centerX - maxOf(hh.width(), mm.width()) / 2f,
                baseline + groupTop, centerX + maxOf(hh.width(), mm.width()) / 2f,
                baseline + groupBottom)
            recordExpandedRun(0, centerX - hh.centerX(), baseline)
            recordExpandedRun(1, centerX - mm.centerX(), baseline + lineStep)
            expandedDateX = centerX - datePaint.measureText(dateText) / 2f
            expandedDateY = baseline + dateOffset
            hasExpandedColon = false
            if (canvas != null) {
                drawStyled(canvas, hour, centerX - hh.centerX(), baseline, style)
                drawStyled(canvas, minute, centerX - mm.centerX(), baseline + lineStep, style)
                if (showDate) canvas.drawText(dateText, expandedDateX, expandedDateY, datePaint)
            }
        } else {
            val minuteX = fillPaint.measureText(hour) + digitSpacing
            val left = minOf(hh.left, minuteX + mm.left)
            val right = maxOf(hh.right, minuteX + mm.right)
            val startX = centerX - (left + right) / 2f
            val desiredBaseline = height * anchor -
                (minOf(hh.top, mm.top) + maxOf(hh.bottom, mm.bottom)) / 2f + dy
            val groupTop = minOf(hh.top, mm.top)
            val groupBottom = if (showDate) maxOf(hh.bottom, mm.bottom,
                datePaint.textSize * 1.9f + datePaint.fontMetrics.bottom) else maxOf(hh.bottom, mm.bottom)
            val baseline = if (contentBottom == Int.MAX_VALUE) desiredBaseline else
                desiredBaseline.coerceIn(safeTop - groupTop,
                    maxOf(safeTop - groupTop, bottom - groupBottom))
            expandedBounds.set(startX + left, baseline + groupTop,
                startX + right, baseline + groupBottom)
            recordExpandedRun(0, startX, baseline)
            recordExpandedRun(1, startX + minuteX, baseline)
            expandedDateX = centerX - datePaint.measureText(dateText) / 2f
            expandedDateY = baseline + datePaint.textSize * 1.9f
            hasExpandedColon = true
            if (canvas != null) {
                drawStyled(canvas, hour, startX, baseline, style)
                drawStyled(canvas, minute, startX + minuteX, baseline, style)
                if (showDate) canvas.drawText(dateText, expandedDateX, expandedDateY, datePaint)
            }
        }
    }

    private fun textBounds(text: String): android.graphics.RectF {
        val bounds = android.graphics.Rect()
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        return android.graphics.RectF(bounds).apply {
            if (style == STYLE_VOGUE) {
                val extra = fillPaint.textSize * 0.18f * (text.length - 1).coerceAtLeast(0)
                left -= extra / 2f
                right += extra / 2f
            }
        }
    }

    // draw a single run in the given style
    private fun drawStyled(canvas: Canvas, text: String, x: Float, y: Float, forStyle: Int) {
        if (materials.draw(canvas, text, x, y, forStyle, fillPaint,
                if (forStyle == org.petalos.config.DepthClockMaterials.LIQUID)
                    liquidBackdrop ?: backdrop else backdrop, width, height)) return
        when (forStyle) {
            STYLE_OUTLINE -> {
                drawSoftShadow(canvas, text, x, y)
                canvas.drawText(text, x, y, strokePaint)
            }
            STYLE_GRADIENT -> {
                val w = fillPaint.measureText(text)
                fillPaint.shader = LinearGradient(
                    0f, y - fillPaint.textSize, 0f, y,
                    Color.argb(255, Color.red(fillPaint.color), Color.green(fillPaint.color),
                        Color.blue(fillPaint.color)),
                    Color.argb(90, Color.red(fillPaint.color), Color.green(fillPaint.color),
                        Color.blue(fillPaint.color)),
                    Shader.TileMode.CLAMP
                )
                canvas.drawText(text, x, y, fillPaint)
                fillPaint.shader = null
            }
            STYLE_HYBRID -> {
                // flip fill and outline every other char
                var cx = x
                var solid = true
                for (ch in text) {
                    val chStr = ch.toString()
                    val cw = fillPaint.measureText(chStr)
                    if (ch == ' ') { cx += cw; continue }
                    if (solid) canvas.drawText(chStr, cx, y, fillPaint)
                    else canvas.drawText(chStr, cx, y, strokePaint)
                    cx += cw
                    solid = !solid
                }
            }
            STYLE_BLOCKBUSTER -> drawBlockbuster(canvas, text, x, y)
            STYLE_NEON -> drawNeon(canvas, text, x, y)
            STYLE_GOLD -> drawGoldOutline(canvas, text, x, y)
            STYLE_VOGUE -> drawEditorial(canvas, text, x, y)
            STYLE_MONOLITH -> drawMonolith(canvas, text, x, y)
            else -> {
                drawSoftShadow(canvas, text, x, y)
                canvas.drawText(text, x, y, fillPaint)
            }
        }
    }

    // poster look with a hard split
    private fun drawBlockbuster(canvas: Canvas, text: String, x: Float, y: Float) {
        fillPaint.shader = LinearGradient(
            0f, y - fillPaint.textSize, 0f, y + fillPaint.textSize * 0.1f,
            intArrayOf(fillPaint.color, fillPaint.color, shade(fillPaint.color, 0.45f)),
            floatArrayOf(0f, 0.58f, 0.58f),
            Shader.TileMode.CLAMP
        )
        drawSoftShadow(canvas, text, x, y)
        canvas.drawText(text, x, y, fillPaint)
        fillPaint.shader = null
    }

    // fat glow under a thin bright core
    private fun drawNeon(canvas: Canvas, text: String, x: Float, y: Float) {
        glowPaint.typeface = fillPaint.typeface
        glowPaint.textSize = fillPaint.textSize
        glowPaint.color = fillPaint.color
        glowPaint.strokeWidth = fillPaint.textSize * 0.02f
        glowPaint.setShadowLayer(fillPaint.textSize * 0.28f, 0f, 0f,
            withAlpha(fillPaint.color, 180))
        canvas.drawText(text, x, y, glowPaint)
        canvas.drawText(text, x, y, glowPaint)
        // bright core last
        glowPaint.setShadowLayer(fillPaint.textSize * 0.08f, 0f, 0f, Color.WHITE)
        canvas.drawText(text, x, y, glowPaint)
        glowPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    // serif outline with a warm glow
    private fun drawGoldOutline(canvas: Canvas, text: String, x: Float, y: Float) {
        glowPaint.typeface = strokePaint.typeface
        glowPaint.textSize = strokePaint.textSize
        glowPaint.color = strokePaint.color
        glowPaint.strokeWidth = strokePaint.strokeWidth
        glowPaint.setShadowLayer(strokePaint.textSize * 0.14f, 0f, 0f,
            withAlpha(strokePaint.color, 150))
        canvas.drawText(text, x, y, glowPaint)
        glowPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        canvas.drawText(text, x, y, strokePaint)
    }

    // serif with wide tracking
    private fun drawEditorial(canvas: Canvas, text: String, x: Float, y: Float) {
        val spacing = fillPaint.textSize * 0.18f
        val plain = fillPaint.measureText(text)
        val total = if (text.length > 1) plain + spacing * (text.length - 1) else plain
        var cx = x + (plain - total) / 2f
        val savedColor = fillPaint.color
        fillPaint.color = Color.argb(70, 0, 0, 0)
        var sx = cx
        for (ch in text) {
            val s = ch.toString()
            canvas.drawText(
                s, sx + fillPaint.textSize * 0.02f, y + fillPaint.textSize * 0.03f, fillPaint
            )
            sx += fillPaint.measureText(s) + spacing
        }
        fillPaint.color = savedColor
        for (ch in text) {
            val s = ch.toString()
            canvas.drawText(s, cx, y, fillPaint)
            cx += fillPaint.measureText(s) + spacing
        }
    }

    // fake extrude, just offset copies
    private fun drawMonolith(canvas: Canvas, text: String, x: Float, y: Float) {
        val depth = fillPaint.textSize * 0.09f
        val steps = 14
        depthPaint.typeface = fillPaint.typeface
        depthPaint.textSize = fillPaint.textSize
        depthPaint.color = shade(fillPaint.color, 0.22f)
        for (i in steps downTo 1) {
            val t = i / steps.toFloat()
            canvas.drawText(text, x + depth * t, y + depth * t, depthPaint)
        }
        canvas.drawText(text, x, y, fillPaint)
    }

    private fun shade(color: Int, factor: Float): Int =
        Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255),
        )

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun drawSoftShadow(canvas: Canvas, text: String, x: Float, y: Float) {
        val savedColor = fillPaint.color
        fillPaint.color = Color.argb(70, 0, 0, 0)
        canvas.drawText(text, x + fillPaint.textSize * 0.02f, y + fillPaint.textSize * 0.03f,
            fillPaint)
        fillPaint.color = savedColor
    }
}
