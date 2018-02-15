/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.documentsui.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
* This class receives a callback when Notification is posted or removed
* and monitors the Notification status.
* And, this sends the operation's result by Broadcast.
*/
public class TestNotificationService extends NotificationListenerService {
    public static final String ACTION_CHANGE_CANCEL_MODE =
            "com.android.documentsui.services.TestNotificationService.ACTION_CHANGE_CANCEL_MODE";

    public static final String ACTION_CHANGE_EXECUTION_MODE =
            "com.android.documentsui.services.TestNotificationService.ACTION_CHANGE_EXECUTION_MODE";

    public static final String ACTION_OPERATION_RESULT =
            "com.android.documentsui.services.TestNotificationService.ACTION_OPERATION_RESULT";

    public static final String ACTION_DISPLAY_SD_CARD_NOTIFICATION =
            "com.android.documentsui.services.TestNotificationService.ACTION_DISPLAY_SD_CARD_NOTIFICATION";

    public static final String ACTION_SD_CARD_SETTING_COMPLETED =
            "com.android.documentsui.services.TestNotificationService.ACTION_SD_CARD_SETTING_COMPLETED";

    public static final String EXTRA_RESULT =
            "com.android.documentsui.services.TestNotificationService.EXTRA_RESULT";

    public static final String EXTRA_ERROR_REASON =
            "com.android.documentsui.services.TestNotificationService.EXTRA_ERROR_REASON";

    private static final String UNSUPPORTED_NOTIFICATION_TEXT = "Unsupported Virtual SD card";

    private static final String CORRUPTED_NOTIFICATION_TEXT = "Corrupted Virtual SD card";

    private static final String VIRTUAL_SD_CARD_TEXT = "Virtual SD card";

    private final static String DOCUMENTSUI_PACKAGE= "com.android.documentsui";

    private final static String SD_CARD_NOTIFICATION_PACKAGE = "com.android.systemui";

    private final static String CANCEL = "Cancel";

    public enum MODE {
        CANCEL_MODE,
        EXECUTION_MODE;
    }

    private MODE mCurrentMode = MODE.CANCEL_MODE;

    private boolean mCancelled = false;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_CHANGE_CANCEL_MODE.equals(action)) {
                mCurrentMode = MODE.CANCEL_MODE;
            } else if (ACTION_CHANGE_EXECUTION_MODE.equals(action)) {
                mCurrentMode = MODE.EXECUTION_MODE;
            }
        }
    };

    @Override
    public void onCreate() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CHANGE_CANCEL_MODE);
        filter.addAction(ACTION_CHANGE_EXECUTION_MODE);
        registerReceiver(mReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mReceiver);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkgName = sbn.getPackageName();
        if (SD_CARD_NOTIFICATION_PACKAGE.equals(pkgName)) {
            sendBroadcastForVirtualSdCard(sbn.getNotification());
        } else if (DOCUMENTSUI_PACKAGE.equals(pkgName)) {
            if (MODE.CANCEL_MODE.equals(mCurrentMode)) {
                mCancelled = doCancel(sbn.getNotification());
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        String pkgName = sbn.getPackageName();
        if (!DOCUMENTSUI_PACKAGE.equals(pkgName)) {
            return;
        }

        Intent intent = new Intent(ACTION_OPERATION_RESULT);
        if (MODE.CANCEL_MODE.equals(mCurrentMode)) {
            intent.putExtra(EXTRA_RESULT, mCancelled);
            if (!mCancelled) {
                intent.putExtra(EXTRA_ERROR_REASON, "Cannot executed cancel");
            }
        } else if (MODE.EXECUTION_MODE.equals(mCurrentMode)) {
            intent.putExtra(EXTRA_RESULT, true);
        }
        sendBroadcast(intent);
    }

    private void sendBroadcastForVirtualSdCard(Notification notification) {
        String title = notification.extras.getString(Notification.EXTRA_TITLE);
        if (UNSUPPORTED_NOTIFICATION_TEXT.equals(title) ||
                CORRUPTED_NOTIFICATION_TEXT.equals(title)) {
            sendBroadcast(new Intent(ACTION_DISPLAY_SD_CARD_NOTIFICATION));
        } else if (VIRTUAL_SD_CARD_TEXT.equals(title)) {
            sendBroadcast(new Intent(ACTION_SD_CARD_SETTING_COMPLETED));
        }
    }

    private boolean doCancel(Notification noti) {
        Notification.Action aList [] = noti.actions;
        if (aList == null) {
            return false;
        }

        boolean result = false;
        for (Notification.Action item : aList) {
            if (CANCEL.equals(item.title)) {
                try {
                    item.actionIntent.send();
                    result = true;
                } catch (PendingIntent.CanceledException e) {
                }
            }
        }
        return result;
    }
}
