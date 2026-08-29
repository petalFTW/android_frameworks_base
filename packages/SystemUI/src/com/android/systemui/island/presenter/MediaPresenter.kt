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

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.android.systemui.island.IslandGeometry
import com.android.systemui.island.render.EqualizerView
import com.android.systemui.island.render.PaletteTinter

/**
 * Renders the media island: collapsed is an equalizer + play/pause glyph, expanded is album art,
 * title/artist, a seek slider and transport controls (§4.5). The island is tinted from the artwork.
 */
class MediaPresenter(
    private val context: Context,
    private val geometry: IslandGeometry,
    private val controller: MediaController,
) : IslandPresenter {

    private var cachedTint: IslandTint? = null
    private var playing = false
    private var playPause: ImageView? = null
    private var equalizer: EqualizerView? = null
    private var seekBar: SeekBar? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var artView: ImageView? = null

    private val callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                updateContent()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                playing = state?.state == PlaybackState.STATE_PLAYING
                updateTransport()
            }
        }

    init {
        playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        controller.registerCallback(callback)
        extractTint()
    }

    override fun bindCollapsed(container: ViewGroup): Int {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(geometry.dp(10f), 0, geometry.dp(12f), 0)
        }
        equalizer = EqualizerView(context).apply {
            barCount = 3
            setMetrics(
                geometry.equalizerBarWidth,
                geometry.equalizerGap,
                geometry.equalizerMaxHeight,
            )
        }
        val eqLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            (geometry.equalizerMaxHeight * 1.2f).toInt(),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(equalizer, eqLp)

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

        val barsWidth = (geometry.equalizerBarWidth * 3 + geometry.equalizerGap * 2).toInt()
        return geometry.dp(10f) + barsWidth + geometry.dp(8f) +
            geometry.iconSize + geometry.dp(12f)
    }

    override fun bindExpanded(container: ViewGroup) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(geometry.expandedPadding, geometry.expandedPadding,
                geometry.expandedPadding, geometry.expandedPadding)
        }

        // Top row: album art + title/artist + equalizer
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, geometry.artRadius)
                }
            }
        }
        topRow.addView(artView, LinearLayout.LayoutParams(geometry.artSize, geometry.artSize))

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleView = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        artistView = TextView(context).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.6f
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

        equalizer = EqualizerView(context).apply {
            barCount = 6
            setMetrics(
                geometry.equalizerBarWidth,
                geometry.equalizerGap,
                geometry.equalizerMaxHeightExpanded,
            )
        }
        topRow.addView(
            equalizer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (geometry.equalizerMaxHeightExpanded * 1.2f).toInt(),
            ).apply { marginStart = geometry.dp(10f) },
        )

        root.addView(topRow)

        // Seek bar
        seekBar = SeekBar(context).apply {
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, progress: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(b: SeekBar) {}
                    override fun onStopTrackingTouch(b: SeekBar) {
                        val duration = controller.metadata
                            ?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                        if (duration > 0) {
                            controller.transportControls.seekTo(
                                (b.progress / 100f * duration).toLong()
                            )
                        }
                    }
                },
            )
        }
        root.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = geometry.dp(6f) },
        )

        // Transport controls
        val transport = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        transport.addView(iconButton(android.R.drawable.ic_media_previous) { prev() })
        playPause = iconButton(android.R.drawable.ic_media_pause) { togglePlayback() }
        transport.addView(playPause)
        transport.addView(iconButton(android.R.drawable.ic_media_next) { next() })
        root.addView(transport)

        container.addView(root)
        updateContent()
    }

    override fun expandedHeightPx(): Int = geometry.expandedHeightMedia

    override fun tint(): IslandTint? = cachedTint

    override fun onPrimaryAction(): Boolean {
        // Tapping the card background just collapses; it must not toggle playback.
        return true
    }

    override fun onDestroy() {
        controller.unregisterCallback(callback)
        cachedTint = null
    }

    private fun updateContent() {
        val metadata = controller.metadata
        titleView?.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        artistView?.text =
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""

        metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { artView?.setImageBitmap(it) }
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        seekBar?.isEnabled = duration > 0
        updateTransport()
        equalizer?.active = playing
    }

    private fun updateTransport() {
        val glyph =
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        playPause?.setImageResource(glyph)
        equalizer?.active = playing
    }

    private fun togglePlayback() {
        val transport = controller.transportControls
        if (playing) transport.pause() else transport.play()
    }

    private fun prev() = controller.transportControls.skipToPrevious()
    private fun next() = controller.transportControls.skipToNext()

    private fun iconButton(glyph: Int, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(glyph)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                geometry.dp(40f), geometry.dp(40f),
            )
        }

    private fun extractTint() {
        val art = controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
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
}
