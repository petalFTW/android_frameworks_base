/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Wallpaper's in another window, so sample the actual paper here.
class PetalMediaRibbonBackdrop(private val view: View) : View.OnAttachStateChangeListener {
    private val manager = WallpaperManager.getInstance(view.context)
    private val node = RenderNode("Petal ribbon wallpaper")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val location = IntArray(2)
    private var bitmap: Bitmap? = null
    private var wallpaperId = -1
    private var scope: CoroutineScope? = null
    private var loading: Job? = null
    private var enabled = false
    private val geometry = IntArray(6)
    private var geometryValid = false
    private var retryAttempts = 0
    private val listener = WallpaperManager.OnColorsChangedListener { _, _ -> reload() }

    init {
        view.addOnAttachStateChangeListener(this)
        val radius = 28f * view.resources.displayMetrics.density
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value && view.isAttachedToWindow) start() else stop()
    }

    fun destroy() {
        enabled = false
        stop()
        view.removeOnAttachStateChangeListener(this)
        bitmap = null
        wallpaperId = -1
        retryAttempts = 0
        geometryValid = false
        node.discardDisplayList()
    }

    private fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        retryAttempts = 0
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        reload()
    }

    private fun stop() {
        if (scope == null) return
        manager.removeOnColorsChangedListener(listener)
        scope?.cancel()
        scope = null
        loading = null
        // Host swaps don't need another giant wallpaper decode.
    }

    private fun reload() {
        loading?.cancel()
        loading = scope?.launch {
            val loaded = withContext(Dispatchers.IO) { loadPaper(bitmap, wallpaperId) }
            val fresh = loaded.second
            if (fresh != null) {
                retryAttempts = 0
                if (loaded.first > 0) wallpaperId = loaded.first
                if (bitmap !== fresh) {
                    bitmap = fresh
                    geometryValid = false
                    view.invalidate()
                }
            } else if (enabled && retryAttempts < 4) {
                // wallpaper swap caught us mid-write; never bin the last good paper
                retryAttempts++
                val backoff = 400L * retryAttempts
                loading = scope?.launch {
                    delay(backoff)
                    reload()
                }
            }
        }
    }

    // Lock paper first, system paper as the fallback; a matching id skips the big decode.
    private fun loadPaper(cached: Bitmap?, cachedId: Int): Pair<Int, Bitmap?> = try {
        val lockId = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
        if (cached != null && lockId > 0 && lockId == cachedId) {
            lockId to cached
        } else manager.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use {
            lockId to BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null,
                BitmapFactory.Options().apply { inSampleSize = 2 })
        } ?: run {
            val sysId = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
            if (cached != null && sysId > 0 && sysId == cachedId) {
                sysId to cached
            } else manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use {
                sysId to BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null,
                    BitmapFactory.Options().apply { inSampleSize = 2 })
            } ?: (-1 to null)
        }
    } catch (error: IOException) {
        Log.w("PetalRibbon", "Wallpaper read failed", error)
        -1 to null
    } catch (error: SecurityException) {
        Log.w("PetalRibbon", "Wallpaper access failed", error)
        -1 to null
    }

    fun draw(canvas: Canvas) {
        val paper = bitmap ?: return
        if (!canvas.isHardwareAccelerated || view.width == 0 || view.height == 0) return
        view.getLocationOnScreen(location)
        val root = view.rootView
        if (!geometryValid || geometry[0] != location[0] || geometry[1] != location[1] ||
            geometry[2] != view.width || geometry[3] != view.height ||
            geometry[4] != root.width || geometry[5] != root.height) {
            geometryValid = true
            geometry[0] = location[0]
            geometry[1] = location[1]
            geometry[2] = view.width
            geometry[3] = view.height
            geometry[4] = root.width
            geometry[5] = root.height
            val pad = (56f * view.resources.displayMetrics.density).toInt()
            node.setPosition(-pad, -pad, view.width + pad, view.height + pad)
            val recording = node.beginRecording()
            recording.translate(pad.toFloat() - location[0], pad.toFloat() - location[1])
            val scale = maxOf(root.width.toFloat() / paper.width, root.height.toFloat() / paper.height)
            val w = paper.width * scale
            val h = paper.height * scale
            val x = (root.width - w) / 2f
            val y = (root.height - h) / 2f
            recording.drawBitmap(paper, null, RectF(x, y, x + w, y + h), paint)
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }

    override fun onViewAttachedToWindow(view: View) {
        if (enabled) start()
    }

    override fun onViewDetachedFromWindow(view: View) = stop()
}
