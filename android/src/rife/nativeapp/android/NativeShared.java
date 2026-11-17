/*
 * Copyright 2026 Geert Bevin (gbevin[remove] at uwyn dot com)
 * Licensed under the Apache License, Version 2.0 (the "License")
 */
package rife.nativeapp.android;

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
import java.net.URLConnection;
import java.nio.file.Files;

/**
 * Hands the one file that a page saved to the app that it's shared with:
 * another app can't read a file of this one, it's given an address here that
 * it may read for as long. An app declares it in its manifest:
 * <pre>
 * &lt;provider android:name="rife.nativeapp.android.NativeShared" android:authorities="${applicationId}.rife2.files"
 *           android:exported="false" android:grantUriPermissions="true"/&gt;
 * </pre>
 * with its package in place of {@code ${applicationId}}.
 *
 * @author Geert Bevin (gbevin[remove] at uwyn dot com)
 * @since 1.11.0
 */
public final class NativeShared extends ContentProvider {
    static String authority(Context context) {
        return context.getPackageName() + ".rife2.files";
    }

    private static File folder(Context context) {
        return new File(context.getCacheDir(), "rife2-shared");
    }

    /** Keeps a file under the name that a page gave it, in place of the one before: there's one at a time. */
    static File keep(Context context, String name, byte[] content) throws IOException {
        var folder = folder(context);
        var before = folder.listFiles();
        if (before != null) {
            for (var old : before) {
                old.delete();
            }
        }
        folder.mkdirs();
        // a name, whatever the page gave
        var file = new File(folder, new File(name).getName());
        Files.write(file.toPath(), content);
        return file;
    }

    /** The file that was kept last. */
    static File kept(Context context) {
        var files = folder(context).listFiles();
        return files == null || files.length == 0 ? new File(folder(context), "none") : files[0];
    }

    static Uri address(Context context, String name) {
        return new Uri.Builder().scheme("content").authority(authority(context)).appendPath(name).build();
    }

    private File file(Uri address) throws FileNotFoundException {
        var name = address.getLastPathSegment();
        var file = name == null ? null : new File(folder(getContext()), name);
        // only what's in the folder itself
        if (file == null || !file.isFile() || !folder(getContext()).equals(file.getParentFile())) {
            throw new FileNotFoundException(String.valueOf(address));
        }
        return file;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri address, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(address), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri address, String[] projection, String selection, String[] arguments, String order) {
        var asked = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        var answered = new MatrixCursor(asked, 1);
        try {
            var file = file(address);
            var row = answered.newRow();
            for (var column : asked) {
                row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? file.getName() : OpenableColumns.SIZE.equals(column) ? (Object) file.length() : null);
            }
        } catch (FileNotFoundException e) {
            // no row
        }
        return answered;
    }

    @Override
    public String getType(Uri address) {
        var type = URLConnection.guessContentTypeFromName(address.getLastPathSegment());
        return type == null ? "application/octet-stream" : type;
    }

    @Override
    public Uri insert(Uri address, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri address, String selection, String[] arguments) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri address, ContentValues values, String selection, String[] arguments) {
        throw new UnsupportedOperationException();
    }
}
