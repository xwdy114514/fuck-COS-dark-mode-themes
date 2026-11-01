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

    private static String sModulePath;
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

    private static void log(String s) {
        XposedBridge.log("CosNightTheme: " + s);
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
                log("night -> " + np);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void rewriteStream(XC_MethodHook.MethodHookParam p, int idx) {
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
            Object tr = XposedHelpers.getObjectField(p.thisObject, "mThemeResources");
            if (tr == null) return;
            Object zip = XposedHelpers.getObjectField(tr, "mPackageZipFile");
            if (zip == null) return;
            Object has = XposedHelpers.callMethod(zip, "containsEntry", np);
            if (Boolean.TRUE.equals(has)) {
                p.args[idx] = np;
                log("stream night -> " + np);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int sScrimId = -1;
    private static int sBlurId = -1;
    private static int sLastUiMode = -1;

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam param) {
        try {
            sModulePath = XposedBridge.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            log("module path=" + sModulePath);
        } catch (Throwable t) {
            log("module path fail " + t);
        }
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
                            log("cleared bg color on " + n);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
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
                                                        fDecor.setBackground(loadBg(fDecor.getContext()));
                                                    }
                                                    int bid2 = fDecor.getContext().getResources()
                                                            .getIdentifier("blur_overlay", "id", pkg);
                                                    if (bid2 != 0) hideAllById(fDecor, bid2, 0);
                                                    if (n > 0) log("layout re-clear x" + n + " pkg=" + pkg);
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
                                    log("onCreate bg " + act.getComponentName().getClassName());
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
                log("V " + sb);
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
                else log("blur_overlay not found");
            }
            decor.setBackground(loadBg(decor.getContext()));
            log("alarm fix pass dump=" + dump + " cleared=" + cleared);
            if (dump) {
                sDumpLines = 0;
                dumpTree(decor, 0);
            }
        } catch (Throwable t) {
            log("alarm fix fail " + t);
        }
    }

    private static void paintPartial(android.view.View v, String part, Drawable d) {
        if (v == null) return;
        try {
            int id = v.getId();
            if (id != 0) {
                String name = v.getResources().getResourceEntryName(id);
                if (name != null && name.toLowerCase().contains(part)) {
                    v.setBackground(d);
                    log("painted " + name);
                }
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                paintPartial(g.getChildAt(i), part, d);
            }
        }
    }

    private static void applyThemeBg(android.app.Activity act) {
        try {
            Window w = act.getWindow();
            if (w == null) return;
            Drawable d = loadBg(act);
            if (d == null) return;
            android.view.View decor = w.getDecorView();
            if (decor == null) return;
            decor.setBackground(d);
            android.view.View content = decor.findViewById(android.R.id.content);
            if (content != null) clearBg(content, 10);
        } catch (Throwable ignored) {
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        boolean hit = false;
        for (String t : TARGETS) {
            if (t.equals(lp.packageName)) { hit = true; break; }
        }
        if (!hit) return;

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
                                if (zip != null) return;
                                Object newZip = XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass("android.content.res.OplusThemeZipFile", lp.classLoader),
                                        "getThemeZipFile", "/data/theme/", pkg, p.args[0]);
                                if (newZip != null) {
                                    XposedHelpers.setObjectField(res, "mPackageZipFile", newZip);
                                    XposedHelpers.setBooleanField(res, "mHasValue", true);
                                    XposedHelpers.setBooleanField(res, "mHasDrawable", true);
                                    if ("com.android.settings".equals(pkg)) log("forced zip " + pkg);
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
                            log("day color ids scrim=" + sScrimId + " blur=" + sBlurId);
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
