package xwdy.deepseek.test;

/** 只允许白名单商店 + 主用户；不保留备份。 */
public final class ThemeEngine {

    public static boolean allowed(String pkg) {
        return "com.heytap.themestore".equals(pkg) || "com.oplus.themestore".equals(pkg)
                || "com.nearme.themestore".equals(pkg);
    }

    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String base(String pkg) {
        if (!allowed(pkg)) throw new IllegalArgumentException("Unsupported package");
        String p = "/storage/emulated/0/Android/data/" + pkg + "/files";
        return "P=" + quote(p) + "\nPKG=" + quote(pkg) + "\nT=\"$P/Themes\"\n"
                + "fail() { echo \"FAIL:$1\"; exit 1; }\n"
                + "suspended() { dumpsys package \"$PKG\" 2>/dev/null | grep 'User 0:.*suspended=' | head -n 1"
                + " | sed 's/.*suspended=\\([a-z]*\\).*/\\1/'; }\n"
                + "empty() { [ -f \"$T\" ] && [ ! -s \"$T\" ] && [ ! -L \"$T\" ]; }\n";
    }

    /** allowRoot=true 时接受 uid 0（Sui / su），否则只接受 shell 的 uid 2000。 */
    private static String guard(boolean allowRoot) {
        String identity = allowRoot
                ? "U=$(id -u)\n"
                  + "[ \"$U\" = 0 ] || [ \"$U\" = 2000 ] || fail SHELL_IDENTITY_REQUIRED\n"
                : "[ \"$(id -u)\" = 2000 ] || fail SHELL_IDENTITY_REQUIRED\n";
        return identity
                + "[ \"$(am get-current-user)\" = 0 ] || fail OWNER_PROFILE_REQUIRED\n"
                + "[ -d \"$P\" ] && [ -r \"$P\" ] && [ -w \"$P\" ] && [ -x \"$P\" ] || fail DIRECTORY_ACCESS\n"
                + "for X in \"$P\" \"$T\"; do [ ! -L \"$X\" ] || fail SYMLINK_REFUSED; done\n"
                + "DATA=$(readlink -f /storage/emulated/0/Android/data)\n"
                + "[ -n \"$DATA\" ] && [ \"$(readlink -f \"$P\")\" = \"$DATA/$PKG/files\" ] || fail PATH_MISMATCH\n";
    }

    public static String inspect(String pkg) {
        return base(pkg)
                + "echo UID:$(id -u)\nid -Z\necho CURRENT_USER:$(am get-current-user)\n"
                + "if [ ! -d \"$P\" ] || [ ! -r \"$P\" ] || [ ! -x \"$P\" ]; then echo STATE:INACCESSIBLE; exit 1; fi\n"
                + "if [ -L \"$T\" ]; then echo STATE:CONFLICT; exit 1; fi\n"
                + "V=$(suspended); echo SUSPENDED:$V\n"
                + "if [ -d \"$T\" ]; then echo STATE:DIRECTORY; "
                + "elif empty; then echo STATE:EMPTY; "
                + "elif [ -e \"$T\" ]; then echo STATE:CONFLICT; "
                + "else echo STATE:ABSENT; fi\n";
    }

    public static String lock(String pkg) {
        return lock(pkg, false);
    }

    public static String lock(String pkg, boolean allowRoot) {
        return base(pkg) + guard(allowRoot)
                + "am force-stop --user 0 \"$PKG\" 2>/dev/null || :\n"
                // Themes 若是目录（老版本商店会建目录），先整体挪到安全位置，绝不 rm -rf
                + "if [ -d \"$T\" ]; then B=\"$P/.Themes.lockdir-$(date +%Y%m%d-%H%M%S)\"; "
                + "mv \"$T\" \"$B\" || fail BACKUP_FAILED; echo \"INFO:DIR_MOVED:$B\"; "
                + "elif [ -e \"$T\" ] && ! empty; then fail EXISTING_FILE; fi\n"
                // 已经是空占位文件：本来就算锁定成功，只补一次挂起，不能再用 set -C 覆盖
                + "if empty; then\n"
                + "  pm suspend --user 0 \"$PKG\" 2>/dev/null || :\n"
                + "  if [ \"$(id -u)\" = 0 ]; then su 2000 -c \"pm suspend --user 0 $PKG\" >/dev/null 2>&1 || :; fi\n"
                + "  [ \"$(suspended)\" = true ] || echo INFO:SUSPEND_NOT_CONFIRMED\n"
                + "  echo RESULT:ALREADY_LOCKED\n"
                + "  exit 0\n"
                + "fi\n"
                + "(set -C; : > \"$T\") || fail PLACEHOLDER_CREATE\n"
                + "empty || fail FINAL_VERIFY\n"
                + "pm suspend --user 0 \"$PKG\" 2>/dev/null || :\n"
                + "if [ \"$(id -u)\" = 0 ]; then su 2000 -c \"pm suspend --user 0 $PKG\" >/dev/null 2>&1 || :; fi\n"
                + "[ \"$(suspended)\" = true ] || echo INFO:SUSPEND_NOT_CONFIRMED\n"
                + "echo RESULT:LOCKED\n";
    }

    public static String unlock(String pkg) {
        return unlock(pkg, false);
    }

    public static String unlock(String pkg, boolean allowRoot) {
        return base(pkg) + guard(allowRoot)
                + "pm unsuspend --user 0 \"$PKG\" 2>/dev/null || :\n"
                + "pm enable --user 0 \"$PKG\" 2>/dev/null || :\n"
                // ColorOS 上同一包可能被 root 与 shell 各挂起一次，只解一边会一直显示“已挂起”
                + "if [ \"$(id -u)\" = 0 ]; then su 2000 -c \"pm unsuspend --user 0 $PKG\" >/dev/null 2>&1 || :; fi\n"
                + "if empty; then rm -f \"$T\" || fail PLACEHOLDER_REMOVE; "
                + "elif [ -e \"$T\" ] && [ ! -d \"$T\" ]; then fail EXISTING_FILE; fi\n"
                // 锁定前若把 Themes 目录挪走过，这里按最新的一份还原
                + "B=$(ls -d \"$P\"/.Themes.lockdir-* 2>/dev/null | sort | tail -n 1)\n"
                + "if [ -n \"$B\" ] && [ ! -e \"$T\" ]; then "
                + "mv \"$B\" \"$T\" || fail RESTORE_FAILED; echo INFO:DIR_RESTORED; fi\n"
                + "V=$(suspended); echo \"SUSPENDED:$V\"\n"
                + "if [ \"$V\" = false ]; then echo RESULT:UNLOCKED; "
                + "else echo INFO:STILL_SUSPENDED; fi\n";
    }

    /** 只解冻：解除挂起（suspended）+ 恢复启用状态，不动 Themes 文件。 */
    public static String unsuspend(String pkg) {
        return base(pkg)
                + "U=$(id -u)\n"
                + "[ \"$U\" = 0 ] || [ \"$U\" = 2000 ] || fail SHELL_IDENTITY_REQUIRED\n"
                + "[ \"$(am get-current-user)\" = 0 ] || fail OWNER_PROFILE_REQUIRED\n"
                // ColorOS 上同一个包可能被 root 与 shell 各挂起一次，只解一边会一直显示“已挂起”
                + "pm unsuspend --user 0 \"$PKG\" 2>/dev/null || :\n"
                + "pm enable --user 0 \"$PKG\" 2>/dev/null || :\n"
                + "if [ \"$U\" = 0 ]; then su 2000 -c \"pm unsuspend --user 0 $PKG\" >/dev/null 2>&1 || :; fi\n"
                + "V=$(suspended); echo \"SUSPENDED:$V\"\n"
                + "if [ \"$V\" = false ]; then echo RESULT:UNSUSPENDED; "
                + "else echo \"LEFT:$(dumpsys package \"$PKG\" 2>/dev/null | grep -c suspendingPackage=)\"; "
                + "echo INFO:SUSPEND_UNKNOWN; fi\n";
    }

    /** 冻结：只挂起，不动 Themes 文件（与「锁定」分开）。 */
    public static String suspend(String pkg) {
        return base(pkg)
                + "U=$(id -u)\n"
                + "[ \"$U\" = 0 ] || [ \"$U\" = 2000 ] || fail SHELL_IDENTITY_REQUIRED\n"
                + "[ \"$(am get-current-user)\" = 0 ] || fail OWNER_PROFILE_REQUIRED\n"
                + "am force-stop --user 0 \"$PKG\" 2>/dev/null || :\n"
                + "pm suspend --user 0 \"$PKG\" 2>/dev/null || :\n"
                + "if [ \"$U\" = 0 ]; then su 2000 -c \"pm suspend --user 0 $PKG\" >/dev/null 2>&1 || :; fi\n"
                + "V=$(suspended); echo \"SUSPENDED:$V\"\n"
                + "if [ \"$V\" = true ]; then echo RESULT:SUSPENDED; "
                + "else echo INFO:SUSPEND_UNKNOWN; fi\n";
    }

    public static String state(String text) {
        for (String line : text.split("\r?\n")) if (line.startsWith("STATE:")) return line.substring(6);
        return "UNKNOWN";
    }

    public static String uid(String text) {
        for (String line : text.split("\r?\n")) {
            if (line.startsWith("UID:")) return line.substring(4).trim();
        }
        return "";
    }

    public static boolean operationSucceeded(String text, boolean lock) {
        for (String line : text.split("\r?\n")) {
            if (lock && (line.equals("RESULT:LOCKED") || line.equals("RESULT:ALREADY_LOCKED"))) return true;
            if (!lock && (line.equals("RESULT:UNLOCKED") || line.equals("RESULT:ALREADY_UNLOCKED"))) return true;
        }
        return false;
    }
}
