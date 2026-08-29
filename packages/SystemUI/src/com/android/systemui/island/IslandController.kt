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

package com.android.systemui.island

import android.content.Context
import android.content.res.Configuration
import android.media.session.MediaController
import android.view.View
import android.view.WindowManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.island.cluster.IslandState
import com.android.systemui.island.cluster.IslandStateMachine
import com.android.systemui.island.cluster.IslandView
import com.android.systemui.island.presenter.CallPayload
import com.android.systemui.island.presenter.CallPresenter
import com.android.systemui.island.presenter.IslandPresenter
import com.android.systemui.island.presenter.MediaPresenter
import com.android.systemui.island.presenter.NotificationPayload
import com.android.systemui.island.presenter.NotificationPresenter
import com.android.systemui.island.presenter.SystemChipPayload
import com.android.systemui.island.presenter.SystemChipPresenter
import com.android.systemui.island.settings.IslandSettings
import com.android.systemui.island.signal.CallSignalSource
import com.android.systemui.island.signal.ChargingSignalSource
import com.android.systemui.island.signal.MediaSignalSource
import com.android.systemui.island.signal.NotificationSignalSource
import com.android.systemui.island.signal.SignalRouter
import com.android.systemui.island.signal.TorchSignalSource
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Entry point for the dual-anchor liquid-drop dynamic island (§10.1). Owns the window, the two
 * clusters' state machines, the presenters and the signal sources.
 */
@SysUISingleton
class IslandController @Inject constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    @Main private val mainExecutor: DelayableExecutor,
    @Application private val scope: CoroutineScope,
    private val settings: IslandSettings,
    private val configurationController: ConfigurationController,
    private val signalRouter: SignalRouter,
    private val notificationSignalSource: NotificationSignalSource,
    private val mediaSignalSource: MediaSignalSource,
    private val callSignalSource: CallSignalSource,
    private val torchSignalSource: TorchSignalSource,
    private val chargingSignalSource: ChargingSignalSource,
) : CoreStartable {

    private lateinit var geometry: IslandGeometry
    private var window: IslandWindow? = null

    private val leftMachine = IslandStateMachine(Cluster.LEFT, mainExecutor)
    private val rightMachine = IslandStateMachine(Cluster.RIGHT, mainExecutor)

    private val configListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() = applyTheme()
            override fun onThemeChanged() = applyTheme()
        }

    override fun start() {
        settings.start()
        scope.launch {
            settings.enabled.collect { enabled -> onEnabledChanged(enabled) }
        }
        scope.launch {
            settings.animationMode.collect { mode ->
                window?.rootView?.leftIsland?.setAnimationMode(mode)
                window?.rootView?.rightIsland?.setAnimationMode(mode)
            }
        }
    }

    private fun onEnabledChanged(enabled: Boolean) {
        if (enabled) {
            if (window == null) {
                geometry = IslandGeometry(context, windowManager)
                val w = IslandWindow(context, windowManager, geometry)
                window = w
                leftMachine.setNotifDwellMs(settings.notifDwellMs())
                wireMachine(leftMachine, w.rootView.leftIsland)
                wireMachine(rightMachine, w.rootView.rightIsland)
                w.rootView.leftIsland.setAnimationMode(settings.animationMode())
                w.rootView.rightIsland.setAnimationMode(settings.animationMode())
                signalRouter.register(Cluster.LEFT, leftMachine)
                signalRouter.register(Cluster.RIGHT, rightMachine)
                w.addToWindow()
                applyTheme()
                notificationSignalSource.start()
                mediaSignalSource.start()
                callSignalSource.start()
                torchSignalSource.start()
                chargingSignalSource.start()
                configurationController.addCallback(configListener)
            }
        } else {
            window?.let { w ->
                configurationController.removeCallback(configListener)
                w.removeFromWindow()
            }
            window = null
        }
    }

    private fun wireMachine(machine: IslandStateMachine, view: IslandView) {
        machine.listener =
            object : IslandStateMachine.Listener {
                override fun onShow(signal: IslandSignal, form: Form) {
                    val presenter = createPresenter(signal)
                    android.util.Log.d(TAG, "onShow signal=${signal.id} kind=${signal.kind} presenter=${presenter != null}")
                    if (presenter == null) return
                    view.visibility = View.VISIBLE
                    view.show(signal, presenter, form)
                }

                override fun onMorph(signal: IslandSignal) {
                    val presenter = createPresenter(signal) ?: return
                    view.morph(signal, presenter)
                }

                override fun onExpand() = view.expand()

                override fun onCollapse() = view.collapse()

                override fun onDismiss() {
                    view.dismiss()
                }

                override fun onFormChange(form: Form) = Unit
            }

        view.callbacks =
            object : IslandView.Callbacks {
                override fun onExpandedStateChanged(island: IslandView, expanded: Boolean) {
                    // Collision rule: left wins, right collapses to its minimum form.
                    if (island.cluster == Cluster.LEFT && expanded) {
                        val rightView = window?.rootView?.rightIsland ?: return
                        if (rightView.isExpanded) rightMachine.userCollapse()
                    }
                }

                override fun onFormSettled(island: IslandView, expanded: Boolean) {
                    if (expanded) {
                        android.util.Log.d(
                            TAG,
                            "settled EXPANDED left=${island.left} right=${island.right} " +
                                "width=${island.width} screenW=${geometry.screenWidth} " +
                                "sideMargin=${geometry.expandedSideMargin} " +
                                "maxW=${geometry.expandedMaxWidth}",
                        )
                    }
                    machine.notifyTransitionComplete(
                        if (expanded) IslandState.EXPANDED else IslandState.COLLAPSED
                    )
                }

                override fun onUserDismiss(island: IslandView) {
                    machine.userDismiss()
                    island.presenterForSignal()?.onDismiss()
                }

                override fun onUserExpand(island: IslandView) = machine.userExpand()

                override fun onUserPrimaryAction(island: IslandView) {
                    val handled = island.presenterForSignal()?.onPrimaryAction() == true
                    if (handled) machine.userCollapse()
                }
            }
    }

    private fun createPresenter(signal: IslandSignal): IslandPresenter? {
        return when (signal.kind) {
            SignalKind.NOTIFICATION -> {
                val payload = signal.payload as? NotificationPayload ?: return null
                NotificationPresenter(context, geometry, payload)
            }
            SignalKind.MEDIA -> {
                val controller = signal.payload as? MediaController ?: return null
                MediaPresenter(context, geometry, controller)
            }
            SignalKind.CALL_INCOMING, SignalKind.CALL_ONGOING -> {
                val payload = signal.payload as? CallPayload ?: return null
                CallPresenter(context, geometry, payload)
            }
            SignalKind.TORCH,
            SignalKind.CHARGING,
            SignalKind.VOLUME,
            SignalKind.SCREEN_RECORDING,
            SignalKind.HOTSPOT,
            SignalKind.CAST,
            SignalKind.DND,
            SignalKind.NFC,
            -> {
                val payload = signal.payload as? SystemChipPayload ?: return null
                SystemChipPresenter(context, geometry, payload)
            }
            else -> null
        }
    }

    private fun applyTheme() {
        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window?.rootView?.setDarkMode(dark)
        window?.rootView?.leftIsland?.onConfigurationChanged(dark)
        window?.rootView?.rightIsland?.onConfigurationChanged(dark)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("IslandController:")
        pw.println("  enabled=${settings.isEnabled()} animMode=${settings.animationMode()}")
        pw.println("  LEFT: state=${leftMachine.state} signal=${leftMachine.current?.id}")
        pw.println("  RIGHT: state=${rightMachine.state} signal=${rightMachine.current?.id}")
    }

    companion object {
        private const val TAG = "IslandController"
    }
}
