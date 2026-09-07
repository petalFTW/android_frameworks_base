/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.text.format.Time
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import java.io.PrintWriter
import javax.inject.Inject
import org.petalos.config.PetalConfig

@SysUISingleton
class PetalScreenDithering @Inject constructor(
    @Application private val context: Context,
    @Main private val handler: Handler,
    private val userTracker: UserTracker,
) : CoreStartable {
    private val power = context.getSystemService(PowerManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val windows = context.getSystemService(WindowManager::class.java)
    private var mask: PixelMask? = null
    private var percent = PetalConfig.DEFAULT_DITHERING_PERCENT
    private var phase = 0

    private val shift = object : Runnable {
        override fun run() {
            val view = mask ?: return
            phase = (phase + 1) % 16
            view.updatePattern(percent, phase)
            handler.postDelayed(this, SHIFT_INTERVAL_MS)
        }
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private val userCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) = refresh()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refresh()
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refresh()
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refresh()
        }
    }

    override fun start() {
        for (key in arrayOf(PetalConfig.KEY_DITHERING_ENABLED,
                PetalConfig.KEY_DITHERING_BATTERY_SAVER,
                PetalConfig.KEY_DITHERING_SCHEDULE,
                PetalConfig.KEY_DITHERING_START_MIN,
                PetalConfig.KEY_DITHERING_END_MIN,
                PetalConfig.KEY_DITHERING_PERCENT)) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL)
        }
        userTracker.addCallback(userCallback) { handler.post(it) }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        displays.registerDisplayListener(displayListener, handler)
        refresh()
    }

    private fun inScheduleWindow(): Boolean {
        val now = Time()
        now.setToNow()
        val minute = now.hour * 60 + now.minute
        val start = clampMinute(Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_START_MIN, PetalConfig.DEFAULT_DITHERING_START_MIN,
            userTracker.userId))
        val end = clampMinute(Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_END_MIN, PetalConfig.DEFAULT_DITHERING_END_MIN,
            userTracker.userId))
        // Window wraps midnight when start >= end.
        return if (start == end) false
            else if (start < end) minute in start until end
            else minute >= start || minute < end
    }

    private fun refresh() {
        val enabled = Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_ENABLED, 1, userTracker.userId) != 0
        val withBatterySaver = Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_BATTERY_SAVER, 1, userTracker.userId) != 0
        val onSchedule = Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_SCHEDULE, 0, userTracker.userId) != 0
        val requested = Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_PERCENT, PetalConfig.DEFAULT_DITHERING_PERCENT,
            userTracker.userId)
        val nextPercent = if (requested in arrayOf(25, 50, 75)) requested
            else PetalConfig.DEFAULT_DITHERING_PERCENT
        val active = enabled &&
            ((withBatterySaver && power.isPowerSaveMode) ||
                (onSchedule && inScheduleWindow())) &&
            displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        if (!active) {
            handler.removeCallbacks(shift)
            mask?.let { windows.removeViewImmediate(it) }
            mask = null
            percent = nextPercent
            return
        }
        if (mask == null) {
            percent = nextPercent
            val view = PixelMask(context)
            view.updatePattern(percent, phase)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "PetalScreenDithering"
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                privateFlags = privateFlags or
                    WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                    WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
            }
            windows.addView(view, params)
            mask = view
            handler.postDelayed(shift, SHIFT_INTERVAL_MS)
        } else if (percent != nextPercent) {
            percent = nextPercent
            mask?.updatePattern(percent, phase)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val enabled = Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_ENABLED, 1, userTracker.userId) != 0
        val withBatterySaver = Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_BATTERY_SAVER, 1, userTracker.userId) != 0
        val onSchedule = Settings.System.getIntForUser(context.contentResolver,
            PetalConfig.KEY_DITHERING_SCHEDULE, 0, userTracker.userId) != 0
        pw.println("PetalScreenDithering: active=${mask != null} percent=$percent phase=$phase " +
            "enabled=$enabled batterySaver=${power.isPowerSaveMode} " +
            "withBatterySaver=$withBatterySaver schedule=$onSchedule " +
            "inWindow=${inScheduleWindow()}")
    }

    private class PixelMask(context: Context) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
        }
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        fun updatePattern(percent: Int, phase: Int) {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bitmap.density = Bitmap.DENSITY_NONE
            for (y in 0..3) {
                for (x in 0..3) {
                    // Rotate ranks so every pixel gets its turn off.
                    val rank = (RANKS[y * 4 + x] + phase) % 16
                    bitmap.setPixel(x, y, if (rank < percent * 16 / 100)
                        Color.BLACK else Color.TRANSPARENT)
                }
            }
            paint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    companion object {
        private const val SHIFT_INTERVAL_MS = 60_000L
        private val RANKS = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

        private fun clampMinute(value: Int): Int {
            return if (value in 0..24 * 60) value else 0
        }
    }
}
