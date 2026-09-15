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

import android.app.WallpaperManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.max
import org.petalos.config.PetalConfig

// swaps the lock wallpaper for whatever is playing
@SysUISingleton
class PetalLockScreenMediaCover @Inject constructor(
    @Application private val context: Context,
    @Main private val mainExecutor: DelayableExecutor,
) : CoreStartable {

    private val wallpaperManager = WallpaperManager.getInstance(context)
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var enabled = false
    private var coverApplied = false
    private var originalWallpaper: Bitmap? = null

    // fired when the cover art toggles
    fun interface CoverStateListener {
        fun onCoverStateChanged(coverApplied: Boolean)
    }

    private val coverStateListeners = java.util.concurrent.CopyOnWriteArrayList<CoverStateListener>()

    fun addCoverStateListener(listener: CoverStateListener) {
        coverStateListeners.add(listener)
    }

    fun removeCoverStateListener(listener: CoverStateListener) {
        coverStateListeners.remove(listener)
    }

    fun isCoverApplied(): Boolean = coverApplied

    private fun notifyCoverStateChanged() {
        coverStateListeners.forEach { it.onCoverStateChanged(coverApplied) }
    }

    // the controller we attached the callback to
    private var trackedController: MediaController? = null

    // hash of what's already up, avoids redraws
    private var appliedMetaHash = 0

    // one io thread or strictmode yells
    private val applyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // bump this so late decodes get thrown away
    private val applyGeneration = java.util.concurrent.atomic.AtomicInteger()

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = refreshEnabled()
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onSessionsChanged(controllers.orEmpty())
        }

    // skips don't change the session list, so watch the controller
    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val controller = trackedController ?: return
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                applyCoverArt(controller)
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val controller = trackedController ?: return
            if (state?.state == PlaybackState.STATE_PLAYING) {
                applyCoverArt(controller)
            }
        }
    }

    override fun start() {
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_LOCK_MEDIA_COVER),
            false,
            settingsObserver,
        )
        // also watch the cd player, it fights for the lockscreen
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_LOCK_CD_PLAYER),
            false,
            settingsObserver,
        )
        refreshEnabled()
    }

    private fun refreshEnabled() {
        val now = PetalConfig.isLockMediaCoverEnabled(context)
        if (now == enabled) return
        enabled = now
        if (enabled) {
            // only one can own the lockscreen, so kill cd here
            Settings.System.putInt(
                context.contentResolver,
                PetalConfig.KEY_LOCK_CD_PLAYER,
                0,
            )
            // cover art hides the stock media controls
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                0,
            )
            sessionManager.addOnActiveSessionsChangedListener(sessionListener, null, handler)
            reconcile()
        } else {
            // cd has its own controls, leave stock alone
            if (!PetalConfig.isLockCdPlayerEnabled(context)) {
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
                    1,
                )
            }
            sessionManager.removeOnActiveSessionsChangedListener(sessionListener)
            trackedController?.unregisterCallback(mediaCallback)
            trackedController = null
            restoreWallpaperAsync()
        }
    }

    // reboot can leave stale art, so restore first
    private fun reconcile() {
        if (!coverApplied && backupFile().exists()) restoreWallpaperAsync()
        onSessionsChanged(runCatching {
            sessionManager.getActiveSessions(null).orEmpty()
        }.getOrDefault(emptyList()))
    }

    private fun onSessionsChanged(controllers: List<MediaController>) {
        if (!enabled) return
        val playing = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        // track the chosen one so skips refresh art
        if (trackedController != playing) {
            trackedController?.unregisterCallback(mediaCallback)
            trackedController = playing
            playing?.registerCallback(mediaCallback)
        }
        if (playing != null) {
            applyCoverArt(playing)
        } else {
            restoreWallpaperAsync()
        }
    }

    private fun applyCoverArt(controller: MediaController) {
        val metadata = controller.metadata
        if (metadata == null) {
            restoreWallpaperAsync()
            return
        }
        // same art still showing, bail
        if (coverApplied && metadata.hashCode() == appliedMetaHash) return
        val embedded = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (embedded != null) {
            applyAsync { setLockWallpaper(embedded, metadata.hashCode()) }
            return
        }
        val uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.let { Uri.parse(it) }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)?.let { Uri.parse(it) }
        if (uri == null) {
            restoreWallpaperAsync()
            return
        }
        val metaHash = metadata.hashCode()
        val gen = applyGeneration.incrementAndGet()
        // decode on another thread, and drop it if it's stale
        Thread {
            val bitmap = decodeArtwork(uri)
            mainExecutor.execute {
                if (enabled && gen == applyGeneration.get()) {
                    applyAsync {
                        if (bitmap != null) setLockWallpaper(bitmap, metaHash)
                        else restoreWallpaper()
                    }
                }
            }
        }.start()
    }

    // serial executor keeps wallpaper calls in order
    private fun applyAsync(block: () -> Unit) {
        val gen = applyGeneration.incrementAndGet()
        applyExecutor.execute {
            if (gen == applyGeneration.get()) block()
        }
    }

    private fun restoreWallpaperAsync() {
        applyAsync { restoreWallpaper() }
    }

    private fun setLockWallpaper(bitmap: Bitmap, metaHash: Int) {
        runCatching {
            if (!coverApplied) {
                // the backup is the original, never read live art for this
                if (originalWallpaper == null) {
                    originalWallpaper = if (backupFile().exists()) {
                        loadBackupBitmap()
                    } else {
                        readLockWallpaper()
                    }
                }
                if (!backupFile().exists()) {
                    persistBackup(originalWallpaper)
                }
            }
            val scaled = scaleToScreen(bitmap)
            // full-frame rect stops the service recentering
            wallpaperManager.setBitmap(
                scaled,
                Rect(0, 0, scaled.width, scaled.height),
                true,
                WallpaperManager.FLAG_LOCK,
            )
            coverApplied = true
            appliedMetaHash = metaHash
        }.onFailure { Log.w(TAG, "Failed to set lock screen cover art", it) }
        mainExecutor.execute { notifyCoverStateChanged() }
    }

    private fun restoreWallpaper() {
        val backup = backupFile()
        val hasBackup = backup.exists()
        if (!coverApplied && !hasBackup) return
        coverApplied = false
        appliedMetaHash = 0
        val original = originalWallpaper ?: loadBackupBitmap()
        originalWallpaper = null
        runCatching {
            if (original != null) {
                // put the saved pixels back, same full frame
                wallpaperManager.setBitmap(
                    original,
                    Rect(0, 0, original.width, original.height),
                    true,
                    WallpaperManager.FLAG_LOCK,
                )
            } else if (hasBackup) {
                // there was no lock wallpaper, clear it
                wallpaperManager.clear(WallpaperManager.FLAG_LOCK)
            }
        }.onFailure { Log.w(TAG, "Failed to restore lock screen wallpaper", it) }
        if (hasBackup) backup.delete()
        mainExecutor.execute { notifyCoverStateChanged() }
    }

    private fun readLockWallpaper(): Bitmap? = runCatching {
        val file = wallpaperManager.getWallpaperFile(WallpaperManager.FLAG_LOCK) ?: return null
        val bytes = FileInputStream(file.fileDescriptor).use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun backupFile(): File = File(context.filesDir, "petal_lock_media_cover.bin")

    // save the original so a reboot can put it back
    private fun persistBackup(bitmap: Bitmap?) {
        runCatching {
            FileOutputStream(backupFile()).use { out ->
                if (bitmap == null) {
                    out.write(0)
                } else {
                    out.write(1)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }.onFailure { Log.w(TAG, "Failed to persist lock wallpaper backup", it) }
    }

    private fun loadBackupBitmap(): Bitmap? = runCatching {
        val f = backupFile()
        if (!f.exists()) return null
        FileInputStream(f).use { input ->
            if (input.read() != 1) return null
            BitmapFactory.decodeStream(input)
        }
    }.getOrNull()

    // fill the screen and center crop
    private fun scaleToScreen(bitmap: Bitmap): Bitmap {
        val dm = context.resources.displayMetrics
        val targetW = dm.widthPixels.coerceAtLeast(1)
        val targetH = dm.heightPixels.coerceAtLeast(1)
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return bitmap
        val scale = max(targetW / srcW.toFloat(), targetH / srcH.toFloat())
        val cropW = (targetW / scale).toInt().coerceAtLeast(1)
        val cropH = (targetH / scale).toInt().coerceAtLeast(1)
        val cropX = ((srcW - cropW) / 2).coerceIn(0, srcW - cropW)
        val cropY = ((srcH - cropH) / 2).coerceIn(0, srcH - cropH)
        val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
        return Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
    }

    private fun decodeArtwork(uri: Uri): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val req = max(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
        )
        var sample = 1
        while (bounds.outWidth / sample > req || bounds.outHeight / sample > req) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("PetalLockScreenMediaCover: enabled=$enabled coverApplied=$coverApplied")
    }

    companion object {
        private const val TAG = "PetalLockMediaCover"
    }
}
