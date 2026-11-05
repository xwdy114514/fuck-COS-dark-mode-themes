package xwdy.deepseek.test;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * 文案外置：界面文字按 /storage/emulated/0/MCTGA/texts.json 覆盖，实时生效。
 *
 * 支持两种命中：
 * 1) 整串精确命中（普通按钮、标题）；
 * 2) 片段命中（拼接出来的状态行，例如 "已锁定（空文件） · 商店已挂起" 里的 " · 商店已挂起"）。
 *    片段只取长度 >= 6 的键，避免 "（" "）" 这类短词误伤。
 */
public final class Texts {

    public static final String FILE = "/storage/emulated/0/MCTGA/texts.json";
    private static final String TAG_PREFIX = "mctga_text:";
    private static final int MIN_FRAGMENT = 3;

    /** 可做片段替换的键：至少 3 个字符，且含文字/数字，避免 "（" "）" 这类符号误伤。 */
    private static boolean usableFragment(String k) {
        if (k == null || k.length() < MIN_FRAGMENT) return false;
        for (int i = 0; i < k.length(); i++) {
            if (Character.isLetterOrDigit(k.charAt(i))) return true;
        }
        return false;
    }

    private static HashMap<String, String> map;
    private static List<String> fragments = new ArrayList<String>();
    private static List<String> templates = new ArrayList<String>();
    private static long mtime = -1L;

    private Texts() {
    }

    private static void load() {
        try {
            java.io.File f = new java.io.File(FILE);
            if (!f.isFile() || f.length() == 0) {
                map = null;
                fragments = new ArrayList<String>();
                templates = new ArrayList<String>();
                mtime = -1L;
                return;
            }
            if (map != null && f.lastModified() == mtime) return;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            org.json.JSONObject o = new org.json.JSONObject(new String(bo.toByteArray(), "UTF-8"));
            HashMap<String, String> m = new HashMap<String, String>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, k);
                // 默认表里多行文案的换行是字面 "\n"，界面文本是真换行，统一成真换行再比较
                k = k.replace("\\n", "\n");
                v = v.replace("\\n", "\n");
                if (k.length() > 0 && !v.equals(k)) m.put(k, v);
            }
            List<String> fr = new ArrayList<String>();
            for (String k : m.keySet()) {
                if (usableFragment(k)) fr.add(k);
            }
            Collections.sort(fr, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
            map = m;
            fragments = fr;
            List<String> tp = new ArrayList<String>();
            for (String k : m.keySet()) if (k.indexOf("%n") >= 0) tp.add(k);
            Collections.sort(tp, new Comparator<String>() {
                @Override
                public int compare(String a, String b) { return b.length() - a.length(); }
            });
            templates = tp;
            tplCache.clear();
            mtime = f.lastModified();
        } catch (Throwable t) {
            map = null;
            fragments = new ArrayList<String>();
            templates = new ArrayList<String>();
        }
    }

    /** 对一段文本应用映射：整串优先，其次按长度降序做片段替换。 */
    private static String transform(String src) {
        if (src == null || src.length() == 0) return src;
        String exact = map.get(src);
        if (exact != null) return fillNums(exact, src);
        // 键里带 %n 的整句模板：数字位置通配，命中后把原文数字填进新文案
        for (String tpl : templates) {
            java.util.regex.Pattern p = compileTemplate(tpl);
            if (p != null && p.matcher(src).matches()) return fillNums(map.get(tpl), src);
        }
        String out = src;
        for (String k : fragments) {
            if (out.contains(k)) out = out.replace(k, map.get(k));
        }
        return fillNums(out, src);
    }

    private static java.util.HashMap<String, java.util.regex.Pattern> tplCache =
            new java.util.HashMap<String, java.util.regex.Pattern>();

    /** 把含 %n 的键编译成正则：%n 对应一段数字，其它部分按字面量转义。 */
    private static java.util.regex.Pattern compileTemplate(String tpl) {
        java.util.regex.Pattern p = tplCache.get(tpl);
        if (p != null) return p;
        try {
            String[] parts = tpl.split("%n", -1);
            StringBuilder sb = new StringBuilder("^");
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append("(\\d+(?:[.\\-]\\d+)*)");
                sb.append(java.util.regex.Pattern.quote(parts[i]));
            }
            sb.append("$");
            p = java.util.regex.Pattern.compile(sb.toString());
            tplCache.put(tpl, p);
        } catch (Throwable t) {
            p = null;
        }
        return p;
    }

    private static final java.util.regex.Pattern NUMS = java.util.regex.Pattern.compile("\\d+(?:[.\\-]\\d+)*");

    /** 文案里的 %n 依次替换成原文中的数字（数字本身由程序的变量决定）。 */
    private static String fillNums(String tpl, String src) {
        if (tpl == null || tpl.indexOf("%n") < 0) return tpl;
        java.util.regex.Matcher m = NUMS.matcher(src == null ? "" : src);
        java.util.List<String> nums = new java.util.ArrayList<String>();
        while (m.find()) nums.add(m.group());
        if (nums.isEmpty()) return tpl;
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int from = 0;
        while (true) {
            int pos = tpl.indexOf("%n", from);
            if (pos < 0) {
                sb.append(tpl.substring(from));
                break;
            }
            sb.append(tpl, from, pos);
            sb.append(nums.get(Math.min(idx, nums.size() - 1)));
            idx++;
            from = pos + 2;
        }
        return sb.toString();
    }

    public static void apply(View root) {
        load();
        if (root == null) return;
        applyRec(root);
    }

    private static void applyRec(View v) {
        if (v == null) return;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (map != null) {
                Object tag = tv.getTag();
                if (tag instanceof String && ((String) tag).startsWith(TAG_PREFIX)) {
                    applyOne(tv, ((String) tag).substring(TAG_PREFIX.length()));
                } else {
                    CharSequence cs = tv.getText();
                    if (cs != null) {
                        String s = cs.toString();
                        String want = transform(s);
                        if (!want.equals(s)) {
                            tv.setTag(TAG_PREFIX + s);
                            applyOne(tv, s);
                        }
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyRec(g.getChildAt(i));
        }
    }

    /** 文案被清空时当作不存在：隐藏控件，不留空隙；恢复成非空再显示回来。 */
    private static void applyOne(TextView tv, String origin) {
        String want = transform(origin);
        if (want.length() == 0 && origin.length() > 0) {
            if (tv.getVisibility() != View.GONE) tv.setVisibility(View.GONE);
            return;
        }
        if (tv.getVisibility() == View.GONE) tv.setVisibility(View.VISIBLE);
        if (!want.contentEquals(tv.getText())) tv.setText(want);
    }

    /** 供诊断用：当前覆盖条数。 */
    public static int size() {
        return map == null ? 0 : map.size();
    }

}
