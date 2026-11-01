package xwdy.deepseek.test;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * MCTGA 控制台。
 *
 * 分区：主题常驻（主功能）→ 深色适配（附加）→ 高级。
 * 身份：写 /data/theme 与重启应用要 Root；pm suspend/unsuspend 用 Sui/Shizuku 的 shell 即可；
 *       空文件替换（锁定/解锁）要能读写商店的 Android/data。
 */
public class MainActivity extends Activity {

    private static final int COLOR_BG_TOP = 0xFF0D0E11;
    private static final int COLOR_BG_BOTTOM = 0xFF15171C;
    private static final int COLOR_CARD = 0xFF1A1C22;
    private static final int COLOR_CARD_ALT = 0xFF202228;
    private static final int COLOR_LINE = 0x14FFFFFF;
    private static final int COLOR_TEXT = 0xFFF1F0F5;
    private static final int COLOR_SUB = 0xFF97949F;
    private static final int COLOR_FAINT = 0xFF6E6B77;
    private static final int COLOR_ACCENT = 0xFF4ADE80;
    private static final int COLOR_ACCENT_DEEP = 0xFF1F6E43;
    private static final int COLOR_WARN = 0xFFFFB86B;
    private static final int COLOR_BAD = 0xFFF87171;

    private static final int BTN_PRIMARY = 0;
    private static final int BTN_SECONDARY = 1;
    private static final int BTN_GHOST = 2;

    private static final String PREFS = "cfg";
    private static final int REQ_PICK = 1;
    private static final int REQ_SHIZUKU = 100;

    private static final String[] CANDIDATES = {
            "com.heytap.themestore", "com.oplus.themestore", "com.nearme.themestore"
    };

    private static final String[] ADAPTED_APPS = {
            "com.android.settings", "com.android.contacts", "com.android.mms",
            "com.coloros.filemanager", "com.coloros.alarmclock", "com.coloros.calendar",
            "com.coloros.soundrecorder", "com.coloros.gallery3d", "com.coloros.video",
            "com.coloros.digitalwellbeing", "com.coloros.familyguard", "com.heytap.cloud",
            "com.heytap.health", "com.heytap.speechassist", "com.oplus.aimemory",
            "com.oplus.tips", "com.coloros.shortcuts"
    };

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean changing = new AtomicBoolean();
    private final AtomicBoolean querying = new AtomicBoolean();

    private SharedPreferences prefs;
    private Binder shizukuCallback;

    private TextView tvStore;
    private TextView tvStoreState;
    private TextView tvThemes;
    private TextView tvRoot;
    private TextView tvShizuku;
    private TextView tvChannel;
    private TextView tvEnv;
    private TextView tvLog;
    private TextView btnDark;
    private TextView btnInstall;
    private TextView tvRiskNote;
    private TextView btnFix;
    private TextView btnStoreImport;
    private TextView btnOneKey;
    private TextView tvNoRootHint;
    private LinearLayout advanced;

    private String storePkg = "";
    private String themesState = "检测中…";
    private boolean storeSuspended;
    private boolean storeSuspendedKnown;
    private boolean rootReady;
    private boolean rootChecked;
    private String channelText = "—";
    private EnvCheck.Report env;
    private long envCheckedAt;
    private boolean foreground;
    private boolean stopped;
    private boolean pendingInstallRisk;

    private final Shizuku.OnBinderReceivedListener receivedListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                }
            };

    private final Shizuku.OnBinderDeadListener deadListener = new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            post(new Runnable() {
                @Override
                public void run() {
                    refresh();
                }
            });
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int request, int result) {
                    if (request != REQ_SHIZUKU) return;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            toast(ShizukuBridge.granted() ? "Shizuku 已授权" : "Shizuku 授权未完成");
                            refresh();
                        }
                    });
                }
            };

    // ================================================================ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, 0);
        buildUi();
        try {
            Shizuku.addBinderReceivedListenerSticky(receivedListener);
            Shizuku.addBinderDeadListener(deadListener);
            Shizuku.addRequestPermissionResultListener(permissionListener);
        } catch (Throwable ignored) {
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!ShizukuBridge.connected()) requestBinderBroadcast(true);
            }
        }, 800);
        detectStore();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        refresh();
        // 从文件选择器返回时 onActivityResult 先于 onResume，风险确认对话框会弹不出来；
        // 这里补一次，等界面真正回到前台再问，而不是只丢一句 toast。
        if (pendingInstallRisk) {
            pendingInstallRisk = false;
            post(new Runnable() {
                @Override
                public void run() {
                    installTheme();
                }
            });
        }
    }

    @Override
    protected void onPause() {
        foreground = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        try {
            Shizuku.removeBinderReceivedListener(receivedListener);
            Shizuku.removeBinderDeadListener(deadListener);
            Shizuku.removeRequestPermissionResultListener(permissionListener);
        } catch (Throwable ignored) {
        }
        ui.removeCallbacksAndMessages(null);
        exec.shutdownNow();
        super.onDestroy();
    }

    // ================================================================ 界面构建

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COLOR_BG_TOP, COLOR_BG_BOTTOM}));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(28));
        scroll.addView(root);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                        insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        // 标题
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("MCTGA");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        TextView ver = new TextView(this);
        ver.setText(ver());
        ver.setTextSize(11);
        ver.setTextColor(COLOR_SUB);
        ver.setPadding(dp(10), dp(4), dp(10), dp(4));
        ver.setBackground(rounded(COLOR_CARD, 0, 20));
        head.addView(ver);
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("ColorOS 主题增强 · 深色适配 + 主题常驻");
        sub.setTextSize(11.5f);
        sub.setTextColor(COLOR_FAINT);
        sub.setPadding(0, dp(6), 0, 0);
        root.addView(sub);

        // 主状态卡
        LinearLayout status = card();
        status.setLayoutParams(marginTop(dp(16)));
        tvStore = row(status, "主题商店");
        tvStoreState = row(status, "商店状态");
        tvThemes = row(status, "Themes");
        divider(status);
        tvRoot = row(status, "Root");
        tvShizuku = row(status, "Shizuku");
        tvChannel = row(status, "权限通道");
        root.addView(status);

        // 主题常驻
        section(root, "主题常驻");
        btnInstall = button("选择 .theme 交给主题商店", BTN_PRIMARY, new Runnable() {
            @Override
            public void run() {
                pickTheme();
            }
        });
        addBtn(root, btnInstall);

        tvRiskNote = new TextView(this);
        tvRiskNote.setTextSize(11);
        tvRiskNote.setTextColor(COLOR_FAINT);
        tvRiskNote.setLineSpacing(dp(3), 1f);
        tvRiskNote.setPadding(dp(2), dp(8), dp(2), 0);
        tvRiskNote.setText("不会改 /data/theme：写入 下载/MCTGA（媒体库，无需 Root），"
                + "再打开主题商店详情页，由商店自己应用。");
        root.addView(tvRiskNote);
        btnFix = button("补齐主题资源（需 Root）", BTN_GHOST, new Runnable() {
            @Override
            public void run() {
                installTheme();
            }
        });
        addBtn(root, btnFix);

        btnStoreImport = button("打开文件管理导入 .theme", BTN_SECONDARY, new Runnable() {
            @Override
            public void run() {
                openFileManager();
                toast("在文件管理里点 .theme → 免费试用，用它代替补齐");
            }
        });
        addBtn(root, btnStoreImport);

        tvNoRootHint = new TextView(this);
        tvNoRootHint.setTextSize(11);
        tvNoRootHint.setTextColor(COLOR_FAINT);
        tvNoRootHint.setLineSpacing(dp(3), 1f);
        tvNoRootHint.setPadding(dp(2), dp(8), dp(2), 0);
        tvNoRootHint.setVisibility(View.GONE);
        tvNoRootHint.setText("没有 Root：一键生效已隐藏；「选择 .theme 交给主题商店」仍可用，不需要 Root。");
        root.addView(tvNoRootHint);
        pair(root,
                button("锁定主题", BTN_SECONDARY, new Runnable() {
                    @Override
                    public void run() {
                        changeTheme(true);
                    }
                }),
                button("解锁", BTN_GHOST, new Runnable() {
                    @Override
                    public void run() {
                        changeTheme(false);
                    }
                }));
        pair(root,
                button("解冻商店", BTN_GHOST, new Runnable() {
                    @Override
                    public void run() {
                        storeAction(true);
                    }
                }),
                button("冻结商店", BTN_GHOST, new Runnable() {
                    @Override
                    public void run() {
                        storeAction(false);
                    }
                }));
        pair(root,
                button("文件管理", BTN_GHOST, new Runnable() {
                    @Override
                    public void run() {
                        openFileManager();
                    }
                }),
                button("刷新状态", BTN_GHOST, new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                        refreshEnv(true);
                        toast("已刷新");
                    }
                }));

        TextView lockHint = new TextView(this);
        lockHint.setTextSize(11);
        lockHint.setTextColor(COLOR_FAINT);
        lockHint.setLineSpacing(dp(3), 1f);
        lockHint.setPadding(dp(2), dp(10), dp(2), 0);
        lockHint.setText("锁定后试用到期不会恢复默认主题；换主题前先解锁。");
        root.addView(lockHint);

        // 附加功能
        section(root, "附加 · 深色适配");
        LinearLayout envCard = card();
        envCard.setBackground(rounded(COLOR_CARD_ALT, COLOR_LINE, 16));
        tvEnv = new TextView(this);
        tvEnv.setTextSize(12.5f);
        tvEnv.setTextColor(COLOR_TEXT);
        tvEnv.setLineSpacing(dp(4), 1f);
        tvEnv.setText("检测中…");
        envCard.addView(tvEnv);
        root.addView(envCard);

        btnDark = button(darkSwitchText(), BTN_SECONDARY, new Runnable() {
            @Override
            public void run() {
                toggleDark();
            }
        });
        addBtn(root, btnDark);

        btnOneKey = button("一键生效（重启适配应用 + SystemUI）", BTN_GHOST, new Runnable() {
            @Override
            public void run() {
                oneKey();
            }
        });
        addBtn(root, btnOneKey);

        // 高级
        section(root, "高级");
        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        member(advanced, "切换主题商店包名", new Runnable() {
            @Override
            public void run() {
                switchStore();
            }
        });
        member(advanced, "诊断信息（可复制 / 分享）", new Runnable() {
            @Override
            public void run() {
                showDiagnostics();
            }
        });
        member(advanced, "关于 MCTGA", new Runnable() {
            @Override
            public void run() {
                showAbout();
            }
        });
        root.addView(advanced);
        action(root, "展开高级选项", BTN_GHOST, new Runnable() {
            @Override
            public void run() {
                advanced.setVisibility(advanced.getVisibility() == View.GONE
                        ? View.VISIBLE : View.GONE);
            }
        });

        // 最近操作
        tvLog = new TextView(this);
        tvLog.setTextSize(11.5f);
        tvLog.setTextColor(COLOR_SUB);
        tvLog.setLineSpacing(dp(3), 1f);
        tvLog.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvLog.setBackground(rounded(COLOR_CARD, 0, 14));
        LinearLayout.LayoutParams logLp = marginTop(dp(16));
        tvLog.setLayoutParams(logLp);
        tvLog.setText(brief(prefs.getString("last_log", "最近操作：无")));
        root.addView(tvLog);

        // 帮助
        LinearLayout help = card();
        help.setLayoutParams(marginTop(dp(16)));
        TextView helpTitle = new TextView(this);
        helpTitle.setText("使用顺序");
        helpTitle.setTextSize(13);
        helpTitle.setTypeface(Typeface.DEFAULT_BOLD);
        helpTitle.setTextColor(COLOR_TEXT);
        help.addView(helpTitle);
        TextView helpBody = new TextView(this);
        helpBody.setTextSize(12);
        helpBody.setTextColor(0xFFB6B3BF);
        helpBody.setLineSpacing(dp(4), 1f);
        helpBody.setPadding(0, dp(10), 0, 0);
        helpBody.setText("1  文件管理里找到 .theme，点它 → 免费试用\n"
                + "2  这里「选择 .theme」只复制到 下载/MCTGA 并打开商店详情页，不写 /data/theme\n"
                + "3  「一键生效」重启适配应用与 SystemUI\n"
                + "4  「锁定主题」挡住试用到期恢复；换主题先解锁\n"
                + "5  装/更新本 APK 后也要再执行一次一键生效");
        help.addView(helpBody);
        TextView helpNote = new TextView(this);
        helpNote.setTextSize(11);
        helpNote.setTextColor(COLOR_FAINT);
        helpNote.setPadding(0, dp(10), 0, 0);
        helpNote.setText("Sui / Shizuku 负责 pm 操作；读写商店目录需要能用 Android/data 的身份。"
                + "不要卸载主题商店。");
        help.addView(helpNote);
        root.addView(help);

        TextView footer = new TextView(this);
        footer.setText("项目守则：不催主题作者做适配 · 严禁倒卖");
        footer.setTextSize(10.5f);
        footer.setTextColor(COLOR_FAINT);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(rounded(COLOR_CARD, COLOR_LINE, 16));
        return box;
    }

    private void section(LinearLayout parent, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11);
        t.setTextColor(COLOR_FAINT);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(2), dp(26), 0, dp(10));
        parent.addView(t);
    }

    private TextView row(LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12.5f);
        l.setTextColor(COLOR_SUB);
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setTextSize(12.5f);
        v.setTextColor(COLOR_TEXT);
        v.setGravity(Gravity.END);
        r.addView(l);
        r.addView(v);
        parent.addView(r);
        return v;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        line.setLayoutParams(lp);
        parent.addView(line);
    }

    private void action(LinearLayout parent, String text, int style, Runnable onClick) {
        addBtn(parent, button(text, style, onClick));
    }

    private void addBtn(LinearLayout parent, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, v.getLayoutParams() != null
                ? v.getLayoutParams().height : dp(46));
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        parent.addView(v);
    }

    private void member(LinearLayout parent, String text, Runnable onClick) {
        addBtn(parent, button(text, BTN_GHOST, onClick));
    }

    private void pair(LinearLayout parent, View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(marginTop(dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        left.setLayoutParams(lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        rp.leftMargin = dp(12);
        right.setLayoutParams(rp);
        row.addView(left);
        row.addView(right);
        parent.addView(row);
    }

    private TextView button(String text, int style, final Runnable onClick) {
        final TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(style == BTN_PRIMARY ? 15f : 14f);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT, style == BTN_PRIMARY ? Typeface.BOLD : Typeface.NORMAL);
        b.setClickable(true);
        b.setFocusable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(style == BTN_GHOST ? 46 : 50)));

        Drawable bg;
        int ripple;
        if (style == BTN_PRIMARY) {
            b.setTextColor(0xFF0B2318);
            bg = rounded(COLOR_ACCENT, 0, 14);
            ripple = 0x330B2318;
        } else if (style == BTN_SECONDARY) {
            b.setTextColor(COLOR_ACCENT);
            bg = rounded(COLOR_CARD_ALT, COLOR_ACCENT_DEEP, 14);
            ripple = 0x224ADE80;
        } else {
            b.setTextColor(COLOR_TEXT);
            bg = rounded(COLOR_CARD_ALT, COLOR_LINE, 14);
            ripple = 0x14FFFFFF;
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), bg, null));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClick.run();
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px;
        return lp;
    }


    private GradientDrawable rounded(int color, int strokeColor, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void post(Runnable r) {
        ui.post(r);
    }

    private boolean canShowDialog() {
        return foreground && !stopped && !isFinishing() && !isDestroyed();
    }

    private String ver() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private int vcode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ================================================================ 状态

    private void refresh() {
        if (stopped) return;
        detectStore();
        render();
        refreshRoot();
        refreshStore();
        refreshEnv(false);
    }

    private void render() {
        if (tvStore == null) return;

        if (storePkg.isEmpty()) {
            paint(tvStore, "未确定", COLOR_WARN);
        } else {
            String v = storePkg;
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(storePkg, 0);
                v += " " + pi.versionName;
            } catch (Throwable ignored) {
            }
            paint(tvStore, v, COLOR_TEXT);
        }

        if (!storeSuspendedKnown) paint(tvStoreState, "未知", COLOR_SUB);
        else paint(tvStoreState, storeSuspended ? "已挂起" : "正常", storeSuspended ? COLOR_WARN : COLOR_ACCENT);

        if (themesState.startsWith("已锁定")) paint(tvThemes, themesState, COLOR_ACCENT);
        else if (themesState.startsWith("正常目录")) paint(tvThemes, themesState, COLOR_TEXT);
        else if (themesState.contains("读不了") || themesState.contains("无法")) paint(tvThemes, themesState, COLOR_WARN);
        else paint(tvThemes, themesState, COLOR_SUB);

        if (!rootChecked) paint(tvRoot, "检测中…", COLOR_SUB);
        else paint(tvRoot, rootReady ? "可用" : "不可用（su 被拒绝或隐藏）",
                rootReady ? COLOR_ACCENT : COLOR_WARN);

        String sh = ShizukuBridge.state();
        paint(tvShizuku, sh, sh.startsWith("已授权") ? COLOR_ACCENT : COLOR_WARN);
        paint(tvChannel, channelText, COLOR_SUB);
        boolean hasRoot = rootChecked && rootReady;
        if (btnInstall != null) btnInstall.setVisibility(View.VISIBLE);
        if (tvRiskNote != null) tvRiskNote.setVisibility(View.VISIBLE);
        if (btnFix != null) btnFix.setVisibility(hasRoot ? View.VISIBLE : View.GONE);
        boolean os17 = colorOs17();
        if (btnDark != null) { btnDark.setEnabled(os17); btnDark.setAlpha(os17 ? 1f : 0.4f); }
        if (btnOneKey != null) { btnOneKey.setEnabled(os17); btnOneKey.setAlpha(os17 ? 1f : 0.4f); }
        if (btnOneKey != null) btnOneKey.setVisibility(hasRoot ? View.VISIBLE : View.GONE);
        if (btnStoreImport != null) btnStoreImport.setVisibility(hasRoot ? View.GONE : View.VISIBLE);
        if (tvNoRootHint != null) tvNoRootHint.setVisibility(hasRoot ? View.GONE : View.VISIBLE);
        renderEnv();
    }

    private void paint(TextView v, String text, int color) {
        v.setText(text);
        v.setTextColor(color);
    }

    private void renderEnv() {
        if (tvEnv == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("开关：").append(darkOn() ? "已开启" : "已关闭");
        if (env == null) {
            sb.append('\n').append("环境：检测中…");
        } else {
            sb.append('\n').append("环境：").append(env.verdict);
            if (env.level == EnvCheck.LEVEL_USABLE && !env.samplePkg.isEmpty()) {
                sb.append('\n').append(env.samplePkg).append(" 夜间资源 ")
                        .append(env.sampleNight).append(" 项");
            }
        }
        if (!darkOn()) sb.append('\n').append("已关闭：不再注入主题资源与插画");
        if (!colorOs17()) sb.insert(0, "仅支持 ColorOS 17（当前 " + osVersion() + "），深色适配已停用\n");
        tvEnv.setText(sb.toString());
        if (btnDark != null) btnDark.setText(darkSwitchText());
    }

    private String brief(String text) {
        String[] lines = text.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < 3; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        if (sb.length() > 220) sb.setLength(220);
        if (lines.length > 3) sb.append("\n…（详见诊断信息）");
        return sb.toString();
    }

    private void setLog(String text) {
        prefs.edit().putString("last_log_full", text).apply();
        String briefText = brief(text);
        prefs.edit().putString("last_log", briefText).apply();
        if (tvLog != null) tvLog.setText(briefText);
    }

    private void refreshRoot() {
        if (rootChecked) return;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootShell.available();
                post(new Runnable() {
                    @Override
                    public void run() {
                        rootChecked = true;
                        rootReady = ok;
                        render();
                    }
                });
            }
        });
    }

    private void refreshStore() {
        if (querying.get()) return;
        final String pkg = storePkg;
        if (!ThemeEngine.allowed(pkg)) {
            themesState = "未确定主题商店";
            storeSuspendedKnown = false;
            render();
            return;
        }
        final boolean viaShizuku = ShizukuBridge.granted();
        querying.set(true);
        exec.execute(new Runnable() {
            @Override
            public void run() {
                ShellRunner.Result r = sh(ThemeEngine.inspect(pkg), 15000, viaShizuku);
                final String state = ThemeEngine.state(r.out);
                final String uid = ThemeEngine.uid(r.out);
                final boolean suspended = isSuspended(pkg, r.out);
                final boolean timeout = r.timedOut;
                post(new Runnable() {
                    @Override
                    public void run() {
                        querying.set(false);
                        if (stopped || !pkg.equals(storePkg)) return;
                        storeSuspended = suspended;
                        storeSuspendedKnown = !timeout;
                        themesState = describe(state, suspended, timeout);
                        channelText = !r.out.contains("UID:") ? "不可用"
                                : ("0".equals(uid)
                                        ? (viaShizuku ? "Sui / Shizuku（root）" : "su（root）")
                                        : "Shizuku / Sui（shell 2000）")
                                        + ("INACCESSIBLE".equals(state) ? " · 无目录权限" : "");
                        render();
                    }
                });
            }
        });
    }

    private void refreshEnv(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && env != null && now - envCheckedAt < 30000) return;
        envCheckedAt = now;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                boolean darkNow = (getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                final EnvCheck.Report report = EnvCheck.run(darkNow);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (stopped) return;
                        env = report;
                        renderEnv();
                    }
                });
            }
        });
    }

    private String describe(String state, boolean suspended, boolean timeout) {
        String suffix = suspended ? " · 商店已挂起" : "";
        if (timeout) return "查询超时";
        if ("EMPTY".equals(state)) return "已锁定（空文件）" + suffix;
        if ("DIRECTORY".equals(state)) return "正常目录" + suffix;
        if ("ABSENT".equals(state)) return "不存在（还没导入）" + suffix;
        if ("CONFLICT".equals(state)) return "同名文件冲突" + suffix;
        if ("INACCESSIBLE".equals(state)) return "目录读不了";
        return "未知（无特权通道）";
    }

    private boolean isSuspended(String pkg, String out) {
        try {
            return getPackageManager().isPackageSuspended(pkg);
        } catch (Throwable t) {
            for (String line : out.split("\r?\n")) {
                String s = line.trim();
                if (s.startsWith("SUSPENDED:")) return "true".equals(s.substring(10).trim());
            }
            return false;
        }
    }

    // ================================================================ 特权通道

    private ShellRunner.Result sh(String script, long timeout, boolean viaShizuku) {
        return viaShizuku ? ShizukuBridge.shell(script, timeout) : RootShell.shell(script, timeout);
    }

    private boolean channelReady() {
        return ShizukuBridge.granted() || rootReady;
    }

    private void requireChannel() {
        if (!canShowDialog()) {
            toast("需要 Sui / Shizuku 或 Root");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("需要特权通道")
                .setMessage("Sui / Shizuku 未授权，Root 也不可用。\n\n"
                        + "· pm 操作（解冻/冻结）：Sui 或 Shizuku 授权即可\n"
                        + "· 读写商店目录、写 /data/theme：需要 Shizuku（无线调试）或 Root")
                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        shizukuSetup();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 商店识别

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private List<String> installedCandidates() {
        List<String> list = new ArrayList<String>();
        for (String p : CANDIDATES) if (isInstalled(p)) list.add(p);
        return list;
    }

    private boolean looksLikeStore(String pkg) {
        try {
            if (getPackageManager().getLaunchIntentForPackage(pkg) != null) return true;
        } catch (Throwable ignored) {
        }
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(
                    pkg, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null) {
                for (android.content.pm.ActivityInfo ai : pi.activities) {
                    String n = ai.name;
                    if (n == null) continue;
                    if (n.contains("LocalProductListActivity") || n.contains("ThemeActivity")
                            || n.contains("ThemeDetailActivity")) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void detectStore() {
        List<String> installed = installedCandidates();
        String saved = prefs.getString("store_pkg", "");
        if (ThemeEngine.allowed(saved) && installed.contains(saved)) {
            storePkg = saved;
            return;
        }
        List<String> stores = new ArrayList<String>();
        for (String pkg : installed) if (looksLikeStore(pkg)) stores.add(pkg);
        if (stores.size() == 1) {
            storePkg = stores.get(0);
            prefs.edit().putString("store_pkg", storePkg).apply();
        } else if (installed.contains(storePkg)) {
            // 保持现有选择
        } else {
            storePkg = "";
        }
    }

    private void switchStore() {
        if (changing.get()) {
            toast("正在操作，请稍候");
            return;
        }
        final List<String> stores = new ArrayList<String>();
        String saved = prefs.getString("store_pkg", "");
        for (String pkg : installedCandidates()) {
            if (looksLikeStore(pkg) || pkg.equals(saved)) stores.add(pkg);
        }
        if (stores.isEmpty()) {
            toast("没有找到可确认的主题商店");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择主题商店")
                .setItems(stores.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int index) {
                        storePkg = stores.get(index);
                        prefs.edit().putString("store_pkg", storePkg).apply();
                        themesState = "检测中…";
                        storeSuspendedKnown = false;
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 主题常驻

    private void changeTheme(final boolean lock) {
        if (!channelReady()) {
            requireChannel();
            return;
        }
        final String pkg = storePkg;
        if (!ThemeEngine.allowed(pkg) || !isInstalled(pkg) || (lock && !looksLikeStore(pkg))) {
            toast("请先确定主题商店");
            return;
        }
        if (android.os.Process.myUid() / 100000 != 0) {
            toast("仅支持主用户");
            return;
        }
        if (themesState.contains("读不了")) {
            showNoDataAccess();
            return;
        }
        if (!changing.compareAndSet(false, true)) {
            toast("已有操作在执行");
            return;
        }
        toast(lock ? "正在锁定…" : "正在解锁…");
        final boolean viaShizuku = ShizukuBridge.granted();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                ShellRunner.Result result;
                try {
                    boolean allowRoot = uidOf(viaShizuku) == 0;
                    String script = lock ? ThemeEngine.lock(pkg, allowRoot)
                            : ThemeEngine.unlock(pkg, allowRoot);
                    result = sh(script, 45000, viaShizuku);
                    if (result.ok() && !ThemeEngine.operationSucceeded(result.out, lock)) {
                        result = new ShellRunner.Result(-2, false, result.out,
                                result.err + "\n缺少最终校验标记");
                    }
                } catch (Throwable t) {
                    result = new ShellRunner.Result(-1, false, "", String.valueOf(t));
                } finally {
                    changing.set(false);
                }
                final ShellRunner.Result fin = result;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (fin.ok()) {
                            String msg = fin.out.contains("RESULT:ALREADY_UNLOCKED")
                                    ? "当前已解锁"
                                    : fin.out.contains("RESULT:ALREADY_LOCKED")
                                    ? "主题已锁定（原本就是锁定状态）"
                                    : (lock ? "主题已锁定" : "已解锁");
                            toast(msg);
                            setLog("最近操作：" + msg);
                        } else {
                            finishWithError(lock ? "锁定未完成" : "解锁未完成", fin, lock);
                        }
                        refresh();
                    }
                });
            }
        });
    }

    private void finishWithError(String title, ShellRunner.Result r, boolean lock) {
        String hint = hintFor(r, lock);
        setLog("最近操作：" + title + "\n" + hint);
        if (!canShowDialog()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(hint + "\n\n" + r.text())
                .setPositiveButton("知道了", null)
                .show();
    }

    private String hintFor(ShellRunner.Result r, boolean lock) {
        String all = r.out + "\n" + r.err;
        if (r.timedOut) return "操作超时，结果未知；建议重启 Shizuku 后刷新状态确认。";
        if (all.contains("SHELL_IDENTITY_REQUIRED")) return "需要 shell（2000）或 root（0）身份。";
        if (all.contains("OWNER_PROFILE_REQUIRED")) return "仅支持主用户，分身/访客无法操作。";
        if (all.contains("DIRECTORY_ACCESS") || all.contains("Permission denied")) {
            return "读不到商店目录：需要能用 Android/data 的身份（无线调试/ADB 启动的 Shizuku，或 Root）。";
        }
        if (all.contains("PATH_MISMATCH") || all.contains("SYMLINK") || all.contains("CONFLICT")) {
            return "目录状态异常，已停止操作以保留数据。";
        }
        if (all.contains("SUSPEN")) return "挂起状态未确认，核心结果看下面输出。";
        if (all.contains("PLACEHOLDER_CREATE")) return "占位文件创建失败：商店目录不可写，或已存在同名文件。";
        if (all.contains("EXISTING_FILE")) return "Themes 已是非空文件，已停止以保留原数据。";
        if (all.contains("BACKUP_FAILED") || all.contains("RESTORE_FAILED")) {
            return "移动 / 还原 Themes 目录失败，未做替换。";
        }
        return lock ? "锁定没有完成。" : "解锁没有完成。";
    }

    private void showNoDataAccess() {
        if (!canShowDialog()) {
            toast("当前身份读不到 Android/data");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("当前身份读不到 Android/data")
                .setMessage("Sui 提供的 shell 只能执行 pm 等 Binder 操作，读不到 "
                        + "/storage/emulated/0/Android/data，所以空文件替换式锁定/解锁用不了。\n\n"
                        + "· 解冻 / 冻结：现在就能用\n"
                        + "· 锁定 / 解锁：需要无线调试或 ADB 启动的 Shizuku，或 Root\n\n"
                        + "需要别的办法可以让我把文件操作搬进商店进程里做。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void storeAction(final boolean unsuspend) {
        if (!channelReady()) {
            requireChannel();
            return;
        }
        final String pkg = storePkg;
        if (!ThemeEngine.allowed(pkg) || !isInstalled(pkg)) {
            toast("请先确定主题商店");
            return;
        }
        final boolean viaShizuku = ShizukuBridge.granted();
        if (!changing.compareAndSet(false, true)) {
            toast("已有操作在执行");
            return;
        }
        toast(unsuspend ? "正在解冻商店…" : "正在冻结商店…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final ShellRunner.Result r = sh(
                        unsuspend ? ThemeEngine.unsuspend(pkg) : ThemeEngine.suspend(pkg),
                        30000, viaShizuku);
                final boolean ok = r.ok() && (r.out.contains("RESULT:UNSUSPENDED")
                        || r.out.contains("RESULT:SUSPENDED"));
                changing.set(false);
                post(new Runnable() {
                    @Override
                    public void run() {
                        String what = unsuspend ? "解冻商店" : "冻结商店";
                        if (ok) {
                            toast(unsuspend ? "商店已解冻" : "商店已冻结");
                            setLog("最近操作：" + what + " 完成");
                        } else {
                            finishWithError(what + " 未完成", r, !unsuspend);
                        }
                        refresh();
                    }
                });
            }
        });
    }

    // ================================================================ 安装 / 生效

    private void openFileManager() {
        for (String pkg : new String[]{"com.coloros.filemanager", "com.oplus.filemanager"}) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    startActivity(i);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        toast("没有找到系统文件管理");
    }

    private void pickTheme() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            toast("打开文件选择器失败：" + t);
        }
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? uri.toString() : last;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_PICK) return;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        String name = queryName(uri);
        prefs.edit().putString("theme_uri", uri.toString())
                .putString("theme_name", name).apply();
        handoffTheme(uri);
    }

    private void installTheme() {
        if (prefs.getString("theme_uri", "").isEmpty()) {
            toast("还没选择主题文件");
            return;
        }
        if (!canShowDialog()) {
            pendingInstallRisk = true;
            toast("已选好 .theme；回到界面后确认补齐");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("补齐主题资源")
                .setMessage("只会把 .theme 里的 com.* 主题包写到本应用数据目录（files/theme-packs），"
                        + "由模块在联系人 / 短信进程里读取；不会修改 /data/theme。\n\n"
                        + "写入后会自动重启联系人 / 短信（有 Root 时）；没有 Root 请手动重启这两个应用。")
                .setPositiveButton("开始补齐", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doInstallTheme();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选中的 .theme 交给主题商店：只复制到 Download/MCTGA，再打开商店详情页，不写 /data/theme。 */
    private void handoffTheme(final Uri uri) {
        final String name = queryName(uri);
        toast("正在交给主题商店…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String dest = null;
                String err = null;
                try {
                    java.io.File cache = new java.io.File(getCacheDir(), "handoff.theme");
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("无法读取所选文件");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(cache);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    String safe = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
                    if (safe.isEmpty()) safe = "theme.theme";
                    if (!safe.toLowerCase().endsWith(".theme")) safe = safe + ".theme";
                    String dir = android.os.Environment
                            .getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS)
                            .getAbsolutePath() + "/MCTGA";
                    dest = writeToDownloads(cache, safe, dir);
                    if (rootReady && dest != null) {
                        // 有 root 时补一次 chmod，保证商店按路径一定能读到
                        RootShell.shell("chmod 0644 '" + dest + "'", 10000);
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                final String fDest = dest;
                final String fErr = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (fErr != null || fDest == null) {
                            toast("保存失败：" + fErr);
                            return;
                        }
                        openThemePreview(fDest);
                    }
                });
            }
        });
    }

    /** 用 MediaStore 写 下载/MCTGA（API 29+ 不需要存储权限）；返回真实文件路径。 */
    private String writeToDownloads(java.io.File src, String name, String dir) throws Exception {
        android.content.ContentResolver cr = getContentResolver();
        android.net.Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        long size = src.length();

        // 同名同大小就复用，避免重复写上百 MB
        android.database.Cursor c = cr.query(collection,
                new String[]{android.provider.MediaStore.Downloads._ID},
                android.provider.MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                        + android.provider.MediaStore.Downloads.SIZE + "=? AND "
                        + android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                new String[]{name, String.valueOf(size), "Download/MCTGA%"},
                android.provider.MediaStore.Downloads._ID + " DESC");
        if (c != null) {
            try {
                if (c.moveToFirst()) return dir + "/" + name;
            } finally {
                c.close();
            }
        }

        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/MCTGA/");
        v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        v.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
        android.net.Uri item = cr.insert(collection, v);
        if (item == null) throw new java.io.IOException("系统拒绝写入下载目录");
        try {
            java.io.OutputStream os = cr.openOutputStream(item);
            if (os == null) throw new java.io.IOException("打不开目标文件");
            java.io.FileInputStream fis = new java.io.FileInputStream(src);
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) > 0) os.write(buf, 0, n);
            fis.close();
            os.close();
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
            cr.update(item, done, null, null);
        } catch (Exception e) {
            cr.delete(item, null, null);
            throw e;
        }
        String real = name;
        android.database.Cursor c2 = cr.query(item,
                new String[]{android.provider.MediaStore.Downloads.DISPLAY_NAME},
                null, null, null);
        if (c2 != null) {
            try {
                if (c2.moveToFirst()) real = c2.getString(0);
            } finally {
                c2.close();
            }
        }
        return dir + "/" + real;
    }

    /** 调商店自己的主题详情页；文件管理点 .theme 用的就是这套 action + extra。 */
    private void openThemePreview(String path) {
        String pkg = (storePkg == null || storePkg.isEmpty()) ? "com.heytap.themestore" : storePkg;
        String[] actions = {
                "com.oplus.themestore.action.PREVIEW_THEME",
                "oppo.intent.action.OPPO_PREVIEW_THEME",
                "android.intent.action.OPPO_PREVIEW_THEME"
        };
        for (String action : actions) {
            try {
                Intent i = new Intent(action);
                i.setPackage(pkg);
                i.putExtra("oplus_preview_theme_path", path);
                i.putExtra("oppo_preview_theme_path", path);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                toast("已打开主题详情，加载几秒后点「免费试用」");
                return;
            } catch (Throwable ignored) {
            }
        }
        toast("没能打开主题商店，文件已放在 下载/MCTGA：" + path);
    }

    private void doInstallTheme() {
        final String uriStr = prefs.getString("theme_uri", "");
        final String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        setLog("最近操作：正在校验并补齐 .theme…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String message;
                String detail;
                try {
                    File cache = new File(getCacheDir(), "import.theme");
                    InputStream in = getContentResolver().openInputStream(Uri.parse(uriStr));
                    if (in == null) throw new java.io.IOException("无法读取所选文件");
                    FileOutputStream out = new FileOutputStream(cache);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();

                    String cfgText = "";
                    try { cfgText = RootShell.shell("cat /data/theme/config 2>/dev/null", 10000).out; } catch (Throwable ignored) { }
                    int packs = buildPacks(cache, cfgText);
                    if (rootReady) {
                        RootShell.shell("am force-stop com.android.contacts; am force-stop com.android.mms", 15000);
                    }
                    if (packs == 0) {
                        message = "没解出可用的主题包，未写入";
                        detail = "";
                    } else {
                        message = "已写入应用数据目录 " + packs + " 个包";
                        detail = "未修改 /data/theme；" + (rootReady ? "已重启联系人 / 短信" : "请手动重启联系人 / 短信");
                    }
                } catch (Throwable t) {
                    prefs.edit().putString("last_error", String.valueOf(t)).apply();
                    message = "安装失败：" + t;
                    detail = "";
                }
                final String msg = message;
                final String det = detail;
                post(new Runnable() {
                    @Override
                    public void run() {
                        setLog("最近操作：" + msg + (det.isEmpty() ? "" : "\n" + det));
                        toast(msg);
                        refresh();
                        refreshEnv(true);
                    }
                });
            }
        });
    }


    /** 从「V17.0.0」这种 ROM 版本得出 17000 形式的 OS 代号。 */
    private int osCode() {
        try {
            String s = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, "ro.build.version.oplusrom");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("V(\\d+)\\.").matcher(s == null ? "" : s);
            if (m.find()) return Integer.parseInt(m.group(1)) * 1000;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 从商店写的 config 里取本机 OS 的变体：正式包名 -> 变体成员名。 */
    private java.util.Map<String, String> variantMapFromConfig(String cfg,
            java.util.zip.ZipFile zf, int os) {
        java.util.Map<String, String> map = new java.util.HashMap<String, String>();
        if (cfg == null || cfg.isEmpty() || os == 0) return map;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"filePath\"\\s*:\\s*\"(com\\.[A-Za-z0-9._]+?)_(\\d{3})\""
                        + "[\\s\\S]{0,400}?\"themeOsVersion\"\\s*:\\s*\"?(\\d+)").matcher(cfg);
        while (m.find()) {
            if (Integer.parseInt(m.group(3)) != os) continue;
            String base = m.group(1);
            String variant = base + "_" + m.group(2);
            if (zf.getEntry(variant) != null) map.put(base, variant);
        }
        return map;
    }

    /** 兜底：themeInfo 里 version="001,004"，OS17 以上取最高后缀。 */
    private void variantsFromInfo(java.util.zip.ZipFile zf, int os,
            java.util.Map<String, String> map) {
        if (os < 17000) return;
        try {
            java.util.zip.ZipEntry e = zf.getEntry("themeInfo.xml");
            if (e == null) return;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.InputStream is = zf.getInputStream(e);
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            is.close();
            String xml = bo.toString("UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<package name=\"(com\\.[A-Za-z0-9._]+)\" version=\"([0-9,]+)\"")
                    .matcher(xml);
            while (m.find()) {
                String base = m.group(1);
                int best = -1;
                for (String v : m.group(2).split(",")) {
                    try {
                        int x = Integer.parseInt(v.trim());
                        if (x > best) best = x;
                    } catch (Throwable ignored) {
                    }
                }
                if (best <= 1) continue;
                String variant = base + "_" + String.format(java.util.Locale.US, "%03d", best);
                if (zf.getEntry(variant) != null) map.put(base, variant);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 合并两种来源：优先商店 config，其次 themeInfo。 */
    private java.util.Map<String, String> variantMap(File theme, String cfgText) {
        java.util.zip.ZipFile zf = null;
        java.util.Map<String, String> map = new java.util.HashMap<String, String>();
        try {
            zf = new java.util.zip.ZipFile(theme);
            int os = osCode();
            map = variantMapFromConfig(cfgText, zf, os);
            if (map.isEmpty()) variantsFromInfo(zf, os, map);
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) { }
        }
        return map;
    }

    /** 生成只含 com.* 的规范化副本：本机变体改回正式包名，其它变体丢弃。 */

    /** 把 .theme 里的各包（本机变体 + 匹配的夜间图）写进本应用 files/theme-packs，不动 /data/theme。 */
    private int buildPacks(File theme, String cfgText) throws Exception {
        java.util.Map<String, String> map = variantMap(theme, cfgText);
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(theme);
        java.io.File dir = new java.io.File(getFilesDir(), "theme-packs");
        dir.mkdirs();
        int count = 0;
        byte[] buf = new byte[65536];
        try {
            java.util.Set<String> bases = new java.util.LinkedHashSet<String>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> es = zf.entries();
            while (es.hasMoreElements()) {
                String n = es.nextElement().getName();
                if (!n.startsWith("com.") || n.contains("/")) continue;
                bases.add(n.matches("com\\..+_\\d{3}") ? n.substring(0, n.length() - 4) : n);
            }
            for (String base : bases) {
                String chosen = map.containsKey(base) ? map.get(base) : base;
                java.util.zip.ZipEntry ce = zf.getEntry(chosen);
                if (ce == null) continue;
                java.io.File tmp = new java.io.File(dir, base + ".tmp");
                java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(
                        new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)));
                java.util.Set<String> names = new java.util.HashSet<String>();
                boolean hasNight = false;
                try {
                    java.util.zip.ZipInputStream zis =
                            new java.util.zip.ZipInputStream(zf.getInputStream(ce));
                    java.util.zip.ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.isDirectory()) continue;
                        String nm = e.getName();
                        if (nm.contains("-night")) hasNight = true;
                        names.add(nm);
                        zo.putNextEntry(new java.util.zip.ZipEntry(nm));
                        int n;
                        while ((n = zis.read(buf)) > 0) zo.write(buf, 0, n);
                        zo.closeEntry();
                    }
                    zis.close();
                    if (!hasNight && !base.equals(chosen) && zf.getEntry(base) != null) {
                        java.util.zip.ZipInputStream zis2 =
                                new java.util.zip.ZipInputStream(zf.getInputStream(zf.getEntry(base)));
                        java.util.zip.ZipEntry e2;
                        while ((e2 = zis2.getNextEntry()) != null) {
                            String nm = e2.getName();
                            if (!nm.contains("-night")) continue;
                            if (names.contains(nm) || !names.contains(nm.replace("-night", ""))) continue;
                            zo.putNextEntry(new java.util.zip.ZipEntry(nm));
                            int n;
                            while ((n = zis2.read(buf)) > 0) zo.write(buf, 0, n);
                            zo.closeEntry();
                            names.add(nm);
                        }
                        zis2.close();
                    }
                } finally {
                    zo.close();
                }
                java.io.File out = new java.io.File(dir, base);
                boolean ok = false;
                try {
                    java.util.zip.ZipFile vz = new java.util.zip.ZipFile(tmp);
                    ok = vz.size() > 0;
                    vz.close();
                } catch (Throwable ignored) {
                }
                if (!ok) {
                    tmp.delete();
                    continue;
                }
                if (out.exists()) out.delete();
                if (tmp.renameTo(out)) count++;
            }
        } finally {
            zf.close();
        }
        return count;
    }


    private void oneKey() {
        if (!rootReady) {
            showDarkChannelHint("一键生效要重启其它应用和 SystemUI");
            return;
        }
        toast("正在检查主题…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                ShellRunner.Result probe = RootShell.shell(
                        "test -f /data/theme/com.android.settings && echo THEME_YES || echo THEME_NO", 8000);
                if (!probe.out.contains("THEME_YES")) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            setLog("最近操作：主题还没应用，先去主题商店应用一次");
                            toast("请先在主题商店应用主题");
                        }
                    });
                    return;
                }
                ShellRunner.Result stop = RootShell.shell(forceStopScript(), 25000);
                final boolean ok = !stop.timedOut;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            setLog("最近操作：一键生效完成，界面已重启");
                            toast("已重启适配应用与 SystemUI");
                        } else {
                            setLog("最近操作：重启命令超时，结果未确认");
                            toast("重启命令超时");
                        }
                        refresh();
                    }
                });
            }
        });
    }

    private String forceStopScript() {
        StringBuilder sb = new StringBuilder();
        for (String pkg : ADAPTED_APPS) sb.append("am force-stop ").append(pkg).append("; ");
        sb.append("killall com.android.systemui");
        return sb.toString();
    }

    /** 统一的提示弹窗；不在前台就退化成 toast。 */
    private void showHint(String title, String message, String shortToast) {
        if (!canShowDialog()) {
            toast(shortToast);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showNeedRoot(String why) {
        showHint("这个操作需要 Root",
                why + "。\n\n当前 Root 不可用：su 没有返回 0（未授权或被隐藏）。\n\n"
                        + "想恢复可以：在 KernelSU / Magisk / Sui 里给 MCTGA 授权。",
                "需要 Root");
    }

    /** 深色适配靠 LSPosed 生效；只有开关写系统设置 / 重启应用才需要特权。 */
    private void showDarkChannelHint(String why) {
        showHint("深色适配不需要在这里开 Root",
                "深色适配是 LSPosed 模块，在目标应用进程里生效，和 Root 无关。\n\n"
                        + "只有「" + why + "」这一步要 Root 或 Shizuku。\n\n"
                        + "没有特权也能用：LSPosed 里勾好作用域后，手动结束目标应用或重启手机即可生效。",
                "深色适配由 LSPosed 生效；" + why + " 需要 Root 或 Shizuku");
    }

    // ================================================================ 深色适配开关

    private boolean darkOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), "mctga_dark_attach", 1) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    private String darkSwitchText() {
        return darkOn() ? "深色适配：已开启（点击关闭）" : "深色适配：已关闭（点击开启）";
    }

    private void toggleDark() {
        if (!channelReady()) {
            showDarkChannelHint("切换开关要写 Settings.Global");
            return;
        }
        final boolean next = !darkOn();
        final boolean viaShizuku = ShizukuBridge.granted();
        toast(next ? "正在开启深色适配…" : "正在关闭深色适配…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final ShellRunner.Result r = sh("settings put global mctga_dark_attach "
                        + (next ? "1" : "0"), 12000, viaShizuku);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (r.ok()) {
                            toast(next ? "已开启，重启目标应用后生效"
                                    : "已关闭，重启目标应用后回到系统配色");
                        } else {
                            toast("写入失败：" + r.err.trim());
                        }
                        renderEnv();
                    }
                });
            }
        });
    }

    // ================================================================ Shizuku

    private void shizukuSetup() {
        if (ShizukuBridge.granted()) {
            toast("Shizuku 已授权");
            return;
        }
        try {
            if (!ShizukuBridge.connected()) {
                requestBinderBroadcast(false);
                showShizukuGuide();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
            toast("已发送授权请求");
        } catch (Throwable t) {
            requestBinderBroadcast(false);
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!ShizukuBridge.granted()) showShizukuGuide();
                }
            }, 1500);
        }
    }

    private void requestBinderBroadcast(boolean silent) {
        try {
            shizukuCallback = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    if (code == 1) {
                        IBinder binder = data.readStrongBinder();
                        data.readString();
                        if (binder != null) {
                            rikka.shizuku.Shizuku.onBinderReceived(binder, getPackageName());
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    refresh();
                                }
                            });
                        }
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };
            Bundle bundle = new Bundle();
            bundle.putBinder("binder", shizukuCallback);
            Intent intent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER");
            intent.setPackage("moe.shizuku.privileged.api");
            intent.putExtra("data", bundle);
            sendBroadcast(intent);
            if (!silent) toast("已发送广播请求 binder");
        } catch (Throwable t) {
            if (!silent) toast("广播请求失败：" + t);
        }
    }

    private void showShizukuGuide() {
        if (!canShowDialog()) return;
        new AlertDialog.Builder(this)
                .setTitle("需要 Shizuku 授权")
                .setMessage("Sui 提供的是 shell 身份，pm 操作够用；"
                        + "要读写 Android/data 需要无线调试/ADB 启动的 Shizuku。\n\n"
                        + "1 启动 Shizuku（Android 11+ 用无线调试）\n"
                        + "2 在 Shizuku 里允许 MCTGA\n"
                        + "3 回到这里刷新状态")
                .setPositiveButton("打开 Shizuku", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            Intent i = getPackageManager().getLaunchIntentForPackage(
                                    "moe.shizuku.privileged.api");
                            if (i != null) startActivity(i);
                            else toast("没有找到 Shizuku 应用");
                        } catch (Throwable t) {
                            toast("打开失败：" + t);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                          int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_SHIZUKU) {
            toast(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    ? "Shizuku 授权成功" : "Shizuku 授权被拒绝");
            refresh();
        }
    }

    // ================================================================ 诊断 / 关于

    private static final String DIAG_SCRIPT =
            "echo UID:$(id -u)\n"
                    + "id -Z 2>&1\n"
                    + "echo --- 路径 ---\n"
                    + "ls -ld /storage/emulated/0/Android/data 2>&1\n"
                    + "ls /storage/emulated/0/Android/data 2>&1 | head -5\n"
                    + "ls -ld /storage/emulated/0/Android/data/com.heytap.themestore/files 2>&1\n"
                    + "ls -ld /storage/emulated/0/Android/data/com.heytap.themestore/files/Themes 2>&1\n"
                    + "echo --- root ---\n"
                    + "command -v su 2>&1\n"
                    + "/system/bin/su -c 'echo SU_OK' 2>&1\n"
                    + "echo DONE\n";


    private void showDiagnostics() {
        final boolean viaShizuku = ShizukuBridge.granted();
        toast("正在收集诊断…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final ShellRunner.Result r = sh(DIAG_SCRIPT, 30000, viaShizuku);
                StringBuilder sb = new StringBuilder();
                sb.append("MCTGA ").append(ver()).append("（").append(vcode()).append("）\n");
                sb.append("系统：").append(osVersion()).append('\n');
                sb.append("Root：").append(rootReady ? "可用" : "不可用").append('\n');
                sb.append("Shizuku：").append(ShizukuBridge.state()).append('\n');
                sb.append("通道：").append(channelText)
                        .append('（').append(viaShizuku ? "Sui / Shizuku" : "Root").append("）\n");
                sb.append("商店：").append(storePkg.isEmpty() ? "未确定" : storePkg).append('\n');
                sb.append("商店状态：").append(storeSuspendedKnown
                        ? (storeSuspended ? "已挂起" : "正常") : "未知").append('\n');
                sb.append("Themes：").append(themesState).append('\n');
                sb.append("主题文件：").append(prefs.getString("theme_name", "无")).append('\n');
                String le = prefs.getString("last_error", "");
                if (!le.isEmpty()) sb.append("最近错误：").append(le).append('\n');
                if (env != null) sb.append("深色环境：").append(env.verdict).append('\n');
                sb.append("\n--- 通道 ---\n").append(r.out).append(r.err);
                sb.append("\n--- 最近操作 ---\n").append(prefs.getString("last_log", "无"));
                final String text = sb.toString();
                post(new Runnable() {
                    @Override
                    public void run() {
                        showShareable("诊断信息", text);
                        setLog("最近操作：诊断已生成");
                    }
                });
            }
        });
    }

    /** 可滚动、可复制 / 分享的文本弹窗。 */
    private void showShareable(final String title, final String text) {
        if (!canShowDialog()) {
            copyText(text);
            toast("已复制到剪贴板");
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10.5f);
        tv.setTextColor(COLOR_TEXT);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyText(text);
                        toast("已复制到剪贴板");
                    }
                })
                .setNeutralButton("分享", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        shareText(title, text);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyText(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("MCTGA", text));
        } catch (Throwable ignored) {
        }
    }

    private void shareText(String title, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "分享诊断"));
        } catch (Throwable t) {
            toast("分享失败：" + t);
        }
    }

    /** 深色适配目前只支持 ColorOS 17。 */
    private boolean colorOs17() {
        String rom = readProp("ro.build.version.oplusrom");
        return rom != null && rom.startsWith("V17");
    }

    private String readProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : (String) v;
        } catch (Throwable t) {
            return "";
        }
    }

    private String osVersion() {
        String s = "Android " + Build.VERSION.RELEASE;
        String rom = readProp("ro.build.version.oplusrom");
        if (rom == null || rom.isEmpty()) rom = readProp("ro.build.version.opporom");
        if (rom != null && !rom.isEmpty()) s += " / ColorOS " + rom;
        return s;
    }

    private void showAbout() {
        final Dialog dlg = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(20), dp(20), dp(14));
        box.setBackground(rounded(COLOR_CARD, COLOR_LINE, 20));

        TextView t = new TextView(this);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(12.5f);
        t.setLineSpacing(dp(3), 1f);
        t.setText("MCTGA\nMake ColorOS Themes Great Again\n\n"
                + "版本 " + ver() + "（versionCode " + vcode() + "）\n"
                + "包名 xwdy.deepseek.test\n\n"
                + "两件事合在一个应用里：\n"
                + "· 深色模式主题适配（LSPosed，20 个作用域包）\n"
                + "· 主题常驻防护（锁定商店，挡住试用到期恢复）\n\n"
                + "身份：Sui/Shizuku 做 pm 操作；读写 Android/data 需要 Shizuku（无线调试）"
                + "或 Root；Sui 会接管 su，所以 Root 功能可能不可用。\n\n"
                + "项目主页\nhttps://github.com/xwdy114514/fuck-COS-dark-mode-themes\n\n"
                + "项目守则\n1 不要向主题作者催促适配深色主题，否则停更删库\n2 严禁倒卖\n\n"
                + "本应用不含任何 .theme 主题包、系统 APK 或反编译代码。");
        box.addView(t);

        TextView close = button("关闭", BTN_GHOST, new Runnable() {
            @Override
            public void run() {
                dlg.dismiss();
            }
        });
        box.addView(close);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg.setContentView(sv);
        dlg.show();
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private int uidOf(boolean viaShizuku) {
        ShellRunner.Result r = sh("id -u", 8000, viaShizuku);
        try {
            return Integer.parseInt(r.out.trim().split("\n")[0].trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 回滚


    private String restoreScript() {
        return "set -e\n"
                + "ROOT=/data/local/tmp/mctga_theme_backup\n"
                + "[ -d \"$ROOT\" ] || { echo FAIL:NO_BACKUP; exit 5; }\n"
                + "BAK=$(ls -1d \"$ROOT\"/* 2>/dev/null | tail -n 1)\n"
                + "[ -n \"$BAK\" ] || { echo FAIL:EMPTY_BACKUP; exit 5; }\n"
                + "echo USE:$BAK\n"
                + "IMM=0\n"
                + "if lsattr -d /data/theme 2>/dev/null | grep -q ' i'; then\n"
                + "  IMM=1; chattr -R -i /data/theme 2>/dev/null || true\n"
                + "fi\n"
                + "n=0\n"
                + "for f in \"$BAK\"/*; do\n"
                + "  [ -f \"$f\" ] || continue\n"
                + "  b=${f##*/}\n"
                + "  cp -f \"$f\" \"/data/theme/.$b.restore.tmp\"\n"
                + "  chown 10303:10303 \"/data/theme/.$b.restore.tmp\"\n"
                + "  chmod 0666 \"/data/theme/.$b.restore.tmp\"\n"
                + "  mv -f \"/data/theme/.$b.restore.tmp\" \"/data/theme/$b\"\n"
                + "  n=$((n+1))\n"
                + "done\n"
                + "echo RESTORED:$n\n"
                + "[ \"$IMM\" = 1 ] && chattr -R +i /data/theme 2>/dev/null || true\n"
                + "echo DONE\n";
    }
}
