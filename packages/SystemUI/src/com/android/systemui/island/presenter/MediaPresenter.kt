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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.island.IslandGeometry
import com.android.systemui.island.render.RecordView
import com.android.systemui.island.render.GlassSeekBar
import com.android.systemui.island.render.PaletteTinter

// spins while the track is playing
class MediaPresenter(
    private val context: Context,
    private val geometry: IslandGeometry,
    private val controller: MediaController,
) : IslandPresenter {

    private var cachedTint: IslandTint? = null
    private var tintListener: ((IslandTint?) -> Unit)? = null
    private var playing = false
    private var playPause: ImageView? = null
    private var record: RecordView? = null
    private var seekBar: GlassSeekBar? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var artView: ImageView? = null
    private var artOverlay: ImageView? = null
    private var accentButtonBg: GradientDrawable? = null
    private var automixPill: TextView? = null
    private var automixDecision: Boolean? = null
    private var trackIdentity = trackIdentity(controller.metadata)
    private var destroyed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                val identity = trackIdentity(metadata)
                if (identity != trackIdentity) {
                    trackIdentity = identity
                    transitionDip()
                }
                updateContent()
                refreshTint()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                playing = when (state?.state) {
                    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> playing
                    else -> state?.state == PlaybackState.STATE_PLAYING
                }
                updateTransport()
            }
        }

    // PetalMusic tells us which sleeve is coming in, when the blend is audible
    private val artFadeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getStringExtra(EXTRA_OWNER) != controller.packageName) return
                if (!intent.getBooleanExtra(EXTRA_ACTIVE, false)) {
                    cancelArtFade()
                    return
                }
                val cover = intent.getStringExtra(EXTRA_COVER) ?: return
                val duration = intent.getIntExtra(EXTRA_DURATION, 600).toLong().coerceAtLeast(250L)
                startArtFade(cover, duration)
            }
        }

    init {
        playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        controller.registerCallback(callback)
        runCatching {
            context.registerReceiver(
                artFadeReceiver,
                IntentFilter(ACTION_ART_FADE),
                Context.RECEIVER_EXPORTED,
            )
        }
        extractTint()
    }

    override fun bindCollapsed(container: ViewGroup): Int {
        stopProgressTicker()
        cancelArtFade()
        seekBar = null
        titleView = null
        artistView = null
        artView = null
        artOverlay = null

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(geometry.dp(10f), 0, geometry.dp(12f), 0)
        }
        record?.stop()
        record = RecordView(context).apply {
            cachedTint?.accent?.let { setRecordColor(it) }
        }
        row.addView(record, LinearLayout.LayoutParams(
            geometry.iconSize, geometry.iconSize,
        ).apply { gravity = Gravity.CENTER_VERTICAL })

        playPause = ImageView(context).apply {
            setOnClickListener { togglePlayback() }
        }
        val ppLp = LinearLayout.LayoutParams(
            geometry.iconSize, geometry.iconSize,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = geometry.dp(8f)
        }
        row.addView(playPause, ppLp)
        container.addView(row)
        updateTransport()

        return geometry.dp(10f) + geometry.iconSize + geometry.dp(8f) +
            geometry.iconSize + geometry.dp(12f)
    }

    override fun bindExpanded(container: ViewGroup) {
        val dark = isDarkTheme()
        val primary = if (dark) Color.WHITE else 0xFF101012.toInt()
        val secondary = if (dark) 0xB3FFFFFF.toInt() else 0xB3101012.toInt()
        val accent = cachedTint?.accent ?: primary
        val onAccent = if (Color.luminance(accent) > 0.5f) 0xFF101012.toInt() else Color.WHITE
        val chipFill = if (dark) 0x14FFFFFF.toInt() else 0x14101012.toInt()
        val chipStroke = if (dark) 0x33FFFFFF.toInt() else 0x2A101012.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(geometry.expandedPadding, geometry.expandedPadding,
                geometry.expandedPadding, geometry.expandedPadding)
        }

        // album art, text, then the record
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val artClip = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, geometry.artRadius)
            }
        }
        artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = artClip
        }
        // incoming sleeve rides over the outgoing one while a mix runs
        val overlay = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = artClip
            alpha = 0f
        }
        artOverlay = overlay
        val artSlot = FrameLayout(context)
        artSlot.addView(artView, FrameLayout.LayoutParams(geometry.artSize, geometry.artSize))
        artSlot.addView(overlay, FrameLayout.LayoutParams(geometry.artSize, geometry.artSize))
        topRow.addView(artSlot, LinearLayout.LayoutParams(geometry.artSize, geometry.artSize))

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleView = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setTextColor(primary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        artistView = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textColumn.addView(titleView)
        textColumn.addView(artistView)
        topRow.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = geometry.dp(12f)
            },
        )

        record?.stop()
        record = RecordView(context).apply {
            setRecordColor(accent)
            active = playing
        }
        topRow.addView(record, LinearLayout.LayoutParams(
            geometry.dp(28f), geometry.dp(28f),
        ).apply { marginStart = geometry.dp(10f) })

        // automix button, only when the bridge says so
        if (automixAvailable()) {
            automixDecision = automixOn()
            val pill = TextView(context).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = android.graphics.Typeface.create(
                    "sans-serif", android.graphics.Typeface.BOLD)
                setPadding(geometry.dp(10f), geometry.dp(5f), geometry.dp(10f), geometry.dp(5f))
                isClickable = true
                setOnClickListener { toggleAutomix(accent, onAccent, chipFill, chipStroke, primary) }
            }
            automixPill = pill
            paintAutomixPill(accent, onAccent, chipFill, chipStroke, primary)
            topRow.addView(pill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = geometry.dp(8f) })
        } else {
            automixPill = null
            automixDecision = null
        }

        root.addView(topRow)

        // seek bar
        seekBar = GlassSeekBar(context).apply {
            setMetrics(geometry.dp(4f).toFloat(), geometry.dp(5f).toFloat())
            trackColor = if (dark) 0x40FFFFFF.toInt() else 0x33101012.toInt()
            progressColor = accent
            thumbColor = primary
            onSeek = { fraction ->
                val duration = controller.metadata
                    ?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (duration > 0) {
                    controller.transportControls.seekTo((fraction * duration).toLong())
                }
            }
        }
        root.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = geometry.dp(6f) },
        )

        // skip, play/pause, skip
        val transport = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun transportButton(
            filled: Boolean,
            glyph: Int,
            onClick: () -> Unit,
        ): ImageView =
            ImageView(context).apply {
                setImageResource(glyph)
                setColorFilter(if (filled) onAccent else primary)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (filled) {
                        setColor(accent)
                    } else {
                        setColor(chipFill)
                        setStroke(geometry.dp(1f), chipStroke)
                    }
                }
                setOnClickListener { onClick() }
            }

        val previous = transportButton(false, android.R.drawable.ic_media_previous) { prev() }
        transport.addView(
            previous,
            LinearLayout.LayoutParams(geometry.dp(36f), geometry.dp(36f)).apply {
                marginEnd = geometry.dp(16f)
            },
        )

        playPause = transportButton(
            true,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        ) { togglePlayback() }
        accentButtonBg = playPause?.background as? GradientDrawable
        transport.addView(
            playPause,
            LinearLayout.LayoutParams(geometry.dp(44f), geometry.dp(44f)),
        )

        val next = transportButton(false, android.R.drawable.ic_media_next) { next() }
        transport.addView(
            next,
            LinearLayout.LayoutParams(geometry.dp(36f), geometry.dp(36f)).apply {
                marginStart = geometry.dp(16f)
            },
        )

        root.addView(transport)

        container.addView(root)
        updateContent()
        updateSeekProgress()
        if (playing) startProgressTicker()
    }

    override fun expandedHeightPx(): Int = geometry.expandedHeightMedia

    override fun tint(): IslandTint? = cachedTint

    override fun setTintListener(listener: ((IslandTint?) -> Unit)?) {
        tintListener = listener
    }

    // automix helpers

    private fun automixAvailable(): Boolean {
        val cr = context.contentResolver
        val enabled = Settings.System.getInt(cr, "petal_bridge_enabled", 0) == 1
        val ask = Settings.System.getInt(cr, "petal_bridge_ask", 0) == 1
        return enabled && ask
    }

    private fun automixOn(): Boolean = automixDecisions[controller.packageName] == true

    private fun paintAutomixPill(
        accent: Int,
        onAccent: Int,
        chipFill: Int,
        chipStroke: Int,
        primary: Int,
    ) {
        val pill = automixPill ?: return
        val on = automixDecision == true
        pill.text = if (on) "Transition on" else "Transition off"
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = geometry.dp(14f).toFloat()
            if (on) {
                setColor(accent)
                setStroke(geometry.dp(1f), accent)
            } else {
                setColor(chipFill)
                setStroke(geometry.dp(1f), chipStroke)
            }
        }
        pill.setTextColor(if (on) onAccent else primary)
    }

    private fun toggleAutomix(
        accent: Int,
        onAccent: Int,
        chipFill: Int,
        chipStroke: Int,
        primary: Int,
    ) {
        val on = !(automixDecision == true)
        automixDecision = on
        // Remembered in memory only, so a collapse keeps the choice but a
        // SystemUI restart defaults the app back to Automix off.
        automixDecisions[controller.packageName] = on
        sendAutomix(on)
        paintAutomixPill(accent, onAccent, chipFill, chipStroke, primary)
    }

    private fun sendAutomix(approved: Boolean) {
        val intent = Intent("org.rab1d.bridge.action.SET_AUTOMIX")
            .setPackage("org.rab1d.bridge")
            .putExtra("package", controller.packageName)
            .putExtra("approved", approved)
        context.sendBroadcast(intent)
    }

    override fun onPrimaryAction(): Boolean {
        // card tap only collapses, never toggles playback
        return true
    }

    override fun onDestroy() {
        destroyed = true
        tintListener = null
        stopProgressTicker()
        mainHandler.removeCallbacksAndMessages(null)
        controller.unregisterCallback(callback)
        runCatching { context.unregisterReceiver(artFadeReceiver) }
        artOverlay?.animate()?.cancel()
        cachedTint = null
        artView = null
        artOverlay = null
        titleView = null
        artistView = null
        seekBar = null
        playPause = null
        accentButtonBg = null
        automixPill = null
        automixDecision = null
        record?.stop()
        record = null
    }

    private fun trackIdentity(metadata: MediaMetadata?): List<String?> = listOf(
        metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
        metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
    )

    // quick dip + fade back in when the track flips
    private fun transitionDip() {
        val art = artView ?: return
        // a live cover crossfade already owns the art, don't dip under it
        if ((artOverlay?.alpha ?: 0f) > 0.01f) return
        art.animate().cancel()
        art.animate()
            .alpha(0.15f)
            .setDuration(180L)
            .withEndAction {
                if (destroyed) return@withEndAction
                art.animate().alpha(1f).setDuration(700L).start()
            }
            .start()
    }

    // same feel as the app: incoming cover fades in over the old one, slight grow
    private fun startArtFade(cover: String, durationMs: Long) {
        val overlay = artOverlay ?: return
        val current = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: controller.metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (cover == current) return
        Thread {
            val bitmap = decodeArtwork(Uri.parse(cover)) ?: return@Thread
            mainHandler.post {
                if (destroyed || artOverlay !== overlay) return@post
                overlay.animate().cancel()
                overlay.setImageBitmap(bitmap)
                overlay.alpha = 0f
                overlay.scaleX = 1f
                overlay.scaleY = 1f
                overlay.animate()
                    .alpha(1f)
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .setDuration(durationMs)
                    .withEndAction {
                        if (destroyed) return@withEndAction
                        artView?.setImageBitmap(bitmap)
                        resetArtOverlay()
                    }
                    .start()
            }
        }.start()
    }

    private fun cancelArtFade() {
        artOverlay?.animate()?.cancel()
        resetArtOverlay()
    }

    private fun resetArtOverlay() {
        val overlay = artOverlay ?: return
        overlay.alpha = 0f
        overlay.scaleX = 1f
        overlay.scaleY = 1f
    }

    // new art means new accent, push it through
    private fun refreshTint() {        val metadata = controller.metadata
        val embedded = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (embedded != null) {
            cachedTint = PaletteTinter.extract(downscale(embedded))
            onTintReady()
            return
        }
        val uri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.let { Uri.parse(it) }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)?.let { Uri.parse(it) }
            ?: return
        Thread {
            val bitmap = decodeArtwork(uri) ?: return@Thread
            mainHandler.post {
                if (destroyed) return@post
                cachedTint = PaletteTinter.extract(downscale(bitmap))
                onTintReady()
            }
        }.start()
    }

    private fun onTintReady() {
        tintListener?.invoke(cachedTint)
        applyAccentToContent()
    }

    // accent, or the theme foreground if there's no tint
    private fun currentAccent(): Int {
        val primary = if (isDarkTheme()) Color.WHITE else 0xFF101012.toInt()
        return cachedTint?.accent ?: primary
    }

    // repaint the already-built card after a tint change
    private fun applyAccentToContent() {
        val accent = currentAccent()
        val onAccent = if (Color.luminance(accent) > 0.5f) 0xFF101012.toInt() else Color.WHITE
        record?.setRecordColor(accent)
        seekBar?.progressColor = accent
        accentButtonBg?.setColor(accent)
        playPause?.setColorFilter(onAccent)
    }

    private fun updateContent() {
        val metadata = controller.metadata
        titleView?.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        artistView?.text =
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""

        loadArtwork(metadata)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        seekBar?.isEnabled = duration > 0
        updateTransport()
        record?.active = playing
    }

    // embedded art first, then try the uri
    private fun loadArtwork(metadata: MediaMetadata?) {
        val embedded = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (embedded != null) {
            artView?.setImageBitmap(embedded)
            return
        }
        val uri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?.let { Uri.parse(it) }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)?.let { Uri.parse(it) }
            ?: return

        artView?.tag = uri
        Thread {
            val bitmap = decodeArtwork(uri)
            mainHandler.post {
                if (!destroyed && artView?.tag == uri) artView?.setImageBitmap(bitmap)
            }
        }.start()
    }

    private fun decodeArtwork(uri: Uri): Bitmap? =
        runCatching {
            val bytes = openStreamBytes(uri) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val req = geometry.artSize * 2
            var sample = 1
            while (bounds.outWidth / sample > req || bounds.outHeight / sample > req) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()

    // resolver first (content/file), then pull remote sleeves off the wire
    private fun openStreamBytes(uri: Uri): ByteArray? {
        runCatching { context.contentResolver.openInputStream(uri) }
            .getOrNull()
            ?.use { return it.readBytes() }
        val scheme = uri.scheme
        if (scheme == "http" || scheme == "https") {
            return runCatching {
                java.net.URL(uri.toString()).openStream().use { it.readBytes() }
            }.getOrNull()
        }
        return null
    }

    private fun updateTransport() {
        val glyph =
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        playPause?.setImageResource(glyph)
        record?.active = playing
        if (playing) startProgressTicker() else stopProgressTicker()
    }

    private fun togglePlayback() {
        val transport = controller.transportControls
        if (playing) transport.pause() else transport.play()
    }

    private fun prev() = controller.transportControls.skipToPrevious()
    private fun next() = controller.transportControls.skipToNext()

    private val progressTicker = object : Runnable {
        override fun run() {
            if (destroyed || !playing) return
            updateSeekProgress()
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private fun startProgressTicker() {
        if (destroyed || seekBar == null) return
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    private fun stopProgressTicker() {
        mainHandler.removeCallbacks(progressTicker)
    }

    private fun updateSeekProgress() {
        if (seekBar?.isDragging == true) return
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0) return
        val state = controller.playbackState ?: return
        val position = if (state.state == PlaybackState.STATE_PLAYING) {
            // speed 0 breaks the math, clamp it to 1x
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
            val elapsed =
                if (state.lastPositionUpdateTime > 0L) {
                    (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
                } else {
                    0L
                }
            state.position + (elapsed * speed).toLong()
        } else {
            state.position
        }
        seekBar?.progress = (position.toFloat() / duration).coerceIn(0f, 1f)
    }

    private fun isDarkTheme(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun extractTint() {
        val art = controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: return
        cachedTint = PaletteTinter.extract(downscale(art))
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val max = 128f
        val scale = maxOf(bitmap.width, bitmap.height).toFloat()
        if (scale <= max) return bitmap
        val f = max / scale
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true
        )
    }

    private companion object {
        // seek bar poll interval while playing
        const val TICK_MS = 500L

        // incoming-cover cue from PetalMusic's in-app mixer
        const val ACTION_ART_FADE = "org.rab1d.bridge.action.TRANSITION_ART"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_COVER = "cover"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_ACTIVE = "active"

        // AutoMix is opt-in per app and defaults to off. The choice is kept in
        // memory only, so it survives an island collapse but a SystemUI restart
        // starts every app back at "Automix off" instead of reviving a stale
        // approval the user made in an earlier session.
        val automixDecisions = HashMap<String, Boolean>()
    }
}
