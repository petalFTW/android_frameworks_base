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
import android.graphics.Rect
import android.media.session.MediaController
import android.view.View
import android.view.WindowManager
import androidx.core.view.doOnLayout
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
import com.android.systemui.island.signal.ExtraKeySignalSource
import com.android.systemui.island.signal.CallSignalSource
import com.android.systemui.island.signal.ChargingSignalSource
import com.android.systemui.island.signal.MediaSignalSource
import com.android.systemui.island.signal.NotificationSignalSource
import com.android.systemui.island.signal.SignalRouter
import com.android.systemui.island.signal.TorchSignalSource
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shade.ShadeStateListener
import com.android.systemui.shade.STATE_CLOSED
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// top-level wiring; window, state machines, presenters
@SysUISingleton
class IslandController @Inject constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    @Main private val mainExecutor: DelayableExecutor,
    @Application private val scope: CoroutineScope,
    private val settings: IslandSettings,
    private val configurationController: ConfigurationController,
    private val shadeExpansionStateManager: ShadeExpansionStateManager,
    private val shadeInteractor: ShadeInteractor,
    private val keyguardStateController: KeyguardStateController,
    private val activityStarter: ActivityStarter,
    private val signalRouter: SignalRouter,
    private val statusBarIconHider: StatusBarIconHider,
    private val notificationSignalSource: NotificationSignalSource,
    private val mediaSignalSource: MediaSignalSource,
    private val callSignalSource: CallSignalSource,
    private val torchSignalSource: TorchSignalSource,
    private val chargingSignalSource: ChargingSignalSource,
    private val extraKeySignalSource: ExtraKeySignalSource,
    private val miniNotifSource: com.android.systemui.island.render.IslandMiniNotifSource,
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

    // shade brings the icons back, so re-hide after every change
    private val shadeStateListener =
        ShadeStateListener { state ->
            refreshStatusBarCoverage()
            if (state == STATE_CLOSED) {
                mainExecutor.executeDelayed({ refreshStatusBarCoverage() }, 400L)
            }
        }

    // keyguard does the same thing; hide again once it settles
    private val keyguardStateCallback =
        object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() {
                refreshStatusBarCoverage()
                mainExecutor.executeDelayed({ refreshStatusBarCoverage() }, 400L)
            }
        }

    // bridge says a crossfade started, spray the media blob
    private val bridgeTransitionReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                val duration = intent?.getIntExtra(EXTRA_TRANSITION_DURATION, 10000)
                    ?.toLong() ?: 10000L
                val root = window?.rootView ?: return
                if (leftMachine.current?.kind == SignalKind.MEDIA) {
                    root.leftIsland.startChromaShimmer(duration)
                }
                if (rightMachine.current?.kind == SignalKind.MEDIA) {
                    root.rightIsland.startChromaShimmer(duration)
                }
            }
        }

    override fun start() {
        settings.start()
        extraKeySignalSource.start()
        context.registerReceiver(
            bridgeTransitionReceiver,
            android.content.IntentFilter(ACTION_BRIDGE_TRANSITION),
            Context.RECEIVER_EXPORTED,
        )
        shadeExpansionStateManager.addStateListener(shadeStateListener)
        keyguardStateController.addCallback(keyguardStateCallback)
        scope.launch {
            settings.enabled.collect { enabled -> onEnabledChanged(enabled) }
        }
        scope.launch {
            settings.animationMode.collect { mode ->
                window?.rootView?.leftIsland?.setAnimationMode(mode)
                window?.rootView?.rightIsland?.setAnimationMode(mode)
            }
        }
        // shade/qs re-show icons, keep re-asserting
        scope.launch {
            shadeInteractor.isAnyExpanded.collect { refreshStatusBarCoverage() }
        }
        scope.launch {
            shadeInteractor.isQsExpanded.collect { refreshStatusBarCoverage() }
        }
    }

    private fun onEnabledChanged(enabled: Boolean) {
        if (enabled) {
            if (window == null) {
                geometry = IslandGeometry(context, windowManager)
                val w = IslandWindow(context, windowManager, geometry, miniNotifSource)
                window = w
                leftMachine.setNotifDwellMs(settings.notifDwellMs())
                w.rootView.onOutsideTouch = {
                    // tap outside closes whatever is open
                    if (w.rootView.leftIsland.isExpanded) leftMachine.userCollapse()
                    if (w.rootView.rightIsland.isExpanded) rightMachine.userCollapse()
                }
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
                statusBarIconHider.updateCoverage(null, null)
                w.removeFromWindow()
            }
            window = null
        }
    }

    private fun wireMachine(machine: IslandStateMachine, view: IslandView) {
        val host =
            object : IslandPresenter.Host {
                override fun setWindowFocusable(focusable: Boolean) {
                    window?.setFocusable(focusable)
                }

                override fun setDismissalHeld(held: Boolean) {
                    machine.holdDismissal(held)
                }

                override fun launchPendingIntent(pendingIntent: android.app.PendingIntent) {
                    // dismiss keyguard first, otherwise the pi goes nowhere
                    activityStarter.startPendingIntentDismissingKeyguard(pendingIntent)
                }
            }

        machine.listener =
            object : IslandStateMachine.Listener {
                override fun onShow(signal: IslandSignal, form: Form) {
                    val presenter = createPresenter(signal)
                    android.util.Log.d(TAG, "onShow signal=${signal.id} kind=${signal.kind} presenter=${presenter != null}")
                    if (presenter == null) return
                    presenter.setHost(host)
                    view.visibility = View.VISIBLE
                    view.show(signal, presenter, form)
                    refreshStatusBarCoverage()
                    // relayout can move the blob, recheck after
                    view.doOnLayout { refreshStatusBarCoverage() }
                }

                override fun onMorph(signal: IslandSignal) {
                    val presenter = createPresenter(signal) ?: return
                    presenter.setHost(host)
                    view.morph(signal, presenter)
                }

                override fun onExpand() {
                    view.expand()
                    refreshStatusBarCoverage()
                }

                override fun onCollapse() {
                    view.collapse()
                    refreshStatusBarCoverage()
                }

                override fun onDismiss() {
                    view.dismiss()
                    refreshStatusBarCoverage()
                    // dissolve takes a moment, check again when it's done
                    mainExecutor.executeDelayed({ refreshStatusBarCoverage() }, 260L)
                }

                override fun onFormChange(form: Form) = Unit
            }

        view.callbacks =
            object : IslandView.Callbacks {
                override fun onExpandedStateChanged(island: IslandView, expanded: Boolean) {
                    // left wins collisions. don't ask
                    if (island.cluster == Cluster.LEFT && expanded) {
                        val rightView = window?.rootView?.rightIsland ?: return
                        if (rightView.isExpanded) rightMachine.userCollapse()
                    }
                    refreshStatusBarCoverage()
                }

                override fun onFormSettled(island: IslandView, expanded: Boolean) {
                    refreshStatusBarCoverage()
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
                    // grab the id before dismiss replaces it
                    val signalId = machine.current?.id
                    machine.userDismiss()
                    island.presenterForSignal()?.onDismiss()
                    // a real swipe cancels the notif, timed expiry must not
                    if (signalId?.startsWith(NOTIF_ID_PREFIX) == true) {
                        notificationSignalSource.cancelFromIsland(
                            signalId.removePrefix(NOTIF_ID_PREFIX)
                        )
                    }
                }

                override fun onUserExpand(island: IslandView) = machine.userExpand()

                override fun onUserPrimaryAction(island: IslandView) {
                    val signalId = machine.current?.id
                    val handled = island.presenterForSignal()?.onPrimaryAction() == true
                    if (handled) {
                        // mark it opened so it doesn't pop back up
                        if (signalId?.startsWith(NOTIF_ID_PREFIX) == true) {
                            signalRouter.markOpened(signalId.removePrefix(NOTIF_ID_PREFIX))
                        }
                        machine.userCollapse()
                    }
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
            SignalKind.EXTRA_KEY,
            -> {
                val payload = signal.payload as? SystemChipPayload ?: return null
                SystemChipPresenter(context, geometry, payload)
            }
            else -> null
        }
    }

    // hide the bar icons the blob is sitting on
    private fun refreshStatusBarCoverage() {
        val root = window?.rootView ?: return
        statusBarIconHider.updateCoverage(
            coverageRect(root.leftIsland),
            coverageRect(root.rightIsland),
        )
    }

    private fun coverageRect(island: IslandView): Rect? {
        if (island.visibility != View.VISIBLE || island.alpha < 0.5f || island.width <= 0) {
            return null
        }
        val r = Rect()
        island.getBoundsOnScreen(r)
        r.inset(-geometry.dp(4f), -geometry.dp(4f))
        return r
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
        private const val NOTIF_ID_PREFIX = "notif:"
        private const val ACTION_BRIDGE_TRANSITION = "org.rab1d.bridge.action.TRANSITION"
        private const val EXTRA_TRANSITION_DURATION = "duration"
    }
}
