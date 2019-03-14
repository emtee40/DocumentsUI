/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.android.documentsui.base.DocumentInfo.getCursorInt;
import static com.android.documentsui.base.DocumentInfo.getCursorString;

import android.database.Cursor;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import com.android.documentsui.MenuManager;
import com.android.documentsui.archives.ArchivesProvider;
import com.android.documentsui.base.MimeTypes;
import com.android.documentsui.roots.RootCursorWrapper;
import com.android.documentsui.selection.SelectionHelper.SelectionObserver;
import android.net.Uri;
import com.android.documentsui.dirlist.DrmUtils;
import com.android.documentsui.base.DocumentInfo;
import com.android.documentsui.Model;
import java.util.Hashtable;
import java.util.List;
import com.google.common.collect.Lists;

import java.util.function.Function;

/**
 * A class that aggregates document metadata describing the selection. It can answer questions
 * like: Can the selection be deleted? and Does the selection contain a folder?
 *
 * <p>By collecting information in real-time as the selection changes the need to
 * traverse the entire selection in order to answer questions is eliminated.
 */
public class SelectionMetadata extends SelectionObserver
        implements MenuManager.SelectionDetails {

    private static final String TAG = "SelectionMetadata";
    private final static int FLAG_CAN_DELETE =
            Document.FLAG_SUPPORTS_REMOVE | Document.FLAG_SUPPORTS_DELETE;

    private final Function<String, Cursor> mDocFinder;

    private int mDirectoryCount = 0;
    private int mFileCount = 0;

    // Partial files are files that haven't been fully downloaded.
    private int mPartialCount = 0;
    private int mWritableDirectoryCount = 0;
    private int mNoDeleteCount = 0;
    private int mNoRenameCount = 0;
    private int mInArchiveCount = 0;
    private boolean mSupportsSettings = false;
    private Hashtable<String, Boolean> mCanForwards = new Hashtable<String, Boolean>();
    private List<String> mForbidenForwards = Lists.newArrayList();
    private Model mModel;

    public SelectionMetadata(Function<String, Cursor> docFinder) {
        mDocFinder = docFinder;
    }

    @Override
    public void onItemStateChanged(String modelId, boolean selected) {
        final Cursor cursor = mDocFinder.apply(modelId);
        if (cursor == null) {
            Log.w(TAG, "Model returned null cursor for document: " + modelId
                    + ". Ignoring state changed event.");
            return;
        }

        final int delta = selected ? 1 : -1;

        final String mimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        if (MimeTypes.isDirectoryType(mimeType)) {
            mDirectoryCount += delta;
        } else {
            mFileCount += delta;
        }

        final int docFlags = getCursorInt(cursor, Document.COLUMN_FLAGS);
        if ((docFlags & Document.FLAG_PARTIAL) != 0) {
            mPartialCount += delta;
        }
        if ((docFlags & Document.FLAG_DIR_SUPPORTS_CREATE) != 0) {
            mWritableDirectoryCount += delta;
        }
        if ((docFlags & FLAG_CAN_DELETE) == 0) {
            mNoDeleteCount += delta;
        }
        if ((docFlags & Document.FLAG_SUPPORTS_RENAME) == 0) {
            mNoRenameCount += delta;
        }
        if ((docFlags & Document.FLAG_PARTIAL) != 0) {
            mPartialCount += delta;
        }
        mSupportsSettings = (docFlags & Document.FLAG_SUPPORTS_SETTINGS) != 0 &&
                (mFileCount + mDirectoryCount) == 1;


        final String authority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
        if (ArchivesProvider.AUTHORITY.equals(authority)) {
            mInArchiveCount += delta;
        }

        if (DrmUtils.isHwDrmSupported()) {
            if (mimeType != null && (mimeType.startsWith("image/")
                    || mimeType.startsWith("audio/")
                    || mimeType.startsWith("video/")
                    || mimeType.equals("application/vnd.oma.drm.content"))) {
                DocumentInfo doc = DocumentInfo.fromDirectoryCursor(cursor);
                if (selected) {
                    boolean canForward = true;
                    if (doc.documentId != null && mCanForwards.containsKey(doc.documentId)) {
                        canForward = mCanForwards.get(doc.documentId);
                    } else {
                        String path = mModel.getModeIdPath(doc.documentId);
                        if (path != null) {
                            canForward = DrmUtils.canForward(Uri.parse(path));
                        }

                        if (doc.documentId != null) {
                            mCanForwards.put(doc.documentId, Boolean.valueOf(canForward));
                        }
                    }

                    if (!canForward && doc.documentId != null && !mForbidenForwards.contains(doc.documentId)) {
                        mForbidenForwards.add(doc.documentId);
                    }
                } else {
                    if (doc.documentId != null && mForbidenForwards.contains(doc.documentId)) {
                        mForbidenForwards.remove(doc.documentId);
                    }
                }
            }
        }
    }

    @Override
    public void onSelectionReset() {
        mFileCount = 0;
        mDirectoryCount = 0;
        mPartialCount = 0;
        mWritableDirectoryCount = 0;
        mNoDeleteCount = 0;
        mNoRenameCount = 0;
    }

    @Override
    public boolean containsDirectories() {
        return mDirectoryCount > 0;
    }

    @Override
    public boolean containsFiles() {
        return mFileCount > 0;
    }

    @Override
    public int size() {
        return mDirectoryCount + mFileCount;
    }

    @Override
    public boolean containsPartialFiles() {
        return mPartialCount > 0;
    }

    @Override
    public boolean containsFilesInArchive() {
        return mInArchiveCount > 0;
    }

    @Override
    public boolean canDelete() {
        return size() > 0 && mNoDeleteCount == 0;
    }

    @Override
    public boolean canExtract() {
        return size() > 0 && mInArchiveCount == size();
    }

    @Override
    public boolean canRename() {
        return mNoRenameCount == 0 && size() == 1;
    }

    @Override
    public boolean canViewInOwner() {
        return mSupportsSettings;
    }

    @Override
    public boolean canPasteInto() {
        return mDirectoryCount == 1 && mWritableDirectoryCount == 1 && size() == 1;
    }

    @Override
    public boolean canOpenWith() {
        return size() == 1 && mDirectoryCount == 0 && mInArchiveCount == 0 && mPartialCount == 0;
    }

    @Override
    public boolean canForward() {
        if (DrmUtils.isHwDrmSupported()) {
            return mForbidenForwards.isEmpty();
        }
        return true;
    }

    public void setModel(Model model) {
        mModel = model;
    }
}
