package xwdy.deepseek.test;

/**
 * 环境检测：判断当前设备能不能真正用上「深色主题适配」。
 * 有 Root 时能直接看 /data/adb 与 /data/theme；没有 Root 时只报告能确认的部分，
 * 不把“读不到”说成“不可用”。
 */
public final class EnvCheck {

    public static final int LEVEL_UNKNOWN = -1;
    public static final int LEVEL_UNAVAILABLE = 0;
    public static final int LEVEL_LIMITED = 1;
    public static final int LEVEL_USABLE = 2;

    private static final String[] SAMPLE_PACKAGES = {
            "com.android.settings", "com.android.systemui",
            "com.coloros.alarmclock", "com.coloros.calendar",
            "com.coloros.filemanager", "com.coloros.gallery3d",
    };

    public static final class Report {
        public boolean rootOk;
        public boolean lspdRunning;
        public boolean themeDirPresent;
        public int themeFileCount;
        public int nightEntries;
        public String samplePkg = "";
        public int sampleNight;
        public boolean darkMode;
        public int level = LEVEL_UNKNOWN;
        public String verdict = "检测中…";
        public String detail = "";
    }

    private EnvCheck() {
    }

    /** Shared evidence for UI gating and diagnostics; a leftover directory is not a running daemon. */
    static String lspdProbe() {
        return "echo LSPD:$(pidof lspd 2>/dev/null | head -1)\n";
    }
    static boolean lspdRunning(String output) {
        for (String line : output.split("\r?\n")) {
            if (line.startsWith("LSPD:") && !line.substring(5).trim().isEmpty()) return true;
        }
        return false;
    }

    private static String probeScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("echo ROOT:$(id -u)\n");
        sb.append(lspdProbe());
        sb.append("[ -d /data/theme ] && echo THEMEDIR:1 || echo THEMEDIR:0\n");
        sb.append("echo COUNT:$(ls /data/theme 2>/dev/null | wc -l)\n");
        for (String p : SAMPLE_PACKAGES) {
            sb.append("if [ -f /data/theme/").append(p).append(" ]; then ")
                    .append("n=$(unzip -l /data/theme/").append(p)
                    .append(" 2>/dev/null | grep -c 'drawable-night'); ")
                    .append("echo PKG:").append(p).append("=$n; fi\n");
        }
        return sb.toString();
    }

    public static Report run(boolean darkMode) {
        Report r = new Report();
        r.darkMode = darkMode;

        ShellRunner.Result s = RootShell.shell(probeScript(), 30000);
        for (String line : s.out.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("ROOT:")) {
                r.rootOk = "0".equals(line.substring(5).trim());
            } else if (line.startsWith("LSPD:")) {
                r.lspdRunning = lspdRunning(s.out);
            } else if (line.startsWith("THEMEDIR:")) {
                r.themeDirPresent = "1".equals(line.substring(9).trim());
            } else if (line.startsWith("COUNT:")) {
                try {
                    r.themeFileCount = Integer.parseInt(line.substring(6).trim());
                } catch (Throwable ignored) {
                }
            } else if (line.startsWith("PKG:")) {
                int eq = line.lastIndexOf('=');
                if (eq < 0) continue;
                try {
                    int n = Integer.parseInt(line.substring(eq + 1).trim());
                    r.nightEntries += n;
                    if (n > r.sampleNight) {
                        r.sampleNight = n;
                        r.samplePkg = line.substring(4, eq);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        StringBuilder d = new StringBuilder();
        d.append("当前模式：").append(darkMode ? "深色" : "浅色");
        if (r.rootOk) {
            d.append(" · LSPosed ").append(r.lspdRunning ? "运行中" : "未运行");
            d.append(" · /data/theme ").append(r.themeDirPresent
                    ? r.themeFileCount + " 个目录项（不代表已应用主题包）" : "不存在");
            if (!r.samplePkg.isEmpty()) {
                d.append(" · 夜间资源 ").append(r.sampleNight).append(" 项");
            }
        } else {
            d.append(" · Root 检测未通过，主题与框架状态未确认");
        }

        if (!OsVersion.colorOs()) {
            r.level = LEVEL_UNAVAILABLE;
            r.verdict = OsVersion.unsupportedHint();
            d.insert(0, OsVersion.label() + " · ");
        } else if (!OsVersion.supported()) {
            r.level = LEVEL_UNAVAILABLE;
            r.verdict = OsVersion.unsupportedHint();
            d.insert(0, OsVersion.label() + " · ");
        } else if (!s.ok()) {
            r.level = LEVEL_UNKNOWN;
            r.verdict = "检测失败或超时，结果未知";
        } else if (!r.rootOk) {
            r.level = LEVEL_UNKNOWN;
            r.verdict = "未确认：需要可用 Root 通道读取主题与框架状态。";
        } else if (!r.lspdRunning) {
            r.level = LEVEL_UNAVAILABLE;
            r.verdict = "未检测到 LSPosed 运行，请检查框架状态。";
        } else if (r.nightEntries == 0) {
            r.level = LEVEL_LIMITED;
            if (!r.themeDirPresent || r.themeFileCount == 0) {
                r.verdict = "未检测到主题目录或目录为空。请先在商店应用主题。";
            } else {
                r.verdict = "抽样未检出夜间资源。可能未应用主题、主题无夜间资源或资源不可读；缓存存在不代表已生效。";
            }
        } else {
            r.level = LEVEL_USABLE;
            r.verdict = "已检出夜间资源与 LSPosed 进程，不代表所有界面已生效。当前为"
                    + (darkMode ? "深色" : "浅色") + "模式";
        }
        r.detail = d.toString();
        return r;
    }
}
