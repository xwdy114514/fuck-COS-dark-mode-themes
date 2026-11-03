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
    private TextView tvSystem;
    private TextView tvStoreState;
    private TextView tvThemes;
    private TextView tvRoot;
    private TextView tvShizuku;
    private TextView tvChannel;
    private TextView tvEnv;
    private LinearLayout envCard;
    private TextView tvDarkBlocked;
    private TextView tvLog;
    private TextView btnDark;
    private TextView btnInstall;
    private TextView tvRiskNote;
    private TextView btnFix;
    private TextView btnStoreImport;
    private TextView btnOneKey;
    private TextView tvNoRootHint;
    private TextView tvGuide;
    private TextView btnGuide;
    private TextView btnGuideAlt;
    private LinearLayout guideList;
    private java.util.List<String> scannedThemes = new java.util.ArrayList<String>();
    private java.util.Map<String, Long> scannedSizes = new java.util.HashMap<String, Long>();
    private String lastEntryName;
    private long lastScanAt;
    private boolean scanning;
    /** theme_uri 指向的文件是否还在（商店导入完常把源文件删掉）。 */
    private boolean themeUriOk;
    private boolean themeApplied;
    /** /data/theme 目录的修改时间（秒），用来判断「选了文件之后主题真的被应用了」。 */
    private long themeMtime;
    private boolean hookReady;
    private boolean hookChecked;
    private boolean hookUnknown;
    private int packsCount = -1;
    private LinearLayout advanced;

    private String storePkg = "";
    private String themesState = "检测中…";
    private boolean storeSuspended;
    private boolean storeSuspendedKnown;
    private boolean unlockPrompted;
    private boolean rootReady;
    private boolean rootChecked;
    private String channelText = "—";
    private EnvCheck.Report env;
    private long envCheckedAt;
    private boolean foreground;
    private boolean blocked;
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
        OsVersion.check();
        prefs = getSharedPreferences(PREFS, 0);
        if (!osAllowed()) {
            showBlockedScreen();
            return;
        }
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
        // 先把「文件还在不在」按旧记录乐观处理，refreshGuide 拿到真实结果后再纠正，避免首帧闪一下第 1 步
        themeUriOk = !prefs.getString("theme_uri", "").isEmpty();
        cleanupCacheCopies();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (blocked) return;
        foreground = true;
        refresh();
        refreshGuide();
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

    /** 非 ColorOS / 版本过低时的整屏拦截，除了退出什么都做不了。 */
    private void showBlockedScreen() {
        blocked = true;
        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COLOR_BG_TOP, COLOR_BG_BOTTOM}));
        sv.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(40), dp(28), dp(40));

        TextView title = new TextView(this);
        title.setText("MCTGA 无法在此设备运行");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView reason = new TextView(this);
        reason.setText(OsVersion.unsupportedHint());
        reason.setTextSize(14);
        reason.setTextColor(COLOR_BAD);
        reason.setGravity(Gravity.CENTER);
        reason.setPadding(0, dp(14), 0, 0);
        box.addView(reason);

        TextView detail = new TextView(this);
        detail.setText("设备信息\n"
                + "厂商 / 机型：" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "系统：" + OsVersion.label() + "\n"
                + "Android：" + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）\n\n"
                + "本软件只针对 ColorOS 11+ / realme UI 2.0+ 的主题引擎实现，其它系统上所有功能都不会工作，"
                + "所以直接拒绝启动。换到 ColorOS 11+ 或 realme UI 2.0+ 设备再打开即可。");
        detail.setTextSize(12);
        detail.setTextColor(COLOR_SUB);
        detail.setLineSpacing(dp(4), 1f);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(20), 0, 0);
        box.addView(detail);

        TextView exit = button("退出", BTN_GHOST, new Runnable() {
            @Override
            public void run() {
                finishAndRemoveTask();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(28);
        exit.setLayoutParams(lp);
        box.addView(exit);

        sv.addView(box);
        setContentView(sv);
        sv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                        insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
    }

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
        tvSystem = new TextView(this);
        tvSystem.setTextSize(11.5f);
        tvSystem.setLineSpacing(dp(3), 1f);
        tvSystem.setPadding(0, dp(8), 0, 0);
        root.addView(tvSystem);

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
        LinearLayout guide = card();
        LinearLayout.LayoutParams guideLp = marginTop(dp(6));
        guide.setLayoutParams(guideLp);
        guide.setBackground(rounded(COLOR_CARD_ALT, COLOR_LINE, 16));
        tvGuide = new TextView(this);
        tvGuide.setTextSize(12.5f);
        tvGuide.setTextColor(COLOR_TEXT);
        tvGuide.setLineSpacing(dp(4), 1f);
        tvGuide.setText("引导加载中…");
        guide.addView(tvGuide);
        btnGuide = button("…", BTN_PRIMARY, new Runnable() {
            @Override
            public void run() {
                guideAction(guideStep());
            }
        });
        LinearLayout.LayoutParams gbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        gbLp.topMargin = dp(10);
        btnGuide.setLayoutParams(gbLp);
        guide.addView(btnGuide);

        // 引导卡里的备用按钮：第 1 步「自己在文件管理里点 .theme」，第 2 步「退回上一步」。
        btnGuideAlt = button("…", BTN_GHOST, new Runnable() {
            @Override
            public void run() {
                if (guideStep() == 1) {
                    openFileManager();
                    toast("在文件管理里点 .theme → 免费试用");
                } else {
                    backToPick();
                }
            }
        });
        LinearLayout.LayoutParams gaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        gaLp.topMargin = dp(8);
        btnGuideAlt.setLayoutParams(gaLp);
        btnGuideAlt.setVisibility(View.GONE);
        guide.addView(btnGuideAlt);

        // 第 1 步会在这里列出扫描到的 ColorOS 主题，点一下直接试用
        guideList = new LinearLayout(this);
        guideList.setOrientation(LinearLayout.VERTICAL);
        guideList.setVisibility(View.GONE);
        guide.addView(guideList);
        root.addView(guide);

        final LinearLayout manual = new LinearLayout(this);
        manual.setOrientation(LinearLayout.VERTICAL);
        manual.setVisibility(View.GONE);
        root.addView(manual);
        final TextView manualToggle = button("手动操作（进阶）", BTN_GHOST, new Runnable() {
            @Override
            public void run() {
                manual.setVisibility(manual.getVisibility() == View.GONE
                        ? View.VISIBLE : View.GONE);
            }
        });
        addBtn(root, manualToggle);



        btnInstall = button("选择 .theme 交给主题商店", BTN_PRIMARY, new Runnable() {
            @Override
            public void run() {
                pickTheme();
            }
        });
        addBtn(manual, btnInstall);

        tvRiskNote = new TextView(this);
        tvRiskNote.setTextSize(11);
        tvRiskNote.setTextColor(COLOR_FAINT);
        tvRiskNote.setLineSpacing(dp(3), 1f);
        tvRiskNote.setPadding(dp(2), dp(8), dp(2), 0);
        tvRiskNote.setText("不会改 /data/theme：写入 下载/MCTGA（媒体库，无需 Root），"
                + "再打开主题商店详情页，由商店自己应用。");
        manual.addView(tvRiskNote);

        btnStoreImport = button("打开文件管理导入 .theme", BTN_SECONDARY, new Runnable() {
            @Override
            public void run() {
                openFileManager();
                toast("在文件管理里点 .theme → 免费试用，用它代替补齐");
            }
        });
        addBtn(manual, btnStoreImport);

        tvNoRootHint = new TextView(this);
        tvNoRootHint.setTextSize(11);
        tvNoRootHint.setTextColor(COLOR_FAINT);
        tvNoRootHint.setLineSpacing(dp(3), 1f);
        tvNoRootHint.setPadding(dp(2), dp(8), dp(2), 0);
        tvNoRootHint.setVisibility(View.GONE);
        tvNoRootHint.setText("没有 Root：一键生效已隐藏；「选择 .theme 交给主题商店」仍可用，不需要 Root。");
        manual.addView(tvNoRootHint);
        pair(manual,
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
        pair(manual,
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
        pair(manual,
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
        tvDarkBlocked = new TextView(this);
        tvDarkBlocked.setTextSize(12.5f);
        tvDarkBlocked.setTextColor(COLOR_WARN);
        tvDarkBlocked.setPadding(dp(2), dp(6), dp(2), 0);
        tvDarkBlocked.setText("没有 lsposed 和 root，无法使用");
        tvDarkBlocked.setVisibility(View.GONE);
        root.addView(tvDarkBlocked);
        envCard = card();
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
        btnFix = button("补齐联系人/短信主题包", BTN_GHOST, new Runnable() {
            @Override
            public void run() {
                installTheme();
            }
        });
        addBtn(root, btnFix);

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
        helpBody.setText("1  选 .theme → 自动复制到 下载/MCTGA 并打开商店免费试用页\n"
                + "2  商店里点「免费试用」，装好回来会自动进第 3 步\n"
                + "3  「锁定主题」挡住试用到期恢复；换主题先解锁\n"
                + "4  「一键生效」重启适配应用与 SystemUI\n"
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


    /** 硬门槛：非 ColorOS 或主版本 < 11 一律不给用；调试开关只能让它更严格。 */
    private boolean osAllowed() {
        if (!OsVersion.supported()) return false;
        try {
            return Settings.Global.getInt(getContentResolver(),
                    "mctga_force_unsupported", 0) == 0;
        } catch (Throwable t) {
            return true;
        }
    }

    private void refresh() {
        if (blocked || stopped) return;
        detectStore();
        render();
        refreshRoot();      // Root 探测完成后会触发 refreshStore / refreshEnv
        refreshHook();
    }

    private void render() {
        boolean osOk = OsVersion.supported();
        boolean isColorOs = OsVersion.colorOs();
        if (!osOk) {
            if (btnInstall != null) {
                btnInstall.setEnabled(false);
                btnInstall.setAlpha(0.45f);
                btnInstall.setText(isColorOs ? "仅支持 ColorOS 11+ / realme UI 2.0+，当前已停用"
                        : "仅支持 OPPO 系系统（ColorOS / realme UI），当前已停用");
            }
            if (btnDark != null) {
                btnDark.setEnabled(false);
                btnDark.setAlpha(0.45f);
            }
            if (btnFix != null) {
                btnFix.setEnabled(false);
                btnFix.setAlpha(0.45f);
            }
        }
        if (tvSystem != null) {
            boolean okOs = OsVersion.supported();
            tvSystem.setText((okOs ? "系统：" : "系统（不支持）：") + OsVersion.label());
            tvSystem.setTextColor(okOs ? COLOR_SUB : COLOR_BAD);
        }
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
        boolean darkBlocked = hookChecked && (!hookReady || !(rootReady || ShizukuBridge.granted()));
        if (btnInstall != null) btnInstall.setVisibility(View.VISIBLE);
        if (tvRiskNote != null) tvRiskNote.setVisibility(View.VISIBLE);
        if (tvDarkBlocked != null) tvDarkBlocked.setVisibility(darkBlocked ? View.VISIBLE : View.GONE);
        if (envCard != null) envCard.setVisibility(darkBlocked ? View.GONE : View.VISIBLE);
        if (btnDark != null) btnDark.setVisibility(darkBlocked ? View.GONE : View.VISIBLE);
        if (btnFix != null) btnFix.setVisibility(darkBlocked ? View.GONE : (hookReady ? View.VISIBLE : View.GONE));
        boolean os17 = OsVersion.supported();
        if (btnDark != null) { btnDark.setEnabled(os17); btnDark.setAlpha(os17 ? 1f : 0.4f); }
        if (btnOneKey != null) { btnOneKey.setEnabled(os17); btnOneKey.setAlpha(os17 ? 1f : 0.4f); }
        if (btnOneKey != null) btnOneKey.setVisibility(darkBlocked ? View.GONE : (hasRoot ? View.VISIBLE : View.GONE));
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
        if (hookChecked && hookUnknown) sb.append('\n').append("无法确认 LSPosed 状态（没有 Root / Shizuku）");
        else if (hookChecked && !hookReady) sb.append('\n').append("LSPosed 未运行：Hook 不生效，深色适配与补齐都无效");
        if (!darkOn()) sb.append('\n').append("已关闭：不再注入主题资源与插画");
        if (!OsVersion.colorOs()) {
            sb.append('\n').append("本软件仅支持 ColorOS 设备，当前不是 ColorOS，全部功能已停用");
        } else if (!OsVersion.supported()) {
            sb.append('\n').append("深色适配已停用（").append(OsVersion.unsupportedHint()).append("）");
        }
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

    /**
     * Hook 能不能生效，取决于 LSPosed 是否在运行。没有 LSPosed（本质要 Root）就没有 Hook，
     * 那么「补齐」写进去的主题包也没人读，所以补齐按钮要以这个为前提。
     */


    private void refreshHook() {
        if (hookChecked) return;
        if (!rootReady && !ShizukuBridge.granted()) {
            hookChecked = true;
            hookUnknown = true;
            render();
            return;
        }
        exec.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                boolean unknown = false;
                try {
                    boolean viaShizuku = ShizukuBridge.granted();
                    ShellRunner.Result r = sh(
                            "pidof lspd 2>/dev/null || true; [ -d /data/adb/lspd ] && echo LSPD_DIR",
                            10000, viaShizuku);
                    String out = r.out == null ? "" : r.out.trim();
                    unknown = out.isEmpty();
                    ok = !unknown && (out.contains("LSPD_DIR")
                            || out.replaceAll("\\D", "").length() > 0);
                } catch (Throwable t) {
                    unknown = true;
                }
                final boolean ready = ok;
                final boolean unk = unknown;
                post(new Runnable() {
                    @Override
                    public void run() {
                        hookChecked = true;
                        hookReady = ready;
                        hookUnknown = unk;
                        render();
                    }
                });
            }
        });
    }

    private void refreshRoot() {
        if (rootChecked) {
            refreshStore();
            refreshEnv(false);
            return;
        }
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
                        refreshGuide();
                        refreshStore();
                        refreshEnv(false);
                    }
                });
            }
        });
    }

    /**
     * 通道选择：有 Root 就优先 Root —— 读 Android/data、写 /data/theme 只有 Root 能保证；
     * Sui 的 shell（uid 2000, u:r:ksu:s0）会被外部存储的 FUSE 拒掉，只能做 pm 类操作。
     * 返回 true 表示走 Shizuku/Sui。
     */
    private boolean useShizukuChannel() {
        return !(rootChecked && rootReady) && ShizukuBridge.granted();
    }

    // ================================================================ 引导
    // ---------------------------------------------------------------- 引导

    /**
     * 引导步骤：1=选 .theme 并拉起商店详情页 2=等商店应用完成 3=锁定 4=全部完成。
     */
    private int guideStep() {
        String uri = prefs.getString("theme_uri", "");
        boolean picked = !uri.isEmpty() && themeUriOk;
        boolean locked = themesState.startsWith("已锁定");
        if (!picked) return 1;
        if (!appliedConfirmed()) return 2;
        if (!locked) return 3;
        return 4;
    }

    /**
     * 「选的这个 .theme 已经被商店应用了」怎么判：
     * A) 用户点过「我在商店点过免费试用」→ ack_at >= pick_at；
     * B) 自动：/data/theme 的修改时间晚于选文件时间；
     * C) 老数据（没有 pick_at）：以系统状态或历史确认为准。
     */
    private boolean appliedConfirmed() {
        long pickAt = prefs.getLong("pick_at", 0L);
        long ackAt = prefs.getLong("ack_at", 0L);
        if (pickAt <= 0) return themeApplied || ackAt > 0;
        if (ackAt >= pickAt) return true;
        return themeApplied && themeMtime >= pickAt;
    }

    private String pickedName() {
        String n = prefs.getString("theme_name", "");
        if (n == null) n = "";
        n = n.trim();
        return n.isEmpty() ? "（未记录文件名）" : n;
    }

    private String guideTitle(int step) {
        switch (step) {
            case 1: return "第 1 步 / 共 3 步：选 .theme，直接打开免费试用页";
            case 2: return "第 2 步 / 共 3 步：在主题商店点「免费试用」";
            case 3: return "第 3 步 / 共 3 步：锁定主题，挡住到期恢复";
            default: return "3 步都完成了 ✓";
        }
    }

    private String guideBody(int step) {
        switch (step) {
            case 1:
                return "下面会列出根目录和夸克两个下载目录里名字带 ColorOS 的 .theme / .exe，点一个就直接打开免费试用页；"
                        + "没有想要的就在文件管理里自己选（必须是 ColorOS 主题）。\n"
                        + "MCTGA 会把它复制到 下载/MCTGA，再拉起主题商店——不碰 /data/theme。";
            case 2:
                return "已交给商店：" + pickedName() + "\n"
                        + "在商店里点「免费试用 / 应用」，等它装完。\n"
                        + "装完回到这里会自动跳到第 3 步；没跳就点下面的按钮。\n"
                        + "想换一个就点「退回上一步」。";
            case 3:
                return "试用到期后商店可能把主题恢复成默认。点「锁定主题」就不会了；"
                        + "换主题前先「解锁」。";
            default:
                return "主题已应用、已锁定。换主题时先点「解锁」，再从第 1 步开始。\n"
                        + "（联系人 / 短信主题包补丁在「附加 · 深色适配」里）";
        }
    }

    private void guideAction(int step) {
        if (!channelReady()) {
            shizukuSetup();
            return;
        }
        switch (step) {
            case 1:
                pickTheme();
                break;
            case 2:
                confirmApplied();
                break;
            case 3:
                changeTheme(true);
                break;
            default:
                startChangeTheme();
                break;
        }
    }

    /** 第 2 步人工确认：/data/theme 的 mtime 没刷新时也能继续，避免卡在这一步。 */
    private void confirmApplied() {
        prefs.edit().putLong("ack_at", System.currentTimeMillis() / 1000L).apply();
        setLog("最近操作：已确认在商店点过免费试用");
        toast("已确认，进入第 3 步");
        refreshGuide();
    }

    /** 第 2 步退回第 1 步：清掉这次的选择，重新列出扫描结果让用户重选。 */
    private void backToPick() {
        prefs.edit().remove("pick_at")
                .remove("ack_at")
                .putString("theme_uri", "")
                .putString("theme_name", "").apply();
        scannedThemes.clear();
        scannedSizes.clear();
        lastScanAt = 0;
        setLog("最近操作：已退回上一步（重新选 .theme）");
        toast("已退回第 1 步，重新选一个 .theme");
        refreshGuide();
    }
    /** 全部完成后再换主题：先让用户去商店恢复默认主题，再把这套状态清掉重走一遍。 */
    private void startChangeTheme() {
        if (!canShowDialog()) {
            toast("先关掉其它浮窗再点");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("换主题前先恢复默认主题")
                .setMessage("系统对第三方主题的试用有记录，直接在旧主题上再套一个新的，"
                        + "商店那边经常装不上或者读不出来。\n\n"
                        + "① 点「打开默认主题」，在「我的资源」里点「默认主题」→「恢复默认主题」\n"
                        + "② 回到 MCTGA 点「已恢复，重新选主题」\n\n"
                        + "打开商店前会先自动解锁（解除挂起）。")
                .setPositiveButton("打开默认主题", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        unlockAndOpenStore();
                    }
                })
                .setNeutralButton("已恢复，重新选主题", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        resetForNewTheme();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 清掉这一次的选用状态，回到第 1 步重新选（不解锁、不改已经应用的主题）。 */
    private void resetForNewTheme() {
        prefs.edit().remove("pick_at")
                .remove("ack_at")
                .putString("theme_uri", "")
                .putString("theme_name", "").apply();
        themeUriOk = false;
        scannedThemes.clear();
        scannedSizes.clear();
        lastScanAt = 0;
        setLog("最近操作：开始换主题，请重新选 .theme");
        toast("回到第 1 步，选一个新主题");
        refreshGuide();
    }

    /** 换主题前打开商店：先解冻 + 删掉 Themes 占位文件，再拉起商店。 */
    private void unlockAndOpenStore() {
        final String pkg = (storePkg == null || storePkg.isEmpty())
                ? "com.heytap.themestore" : storePkg;
        final boolean useRoot = rootReady;
        final boolean viaShizuku = !useRoot && ShizukuBridge.granted();
        toast("正在解冻主题商店…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    ShellRunner.Result r = sh(ThemeEngine.unlock(pkg, useRoot), 20000, viaShizuku);
                    msg = ThemeEngine.operationSucceeded(r.out, false)
                            ? "已解冻并清掉锁定" : "解冻未确认（检查 Root / Shizuku）";
                } catch (Throwable t) {
                    msg = "解冻失败：" + t;
                }
                final String m = msg;
                post(new Runnable() {
                    @Override
                    public void run() {
                        toast(m);
                        openDefaultThemeList();
                    }
                });
            }
        });
    }

    /** 商店没有公开的“默认主题详情”入口，只能到「我的资源」列表，再点「默认主题」。 */
    private void openDefaultThemeList() {
        String pkg = (storePkg == null || storePkg.isEmpty())
                ? "com.heytap.themestore" : storePkg;
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(pkg,
                    "com.nearme.themespace.activities.MyResourcesUnifiedActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("已打开「我的资源」，点「默认主题」→「恢复默认主题」");
            return;
        } catch (Throwable ignored) {
        }
        openStoreApp();
    }

    private void openStoreApp() {
        String pkg = (storePkg == null || storePkg.isEmpty()) ? "com.heytap.themestore" : storePkg;
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
        }
        toast("找不到主题商店");
    }


    private void renderGuide() {
        if (tvGuide == null || btnGuide == null) return;
        // 没通道什么也做不了：扫描、核对、交给商店都要 Shizuku 或 Root
        if (!channelReady()) {
            tvGuide.setText("先激活 Shizuku（有 Root 也行）\n"
                    + "扫描 下载 里的 .theme、核对是不是 ColorOS 主题、把文件交给主题商店，"
                    + "这三件事都要特权通道。\n\n"
                    + "① 打开 Shizuku，确认已启动\n"
                    + "② 回到 MCTGA，点下面的按钮授权\n"
                    + "③ 授权后引导会自动往下走");
            tvGuide.setTextColor(COLOR_WARN);
            btnGuide.setText("连接 / 授权 Shizuku");
            btnGuide.setEnabled(true);
            btnGuide.setAlpha(1f);
            if (btnGuideAlt != null) btnGuideAlt.setVisibility(View.GONE);
            if (guideList != null) guideList.setVisibility(View.GONE);
            return;
        }
        int step = guideStep();
        tvGuide.setText(guideTitle(step) + "\n" + guideBody(step));
        tvGuide.setTextColor(step >= 4 ? COLOR_ACCENT : COLOR_TEXT);
        if (step >= 4) {
            btnGuide.setText("换主题（重新走一遍）");
            btnGuide.setEnabled(true);
            btnGuide.setAlpha(1f);
        } else {
            btnGuide.setText(guideButtonText(step));
            btnGuide.setEnabled(true);
            btnGuide.setAlpha(1f);
        }
        if (btnGuideAlt != null) {
            if (step == 1 || step == 2) {
                btnGuideAlt.setVisibility(View.VISIBLE);
                btnGuideAlt.setText(step == 1 ? "自己选文件 / 文件管理" : "退回上一步");
            } else {
                btnGuideAlt.setVisibility(View.GONE);
            }
        }
        if (step == 1) {
            renderThemeList();
            if (scannedThemes.isEmpty()) scanThemes(false);
        } else if (guideList != null) {
            guideList.setVisibility(View.GONE);
        }
    }

    private String guideButtonText(int step) {
        switch (step) {
            case 1: return "选择 .theme → 打开免费试用页";
            case 2: return "我已经在商店点过「免费试用」";
            default: return "锁定主题";
        }
    }

    /** 扫根目录 + 夸克网盘两个下载目录里的 ColorOS 主题（含 exe/7z 压缩包）；App 没存储权限，只能走特权通道。 */
    private void scanThemes(boolean force) {
        if (scanning || !channelReady()) return;
        if (!force && System.currentTimeMillis() - lastScanAt < 5000L) return;
        scanning = true;
        lastScanAt = System.currentTimeMillis();
        final boolean viaShizuku = useShizukuChannel();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                // 根目录按时间倒序；夸克目录递归；每行「大小<TAB>路径」
                String cmd = "{ ls -1t /storage/emulated/0/*.theme 2>/dev/null\n"
                        + "  find /storage/emulated/0/Download/QuarkDownloads/CloudDrive"
                        + " /storage/emulated/0/Download/QuarkCloudDrive -maxdepth 3 -type f 2>/dev/null\n"
                        + "} | while read -r p; do\n"
                        + "  case \"$p\" in *.theme|*.exe|*.7z) ;; *) continue;; esac\n"
                        + "  s=$(stat -c %s \"$p\" 2>/dev/null)\n"
                        + "  [ -n \"$s\" ] && printf '%s\\t%s\\n' \"$s\" \"$p\"\n"
                        + "done\n";
                ShellRunner.Result r = sh(cmd, 40000, viaShizuku);
                final java.util.List<String> hits = new java.util.ArrayList<String>();
                final java.util.Map<String, Long> sizes = new java.util.HashMap<String, Long>();
                java.util.Set<String> seen = new java.util.HashSet<String>();
                for (String line : r.out.split("\r?\n")) {
                    String row = line.trim();
                    int tab = row.indexOf('\t');
                    if (tab <= 0) continue;
                    String path = row.substring(tab + 1).trim();
                    long size;
                    try {
                        size = Long.parseLong(row.substring(0, tab).trim());
                    } catch (Throwable t) {
                        continue;
                    }
                    if (!path.endsWith(".theme") && !path.endsWith(".exe") && !path.endsWith(".7z")) {
                        continue;
                    }
                    if (!isColorOsTheme(path)) continue;
                    // 去重：同路径只留一次；同文件名 + 同大小也视为同一份（夸克两个目录是镜像）
                    String key = baseName(path).toLowerCase() + "|" + size;
                    if (!seen.add(key)) continue;
                    hits.add(path);
                    sizes.put(path, size);
                    if (hits.size() >= 12) break;
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        scannedThemes = hits;
                        scannedSizes = sizes;
                        renderThemeList();
                    }
                });
            }
        });
    }

    /** 把扫描结果画成按钮，点一下就走「复制/解压 → 打开免费试用页」。 */
    private void renderThemeList() {
        if (guideList == null) return;
        guideList.removeAllViews();
        boolean show = channelReady() && guideStep() == 1;
        guideList.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        TextView head = new TextView(this);
        if (scannedThemes.isEmpty()) {
            head.setText("没扫到名字带 ColorOS 的 .theme / .exe（根目录 + 夸克两个下载目录）；"
                    + "用「自己选文件 / 文件管理」挑一个。");
        } else {
            head.setText("扫到 " + scannedThemes.size()
                    + " 个 ColorOS 主题（根目录 + 夸克目录），点一个直接试用：");
        }
        head.setTextSize(11.5f);
        head.setTextColor(COLOR_SUB);
        head.setPadding(0, dp(10), 0, 0);
        guideList.addView(head);
        for (final String path : scannedThemes) {
            String nm = baseName(path);
            String label = nm;
            Long sz = scannedSizes.get(path);
            if (isArchiveFile(nm)) {
                label = nm + "  ·  压缩包" + (sz == null ? "" : " " + humanSize(sz)) + "，点它自动解压";
            }
            TextView b = button(label, BTN_SECONDARY, new Runnable() {
                @Override
                public void run() {
                    useLocalTheme(path);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            lp.topMargin = dp(6);
            b.setLayoutParams(lp);
            b.setTextSize(11.5f);
            guideList.addView(b);
        }
    }

    /** 点中扫描结果：复制到 下载/MCTGA，再拉起商店详情页。 */
    private void useLocalTheme(final String src) {
        if (!channelReady()) {
            shizukuSetup();
            return;
        }
        if (!isColorOsTheme(src)) {
            toast("这不是 ColorOS 主题，装不了");
            return;
        }
        final String name = baseName(src);
        final boolean archive = isArchiveFile(name);
        final boolean viaShizuku = useShizukuChannel();
        toast(archive ? "正在解压 " + name + " …" : "正在准备 " + name + " …");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String dest = null;
                String err = null;
                String finalName = name;
                java.io.File pkg = null;
                try {
                    // App 自己读不了 /storage：让特权通道 cat 源文件，app 把字节写进自己的缓存
                    pkg = stageFile(src, "pkg.bin");
                    java.io.File theme;
                    if (archive) {
                        theme = extractThemeFromArchive(pkg);
                        if (theme == null) throw new java.io.IOException("解压失败，包里没有 .theme");
                        if (lastEntryName != null && lastEntryName.toLowerCase().endsWith(".theme")) {
                            finalName = lastEntryName;
                        } else {
                            finalName = name.replaceAll("(?i)\\.(exe|7z|zip)$", "") + ".theme";
                        }
                    } else {
                        theme = pkg;
                    }
                    if (!looksLikeZip(theme)) {
                        throw new java.io.IOException("不是主题包（.theme 应该是 zip）");
                    }
                    String safe = safeThemeName(finalName);
                    String dir = android.os.Environment
                            .getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS)
                            .getAbsolutePath() + "/MCTGA";
                    java.io.InputStream in = new java.io.FileInputStream(theme);
                    dest = writeToDownloads(in, theme.length(), safe, dir);
                    in.close();
                    finalName = safe;
                    // 商店按路径读，尽量把权限放开（FUSE 上可能无效，root 下才有实际效果）
                    sh("chmod 0644 " + shq(dest) + " 2>/dev/null\n", 10000, viaShizuku);
                } catch (Throwable t) {
                    err = String.valueOf(t);
                } finally {
                    deleteQuietly(pkg);
                    // 解压出来的临时主题已经写进 下载/MCTGA，不用留着占 80+ MB
                    deleteQuietly(new java.io.File(getCacheDir(), "extracted.theme"));
                    lastEntryName = null;
                }
                final String fDest = dest;
                final String fErr = err;
                final String fName = finalName;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (fErr != null || fDest == null) {
                            android.util.Log.w("MCTGA", "useLocalTheme fail: " + fErr);
                            setLog("最近操作：准备失败 " + fErr);
                            toast("准备失败：" + fErr);
                            return;
                        }
                        prefs.edit().putString("theme_uri", "file://" + fDest)
                                .putString("theme_name", fName)
                                .putLong("pick_at", System.currentTimeMillis() / 1000L)
                                .putLong("ack_at", 0L).apply();
                        refreshGuide();
                        openThemePreview(fDest);
                    }
                });
            }
        });
    }

    /** 是不是 ColorOS 主题：目前按文件名判定（大小写不敏感）。 */
    private boolean isColorOsTheme(String pathOrName) {
        return pathOrName != null && pathOrName.toLowerCase().contains("coloros");
    }

    private String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    /** 单引号转义，拼 shell 路径用。 */
    private String shq(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    /** .exe / .7z 当压缩包处理（夸克网盘下来的主题多为 7-Zip SFX，扩展名是 exe）。 */
    private boolean isArchiveFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".exe") || n.endsWith(".7z");
    }

    private String safeThemeName(String raw) {

        String safe = raw == null ? "" : raw.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        if (safe.isEmpty()) safe = "theme.theme";
        if (!safe.toLowerCase().endsWith(".theme")) safe = safe + ".theme";
        return safe;
    }

    /**
     * 把外部路径的文件流式搬进 app 自己的缓存：shell 有读权限，app 有写自己目录的权限，
     * 中间不落任何 app 读不了的中间文件。
     */
    private java.io.File stageFile(String src, String dstName) throws Exception {
        final boolean viaShizuku = useShizukuChannel();
        // 先问一下源文件多大：读取中途被截断是最容易让商店「读不出来」的原因
        long expect = -1;
        try {
            ShellRunner.Result st = sh("stat -c %s " + shq(src) + "\n", 15000, viaShizuku);
            expect = Long.parseLong(st.out.trim());
        } catch (Throwable ignored) {
        }
        java.io.File dst = new java.io.File(getCacheDir(), dstName);
        Process p = viaShizuku
                ? ShizukuBridge.process("cat " + shq(src))
                : RootShell.process("cat " + shq(src));
        java.io.InputStream in = p.getInputStream();
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        long total = 0;
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            try { out.close(); } catch (Throwable ignored) { }
            try { in.close(); } catch (Throwable ignored) { }
        }
        int code;
        try {
            code = p.waitFor();
        } catch (Throwable t) {
            code = -1;
        }
        if (code != 0 || total == 0 || (expect > 0 && total != expect)) {
            deleteQuietly(dst);
            throw new java.io.IOException("读取源文件失败或不完整（" + total + " / "
                    + (expect > 0 ? expect : -1) + " 字节，exit=" + code + "）");
        }
        return dst;
    }

    /** .theme 本体是 zip，用来挡掉解压出来不是主题包的情况。 */
    private boolean looksLikeZip(java.io.File f) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            int a = in.read();
            int b = in.read();
            return a == 'P' && b == 'K';
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 找 7z 签名偏移（SFX exe 前半段是 PE 头，真正的 7z 数据在后面）；没有返回 -1。 */
    private long find7zOffset(java.io.File f) {
        byte[] sig = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
        java.io.InputStream in = null;
        try {
            in = new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 16);
            long pos = 0;
            int matched = 0;
            int b;
            while ((b = in.read()) >= 0) {
                if ((byte) b == sig[matched]) {
                    matched++;
                    if (matched == sig.length) return pos - sig.length + 1;
                } else {
                    matched = (byte) b == sig[0] ? 1 : 0;
                }
                pos++;
            }
        } catch (Throwable t) {
            android.util.Log.w("MCTGA", "7z scan fail " + t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
        return -1;
    }

    /** 从 exe/7z 里解出第一个 .theme 到 cache/extracted.theme；条目名记在 lastEntryName。 */
    private java.io.File extractThemeFromArchive(java.io.File pkg) {
        long off = find7zOffset(pkg);
        if (off < 0) return null;
        java.io.File out = new java.io.File(getCacheDir(), "extracted.theme");
        java.nio.channels.FileChannel fc = null;
        org.apache.commons.compress.archivers.sevenz.SevenZFile z = null;
        try {
            fc = new java.io.RandomAccessFile(pkg, "r").getChannel();
            z = new org.apache.commons.compress.archivers.sevenz.SevenZFile(
                    new ShiftChannel(fc, off));
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String en = e.getName() == null ? "" : e.getName();
                if (!en.toLowerCase().endsWith(".theme")) continue;
                lastEntryName = baseName(en);
                java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                try {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = z.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    os.close();
                }
                // 解出来的长度必须和条目声明一致，否则宁可报错也别把半截文件丢给商店
                if (out.length() != e.getSize()) {
                    android.util.Log.w("MCTGA", "7z extract short: " + out.length() + " / " + e.getSize());
                    deleteQuietly(out);
                    lastEntryName = null;
                    return null;
                }
                return out;
            }
        } catch (Throwable t) {
            android.util.Log.w("MCTGA", "7z extract fail " + t);
        } finally {
            try { if (z != null) z.close(); } catch (Throwable ignored) { }
            try { if (fc != null) fc.close(); } catch (Throwable ignored) { }
        }
        deleteQuietly(out);
        return null;
    }

    /** 把底层文件从某个偏移当作一个新的可读通道，省掉再复制一份 7z 数据。 */
    private static final class ShiftChannel implements java.nio.channels.SeekableByteChannel {
        private final java.nio.channels.FileChannel base;
        private final long off;
        private final long len;
        private long pos;

        ShiftChannel(java.nio.channels.FileChannel base, long off) throws java.io.IOException {
            this.base = base;
            this.off = off;
            this.len = base.size() - off;
        }

        public int read(java.nio.ByteBuffer dst) throws java.io.IOException {
            if (pos >= len) return -1;
            int want = (int) Math.min(dst.remaining(), len - pos);
            java.nio.ByteBuffer tmp = dst.duplicate();
            tmp.limit(tmp.position() + want);
            int n = base.read(tmp, off + pos);
            if (n > 0) {
                dst.position(dst.position() + n);
                pos += n;
            }
            return n;
        }

        public int write(java.nio.ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        public long position() {
            return pos;
        }

        public java.nio.channels.SeekableByteChannel position(long newPosition) {
            pos = newPosition;
            return this;
        }

        public long size() {
            return len;
        }

        public java.nio.channels.SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        public boolean isOpen() {
            return base.isOpen();
        }

        public void close() throws java.io.IOException {
            base.close();
        }
    }

    private String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.0f MB", bytes / 1048576.0);
        }
        return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
    }
    /** 查一次「主题是否应用」「/data/theme 的 mtime」「补齐包有几个」，用来推进引导。 */
    private void refreshGuide() {
        final String packsDir = new File(getFilesDir(), "theme-packs").getAbsolutePath();
        final boolean viaShizuku = useShizukuChannel();
        final String uri = prefs.getString("theme_uri", "");
        // theme_uri 指向的文件还在不在：商店导入完成后常把源文件删掉，状态得跟着回退
        String uriCheck = "";
        if (uri.startsWith("file://")) {
            String path = uri.substring(7);
            uriCheck = "echo URIHAS:$( [ -f " + shq(path) + " ] && echo 1 || echo 0 )\n";
        }
        final String extra = uriCheck;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                ShellRunner.Result r = sh("echo APPLIED:$( [ -f /data/theme/com.android.settings ] && echo 1 || echo 0 )\n"
                        + "echo MTIME:$(stat -c %Y /data/theme 2>/dev/null || echo 0)\n"
                        + "echo PACKS:$(ls '" + packsDir + "' 2>/dev/null | wc -l)\n"
                        + extra, 12000, viaShizuku);
                final boolean applied = r.out.contains("APPLIED:1");
                long mtime = 0;
                int packs = 0;
                boolean urihas = false;
                boolean uriChecked = false;
                for (String line : r.out.split("\r?\n")) {
                    if (line.startsWith("PACKS:")) {
                        try { packs = Integer.parseInt(line.substring(6).trim()); } catch (Throwable ignored) { }
                    }
                    if (line.startsWith("MTIME:")) {
                        try { mtime = Long.parseLong(line.substring(6).trim()); } catch (Throwable ignored) { }
                    }
                    if (line.startsWith("URIHAS:")) {
                        uriChecked = true;
                        urihas = line.contains("URIHAS:1");
                    }
                }
                final boolean fUriOk = uriChecked ? urihas : checkContentUri(uri);
                final int packCount = packs;
                final long fmtime = mtime;
                post(new Runnable() {
                    @Override
                    public void run() {
                        themeApplied = applied;
                        themeMtime = fmtime;
                        themeUriOk = uri.isEmpty() ? false : fUriOk;
                        packsCount = packCount;
                        renderGuide();
                    }
                });
            }
        });
    }

    /** content:// 的可用性只能用 Java 试读；file:// 交给 shell 判断（app 自己 stat 不了 /storage）。 */
    private boolean checkContentUri(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        if (uri.startsWith("file://")) return true;
        java.io.InputStream in = null;
        try {
            in = getContentResolver().openInputStream(android.net.Uri.parse(uri));
            if (in == null) return false;
            return in.read() >= 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
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
        final boolean viaShizuku = useShizukuChannel();
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
                        refreshGuide();
                        maybePromptLocked();
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

    /** 商店被挂起且 Themes 是空占位文件时，主动问一次要不要处理。 */

    /** 商店被挂起 / Themes 是空占位文件，任一成立就主动问一次要不要处理。 */
    private void maybePromptLocked() {
        boolean susp = storeSuspendedKnown && storeSuspended;
        boolean empty = themesState.startsWith("已锁定");
        if (!susp && !empty) {
            unlockPrompted = false;
            return;
        }
        if (unlockPrompted || !canShowDialog() || !channelReady()) return;
        unlockPrompted = true;
        String title;
        String msg;
        if (susp && empty) {
            title = "主题商店被锁定";
            msg = "检测到主题商店处于挂起状态，且 Themes 是空占位文件。\n\n"
                    + "这会导致商店打不开，也没法恢复默认主题。\n\n"
                    + "要现在解冻商店并删除这个空文件吗？";
        } else if (susp) {
            title = "主题商店被挂起";
            msg = "检测到主题商店处于挂起状态（Themes 空文件已不在）。\n\n"
                    + "挂起状态下商店打不开。\n\n"
                    + "要现在解冻商店吗？";
        } else {
            title = "Themes 是空文件";
            msg = "检测到商店的 Themes 仍是空占位文件（商店没有被挂起）。\n\n"
                    + "空文件会让商店导入 / 换主题失败。\n\n"
                    + "要现在删除这个空文件吗？";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("处理", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        handleLockedState();
                    }
                })
                .setNegativeButton("暂不", null)
                .show();
    }
    private void handleLockedState() {
        final String pkg = (storePkg == null || storePkg.isEmpty())
                ? "com.heytap.themestore" : storePkg;
        final boolean useRoot = rootReady;
        final boolean viaShizuku = !useRoot && ShizukuBridge.granted();
        toast("正在解冻主题商店…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    ShellRunner.Result r = sh(ThemeEngine.unlock(pkg, useRoot), 20000, viaShizuku);
                    msg = ThemeEngine.operationSucceeded(r.out, false)
                            ? "已解冻并删除空文件" : "处理未确认（检查 Root / Shizuku）";
                } catch (Throwable t) {
                    msg = "处理失败：" + t;
                }
                final String m = msg;
                post(new Runnable() {
                    @Override
                    public void run() {
                        toast(m);
                        refresh();
                    }
                });
            }
        });
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
        final boolean viaShizuku = useShizukuChannel();
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
        final boolean viaShizuku = useShizukuChannel();
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
        // 尽量从上次选的目录 / 下载目录打开，少让用户翻文件夹（不认这个 extra 的选择器会忽略）
        try {
            String last = prefs.getString("theme_uri", "");
            i.putExtra("android.provider.extra.INITIAL_URI", last.isEmpty()
                    ? android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    : Uri.parse(last));
        } catch (Throwable ignored) {
        }
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
        if (!isColorOsTheme(name)) {
            toast("这不是 ColorOS 主题，MCTGA 装不了");
            setLog("最近操作：已拒绝 " + name + "\n这不是 ColorOS 主题，MCTGA 装不了");
            refreshGuide();
            return;
        }
        // 每次重新选文件都清掉上一次的「已应用」确认，引导才会重新问一遍。
        long now = System.currentTimeMillis() / 1000L;
        prefs.edit().putString("theme_uri", uri.toString())
                .putString("theme_name", name)
                .putLong("pick_at", now)
                .putLong("ack_at", 0L).apply();
        refreshGuide();
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
                .setMessage("ColorOS 的主题引擎不会给联系人 / 短信解压主题包，深色下这两个界面会发亮。\n\n"
                        + "这里会从你选的 .theme 里取出这两个应用的主题资源，交给模块在它们进程里读取，"
                        + "让深色模式也能套上主题。\n\n"
                        + "不会修改系统目录 /data/theme，也不会改动 .theme 原文件。")
                .setPositiveButton("开始补齐", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doInstallTheme();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选中的 .theme 交给主题商店：流式直写 Download/MCTGA，再打开商店详情页，不写 /data/theme。 */
    private void handoffTheme(final Uri uri) {
        final String name = queryName(uri);
        final long size = querySize(uri);
        toast("正在交给主题商店…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String dest = null;
                String err = null;
                java.io.InputStream in = null;
                try {
                    String safe = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
                    if (safe.isEmpty()) safe = "theme.theme";
                    if (!safe.toLowerCase().endsWith(".theme")) safe = safe + ".theme";
                    String dir = android.os.Environment
                            .getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS)
                            .getAbsolutePath() + "/MCTGA";
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("无法读取所选文件");
                    dest = writeToDownloads(in, size, safe, dir);
                    if (rootReady && dest != null) {
                        // 有 root 时补一次 chmod，保证商店按路径一定能读到
                        RootShell.shell("chmod 0644 '" + dest + "'", 10000);
                    }
                } catch (Throwable t) {
                    err = String.valueOf(t);
                } finally {
                    if (in != null) {
                        try { in.close(); } catch (Throwable ignored) { }
                    }
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

    /** 所选文件的大小；取不到返回 -1（-1 时不做「同名同大小复用」）。 */
    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1L;
    }

    /** 用 MediaStore 把流直接写进 下载/MCTGA（API 29+ 不需要存储权限）；返回真实文件路径。 */
    private String writeToDownloads(java.io.InputStream src, long size, String name, String dir)
            throws Exception {
        android.content.ContentResolver cr = getContentResolver();
        android.net.Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;

        // 同名同大小才复用；而且必须确认那个文件真的还在——只信 MediaStore 的记录会拿到已删文件
        if (size > 0) {
            android.database.Cursor c = cr.query(collection,
                    new String[]{android.provider.MediaStore.Downloads._ID,
                            android.provider.MediaStore.Downloads.DISPLAY_NAME},
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                            + android.provider.MediaStore.Downloads.SIZE + "=? AND "
                            + android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                    new String[]{name, String.valueOf(size), "Download/MCTGA%"},
                    android.provider.MediaStore.Downloads._ID + " DESC");
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        String realName = c.getString(1);
                        android.net.Uri row = android.content.ContentUris.withAppendedId(collection, id);
                        android.content.res.AssetFileDescriptor fd = null;
                        try {
                            fd = cr.openAssetFileDescriptor(row, "r");
                            if (fd != null && fd.getLength() == size) {
                                return dir + "/" + realName;
                            }
                        } catch (Throwable ignored) {
                            // 文件已经没了，下面把这行清掉重新写
                        } finally {
                            try { if (fd != null) fd.close(); } catch (Throwable ignored) { }
                        }
                        try { cr.delete(row, null, null); } catch (Throwable ignored) { }
                    }
                } finally {
                    c.close();
                }
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
            byte[] buf = new byte[65536];
            int n;
            while ((n = src.read(buf)) > 0) os.write(buf, 0, n);
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

    private void deleteQuietly(java.io.File f) {
        try {
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 启动时清掉上一轮遗留的大副本；仍被当作主题来源的那份要留着。 */
    /** 启动时清掉上一轮遗留的中转文件；import.theme 可能正被补齐使用，10 分钟内的不动。 */
    private void cleanupCacheCopies() {
        java.io.File c = getCacheDir();
        // 旧版本（流式改造前）留下的副本，现在不写了，只做清理
        deleteQuietly(new java.io.File(c, "handoff.theme"));
        java.io.File imp = new java.io.File(c, "import.theme");
        if (System.currentTimeMillis() - imp.lastModified() > 10 * 60 * 1000L) deleteQuietly(imp);
        // 解压 exe/7z 的中转文件（正常流程自己会删，这里兜底崩溃残留）
        deleteQuietly(new java.io.File(c, "pkg.bin"));
        deleteQuietly(new java.io.File(c, "extracted.theme"));
    }

    /** 调商店自己的主题详情页；文件管理点 .theme 用的就是这套 action + extra。 */
    private void openThemePreview(final String path) {
        final String pkg = (storePkg == null || storePkg.isEmpty()) ? "com.heytap.themestore" : storePkg;
        if (!channelReady()) {
            // 没有特权通道就没法 force-stop，商店可能仍显示上一次的主题
            if (tryPreview(pkg, path)) return;
            setLog("最近操作：打不开主题商店详情页\n文件已放在 " + path);
            toast("打不开主题商店详情页，文件已放在 下载/MCTGA");
            openFileManager();
            return;
        }
        final boolean viaShizuku = useShizukuChannel();
        exec.execute(new Runnable() {
            @Override
            public void run() {
                // 关键：商店会把上一次的本地主题记住，不换进程就一直打开旧的那份——
                // 先结束它的进程，再拉详情页，才能保证加载的是这次的文件。
                sh("pm unsuspend --user 0 " + shq(pkg) + " 2>/dev/null || :\n"
                        + "am force-stop " + shq(pkg) + "\n", 20000, viaShizuku);
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (tryPreview(pkg, path)) return;
                        setLog("最近操作：打不开主题商店详情页\n文件已放在 " + path);
                        toast("打不开主题商店详情页，文件已放在 下载/MCTGA");
                        openFileManager();
                    }
                });
            }
        });
    }

    private boolean tryPreview(String pkg, String path) {
        // 顺序很重要：FileManager 用的是 oppo.intent.action.OPPO_PREVIEW_THEME（本地文件解析分支）；
        // 先试 com.oplus.themestore.action.PREVIEW_THEME 会进另一条分支——页面打开但内容全空。
        String[] actions = {
                "oppo.intent.action.OPPO_PREVIEW_THEME",
                "com.oplus.themestore.action.PREVIEW_THEME",
                "android.intent.action.OPPO_PREVIEW_THEME",
        };
        for (String action : actions) {
            try {
                Intent i = new Intent(action);
                i.setPackage(pkg);
                i.putExtra("preview_theme_path", path);
                i.putExtra("oppo_preview_theme_path", path);
                i.putExtra("oplus_preview_theme_path", path);
                // 不加 SINGLE_TOP：商店栈顶已有详情页时会复用旧实例、不重新加载（会显示上一个主题）
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivity(i);
                toast("已打开主题详情，加载几秒后点「免费试用」");
                return true;
            } catch (Throwable t) {
                android.util.Log.w("MCTGA", "preview fail " + action + " -> " + t);
            }
        }
        return false;
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
                    boolean copied = false;
                    // 扫描出来的主题只有文件路径（App 自己读不了 /storage），走特权通道 cp
                    if (uriStr.startsWith("file://") && channelReady()) {
                        String srcPath = Uri.parse(uriStr).getPath();
                        ShellRunner.Result cp = sh("cp -f " + shq(srcPath) + " "
                                + shq(cache.getAbsolutePath()) + "\n", 180000, useShizukuChannel());
                        copied = cp.ok() && cache.length() > 0;
                    }
                    if (!copied) {
                        InputStream in = getContentResolver().openInputStream(Uri.parse(uriStr));
                        if (in == null) throw new java.io.IOException("无法读取所选文件");
                        FileOutputStream out = new FileOutputStream(cache);
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        out.close();
                    }

                    String cfgText = "";
                    if (rootReady) { try { cfgText = RootShell.shell("cat /data/theme/config 2>/dev/null", 10000).out; } catch (Throwable ignored) { } }
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
        final boolean viaShizuku = useShizukuChannel();
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



    /** 可滚动、可复制 / 分享的文本弹窗。 */
    private void showDiagnostics() {
        final boolean viaShizuku = useShizukuChannel();
        toast("正在收集诊断…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final ShellRunner.Result r = sh(DIAG_SCRIPT, 30000, viaShizuku);
                String extra = "echo THEME_FILES:$(ls /data/theme 2>/dev/null | wc -l)\n"
                        + "for p in com.android.contacts com.android.mms com.android.settings"
                        + " com.android.systemui; do "
                        + "n=$(unzip -l /data/theme/$p 2>/dev/null | grep -c night); "
                        + "echo NIGHT:$p=$n; done\n"
                        + "T=/storage/emulated/0/Android/data/" + storePkg + "/files/Themes\n"
                        + "echo THEMES:$(stat -c %s \"$T\" 2>/dev/null || echo none)\n"
                        + "echo LOCKDIR:$(ls -d /storage/emulated/0/Android/data/" + storePkg
                        + "/files/.Themes.lockdir-* 2>/dev/null | wc -l)\n"
                        + "echo SUSPEND_ENTRIES:$(dumpsys package " + storePkg
                        + " 2>/dev/null | grep -c suspendingPackage=)\n"
                        + "echo LSPD:$(pidof lspd 2>/dev/null || echo none)\n";
                final ShellRunner.Result e = sh(extra, 20000, viaShizuku);
                StringBuilder sb = new StringBuilder();
                sb.append("MCTGA ").append(ver()).append("（").append(vcode()).append("）\n");
                sb.append("设备：").append(android.os.Build.MANUFACTURER).append(' ')
                        .append(android.os.Build.MODEL).append(" / Android ")
                        .append(android.os.Build.VERSION.RELEASE).append('\n');
                sb.append("系统：").append(osVersion()).append('\n');
                sb.append("支持：").append(OsVersion.supported() ? "ColorOS 17" : "不支持").append('\n');
                sb.append("Root：").append(rootReady ? "可用" : "不可用").append('\n');
                sb.append("Shizuku：").append(ShizukuBridge.state()).append('\n');
                sb.append("LSPosed：").append(hookChecked
                        ? (hookUnknown ? "无法确认（没有 Root / Shizuku）"
                        : (hookReady ? "运行中" : "未运行")) : "检测中").append('\n');
                sb.append("通道：").append(channelText)
                        .append('（').append(viaShizuku ? "Sui / Shizuku" : "Root").append("）\n");
                sb.append("深色开关：").append(darkOn() ? "已开启" : "已关闭").append('\n');
                sb.append("商店：").append(storePkg.isEmpty() ? "未确定" : storePkg).append('\n');
                sb.append("商店状态：").append(storeSuspendedKnown
                        ? (storeSuspended ? "已挂起" : "正常") : "未知").append('\n');
                sb.append("Themes：").append(themesState).append('\n');
                sb.append("主题文件：").append(prefs.getString("theme_name", "无")).append('\n');
                String le = prefs.getString("last_error", "");
                if (!le.isEmpty()) sb.append("最近错误：").append(le).append('\n');
                if (env != null) sb.append("深色环境：").append(env.verdict).append('\n');
                java.io.File dir = new java.io.File(getFilesDir(), "theme-packs");
                java.io.File[] packs = dir.listFiles();
                if (packs == null || packs.length == 0) {
                    sb.append("补齐包：无\n");
                } else {
                    sb.append("补齐包：");
                    for (java.io.File f : packs) {
                        sb.append(f.getName()).append('(').append(f.length()).append(") ");
                    }
                    sb.append('\n');
                }
                sb.append("\n--- 主题 / 商店 ---\n").append(e.out).append(e.err);
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
                + "仅支持 ColorOS 11 及以上；非 ColorOS 设备全部功能停用。\n\n"
                + "身份：Sui/Shizuku 做 pm 操作；读写 Android/data 需要 Shizuku（无线调试）"
                + "或 Root；Sui 会接管 su，所以 Root 功能可能不可用。");
        box.addView(t);

        // 相关链接
        TextView linkTitle = new TextView(this);
        linkTitle.setText("相关链接");
        linkTitle.setTextSize(12);
        linkTitle.setTextColor(COLOR_FAINT);
        linkTitle.setPadding(0, dp(16), 0, dp(4));
        box.addView(linkTitle);

        link(box, "B 站 @weeazn（主题作者）", "https://space.bilibili.com/67368617");
        link(box, "weeazn 的主题教程文档", "https://www.kdocs.cn/l/cuXTjG62IqXw");
        link(box, "weeazn 往期主题下载", "https://www.kdocs.cn/l/cn5igSraFFa3");
        link(box, "MCTGA 项目主页", "https://github.com/xwdy114514/fuck-COS-dark-mode-themes");

        TextView rules = new TextView(this);
        rules.setTextColor(COLOR_SUB);
        rules.setTextSize(11.5f);
        rules.setLineSpacing(dp(3), 1f);
        rules.setPadding(0, dp(16), 0, 0);
        rules.setText("项目守则\n1 不要向主题作者催促适配深色主题，否则停更删库\n2 严禁倒卖\n\n"
                + "本应用不含任何 .theme 主题包、系统 APK 或反编译代码。");
        box.addView(rules);

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

    /** 一行可点击的外链。 */
    private void link(LinearLayout parent, String label, final String url) {
        TextView v = new TextView(this);
        v.setText(label + "\n" + url);
        v.setTextSize(12);
        v.setTextColor(COLOR_ACCENT);
        v.setLineSpacing(dp(3), 1f);
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        v.setBackground(rounded(COLOR_CARD_ALT, COLOR_LINE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        v.setClickable(true);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openUrl(url);
            }
        });
        parent.addView(v);
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("打不开链接：" + t.getMessage());
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


}
