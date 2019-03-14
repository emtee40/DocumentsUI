/*<DTS2016081001597 chencheng/00219147 20160819 create*/
/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import android.content.Context;
import android.drm.DrmManagerClient;
import android.net.Uri;
import android.os.SystemProperties;

import android.util.Log;

public class DrmUtils {
    private static final boolean DRM_ENABLED =
            SystemProperties.getBoolean("ro.huawei.cust.oma_drm", false);

    public static final int UNKNOWN = 0x04; // unknown
    public static final int FORWARD_LOCK = 0x05;  //forward lock
    public static final int COMBINED_DELIVERY = 0x06; //combined delivery
    public static final int SEPARATE_DELIVERY = 0x07; //separate delivery
    public static final int SEPARATE_DELIVERY_SF = 0x08; //separate delivery DRM message

    private static DrmManagerClient sDrmManagerClient;

    public static void initialize(Context context) {
        sDrmManagerClient = new DrmManagerClient(context.getApplicationContext());
    }

    public static boolean isHwDrmSupported() {
        return DRM_ENABLED;
    }

    public static boolean isDrmFile(Uri uri) {
        try {
            if (uri != null && !uri.toString().endsWith(".dcf")) {
                return false;
            }

            if (sDrmManagerClient != null && sDrmManagerClient.canHandle(uri, null)) {
                return true;
            }
        } catch (IllegalArgumentException e) {
            // Ignore
            /* < DTS2017110913813 liyapeng/wx400505 20171110 begin >*/
            Log.e("DocumentsUI DrmUtils", e.toString());
            /* < DTS2017110913813 liyapeng/wx400505 20171110 end >*/
        }
        return false;
    }

    //return UNKNOWN,FORWARD_LOCK, COMBINED_DELIVERY
    //SEPARATE_DELIVERY, SEPARATE_DELIVERY_SF
    public static int getObjectType(Uri uri) {
        try {
            if (isDrmFile(uri)) {
                return sDrmManagerClient.getDrmObjectType(uri, null);
            }
        } catch (IllegalArgumentException e) {
            // Ignore
            /* < DTS2017110913813 liyapeng/wx400505 20171110 begin >*/
            Log.e("DocumentsUI DrmUtils", e.toString());
            /* < DTS2017110913813 liyapeng/wx400505 20171110 end >*/

        }
        return UNKNOWN;
    }

    public static boolean canForward(Uri uri) {
        int ret = getObjectType(uri);
        return FORWARD_LOCK != ret && COMBINED_DELIVERY != ret;
    }
}