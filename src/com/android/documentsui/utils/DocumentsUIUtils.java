package com.android.documentsui.utils;

import com.huawei.android.os.SystemPropertiesEx;

import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.Downloads;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.provider.MediaStore.Audio;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import java.util.Hashtable;
import java.util.List;

import com.android.documentsui.base.DocumentInfo;

public class DocumentsUIUtils {

    private static final String TAG = "documentsui";

    private static final String DOWNLOADS_DOCUMENTS_AUTHORITY = "com.android.providers.downloads.documents";
    private static final String _ID = "_id";
    private static final String _DATA = "_data";

    private static final String EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents";
    private static final String ROOT_ID_PRIMARY_EMULATED = "primary";

    private static final String MEDIA_PROVIDER_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents";
    private static final String IMAGE_ROOT = "images_root";
    private static final String VIDEO_ROOT = "videos_root";
    private static final String AUDIO_ROOT = "audio_root";
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_VIDEO = "video";
    private static final String TYPE_AUDIO = "audio";
    private static final String NAVIGATIONBAR_IS_MIN = "navigationbar_is_min";
    private static final String DISPLAY_NOTCH_STATUS = "display_notch_status";

    public static boolean isPad(){
        if("tablet".equals(SystemPropertiesEx.get("ro.build.characteristics",""))){
            return true;
        }else{
            return false;
        }
    }

    public static int getDisplayRotate(Context context) {
        int rotate = 0;
        if (null != context) {
            WindowManager wmManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (null != wmManager) {
                return wmManager.getDefaultDisplay().getRotation();
            }
        }
        return rotate;
    }

    public static boolean isNavigationBarExist(Context context) {
        if (null != context) {
            boolean exist = Settings.Global.getInt(context.getContentResolver(), NAVIGATIONBAR_IS_MIN, 0) == 0;
            return exist;
        }
        return false;
    }

    public static boolean hasNotchInScreen() {
        return (SystemPropertiesEx.get("ro.config.hw_notch_size", "").equals("")) ? false : true;
    }

    public static boolean getDisplayNotchStatus(Context context) {
        if (null != context) {
            boolean exist = Settings.Secure.getInt(context.getContentResolver(), DISPLAY_NOTCH_STATUS, 0) == 0;
            return exist;
        }
        return false;
    }

    public static Hashtable<String, String> findPathByDocId (Context context, Cursor docCursor, String authority, String rootId) {
        Hashtable<String, String> docsIdPath = new Hashtable<String, String>();
        if (context == null || context.getContentResolver() == null || authority == null || rootId == null) {
            return docsIdPath;
        }
        if (EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY.equals(authority)) {
            return convertExternalDocCursor(context, docCursor);
        }

        final String[] projection = {_ID, _DATA};

        if (DOWNLOADS_DOCUMENTS_AUTHORITY.equals(authority)) {
            return query(context.getContentResolver(), Downloads.Impl.ALL_DOWNLOADS_CONTENT_URI, projection, null, null, null, null);
        }

        if (MEDIA_PROVIDER_DOCUMENTS_AUTHORITY.equals(authority)) {
            if (IMAGE_ROOT.equals(rootId)) {
                return query(context.getContentResolver(), Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null, TYPE_IMAGE);
            } else if (VIDEO_ROOT.equals(rootId)) {
                return query(context.getContentResolver(), Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null, TYPE_VIDEO);
            } else if (AUDIO_ROOT.equals(rootId)) {
                return query(context.getContentResolver(), Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null, TYPE_AUDIO);
            }
        }

        return docsIdPath;
    }

    private static Hashtable<String, String> query(ContentResolver resolver, Uri uri, String[] projection,
                                                   String selection, String[] selectionArgs, String sortOrder, String prefix) {
        Hashtable<String, String> docsIdPath = new Hashtable<String, String>();
        Cursor cursor = null;
        try {
            if (resolver != null) {
                cursor = resolver.query(uri, projection, selection, selectionArgs, sortOrder);
                getDataColumn(cursor, docsIdPath, prefix);
            }
        } catch(IllegalArgumentException ex) {
            Log.e(TAG, "query->ex:", ex);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return docsIdPath;
    }

    /**
     *@param Cursor cursor input
     *@param docs output
     *@throws IllegalArgumentException if the column does not exist
     */
    private static void getDataColumn(Cursor cursor,Hashtable<String, String> docs, String prefix) {
        if (docs == null || cursor == null || !cursor.moveToFirst()) {
            return;
        }

        final int indexId = cursor.getColumnIndexOrThrow(_ID);
        final int indexData = cursor.getColumnIndexOrThrow(_DATA);
        do {
            String id = cursor.getString(indexId);
            String data = cursor.getString(indexData);
            if (id != null && data != null) {
                if (prefix != null) {
                    id = prefix + ":" + id;
                }
                docs.put(id, data);
            }
        } while (cursor.moveToNext());
    }

    private static Hashtable<String, String> convertExternalDocCursor(Context context, Cursor docCursor) {
        Hashtable<String, String> docsIdPath = new Hashtable<String, String>();
        if (context == null || docCursor == null || !docCursor.moveToFirst()) {
            return docsIdPath;
        }

        StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (storageManager == null) {
            return docsIdPath;
        }

        final int userId = UserHandle.myUserId();
        final List<VolumeInfo> volumes = storageManager.getVolumes();

        String rootId= null;
        Hashtable<String, String> storagePaths = new Hashtable<String, String>();
        for (VolumeInfo volume : volumes) {
            if (!volume.isMountedReadable()) {
                continue;
            }

            if (volume.getType() == VolumeInfo.TYPE_EMULATED) {
                rootId = ROOT_ID_PRIMARY_EMULATED;
            } else if (volume.getType() == VolumeInfo.TYPE_PUBLIC) {
                rootId = volume.getFsUuid();
            } else {
                // Unsupported volume; ignore
                continue;
            }

            String path = null;
            if (volume.getPathForUser(userId) != null) {
                path =volume.getPathForUser(userId).getPath();
            }

            if (rootId != null && path != null) {
                storagePaths.put(rootId, path);
            }
        }

        do {
            String documentsId = DocumentInfo.getCursorString(docCursor, Document.COLUMN_DOCUMENT_ID);
            String mimeType = DocumentInfo.getCursorString(docCursor, Document.COLUMN_MIME_TYPE);
            if (documentsId != null && !Document.MIME_TYPE_DIR.equals(mimeType)) {
                String[] split = documentsId.split(":");
                if (split.length >= 2 && storagePaths.containsKey(split[0])) {
                    String path = storagePaths.get(split[0]) + "/" + split[1];
                    docsIdPath.put(documentsId, path);
                }
            }
        } while (docCursor.moveToNext());

        docCursor.moveToFirst();

        return docsIdPath;
    }
}