/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.statusbar.notification.stack;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.AnimatorTestRule;
import com.android.systemui.flags.DisableSceneContainer;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@DisableSceneContainer
@RunWith(AndroidJUnit4.class)
@RunWithLooper
public class PetalNotificationCollectionTest extends SysuiTestCase {
    @Rule public final AnimatorTestRule mAnimatorRule = new AnimatorTestRule(this);
    private NotificationStackScrollLayout mHost;
    private AmbientState mAmbient;
    private MediaContainerView mMedia;
    private PetalNotificationCollection mCollection;
    private float mDensity;

    @Before
    public void setUp() {
        allowTestableLooperAsMainThread();
        mDensity = mContext.getResources().getDisplayMetrics().density;
        mHost = mock(NotificationStackScrollLayout.class);
        when(mHost.getContext()).thenReturn(mContext);
        when(mHost.getResources()).thenReturn(mContext.getResources());
        when(mHost.getHeight()).thenReturn(px(1000));
        mAmbient = mock(AmbientState.class);
        when(mAmbient.isOnKeyguard()).thenReturn(true);
        when(mAmbient.getStackY()).thenReturn((float) px(100));
        when(mAmbient.getInnerHeight()).thenReturn(px(800));
        mMedia = mock(MediaContainerView.class);
        when(mMedia.getVisibility()).thenReturn(View.VISIBLE);
        when(mMedia.getIntrinsicHeight()).thenReturn(px(300));
        ExpandableViewState mediaState = new ExpandableViewState();
        mediaState.setAlpha(1f);
        mediaState.setYTranslation(px(100));
        when(mMedia.getViewState()).thenReturn(mediaState);
        mCollection = new PetalNotificationCollection(mHost);
    }

    @Test
    public void differentHeightCards_peekBelowFrontWithoutCoveringItsContent() {
        ExpandableNotificationRow front = row(80);
        ExpandableNotificationRow second = row(100);
        ExpandableNotificationRow third = row(60);
        children(mMedia, front, second, third);

        mCollection.updateStates(mAmbient);

        ExpandableViewState first = front.getViewState();
        ExpandableViewState next = second.getViewState();
        ExpandableViewState back = third.getViewState();
        float frontBottom = first.getYTranslation() + first.height;
        assertThat(next.getYTranslation() + next.height * next.getScaleY())
                .isWithin(.01f).of(frontBottom + px(8));
        assertThat(back.getYTranslation() + back.height * back.getScaleY())
                .isWithin(.01f).of(frontBottom + px(16));
        assertThat(first.getZTranslation()).isGreaterThan(next.getZTranslation());
        assertThat(next.getZTranslation()).isGreaterThan(back.getZTranslation());
        assertThat(first.getScaleX()).isEqualTo(1f);
        assertThat(next.getScaleX()).isGreaterThan(back.getScaleX());
    }

    @Test
    public void collapsedCollection_hidesFourthCardAndRetainsPrivacyState() {
        ExpandableNotificationRow front = row(80);
        ExpandableNotificationRow second = row(80);
        ExpandableNotificationRow third = row(80);
        ExpandableNotificationRow fourth = row(80);
        front.getViewState().hideSensitive = true;
        children(mMedia, front, second, third, fourth);

        mCollection.updateStates(mAmbient);

        assertThat(front.getViewState().hideSensitive).isTrue();
        assertThat(fourth.getViewState().hidden).isTrue();
        assertThat(fourth.getViewState().getAlpha()).isEqualTo(0f);
        verify(second).setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    @Test
    public void playerDisappears_restoresCardScaleAndAccessibility() {
        ExpandableNotificationRow front = row(80);
        ExpandableNotificationRow rear = row(80);
        children(mMedia, front, rear);
        mCollection.updateStates(mAmbient);

        when(mMedia.getVisibility()).thenReturn(View.GONE);
        mCollection.updateStates(mAmbient);

        assertThat(mCollection.isActive()).isFalse();
        assertThat(rear.getViewState().getScaleX()).isEqualTo(1f);
        verify(rear).setScaleX(1f);
        verify(rear).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        assertThat(mCollection.getHeight(mAmbient, false)).isEqualTo(-1f);
    }

    @Test
    public void noNotifications_doesNotReserveCollectionSpace() {
        children(mMedia);

        assertThat(mCollection.getHeight(mAmbient, false)).isEqualTo(-1f);
        assertThat(mCollection.isActive()).isFalse();
    }

    @Test
    public void dozing_leavesPulsingNotificationLayoutAlone() {
        ExpandableNotificationRow front = row(80);
        children(mMedia, front);
        when(mAmbient.isDozing()).thenReturn(true);

        mCollection.updateStates(mAmbient);

        assertThat(mCollection.isActive()).isFalse();
        assertThat(front.getViewState().getYTranslation()).isEqualTo(0f);
    }

    @Test
    public void collectionGesture_keepsCancelOwnedUntilNextDown() {
        children(mMedia, row(80));
        mCollection.updateStates(mAmbient);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, px(160), px(430), 0);
        MotionEvent cancel = MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL,
                px(160), px(430), 0);
        MotionEvent outside = MotionEvent.obtain(20, 20, MotionEvent.ACTION_DOWN, 0, 0, 0);
        try {
            assertThat(mCollection.onTouch(down)).isTrue();
            assertThat(mCollection.ownsTouch()).isTrue();
            mCollection.onTouch(cancel);
            assertThat(mCollection.ownsTouch()).isTrue();
            assertThat(mCollection.onTouch(outside)).isFalse();
            assertThat(mCollection.ownsTouch()).isFalse();
        } finally {
            down.recycle();
            cancel.recycle();
            outside.recycle();
        }
    }

    @Test
    public void unfold_keepsPanelBudgetAndExpandedAnchorStable() {
        ExpandableNotificationRow front = row(80);
        children(mMedia, front);
        mCollection.updateStates(mAmbient);
        float budget = mCollection.getHeight(mAmbient, false);
        mCollection.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND);
        mAnimatorRule.advanceAnimationDuration(320);
        mCollection.updateStates(mAmbient);

        assertThat(mCollection.getHeight(mAmbient, false)).isWithin(.01f).of(budget);
        float expandedY = front.getViewState().getYTranslation();
        when(mAmbient.getStackY()).thenReturn((float) px(200));
        mMedia.getViewState().setYTranslation(px(200));
        mCollection.updateStates(mAmbient);
        assertThat(front.getViewState().getYTranslation()).isWithin(.01f).of(expandedY);
    }

    @Test
    public void unfold_fadesPlayerBeforeMovingCards() {
        ExpandableNotificationRow front = row(80);
        children(mMedia, front);
        mCollection.updateStates(mAmbient);
        float compactY = front.getViewState().getYTranslation();
        mCollection.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND);
        mAnimatorRule.advanceTimeBy(64);
        mMedia.getViewState().setAlpha(1f);
        mCollection.updateStates(mAmbient);

        assertThat(front.getViewState().getYTranslation()).isWithin(.01f).of(compactY);
        assertThat(mMedia.getViewState().getAlpha()).isLessThan(1f);
        mAnimatorRule.advanceAnimationDuration(320);
    }

    @Test
    public void shadeDrag_keepsGeometryWhenPlayerDisappearsAndBlendsTowardShade() {
        ExpandableNotificationRow front = row(80);
        children(mMedia, front);
        mCollection.updateStates(mAmbient);
        float compactY = front.getViewState().getYTranslation();
        when(mAmbient.getFractionToShade()).thenReturn(.5f);
        when(mMedia.getVisibility()).thenReturn(View.GONE);
        front.getViewState().setYTranslation(px(100));
        front.getViewState().height = px(80);
        front.getViewState().setAlpha(1f);
        mCollection.updateStates(mAmbient);

        assertThat(mCollection.isActive()).isTrue();
        assertThat(front.getViewState().getYTranslation()).isWithin(.01f)
                .of((compactY + px(100)) / 2f);
        assertThat(front.getViewState().height).isEqualTo(px(80));
    }

    @Test
    public void collapse_keepsCdHiddenUntilCardsLand() {
        View cd = mock(View.class);
        when(cd.getHeight()).thenReturn(px(300));
        ExpandableNotificationRow front = row(80);
        children(front);
        mCollection.setCdPlayer(cd, true);
        mCollection.updateStates(mAmbient);
        float compactY = front.getViewState().getYTranslation();
        mCollection.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND);
        mAnimatorRule.advanceAnimationDuration(320);
        mCollection.updateStates(mAmbient);
        clearInvocations(cd);

        mCollection.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE);
        mAnimatorRule.advanceTimeBy(80);
        mCollection.updateStates(mAmbient);
        verify(cd).setAlpha(0f);
        assertThat(front.getViewState().getYTranslation()).isLessThan(compactY);

        mAnimatorRule.advanceAnimationDuration(320);
        mCollection.updateStates(mAmbient);
        verify(cd).setAlpha(1f);
        assertThat(front.getViewState().getYTranslation()).isWithin(.01f).of(compactY);
    }

    @Test
    public void bouncerShowing_resetsInsteadOfReusingFrozenPlayer() {
        children(mMedia, row(80));
        mCollection.updateStates(mAmbient);
        when(mAmbient.isBouncerInTransit()).thenReturn(true);
        when(mAmbient.isBouncerShowing()).thenReturn(true);

        assertThat(mCollection.refresh(mAmbient)).isFalse();
        assertThat(mCollection.getHeight(mAmbient, false)).isEqualTo(-1f);
    }

    @Test
    public void dismissedLastRow_releasesCollectionSpace() {
        ExpandableNotificationRow front = row(80);
        children(mMedia, front);
        mCollection.updateStates(mAmbient);
        when(front.isDismissed()).thenReturn(true);

        assertThat(mCollection.getHeight(mAmbient, false)).isEqualTo(-1f);
    }

    private ExpandableNotificationRow row(int height) {
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        when(row.getVisibility()).thenReturn(View.VISIBLE);
        when(row.getMinHeight(true)).thenReturn(px(height));
        when(row.getIntrinsicHeight()).thenReturn(px(height));
        when(row.getWidth()).thenReturn(px(320));
        when(row.getViewState()).thenReturn(new ExpandableViewState());
        return row;
    }

    private void children(View... children) {
        when(mHost.getChildCount()).thenReturn(children.length);
        for (int i = 0; i < children.length; i++) {
            when(mHost.getChildAt(i)).thenReturn(children[i]);
        }
    }

    private int px(int dp) {
        return Math.round(dp * mDensity);
    }
}
