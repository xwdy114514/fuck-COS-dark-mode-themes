package xwdy.deepseek.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/** 只读提供 files/theme-packs/<名字> 给作用域内的应用进程。 */
public class PackProvider extends ContentProvider {

    public static final String AUTHORITY = "xwdy.deepseek.test.packs";

    private static final String[] ALLOW = {
            "com.android.contacts", "com.android.mms", "com.android.settings",
            "com.android.systemui", "com.coloros.filemanager", "com.coloros.alarmclock",
            "com.coloros.calendar", "com.coloros.soundrecorder", "com.coloros.gallery3d",
            "com.coloros.video", "com.coloros.digitalwellbeing", "com.coloros.familyguard",
            "com.heytap.cloud", "com.heytap.health", "com.heytap.speechassist",
            "com.oplus.aimemory", "com.oplus.tips", "com.coloros.shortcuts",
            "com.heytap.themestore", "com.android.stk",
    };

    private static void log(String s) {
        android.util.Log.i("CosNightTheme", "PackProvider: " + s);
    }

    private File dir() {
        return new File(getContext().getFilesDir(), "theme-packs");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try {
            checkCaller();
            File f = resolve(uri);
            log("open " + f.getName() + " " + f.length());
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (java.io.FileNotFoundException e) {
            log("open fail " + uri + " " + e.getMessage());
            throw e;
        } catch (Throwable t) {
            log("open error " + uri + " " + t);
            throw new java.io.FileNotFoundException(String.valueOf(t));
        }
    }

    private void checkCaller() throws java.io.FileNotFoundException {
        int uid = android.os.Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        try {
            String[] pkgs = getContext().getPackageManager().getPackagesForUid(uid);
            if (pkgs != null) {
                for (String p : pkgs) {
                    for (String a : ALLOW) {
                        if (a.equals(p)) return;
                    }
                }
            }
            log("deny uid=" + uid + " pkg=" + (pkgs == null ? "null" : pkgs[0]));
        } catch (Throwable t) {
            log("deny check error uid=" + uid + " " + t);
        }
        throw new java.io.FileNotFoundException("caller denied");
    }

    private File resolve(Uri uri) throws java.io.FileNotFoundException {
        String name = uri == null ? null : uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new java.io.FileNotFoundException(String.valueOf(uri));
        }
        File d = dir();
        File f = new File(d, name);
        if (!f.isFile()) {
            log("missing " + f.getAbsolutePath() + " dirExists=" + d.isDirectory());
            throw new java.io.FileNotFoundException(f.getAbsolutePath());
        }
        return f;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"_size"});
        try {
            checkCaller();
            c.addRow(new Object[]{resolve(uri).length()});
        } catch (Throwable t) {
            log("query fail " + uri + " " + t);
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
