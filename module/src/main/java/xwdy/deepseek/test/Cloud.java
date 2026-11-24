package xwdy.deepseek.test;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * 轻档云控：只读一个 cloud.json，用来做「公告 / 强制更新 / 禁用指定版本」。
 *
 * 设计原则：
 * - 失败静默（超时 4 秒、断网、服务器挂了都不影响本地功能）；
 * - 不采集任何设备信息，只发一次 GET；
 * - 低版本提示更新；指定停用版本由界面与 Hook 执行停用策略；
 * - 云控走 HTTP 明文（服务器只有 AAAA、8081 无 TLS），所以**配置必须验签**：
 *   cloud.json 里的 sig 是用 ECDSA P-256 私钥对「语义字段拼接串」的签名，
 *   App 内置公钥验证，验不过就当没拉到配置。这样中间人只能丢弃配置（等于断网），
 *   不能伪造公告 / 强制更新 / 停用版本。
 * - 再加 seq 单调：服务器每次改配置把 seq +1，App 记住见过的最大 seq，
 *   防止中间人重放一份旧的合法签名配置。
 */
public final class Cloud {

    /** cloud.json 地址；留空表示不启用云控。 */
    public static final String URL = "http://mc.nyafish.top:8081/cloud.json";

    /** 签名覆盖的字段串前缀，改动即等于换格式，必须和设备端签名工具 tools/CloudSign.java 同步。 */
    private static final String MAGIC = "MCTGA-CLOUD-1";

    /** ECDSA P-256 公钥（X.509 SubjectPublicKeyInfo 的 Base64）。私钥只在构建机 /data/local/tmp，不进仓库。 */
    private static final String PUBKEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEig7qJUBsyd/bGA7Mu867Xb2zlQoRydAFI85VMmp3oeCFQT7CZSTVzFJbkSSwc4ittVhxIpj/6ZVr04WGsiKLeQ==";

    private static final String TAG = "MCTGA";
    private static final String PREFS = "cfg";
    private static final String KEY_SEQ = "cloud_seq";

    public static final class Info {
        public int seq;
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

    public static void fetch(Context ctx, final Callback cb) {
        if (!enabled()) {
            if (cb != null) cb.onResult(null);
            return;
        }
        final Context app = ctx == null ? null : ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                Info info = null;
                HttpURLConnection c = null;
                try {
                    String body;
                    c = (HttpURLConnection) new URL(URL).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    c.setRequestMethod("GET");
                    c.setRequestProperty("Cache-Control", "no-cache");
                    if (c.getResponseCode() == 200) {
                        body = readAll(c.getInputStream());
                        c.disconnect();
                        info = parse(app, body);
                    } else {
                        c.disconnect();
                    }
                } catch (Throwable t) {
                    // 失败静默：不给用户弹窗，只留一行日志方便排查（断网/服务器挂了都走这里）
                    Log.w(TAG, "cloud: 拉取失败 " + t);
                } finally {
                    if (c != null) c.disconnect();
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
        try (InputStream input = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            long deadline = android.os.SystemClock.elapsedRealtime() + 4000;
            int n;
            while ((n = input.read(b)) != -1) {
                if (android.os.SystemClock.elapsedRealtime() > deadline)
                    throw new java.io.IOException("cloud body deadline exceeded");
                if (bo.size() + n > 65536) throw new java.io.IOException("cloud body too large");
                bo.write(b, 0, n);
            }
            return new String(bo.toByteArray(), "UTF-8");
        }
    }

    /** 解析 + 验签 + 防重放；任何一步不过都返回 null（静默，不影响本地功能）。 */
    private static Info parse(Context ctx, String body) {
        try {
            JSONObject o = new JSONObject(body);
            Info i = new Info();
            i.seq = o.optInt("seq", 0);
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

            String sig = o.optString("sig", "");
            if (!verify(i, sig)) {
                Log.w(TAG, "cloud: 签名无效，忽略配置（seq=" + i.seq + "）");
                return null;
            }
            if (ctx != null) {
                SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
                int seen = p.getInt(KEY_SEQ, 0);
                if (i.seq < seen) {
                    Log.w(TAG, "cloud: seq 回退（" + i.seq + " < " + seen + "），疑似重放，忽略");
                    return null;
                }
                if (i.seq > seen) p.edit().putInt(KEY_SEQ, i.seq).apply();
            }
            Log.i(TAG, "cloud: 验签通过 seq=" + i.seq + " minVersionCode=" + i.minVersionCode
                    + " notice=" + (i.notice.length() > 0 ? "有" : "无"));
            return i;
        } catch (Throwable t) {
            Log.w(TAG, "cloud: 解析失败 " + t);
            return null;
        }
    }

    private static boolean verify(Info i, String sigB64) {
        if (sigB64 == null || sigB64.length() == 0) return false;
        try {
            byte[] key = Base64.decode(PUBKEY_B64, Base64.NO_WRAP);
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(key));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk);
            s.update(canonical(i.seq, i.minVersionCode, i.blocked, i.notice, i.downloadUrl)
                    .getBytes(Charset.forName("UTF-8")));
            return s.verify(Base64.decode(sigB64, Base64.NO_WRAP));
        } catch (Throwable t) {
            Log.w(TAG, "cloud: 验签异常 " + t);
            return false;
        }
    }

    /**
     * 被签的「语义字段拼接串」，必须与 tools/CloudSign.java 的 canonical() 逐字节一致。
     * 只覆盖真正影响行为的字段，JSON 排版 / 键序 / 多余字段都不参与签名。
     */
    static String canonical(int seq, int minVersionCode, int[] blocked,
                            String notice, String downloadUrl) {
        int[] b = blocked == null ? new int[0] : blocked.clone();
        Arrays.sort(b);
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        sb.append("seq=").append(seq).append('\n');
        sb.append("minVersionCode=").append(minVersionCode).append('\n');
        sb.append("blocked=");
        for (int k = 0; k < b.length; k++) {
            if (k > 0) sb.append(',');
            sb.append(b[k]);
        }
        sb.append('\n');
        sb.append("notice=").append(notice == null ? "" : notice).append('\n');
        sb.append("downloadUrl=").append(downloadUrl == null ? "" : downloadUrl).append('\n');
        return sb.toString();
    }
}
