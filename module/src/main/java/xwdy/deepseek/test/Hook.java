package xwdy.deepseek.test;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {

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
            "com.android.stk",
            "com.coloros.digitalwellbeing",
            "com.coloros.familyguard",
            "com.coloros.shortcuts",
            "com.heytap.cloud",
            "com.heytap.health",
            "com.heytap.speechassist",
            "com.heytap.themestore",
            "com.oplus.aimemory",
            "com.oplus.tips",
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
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        boolean hit = false;
        for (String t : TARGETS) {
            if (t.equals(lp.packageName)) { hit = true; break; }
        }
        if (!hit) return;

        log("loaded pkg=" + lp.packageName);

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
