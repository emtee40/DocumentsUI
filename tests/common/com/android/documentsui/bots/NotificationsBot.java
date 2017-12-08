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

package com.android.documentsui.bots;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiSelector;
import android.text.TextUtils;
import android.view.KeyEvent;

/**
 * A test helper class for controlling notification items.
 */
public class NotificationsBot extends Bots.BaseBot {

    public NotificationsBot(UiDevice device, Context context, int timeout) {
        super(device, context, timeout);
    }

    public void setNotificationAccess(
            Activity activity, String appName, String label) throws UiObjectNotFoundException {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        activity.startActivity(intent);
        mDevice.waitForIdle();

        clickLabel(appName);

        clickLabel(label);
        mDevice.pressKeyCode(KeyEvent.KEYCODE_BACK);
        mDevice.waitForIdle();
    }

    public boolean isNotificationAccessEnabled(ContentResolver resolver, String pkgName) {
        String listeners = Settings.Secure.getString(resolver, "enabled_notification_listeners");
        if (!TextUtils.isEmpty(listeners)) {
            String[] list = listeners.split(":");
            for(String item : list) {
                if(item.startsWith(pkgName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void clickLabel(String appName) throws UiObjectNotFoundException {
        UiSelector selector = new UiSelector().text(appName);
        mDevice.findObject(selector).click();
        mDevice.waitForIdle();
    }
}

