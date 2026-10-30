/*
 * Copyright (C) 2018 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.phenotype;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class ConfigurationProvider extends ContentProvider {
    private String namespace(Uri uri) {
        return "com.google.android.gms.phenotype".equals(uri.getAuthority()) && uri.getPathSegments().size() == 1
                && uri.getQuery() == null && uri.getFragment() == null ? uri.getLastPathSegment() : null;
    }
    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        Map<String, String> values = FolsomPhenotypeConfiguration.rows(getContext(), namespace(uri));
        if (values == null) values = SwitchFlags.rows(namespace(uri));
        if (values != null) for (Map.Entry<String, String> entry : values.entrySet()) {
            cursor.addRow(new String[]{entry.getKey(), entry.getValue()});
        }
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues contentValues) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String s, @Nullable String[] strings) {
        if (s != null || strings != null && strings.length != 1) throw new IllegalArgumentException();
        return FolsomPhenotypeConfiguration.delete(getContext(), namespace(uri), strings == null ? null : strings[0]);
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues contentValues, @Nullable String s, @Nullable String[] strings) {
        if (s != null || strings != null || contentValues == null) throw new IllegalArgumentException();
        return FolsomPhenotypeConfiguration.update(getContext(), namespace(uri), contentValues);
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        return FolsomPhenotypeConfiguration.call(getContext(), method, arg, extras);
    }
}
