/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.interruption;

import static com.android.systemui.statusbar.NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.dagger.NotificationsModule;
import com.android.systemui.statusbar.policy.HeadsUpManager;

/** Handles heads-up and pulsing behavior driven by notification changes. */
public class NotificationAlertingManager {

    private static final String TAG = "NotifAlertManager";

    private final NotificationRemoteInputManager mRemoteInputManager;
    private final VisualStabilityManager mVisualStabilityManager;
    private final StatusBarStateController mStatusBarStateController;
    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private final NotificationListener mNotificationListener;

    private HeadsUpManager mHeadsUpManager;

    /**
     * Injected constructor. See {@link NotificationsModule}.
     */
    public NotificationAlertingManager(
            NotificationEntryManager notificationEntryManager,
            NotificationRemoteInputManager remoteInputManager,
            VisualStabilityManager visualStabilityManager,
            StatusBarStateController statusBarStateController,
            NotificationInterruptStateProvider notificationInterruptionStateProvider,
            NotificationListener notificationListener,
            HeadsUpManager headsUpManager) {
        mRemoteInputManager = remoteInputManager;
        mVisualStabilityManager = visualStabilityManager;
        mStatusBarStateController = statusBarStateController;
        mNotificationInterruptStateProvider = notificationInterruptionStateProvider;
        mNotificationListener = notificationListener;
        mHeadsUpManager = headsUpManager;

        notificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onEntryInflated(NotificationEntry entry) {
                showAlertingView(entry);
            }

            @Override
            public void onPreEntryUpdated(NotificationEntry entry) {
                updateAlertState(entry);
            }

            @Override
            public void onEntryRemoved(
                    NotificationEntry entry,
                    NotificationVisibility visibility,
                    boolean removedByUser,
                    int reason) {
                stopAlerting(entry.getKey());
            }
        });
    }

    /**
     * Adds the entry to the respective alerting manager if the content view was inflated and
     * the entry should still alert.
     */
    private void showAlertingView(NotificationEntry entry) {
        // TODO: Instead of this back and forth, we should listen to changes in heads up and
        // cancel on-going heads up view inflation using the bind pipeline.
        if (entry.getRow().getPrivateLayout().getHeadsUpChild() != null) {
            mHeadsUpManager.showNotification(entry);
            if (!mStatusBarStateController.isDozing()) {
                // Mark as seen immediately
                setNotificationShown(entry.getSbn());
            }
        }
    }

    private void updateAlertState(NotificationEntry entry) {
        boolean alertAgain = alertAgain(entry, entry.getSbn().getNotification());
        // includes check for whether this notification should be filtered:
        boolean shouldAlert = mNotificationInterruptStateProvider.shouldHeadsUp(entry);
        final boolean wasAlerting = mHeadsUpManager.isAlerting(entry.getKey());
        if (wasAlerting) {
            if (shouldAlert) {
                mHeadsUpManager.updateNotification(entry.getKey(), alertAgain);
            } else if (!mHeadsUpManager.isEntryAutoHeadsUpped(entry.getKey())) {
                // We don't want this to be interrupting anymore, let's remove it
                mHeadsUpManager.removeNotification(entry.getKey(), false /* removeImmediately */);
            }
        } else if (shouldAlert && alertAgain) {
            // This notification was updated to be alerting, show it!
            mHeadsUpManager.showNotification(entry);
        }
    }

    /**
     * Checks whether an update for a notification warrants an alert for the user.
     *
     * @param oldEntry the entry for this notification.
     * @param newNotification the new notification for this entry.
     * @return whether this notification should alert the user.
     */
    public static boolean alertAgain(
            NotificationEntry oldEntry, Notification newNotification) {
        return oldEntry == null || !oldEntry.hasInterrupted()
                || (newNotification.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0;
    }

    private void setNotificationShown(StatusBarNotification n) {
        try {
            mNotificationListener.setNotificationsShown(new String[]{n.getKey()});
        } catch (RuntimeException e) {
            Log.d(TAG, "failed setNotificationsShown: ", e);
        }
    }

    private void stopAlerting(final String key) {
        // Attempt to remove notifications from their alert manager.
        // Though the remove itself may fail, it lets the manager know to remove as soon as
        // possible.
        if (mHeadsUpManager.isAlerting(key)) {
            // A cancel() in response to a remote input shouldn't be delayed, as it makes the
            // sending look longer than it takes.
            // Also we should not defer the removal if reordering isn't allowed since otherwise
            // some notifications can't disappear before the panel is closed.
            boolean ignoreEarliestRemovalTime =
                    mRemoteInputManager.getController().isSpinning(key)
                            && !FORCE_REMOTE_INPUT_HISTORY
                            || !mVisualStabilityManager.isReorderingAllowed();
            mHeadsUpManager.removeNotification(key, ignoreEarliestRemovalTime);
        }
    }
}
