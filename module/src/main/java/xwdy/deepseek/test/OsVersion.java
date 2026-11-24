package xwdy.deepseek.test;

/**
 * 设备系统判定：只有 OPPO 系（ColorOS 11+ / realme UI 2.0+）能用本软件。
 *
 * 为什么把 realme 也算进来：realme UI 是 ColorOS 的衍生分支，主题引擎、主题商店、
 * /data/theme 结构、com.coloros.* / com.oplus.* 组件都是同一套，所以适配逻辑通用。
 *
 * 判定顺序：
 *  1. 品牌属性：ro.product.brand / manufacturer / vendor.brand 含 realme → realme UI；
 *     oplusrom / opporom 等 OPPO 属性存在 → ColorOS；
 *  2. 包特征（反射查包）：com.oplus.themestore、com.coloros.* 等任一存在 → ColorOS 系；
 *  3. 版本：ColorOS 取 oplusrom/opporom 主版本 ≥ 11；
 *     realme UI 若也能读到 ColorOS 基线就用基线判，否则看 realme UI 版本 ≥ 2.0，
 *     再不行按 Android ≥ 11 兜底（realme UI 2.0 起就是 Android 11）。
 *
 * 版本号使用纯 Java 解析，不依赖 Root / Shizuku。
 */
public final class OsVersion {

    /** ColorOS 支持的最低主版本。 */
    public static final int MIN_MAJOR = 11;
    /** realme UI 支持的最低主版本（2.0 起基于 ColorOS 11）。 */
    public static final int MIN_REALME_MAJOR = 2;
    /** 兜底：Android 11 = API 30。 */
    public static final int MIN_SDK = 30;

    public static final int FAMILY_NONE = 0;
    public static final int FAMILY_COLOROS = 1;
    public static final int FAMILY_REALME = 2;

    /** OPPO 系专有包，任意一个存在即说明是 ColorOS / realme UI 设备。 */
    public static final String[] MARKER_PACKAGES = {
            "com.oplus.themestore",
            "com.heytap.themestore",
            "com.oplus.uxdesign",
            "com.coloros.filemanager",
            "com.coloros.alarmclock",
            "com.oplus.settings",
    };

    private static int sMajor = -1;
    private static int sRealmeMajor = -1;
    private static int sFamily = -1;
    private static String sReason = "检测中…";
    private static volatile boolean sChecked;

    private OsVersion() {
    }

    // ---------------------------------------------------------------- 属性

    public static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : (String) v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** ColorOS 基线版本串（V17.0.0 这种）。 */
    public static String rawRom() {
        String rom = prop("ro.build.version.oplusrom");
        if (rom.isEmpty()) rom = prop("ro.build.version.opporom");
        return rom;
    }

    /** realme UI 自己的版本串（"realme UI 5.0" / "5.0" 这类），可能有也可能没有。 */
    public static String rawRealme() {
        String[] keys = {
                "ro.build.version.realmeui", "ro.realmeui.version",
                "ro.build.realmeui", "ro.realme.version",
        };
        for (String k : keys) {
            String v = prop(k);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static boolean isRealmeBrand() {
        String[] keys = {
                "ro.product.brand", "ro.product.manufacturer",
                "ro.product.vendor.brand", "ro.product.odm.brand",
                "ro.product.system.brand",
        };
        for (String k : keys) {
            String v = prop(k).toLowerCase();
            if (v.contains("realme")) return true;
        }
        return false;
    }

    private static boolean colorOsByProps() {
        if (!rawRom().isEmpty()) return true;
        String[] extras = {
                "ro.build.version.oplusrom.display",
                "ro.oplus.version", "ro.oplus.version.major",
                "ro.build.version.opporom.display", "ro.oppo.version",
                "ro.build.version.rom", "ro.build.oplusrom",
        };
        for (String k : extras) {
            if (!prop(k).isEmpty()) return true;
        }
        return false;
    }

    private static boolean colorOsByPackages() {
        for (String p : MARKER_PACKAGES) {
            if (packageExists(p)) return true;
        }
        return false;
    }

    private static boolean packageExists(String pkg) {
        try {
            Class<?> globals = Class.forName("android.app.AppGlobals");
            Object pm = globals.getMethod("getPackageManager").invoke(null);
            java.lang.reflect.Method m = pm.getClass().getMethod(
                    "getPackageInfo", String.class, int.class, int.class);
            Object info = m.invoke(pm, pkg, 0, android.os.Process.myUid() / 100000);
            return info != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 版本号

    /** 提取版本串中第一段数字，支持 V17.0.0、realme UI 7.0 等前缀。 */
    public static int parseDigits(String text) {
        if (text == null) return 0;
        int n = 0;
        boolean seen = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                seen = true;
                n = n * 10 + (c - '0');
                if (n > 100) return 0;
            } else if (seen) {
                break;
            }
        }
        return seen ? n : 0;
    }

    /** Parsing a property is pure computation: never request privilege or block startup. */
    private static int parseVersion(String version) {
        return parseDigits(version);
    }

    public static synchronized int major() {
        if (sMajor >= 0) return sMajor;
        sMajor = parseVersion(rawRom());
        return sMajor;
    }

    public static synchronized int realmeMajor() {
        if (sRealmeMajor >= 0) return sRealmeMajor;
        sRealmeMajor = parseVersion(rawRealme());
        return sRealmeMajor;
    }

    // ---------------------------------------------------------------- 综合判定

    public static synchronized int family() {
        if (sFamily >= 0) return sFamily;
        if (isRealmeBrand() || !rawRealme().isEmpty()) {
            sFamily = FAMILY_REALME;
        } else if (colorOsByProps() || colorOsByPackages()) {
            sFamily = FAMILY_COLOROS;
        } else {
            sFamily = FAMILY_NONE;
        }
        return sFamily;
    }

    /** 是否 OPPO 系（ColorOS 或 realme UI）。 */
    public static boolean colorOs() {
        return family() != FAMILY_NONE;
    }

    /** 是否可用：OPPO 系且版本达标。 */
    public static boolean supported() {
        switch (family()) {
            case FAMILY_COLOROS:
                return major() >= MIN_MAJOR;
            case FAMILY_REALME:
                // 能读到 ColorOS 基线就按基线判，否则看 realme UI 版本，再不行看 Android 版本
                if (!rawRom().isEmpty() && major() > 0) return major() >= MIN_MAJOR;
                if (realmeMajor() > 0) return realmeMajor() >= MIN_REALME_MAJOR;
                return android.os.Build.VERSION.SDK_INT >= MIN_SDK;
            default:
                return false;
        }
    }

    public static synchronized void reset() {
        sMajor = -1;
        sRealmeMajor = -1;
        sFamily = -1;
        sReason = "检测中…";
        sChecked = false;
    }

    /** 启动时做一次完整判定，把原因写进 sReason 供界面显示。 */
    public static synchronized void check() {
        if (sChecked) return;
        sChecked = true;
        int fam = family();
        if (fam == FAMILY_NONE) {
            sReason = "未检测到 ColorOS / realme UI（无 oplusrom 属性，也没有 OPPO 系组件）";
            return;
        }
        if (fam == FAMILY_REALME) {
            if (!rawRom().isEmpty() && major() > 0) {
                sReason = major() >= MIN_MAJOR
                        ? "realme UI（ColorOS 基线 " + major() + "）符合要求"
                        : "仅支持 realme UI 2.0 及以上，当前 ColorOS 基线 " + major();
            } else if (realmeMajor() > 0) {
                sReason = realmeMajor() >= MIN_REALME_MAJOR
                        ? "realme UI " + realmeMajor() + "，符合要求"
                        : "仅支持 realme UI 2.0 及以上，当前 realme UI " + realmeMajor();
            } else {
                sReason = android.os.Build.VERSION.SDK_INT >= MIN_SDK
                        ? "realme UI（版本未知，按 Android 11+ 放行）" : "仅支持 realme UI 2.0 及以上";
            }
            return;
        }
        int m = major();
        if (m <= 0) {
            sReason = "检测到 ColorOS，但版本号无法解析（" + label() + "）";
        } else if (m < MIN_MAJOR) {
            sReason = "仅支持 ColorOS 11 及以上，当前 ColorOS " + m;
        } else {
            sReason = "ColorOS " + m + "，符合要求";
        }
    }

    public static String reason() {
        return sReason;
    }

    /** 给界面显示的当前系统描述。 */
    public static String label() {
        int fam = family();
        if (fam == FAMILY_REALME) {
            String rv = rawRealme();
            if (!rawRom().isEmpty()) return "realme UI（ColorOS " + rawRom().replaceFirst("^[Vv]", "") + "）";
            if (!rv.isEmpty()) return "realme UI " + rv.replaceFirst("^[^0-9]*", "");
            return "realme UI（版本未知）";
        }
        if (fam == FAMILY_COLOROS) {
            String rom = rawRom();
            return rom.isEmpty() ? "ColorOS（版本未知）" : "ColorOS " + rom.replaceFirst("^[Vv]", "");
        }
        return "非 OPPO 系系统（" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "）";
    }

    public static String unsupportedHint() {
        int fam = family();
        if (fam == FAMILY_NONE) {
            return "本软件仅支持 ColorOS 11+ / realme UI 2.0+，当前系统不是 OPPO 系";
        }
        if (fam == FAMILY_REALME) {
            int v = realmeMajor();
            return v > 0 ? "仅支持 realme UI 2.0 及以上，当前 realme UI " + v
                    : "仅支持 realme UI 2.0 及以上";
        }
        if (major() <= 0) return "仅支持 ColorOS 11 及以上，当前 " + label();
        return "仅支持 ColorOS 11 及以上，当前 ColorOS " + major();
    }
}
