package xwdy.deepseek.test;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String PREFS = "cfg";
    private static final int BG_TOP = 0xFF101014;
    private static final int BG_BOTTOM = 0xFF1C1720;
    private static final int CARD = 0xFF1E1E24;
    private static final int BTN = 0xFF262630;
    private static final int TEXT_MAIN = 0xFFF2F0F5;
    private static final int TEXT_SUB = 0xFF9E97A8;

    private TextView tvStatus;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private GradientDrawable bg() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG_BOTTOM});
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(TEXT_MAIN);
        b.setTextSize(15);
        b.setAllCaps(false);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(BTN);
        gd.setCornerRadius(dp(14));
        gd.setStroke(dp(1), 0x2EFFFFFF);
        b.setBackground(gd);
        b.setElevation(0);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(bg());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad + dp(12), pad, pad);

        TextView title = new TextView(this);
        title.setText("MCTGA");
        title.setTextSize(26);
        title.setTextColor(TEXT_MAIN);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Make ColorOS Themes Great Again · ColorOS 17 · " + ver());
        sub.setTextSize(12);
        sub.setTextColor(TEXT_SUB);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        sub.setLayoutParams(subLp);
        root.addView(sub);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(14);
        tvStatus.setTextColor(TEXT_MAIN);
        tvStatus.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = round(CARD, 16);
        card.setStroke(dp(1), 0x2EFFFFFF);
        tvStatus.setBackground(card);
        tvStatus.setText("当前主题：\n" + getSharedPreferences(PREFS, 0).getString("theme_name", "未选择"));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(18);
        tvStatus.setLayoutParams(cardLp);
        root.addView(tvStatus);

        Button pick = makeButton("选择主题文件 (.theme)");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(i, 1);
                } catch (Throwable t) {
                    tvStatus.setText("打开文件选择器失败: " + t);
                }
            }
        });
        root.addView(pick);

        Button oneKey = makeButton("一键生效（重启所有适配应用 + SystemUI）");
        oneKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvStatus.setText("正在检查主题状态...");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final int state = themeState();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (state == -1) {
                                    tvStatus.setText("没有拿到 root 权限，无法检查/操作系统主题目录。\n\n请在 KernelSU / Magisk 里给本模块授权（包名 xwdy.deepseek.test），授权后再点一次。");
                                    android.widget.Toast.makeText(MainActivity.this,
                                            "请先授予 root 权限", android.widget.Toast.LENGTH_LONG).show();
                                    return;
                                }
                                if (state == 0) {
                                    tvStatus.setText("检测到主题还没有应用，请先去主题商店应用主题，再回来点一键生效");
                                    android.widget.Toast.makeText(MainActivity.this,
                                            "请先应用主题", android.widget.Toast.LENGTH_LONG).show();
                                    return;
                                }
                                tvStatus.setText("正在重启界面和 SystemUI，屏幕会短暂黑一下...");
                                android.widget.Toast.makeText(MainActivity.this,
                                        "正在重启界面和 SystemUI", android.widget.Toast.LENGTH_SHORT).show();
                                v.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        runSu("am force-stop com.android.settings; am force-stop com.android.contacts; am force-stop com.android.mms; "
                                                + "am force-stop com.coloros.filemanager; am force-stop com.coloros.alarmclock; am force-stop com.coloros.calculator; "
                                                + "am force-stop com.coloros.calendar; am force-stop com.coloros.weather2; am force-stop com.coloros.note; "
                                                + "am force-stop com.coloros.soundrecorder; am force-stop com.coloros.gallery3d; am force-stop com.coloros.video; "
                                                + "killall com.android.systemui");
                                    }
                                }, 1500);
                            }
                        });
                    }
                }).start();
            }
        });
        root.addView(oneKey);

        Button about = makeButton("关于 MCTGA"); about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });
        root.addView(about);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(TEXT_SUB);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(20), 0, 0);
        hint.setText("LSPosed 作用域（共 20 个）：\n"
                + "· 设置 / SystemUI / 联系人 / 信息\n"
                + "· 时钟 / 日历 / 录音 / 相册 / 文件管理 / 视频\n"
                + "· 数字健康 / 家人守护 / 健康 / 云服务\n"
                + "· 小布助手 / AI 记忆 / 使用技巧 / 快捷指令\n"
                + "· 主题商店 / SIM 卡工具\n\n"
                + "其中 13 个带插画背景：\n"
                + "时钟、录音、日历、文件管理、相册、家人守护、健康、云服务、\n"
                + "数字健康、小布助手、AI 记忆、使用技巧、快捷指令\n\n"
                + "使用顺序：\n"
                + "1. 去主题商店导入并应用 .theme\n"
                + "2. 回到本应用选择主题文件（选完自动补齐主题内所有包）\n"
                + "3. 点击“一键生效”");
        root.addView(hint);

        scroll.addView(root);
        setContentView(scroll);
    }

    private String ver() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
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

    private void showAbout() {
        final android.app.Dialog dlg = new android.app.Dialog(this);
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(22), dp(22), dp(14));
        box.setBackground(round(CARD, 18));

        TextView t = new TextView(this);
        t.setTextColor(TEXT_MAIN);
        t.setTextSize(13);
        t.setLineSpacing(dp(3), 1f);
        t.setText("MCTGA\nMake ColorOS Themes Great Again\n\n"
                + "版本：" + ver() + "（versionCode " + vcode() + "）\n"
                + "适用：ColorOS 17（Android 17 / SDK 37）\n"
                + "包名：xwdy.deepseek.test\n\n"
                + "作用域 20 个包，其中 13 个应用会透出主题插画背景：\n"
                + "时钟、录音、日历、文件管理、相册、家人守护、健康、云服务、\n"
                + "数字健康、小布助手、AI 记忆、使用技巧、快捷指令。\n\n"
                + "项目主页\nhttps://github.com/xwdy114514/fuck-COS-dark-mode-themes\n\n"
                + "本模块不包含任何主题包（.theme）、系统 APK 或反编译代码。\n"
                + "使用 Root 与修改系统主题有风险，请自行评估并承担。");
        box.addView(t);

        Button close = makeButton("关闭"); close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(16);
        close.setLayoutParams(lp);
        box.addView(close);

        sv.addView(box);
        dlg.setContentView(sv);
        dlg.show();
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
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
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        String name = queryName(uri);
        getSharedPreferences(PREFS, 0).edit()
                .putString("theme_uri", uri.toString())
                .putString("theme_name", name)
                .apply();
        tvStatus.setText("当前主题：\n" + name + "\n\n请到主题商店应用主题，然后回来点“一键生效”");
        doFix();
        android.widget.Toast.makeText(this,
                "请到主题商店应用主题，然后回来点“一键生效”",
                android.widget.Toast.LENGTH_LONG).show();
        try {
            Intent ts = new Intent();
            ts.setComponent(new ComponentName("com.heytap.themestore",
                    "com.nearme.themespace.activities.ThemeMainActivity"));
            ts.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(ts);
        } catch (Throwable ignored) {
        }
    }

    private void doFix() {
        tvStatus.setText("正在补齐...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    String uriStr = getSharedPreferences(PREFS, 0).getString("theme_uri", "");
                    if (uriStr.isEmpty()) {
                        msg = "还没选择主题文件";
                    } else {
                        Uri uri = Uri.parse(uriStr);
                        File cache = new File(getCacheDir(), "import.theme");
                        InputStream in = getContentResolver().openInputStream(uri);
                        FileOutputStream out = new FileOutputStream(cache);
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        out.close();
                        String cmd = "cp " + cache.getAbsolutePath() + " /data/local/tmp/eta_import.theme; "
                                + "rm -rf /data/local/tmp/eta_import; mkdir -p /data/local/tmp/eta_import; "
                                + "cd /data/local/tmp && unzip -o eta_import.theme 'com.*' -d eta_import >/dev/null 2>&1; "
                                + "n=0; for p in /data/local/tmp/eta_import/com.*; do [ -f \"$p\" ] || continue; "
                                + "b=${p##*/}; case \"$b\" in *_[0-9][0-9][0-9]) continue;; esac; "
                                + "cp -f \"$p\" /data/theme/; chown 10303:10303 \"/data/theme/$b\"; chmod 0666 \"/data/theme/$b\"; n=$((n+1)); done; "
                                + "echo \"$n\"; "
                                + "am force-stop com.android.contacts; am force-stop com.android.mms";
                        runSu(cmd);
                        msg = "已补齐主题内所有应用包（目录已刷新）";
                    }
                } catch (Throwable t) {
                    msg = "失败: " + t;
                }
                final String msgFinal = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText(msgFinal);
                    }
                });
            }
        }).start();
    }

    // 返回 -1 = 没有 root 权限，0 = 主题未应用，1 = 已应用
    private int themeState() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "echo ROOT_OK; test -f /data/theme/com.android.settings && echo THEME_YES || echo THEME_NO"});
            boolean done = p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                return -1;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            String out = sb.toString();
            if (!out.contains("ROOT_OK")) return -1;
            return out.contains("THEME_YES") ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void runSu(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        } catch (Throwable t) {
            // 子线程不能直接更新 UI
        }
    }
}
