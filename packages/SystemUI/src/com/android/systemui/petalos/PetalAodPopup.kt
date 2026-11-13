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

package com.android.systemui.petalos

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.doze.DozeMachine
import com.android.systemui.doze.DozeTransitionCallback
import com.android.systemui.doze.DozeTransitionListener
import com.android.systemui.island.settings.IslandSettings
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider
import javax.inject.Inject

// screen off + new notif = ride the doze pulse and draw our card on black over it.
// used to do a full wakeUp per notif, which kicked the phone out of doze ~60 times a day
@SysUISingleton
class PetalAodPopup @Inject constructor(
    @Application private val context: Context,
    private val notifPipeline: NotifPipeline,
    private val decisions: VisualInterruptionDecisionProvider,
    private val powerManager: PowerManager,
    private val windowManager: WindowManager,
    private val ambientConfig: AmbientDisplayConfiguration,
    private val userTracker: UserTracker,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val islandSettings: IslandSettings,
    private val dozeTransitions: DozeTransitionListener,
) : CoreStartable {

    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var card: View? = null
    private var pending: NotificationEntry? = null
    private var pendingAt = 0L
    private var pulsing = false

    private val listener = object : NotifCollectionListener {
        override fun onEntryAdded(entry: NotificationEntry) = post(entry)

        // whatsapp & co update the SAME notif per message and that pulses too.
        // we only listened to adds so message 2+ got the stock lineage pulse
        override fun onEntryUpdated(entry: NotificationEntry) {
            if ((entry.sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0) return
            post(entry)
        }

        private fun post(entry: NotificationEntry) {
            handler.post {
                // never let this take systemui down
                try { maybeQueue(entry) } catch (e: RuntimeException) {
                    android.util.Log.w(TAG, "popup failed", e)
                    teardown()
                }
            }
        }
    }

    private val dozeCallback = object : DozeTransitionCallback {
        override fun onDozeTransition(oldState: DozeMachine.State, newState: DozeMachine.State) {
            handler.post {
                try { onDoze(newState) } catch (e: RuntimeException) {
                    android.util.Log.w(TAG, "doze hook blew up", e)
                    teardown()
                }
            }
        }
    }

    override fun start() {
        notifPipeline.addCollectionListener(listener)
        dozeTransitions.addCallback(dozeCallback)
    }

    private fun enabled() = Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, 1) != 0

    // 0 = pure black (default, no card), 1 = follow the notification look
    private fun styled() = Settings.Secure.getInt(context.contentResolver, KEY_STYLE, 0) == 1

    // just remembers the notif. the actual pulse comes from DozeTriggers, which does the prox check
    private fun maybeQueue(entry: NotificationEntry) {
        if (!enabled()) return
        if (powerManager.isInteractive) return
        // aod already shows stuff, don't double up
        if (ambientConfig.alwaysOnEnabled(userTracker.userId)) return

        val sbn = entry.sbn
        val n = sbn.notification
        if (sbn.packageName == "android" || sbn.packageName == context.packageName) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        // summaries stay in: GROUP_ALERT_SUMMARY apps pulse on the summary and mute the kids,
        // skipping them = stock aod for every "3 new messages". decision below sorts who alerts
        if (n.category == Notification.CATEGORY_CALL || n.fullScreenIntent != null) return
        if (n.extras.getParcelable(
                Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java
            ) != null
        ) return
        // dnd, hide silent, lockscreen privacy all live in here
        if (!decisions.makeUnloggedPopupDecision(entry).shouldInterrupt) return

        pending = entry
        pendingAt = SystemClock.elapsedRealtime()
        brightUntil = pendingAt + PENDING_TTL_MS
        // second notif mid-pulse, just swap the card
        if (pulsing) show(entry)
    }

    private fun onDoze(state: DozeMachine.State) {
        when (state) {
            DozeMachine.State.DOZE_PULSING, DozeMachine.State.DOZE_PULSING_BRIGHT -> {
                pulsing = true
                val entry = pending
                // pickup/tap pulses have no fresh notif, leave those to the stock ui
                if (entry != null && SystemClock.elapsedRealtime() - pendingAt < PENDING_TTL_MS) {
                    show(entry)
                }
            }
            DozeMachine.State.DOZE_REQUEST_PULSE -> Unit
            else -> {
                pulsing = false
                pending = null
                fadeOutAndRemove()
            }
        }
    }

    private fun show(entry: NotificationEntry) {
        pending = null
        // card's up, keep the pulse bright till we tear down
        brightUntil = Long.MAX_VALUE
        val c = buildCard(entry)
        c.alpha = 0f
        val existing = root
        if (existing != null) {
            existing.removeAllViews()
            existing.addView(c, cardLp())
        } else {
            val view = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                setOnClickListener { keepAwake() }
            }
            view.addView(c, cardLp())
            try {
                windowManager.addView(view, layoutParams())
            } catch (e: RuntimeException) {
                android.util.Log.w(TAG, "couldn't add popup window", e)
                return
            }
            root = view
        }
        card = c
        c.animate().alpha(1f).setDuration(FADE_MS).start()
    }

    // sits where the user parked lockscreen notifs in depth studio, centre if they never touched it
    private fun cardLp() =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply {
                val pct = if (org.petalos.config.PetalConfig.isDepthEnabled(context))
                    org.petalos.config.PetalConfig.getDepthNotificationStartPct(context)
                else org.petalos.config.DepthNotificationPlacement.AUTOMATIC
                if (pct == org.petalos.config.DepthNotificationPlacement.AUTOMATIC) {
                    gravity = Gravity.CENTER
                } else {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = context.resources.displayMetrics.heightPixels * pct / 100
                }
            }

    private fun fadeOutAndRemove() {
        val c = card ?: run { teardown(); return }
        c.animate().cancel()
        c.animate().alpha(0f).setDuration(FADE_MS).withEndAction { teardown() }.start()
    }

    // tapped the card, now the user actually wants the phone. real wake is fine here
    private fun keepAwake() {
        try {
            powerManager.wakeUp(
                SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_TAP, "petal:aod_popup_tap",
            )
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "wake failed", e)
        }
        teardown()
    }

    private fun teardown() {
        brightUntil = 0L
        card?.animate()?.cancel()
        val r = root ?: return
        root = null
        card = null
        try {
            windowManager.removeView(r)
        } catch (e: RuntimeException) {
            android.util.Log.w(TAG, "couldn't remove popup window", e)
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        // sits over keyguard, shade and the island
        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.OPAQUE,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
        title = "PetalAodPopup"
        packageName = context.packageName
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    // summary text is useless ("2 new messages"), show the newest kid instead
    private fun contentEntry(entry: NotificationEntry): NotificationEntry {
        val sbn = entry.sbn
        if (!sbn.isGroup || !sbn.notification.isGroupSummary) return entry
        return notifPipeline.allNotifs
            .filter { it.sbn.groupKey == sbn.groupKey && !it.sbn.notification.isGroupSummary }
            .maxByOrNull { it.sbn.notification.`when`.takeIf { w -> w > 0 } ?: it.sbn.postTime }
            ?: entry
    }

    // messaging style keeps the real latest line in EXTRA_MESSAGES, EXTRA_TEXT is often stale
    private fun latestMessage(extras: android.os.Bundle): Pair<CharSequence?, CharSequence?>? {
        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java)
            ?: return null
        val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw).lastOrNull()
            ?: return null
        return last.senderPerson?.name to last.text
    }

    private fun buildCard(original: NotificationEntry): View {
        val entry = contentEntry(original)
        val sbn = entry.sbn
        val n = sbn.notification
        val styled = styled()
        val hidden = lockscreenUserManager.getRedactionType(entry) !=
            NotificationLockscreenUserManager.REDACTION_TYPE_NONE

        val appLabel = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        // redacted = public version if the app gave one, else a generic line
        val pub = if (hidden) n.publicVersion else null
        val src = pub?.extras ?: n.extras
        val message = if (hidden) null else latestMessage(src)
        val title = if (hidden && pub == null) appLabel
            else message?.first ?: src.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: src.getCharSequence(Notification.EXTRA_TITLE)
        val body = if (hidden && pub == null) {
            context.getString(com.android.internal.R.string.notification_hidden_text)
        } else {
            message?.second ?: src.getCharSequence(Notification.EXTRA_TEXT)
                ?: src.getCharSequence(Notification.EXTRA_BIG_TEXT)
        }

        val accent = if (styled && n.color != Notification.COLOR_DEFAULT) n.color else Color.WHITE

        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
        }

        // app icon + name row
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        n.smallIcon?.loadDrawable(context)?.mutate()?.let { d ->
            d.setTint(accent)
            head.addView(ImageView(context).apply { setImageDrawable(d) }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
        }
        head.addView(
            text(appLabel, 13f, 0xCCFFFFFF.toInt(), false).apply { setPadding(dp(8f), 0, 0, 0) },
        )
        col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // the notification graphic: picture first, avatar/large icon otherwise
        if (!hidden) {
            val pic = (n.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java))
                ?.let { BitmapDrawable(context.resources, it) }
            val big: Drawable? = pic ?: n.getLargeIcon()?.loadDrawable(context)
            if (big != null) {
                val iv = ImageView(context).apply {
                    setImageDrawable(big)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    background = GradientDrawable().apply {
                        cornerRadius = if (pic != null) dp(20f).toFloat() else dp(40f).toFloat()
                        setColor(Color.TRANSPARENT)
                    }
                }
                val lp = if (pic != null) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180f))
                } else {
                    LinearLayout.LayoutParams(dp(80f), dp(80f))
                }
                lp.topMargin = dp(16f)
                col.addView(iv, lp)
            }
        }

        if (!title.isNullOrBlank()) {
            col.addView(text(title, 20f, Color.WHITE, true).apply { maxLines = 2 }, lpTop(14f))
        }
        if (!body.isNullOrBlank()) {
            col.addView(text(body, 16f, 0xE6FFFFFF.toInt(), false).apply { maxLines = 4 }, lpTop(6f))
        }

        val frameLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (!styled) return col

        // follow the notif look: rounded surface, glass if the island has glass on
        val wrap = FrameLayout(context).apply {
            setPadding(dp(20f), 0, dp(20f), 0)
            val bg = GradientDrawable().apply {
                cornerRadius = dp(28f).toFloat()
                if (islandSettings.glassNotifications()) {
                    setColor(0x26FFFFFF)
                    setStroke(dp(1f), 0x40FFFFFF)
                } else {
                    setColor(0xFF1E1E22.toInt())
                }
            }
            col.background = bg
        }
        wrap.addView(col, frameLp)
        return wrap
    }

    private fun lpTop(topDp: Float) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(topDp) }

    private fun text(s: CharSequence, sp: Float, color: Int, bold: Boolean) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        gravity = Gravity.CENTER_HORIZONTAL
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    companion object {
        private const val TAG = "PetalAodPopup"
        const val KEY_ENABLED = "petal_aod_popup_enabled"
        const val KEY_STYLE = "petal_aod_popup_style"
        private const val FADE_MS = 450L
        // notif has to land this close to the pulse to count as the reason for it
        private const val PENDING_TTL_MS = 5000L

        // DozeScreenBrightness peeks at this. static so we don't drag dagger into doze scope
        @Volatile private var brightUntil = 0L

        /** True when the current/next pulse is ours, so it should run at normal brightness. */
        @JvmStatic
        fun wantsBrightPulse(): Boolean = SystemClock.elapsedRealtime() < brightUntil
    }
}
