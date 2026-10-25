package com.android.systemui.island.signal

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.island.Cluster
import com.android.systemui.island.Form
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.SignalKind
import com.android.systemui.island.presenter.NotificationPayload
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class CalendarSignalSource @Inject constructor(
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    private val router: SignalRouter,
) {
    private data class Event(
        val id: Long,
        val begin: Long,
        val end: Long,
        val title: String,
        val location: String?,
    ) {
        val signalId get() = "calendar:$id:$begin"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val shown = mutableSetOf<String>()
    private var started = false
    private var generation = 0
    private val refresh = Runnable { refresh() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = requestRefresh()
    }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = requestRefresh()
    }

    fun start() {
        if (started) return
        started = true
        context.contentResolver.registerContentObserver(
            CalendarContract.Events.CONTENT_URI, true, observer,
        )
        context.contentResolver.registerContentObserver(
            CalendarContract.Instances.CONTENT_URI, true, observer,
        )
        context.registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        requestRefresh()
    }

    private fun requestRefresh() {
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    private fun refresh() {
        val version = ++generation
        scope.launch(Dispatchers.IO) {
            val events = queryEvents()
            withContext(Dispatchers.Main) {
                if (version == generation) update(events)
            }
        }
    }

    private fun queryEvents(): List<Event> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED) return emptyList()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + LOOK_AHEAD_MS)
        }.build()
        return runCatching {
            val events = mutableListOf<Event>()
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.ALL_DAY,
                ),
                "${CalendarContract.Instances.ALL_DAY}=0",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val begin = cursor.getLong(1)
                    val end = cursor.getLong(2)
                    if (end > now) {
                        events.add(Event(cursor.getLong(0), begin, end,
                            cursor.getString(3).orEmpty(), cursor.getString(4)))
                    }
                }
            }
            events
        }.onFailure { Log.w(TAG, "Calendar lookup failed", it) }.getOrDefault(emptyList())
    }

    private fun update(events: List<Event>) {
        val now = System.currentTimeMillis()
        val current = events.map { it.signalId }.toSet()
        shown.filter { it !in current }.forEach(router::removeSignal)
        shown.retainAll(current)
        events.filter { it.begin - UPCOMING_WINDOW_MS <= now && it.end > now }.forEach { event ->
            if (shown.add(event.signalId)) router.emit(makeSignal(event))
        }
        val next = events.flatMap { event ->
            listOf(event.begin - UPCOMING_WINDOW_MS, event.end)
        }.filter { it > now }.minOrNull()
        handler.removeCallbacks(refresh)
        if (next != null) handler.postDelayed(refresh, (next - now).coerceAtLeast(1000L))
    }

    private fun makeSignal(event: Event): IslandSignal {
        val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(
            CalendarContract.Events.CONTENT_URI, event.id,
        )).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val action = PendingIntent.getActivity(context, event.signalId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return IslandSignal(
            id = event.signalId,
            kind = SignalKind.NOTIFICATION,
            cluster = Cluster.LEFT,
            priority = 45,
            initialForm = Form.CAPSULE,
            ttlMs = 0L,
            payload = NotificationPayload(
                key = event.signalId,
                packageName = context.packageName,
                appLabel = "Calendar",
                sender = null,
                title = event.title,
                text = event.location,
                subText = null,
                smallIcon = Icon.createWithResource(context, android.R.drawable.ic_menu_my_calendar),
                largeIcon = null,
                iconColor = Color.WHITE,
                contentIntent = action,
                actions = emptyList(),
                progressCurrent = 0,
                progressMax = 0,
                progressIndeterminate = false,
                whenMillis = event.begin,
            ),
        )
    }

    companion object {
        private const val TAG = "IslandCalendar"
        private const val UPCOMING_WINDOW_MS = 15 * 60 * 1000L
        private const val LOOK_AHEAD_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
