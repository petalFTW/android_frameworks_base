/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.shade

import android.os.RemoteException
import android.util.Log
import androidx.annotation.MainThread
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.recents.ILauncherProxy
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Streams the combined panel/QS progress only for the lifetime of the connected Home binder. */
@SysUISingleton
class HomeShadeTransitionBridge
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val shadeInteractor: Provider<ShadeInteractor>,
) {
    private var collection: Job? = null

    @MainThread
    fun start(proxy: ILauncherProxy) {
        stop()
        collection = scope.launch {
            val shade = shadeInteractor.get()
            // anyExpansion is max(shade, QS), so switching between the two panels cannot replay
            // a spurious closed frame. StateFlow also replays the current pose on reconnection.
            combine(shade.anyExpansion, shade.isUserInteracting) { expansion, tracking ->
                    expansion to tracking
                }
                .distinctUntilChanged()
                .collect { (expansion, tracking) ->
                    try {
                        proxy.onShadeExpansionChanged(expansion, tracking)
                    } catch (error: RemoteException) {
                        Log.w("HomeShadeTransition", "Home progress binder unavailable", error)
                        throw CancellationException("Home disconnected").apply { initCause(error) }
                    }
                }
        }
    }

    @MainThread
    fun stop() {
        collection?.cancel()
        collection = null
    }
}
