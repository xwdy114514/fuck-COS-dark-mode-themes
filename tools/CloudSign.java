import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCTGA 云控签名工具（只在构建机/本机跑，不进 APK）。
 *
 * 用法：
 *   java CloudSign keygen <私钥输出> <公钥输出>
 *   java CloudSign sign   <私钥> <cloud.json> [输出文件，缺省原地覆盖]
 *   java CloudSign verify <公钥> <cloud.json>
 *
 * 算法：SHA256withECDSA（P-256）。被签的是「语义字段」按固定顺序拼成的串，
 * 必须与设备端 Cloud.canonical() 逐字节一致：
 *
 *   MCTGA-CLOUD-1\n
 *   seq=<seq>\n
 *   minVersionCode=<minVersionCode>\n
 *   blocked=<升序逗号分隔>\n
 *   notice=<notice>\n
 *   downloadUrl=<downloadUrl>\n
 *
 * 好处：JSON 怎么排版、键序如何、有没有多余字段都不影响验证；签名作为 sig 字段
 * 塞回同一个 cloud.json，服务器不用加第二个文件、nginx 不用改。
 *
 * 本工具不依赖 org.json（构建机 JDK 没有），自带一个够用的小 JSON 解析器。
 */
public final class CloudSign {

    public static final String MAGIC = "MCTGA-CLOUD-1";

    public static void main(String[] a) throws Exception {
        try {
            if (a.length == 3 && "keygen".equals(a[0])) {
                keygen(new File(a[1]), new File(a[2]));
            } else if (a.length >= 3 && "sign".equals(a[0])) {
                sign(new File(a[1]), new File(a[2]), a.length >= 4 ? new File(a[3]) : new File(a[2]));
            } else if (a.length == 3 && "verify".equals(a[0])) {
                verify(new File(a[1]), new File(a[2]));
            } else {
                System.err.println("用法: keygen <私钥> <公钥> | sign <私钥> <cloud.json> [输出] | verify <公钥> <cloud.json>");
                System.exit(2);
            }
        } catch (Throwable t) {
            System.err.println("失败: " + t);
            System.exit(1);
        }
    }

    private static void keygen(File keyOut, File pubOut) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        writeB64(keyOut, kp.getPrivate().getEncoded());
        writeB64(pubOut, kp.getPublic().getEncoded());
        System.out.println("私钥（只放本机，不进仓库、不进源码包）: " + keyOut.getAbsolutePath());
        System.out.println("公钥（Base64，粘进 Cloud.java 的 PUBKEY_B64）:");
        System.out.println(Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
    }

    private static void sign(File keyFile, File jsonFile, File out) throws Exception {
        PrivateKey key = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(readAll(keyFile))));
        Map<String, Object> o = asObject(readAll(jsonFile));
        Fields f = Fields.of(o);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(f.canonical().getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(s.sign());

        o.put("seq", f.seq);
        o.put("sig", sig);
        FileOutputStream fo = new FileOutputStream(out);
        fo.write(toJson(o, 0).getBytes(StandardCharsets.UTF_8));
        fo.write('\n');
        fo.close();
        System.out.println("已签名 seq=" + f.seq + " minVersionCode=" + f.minVersionCode
                + " blocked=" + Arrays.toString(f.blocked) + " → " + out.getAbsolutePath());
    }

    private static void verify(File pubFile, File jsonFile) throws Exception {
        PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(readAll(pubFile))));
        Map<String, Object> o = asObject(readAll(jsonFile));
        Fields f = Fields.of(o);
        String sig = str(o.get("sig"));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(key);
        s.update(f.canonical().getBytes(StandardCharsets.UTF_8));
        boolean ok = false;
        try {
            ok = s.verify(Base64.getDecoder().decode(sig));
        } catch (Throwable ignored) {
        }
        System.out.println((ok ? "签名有效" : "签名无效") + " seq=" + f.seq);
        if (!ok) System.exit(1);
    }

    /** 与设备端 Cloud.canonical() 必须逐字节一致。 */
    public static String canonical(int seq, int minVersionCode, int[] blocked,
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

    /** 云控里被签的五个字段。 */
    private static final class Fields {
        int seq, minVersionCode;
        int[] blocked;
        String notice, downloadUrl;

        static Fields of(Map<String, Object> o) {
            Fields f = new Fields();
            f.seq = num(o.get("seq"));
            f.minVersionCode = num(o.get("minVersionCode"));
            f.notice = str(o.get("notice"));
            f.downloadUrl = str(o.get("downloadUrl"));
            Object b = o.get("blockedVersionCodes");
            List<Object> list = b instanceof List ? (List<Object>) b : new ArrayList<Object>();
            f.blocked = new int[list.size()];
            for (int k = 0; k < f.blocked.length; k++) f.blocked[k] = num(list.get(k));
            return f;
        }

        String canonical() {
            return CloudSign.canonical(seq, minVersionCode, blocked, notice, downloadUrl);
        }
    }

    private static int num(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static String str(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return "";
    }

    // ---- 极简 JSON（够 cloud.json 用：对象/数组/字符串/数字/true/false/null）----

    private static Map<String, Object> asObject(byte[] data) {
        Object v = new Parser(new String(data, StandardCharsets.UTF_8)).parseValue();
        if (!(v instanceof Map)) throw new IllegalArgumentException("cloud.json 顶层不是对象");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("JSON 提前结束");
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = parseString();
                ws();
                expect(':');
                m.put(k, parseValue());
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("对象里缺逗号 @" + i);
            }
        }

        private List<Object> parseArray() {
            List<Object> l = new ArrayList<Object>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(parseValue());
                ws();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("数组里缺逗号 @" + i);
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("未知转义 \\" + e + " @" + i);
                }
            }
        }

        private Number parseNumber() {
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            String t = s.substring(st, i);
            if (t.length() == 0) throw new IllegalArgumentException("不是数字 @" + st);
            return t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0
                    ? (Number) Long.valueOf(t) : (Number) Double.valueOf(t);
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char next() {
            if (i >= s.length()) throw new IllegalArgumentException("JSON 提前结束");
            return s.charAt(i++);
        }

        private void expect(char c) {
            ws();
            if (next() != c) throw new IllegalArgumentException("期待 '" + c + "' @" + i);
        }
    }

    /** 写回 JSON（两空格缩进，中文不转义）。 */
    private static String toJson(Object v, int depth) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) return "{}";
            sb.append("{\n");
            int n = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append(pad(depth + 1)).append(quote(e.getKey())).append(": ");
                sb.append(toJson(e.getValue(), depth + 1));
                if (++n < m.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append(pad(depth)).append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) return "[]";
            sb.append("[\n");
            for (int k = 0; k < l.size(); k++) {
                sb.append(pad(depth + 1)).append(toJson(l.get(k), depth + 1));
                if (k + 1 < l.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append(pad(depth)).append(']');
        } else if (v instanceof String) {
            sb.append(quote((String) v));
        } else {
            sb.append(String.valueOf(v));
        }
        return sb.toString();
    }

    private static String pad(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < depth; k++) sb.append("  ");
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static void writeB64(File f, byte[] data) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(Base64.getEncoder().encode(data));
        fo.write('\n');
        fo.close();
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
        fi.close();
        return bo.toByteArray();
    }
}
