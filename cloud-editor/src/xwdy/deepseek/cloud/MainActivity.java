package xwdy.deepseek.cloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * MCTGA 云控编辑器：读/写服务器上的 cloud.json（8081）。
 * 读是公开的；写要 Basic 认证（账号 mctga）。
 */
public class MainActivity extends Activity {

    private static final String URL_CLOUD = "http://mc.nyafish.top:8081/cloud.json";
    private static final String AUTH = "mctga:MeiYou520";

    private EditText etMin, etBlocked, etNotice, etUrl;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(20), p, p);

        TextView title = new TextView(this);
        title.setText("MCTGA 云控");
        title.setTextSize(20f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setTextSize(11.5f);
        hint.setText(URL_CLOUD + "\n读：公开；写：Basic 认证 mctga");
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        etMin = field(root, "最低版本号（versionCode，低于会提示更新，0 表示不限）", "200", false);
        etBlocked = field(root, "禁用版本号（逗号分隔，命中会提示已停用）", "203,205", false);
        etNotice = field(root, "公告（显示在主界面标题下，留空则不显示）", "", true);
        etUrl = field(root, "更新下载地址（可留空）", "", false);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button btnLoad = new Button(this);
        btnLoad.setText("读取");
        btnLoad.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load();
            }
        });
        Button btnSave = new Button(this);
        btnSave.setText("发布");
        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });
        bar.addView(btnLoad);
        bar.addView(btnSave);
        root.addView(bar);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12f);
        tvStatus.setPadding(0, dp(10), 0, 0);
        tvStatus.setText("状态：待操作");
        root.addView(tvStatus);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        load();
    }

    private EditText field(LinearLayout root, String label, String def, boolean multiline) {
        TextView tv = new TextView(this);
        tv.setTextSize(12f);
        tv.setText(label);
        tv.setPadding(0, dp(8), 0, dp(2));
        root.addView(tv);
        EditText et = new EditText(this);
        et.setText(def);
        et.setTextSize(13f);
        if (multiline) {
            et.setSingleLine(false);
            et.setMinLines(3);
            et.setGravity(android.view.Gravity.TOP);
        } else {
            et.setSingleLine(true);
            et.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        root.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private void status(String s) {
        if (tvStatus != null) tvStatus.setText("状态：" + s);
    }

    private void load() {
        status("读取中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                String body = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(URL_CLOUD).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("Cache-Control", "no-cache");
                    if (c.getResponseCode() == 200) body = readAll(c.getInputStream());
                    else err = "HTTP " + c.getResponseCode();
                    c.disconnect();
                } catch (Throwable t) {
                    err = t.toString();
                }
                final String b = body;
                final String e = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (b == null) {
                            status("读取失败：" + e);
                            return;
                        }
                        fill(b);
                        status("已读取");
                    }
                });
            }
        }).start();
    }

    private void fill(String body) {
        try {
            JSONObject o = new JSONObject(body);
            etMin.setText(String.valueOf(o.optInt("minVersionCode", 0)));
            etNotice.setText(o.optString("notice", ""));
            etUrl.setText(o.optString("downloadUrl", ""));
            JSONArray arr = o.optJSONArray("blockedVersionCodes");
            StringBuilder sb = new StringBuilder();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(arr.optInt(i, 0));
                }
            }
            etBlocked.setText(sb.toString());
        } catch (Throwable t) {
            status("解析失败：" + t);
        }
    }

    private void save() {
        String json;
        try {
            JSONObject o = new JSONObject();
            o.put("minVersionCode", parseInt(etMin.getText().toString()));
            o.put("notice", etNotice.getText().toString());
            o.put("downloadUrl", etUrl.getText().toString());
            JSONArray arr = new JSONArray();
            String[] parts = etBlocked.getText().toString().split("[,，\\s]+");
            for (String s : parts) {
                if (s.length() == 0) continue;
                arr.put(parseInt(s));
            }
            o.put("blockedVersionCodes", arr);
            json = o.toString();
        } catch (Throwable t) {
            toast("生成 JSON 失败：" + t);
            return;
        }
        final String payload = json;
        status("发布中…");
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                int code = 0;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(URL_CLOUD).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestMethod("PUT");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setRequestProperty("Authorization", "Basic " + basic(AUTH));
                    OutputStream os = c.getOutputStream();
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    code = c.getResponseCode();
                    c.disconnect();
                } catch (Throwable t) {
                    err = t.toString();
                }
                final int codeF = code;
                final String errF = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (codeF >= 200 && codeF < 300) {
                            status("已发布（HTTP " + codeF + "）");
                            toast("已发布");
                        } else {
                            status("发布失败：" + (errF != null ? errF : "HTTP " + codeF));
                        }
                    }
                });
            }
        }).start();
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private String basic(String s) {
        return android.util.Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
