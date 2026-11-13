package org.yosef.karov;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** נותן לנגן שנבחר גישה לקריאת קובץ אחד מתיקיית הקבצים של קרוב. */
public final class TvFileProvider extends ContentProvider {
    static Uri uri(Context context, String destination, String name) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".received")
                .appendPath(destination)
                .appendPath(name)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || TvFiles.viewerType(segments.get(1)) == null)
            throw new FileNotFoundException();
        try {
            TvFileStorage.Target target =
                    TvFileStorage.destinations(getContext()).get(segments.get(0));
            if (target == null) throw new FileNotFoundException();
            return TvFiles.checkedFile(target, segments.get(1));
        } catch (IOException | RuntimeException error) {
            throw new FileNotFoundException();
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            return TvFiles.viewerType(resolve(uri).getName());
        } catch (FileNotFoundException error) {
            return null;
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        String[] columns =
                projection == null
                        ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                        : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        try {
            File file = resolve(uri);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            result.addRow(row);
        } catch (FileNotFoundException ignored) {
        }
        return result;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
