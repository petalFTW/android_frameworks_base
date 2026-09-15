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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.SignalStrength
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.island.Cluster
import com.android.systemui.island.IslandGeometry
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import javax.inject.Inject

// last three notif icons for the mini row
@SysUISingleton
class IslandMiniNotifSource @Inject constructor(pipeline: NotifPipeline) {

    var icons: List<android.graphics.drawable.Icon> = emptyList()
        private set
    var onChanged: (() -> Unit)? = null

    init {
        pipeline.addOnAfterRenderListListener { entries ->
            icons = entries.asSequence()
                .mapNotNull { entry ->
                    when (entry) {
                        is NotificationEntry -> entry
                        is GroupEntry -> entry.representativeEntry
                        else -> null
                    }
                }
                .mapNotNull { it.sbn.notification.smallIcon }
                .take(MAX_ICONS)
                .toList()
            onChanged?.invoke()
        }
    }

    private companion object {
        const val MAX_ICONS = 3
    }
}

// tiny status bar inside the blob, one slot per glyph
class IslandMiniStatusView(
    private val context: Context,
    private val cluster: Cluster,
    geometry: IslandGeometry,
    private val notifSource: IslandMiniNotifSource?,
) : View(context) {

    private val handler = Handler(Looper.getMainLooper())

    private var signalLevel = -1
    private var wifiLevel = -1
    private var wifiConnected = false
    private var btOn = false
    private var btConnected = 0
    private var batteryLevel = -1
    private var batteryCharging = false
    private var notifDrawables: List<Drawable> = emptyList()
    private var timeText = ""

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        color = GLYPH
    }
    private val btPath = Path()

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateTime()
            invalidate()
            handler.postDelayed(this, 10_000L)
        }
    }

    private val telephonyCallback =
        object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.ServiceStateListener {
            override fun onSignalStrengthsChanged(ss: SignalStrength) {
                signalLevel = ss.level
                invalidate()
            }
            override fun onServiceStateChanged(state: ServiceState) {
                if (state.state != ServiceState.STATE_IN_SERVICE) signalLevel = 0
                invalidate()
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
                        batteryCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                            BatteryManager.BATTERY_STATUS_CHARGING
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        wifiConnected = intent.getParcelableExtra<NetworkInfo>(
                                WifiManager.EXTRA_NETWORK_INFO)?.isConnected == true
                        readWifiLevel()
                    }
                    else -> readWifiLevel()
                }
                invalidate()
            }
        }

    init {
        if (cluster == Cluster.LEFT) notifSource?.onChanged = { loadNotifIcons() }
        updateTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        runCatching { context.registerReceiver(receiver, filter) }
        runCatching {
            context.getSystemService(TelephonyManager::class.java)?.registerTelephonyCallback(
                context.mainExecutor, telephonyCallback)
        }
        readWifiLevel()
        refreshBt()
        loadNotifIcons()
        updateTime()
        handler.post(tickRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(receiver) }
        runCatching {
            context.getSystemService(TelephonyManager::class.java)
                ?.unregisterTelephonyCallback(telephonyCallback)
        }
        handler.removeCallbacks(tickRunnable)
    }

    private fun readWifiLevel() {
        wifiLevel = runCatching {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return
            if (wifi.isWifiEnabled && wifiConnected) {
                wifi.connectionInfo?.let { wifi.calculateSignalLevel(it.rssi) } ?: -1
            } else -1
        }.getOrDefault(-1)
        invalidate()
    }

    private fun refreshBt() {
        val result = runCatching {
            val bm = context.getSystemService(BluetoothManager::class.java)
            val adapter = bm?.adapter
            btOn = adapter?.isEnabled == true
            if (btOn) {
                btConnected = bm.getConnectedDevices(BluetoothProfile.GATT).size +
                    bm.getConnectedDevices(BluetoothProfile.HEADSET).size +
                    bm.getConnectedDevices(BluetoothProfile.A2DP).size
            } else 0
        }
        if (result.isFailure) {
            btOn = false
            btConnected = 0
        }
        invalidate()
    }

    private fun loadNotifIcons() {
        if (cluster != Cluster.LEFT) return
        notifDrawables = notifSource?.icons?.mapNotNull { icon ->
            runCatching {
                icon.loadDrawable(context)?.apply {
                    setTint(GLYPH)
                    setBounds(0, 0, iconPx(), iconPx())
                }
            }.getOrNull()
        }.orEmpty()
        invalidate()
    }

    private fun updateTime() {
        if (cluster != Cluster.LEFT) return
        timeText = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun iconPx() = dp(ICON_DP).toInt()

    // right-side glyphs and the width each takes
    private fun rightSlots(): List<Float> {
        val slots = ArrayList<Float>(4)
        if (signalLevel >= 0) slots.add(dp(SIGNAL_DP))
        if (wifiConnected) slots.add(dp(WIFI_DP))
        if (btOn) slots.add(dp(BT_DP))
        if (batteryLevel >= 0) slots.add(dp(BATTERY_DP))
        return slots
    }

    private fun leftWidth(): Float {
        var w = textPaint.measureText(timeText)
        if (notifDrawables.isNotEmpty()) {
            w += dp(GAP_DP) + notifDrawables.size * dp(ICON_DP) +
                (notifDrawables.size - 1) * dp(GAP_DP)
        }
        return w
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = iconPx()
        val w = if (cluster == Cluster.RIGHT) {
            val slots = rightSlots()
            if (slots.isEmpty()) dp(ICON_DP)
            else slots.sum() + dp(GAP_DP) * (slots.size - 1)
        } else {
            textPaint.textSize = dp(9.5f)
            leftWidth()
        }
        setMeasuredDimension(w.toInt().coerceAtLeast(h), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = GLYPH
        var x = 0f
        if (cluster == Cluster.RIGHT) {
            if (signalLevel >= 0) {
                drawSignal(canvas, x, dp(SIGNAL_DP)); x += dp(SIGNAL_DP + GAP_DP)
            }
            if (wifiConnected) {
                drawWifi(canvas, x, dp(WIFI_DP)); x += dp(WIFI_DP + GAP_DP)
            }
            if (btOn) {
                drawBt(canvas, x, dp(BT_DP)); x += dp(BT_DP + GAP_DP)
            }
            if (batteryLevel >= 0) {
                drawBattery(canvas, x, dp(BATTERY_DP))
            }
        } else {
            textPaint.textSize = dp(9.5f)
            canvas.drawText(timeText, x, iconPx() - dp(1.5f), textPaint)
            x += textPaint.measureText(timeText) + dp(GAP_DP)
            for (d in notifDrawables) {
                canvas.save()
                canvas.translate(x, 0f)
                d.draw(canvas)
                canvas.restore()
                x += dp(ICON_DP + GAP_DP)
            }
        }
    }

    private fun drawSignal(canvas: Canvas, x: Float, slotW: Float) {
        val barW = dp(1.8f)
        val gap = dp(1.4f)
        val barsW = 3f * (barW + gap) + barW
        val left0 = x + (slotW - barsW) / 2f
        val maxH = iconPx().toFloat()
        paint.style = Paint.Style.FILL
        for (i in 0 until 4) {
            val h = maxH * (0.35f + 0.2167f * i)
            val left = left0 + i * (barW + gap)
            paint.alpha = if (i < signalLevel + 1) 255 else 70
            canvas.drawRoundRect(
                RectF(left, maxH - h, left + barW, maxH), barW / 2f, barW / 2f, paint)
        }
        paint.alpha = 255
    }

    private fun drawWifi(canvas: Canvas, x: Float, slotW: Float) {
        val cx = x + slotW / 2f
        val cy = iconPx() * 0.92f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.6f)
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(cx - dp(6.5f), cy - dp(6.5f), cx + dp(6.5f), cy + dp(6.5f),
                -135f, 90f, false, paint)
        canvas.drawArc(cx - dp(3.6f), cy - dp(3.6f), cx + dp(3.6f), cy + dp(3.6f),
                -125f, 70f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy - dp(1.2f), dp(1.2f), paint)
    }

    private fun drawBt(canvas: Canvas, x: Float, slotW: Float) {
        val cx = x + slotW / 2f
        val top = dp(1f)
        val bot = iconPx() - dp(1f)
        val mid = (top + bot) / 2f
        val w = iconPx() * 0.38f
        btPath.reset()
        btPath.moveTo(cx, top)
        btPath.lineTo(cx + w, mid - dp(2.2f))
        btPath.lineTo(cx - w, bot)
        btPath.moveTo(cx, top)
        btPath.lineTo(cx, bot)
        btPath.moveTo(cx, top)
        btPath.lineTo(cx - w, mid - dp(2.2f))
        btPath.lineTo(cx + w, bot)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.alpha = if (btConnected > 0) 255 else 140
        canvas.drawPath(btPath, paint)
        paint.alpha = 255
    }

    private fun drawBattery(canvas: Canvas, x: Float, slotW: Float) {
        val w = dp(15f)
        val h = dp(7.5f)
        val x0 = x + (slotW - (w + dp(2.2f))) / 2f
        val top = (iconPx() - h) / 2f
        val r = dp(2f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.1f)
        canvas.drawRoundRect(RectF(x0, top, x0 + w, top + h), r, r, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(RectF(x0 + w + dp(1f), top + h / 2f - dp(1.5f),
                x0 + w + dp(2.2f), top + h / 2f + dp(1.5f)), paint)
        val frac = (batteryLevel / 100f).coerceIn(0f, 1f)
        val inX = x0 + dp(1.6f)
        val inW = (w - dp(3.2f)) * frac
        paint.color = when {
            batteryCharging -> 0xFF30D158.toInt()
            batteryLevel <= 15 -> 0xFFFF453A.toInt()
            else -> GLYPH
        }
        canvas.drawRoundRect(RectF(inX, top + dp(1.6f), inX + inW, top + h - dp(1.6f)),
                dp(1f), dp(1f), paint)
        paint.color = GLYPH
    }

    private companion object {
        const val ICON_DP = 11f
        const val GAP_DP = 4.5f
        const val SIGNAL_DP = 11.4f
        const val WIFI_DP = 14.6f
        const val BT_DP = 11f
        const val BATTERY_DP = 17.5f
        val GLYPH = 0xF2FFFFFF.toInt()
    }
}
