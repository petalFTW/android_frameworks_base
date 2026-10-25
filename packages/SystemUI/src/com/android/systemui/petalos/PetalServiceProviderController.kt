/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos

import android.app.compat.gms.GmsCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.ext.PackageId
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.concurrency.DelayableExecutor
import javax.inject.Inject

// the installed provider decides this, not whichever Hub screen was opened last
@SysUISingleton
class PetalServiceProviderController @Inject constructor(
    @Application private val context: Context,
    @Main private val executor: DelayableExecutor,
    private val userTracker: UserTracker,
) : CoreStartable {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, userTracker.userId)
            if (userId < 0) return
            reconcile(this@PetalServiceProviderController.context.createContextAsUser(
                UserHandle.of(userId), 0))
        }
    }
    private val userCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) = reconcile(userContext)
    }

    override fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
            addDataSchemeSpecificPart(PackageId.GMS_CORE_NAME, android.os.PatternMatcher.PATTERN_LITERAL)
        }
        context.registerReceiverAsUser(receiver, UserHandle.ALL, filter, null,
            Handler(Looper.getMainLooper()))
        userTracker.addCallback(userCallback, executor)
        reconcile(userTracker.userContext)
    }

    private fun reconcile(userContext: Context) {
        val pm = userContext.packageManager
        try {
            pm.getApplicationInfo(COMPAT, PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        val enabled = try {
            val core = pm.getApplicationInfo(PackageId.GMS_CORE_NAME, 0)
            core.enabled && !core.isPrivilegedApp &&
                core.ext().packageId == PackageId.GMS_CORE && !GmsCompat.isMicrogProvider(userContext)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getApplicationEnabledSetting(COMPAT) == state) return
        // no DONT_KILL_APP here: the stale foreground service must actually leave
        runCatching { pm.setApplicationEnabledSetting(COMPAT, state, 0) }
            .onFailure { Log.w("PetalServices", "Couldn't reconcile GMSCompat for user ${userContext.userId}", it) }
    }

    private companion object {
        const val COMPAT = "app.grapheneos.gmscompat"
    }
}
