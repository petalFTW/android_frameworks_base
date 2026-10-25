/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.island.presenter

import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.ViewGroup
import android.widget.TextView
import com.android.systemui.petalos.PetalWorkRedaction
import com.android.systemui.res.R
import java.lang.ref.WeakReference

// Keep already-open Island cards in step with the shade, without replaying the popup.
internal class IslandRedactionBinder(
    private val context: Context,
    private val packageName: String,
    private val notification: StatusBarNotification?,
    private val onChanged: () -> Unit = {},
) {
    private val roots = mutableListOf<WeakReference<ViewGroup>>()
    private var observing = false
    private val listener = Runnable {
        refresh()
        onChanged()
    }

    val mode: Int
        get() = PetalWorkRedaction.modeFor(context, packageName, notification?.notification)

    fun bind(root: ViewGroup) {
        roots.removeAll { it.get() == null }
        if (roots.none { it.get() === root }) roots.add(WeakReference(root))
        if (!observing) {
            PetalWorkRedaction.addChangeListener(context, listener)
            observing = true
        }
        refresh()
    }

    private fun refresh() {
        roots.removeAll { it.get() == null }
        roots.mapNotNull { it.get() }.forEach {
            PetalWorkRedaction.applyIsland(context, it, packageName, notification?.notification)
        }
    }

    fun destroy() {
        if (observing) PetalWorkRedaction.removeChangeListener(listener)
        observing = false
        roots.clear()
    }
}

internal fun TextView.keepIdentityWhenContentRedacted() {
    setTag(R.id.petal_redaction_identity, true)
}
