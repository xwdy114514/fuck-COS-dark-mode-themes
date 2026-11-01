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
        title.setText("fuck COS dark mod theme");
        title.setTextSize(24);
        title.setTextColor(TEXT_MAIN);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("ColorOS 17 深色主题适配 · 0.1-beta28");
        sub.setTextSize(13);
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
                        final boolean applied = hasThemeApplied();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!applied) {
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

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(TEXT_SUB);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(20), 0, 0);
        hint.setText("LSPosed 作用域（共 34 个）：\n"
                + "· 设置 / SystemUI / 联系人 / 短信\n"
                + "· 文件管理 / 时钟 / 计算器 / 日历 / 天气\n"
                + "· 便签 / 录音 / 相册 / 视频\n"
                + "· 文档 / 云服务 / 商店 / 主题商店 / 健康 / 小布\n"
                + "· 钱包 / 相机 / 游戏空间 / 手机管家 / 翻译 / 扫描 等\n\n"
                + "使用顺序：\n"
                + "1. 去主题商店应用主题\n"
                + "2. 回到软件选择主题文件（选完自动补齐主题内所有包）\n"
                + "3. 点击“一键生效”");
        root.addView(hint);

        scroll.addView(root);
        setContentView(scroll);
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

    private boolean hasThemeApplied() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "test -f /data/theme/com.android.settings && echo yes || echo no"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            return "yes".equals(line);
        } catch (Throwable t) {
            return false;
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
