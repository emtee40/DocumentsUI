/*
 * create by lwx400505 2017/12/21 on documentsui storageAvailable don't equal with setting storage
 */
package com.android.documentsui.utils;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.BidiFormatter;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;

public class StorageSizeUtils {

    public static long getStorageAvailableSize(StorageManager storageManager, Boolean isSdCard, Boolean isUsb) {
        long availableSize = 0L;
        try {
            String path = "";
            for (StorageVolume storageVolume : storageManager.getVolumeList()) {
                if ((!storageVolume.isRemovable()) && storageVolume.isEmulated() && !isSdCard) {
                    path = storageVolume.getPath();
                }
                if (storageVolume.isRemovable() && (!storageVolume.isEmulated()) && isSdCard) {
                    path = storageVolume.getPath();
                }
                if (storageVolume.isRemovable() && (!storageVolume.isEmulated()) && isUsb) {
                    path = storageVolume.getPath();
                }
            }
            StatFs stat = new StatFs(path);
            long blockSize = stat.getBlockSize();
            long availableBlocks = stat.getAvailableBlocks();
            availableSize = (availableBlocks * blockSize);

        } catch (IllegalArgumentException e) {
            Log.e("DocumentsUI StorageSizeUtils ", e.getMessage());
        }
        return availableSize;
    }

    public static final boolean IS_MORE_O_VERSION = Build.VERSION.SDK_INT >= 25;

    private static Locale localeFromContext(@NonNull Context context) {
        return context.getResources().getConfiguration().getLocales().get(0);
    }

    /* Wraps the source string in bidi formatting characters in RTL locales */
    private static String bidiWrap(@NonNull Context context, String source) {
        final Locale locale = localeFromContext(context);
        if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) {
            return BidiFormatter.getInstance(true /* RTL*/).unicodeWrap(source);
        } else {
            return source;
        }
    }

    public static String formatFileSize(Context context, long bytes) {
        return formatFileSize(context, bytes, false);
    }

    public static String formatFileSize(@NonNull Context context, long bytes, boolean isCloudSize) {
        if (null == context) {
            return "";
        }
        DecimalFormat df = new DecimalFormat();
        df.setRoundingMode(RoundingMode.HALF_UP);
        df.applyPattern("0.##");
        float result = bytes;
        String spaceNum = null;
        int value = (!isCloudSize && IS_MORE_O_VERSION) ? 1000 : 1024;
        int unitID = com.android.internal.R.string.byteShort;
        if (result > 900) {
            unitID = com.android.internal.R.string.kilobyteShort;
            result = result / value;
        }
        if (result > 900) {
            result = result / value;
            unitID = com.android.internal.R.string.megabyteShort;
        }
        if (result > 900) {
            result = result / value;
            unitID = com.android.internal.R.string.gigabyteShort;
        }

        spaceNum = df.format(result);
        Pattern p = Pattern.compile("(\\d*).[0][0]");
        Matcher m = p.matcher(spaceNum);
        if (m.matches()) {
            spaceNum = spaceNum.replace(".00", "");
        }
        String unit = context.getResources().getString(unitID);
        return bidiWrap(context, context.getString(com.android.internal.R.string.fileSizeSuffix,
                spaceNum, unit));
    }

}