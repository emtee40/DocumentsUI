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

package com.android.documentsui;

import android.app.Activity;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.provider.Settings;
import android.support.test.filters.LargeTest;
import android.support.test.filters.Suppress;
import android.support.test.uiautomator.Configurator;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.test.ActivityInstrumentationTestCase2;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.util.Log;

import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.base.RootInfo;
import com.android.documentsui.bots.Bots;
import com.android.documentsui.bots.UiBot;
import com.android.documentsui.files.FilesActivity;
import com.android.documentsui.services.TestNotificationService;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import libcore.io.IoUtils;

/**
* This class test the below points
* - Copy file on the internal/external storage
*/
@LargeTest
public class StorageManagementUiTest extends ActivityInstrumentationTestCase2<FilesActivity> {
    private static final int TIMEOUT = 5000;

    private static final String AUTHORITY = "com.android.externalstorage.documents";

    private static final String PACKAGE_NAME = "com.android.documentsui.tests";

    private static final String TARGET_FOLDER = "test_folder";

    private static final int TARGET_COUNT = 1000;

    private static final int WAIT_TIME_SECONDS = 120;

    private static final String PRIMARY_ROOT_ID = "primary";

    private static final String DOWNLOAD = "Download";

    private static final String ACCESS_APP_NAME = "DocumentsUI Tests";

    private static final String ALLOW = "ALLOW";

    private static final String TURN_OFF = "TURN OFF";

    private static final String COPY = "Copy to…";

    private static final String MOVE = "Move to…";

    private static final String SHOW_INTERNAL_STORAGE = "Show internal storage";

    private static final String NOTIFICATION_TEXT = "Corrupted Virtual SD card";

    private static final String ERASE_AND_FORMAT = "ERASE & FORMAT";

    private static final String DONE = "DONE";

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

    private Bots mBots;

    private UiDevice mDevice;

    private Context mContext;

    private DocumentsProviderHelper mDocsHelper;

    private UiAutomation mAutomation;

    private ContentProviderClient mClient;

    private RootInfo mPrimaryRoot;

    private RootInfo mSDCardRoot;

    private String mSDCardLabel;

    private CountDownLatch mCountDownLatch;

    private boolean mOperationExecuted;

    private String mErrorReason;

    private boolean mIsVirtualSDCard;

    private int mStayOnValue;

    public StorageManagementUiTest() {
        super(FilesActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        mDevice = UiDevice.getInstance(getInstrumentation());
        // NOTE: Must be the "target" context,
        //       else security checks in content provider will fail.
        mContext = getInstrumentation().getTargetContext();
        mAutomation = getInstrumentation().getUiAutomation();
        mBots = new Bots(mDevice, mAutomation, mContext, TIMEOUT);
        Configurator.getInstance().setToolType(MotionEvent.TOOL_TYPE_MOUSE);
        mClient = mContext.getContentResolver().acquireUnstableContentProviderClient(
                AUTHORITY);
        mDocsHelper = new DocumentsProviderHelper(AUTHORITY, mClient);
        mDevice.setOrientationNatural();

        launchActivity();

        // Espresso register click() as (x, y) MotionEvents,
        // so if a drawer is on top of a file
        // we want to select, it will actually click the drawer.
        // Thus to start a clean state, we always try to close first.
        mBots.roots.closeDrawer();

        // Set "Stay awake" until test is finished.
        mStayOnValue = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN);
        mAutomation.executeShellCommand("settings put global stay_on_while_plugged_in 3");

        setRootInfo();
        if (mPrimaryRoot == null) {
            fail("Internal Storage not found");
        }

        if (mSDCardRoot == null) {
            assertTrue("Cannot set virtual SD Card", setVirtualSDCard());
            setRootInfo();
        }

        // If internal storage is not shown, turn on.
        try {
            mBots.main.clickToolbarOverflowItem(SHOW_INTERNAL_STORAGE);
        } catch (Exception e) {
            // ignore exception and hide menu view.
            mBots.keyboard.pressKey(KeyEvent.KEYCODE_BACK);
        }

        if (!isEnableAccessNotification()) {
            setNotificationAccess(true);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(TestNotificationService.ACTION_OPERATION_RESULT);
        mContext.registerReceiver(mReceiver, filter);
        mContext.sendBroadcast(new Intent(
                TestNotificationService.ACTION_CHANGE_EXECUTION_MODE));

        mOperationExecuted = false;
        mErrorReason = "No response from Notification";
    }

    @Override
    public void tearDown() throws Exception {
        // Delete test documents in Download Folder
        mBots.roots.openRoot(Build.MODEL);
        mBots.directory.openDocument(DOWNLOAD);
        if (mBots.directory.hasDocuments(TARGET_FOLDER)) {
            mBots.directory.selectDocument(TARGET_FOLDER);
            mDevice.waitForIdle();
            mBots.main.clickToolbarItem(R.id.action_menu_delete);
            mBots.main.clickDialogOkButton();
            mDevice.waitForIdle();
        }

        deleteDocuments(Build.MODEL);
        deleteDocuments(mSDCardLabel);

        mContext.unregisterReceiver(mReceiver);
        if (isEnableAccessNotification()) {
            setNotificationAccess(false);
        }

        if (mIsVirtualSDCard) {
            mAutomation.executeShellCommand("sm set-virtual-disk false");
        }

        mAutomation.executeShellCommand("settings put global stay_on_while_plugged_in "
                + mStayOnValue);

        mDevice.unfreezeRotation();
        mClient.release();
        super.tearDown();
    }

    private void setRootInfo() throws RemoteException {
        List<String> rootList = getRootsDocumentIdList();
        int index = rootList.indexOf(PRIMARY_ROOT_ID);
        if (index == -1) {
            fail("Primary storage cannot be found");
        }
        mPrimaryRoot = mDocsHelper.getRoot(rootList.get(index));

        StorageManager storageManager= (StorageManager)mContext.getSystemService(
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
                mSDCardRoot = mDocsHelper.getRoot(rootList.get(index));
            } else if (diskInfo.isUsb()) {
                // TODO: USB storage cannot be connected while testing
                // because InstrumentationTest must be needed to connect a PC to device.
            }
        }
    }

    private List<String> getRootsDocumentIdList() {
        List<String> list = new ArrayList<String>();
        final Uri rootsUri = DocumentsContract.buildRootsUri(AUTHORITY);
        Cursor cursor = null;
        try {
            cursor = mClient.query(rootsUri, null, null, null, null);
            while (cursor.moveToNext()) {
                int index = cursor.getColumnIndex(Root.COLUMN_ROOT_ID);
                String docId = (index != -1) ? cursor.getString(index) : null;
                if (!TextUtils.isEmpty(docId)) {
                    list.add(docId);
                }
            }
        } catch (Exception e) {
            // ignore
        } finally {
            IoUtils.closeQuietly(cursor);
        }
        return list;
    }

    private void launchActivity() {
        final Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(
                UiBot.TARGET_PKG);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mPrimaryRoot != null) {
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(
                    mPrimaryRoot.getUri(), DocumentsContract.Root.MIME_TYPE_ITEM);
        }
        setActivityIntent(intent);
        getActivity(); // Launch the activity.
    }

    private boolean isEnableAccessNotification() {
        ContentResolver resolver = getActivity().getContentResolver();
        String listeners = Settings.Secure.getString(
                resolver, "enabled_notification_listeners");
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

    private void setNotificationAccess(boolean isAllowed) throws Exception {
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        getActivity().startActivity(intent);
        mDevice.waitForIdle();

        mBots.main.findMenuLabelWithName(ACCESS_APP_NAME).click();
        mDevice.waitForIdle();

        String targetObject = TURN_OFF;
        if (isAllowed) {
            targetObject = ALLOW;
        }
        mBots.main.findMenuLabelWithName(targetObject).click();
        mBots.keyboard.pressKey(KeyEvent.KEYCODE_BACK);
        mDevice.waitForIdle();
    }

    public boolean setVirtualSDCard() throws Exception {
        try {
            mAutomation.executeShellCommand("sm set-virtual-disk true");
            mDevice.openNotification();
            mDevice.wait(Until.hasObject(By.text(NOTIFICATION_TEXT)), TIMEOUT);
            UiObject2 title = mDevice.findObject(By.text(NOTIFICATION_TEXT));
            title.click();

            UiObject2 erase = mDevice.findObject(By.text(ERASE_AND_FORMAT));
            erase.click();
            mDevice.wait(Until.hasObject(By.text(DONE)), TIMEOUT);
            UiObject2 done = mDevice.findObject(By.text(DONE));
            done.click();
            mDevice.waitForIdle();

            mIsVirtualSDCard = true;
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private boolean createDocuments(String label, RootInfo root) throws Exception {
        if (TextUtils.isEmpty(label) || root == null) {
            return false;
        }

        // if Test folder is already created, delete it
        if (mBots.directory.hasDocuments(TARGET_FOLDER)) {
            deleteDocuments(label);
        }

        // Create folder and create file in its folder
        mBots.roots.openRoot(label);
        Uri uri = mDocsHelper.createFolder(root, TARGET_FOLDER);
        mDevice.waitForIdle();
        if (!mBots.directory.hasDocuments(TARGET_FOLDER)) {
            return false;
        }

        loadImages(uri);

        // check that image files are loaded completely
        DocumentInfo parent = mDocsHelper.findDocument(root.documentId,
                TARGET_FOLDER);
        List<DocumentInfo> children = mDocsHelper.listChildren(parent.documentId,
                TARGET_COUNT);
        for (DocumentInfo docInfo : children) {
            mTargetFileList.put(docInfo.displayName, docInfo.size);
        }
        assertTrue("Lack of loading file. File count = " + mTargetFileList.size(),
                mTargetFileList.size() == TARGET_COUNT);

        return true;
    }

   private void loadImages(final Uri root) throws Exception {
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final CountDownLatch latch = new CountDownLatch(1);
        exec.submit(new Runnable() {
            @Override
            public void run() {
                Context testContext = getInstrumentation().getContext();
                Resources res = testContext.getResources();
                try {
                    int resId = res.getIdentifier(
                            "uitest_images", "raw", testContext.getPackageName());
                    loadImageFromResources(root, resId, res);
                    latch.countDown();
                } catch (Exception e) {
                    // ignore
                    Log.d("StorageManagement", "Error occurs when loading image. ", e);
                }
            }
        });

        try {
            latch.await(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Cannot wait to load image because of error." + e.toString());
        }
        exec.shutdown();
    }

    private void loadImageFromResources(final Uri root, int resId, Resources res) {
        ZipInputStream in = null;
        int read = 0;
        try {
            in = new ZipInputStream(res.openRawResource(resId));
            ZipEntry zipEntry = null;
            while ((zipEntry = in.getNextEntry()) != null) {
                String fileName = zipEntry.getName();
                Uri uri = mDocsHelper.createDocument(root, "image/png", fileName);
                byte[] buff = new byte[1024];
                while ((read = in.read(buff)) > 0) {
                    mDocsHelper.writeAppendDocument(uri, buff);
                }
                in.closeEntry();
                buff = null;
            }
        } catch (Exception e) {
            // ignore becase caller method has checked
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

    private boolean deleteDocuments(String label) throws Exception {
        if (TextUtils.isEmpty(label)) {
            return false;
        }

        mBots.roots.openRoot(label);
        if (!mBots.directory.hasDocuments(TARGET_FOLDER)) {
            return true;
        }

        if (mCountDownLatch != null) {
            assertTrue("Cannot wait because any operation is waiting now.",
                    mCountDownLatch.getCount() == 0);
        }

        mCountDownLatch = new CountDownLatch(1);
        mBots.directory.selectDocument(TARGET_FOLDER);
        mDevice.waitForIdle();

        mBots.main.clickToolbarItem(R.id.action_menu_delete);
        mBots.main.clickDialogOkButton();
        mDevice.waitForIdle();

        // Wait until copy operation finished
        try {
            mCountDownLatch.await(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // ignore
        }

        return !mBots.directory.hasDocuments(TARGET_FOLDER);
    }

    // Copy SD Card -> Internal Storage //
    public void testCopyDocumentFromSDCard() throws Exception {
        if (mSDCardRoot == null) {
            fail("Not found SD Card. Please insert SD Card or prepare virtual SD Card");
        }

        createDocuments(mSDCardLabel, mSDCardRoot);

        mCountDownLatch = new CountDownLatch(1);
        // Copy folder and child files from SDCard
        mBots.roots.openRoot(mSDCardLabel);
        mBots.directory.selectDocument(TARGET_FOLDER);
        mDevice.waitForIdle();
        mBots.main.clickToolbarOverflowItem(COPY);
        mDevice.waitForIdle();
        mBots.main.clickDialogOkButton();
        mDevice.waitForIdle();

        // Wait until copy operation finished
        try {
            mCountDownLatch.await(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Cannot wait because of error." + e.toString());
        }
        assertTrue(mErrorReason, mOperationExecuted);

        // Check that original folder exists
        mBots.roots.openRoot(mSDCardLabel);
        mBots.directory.assertDocumentsPresent(TARGET_FOLDER);

        // Check that copied folder exists
        mBots.roots.openRoot(Build.MODEL);
        mBots.directory.openDocument(DOWNLOAD);
        mBots.directory.assertDocumentsPresent(TARGET_FOLDER);

        // Check that child files exists
        DocumentInfo rootInfo = mDocsHelper.findDocument(
                mPrimaryRoot.documentId, DOWNLOAD);
        DocumentInfo parent = mDocsHelper.findDocument(
                rootInfo.documentId, TARGET_FOLDER);
        List<DocumentInfo> children = mDocsHelper.listChildren(parent.documentId, TARGET_COUNT);
        for (DocumentInfo info : children) {
            Long size = mTargetFileList.get(info.displayName);
            assertNotNull("Cannot find file.", size);
            assertTrue("Copied file contents differ", info.size == size);
        }
    }

    // Copy Internal Storage -> SD Card //
    public void testCopyDocumentToSDCard() throws Exception {
        if (mSDCardRoot == null) {
            fail("SD Card not found. Please insert SD Card or prepare virtual SD Card");
        }

        createDocuments(Build.MODEL, mPrimaryRoot);

        mCountDownLatch = new CountDownLatch(1);
        // Copy folder and child files to SD Card
        mBots.roots.openRoot(Build.MODEL);
        mBots.directory.selectDocument(TARGET_FOLDER);
        mDevice.waitForIdle();
        mBots.main.clickToolbarOverflowItem(COPY);
        mDevice.waitForIdle();
        mBots.roots.openRoot(mSDCardLabel);
        mBots.main.clickDialogOkButton();
        mDevice.waitForIdle();

        // Wait until copy operation finished
        try {
            mCountDownLatch.await(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Cannot wait because of error." + e.toString());
        }
        assertTrue(mErrorReason, mOperationExecuted);

        // Check that original folder exists
        mBots.roots.openRoot(mSDCardLabel);
        mBots.directory.assertDocumentsPresent(TARGET_FOLDER);

        // Check that copied folder exists
        mBots.roots.openRoot(Build.MODEL);
        mBots.directory.assertDocumentsPresent(TARGET_FOLDER);

        // Check that child files exists
        DocumentInfo parent = mDocsHelper.findDocument(
                mSDCardRoot.documentId, TARGET_FOLDER);
        List<DocumentInfo> children = mDocsHelper.listChildren(parent.documentId, TARGET_COUNT);
        for (DocumentInfo info : children) {
            Long size = mTargetFileList.get(info.displayName);
            assertNotNull("Cannot found file.", size);
            assertTrue("Not same File.", info.size == size);
        }
    }
}
