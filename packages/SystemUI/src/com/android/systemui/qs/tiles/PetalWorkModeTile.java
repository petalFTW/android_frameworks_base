/*
 * Copyright (C) 2026 petalOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.tiles;

import android.content.Intent;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import org.petalos.config.PetalConfig;

import javax.inject.Inject;

/** qs flip for work mode notification redaction, remembers the last mode */
public class PetalWorkModeTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "petal_workmode";
    private final ContentObserver mObserver;
    private Context mUserContext;
    private boolean mObserving;

    @Inject
    public PetalWorkModeTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mUserContext = host.getUserContext();
        mObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                refreshState();
            }
        };
    }

    @Override
    protected void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (listening && !mObserving) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(PetalConfig.KEY_WORK_MODE), false,
                    mObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(PetalConfig.KEY_WORK_MODE_LAST), false,
                    mObserver, UserHandle.USER_ALL);
            mObserving = true;
            refreshState();
        } else if (!listening && mObserving) {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
            mObserving = false;
        }
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mUserContext = mContext.createContextAsUser(UserHandle.of(newUserId), 0);
        super.handleUserSwitch(newUserId);
    }

    @Override
    protected void handleDestroy() {
        if (mObserving) {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
            mObserving = false;
        }
        super.handleDestroy();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        // straight into the hub work mode screen
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName("org.petalos.hub", "org.petalos.hub.HubActivity");
        intent.putExtra(":settings:show_fragment",
                "org.petalos.hub.WorkModeFragment");
        return intent;
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        PetalConfig.toggleWorkMode(mUserContext);
        refreshState();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.petal_work_mode_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        int mode = PetalConfig.getWorkMode(mUserContext);
        state.value = mode != PetalConfig.WORK_MODE_OFF;
        state.icon = maybeLoadResourceIcon(state.value
                ? R.drawable.qs_petal_work_mode_icon_on
                : R.drawable.qs_petal_work_mode_icon_off);
        state.label = getTileLabel();
        state.contentDescription = state.label;
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.secondaryLabel = !state.value ? mContext.getString(
                R.string.petal_work_mode_paused_state)
                : mode == PetalConfig.WORK_MODE_FULL
                        ? mContext.getString(R.string.petal_work_mode_full_state)
                        : mContext.getString(R.string.petal_work_mode_content_state);
        state.stateDescription = state.secondaryLabel;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.VIEW_UNKNOWN;
    }
}
