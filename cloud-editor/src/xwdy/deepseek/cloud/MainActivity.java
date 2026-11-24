package xwdy.deepseek.cloud;

import android.app.Activity;
import android.os.Bundle;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * MCTGA 云控编辑器：读服务器上的 cloud.json，改完**本机签名**再上传。
 *
 * 0.5.4 起客户端会验签（ECDSA P-256）+ 校验 seq 单调，所以不能再直接 PUT 原文：
 * 直接 PUT 会把 sig 抹掉，新客户端会把整份配置当无效丢掉。
 * 现在的流程是「保存内容 → 交给本机脚本 tools/sign-cloud.sh 签名并上传」，
 * 脚本需要 root（su），签名私钥只在本机、不在 APK 里。
 */
public class MainActivity extends Activity {

    private static final String URL_CLOUD = "http://mc.nyafish.top:8081/cloud.json";
    /** 本机工作树里的配置与签名脚本（签名私钥在 /data/local/tmp/ws/keys/cloud.key）。 */
    private static final String WORK_CLOUD = "/storage/emulated/0/MCTGA/cloud.json";
    private static final String SIGN_SH = "/storage/emulated/0/MCTGA/tools/sign-cloud.sh";

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
        hint.setText(URL_CLOUD
                + "\n读取：公开。发布：本机签名（需要 root 授权）→ 自动上传。"
                + "\n客户端 0.5.4 起会验签，别再直接 PUT 原文。");
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        etMin = field(root, "最低版本号（versionCode，低于会提示更新，0 表示不限）", "221", false);
        etBlocked = field(root, "禁用版本号（逗号分隔，命中会提示已停用）", "", false);
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
        btnSave.setText("签名并发布");
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
                        status("已读取" + seqInfo(b));
                    }
                });
            }
        }).start();
    }

    /** 读出服务器当前 seq 与是否有签名，用来给状态栏加信息。 */
    private String seqInfo(String body) {
        try {
            JSONObject o = new JSONObject(body);
            int seq = o.optInt("seq", 0);
            String sig = o.optString("sig", "");
            return "（seq=" + seq + (sig.length() > 0 ? "，有签名" : "，无签名⚠") + "）";
        } catch (Throwable t) {
            return "";
        }
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
        publish(json);
    }

    /**
     * 把内容写到本机工作树，再调签名脚本（签名 + seq+1 + PUT）。
     * 脚本内部会先读服务器当前 seq 再 +1，所以这里不用管 seq。
     */
    private void publish(final String json) {
        status("签名并发布中…（可能弹 root 授权）");
        new Thread(new Runnable() {
            public void run() {
                String b64 = android.util.Base64.encodeToString(
                        json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                String cmd = "echo " + b64 + " | base64 -d > " + WORK_CLOUD
                        + " && sh " + SIGN_SH + " --upload";
                String out = "";
                boolean ok = false;
                try {
                    Process pr = startSu(cmd);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    InputStream in = pr.getInputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    ok = pr.waitFor() == 0;
                    out = new String(bo.toByteArray(), StandardCharsets.UTF_8).trim();
                } catch (Throwable t) {
                    out = t.toString();
                }
                final boolean okF = ok;
                final String outF = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (okF) {
                            status("已签名并上传\n" + outF);
                            toast("已发布（已签名）");
                            load();
                            return;
                        }
                        // 没有 root 授权：把内容落到本应用的外部目录，再把命令塞进剪贴板，
                        // 用户用 MT管理器/终端 粘一次就能签名上传（签名私钥只在构建机，不能进 APK）。
                        String cmd = savePendingAndCommand(json);
                        status("发布失败（拿不到 root）\n" + outF
                                + "\n已保存到本应用目录，命令已复制到剪贴板：\n" + cmd);
                    }
                });
            }
        }).start();
    }

    /**
     * 起 root shell。不同 root 方案下 su 的位置不一样，Shizuku 还会把 su 从被注入进程的
     * 命名空间里藏掉，所以跟主程序的 RootShell 一样逐路径试。
     */
    private Process startSu(String cmd) throws Exception {
        String[] paths = {"/system/bin/su", "/debug_ramdisk/su", "/sbin/su", "su"};
        Exception last = null;
        for (String p : paths) {
            try {
                return new ProcessBuilder(p, "-c", cmd).redirectErrorStream(true).start();
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new java.io.IOException("su not found");
    }

    /**
     * 兜底：把待发布的 JSON 写到本应用外部目录（不需要任何存储权限），
     * 生成一条「复制过去 + 签名 + 上传」的命令并放进剪贴板，返回这条命令。
     */
    private String savePendingAndCommand(String json) {
        String cmd;
        try {
            java.io.File dir = getExternalFilesDir(null);
            java.io.File f = new java.io.File(dir, "cloud.json");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(json.getBytes(StandardCharsets.UTF_8));
            fo.close();
            cmd = "cp '" + f.getAbsolutePath() + "' " + WORK_CLOUD + " && sh " + SIGN_SH + " --upload";
        } catch (Throwable t) {
            cmd = "sh " + SIGN_SH + " --upload";
        }
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("cloud.json", cmd));
            toast("命令已复制到剪贴板");
        } catch (Throwable ignored) {
        }
        return cmd;
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
