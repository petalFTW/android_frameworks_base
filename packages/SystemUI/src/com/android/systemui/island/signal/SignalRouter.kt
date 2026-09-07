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

package com.android.systemui.island.signal

import android.os.SystemClock
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.island.Cluster
import com.android.systemui.island.IslandSignal
import com.android.systemui.island.cluster.IslandStateMachine
import javax.inject.Inject

/**
 * Receives all island signals and routes them to the correct cluster's state machine (§10). Also
 * tracks which notification keys the island is handling, for heads-up suppression (§11.1).
 */
@SysUISingleton
class SignalRouter @Inject constructor() {
    private val machines = mutableMapOf<Cluster, IslandStateMachine>()
    private val handledHeadsUpKeys = mutableSetOf<String>()
    private val recentlyOpened = mutableMapOf<String, Long>()

    fun register(cluster: Cluster, machine: IslandStateMachine) {
        machines[cluster] = machine
    }

    fun emit(signal: IslandSignal): Boolean {
        val machine = machines[signal.cluster] ?: return false
        return machine.accept(signal)
    }

    fun removeSignal(id: String) {
        machines.values.forEach { it.remove(id) }
    }

    fun markHeadsUpHandled(key: String) {
        handledHeadsUpKeys.add(key)
    }

    fun clearHeadsUpHandled(key: String) {
        handledHeadsUpKeys.remove(key)
    }

    fun isHandledByIsland(key: String): Boolean = handledHeadsUpKeys.contains(key)

    /** Records that the user just opened a notification so its re-post doesn't re-emerge. */
    fun markOpened(key: String) {
        recentlyOpened[key] = SystemClock.elapsedRealtime()
    }

    /**
     * True when the notification was opened within the last [RECENT_OPEN_WINDOW_MS]. Opening an
     * app often makes it remove + re-post the notification (marking it read), which would
     * otherwise make the island re-emerge as if a brand-new message arrived.
     */
    fun isRecentlyOpened(key: String): Boolean {
        val opened = recentlyOpened[key] ?: return false
        if (SystemClock.elapsedRealtime() - opened > RECENT_OPEN_WINDOW_MS) {
            recentlyOpened.remove(key)
            return false
        }
        return true
    }

    companion object {
        private const val RECENT_OPEN_WINDOW_MS = 3000L
    }
}
