package xwdy.deepseek.texts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCTGA 文案编辑器：改的是 /storage/emulated/0/MCTGA/texts.json，
 * MCTGA 那边布局一变就会应用，无需重装。
 */
public class MainActivity extends Activity {

    private static final String DIR = "/storage/emulated/0/MCTGA";
    private static final String DEFAULTS = DIR + "/texts-defaults.json";
    private static final String OVERRIDE = DIR + "/texts.json";

    private final Map<String, String> defaults = new LinkedHashMap<String, String>();
    private final Map<String, String> overrides = new LinkedHashMap<String, String>();
    private final List<String> shown = new ArrayList<String>();
    private ArrayAdapter<String> adapter;
    private String filter = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        reload();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, dp(20), p, p);

        TextView title = new TextView(this);
        title.setText("MCTGA 文案");
        title.setTextSize(20f);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        hint.setText("点一条改文字；保存后打开 MCTGA 即生效。文件：" + OVERRIDE);
        hint.setPadding(0, dp(6), 0, dp(10));
        root.addView(hint);

        final EditText search = new EditText(this);
        search.setHint("搜索原文或新文案");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter = s.toString().trim();
                refreshList();
            }
            public void afterTextChanged(Editable s) { }
        });
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button reload = new Button(this);
        reload.setText("重载");
        reload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reload();
                toast("已重载");
            }
        });
        Button clear = new Button(this);
        clear.setText("清除全部覆盖");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("清掉 texts.json 里所有覆盖，恢复默认文案？")
                        .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                overrides.clear();
                                writeOverride();
                                reload();
                                toast("已清除");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        bar.addView(reload);
        bar.addView(clear);
        root.addView(bar);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, shown);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < shown.size()) edit(shown.get(pos));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void reload() {
        defaults.clear();
        overrides.clear();
        readMap(DEFAULTS, defaults);
        readMap(OVERRIDE, overrides);
        if (defaults.isEmpty()) toast("读不到 " + DEFAULTS + "（检查“所有文件访问”权限）");
        refreshList();
    }

    private void refreshList() {
        shown.clear();
        List<String> keys = new ArrayList<String>(defaults.keySet());
        for (String k : overrides.keySet()) if (!defaults.containsKey(k)) keys.add(k);
        Collections.sort(keys);
        for (String k : keys) {
            String v = overrides.containsKey(k) ? overrides.get(k) : defaults.get(k);
            String line = (overrides.containsKey(k) ? "● " : "○ ") + k + "  →  " + v;
            if (filter.length() == 0 || line.contains(filter)) shown.add(line);
        }
        adapter.notifyDataSetChanged();
        if (shown.isEmpty()) toast("没有匹配项（或还没导入默认文案）");
    }

    private void edit(final String row) {
        String key = row.substring(2, row.indexOf("  →  "));
        final String cur = overrides.containsKey(key) ? overrides.get(key) : defaults.get(key);
        final EditText et = new EditText(this);
        et.setText(cur);
        et.setSingleLine(false);
        et.setMinLines(2);

        new AlertDialog.Builder(this)
                .setTitle(key)
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = et.getText().toString();
                        if (v.equals(defaults.get(key))) overrides.remove(key);
                        else overrides.put(key, v);
                        writeOverride();
                        refreshList();
                        toast("已保存，回到 MCTGA 生效");
                    }
                })
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        overrides.remove(key);
                        writeOverride();
                        refreshList();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void readMap(String path, Map<String, String> out) {
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() == 0) return;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            JSONObject o = new JSONObject(new String(b, 0, off, "UTF-8"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.put(k, o.optString(k, ""));
            }
        } catch (Throwable t) {
            toast("读取失败：" + t);
        }
    }

    private void writeOverride() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : overrides.entrySet()) o.put(e.getKey(), e.getValue());
            File dir = new File(DIR);
            if (!dir.isDirectory()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(OVERRIDE);
            out.write(o.toString(2).getBytes("UTF-8"));
            out.close();
        } catch (Throwable t) {
            toast("保存失败：" + t);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
