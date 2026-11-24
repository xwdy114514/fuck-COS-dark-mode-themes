package xwdy.deepseek.test;
import java.lang.reflect.*;
import java.util.*;
import android.view.View;
import android.widget.TextView;
public class Regression {
  static int checks;
  static void equal(Object expected,Object actual,String label) {
    if(!Objects.equals(expected,actual))throw new AssertionError(label+": expected="+expected+" actual="+actual);
    checks++; System.out.println("PASS "+label);
  }
  static void map(HashMap<String,String> m)throws Exception {Field f=Texts.class.getDeclaredField("map");f.setAccessible(true);f.set(null,m);}
  static void apply(TextView v)throws Exception {Method m=Texts.class.getDeclaredMethod("applyRec",View.class);m.setAccessible(true);m.invoke(null,v);}
  public static void main(String[] args)throws Exception {
    HashMap<String,String> m=new HashMap<>();m.put("状态：开","覆盖：开");m.put("状态：关","覆盖：关");map(m);
    TextView v=new TextView();Object ownTag=new Object();v.setTag(ownTag);v.setText("状态：开");apply(v);
    equal("覆盖：开",v.getText(),"initial override");equal(ownTag,v.getTag(),"preserve business tag");
    v.setText("状态：关");apply(v);equal("覆盖：关",v.getText(),"business text update");
    v.setVisibility(View.GONE);apply(v);equal(View.GONE,v.getVisibility(),"preserve business hidden");
    map(null);apply(v);equal("状态：关",v.getText(),"restore latest original on config removal");equal(View.GONE,v.getVisibility(),"config removal preserves business hidden");
    TextView a=new TextView();a.setText("隐藏");m.put("隐藏","");map(m);apply(a);equal(View.GONE,a.getVisibility(),"empty override hides");
    m.put("隐藏","恢复");apply(a);equal(View.VISIBLE,a.getVisibility(),"restore owned visibility");equal("恢复",a.getText(),"restore text");
    m.put("隐藏","");apply(a);map(null);apply(a);equal(View.VISIBLE,a.getVisibility(),"file removal restores owned visibility");equal("隐藏",a.getText(),"file removal restores original");
    for(String x:new String[]{"V17.0.0","17.0.0","ColorOS 17.0"})equal(17,OsVersion.parseDigits(x),"version "+x);
    equal(7,OsVersion.parseDigits("realme UI 7.0"),"realme version");equal(0,OsVersion.parseDigits(null),"null version");equal(0,OsVersion.parseDigits("unknown"),"unknown version");
    equal(true,EnvCheck.lspdRunning("ROOT:0\nLSPD:3530\n"),"daemon PID");equal(false,EnvCheck.lspdRunning("/data/adb/lspd\nLSPD:\n"),"directory alone not running");
    String text="/tmp/ColorOS ' quoted ; $(echo NOT_EXECUTED).theme";
    ShellRunner.Factory local=new ShellRunner.Factory(){public Process start(String cmd)throws Exception{return new ProcessBuilder("/system/bin/sh","-c",cmd).start();}};
    ShellRunner.Result r=ShellRunner.run(local,"printf %s "+ThemeEngine.quote(text),3000);equal(true,r.ok(),"quoted command success");equal(text,r.out,"shell quote roundtrip");
    r=ShellRunner.run(local,"echo fail >&2; exit 7",3000);equal(7,r.code,"nonzero exit preserved");equal(false,r.ok(),"nonzero is not success");
    r=ShellRunner.run(local,"sleep 2",150);equal(true,r.timedOut,"bounded timeout");
    System.out.println("ALL_PASS checks="+checks);
  }
}
