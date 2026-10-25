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
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
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
import com.android.systemui.island.signal.CalendarSignalSource
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
import org.petalos.config.PetalConfig

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
    private val calendarSignalSource: CalendarSignalSource,
    private val mediaSignalSource: MediaSignalSource,
    private val callSignalSource: CallSignalSource,
    private val torchSignalSource: TorchSignalSource,
    private val chargingSignalSource: ChargingSignalSource,
    private val extraKeySignalSource: ExtraKeySignalSource,
    private val miniNotifSource: com.android.systemui.island.render.IslandMiniNotifSource,
) : CoreStartable {

    private lateinit var geometry: IslandGeometry
    private var window: IslandWindow? = null
    // park the blobs as soon as the shade starts moving
    private var qsOpen = false
    private val coverageDrawListener = ViewTreeObserver.OnPreDrawListener {
        refreshStatusBarCoverage()
        true
    }
    private val blurSettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val root = window?.rootView ?: return
            root.leftIsland.refreshBackdropBlur()
            root.rightIsland.refreshBackdropBlur()
            root.leftIsland.refreshGlassStyle()
            root.rightIsland.refreshGlassStyle()
        }
    }

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
                val owner = intent?.getStringExtra(EXTRA_TRANSITION_OWNER)
                val style = intent?.getIntExtra(EXTRA_TRANSITION_STYLE, -1) ?: -1
                val root = window?.rootView ?: return
                // app automix gets the aura, old bridge style 1 still gets the shimmer
                val appMix = !owner.isNullOrEmpty() ||
                    automixPackage() != null
                val shimmer = !appMix && style == BRIDGE_STYLE_REVERB
                if (!appMix && !shimmer) return
                val spray: (com.android.systemui.island.cluster.IslandView) -> Unit = { island ->
                    if (appMix) island.startChromaAura(duration)
                    else island.startChromaShimmer(duration)
                }
                fun matches(signal: IslandSignal?): Boolean = signal?.kind == SignalKind.MEDIA &&
                    (owner.isNullOrBlank() ||
                        (signal.payload as? android.media.session.MediaController)?.packageName == owner)
                if (matches(leftMachine.current)) spray(root.leftIsland)
                if (matches(rightMachine.current)) spray(root.rightIsland)
            }
        }

    // package that owns automix right now, null when the rom bridge does
    private fun automixPackage(): String? = runCatching {
        android.provider.Settings.System.getString(
            context.contentResolver,
            "petal_bridge_automix_pkg",
        )
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun start() {
        settings.start()
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_ISLAND_BLUR_INTENSITY),
            false, blurSettingsObserver,
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_ISLAND_BUBBLE),
            false, blurSettingsObserver,
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(PetalConfig.KEY_ISLAND_REFRACTION),
            false, blurSettingsObserver,
        )
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
        // shade re-shows icons, keep re-asserting
        scope.launch {
            shadeInteractor.isAnyExpanded.collect { refreshStatusBarCoverage() }
        }
        // QQS shows on the first swipe, not just when full QS opens
        scope.launch {
            shadeInteractor.anyExpansion.collect { expansion ->
                qsOpen = expansion > 0f
                applyBlobHidden(qsOpen)
            }
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
                w.rootView.viewTreeObserver.addOnPreDrawListener(coverageDrawListener)
                applyTheme()
                notificationSignalSource.start()
                calendarSignalSource.start()
                mediaSignalSource.start()
                callSignalSource.start()
                torchSignalSource.start()
                chargingSignalSource.start()
                configurationController.addCallback(configListener)
            }
        } else {
            window?.let { w ->
                configurationController.removeCallback(configListener)
                statusBarIconHider.updateCoverage(null, 0f, null, 0f)
                w.rootView.viewTreeObserver.takeIf { it.isAlive }
                    ?.removeOnPreDrawListener(coverageDrawListener)
                w.removeFromWindow()
            }
            window = null
        }
    }

    private fun wireMachine(machine: IslandStateMachine, view: IslandView) {
        var shownKind: SignalKind? = null
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
                    shownKind = signal.kind
                    presenter.setHost(host)
                    view.visibility = View.VISIBLE
                    view.show(signal, presenter, form)
                    // a blob that pops up while qs is open needs to stay parked
                    view.slideAway(qsOpen, animate = false)
                    refreshStatusBarCoverage()
                    // relayout can move the blob, recheck after
                    view.doOnLayout { refreshStatusBarCoverage() }
                }

                override fun onMorph(signal: IslandSignal) {
                    val presenter = createPresenter(signal) ?: return
                    val answered = shownKind == SignalKind.CALL_INCOMING &&
                        signal.kind == SignalKind.CALL_ONGOING
                    shownKind = signal.kind
                    presenter.setHost(host)
                    view.morph(signal, presenter)
                    if (answered && view.isExpanded) {
                        machine.userCollapse()
                    }
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
                override fun onUserTouchChanged(island: IslandView, touching: Boolean) {
                    machine.holdTouch(touching)
                }

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
                    val dismissedPresenter = island.presenterForSignal()
                    machine.userDismiss()
                    dismissedPresenter?.onDismiss()
                    // a real swipe cancels the notif, timed expiry must not
                    if (signalId?.startsWith(NOTIF_ID_PREFIX) == true) {
                        notificationSignalSource.cancelFromIsland(
                            signalId.removePrefix(NOTIF_ID_PREFIX)
                        )
                    }
                }

                override fun onUserExpand(island: IslandView) = machine.userExpand()

                override fun onUserCollapse(island: IslandView) = machine.userCollapse()

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

    // shove both blobs off-screen while qs is showing
    private fun applyBlobHidden(hidden: Boolean, animate: Boolean = true) {
        val root = window?.rootView ?: return
        root.leftIsland.slideAway(hidden, animate)
        root.rightIsland.slideAway(hidden, animate)
    }

    // hide the bar icons the blob is sitting on, at the blob's own progress
    private fun refreshStatusBarCoverage() {
        val root = window?.rootView ?: return
        val left = root.leftIsland
        val right = root.rightIsland
        statusBarIconHider.updateCoverage(
            coverageRect(left), left.presenceProgress(),
            coverageRect(right), right.presenceProgress(),
        )
    }

    private fun coverageRect(island: IslandView): Rect? {
        if (island.visibility != View.VISIBLE || island.width <= 0) {
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
        private const val EXTRA_TRANSITION_STYLE = "style"
        private const val EXTRA_TRANSITION_OWNER = "owner"
        private const val BRIDGE_STYLE_REVERB = 1
    }
}
