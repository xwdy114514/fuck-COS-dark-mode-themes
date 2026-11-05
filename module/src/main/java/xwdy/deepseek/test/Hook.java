package xwdy.deepseek.test;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Window;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static Drawable sBg;
    private static String sBgKey;

    private static final String[] HOOK_BG = {
            "com.coloros.alarmclock",
            "com.coloros.soundrecorder",
            "com.heytap.health",
            "com.heytap.cloud",
            "com.coloros.familyguard",
            "com.coloros.digitalwellbeing",
            "com.coloros.calendar",
            "com.coloros.filemanager",
            "com.coloros.gallery3d",
            "com.heytap.speechassist",
            "com.oplus.aimemory",
            "com.oplus.tips",
            "com.coloros.shortcuts",
    };

    /** 只注入插画 + 蒙层，不清任何背景：这些包没有主题素材，卡片需要保留。 */
    private static final String[] LIGHT_BG = {
            "com.oplus.wirelesssettings",
            "com.oplus.linker",
    };

    private static final String[] TARGETS = {
            "com.android.settings",
            "com.android.systemui",
            "com.android.contacts",
            "com.android.mms",
            "com.coloros.filemanager",
            "com.coloros.alarmclock",
            "com.coloros.calendar",
            "com.coloros.soundrecorder",
            "com.coloros.gallery3d",
            "com.coloros.video",
            "com.coloros.digitalwellbeing",
            "com.coloros.familyguard",
            "com.heytap.cloud",
            "com.heytap.health",
            "com.heytap.themestore",
            "com.heytap.speechassist",
            "com.oplus.aimemory",
            "com.oplus.tips",
            "com.coloros.shortcuts",
            "com.android.stk",
            "com.oplus.wirelesssettings",
            "com.oplus.linker",
    };

    /**
     * 只支持 ColorOS 11 及以上（含 11）。版本串由 OsVersion 解析，
     * 先走模块自己的 C 解析，退化到 shell。解析不出来按不支持处理。
     */
    private static Boolean sOsSupported;

    private static boolean isOplusSupported() {
        if (sOsSupported != null) return sOsSupported;
        try {
            sOsSupported = OsVersion.supported();
        } catch (Throwable t) {
            sOsSupported = false;
        }
        if (!sOsSupported) log("skip: not ColorOS/too old (" + OsVersion.reason() + ")");
        return sOsSupported;
    }

    private static void log(String s) {
        XposedBridge.log("CosNightTheme: " + s);
    }

    private static Boolean sDebug;

    private static boolean debugOn() {
        if (sDebug != null) return sDebug;
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            Object ctx = at.getClass().getMethod("getSystemContext").invoke(at);
            android.content.ContentResolver cr = (android.content.ContentResolver)
                    ctx.getClass().getMethod("getContentResolver").invoke(ctx);
            sDebug = android.provider.Settings.Global.getInt(cr, "mctga_debug", 0) != 0;
        } catch (Throwable t) {
            sDebug = false;
        }
        return sDebug;
    }

    /** 高频调试日志，默认关闭（settings put global mctga_debug 1 打开）。 */
    private static void logv(String s) {
        if (debugOn()) log(s);
    }

    private static boolean isNight() {
        try {
            Class<?> rc = Class.forName("android.content.res.Resources");
            Object sys = rc.getMethod("getSystem").invoke(null);
            Object cfg = rc.getMethod("getConfiguration").invoke(sys);
            int uiMode = cfg.getClass().getField("uiMode").getInt(cfg);
            return (uiMode & 0x30) == 0x20;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 主题里有没有这个包；没有就不注入插画，避免把没素材的界面也套上。 */

    /** 从 MCTGA 的 ContentProvider 取该包的主题 zip 到目标应用缓存；没有就返回 null。 */
    /** 公共资源目录：补齐时由 App 用 root/shell 写到这，目标进程直接读，不经过 Provider。 */
    private static final String PACK_DIR = "/data/local/tmp/mctga-packs/";

    private static String packBaseDir(String pkg) {
        try {
            java.io.File f = new java.io.File(PACK_DIR, pkg);
            if (f.isFile() && f.length() > 0) return PACK_DIR;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 判断主题 zip 里有没有夜间资源；拿不到 ZipFile 时按“有”处理，保持原行为。 */
    private static boolean zipHasNight(Object themeZip) {
        try {
            Object z = null;
            Class<?> c = themeZip.getClass();
            while (c != null && z == null) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.util.zip.ZipFile.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        z = f.get(themeZip);
                        if (z != null) break;
                    }
                }
                c = c.getSuperclass();
            }
            if (z == null) return true;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e =
                    ((java.util.zip.ZipFile) z).entries();
            while (e.hasMoreElements()) {
                if (e.nextElement().getName().contains("-night")) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 判断资源名是不是顶栏类（appbar／toolbar／abl…）。 */
    private static boolean containsBg(String p) {
        for (String s : HOOK_BG) if (s.equals(p)) return true;
        return false;
    }

    private static boolean isBarId(String ln) {
        return ln.contains("appbar") || ln.contains("toolbar")
                || ln.contains("header") || ln.contains("statusbar")
                || ln.endsWith("/abl") || ln.contains("app_bar")
                || ln.contains("action_bar") || ln.contains("title_bar");
    }

    /** 把 appbar / toolbar / header / statusbar 这类顶部条的背景清空，让插画透出来。 */
    private static void clearBarViews(android.view.View v, int depth) {
        if (v == null || depth > 24) return;
        try {
            int id = v.getId();
            if (id != android.view.View.NO_ID) {
                String n = v.getResources().getResourceName(id);
                if (n != null) {
                    String ln = n.toLowerCase();
                    if (isBarId(ln)) v.setBackground(null);
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                int c = g.getChildCount();
                for (int i = 0; i < c; i++) clearBarViews(g.getChildAt(i), depth + 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearNow(Object decor) {
        try {
            int[] ids = {0x7f0b0112, 0x7f0b0340, 0x7f0b0f97};
            for (int id : ids) {
                Object v = XposedHelpers.callMethod(decor, "findViewById", id);
                if (v != null) {
                    XposedHelpers.callMethod(v, "setBackground", new Object[]{null});
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearTree(Object v, int depth) {
        if (v == null || depth > 12) return;
        try {
            XposedHelpers.callMethod(v, "setBackground", new Object[]{null});
        } catch (Throwable ignored) {
        }
        try {
            int n = (int) XposedHelpers.callMethod(v, "getChildCount");
            for (int i = 0; i < n; i++) {
                Object c = XposedHelpers.callMethod(v, "getChildAt", i);
                clearTree(c, depth + 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void rewriteZip(XC_MethodHook.MethodHookParam p, int idx) {
        if (!isNight()) return;
        Object o = p.args[idx];
        if (!(o instanceof String)) return;
        String path = (String) o;
        if (!path.startsWith("res/drawable")) return;
        int slash = path.lastIndexOf('/');
        if (slash < 0) return;
        String name = path.substring(slash + 1);
        String np = "res/drawable-night-xxhdpi/" + name;
        try {
            Object has = XposedHelpers.callMethod(p.thisObject, "containsEntry", np);
            if (Boolean.TRUE.equals(has)) {
                p.args[idx] = np;
                logv("night -> " + np);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int sScrimId = -1;
    private static int sBlurId = -1;
    private static int sLastUiMode = -1;


    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam param) {
        log("MCTGA loaded");
    }


    /**
    /**
     * 主题作者在自己的主题包内放这张图，模块会把它当作应用背景：
     *   res/drawable-xxhdpi/coui_mctga_bg.9.png        浅色
     *   res/drawable-night-xxhdpi/coui_mctga_bg.9.png  深色
     * 兼容：没有 mctga 专用图时，回退到同目录的 coui_window_background_selector.9.png。
     */
    private static final String BG_DAY = "res/drawable-xxhdpi/coui_mctga_bg.9.png";
    private static final String BG_NIGHT = "res/drawable-night-xxhdpi/coui_mctga_bg.9.png";
    private static final String BG_DAY_ALT = "res/drawable-xxhdpi/coui_window_background_selector.9.png";
    private static final String BG_NIGHT_ALT = "res/drawable-night-xxhdpi/coui_window_background_selector.9.png";

    private static String sCurPkg;

    /** 蒙层：深色直接放行（主题的 night 图本身就是深色底），亮色叠 60% 白，缓解插画与文字冲突；不改主题包。 */
    private static final int MASK_DAY = 0x99FFFFFF;   // 60% 白

    private static Drawable withMask(Drawable base, boolean night) {
        if (base == null) return null;
        try {
            if (night) return base;   // 模块不再压暗：深色底交给主题的 night 图
            android.graphics.drawable.LayerDrawable l =
                    new android.graphics.drawable.LayerDrawable(
                            new Drawable[]{base, new android.graphics.drawable.ColorDrawable(MASK_DAY)});
            return l;
        } catch (Throwable t) {
            log("mask fail " + t);
            return base;
        }
    }

    /** 资源层蒙层：主题窗口背景走系统主题资源（contacts/mms/settings 等），不经 loadBg。 */
    private static final java.util.HashMap<Integer, Boolean> sBgResIds =
            new java.util.HashMap<Integer, Boolean>();

    private static int sDialpadId = -1;

    /** 拨号盘底板：主题给的是浅灰 9-patch，深色下用纯色（不带任何内置素材）。 */
    private static Drawable dialpadSolid(Drawable ref) {
        try {
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            float r = 84f;
            gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            gd.setColor(isNight() ? 0xFF101014 : 0xFFF6F3F8);
            return gd;
        } catch (Throwable t) {
            return ref;
        }
    }

    private static boolean isWindowBgName(String n) {
        if (n == null) return false;
        return n.startsWith("coui_window_background") || n.equals("oplus_window_background")
                || n.equals("coui_window_bg_with_card") || n.equals("coui_appbar_default_bg");
    }

    private static boolean alreadyMasked(Drawable d) {
        try {
            if (!(d instanceof android.graphics.drawable.LayerDrawable)) return false;
            android.graphics.drawable.LayerDrawable l = (android.graphics.drawable.LayerDrawable) d;
            int n = l.getNumberOfLayers();
            if (n < 2) return false;
            Drawable top = l.getDrawable(n - 1);
            if (!(top instanceof android.graphics.drawable.ColorDrawable)) return false;
            int c = ((android.graphics.drawable.ColorDrawable) top).getColor();
            return c == MASK_DAY;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void hookResMask(XC_LoadPackage.LoadPackageParam lp) {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object r = p.getResult();
                    if (!(r instanceof Drawable)) return;
                    Drawable dr = (Drawable) r;
                    if (alreadyMasked(dr)) return;
                    if (p.args == null || p.args.length == 0) return;
                    int id = 0;
                    Object a0 = p.args[0];
                    if (a0 instanceof android.util.TypedValue) {
                        id = ((android.util.TypedValue) a0).resourceId;
                    } else {
                        for (int i = 0; i < p.args.length; i++) {
                            if (p.args[i] instanceof Integer) {
                                id = ((Integer) p.args[i]).intValue();
                                break;
                            }
                        }
                    }
                    if (id == 0) return;
                    Boolean hit = sBgResIds.get(Integer.valueOf(id));
                    if (hit == null) {
                        String n = null;
                        try {
                            n = ((android.content.res.Resources) p.thisObject).getResourceEntryName(id);
                        } catch (Throwable ignored) {
                        }
                        if ("pb_bg_dialer".equals(n)) {
                            sDialpadId = id;
                            hit = Boolean.TRUE;
                        } else {
                            hit = Boolean.valueOf(isWindowBgName(n));
                        }
                        sBgResIds.put(Integer.valueOf(id), hit);
                    }
                    if (!hit.booleanValue()) return;
                    if (id == sDialpadId) {
                        p.setResult(dialpadSolid(dr));
                        return;
                    }
                    p.setResult(withMask(dr, isNight()));
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(android.content.res.Resources.class, "loadDrawable", h);
            XposedBridge.hookAllMethods(android.content.res.Resources.class, "getDrawable", h);
            XposedBridge.hookAllMethods(android.content.res.Resources.class, "getDrawableForDensity", h);
            log("hooked res mask");
        } catch (Throwable t) {
            log("hook res mask fail " + t);
        }
    }

    private static Drawable loadBg(Context c) {
        try {
            boolean night = isNight();
            String main = night ? BG_NIGHT : BG_DAY;
            String alt = night ? BG_NIGHT_ALT : BG_DAY_ALT;
            String cand = (sCurPkg == null ? "" : sCurPkg) + ":" + main;
            if (sBg != null && cand.equals(sBgKey)) return sBg;
            Bitmap bm = loadFromPacks(sCurPkg, main, alt);
            if (bm == null) {
                log("bg missing " + main + " pkg=" + sCurPkg);
                return null;
            }
            log("bg ok " + bm.getWidth() + "x" + bm.getHeight() + " pkg=" + sCurPkg);
            sBg = withMask(new BitmapDrawable(c.getResources(), bm), night);
            sBgKey = cand;
            return sBg;
        } catch (Throwable t) {
            log("loadBg fail " + t);
            return null;
        }
    }

    /** 先找本应用自己的主题包（系统 /data/theme → .theme 源文件 → 缓存），取不到再扫其它包。 */
    private static Bitmap loadFromPacks(String pkg, String entry, String alt) {
        java.io.File own = null;
        if (pkg != null) {
            java.io.File sys = new java.io.File("/data/theme/" + pkg);
            Bitmap bm = readPackFor(sys, entry, alt);
            if (bm != null) {
                own = sys;
                log("bg from system " + pkg + " " + entry);
                return bm;
            }
            bm = loadFromThemeFile(pkg, entry, alt);
            if (bm != null) {
                log("bg from theme file " + pkg + " " + entry);
                return bm;
            }
            java.io.File cache = new java.io.File(PACK_DIR, pkg);
            bm = readPackFor(cache, entry, alt);
            if (bm != null) {
                own = cache;
                log("bg from cache " + pkg + " " + entry);
                return bm;
            }
        }
        java.util.List<java.io.File> packs = new java.util.ArrayList<java.io.File>();
        java.io.File[] fs = new java.io.File(PACK_DIR).listFiles();
        if (fs != null) for (java.io.File f : fs) if (f.isFile()) packs.add(f);
        java.io.File[] ts = new java.io.File("/data/theme").listFiles();
        if (ts != null) for (java.io.File f : ts) if (f.isFile()) packs.add(f);
        for (String e : new String[]{entry, alt}) {
            for (java.io.File pack : packs) {
                if (own != null && pack.getAbsolutePath().equals(own.getAbsolutePath())) continue;
                Bitmap bm2 = readZipEntry(pack, e);
                if (bm2 != null) {
                    log("bg from " + pack.getName() + " " + e);
                    return bm2;
                }
            }
        }
        return null;
    }

    private static Bitmap readPackFor(java.io.File f, String entry, String alt) {
        if (f == null || !f.isFile()) return null;
        for (String e : new String[]{entry, alt}) {
            Bitmap bm = readZipEntry(f, e);
            if (bm != null) return bm;
        }
        return null;
    }

    private static Bitmap readZipEntry(java.io.File zip, String entry) {
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(zip);
            java.util.zip.ZipEntry e = zf.getEntry(entry);
            if (e == null) return null;
            java.io.InputStream is = zf.getInputStream(e);
            Bitmap bm = BitmapFactory.decodeStream(is);
            is.close();
            return bm;
        } catch (Throwable t) {
            return null;
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) { }
        }
    }

    private static void hookActivityBg(XC_LoadPackage.LoadPackageParam lp) {
        XposedBridge.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    android.app.Activity act = (android.app.Activity) p.thisObject;
                    String cls = act.getComponentName().getClassName();
                    if (isEditor(cls)) {
                        log("skip editor " + cls);
                        return;
                    }
                    Window w = act.getWindow();
                    if (w == null) return;
                    Drawable d = loadBg(act);
                    if (d == null) {
                        log("onResume bg=null act=" + cls);
                        return;
                    }
                    final android.view.View decor = w.getDecorView();
                    if (decor == null) return;
                    final String pkg = lp.packageName;
                    sCurPkg = pkg;
                    decor.setBackground(d);
                    applyAlarmFix(decor, pkg, false);
                    decor.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                applyAlarmFix(decor, pkg, false);
                            } catch (Throwable ignored) {
                            }
                        }
                    }, 400);
                    decor.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                applyAlarmFix(decor, pkg, false);
                            } catch (Throwable ignored) {
                            }
                        }
                    }, 1600);
                    XposedBridge.log("CosNightTheme: onResume bg=ok act=" + cls);
                } catch (Throwable t) {
                    XposedBridge.log("CosNightTheme: onResume fail " + t);
                }
            }
        });
        XposedBridge.log("CosNightTheme: onResume hooked");
    }

    private static int sDumpLines = 0;

    private static String idName(android.view.View v) {
        try {
            int id = v.getId();
            if (id == 0) return "-";
            try {
                return v.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                return "#" + Integer.toHexString(id);
            }
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void dumpTree(android.view.View v, int depth) {
        if (v == null || depth > 22) return;
        try {
            if (sDumpLines < 320) {
                sDumpLines++;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < depth; i++) sb.append("  ");
                sb.append(depth).append(' ').append(v.getClass().getSimpleName());
                sb.append(" id=").append(idName(v));
                sb.append(" b=").append(v.getLeft()).append(',').append(v.getTop())
                  .append('-').append(v.getRight()).append(',').append(v.getBottom());
                sb.append(" vis=").append(v.getVisibility());
                sb.append(" a=").append(v.getAlpha());
                sb.append(" e=").append(v.getElevation());
                try {
                    android.graphics.drawable.Drawable d = v.getBackground();
                    if (d == null) sb.append(" bg=null");
                    else if (d instanceof android.graphics.drawable.ColorDrawable)
                        sb.append(" bg=C#").append(String.format("%08X",
                                Integer.valueOf(((android.graphics.drawable.ColorDrawable) d).getColor())));
                    else sb.append(" bg=").append(d.getClass().getSimpleName());
                } catch (Throwable t) {
                    sb.append(" bg=?");
                }
                logv("V " + sb);
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpTree(g.getChildAt(i), depth + 1);
            }
        }
    }

    private static boolean hideAllById(android.view.View v, int id, int depth) {
        if (v == null || depth > 30) return false;
        boolean hit = false;
        if (v.getId() == id) {
            v.setVisibility(android.view.View.GONE);
            hit = true;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hideAllById(g.getChildAt(i), id, depth + 1)) hit = true;
            }
        }
        return hit;
    }


    /** 只清纯色/形状背景（容器底色），保留图片/9-patch 背景（主题按钮、图标）。 */
    private static int clearBgCount(android.view.View v, int depth) {
        if (v == null || depth < 0) return 0;
        int n = 0;
        try {
            android.graphics.drawable.Drawable d = v.getBackground();
            if (d != null && isPlainBg(d)) {
                v.setBackground(null);
                n++;
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += clearBgCount(g.getChildAt(i), depth - 1);
            }
        }
        return n;
    }

    /** 纯色、GradientDrawable、ColorDrawable、StateList 里全是纯色的，才算“底色”。 */
    private static boolean isPlainBg(android.graphics.drawable.Drawable d) {
        try {
            String cls = d.getClass().getName();
            if (cls.contains("BitmapDrawable") || cls.contains("NinePatch")) return false;
            if (d instanceof android.graphics.drawable.BitmapDrawable) return false;
            if (d instanceof android.graphics.drawable.NinePatchDrawable) return false;
            if (d instanceof android.graphics.drawable.StateListDrawable) {
                // 状态列表：看当前状态那张
                android.graphics.drawable.Drawable cur = d.getCurrent();
                return cur == null || isPlainBg(cur);
            }
            if (d instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable l = (android.graphics.drawable.LayerDrawable) d;
                for (int i = 0; i < l.getNumberOfLayers(); i++) {
                    if (!isPlainBg(l.getDrawable(i))) return false;
                }
                return true;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 悬浮按钮（FAB）自带底色，不能当“底色”清掉，否则只剩一团光晕。 */
    private static boolean isFab(android.view.View v) {
        try {
            String n = idName(v);
            if (n == null) return false;
            n = n.toLowerCase();
            return n.contains("floating_button") || n.contains("_fab") || n.endsWith("fab")
                    || n.contains("switch");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 清掉一层容器的背景（保留图片背景与 FAB 底色）。 */
    private static void clearBg(android.view.View v, int depth) {
        if (v == null || depth < 0) return;
        try {
            if (!isFab(v)) {
                android.graphics.drawable.Drawable d = v.getBackground();
                if (d != null && isPlainBg(d)) v.setBackground(null);
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                clearBg(g.getChildAt(i), depth - 1);
            }
        }
    }
    private static final String[] DEEP_CLEAR = {
            "com.heytap.health",
            "com.heytap.cloud",
            "com.coloros.alarmclock",
            "com.coloros.soundrecorder",
            "com.coloros.digitalwellbeing",
            "com.coloros.familyguard",
            "com.heytap.speechassist",
            "com.oplus.aimemory",
            "com.coloros.shortcuts",
            "com.coloros.calendar",
            "com.coloros.filemanager",
            "com.coloros.gallery3d",
            "com.oplus.tips",
    };

    private static boolean isDeep(String pkg) {
        for (String s : DEEP_CLEAR) {
            if (s.equals(pkg)) return true;
        }
        return false;
    }

    private static boolean isEditor(String className) {
        if (className == null) return false;
        return className.toLowerCase().contains("editor");
    }

    private static void applyAlarmFix(android.view.View decor, String pkg, boolean dump) {
        try {
            clearBg(decor, isDeep(pkg) ? 40 : 10);
            int cleared = clearScrims(decor, pkg);
            hideNoise(decor, pkg);
            dumpLoadingViews(decor, 0);
            Drawable bgX = loadBg(decor.getContext());
            if (bgX != null) decor.setBackground(bgX);
            logv("alarm fix pass dump=" + dump + " cleared=" + cleared);
            if (dump) {
                sDumpLines = 0;
                dumpTree(decor, 0);
            }
        } catch (Throwable t) {
            log("alarm fix fail " + t);
        }
    }

    private static int clearAllById(android.view.View v, int id, int depth, int clearDepth) {
        if (v == null || depth > 30) return 0;
        int hits = 0;
        if (v.getId() == id) {
            hits += clearBgCount(v, clearDepth);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                hits += clearAllById(g.getChildAt(i), id, depth + 1, clearDepth);
            }
        }
        return hits;
    }

    private static int clearScrims(android.view.View decor, String pkg) {
        int total = 0;
        try {
            android.content.res.Resources r = decor.getContext().getResources();
            String[] names = {"layout_empty_container", "appBarLayout", "world_clock_toolbar_include"};
            int[] depths = {4, 0, 1};
            for (int i = 0; i < names.length; i++) {
                int id = r.getIdentifier(names[i], "id", pkg);
                if (id == 0) continue;
                total += clearAllById(decor, id, 0, depths[i]);
            }
        } catch (Throwable t) {
            log("scrim fail " + t);
        }
        return total;
    }

    /** 顶部加载动画 / 骨架屏这类“一直在转”的视图，隐藏掉。 */
    private static final String[] HIDE_IDS = {
            "blur_overlay",
            "ai_title_loading",
            "eav",
            "pull_refresh_loading_view",
            "refresh_loading",
            "flAnimContainer",
            "loadingView",
            "loading_view",
            "skeleton_view",
    };

    /** 调试用：把 decor 里所有可见的“加载类”视图打出来（含 id、类名、bounds）。 */
    private static void dumpLoadingViews(android.view.View v, int depth) {
        if (v == null || depth > 40) return;
        try {
            String cls = v.getClass().getSimpleName();
            boolean interesting = cls.contains("Loading") || cls.contains("Progress")
                    || cls.contains("Rotate") || cls.contains("Anim")
                    || cls.contains("Circle") || cls.contains("Spin");
            if (interesting && v.getVisibility() == android.view.View.VISIBLE) {
                int id = v.getId();
                String name = "-";
                try {
                    name = id == android.view.View.NO_ID ? "-"
                            : v.getResources().getResourceName(id);
                } catch (Throwable ignored) {
                }
                android.graphics.Rect r = new android.graphics.Rect();
                v.getGlobalVisibleRect(r);
                log("LOADV " + cls + " id=" + name + " " + r.width() + "x" + r.height()
                        + " @" + r.left + "," + r.top);
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    dumpLoadingViews(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
        }
    }
    private static void hideNoise(android.view.View decor, String pkg) {
        try {
            android.content.res.Resources r = decor.getContext().getResources();
            for (String n : HIDE_IDS) {
                int id = r.getIdentifier(n, "id", pkg);
                if (id == 0) continue;
                if (hideAllById(decor, id, 0)) logv("hid " + n);
            }
        } catch (Throwable t) {
            log("hideNoise fail " + t);
        }
    }

    private static Boolean readAttachFlag() {
        try {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            if (at == null) return null;
            Object ctx = XposedHelpers.callMethod(at, "getSystemContext");
            if (ctx == null) return null;
            Object cr = XposedHelpers.callMethod(ctx, "getContentResolver");
            if (cr == null) return null;
            int v = android.provider.Settings.Global.getInt(
                    (android.content.ContentResolver) cr, "mctga_dark_attach", 1);
            return v != 0;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean darkAttachEnabled() {
        Boolean v = readAttachFlag();
        if (v == null) {
            try {
                de.robv.android.xposed.XSharedPreferences p =
                        new de.robv.android.xposed.XSharedPreferences("cfg");
                p.reload();
                v = p.getBoolean("attach_dark", true);
            } catch (Throwable t) {
                v = Boolean.TRUE;
            }
        }
        log("dark attach = " + v);
        return v;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        boolean hit = false;
        for (String t : TARGETS) {
            if (t.equals(lp.packageName)) { hit = true; break; }
        }
        if (!hit) return;

        // 深色适配是附加功能：界面关掉后不再挂任何 hook，进程完全走系统默认逻辑。
        if (!darkAttachEnabled()) {
            log("dark attach disabled, skip pkg=" + lp.packageName);
            return;
        }

        if (!isOplusSupported()) {
            log("skip: unsupported OS pkg=" + lp.packageName);
            return;
        }
        if ("1".equals(OsVersion.prop("persist.mctga.cloud_block"))) {
            log("cloud blocked, skip pkg=" + lp.packageName);
            return;
        }
        log("loaded pkg=" + lp.packageName);
        log("bg-test entry hookbg=" + (containsBg(lp.packageName) ? "1" : "0"));

        for (String hb : HOOK_BG) {
            if (hb.equals(lp.packageName)) {
                hookActivityBg(lp);
                break;
            }
        }



        if ("com.android.settings".equals(lp.packageName)) {
            XC_MethodHook clear = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object activity = p.thisObject;
                        Object window = XposedHelpers.callMethod(activity, "getWindow");
                        final Object decor = XposedHelpers.callMethod(window, "getDecorView");
                        clearNow(decor);
                        Runnable r = new Runnable() {
                            @Override
                            public void run() {
                                clearNow(decor);
                            }
                        };
                        XposedHelpers.callMethod(decor, "post", r);
                        XposedHelpers.callMethod(decor, "postDelayed", r, 800L);
                    } catch (Throwable t) {
                        log("appbar fail " + t);
                    }
                }
            };
            try {
                XposedHelpers.findAndHookMethod(
                        "com.oplus.settings.feature.homepage.OplusSettingsHomepageActivity",
                        lp.classLoader, "onCreate", "android.os.Bundle", clear);
                XposedHelpers.findAndHookMethod(
                        "com.oplus.settings.feature.homepage.OplusSettingsHomepageActivity",
                        lp.classLoader, "onResume", clear);
                log("hooked activity");
            } catch (Throwable t) {
                log("hook activity fail " + t);
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.res.OplusThemeResourcesPackage",
                    lp.classLoader,
                    "getThemeResources",
                    "android.content.res.IResourcesImplExt",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object res = p.getResult();
                            if (res == null) return;
                            try {
                                Object zip = XposedHelpers.getObjectField(res, "mPackageZipFile");
                                String pkg = (String) p.args[1];
                                String base = packBaseDir(pkg);
                                if (base == null) {
                                    if (zip != null) return;
                                    base = "/data/theme/";
                                }
                                Object newZip = XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass("android.content.res.OplusThemeZipFile", lp.classLoader),
                                        "getThemeZipFile", base, pkg, p.args[0]);
                                if (newZip == null && !"/data/theme/".equals(base)) {
                                    newZip = XposedHelpers.callStaticMethod(
                                            XposedHelpers.findClass(
                                                    "android.content.res.OplusThemeZipFile",
                                                    lp.classLoader),
                                            "getThemeZipFile", "/data/theme/", pkg, p.args[0]);
                                }
                                if (newZip != null && !(isNight() && !zipHasNight(newZip))) {
                                    XposedHelpers.setObjectField(res, "mPackageZipFile", newZip);
                                    XposedHelpers.setBooleanField(res, "mHasValue", true);
                                    XposedHelpers.setBooleanField(res, "mHasDrawable", true);
                                    if ("com.android.settings".equals(pkg)) log("forced zip " + pkg);
                                } else if (newZip != null) {
                                    log("skip force(no night) " + pkg);
                                }
                            } catch (Throwable t) {
                                log("force fail " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook getThemeResources fail " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.res.OplusThemeZipFile",
                    lp.classLoader,
                    "getInputStreamInner",
                    int.class, String.class,
                    "android.content.res.OplusThemeZipFile$MultiZipFile",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            rewriteZip(p, 1);
                        }
                    });
            log("hooked inner");
        } catch (Throwable t) {
            log("hook inner fail " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.res.OplusThemeZipFile",
                    lp.classLoader,
                    "getInputStream", int.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            rewriteZip(p, 1);
                        }
                    });
            log("hooked gis");
        } catch (Throwable t) {
            log("hook gis fail " + t);
        }

        if (isLight(lp.packageName)) hookLightBg(lp);
        if ("com.android.contacts".equals(lp.packageName)) hookDialpadFix(lp);
        hookResMask(lp);

        if ("com.android.systemui".equals(lp.packageName)) {
            sLastUiMode = isNight() ? 0x20 : 0x10;
            try {
                Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", lp.classLoader);
                XposedBridge.hookAllMethods(at, "handleConfigurationChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length == 0 || p.args[0] == null) return;
                            Object cfg = p.args[0];
                            int ui = XposedHelpers.getIntField(cfg, "uiMode") & 0x30;
                            int last = sLastUiMode;
                            sLastUiMode = ui;
                            if (last != -1 && last != ui && ui != 0) {
                                sBg = null;
                                sBgKey = null;
                                final boolean restart = "1".equals(OsVersion.prop("persist.mctga.systemui_restart"));
                                log("ui mode changed " + last + " -> " + ui
                                        + (restart ? ", restarting systemui" : ", bg cache cleared"));
                                if (restart) {
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                Thread.sleep(1200L);
                                            } catch (Throwable ignored) {
                                            }
                                            android.os.Process.killProcess(android.os.Process.myPid());
                                        }
                                    }).start();
                                }
                            }
                        } catch (Throwable t) {
                            log("ui mode fail " + t);
                        }
                    }
                });
                log("hooked ui mode");
            } catch (Throwable t) {
                log("hook ui mode fail " + t);
            }
            XC_MethodHook colorHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (isNight()) return;
                    try {
                        if (sScrimId == -1) {
                            Object res = p.thisObject;
                            sScrimId = ((Integer) XposedHelpers.callMethod(res, "getIdentifier",
                                    "notification_scrim_bg_mask_color", "color", "com.android.systemui")).intValue();
                            sBlurId = ((Integer) XposedHelpers.callMethod(res, "getIdentifier",
                                    "notification_material_blur_bg", "color", "com.android.systemui")).intValue();
                            logv("day color ids scrim=" + sScrimId + " blur=" + sBlurId);
                        }
                        int id = ((Integer) p.args[0]).intValue();
                        if (id == 0) return;
                        if (id == sScrimId) {
                            p.setResult(Integer.valueOf(0xFFF2F2F2));
                        } else if (id == sBlurId) {
                            p.setResult(Integer.valueOf(0xCCFFFFFF));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            try {
                XposedHelpers.findAndHookMethod("android.content.res.Resources", lp.classLoader,
                        "getColor", int.class, colorHook);
                XposedHelpers.findAndHookMethod("android.content.res.Resources", lp.classLoader,
                        "getColor", int.class, "android.content.res.Resources$Theme", colorHook);
                log("hooked day color");
            } catch (Throwable t) {
                log("hook day color fail " + t);
            }
        }
    }

    /** 轻模式：只换窗口背景（带蒙层），不动卡片背景。 */
    private static void hookLightBg(XC_LoadPackage.LoadPackageParam lp) {
        XposedBridge.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    android.app.Activity act = (android.app.Activity) p.thisObject;
                    Window w = act.getWindow();
                    if (w == null) return;
                    sCurPkg = lp.packageName;
                    int solid = isNight() ? 0xFF15121A : 0xFFF6F3F8;
                    Drawable d = new android.graphics.drawable.ColorDrawable(solid);
                    if (d == null) return;
                    android.view.View decor = w.getDecorView();
                    if (decor == null) return;
                    decor.setBackground(d);
                    log("light bg ok act=" + act.getComponentName().getClassName());
                } catch (Throwable t) {
                    log("light bg fail " + t);
                }
            }
        });
        log("light bg hooked " + lp.packageName);
    }

    private static boolean isLight(String pkg) {
        for (String s : LIGHT_BG) {
            if (s.equals(pkg)) return true;
        }
        return false;
    }

    /** 拨号盘容器：主题给的是半透明浅灰，深色下换成黑底（保留顶部圆角）。 */
    private static void hookDialpadFix(XC_LoadPackage.LoadPackageParam lp) {
        XposedBridge.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    android.app.Activity act = (android.app.Activity) p.thisObject;
                    Window w = act.getWindow();
                    if (w == null) return;
                    final android.view.View decor = w.getDecorView();
                    if (decor == null) return;
                    try {
                        w.setNavigationBarColor(isNight() ? 0xFF101014 : 0xFFF6F3F8);
                        w.setNavigationBarContrastEnforced(false);
                        w.setDecorFitsSystemWindows(false);
                        w.setNavigationBarColor(0);
                    } catch (Throwable ignored) {
                    }
                    // 拨号页背景：交给主题资源（loadBg 读主题包），模块不再内置任何素材
                    if (act.getComponentName().getClassName().contains("Dialtacts")) {
                        Drawable contactBg = loadBg(act);
                        if (contactBg != null) decor.setBackground(contactBg);
                    }
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                fixDialpad(decor);
                            } catch (Throwable ignored) {
                            }
                        }
                    };
                    decor.post(r);
                    decor.postDelayed(r, 500L);
                    decor.postDelayed(r, 1500L);
                } catch (Throwable t) {
                    log("dialpad fail " + t);
                }
            }
        });
        log("dialpad hooked " + lp.packageName);
    }

    private static void fixDialpad(android.view.View v) {
        if (v == null) return;
        try {
            android.graphics.Rect rc = new android.graphics.Rect();
            v.getGlobalVisibleRect(rc);
            if (rc.top >= 2100 && rc.width() > 800 && v.getBackground() != null) {
                v.setBackground(null);
            }
        } catch (Throwable ignored) {
        }
        try {
            String n = idName(v);
            if (n != null && n.equals("nav_chrome_group")) {
                log("nav clear hit " + v.getClass().getName());
                v.setBackgroundColor(isNight() ? 0xFF101014 : 0xFFF6F3F8);
                android.view.ViewParent pv = v.getParent();
                for (int k = 0; k < 2 && pv instanceof android.view.View; k++) {
                    ((android.view.View) pv).setBackground(null);
                    pv = pv.getParent();
                }
                if (v instanceof android.view.ViewGroup) {
                    android.view.ViewGroup g = (android.view.ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        android.view.View c = g.getChildAt(i);
                        c.setBackground(null);
                        if (c instanceof android.view.ViewGroup) {
                            android.view.ViewGroup gg = (android.view.ViewGroup) c;
                            for (int j = 0; j < gg.getChildCount(); j++) {
                                gg.getChildAt(j).setBackground(null);
                            }
                        }
                    }
                }
            }
            if (n != null && n.equals("dialpad_container")) {
                int color = isNight() ? 0xFF101014 : 0xFFF6F3F8;
                android.graphics.drawable.GradientDrawable gd =
                        new android.graphics.drawable.GradientDrawable();
                float r = 28f * v.getResources().getDisplayMetrics().density;
                gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                gd.setColor(color);
                v.setBackground(gd);
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fixDialpad(g.getChildAt(i));
        }
    }

    private static void clearBgAll(android.view.View v) {
        if (v == null) return;
        try {
            v.setBackground(null);
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) clearBgAll(g.getChildAt(i));
        }
    }

    // 直接读用户最新的 .theme 源文件：主题作者换图后（重新导出 .theme）即可换角色，
    // 不依赖系统是否把该包应用进 /data/theme，也不依赖 /data/local/tmp 的旧副本。
    private static java.util.zip.ZipFile sThemeZip;
    private static String sThemeZipPath;
    private static long sThemeZipMtime;

    private static java.io.File newestThemeFile() {
        java.io.File best = null;
        java.io.File[] fs = new java.io.File("/storage/emulated/0").listFiles();
        if (fs == null) return null;
        for (java.io.File f : fs) {
            if (f == null || !f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (!n.endsWith(".theme")) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private static Bitmap loadFromThemeFile(String pkg, String entry, String alt) {
        try {
            java.io.File tf = newestThemeFile();
            if (tf == null) return null;
            if (sThemeZip == null || !tf.getAbsolutePath().equals(sThemeZipPath)
                    || tf.lastModified() != sThemeZipMtime) {
                if (sThemeZip != null) {
                    try {
                        sThemeZip.close();
                    } catch (Throwable ignored) {
                    }
                    sThemeZip = null;
                }
                sThemeZip = new java.util.zip.ZipFile(tf);
                sThemeZipPath = tf.getAbsolutePath();
                sThemeZipMtime = tf.lastModified();
            }
            java.util.zip.ZipEntry e = sThemeZip.getEntry(pkg);
            if (e == null) return null;
            byte[] inner = readAllBytes(sThemeZip.getInputStream(e));
            java.util.zip.ZipInputStream zin =
                    new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(inner));
            java.util.zip.ZipEntry x;
            while ((x = zin.getNextEntry()) != null) {
                String name = x.getName();
                if (!name.equals(entry) && !name.equals(alt)) continue;
                Bitmap bm = android.graphics.BitmapFactory.decodeStream(zin);
                if (bm != null) return bm;
            }
        } catch (Throwable t) {
            log("theme file read fail " + t);
        }
        return null;
    }

    private static byte[] readAllBytes(java.io.InputStream in) throws java.io.IOException {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
