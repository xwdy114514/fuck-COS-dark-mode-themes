package xwdy.deepseek.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;

/**
 * 只读提供 files/theme-packs/<包名> 给目标应用进程（contacts/mms）。
 * 包放在本应用数据目录，不写 /data/theme。
 */
public class PackProvider extends ContentProvider {

    public static final String AUTHORITY = "xwdy.deepseek.test.packs";

    private File dir() {
        return new File(getContext().getFilesDir(), "theme-packs");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        checkCaller();
        File f = resolve(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 只允许主题相关的目标应用和本应用读取。 */
    private void checkCaller() throws java.io.FileNotFoundException {
        int uid = android.os.Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        try {
            String[] pkgs = getContext().getPackageManager().getPackagesForUid(uid);
            if (pkgs != null) {
                for (String p : pkgs) {
                    if ("com.android.contacts".equals(p) || "com.android.mms".equals(p)
                            || "com.android.settings".equals(p)
                            || "com.android.systemui".equals(p)) {
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        throw new java.io.FileNotFoundException("caller denied");
    }

    private File resolve(Uri uri) throws java.io.FileNotFoundException {
        String name = uri == null ? null : uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new java.io.FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(dir(), name);
        if (!f.isFile()) throw new java.io.FileNotFoundException(f.getAbsolutePath());
        return f;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"_size"});
        try {
            checkCaller();
            c.addRow(new Object[]{resolve(uri).length()});
        } catch (Throwable ignored) {
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
