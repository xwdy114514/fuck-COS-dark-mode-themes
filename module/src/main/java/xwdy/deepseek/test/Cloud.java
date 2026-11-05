package xwdy.deepseek.test;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 轻档云控：只读一个 cloud.json，用来做「公告 / 强制更新 / 禁用指定版本」。
 *
 * 设计原则：
 * - 失败静默（超时 4 秒、断网、服务器挂了都不影响本地功能）；
 * - 不采集任何设备信息，只发一次 GET；
 * - 只做提示，不阻断使用（blocked 版本也只是提示升级，不锁功能）。
 */
public final class Cloud {

    /** cloud.json 地址；留空表示不启用云控。 */
    public static final String URL = "http://mc.nyafish.top:8081/cloud.json";

    public static final class Info {
        public int minVersionCode;
        public int[] blocked;
        public String notice = "";
        public String downloadUrl = "";
    }

    public interface Callback {
        void onResult(Info info);
    }

    private Cloud() {
    }

    public static boolean enabled() {
        return URL != null && URL.length() > 0;
    }

    public static void fetch(final Callback cb) {
        if (!enabled()) {
            if (cb != null) cb.onResult(null);
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Info info = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.setRequestMethod("GET");
                    c.setRequestProperty("Cache-Control", "no-cache");
                    if (c.getResponseCode() == 200) {
                        String body = readAll(c.getInputStream());
                        info = parse(body);
                    }
                    c.disconnect();
                } catch (Throwable ignored) {
                }
                final Info out = info;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.onResult(out);
                    }
                });
            }
        }, "mctga-cloud").start();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    private static Info parse(String body) {
        try {
            JSONObject o = new JSONObject(body);
            Info i = new Info();
            i.minVersionCode = o.optInt("minVersionCode", 0);
            i.notice = o.optString("notice", "");
            i.downloadUrl = o.optString("downloadUrl", "");
            JSONArray arr = o.optJSONArray("blockedVersionCodes");
            if (arr != null) {
                i.blocked = new int[arr.length()];
                for (int k = 0; k < arr.length(); k++) i.blocked[k] = arr.optInt(k, 0);
            } else {
                i.blocked = new int[0];
            }
            return i;
        } catch (Throwable t) {
            return null;
        }
    }
}
