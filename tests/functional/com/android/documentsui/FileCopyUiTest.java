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

package com.android.documentsui;

import static com.android.documentsui.base.Providers.AUTHORITY_STORAGE;
import static com.android.documentsui.base.Providers.ROOT_ID_DEVICE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.provider.Settings;
import android.support.test.espresso.NoMatchingViewException;
import android.support.test.filters.LargeTest;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.util.Log;

import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.files.FilesActivity;
import com.android.documentsui.services.TestNotificationService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import libcore.io.IoUtils;

/**
* This class test the below points
* - Copy large number of files on the internal/external storage
*/
@LargeTest
public class FileCopyUiTest extends ActivityTest<FilesActivity> {
    private static final String TAG = "FileCopyUiTest";

    private static final String PACKAGE_NAME = "com.android.documentsui.tests";

    private static final String TARGET_FOLDER = "test_folder";

    private static final String ACCESS_APP_NAME = "DocumentsUI Tests";

    private static final String ALLOW = "ALLOW";

    private static final String TURN_OFF = "TURN OFF";

    private static final String COPY = "Copy to…";

    private static final String MOVE = "Move to…";

    private static final String SELECT_ALL = "Select all";

    private static final String SHOW_INTERNAL_STORAGE = "Show internal storage";

    private static final String NOTIFICATION_TEXT = "Corrupted Virtual SD card";

    private static final String ERASE_AND_FORMAT = "ERASE & FORMAT";

    private static final String DONE = "DONE";

    private static final int TARGET_COUNT = 1000;

    private static final int WAIT_TIME_SECONDS = 180;

    private final Map<String, Long> mTargetFileList = new HashMap<String, Long>();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TestNotificationService.ACTION_OPERATION_RESULT.equals(action)) {
                mOperationExecuted = intent.getBooleanExtra(
                        TestNotificationService.EXTRA_RESULT, false);
                if (!mOperationExecuted) {
                    mErrorReason = intent.getStringExtra(
                            TestNotificationService.EXTRA_ERROR_REASON);
                }
                mCountDownLatch.countDown();
            }
        }
    };

    private CountDownLatch mCountDownLatch;

    private boolean mOperationExecuted;

    private String mErrorReason;

    private DocumentsProviderHelper mStorageDocsHelper;

    private ContentProviderClient mStorageClient;

    private RootInfo mPrimaryRoot;

    private RootInfo mSDCardRoot;

    private String mSDCardLabel;

    private boolean mIsVirtualSDCard;

    private int mStayOnValue;

    public FileCopyUiTest() {
        super(FilesActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Create ContentProviderClient and DocumentsProviderHelper for using SD Card.
        mStorageClient = mResolver.acquireUnstableContentProviderClient(
                AUTHORITY_STORAGE);
        mStorageDocsHelper = new DocumentsProviderHelper(AUTHORITY_STORAGE, mStorageClient);

        // Set a flag to prevent many refreshes.
        Bundle bundle = new Bundle();
        bundle.putBoolean(StubProvider.EXTRA_ENABLE_ROOT_NOTIFICATION, false);
        mDocsHelper.configure(null, bundle);

        // Set "Stay awake" until test is finished.
        mStayOnValue = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN);
        automation.executeShellCommand("settings put global stay_on_while_plugged_in 3");

        setStorageRootInfo();
        if (mPrimaryRoot == null) {
            fail("Internal Storage not found");
        }

        // If SD Card is not found, set Virtual SD Card
        if (mSDCardRoot == null) {
            assertTrue("Cannot set virtual SD Card", setVirtualSDCard());
        }

        // If Internal Storage is not shown, turn on.
        try {
            bots.main.clickToolbarOverflowItem(SHOW_INTERNAL_STORAGE);
        } catch (NoMatchingViewException e) {
            // Ignore exception and hide menu view.
            bots.keyboard.pressKey(KeyEvent.KEYCODE_BACK);
        }

        try {
            if (!isEnableAccessNotification()) {
                setNotificationAccess(true);
            }
        } catch (Exception e) {
            fail("Cannot set notification access");
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(TestNotificationService.ACTION_OPERATION_RESULT);
        context.registerReceiver(mReceiver, filter);
        context.sendBroadcast(new Intent(
                TestNotificationService.ACTION_CHANGE_EXECUTION_MODE));

        mOperationExecuted = false;
        mErrorReason = "No response from Notification";
    }

    @Override
    public void tearDown() throws Exception {
        // Delete created files
        deleteDocuments(Build.MODEL);
        deleteDocuments(mSDCardLabel);

        if (mIsVirtualSDCard) {
            automation.executeShellCommand("sm set-virtual-disk false");
        }

        automation.executeShellCommand("settings put global stay_on_while_plugged_in "
                + mStayOnValue);

        context.unregisterReceiver(mReceiver);
        try {
            if (isEnableAccessNotification()) {
                setNotificationAccess(false);
            }
        } catch (Exception e) {
            // Ignore
        }

        super.tearDown();
    }

    private boolean createDocuments(String label, RootInfo root,
            DocumentsProviderHelper helper) throws Exception {
        if (TextUtils.isEmpty(label) || root == null) {
            return false;
        }

        // If Test folder is already created, delete it
        if (bots.directory.hasDocuments(TARGET_FOLDER)) {
            deleteDocuments(label);
        }

        // Create folder and create file in its folder
        bots.roots.openRoot(label);
        Uri uri = helper.createFolder(root, TARGET_FOLDER);
        device.waitForIdle();
        if (!bots.directory.hasDocuments(TARGET_FOLDER)) {
            return false;
        }

        loadImages(uri, helper);

        // Check that image files are loaded completely
        DocumentInfo parent = helper.findDocument(root.documentId, TARGET_FOLDER);
        List<DocumentInfo> children = helper.listChildren(parent.documentId, TARGET_COUNT);
        for (DocumentInfo docInfo : children) {
            mTargetFileList.put(docInfo.displayName, docInfo.size);
        }
        assertTrue("Lack of loading file. File count = " + mTargetFileList.size(),
                mTargetFileList.size() == TARGET_COUNT);

        return true;
    }

    private boolean deleteDocuments(String label) throws Exception {
        if (TextUtils.isEmpty(label)) {
            return false;
        }

        bots.roots.openRoot(label);
        if (!bots.directory.hasDocuments(TARGET_FOLDER)) {
            return true;
        }

        if (mCountDownLatch != null) {
            assertTrue("Cannot wait because any operation is waiting now.",
                    mCountDownLatch.getCount() == 0);
        }

        mCountDownLatch = new CountDownLatch(1);
        bots.directory.selectDocument(TARGET_FOLDER);
        device.waitForIdle();

        bots.main.clickToolbarItem(R.id.action_menu_delete);
        bots.main.clickDialogOkButton();
        device.waitForIdle();

        // Wait until copy operation finished
        try {
            mCountDownLatch.await(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore
        }

        return !bots.directory.hasDocuments(TARGET_FOLDER);
    }

    private void loadImages(Uri root, DocumentsProviderHelper helper) throws Exception {
        Context testContext = getInstrumentation().getContext();
        Resources res = testContext.getResources();
        try {
            int resId = res.getIdentifier(
                    "uitest_images", "raw", testContext.getPackageName());
            loadImageFromResources(root, helper, resId, res);
        } catch (Exception e) {
            // Ignore
            Log.d(TAG, "Error occurs when loading image. ", e);
        }
    }

    private void loadImageFromResources(Uri root, DocumentsProviderHelper helper, int resId,
            Resources res) {
        ZipInputStream in = null;
        int read = 0;
        try {
            in = new ZipInputStream(res.openRawResource(resId));
            ZipEntry zipEntry = null;
            while ((zipEntry = in.getNextEntry()) != null) {
                String fileName = zipEntry.getName();
                Uri uri = helper.createDocument(root, "image/png", fileName);
                byte[] buff = new byte[1024];
                while ((read = in.read(buff)) > 0) {
                    helper.writeAppendDocument(uri, buff);
                }
                in.closeEntry();
                buff = null;
            }
        } catch (Exception e) {
            // Ignore becase caller method has checked
        } finally {
            if (in != null) {
                try {
                    in.close();
                    in  = null;
                } catch (Exception e) {
                }
            }
        }
    }

    public boolean setVirtualSDCard() throws Exception {
        try {
            automation.executeShellCommand("sm set-virtual-disk true");
            device.openNotification();
            device.wait(Until.hasObject(By.text(NOTIFICATION_TEXT)), TIMEOUT);
            UiObject2 title = device.findObject(By.text(NOTIFICATION_TEXT));
            title.click();

            UiObject2 erase = device.findObject(By.text(ERASE_AND_FORMAT));
            erase.click();
            device.wait(Until.hasObject(By.text(DONE)), TIMEOUT);
            UiObject2 done = device.findObject(By.text(DONE));
            done.click();
            device.waitForIdle();

            mIsVirtualSDCard = true;
        } catch (Exception e) {
            return false;
        }

        // Call setStorageRootInfo() again for setting SD Card root
        setStorageRootInfo();
        return true;
    }

    private void setNotificationAccess(boolean isAllowed) throws Exception {
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        getActivity().startActivity(intent);
        device.waitForIdle();

        bots.main.findMenuLabelWithName(ACCESS_APP_NAME).click();
        device.waitForIdle();

        String targetObject = isAllowed ? ALLOW : TURN_OFF;
        bots.main.findMenuLabelWithName(targetObject).click();
        bots.keyboard.pressKey(KeyEvent.KEYCODE_BACK);
        device.waitForIdle();
    }

    private boolean isEnableAccessNotification() {
        ContentResolver resolver = getActivity().getContentResolver();
        String listeners = Settings.Secure.getString(
                resolver,"enabled_notification_listeners");
        if (!TextUtils.isEmpty(listeners)) {
            String[] list = listeners.split(":");
            for(String item : list) {
                if(item.startsWith(PACKAGE_NAME)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setStorageRootInfo() throws RemoteException {
        List<String> rootList = getRootsDocumentIdList();
        int index = rootList.indexOf(ROOT_ID_DEVICE);
        if (index == -1) {
            fail("Primary storage cannot be found");
        }
        mPrimaryRoot = mStorageDocsHelper.getRoot(rootList.get(index));

        StorageManager storageManager= (StorageManager)context.getSystemService(
                Context.STORAGE_SERVICE);
        List<VolumeInfo> volList = storageManager.getVolumes();
        for (VolumeInfo info : volList) {
            DiskInfo diskInfo = info.getDisk();
            if (diskInfo == null) {
                continue;
            }
            if (diskInfo.isSd()) {
                mSDCardLabel = diskInfo.getDescription();
                index = rootList.indexOf(info.getFsUuid());
                mSDCardRoot = mStorageDocsHelper.getRoot(rootList.get(index));
            } else if (diskInfo.isUsb()) {
                // TODO: USB storage cannot be connected while testing
                // because InstrumentationTest must be needed to connect a PC to device.
            }
        }
    }

    private List<String> getRootsDocumentIdList() {
        List<String> list = new ArrayList<String>();
        final Uri rootsUri = DocumentsContract.buildRootsUri(AUTHORITY_STORAGE);
        Cursor cursor = null;
        try {
            cursor = mStorageClient.query(rootsUri, null, null, null, null);
            while (cursor.moveToNext()) {
                int index = cursor.getColumnIndex(Root.COLUMN_ROOT_ID);
                String docId = (index != -1) ? cursor.getString(index) : null;
                if (!TextUtils.isEmpty(docId)) {
                    list.add(docId);
                }
            }
        } catch (Exception e) {
            // Ignore
        } finally {
            IoUtils.closeQuietly(cursor);
        }
        return list;
    }

    private void copyFiles(String sourceRoot, String targetRoot) throws Exception {
        mCountDownLatch = new CountDownLatch(1);
        // Copy folder and child files
        bots.roots.openRoot(sourceRoot);
        bots.directory.selectDocument(TARGET_FOLDER);
        device.waitForIdle();
        bots.main.clickToolbarOverflowItem(COPY);
        device.waitForIdle();
        bots.roots.openRoot(targetRoot);
        bots.main.clickDialogOkButton();
        device.waitForIdle();

        // Wait until copy operation finished
        try {
            mCountDownLatch.await(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Cannot wait because of error." + e.toString());
        }

        assertTrue(mErrorReason, mOperationExecuted);
    }

    private void checkCopiedFiles(String rootLabel, RootInfo rootInfo,
            DocumentsProviderHelper helper) throws Exception {
        // Check that copied folder exists
        bots.roots.openRoot(rootLabel);
        device.waitForIdle();
        bots.directory.assertDocumentsPresent(TARGET_FOLDER);

        // Check that copied files exist
        DocumentInfo parent = helper.findDocument(rootInfo.documentId, TARGET_FOLDER);
        List<DocumentInfo> children = helper.listChildren(parent.documentId, TARGET_COUNT);
        for (DocumentInfo info : children) {
            Long size = mTargetFileList.get(info.displayName);
            assertNotNull("Cannot find file.", size);
            assertTrue("Copied file contents differ.", info.size == size);
        }
    }

    // Copy Internal Storage -> Internal Storage //
    public void testCopyDocumentToInternalStorage() throws Exception {
        createDocuments(StubProvider.ROOT_0_ID, rootDir0, mDocsHelper);
        copyFiles(StubProvider.ROOT_0_ID, StubProvider.ROOT_1_ID);

        // Check that original folder exists
        bots.roots.openRoot(StubProvider.ROOT_0_ID);
        bots.directory.assertDocumentsPresent(TARGET_FOLDER);

        // Check that copied files exist
        checkCopiedFiles(StubProvider.ROOT_1_ID, rootDir1, mDocsHelper);
    }

    // Copy SD Card -> Internal Storage //
    public void testCopyDocumentFromSDCard() throws Exception {
        createDocuments(mSDCardLabel, mSDCardRoot, mStorageDocsHelper);
        copyFiles(mSDCardLabel, Build.MODEL);

        // Check that original folder exists
        bots.roots.openRoot(mSDCardLabel);
        bots.directory.assertDocumentsPresent(TARGET_FOLDER);

        // Check that copied files exist
        checkCopiedFiles(Build.MODEL, mPrimaryRoot, mStorageDocsHelper);
    }

    // Copy Internal Storage -> SD Card //
    public void testCopyDocumentToSDCard() throws Exception {
        createDocuments(Build.MODEL, mPrimaryRoot, mStorageDocsHelper);
        copyFiles(Build.MODEL, mSDCardLabel);

        // Check that original folder exists
        bots.roots.openRoot(Build.MODEL);
        bots.directory.assertDocumentsPresent(TARGET_FOLDER);

        // Check that copied files exist
        checkCopiedFiles(mSDCardLabel, mSDCardRoot, mStorageDocsHelper);
    }
}
