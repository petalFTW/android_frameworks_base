/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.view

import android.companion.virtualdevice.flags.Flags
import android.content.Context
import android.graphics.drawable.AnimatedStateListDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.AttributeSet
import android.util.StateSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.android.systemui.common.ui.view.TouchHandlingView
import com.android.systemui.log.TouchHandlingViewLogger
import com.android.systemui.res.R

class DeviceEntryIconView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttrs: Int = 0,
    logger: TouchHandlingViewLogger? = null,
) : FrameLayout(context, attrs, defStyleAttrs) {

    val touchHandlingView: TouchHandlingView =
        TouchHandlingView(
            context = context,
            attrs = attrs,
            longPressDuration = {
                if (Flags.viewconfigurationApis())
                    ViewConfiguration.get(context).longPressTimeoutMillis.toLong()
                else ViewConfiguration.getLongPressTimeout().toLong()
            },
            allowedTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop(),
            logger = logger,
        )
    val iconView: FingerprintBlobView =
        FingerprintBlobView(context, attrs).apply { id = R.id.device_entry_icon_fg }
    val bgView: ImageView = ImageView(context, attrs).apply { id = R.id.device_entry_icon_bg }
    val aodFpDrawable: LottieDrawable = LottieDrawable()
    var accessibilityHintType: AccessibilityHintType = AccessibilityHintType.NONE

    // set by the binder so taps only splash when a fingerprint icon is up
    var isFingerprintIcon: Boolean = false
        private set

    private var usesGlassBackground = false

    var backgroundProtectionAlpha: Float = 0f
        set(value) {
            field = value
            bgView.alpha = if (usesGlassBackground) 0f else value
        }

    private var animatedIconDrawable: AnimatedStateListDrawable = AnimatedStateListDrawable()

    init {
        // let the ripple spill past the icon instead of getting chopped off
        clipChildren = false
        setupIconStates()
        setupIconTransitions()
        setupAccessibilityDelegate()

        // background, icon, then the touch catcher on top
        addBgImageView()
        addIconImageView()
        addTouchHandlingView()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Grow the glass, not the sensor's actual touch box.
        val minimum = iconView.minimumGlassSizePx
        iconView.measure(
            MeasureSpec.makeMeasureSpec(maxOf(measuredWidth, minimum), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxOf(measuredHeight, minimum), MeasureSpec.EXACTLY),
        )
    }

    fun setIconAppearance(type: IconType, useAodVariant: Boolean, color: Int) {
        isFingerprintIcon = type == IconType.FINGERPRINT
        usesGlassBackground = type != IconType.NONE
        bgView.alpha = if (usesGlassBackground) 0f else backgroundProtectionAlpha
        iconView.setIconState(type, useAodVariant, color)
    }

    // grow while the finger is parked on the print
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isFingerprintIcon) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> iconView.setFingerPressed(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    iconView.setFingerPressed(false)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setupAccessibilityDelegate() {
        accessibilityDelegate =
            object : AccessibilityDelegate() {
                private val accessibilityBouncerHint =
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        resources.getString(R.string.accessibility_bouncer),
                    )
                private val accessibilityEnterHint =
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfoCompat.ACTION_CLICK,
                        resources.getString(R.string.accessibility_enter_hint),
                    )

                override fun onInitializeAccessibilityNodeInfo(
                    v: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(v, info)
                    when (accessibilityHintType) {
                        AccessibilityHintType.BOUNCER -> info.addAction(accessibilityBouncerHint)
                        AccessibilityHintType.ENTER -> info.addAction(accessibilityEnterHint)
                        AccessibilityHintType.NONE -> return
                    }
                }
            }
    }

    // wire the lock/unlock/fp drawables, aod flavours included
    private fun setupIconStates() {
        // Lockscreen States
        // LOCK
        animatedIconDrawable.addState(
            getIconState(IconType.LOCK, false),
            context.getDrawable(R.drawable.ic_lock)!!,
            R.id.locked,
        )
        // UNLOCK
        animatedIconDrawable.addState(
            getIconState(IconType.UNLOCK, false),
            context.getDrawable(R.drawable.ic_unlocked)!!,
            R.id.unlocked,
        )
        // FINGERPRINT
        animatedIconDrawable.addState(
            getIconState(IconType.FINGERPRINT, false),
            context.getDrawable(R.drawable.ic_fingerprint)!!,
            R.id.locked_fp,
        )

        // AOD states
        // LOCK
        animatedIconDrawable.addState(
            getIconState(IconType.LOCK, true),
            context.getDrawable(R.drawable.ic_lock_aod)!!,
            R.id.locked_aod,
        )
        // UNLOCK
        animatedIconDrawable.addState(
            getIconState(IconType.UNLOCK, true),
            context.getDrawable(R.drawable.ic_unlocked_aod)!!,
            R.id.unlocked_aod,
        )
        // FINGERPRINT
        LottieCompositionFactory.fromRawRes(mContext, R.raw.udfps_aod_fp).addListener { result ->
            aodFpDrawable.setComposition(result)
        }
        animatedIconDrawable.addState(
            getIconState(IconType.FINGERPRINT, true),
            aodFpDrawable,
            R.id.udfps_aod_fp,
        )

        // Catch-all goes last or it eats the whole bloody list.
        animatedIconDrawable.addState(
            StateSet.WILD_CARD,
            context.getDrawable(R.color.transparent)!!,
            R.id.no_icon,
        )
    }

    private fun setupIconTransitions() {
        // LockscreenLocked <=> AodLocked
        animatedIconDrawable.addTransition(
            R.id.locked_aod,
            R.id.locked,
            context.getDrawable(R.drawable.lock_aod_to_ls) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.locked,
            R.id.locked_aod,
            context.getDrawable(R.drawable.lock_ls_to_aod) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenUnlocked <=> AodUnlocked
        animatedIconDrawable.addTransition(
            R.id.unlocked_aod,
            R.id.unlocked,
            context.getDrawable(R.drawable.unlocked_aod_to_ls) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.unlocked_aod,
            context.getDrawable(R.drawable.unlocked_ls_to_aod) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenLocked <=> LockscreenUnlocked
        animatedIconDrawable.addTransition(
            R.id.locked,
            R.id.unlocked,
            context.getDrawable(R.drawable.lock_to_unlock) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.locked,
            context.getDrawable(R.drawable.unlocked_to_locked) as AnimatedVectorDrawable,
            /* reversible */ false,
        )

        // LockscreenUnlocked <=> AodLocked
        animatedIconDrawable.addTransition(
            R.id.unlocked,
            R.id.locked_aod,
            context.getDrawable(R.drawable.unlocked_to_aod_lock) as AnimatedVectorDrawable,
            /* reversible */ false,
        )
    }

    private fun addTouchHandlingView() {
        addView(touchHandlingView)
        val lp = touchHandlingView.layoutParams as LayoutParams
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        touchHandlingView.layoutParams = lp
    }

    private fun addIconImageView() {
        iconView.scaleType = ImageView.ScaleType.CENTER_CROP
        iconView.setImageDrawable(animatedIconDrawable)
        addView(iconView)
        val lp = iconView.layoutParams as LayoutParams
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.CENTER
        iconView.layoutParams = lp
    }

    private fun addBgImageView() {
        bgView.setImageDrawable(context.getDrawable(R.drawable.fingerprint_bg))
        addView(bgView)
        val lp = bgView.layoutParams as LayoutParams
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        bgView.layoutParams = lp
        bgView.alpha = 0f
    }

    fun getIconState(icon: IconType, aod: Boolean): IntArray {
        val lockIconState = IntArray(2)
        when (icon) {
            IconType.LOCK -> lockIconState[0] = android.R.attr.state_first
            IconType.UNLOCK -> lockIconState[0] = android.R.attr.state_last
            IconType.FINGERPRINT -> lockIconState[0] = android.R.attr.state_middle
            IconType.NONE -> return StateSet.NOTHING
        }
        if (aod) {
            lockIconState[1] = android.R.attr.state_single
        } else {
            lockIconState[1] = -android.R.attr.state_single
        }
        return lockIconState
    }

    enum class IconType(val contentDescriptionResId: Int) {
        LOCK(R.string.accessibility_lock_icon),
        UNLOCK(R.string.accessibility_unlock_button),
        FINGERPRINT(R.string.accessibility_fingerprint_label),
        NONE(-1),
    }

    enum class AccessibilityHintType {
        NONE,
        BOUNCER,
        ENTER,
    }
}
