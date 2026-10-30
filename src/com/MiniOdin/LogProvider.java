package com.MiniOdin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

// Permet de partager le journal comme vrai fichier (.log / .txt) : seuls les fichiers "minodin-*" du cache sont exposés
public class LogProvider extends ContentProvider {
    public boolean onCreate() { return true; }

    File file(Uri u) {
        String n = u.getLastPathSegment();
        if (n == null || n.indexOf('/') >= 0 || n.contains("..") || !n.startsWith("minodin-")) return null;
        File f = new File(getContext().getCacheDir(), n);
        return f.exists() ? f : null;
    }

    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        File f = file(u);
        if (f == null) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        File f = file(u);
        if (f == null) return null;
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    public String getType(Uri u) { return "text/plain"; }
    public Uri insert(Uri u, ContentValues v) { return null; }
    public int delete(Uri u, String s, String[] a) { return 0; }
    public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
