package xwdy.deepseek.test;

import java.io.File;
import java.util.zip.ZipFile;

/** Read-only evidence of an applied application theme. Cache/source files cannot activate it. */
final class AppliedTheme {
    private static final File ROOT = new File("/data/theme");
    private static final long RECHECK_NS = 500000000L;
    private static long checkedAt;
    private static boolean active;

    private AppliedTheme() { }

    static synchronized boolean active() {
        long now = System.nanoTime();
        if (checkedAt == 0 || now - checkedAt >= RECHECK_NS) {
            active = hasApplicationResources(ROOT);
            checkedAt = now;
        }
        return active;
    }

    /** No recursion: applying/, config, icons, wallpapers and downloaded .theme files are not proof. */
    static boolean hasApplicationResources(File root) {
        try {
            File[] files = root.listFiles();
            if (files == null) return false; // unconfirmed state must not revive cached artwork
            for (File file : files) {
                String name = file.getName();
                if (!name.matches("com\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) continue;
                if (!file.isFile() || file.length() == 0) continue;
                if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) continue;
                try (ZipFile zip = new ZipFile(file)) {
                    if (zip.size() > 0) return true;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return false;
    }
}
