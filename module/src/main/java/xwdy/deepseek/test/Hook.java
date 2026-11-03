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

    /** 从 MCTGA 的 ContentProvider 取该包的主题 zip 到目标应用缓存；没有就返回 null。 */
    private static String packBaseDir(String pkg) {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null) return null;
            android.content.Context ctx = (android.content.Context) app;
            android.net.Uri u = android.net.Uri.parse(
                    "content://xwdy.deepseek.test.packs/" + pkg);
            long size = -1;
            android.database.Cursor c = ctx.getContentResolver()
                    .query(u, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) size = c.getLong(0);
                } finally {
                    c.close();
                }
            }
            if (size <= 0) return null;
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "mctga-packs");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            java.io.File f = new java.io.File(dir, pkg);
            if (!f.isFile() || f.length() != size) {
                java.io.InputStream in = ctx.getContentResolver().openInputStream(u);
                if (in == null) return null;
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                out.close();
                in.close();
            }
            return dir.getAbsolutePath() + "/";
        } catch (Throwable t) {
            return null;
        }
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

    private static Drawable loadBg(Context c) {
        String key = isNight() ? "bg_night.png" : "bg_day.png";
        if (sBg != null && key.equals(sBgKey)) return sBg;
        try {
            java.io.InputStream is = Hook.class.getClassLoader().getResourceAsStream(key);
            if (is == null) {
                log("bg not found: " + key);
                return null;
            }
            Bitmap bm = BitmapFactory.decodeStream(is);
            is.close();
            if (bm != null) {
                sBg = new BitmapDrawable(c.getResources(), bm);
                sBgKey = key;
            }
            return sBg;
        } catch (Throwable t) {
            log("loadBg fail " + t);
            return null;
        }
    }

    private static void hookActivityBg(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedBridge.hookAllMethods(android.view.View.class, "setBackgroundColor", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        int c = ((Integer) p.args[0]).intValue();
                        if (c != 0xFF000000 && c != 0xFFFFFFFF) return;
                        android.view.View v = (android.view.View) p.thisObject;
                        String n = v.getResources().getResourceName(v.getId());
                        if (n == null) return;
                        String ln = n.toLowerCase();
                        if (ln.contains("appbar") || ln.contains("toolbar") || ln.contains("header")) {
                            v.setBackground(null);
                            logv("cleared bg color on " + n);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XC_MethodHook barBg = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        android.view.View v = (android.view.View) p.thisObject;
                        int id = v.getId();
                        if (id == android.view.View.NO_ID) return;
                        String n = v.getResources().getResourceName(id);
                        if (n != null && isBarId(n.toLowerCase())) p.setResult(null);
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(android.view.View.class, "setBackground", barBg);
            XposedBridge.hookAllMethods(android.view.View.class, "setBackgroundDrawable", barBg);
            XposedBridge.hookAllMethods(android.view.View.class, "setBackgroundResource", barBg);
            XposedHelpers.findAndHookMethod("android.app.Activity", lp.classLoader, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object o = p.thisObject;
                                if (!(o instanceof android.app.Activity)) return;
                                android.app.Activity act = (android.app.Activity) o;
                                if (isEditor(act.getComponentName().getClassName())) {
                                    log("skip editor " + act.getComponentName().getClassName());
                                    return;
                                }
                                Window w = act.getWindow();
                                if (w == null) return;
                                Drawable d = loadBg(act);
                                if (d == null) return;
                                android.view.View decor = w.getDecorView();
                                if (decor == null) return;
                                final android.view.View fDecor = decor;
                                final String pkg = lp.packageName;
                                decor.setBackground(d);
                                applyAlarmFix(fDecor, pkg, false);
                                decor.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            applyAlarmFix(fDecor, pkg, false);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }, 400);
                                decor.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            boolean wantDump = dumpEnabled();
                                            applyAlarmFix(fDecor, pkg, wantDump);
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }, 1600);
                                final android.view.ViewTreeObserver.OnGlobalLayoutListener gll =
                                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {                                    @Override
                                    public void onGlobalLayout() {
                                        if (sPending) return;
                                        sPending = true;
                                        fDecor.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                sPending = false;
                                                try {
                                                    int n = clearScrims(fDecor, pkg);
                                                    long now = System.currentTimeMillis();
                                                    if (now - sLastDeep > 300) {
                                                        sLastDeep = now;
                                                        n += clearBgCount(fDecor, isDeep(pkg) ? 40 : 10);
                                                        clearBarViews(fDecor, 0);
                                                        fDecor.setBackground(loadBg(fDecor.getContext()));
                                                    }
                                                    int bid2 = fDecor.getContext().getResources()
                                                            .getIdentifier("blur_overlay", "id", pkg);
                                                    if (bid2 != 0) hideAllById(fDecor, bid2, 0);
                                                    if (n > 0) logv("layout re-clear x" + n + " pkg=" + pkg);
                                                } catch (Throwable ignored) {
                                                }
                                            }
                                        });
                                    }
                                };
                                if (sDecorRef == null || sDecorRef.get() != fDecor) {
                                    sDecorRef = new java.lang.ref.WeakReference<android.view.View>(fDecor);
                                    fDecor.getViewTreeObserver().addOnGlobalLayoutListener(gll);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod("android.app.Activity", lp.classLoader, "onCreate",
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                android.app.Activity act = (android.app.Activity) p.thisObject;
                                if (isEditor(act.getComponentName().getClassName())) return;
                                Window w = act.getWindow();
                                if (w == null) return;
                                android.view.View decor = w.getDecorView();
                                if (decor == null) return;
                                Drawable d = loadBg(act);
                                if (d != null) {
                                    decor.setBackground(d);
                                    logv("onCreate bg " + act.getComponentName().getClassName());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("hooked health bg v2");
        } catch (Throwable t) {
            log("hook activity bg fail " + t);
        }
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

    private static boolean sPending;
    private static long sLastDeep;
    private static java.lang.ref.WeakReference<android.view.View> sDecorRef;

    private static int clearBgCount(android.view.View v, int depth) {
        if (v == null || depth < 0) return 0;
        int n = 0;
        try {
            if (v.getBackground() != null) n++;
            v.setBackground(null);
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

    private static boolean dumpEnabled() {
        try {
            Object v = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.SystemProperties", null),
                    "get", "debug.cosnight.dump", "0");
            return "1".equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String[] DEEP_CLEAR = {
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
        String c = className.toLowerCase();
        return c.contains("editor");
    }

    private static void applyAlarmFix(android.view.View decor, String pkg, boolean dump) {
        try {
            clearBg(decor, isDeep(pkg) ? 40 : 10);
            int cleared = clearScrims(decor, pkg);
            int bid = decor.getContext().getResources().getIdentifier("blur_overlay", "id", pkg);
            if (bid != 0) {
                if (hideAllById(decor, bid, 0)) log("hid blur_overlay");
                else logv("blur_overlay not found");
            }
            decor.setBackground(loadBg(decor.getContext()));
            logv("alarm fix pass dump=" + dump + " cleared=" + cleared);
            if (dump) {
                sDumpLines = 0;
                dumpTree(decor, 0);
            }
        } catch (Throwable t) {
            log("alarm fix fail " + t);
        }
    }

    private static void clearBg(android.view.View v, int depth) {
        if (v == null || depth < 0) return;
        try {
            v.setBackground(null);
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                clearBg(g.getChildAt(i), depth - 1);
            }
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
        log("loaded pkg=" + lp.packageName);

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
                                log("ui mode changed " + last + " -> " + ui + ", restarting systemui");
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
}
