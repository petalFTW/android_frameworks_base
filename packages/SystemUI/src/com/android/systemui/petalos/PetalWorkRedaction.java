/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.petalos;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Person;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.HapticFeedbackConstants;
import android.widget.EditText;
import android.widget.TextView;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationContentView;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;

import org.petalos.config.PetalConfig;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

// Same bars in the shade and heads-up; no bloody duplicate notification.
public final class PetalWorkRedaction {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<ExpandableNotificationRow>> ROWS = new ArrayList<>();
    private static final List<WeakReference<Runnable>> LISTENERS = new ArrayList<>();
    private static final WeakHashMap<ExpandableNotificationRow, Peek> PEEKS = new WeakHashMap<>();
    private static final Set<String> NAMES = new HashSet<>();
    private static final Set<String> LOOKUPS = new HashSet<>();
    private static Set<String> sApps = Set.of();
    private static int sMode;
    private static boolean sObserving;

    private PetalWorkRedaction() {}

    public static void track(ExpandableNotificationRow row) {
        if (row == null) return;
        ensureObserved(row.getContext());
        Iterator<WeakReference<ExpandableNotificationRow>> iterator = ROWS.iterator();
        while (iterator.hasNext()) {
            ExpandableNotificationRow existing = iterator.next().get();
            if (existing == null) iterator.remove();
            else if (existing == row) return;
        }
        ROWS.add(new WeakReference<>(row));
    }

    public static void apply(Context systemUiContext, ViewGroup root, StatusBarNotification sbn) {
        applyIsland(systemUiContext, root, sbn == null ? null : sbn.getPackageName(),
                sbn == null ? null : sbn.getNotification());
    }

    public static int modeFor(Context context, String packageName, Notification notification) {
        ensureObserved(context);
        return (packageName != null && sApps.contains(packageName)) || isExemptPerson(notification)
                ? PetalConfig.WORK_MODE_OFF : sMode;
    }

    public static void applyIsland(Context systemUiContext, ViewGroup root, String packageName,
            Notification notification) {
        if (root == null) return;
        ensureObserved(systemUiContext);
        // RemoteViews carry the posting app's resources, not ours.
        CharSequence redactedLabel = systemUiContext.getText(R.string.petal_work_mode_redacted);
        int mode = modeFor(systemUiContext, packageName, notification);
        walk(root, notification, mode, redactedLabel);
    }

    public static void addChangeListener(Context context, Runnable listener) {
        ensureObserved(context);
        LISTENERS.removeIf(reference -> reference.get() == null || reference.get() == listener);
        LISTENERS.add(new WeakReference<>(listener));
    }

    public static void removeChangeListener(Runnable listener) {
        LISTENERS.removeIf(reference -> reference.get() == null || reference.get() == listener);
    }

    private static void notifyChanged() {
        for (Peek peek : new ArrayList<>(PEEKS.values())) peek.animateTo(0f, true);
        reapplyAll();
        List<Runnable> callbacks = new ArrayList<>();
        LISTENERS.removeIf(reference -> reference.get() == null);
        for (WeakReference<Runnable> reference : LISTENERS) {
            Runnable listener = reference.get();
            if (listener != null) callbacks.add(listener);
        }
        for (Runnable callback : callbacks) callback.run();
    }

    public static boolean beginPeek(ExpandableNotificationRow row) {
        List<Mask> masks = new ArrayList<>();
        for (NotificationContentView layout : row.getLayouts()) collectMasks(layout, masks);
        if (masks.isEmpty()) return false;
        Peek peek = PEEKS.get(row);
        if (peek == null) {
            peek = new Peek(row);
            PEEKS.put(row, peek);
            row.addOnAttachStateChangeListener(peek);
        }
        peek.masks.clear();
        int[] origin = new int[2];
        int[] location = new int[2];
        row.getLocationOnScreen(origin);
        peek.width = row.getWidth();
        for (Mask mask : masks) {
            mask.view.getLocationOnScreen(location);
            mask.peekOffset = location[0] - origin[0] + mask.view.getCompoundPaddingLeft();
            mask.peek = peek;
            peek.masks.add(new WeakReference<>(mask));
        }
        peek.animateTo(1f, false);
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        return true;
    }

    public static void endPeek(ExpandableNotificationRow row, boolean immediate) {
        Peek peek = PEEKS.get(row);
        if (peek != null) peek.animateTo(0f, immediate);
    }

    private static void collectMasks(View view, List<Mask> masks) {
        if (view == null) return;
        Object tag = view.getTag(R.id.petal_redacted_text);
        if (tag instanceof Mask && ((Mask) tag).active) masks.add((Mask) tag);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectMasks(group.getChildAt(i), masks);
        }
    }

    private static final class Peek implements View.OnAttachStateChangeListener {
        final WeakReference<ExpandableNotificationRow> row;
        final List<WeakReference<Mask>> masks = new ArrayList<>();
        float progress;
        int width;
        ValueAnimator animator;

        Peek(ExpandableNotificationRow row) {
            this.row = new WeakReference<>(row);
        }

        void animateTo(float target, boolean immediate) {
            if (animator != null) animator.cancel();
            if (immediate || !ValueAnimator.areAnimatorsEnabled()) {
                progress = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(Math.max(1L, Math.round(240f * Math.abs(target - progress))));
            animator.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0f, .2f, 1f));
            animator.addUpdateListener(value -> {
                progress = (float) value.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void invalidate() {
            for (WeakReference<Mask> reference : masks) {
                Mask mask = reference.get();
                if (mask != null && mask.active) mask.view.invalidate();
            }
        }

        @Override
        public void onViewAttachedToWindow(View view) {}

        @Override
        public void onViewDetachedFromWindow(View view) {
            animateTo(0f, true);
            view.removeOnAttachStateChangeListener(this);
            PEEKS.remove(row.get());
        }
    }

    private static void ensureObserved(Context context) {
        if (sObserving) return;
        sObserving = true;
        Context app = context.getApplicationContext();
        refreshSettings(app);
        ContentObserver observer = new ContentObserver(MAIN) {
            @Override
            public void onChange(boolean selfChange) {
                refreshSettings(app);
                notifyChanged();
            }
        };
        for (String key : new String[]{PetalConfig.KEY_WORK_MODE, PetalConfig.KEY_WORK_MODE_LAST,
                PetalConfig.KEY_WORK_MODE_APPS, PetalConfig.KEY_WORK_MODE_CONTACTS}) {
            app.getContentResolver().registerContentObserver(Settings.System.getUriFor(key),
                    false, observer, UserHandle.USER_ALL);
        }
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshSettings(app);
                notifyChanged();
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, MAIN, Context.RECEIVER_EXPORTED);
    }

    private static void refreshSettings(Context context) {
        Context user = context.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), 0);
        sMode = PetalConfig.getWorkMode(user);
        sApps = PetalConfig.getWorkModeApps(user);
        NAMES.clear();
        LOOKUPS.clear();
        for (String[] contact : PetalConfig.getWorkModeContacts(user)) {
            LOOKUPS.add(contact[0]);
            NAMES.add(normalizeName(contact[1]));
        }
    }

    private static void reapplyAll() {
        List<ExpandableNotificationRow> snapshot = new ArrayList<>();
        Iterator<WeakReference<ExpandableNotificationRow>> iterator = ROWS.iterator();
        while (iterator.hasNext()) {
            ExpandableNotificationRow row = iterator.next().get();
            if (row == null) {
                iterator.remove();
                continue;
            }
            snapshot.add(row);
        }
        // setText can re-enter binding and track more rows. Don't walk the live iterator.
        for (ExpandableNotificationRow row : snapshot) {
            StatusBarNotification sbn = NotificationBundleUi.isEnabled()
                    ? (row.getEntryAdapter() == null ? null : row.getEntryAdapter().getSbn())
                    : (row.getEntryLegacy() == null ? null : row.getEntryLegacy().getSbn());
            for (NotificationContentView layout : row.getLayouts()) apply(row.getContext(), layout, sbn);
        }
    }

    private static void walk(View view, Notification notification, int mode,
            CharSequence redactedLabel) {
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            boolean redact = mode != PetalConfig.WORK_MODE_OFF;
            if (redact && mode == PetalConfig.WORK_MODE_CONTENT) {
                String id = idName(view);
                boolean messaging = notification != null && notification.isStyle(
                        Notification.MessagingStyle.class);
                boolean call = notification != null && notification.isStyle(
                        Notification.CallStyle.class);
                redact = !(Boolean.TRUE.equals(view.getTag(R.id.petal_redaction_identity))
                        || "app_name".equals(id) || "app_name_text".equals(id) || "time".equals(id)
                        || "chronometer".equals(id) || "message_name".equals(id)
                        || ((messaging || call) && ("title".equals(id) || "notification_title".equals(id)
                        || "conversation_text".equals(id) || "conversation_title".equals(id)
                        || "conversation_notification_sender".equals(id))));
            }
            Mask mask = (Mask) text.getTag(R.id.petal_redacted_text);
            if (redact) {
                if (mask == null) {
                    mask = new Mask(text, redactedLabel);
                    text.setTag(R.id.petal_redacted_text, mask);
                    text.addTextChangedListener(mask);
                }
                mask.apply();
            } else if (mask != null) {
                mask.restore();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), notification, mode, redactedLabel);
            }
        }
    }

    private static boolean isExemptPerson(Notification notification) {
        if (notification == null || notification.extras == null) return false;
        if (notification.isStyle(Notification.CallStyle.class)) {
            Person caller = notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class);
            if (caller != null) return matchesPerson(caller);
        }
        List<Notification.MessagingStyle.Message> messages = Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES));
        if (notification.isStyle(Notification.MessagingStyle.class) && !messages.isEmpty()) {
            // One allowed sender doesn't expose everyone else's messages in a group.
            messages.addAll(Notification.MessagingStyle.Message
                    .getMessagesFromBundleArray(notification.extras.getParcelableArray(
                            Notification.EXTRA_HISTORIC_MESSAGES)));
            boolean foundSender = false;
            for (Notification.MessagingStyle.Message message : messages) {
                Person sender = message.getSenderPerson();
                if (sender == null) continue;
                if (!matchesPerson(sender)) return false;
                foundSender = true;
            }
            return foundSender;
        }
        ArrayList<Person> people = notification.extras.getParcelableArrayList(
                Notification.EXTRA_PEOPLE_LIST, Person.class);
        if (people != null && people.size() == 1) return matchesPerson(people.get(0));
        String[] legacy = notification.extras.getStringArray(Notification.EXTRA_PEOPLE);
        return legacy != null && legacy.length == 1 && matchesLookup(legacy[0]);
    }

    private static boolean matchesPerson(Person person) {
        if (person == null) return false;
        if (person.getUri() != null && !person.getUri().isEmpty()) {
            Uri uri = Uri.parse(person.getUri());
            if ("content".equals(uri.getScheme())
                    && "com.android.contacts".equals(uri.getAuthority())) {
                return matchesLookup(person.getUri());
            }
        }
        return person.getName() != null && NAMES.contains(normalizeName(person.getName()));
    }

    private static boolean matchesLookup(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        List<String> segments = uri.getPathSegments();
        return "content".equals(uri.getScheme()) && "com.android.contacts".equals(uri.getAuthority())
                && segments.size() >= 3 && "contacts".equals(segments.get(0))
                && "lookup".equals(segments.get(1)) && LOOKUPS.contains(segments.get(2));
    }

    private static String normalizeName(CharSequence value) {
        return value.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String idName(View view) {
        if (view.getId() == View.NO_ID) return "";
        try {
            String owner = view.getResources().getResourcePackageName(view.getId());
            if (!"android".equals(owner) && !"com.android.systemui".equals(owner)) return "";
            return view.getResources().getResourceEntryName(view.getId());
        } catch (android.content.res.Resources.NotFoundException ignored) {
            return "";
        }
    }

    static final class Mask implements TextWatcher {
        private final TextView view;
        private final CharSequence redactedLabel;
        private CharSequence original;
        private CharSequence description;
        private boolean updating;
        private boolean active = true;
        private Peek peek;
        private float peekOffset;

        Mask(TextView view, CharSequence redactedLabel) {
            this.view = view;
            this.redactedLabel = redactedLabel;
            original = snapshot(view.getText());
            description = view.getContentDescription();
        }

        void apply() {
            if (updating || !active) return;
            updating = true;
            try {
                char[] chars = original.toString().toCharArray();
                for (int i = 0; i < chars.length; i++) {
                    if (!Character.isWhitespace(chars[i])) chars[i] = '\u2588';
                }
                SpannableString bars = new SpannableString(new String(chars));
                for (int start = 0; start < chars.length;) {
                    if (Character.isWhitespace(chars[start])) {
                        start++;
                        continue;
                    }
                    int end = start + 1;
                    while (end < chars.length && !Character.isWhitespace(chars[end])) end++;
                    bars.setSpan(new Bar(original.subSequence(start, end).toString(), this), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    start = end;
                }
                view.setText(bars);
                view.setContentDescription(redactedLabel);
            } finally {
                updating = false;
            }
        }

        void restore() {
            if (!active) return;
            active = false;
            updating = true;
            view.removeTextChangedListener(this);
            view.setTag(R.id.petal_redacted_text, null);
            try {
                view.setText(original);
                view.setContentDescription(description);
            } finally {
                updating = false;
            }
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            if (updating || !active) return;
            // RemoteViews reused this thing; keep the new words, not yesterday's stash.
            original = snapshot(s);
            apply();
        }

        private static CharSequence snapshot(CharSequence text) {
            return text instanceof Spanned ? new android.text.SpannedString(text) : text.toString();
        }
    }

    private static final class Bar extends ReplacementSpan {
        private final String original;
        private final Mask mask;

        Bar(String original, Mask mask) {
            this.original = original;
            this.mask = mask;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return (int) Math.ceil(paint.measureText(original));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                int y, int bottom, Paint paint) {
            float width = paint.measureText(original);
            float reveal = mask.peek == null ? x
                    : mask.peek.width * mask.peek.progress - mask.peekOffset;
            if (mask.peek != null && mask.peek.progress > 0f && reveal > x) {
                int saved = canvas.save();
                canvas.clipRect(x, top, Math.min(x + width, reveal), bottom);
                canvas.drawText(original, x, y, paint);
                canvas.restoreToCount(saved);
            }
            if (reveal >= x + width) return;
            int saved = canvas.save();
            canvas.clipRect(Math.max(x, reveal), top, x + width, bottom);
            int color = paint.getColor();
            Paint.Style style = paint.getStyle();
            android.graphics.Shader shader = paint.getShader();
            paint.setShader(null);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(x, y + paint.ascent(), x + width,
                    y + paint.descent(), paint);
            paint.setStyle(style);
            paint.setColor(color);
            paint.setShader(shader);
            canvas.restoreToCount(saved);
        }
    }
}
