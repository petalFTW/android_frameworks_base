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

package com.android.systemui.island.presenter

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.android.systemui.island.IslandGeometry

/** One notification action rendered as a glass pill in the expanded card. */
data class IslandNotifAction(
    val title: CharSequence,
    val icon: Icon?,
    val actionIntent: PendingIntent,
    /** RemoteInput[] when the action is an inline reply; null for ordinary actions. */
    val remoteInputs: Array<RemoteInput>? = null,
) {
    val isReply: Boolean get() = !remoteInputs.isNullOrEmpty()
}

/** Display data extracted from a notification by [com.android.systemui.island.signal.NotificationSignalSource]. */
data class NotificationPayload(
    val key: String,
    val packageName: String,
    val appLabel: String,
    /** Messaging sender (from EXTRA_MESSAGING_PERSON); null for non-conversation notifications. */
    val sender: CharSequence?,
    val title: CharSequence?,
    val text: CharSequence?,
    val subText: CharSequence?,
    val smallIcon: Icon?,
    /** Person icon or notification largeIcon; drives the expanded-card avatar. */
    val largeIcon: Icon?,
    val iconColor: Int,
    val contentIntent: PendingIntent?,
    val actions: List<IslandNotifAction>,
    val progressCurrent: Int,
    val progressMax: Int,
    val progressIndeterminate: Boolean,
    val whenMillis: Long,
    /** OTP-looking code detected in the notification text; gets a system copy chip. */
    val otpCode: String? = null,
)

/**
 * Renders a notification. Collapsed is a circular app icon + bold app label. Expanded is a full
 * detail card: header (app icon + label + time), avatar + sender/title + message body + subtext,
 * an optional progress bar and up to three action pills.
 */
class NotificationPresenter(
    private val context: Context,
    private val geometry: IslandGeometry,
    private val payload: NotificationPayload,
) : IslandPresenter {

    private var textView: TextView? = null
    private var expandedRoot: LinearLayout? = null
    private var host: IslandPresenter.Host? = null

    /** Action pill currently in inline-reply mode, and its views. */
    private var replyAction: IslandNotifAction? = null
    private var replyInput: EditText? = null
    private var actionsRow: View? = null

    override fun setHost(host: IslandPresenter.Host?) {
        this.host = host
    }

    override fun bindCollapsed(container: ViewGroup): Int {
        exitReplyMode()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(geometry.capsulePaddingStart, 0, geometry.capsulePaddingEnd, 0)
        }
        val icon = ImageView(context).apply {
            val d = payload.smallIcon?.loadDrawable(context)
            if (d != null) {
                setImageDrawable(d)
                setColorFilter(payload.iconColor.takeIf { it != 0 } ?: 0xFFFFFFFF.toInt())
            }
        }
        val lp = LinearLayout.LayoutParams(geometry.iconSize, geometry.iconSize)
        lp.gravity = Gravity.CENTER_VERTICAL
        row.addView(icon, lp)

        // Progress notifications collapse to just the app icon + a slim progress bar, so the
        // download/upload stays glanceable without the app label (§11.1).
        if (payload.progressMax > 0) {
            val accent = payload.iconColor.takeIf { it != 0 && it != Color.WHITE } ?: Color.WHITE
            val track = 0x40FFFFFF.toInt()
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = payload.progressMax
                progress = payload.progressCurrent
                isIndeterminate = payload.progressIndeterminate
                progressTintList = ColorStateList.valueOf(accent)
                progressBackgroundTintList = ColorStateList.valueOf(track)
                val drawable = progressDrawable
                if (drawable is android.graphics.drawable.LayerDrawable) {
                    for (i in 0 until drawable.numberOfLayers) {
                        val layer = drawable.getDrawable(i)
                        if (layer is android.graphics.drawable.GradientDrawable) {
                            layer.cornerRadius = geometry.dp(2f).toFloat()
                        }
                    }
                }
            }
            val barLp = LinearLayout.LayoutParams(geometry.dp(64f), geometry.dp(4f)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = geometry.capsuleGap
            }
            row.addView(bar, barLp)
            container.addView(row)
            return geometry.capsulePaddingStart + geometry.iconSize + geometry.capsuleGap +
                geometry.dp(64f) + geometry.capsulePaddingEnd
        }

        val label = TextView(context).apply {
            text = payload.appLabel
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val textLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = geometry.capsuleGap }
        row.addView(label, textLp)
        container.addView(row)

        val labelWidth = label.paint.measureText(payload.appLabel.toString()).toInt()
        val width = geometry.capsulePaddingStart + geometry.iconSize + geometry.capsuleGap +
            labelWidth + geometry.capsulePaddingEnd
        return width
    }

    override fun bindExpanded(container: ViewGroup) {
        exitReplyMode()
        val dark = isDarkTheme()
        val primary = if (dark) Color.WHITE else 0xFF101012.toInt()
        val secondary = if (dark) 0xB3FFFFFF.toInt() else 0xB3101012.toInt()
        val bodyColor = if (dark) 0xE6FFFFFF.toInt() else 0xE6101012.toInt()
        val accent = payload.iconColor.takeIf { it != 0 && it != Color.WHITE } ?: primary
        val chipFill = if (dark) 0x14FFFFFF.toInt() else 0x14101012.toInt()
        val chipStroke = if (dark) 0x33FFFFFF.toInt() else 0x2A101012.toInt()
        val pad = geometry.expandedPadding

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad - geometry.dp(2f), pad, pad)
        }

        // --- Swipe indicator: subtle pill at top center ------------------------------------
        val indicator = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = geometry.dp(1.5f).toFloat()
                setColor(if (dark) 0x40FFFFFF.toInt() else 0x30101012.toInt())
            }
        }
        val indicatorLp = LinearLayout.LayoutParams(
            geometry.dp(28f), geometry.dp(3f),
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(indicator, indicatorLp)

        // --- Header: app icon · app label · timestamp --------------------------------------
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerIcon = ImageView(context).apply {
            val d = loadAppIcon() ?: payload.smallIcon?.loadDrawable(context)
            if (d != null) setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, geometry.dp(4f).toFloat())
                }
            }
        }
        header.addView(
            headerIcon,
            LinearLayout.LayoutParams(geometry.dp(16f), geometry.dp(16f)),
        )
        header.addView(
            TextView(context).apply {
                text = payload.appLabel
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(secondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = geometry.dp(6f)
            },
        )
        val timeStr = formatTime(payload.whenMillis)
        if (timeStr.isNotEmpty()) {
            header.addView(
                TextView(context).apply {
                    text = "·  $timeStr"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(secondary)
                },
            )
        }
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = geometry.dp(8f) },
        )

        // --- Body: avatar + headline/body/subtext -------------------------------------------
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        body.addView(
            buildAvatar(),
            LinearLayout.LayoutParams(avatarSize(), avatarSize()).apply {
                topMargin = geometry.dp(2f)
            },
        )
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        // Headline: sender or title, semibold
        val headline = TextView(context).apply {
            text = payload.sender ?: payload.title ?: payload.appLabel
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(primary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        column.addView(headline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        // Body text: up to 4 lines
        val bodyText = payload.text?.takeIf { it.isNotBlank() }
        if (bodyText != null) {
            val tv = TextView(context).apply {
                text = bodyText
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(bodyColor)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setLineSpacing(geometry.dp(1.5f).toFloat(), 1f)
            }
            textView = tv
            column.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = geometry.dp(2f) },
            )
        }
        // Subtext
        payload.subText?.takeIf { it.isNotBlank() }?.let { st ->
            column.addView(
                TextView(context).apply {
                    text = st
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(secondary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = geometry.dp(2f) },
            )
        }
        body.addView(
            column,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = geometry.dp(12f)
            },
        )
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = geometry.dp(10f) },
        )

        // --- Progress (downloads, uploads, …) ------------------------------------------------
        if (payload.progressMax > 0) {
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = payload.progressMax
                progress = payload.progressCurrent
                isIndeterminate = payload.progressIndeterminate
                progressTintList = ColorStateList.valueOf(accent)
                progressBackgroundTintList = ColorStateList.valueOf(chipStroke)
                // Rounded progress bar track
                val track = progressDrawable
                if (track is android.graphics.drawable.LayerDrawable) {
                    for (i in 0 until track.numberOfLayers) {
                        val layer = track.getDrawable(i)
                        if (layer is android.graphics.drawable.GradientDrawable) {
                            layer.cornerRadius = geometry.dp(2f).toFloat()
                        }
                    }
                }
            }
            root.addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    geometry.dp(4f)).apply { topMargin = geometry.dp(12f) },
            )
        }

        // --- Action pills with ripple ---------------------------------------------------------
        if (payload.actions.isNotEmpty() || payload.otpCode != null) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            // System-injected copy chip for OTP notifications (mirrors the shade's action).
            payload.otpCode?.let { code ->
                val copyChip = buildTextPill(
                    context.getString(com.android.systemui.res.R.string.petal_otp_copy_action),
                    accent,
                    chipFill,
                    chipStroke,
                )
                copyChip.setOnClickListener {
                    com.android.systemui.petalos.PetalOtpHelper.copyToClipboard(context, code)
                }
                row.addView(
                    copyChip,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = geometry.dp(8f) },
                )
            }
            payload.actions.take(3).forEach { action ->
                val pillBg = GradientDrawable().apply {
                    cornerRadius = geometry.dp(18f).toFloat()
                    setColor(chipFill)
                    setStroke(geometry.dp(1f), chipStroke)
                }
                val rippleColor = android.content.res.ColorStateList.valueOf(
                    Color.argb(0x33, Color.red(accent), Color.green(accent), Color.blue(accent))
                )
                val rippleMask = GradientDrawable().apply {
                    cornerRadius = geometry.dp(18f).toFloat()
                    setColor(Color.WHITE)
                }
                val ripple = android.graphics.drawable.RippleDrawable(
                    rippleColor, pillBg, rippleMask,
                )

                val chip = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = ripple
                    setPadding(geometry.dp(12f), geometry.dp(7f), geometry.dp(14f), geometry.dp(7f))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onActionClicked(action) }
                }

                // Optional action icon
                val actionIcon = action.icon?.loadDrawable(context)
                if (actionIcon != null) {
                    val iv = ImageView(context).apply {
                        setImageDrawable(actionIcon)
                        setColorFilter(accent)
                    }
                    chip.addView(iv, LinearLayout.LayoutParams(
                        geometry.dp(14f), geometry.dp(14f),
                    ).apply { marginEnd = geometry.dp(5f) })
                }

                chip.addView(
                    TextView(context).apply {
                        text = action.title
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(accent)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )

                row.addView(
                    chip,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = geometry.dp(8f)
                    },
                )
            }
            val scroller = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(row)
            }
            actionsRow = scroller
            root.addView(
                scroller,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = geometry.dp(12f) },
            )
        }

        expandedRoot = root
        container.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /**
     * Height of the expanded card. Uses a measure pass on the already-bound view hierarchy so that
     * text wrapping, variable font sizes and action pill widths are accounted for exactly.
     */
    override fun expandedHeightPx(): Int {
        val root = expandedRoot ?: return -1
        val targetW = (geometry.screenWidth - geometry.expandedSideMargin * 2)
            .coerceAtMost(geometry.expandedMaxWidth)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(targetW, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(widthSpec, heightSpec)
        return root.measuredHeight
    }

    private fun avatarSize(): Int = geometry.dp(48f)

    /** Simple glass pill with a text label (used for the injected copy-OTP chip). */
    private fun buildTextPill(
        label: String,
        accent: Int,
        chipFill: Int,
        chipStroke: Int,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = geometry.dp(18f).toFloat()
                setColor(chipFill)
                setStroke(geometry.dp(1f), chipStroke)
            }
            setPadding(geometry.dp(12f), geometry.dp(7f), geometry.dp(14f), geometry.dp(7f))
            isClickable = true
            isFocusable = true
            addView(
                TextView(context).apply {
                    text = label
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(accent)
                    maxLines = 1
                },
            )
        }
    }

    /** The app's launcher icon; falls back to null when unavailable (rare). */
    private fun loadAppIcon(): android.graphics.drawable.Drawable? =
        runCatching { context.packageManager.getApplicationIcon(payload.packageName) }.getOrNull()

    private fun buildAvatar(): View {
        val drawable = payload.largeIcon?.loadDrawable(context) ?: loadAppIcon()
        return if (drawable != null) {
            ImageView(context).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0, 0, view.width, view.height, geometry.dp(14f).toFloat()
                        )
                    }
                }
            }
        } else {
            // Fallback: tinted circle holding the notification's small icon.
            FrameLayout(context).apply {
                val c = payload.iconColor.takeIf { it != 0 } ?: 0xFF3C3C40.toInt()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(0x2E, Color.red(c), Color.green(c), Color.blue(c)))
                }
                val iconPad = geometry.dp(10f)
                setPadding(iconPad, iconPad, iconPad, iconPad)
                addView(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        val d = payload.smallIcon?.loadDrawable(context)
                        if (d != null) {
                            setImageDrawable(d)
                            setColorFilter(payload.iconColor.takeIf { it != 0 } ?: Color.WHITE)
                        }
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    /** Accent the rim with the notification colour when the app set one. */
    override fun tint(): IslandTint? =
        payload.iconColor
            .takeIf { it != 0 && it != Color.WHITE }
            ?.let { IslandTint(accent = it) }

    override fun onPrimaryAction(): Boolean {
        val pi = payload.contentIntent
        if (pi == null) {
            android.util.Log.d(TAG, "onPrimaryAction: no contentIntent for ${payload.packageName}")
            return false
        }
        // Route through the host so the keyguard is dismissed first when locked — a bare
        // PendingIntent.send() is a no-op on the lock screen, which is exactly when the island
        // is most prominent (bug: "clicking an expanded notification doesn't open the app").
        host?.launchPendingIntent(pi) ?: sendPendingIntent(pi)
        exitReplyMode()
        return true
    }

    /** Dispatches a tapped action pill: reply actions enter inline-reply mode, others send. */
    private fun onActionClicked(action: IslandNotifAction) {
        android.util.Log.d(TAG, "action clicked: '${action.title}' reply=${action.isReply}")
        if (action.isReply) {
            enterReplyMode(action)
        } else {
            sendPendingIntent(action.actionIntent)
        }
    }

    /**
     * Sends an activity-type PendingIntent through the host (keyguard-aware); broadcast /
     * service PendingIntents are sent directly. Failures are logged instead of being silently
     * swallowed, which is how "actions do nothing" went unnoticed.
     */
    private fun sendPendingIntent(pi: PendingIntent) {
        if (host != null && pi.isActivity) {
            host?.launchPendingIntent(pi)
            return
        }
        try {
            pi.send()
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent canceled for ${payload.packageName}", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send PendingIntent for ${payload.packageName}", e)
        }
    }

    // --- Inline reply -------------------------------------------------------------------------

    /** Swaps the action pill row for a glass text field wired to the action's RemoteInput. */
    private fun enterReplyMode(action: IslandNotifAction) {
        val root = expandedRoot ?: return
        if (replyAction != null) {
            // Already replying to something else — commit-free switch.
            exitReplyMode()
        }
        replyAction = action
        host?.setDismissalHeld(true)
        host?.setWindowFocusable(true)

        val dark = isDarkTheme()
        val primary = if (dark) Color.WHITE else 0xFF101012.toInt()
        val secondary = if (dark) 0xB3FFFFFF.toInt() else 0xB3101012.toInt()
        val chipFill = if (dark) 0x14FFFFFF.toInt() else 0x14101012.toInt()
        val chipStroke = if (dark) 0x33FFFFFF.toInt() else 0x2A101012.toInt()
        val accent = payload.iconColor.takeIf { it != 0 && it != Color.WHITE } ?: primary

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val input = EditText(context).apply {
            hint = action.title
            setHintTextColor(secondary)
            setTextColor(primary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = geometry.dp(18f).toFloat()
                setColor(chipFill)
                setStroke(geometry.dp(1f), chipStroke)
            }
            setPadding(
                geometry.dp(12f), geometry.dp(8f), geometry.dp(12f), geometry.dp(8f),
            )
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    sendReply()
                    true
                } else {
                    false
                }
            }
            // Escape hatch: back while the field is empty leaves reply mode.
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP && text.isNullOrBlank()
                ) {
                    exitReplyMode()
                    true
                } else {
                    false
                }
            }
        }
        replyInput = input
        row.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        fun pillButton(label: String, tint: Int, onClick: () -> Unit): View {
            return TextView(context).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(tint)
                background = GradientDrawable().apply {
                    cornerRadius = geometry.dp(18f).toFloat()
                    setColor(chipFill)
                    setStroke(geometry.dp(1f), chipStroke)
                }
                setPadding(geometry.dp(14f), geometry.dp(8f), geometry.dp(14f), geometry.dp(8f))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        row.addView(
            pillButton(context.getString(android.R.string.cancel), secondary) { exitReplyMode() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = geometry.dp(8f) },
        )
        val send = pillButton(context.getString(android.R.string.ok), accent) { sendReply() }
        row.addView(
            send,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = geometry.dp(8f) },
        )

        // Replace the pills row in place so the card keeps its height (no re-layout spring).
        val oldRow = actionsRow
        val lp = (oldRow?.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = geometry.dp(12f) }
        oldRow?.let { root.removeView(it) }
        actionsRow = row
        root.addView(row, lp)

        input.requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Sends the typed reply through the action's RemoteInput and leaves reply mode. */
    private fun sendReply() {
        val action = replyAction ?: return
        val input = replyInput ?: return
        val text = input.text?.toString().orEmpty()
        if (text.isBlank()) {
            exitReplyMode()
            return
        }
        val remoteInputs = action.remoteInputs ?: run {
            exitReplyMode()
            return
        }
        val results = Bundle()
        remoteInputs.forEach { ri -> results.putCharSequence(ri.resultKey, text) }
        val intent = Intent()
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        try {
            action.actionIntent.send(context, 0, intent)
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply PendingIntent canceled for ${payload.packageName}", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send reply for ${payload.packageName}", e)
        }
        exitReplyMode()
    }

    /** Tears down the reply field, hides the IME and releases the window/dwell holds. */
    private fun exitReplyMode() {
        if (replyAction == null) return
        replyAction = null
        replyInput?.let { input ->
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        }
        replyInput = null
        host?.setWindowFocusable(false)
        host?.setDismissalHeld(false)
        // Rebinding (collapse/expand/morph) rebuilds the content anyway; nothing else to do.
    }

    override fun onDismiss() {
        exitReplyMode()
        // The notification is cancelled by the signal source / router.
    }

    override fun onDestroy() {
        exitReplyMode()
        textView = null
        expandedRoot = null
        actionsRow = null
    }

    private fun isDarkTheme(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        val now = System.currentTimeMillis()
        val diff = now - millis
        return when {
            diff < 60_000L -> "now"
            diff < 3_600_000L -> "${diff / 60_000L}m"
            diff < 86_400_000L -> "${diff / 3_600_000L}h"
            else -> android.text.format.DateUtils.getRelativeTimeSpanString(millis, now, 0L)
                .toString()
        }
    }

    companion object {
        private const val TAG = "IslandNotifPresenter"
    }
}
