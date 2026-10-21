package xwdy.deepseek.test;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String PREFS = "cfg";
    private TextView tv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        int statusBarHeight = 0;
        try {
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) statusBarHeight = getResources().getDimensionPixelSize(resId);
        } catch (Throwable ignored) {
        }
        ll.setFitsSystemWindows(true);
        ll.setPadding(pad, pad + statusBarHeight, pad, pad);

        tv = new TextView(this);
        tv.setTextSize(14);
        tv.setText("当前主题：\n" + getSharedPreferences(PREFS, 0).getString("theme_uri", "未选择")
                + "\n\n请在 LSPosed 中勾选作用域：\n"
                + "· com.android.settings\n"
                + "· com.android.systemui\n"
                + "· com.android.contacts\n"
                + "· com.android.mms");

        Button pick = new Button(this);
        pick.setText("选择主题文件 (.theme)");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.setType("*/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(i, 1);
                } catch (Throwable t) {
                    tv.setText("打开文件选择器失败: " + t);
                }
            }
        });

        Button fix = new Button(this);
        fix.setText("补齐 contacts/mms 并重启应用");
        fix.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doFix();
            }
        });

        Button restart = new Button(this);
        restart.setText("重启 SystemUI");
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSu("killall com.android.systemui");
                tv.setText("已请求重启 SystemUI");
            }
        });

        Button restartSettings = new Button(this);
        restartSettings.setText("重启设置/联系人/短信");
        restartSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSu("am force-stop com.android.settings; am force-stop com.android.contacts; am force-stop com.android.mms");
                tv.setText("已请求重启应用");
            }
        });

        ll.addView(tv);
        ll.addView(pick);
        ll.addView(fix);
        ll.addView(restart);
        ll.addView(restartSettings);
        setContentView(ll);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1 && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            getSharedPreferences(PREFS, 0).edit().putString("theme_uri", uri.toString()).apply();
            tv.setText("当前主题：\n" + uri);
        }
    }

    private void doFix() {
        tv.setText("正在补齐...");
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
                                + "cd /data/local/tmp && unzip -o eta_import.theme com.android.contacts com.android.mms -d eta_import >/dev/null 2>&1; "
                                + "cp -f eta_import/com.android.contacts /data/theme/; cp -f eta_import/com.android.mms /data/theme/; "
                                + "chown 10303:10303 /data/theme/com.android.contacts /data/theme/com.android.mms; "
                                + "chmod 0666 /data/theme/com.android.contacts /data/theme/com.android.mms; "
                                + "am force-stop com.android.contacts; am force-stop com.android.mms";
                        runSu(cmd);
                        msg = "已补齐 contacts/mms";
                    }
                } catch (Throwable t) {
                    msg = "失败: " + t;
                }
                final String msgFinal = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText(msgFinal);
                    }
                });
            }
        }).start();
    }

    private void runSu(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        } catch (Throwable t) {
            // 不能在子线程直接更新 UI
        }
    }
}
