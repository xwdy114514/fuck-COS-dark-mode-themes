import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;

/** Extract complete constant UI messages, never arbitrary source fragments or shell commands. */
public final class CopyCatalog {
    private static final Set<String> CALLS = new HashSet<>(Arrays.asList(
        "setText", "setTitle", "setMessage", "setPositiveButton", "setNegativeButton", "setNeutralButton",
        "toast", "button", "row", "section", "action", "member", "link", "paint", "showHint", "showShareable", "disclosure"));
    private static final Set<String> RETURNS = new HashSet<>(Arrays.asList(
        "guideTitle", "guideBody", "guideButtonText", "darkSwitchText", "hintFor", "describe", "state"));
    private static final TreeSet<String> values = new TreeSet<>();
    static String literal(ExpressionTree e) {
        if(e instanceof LiteralTree) { Object v=((LiteralTree)e).getValue(); return v instanceof String?(String)v:null; }
        if(e instanceof BinaryTree && e.getKind()==Tree.Kind.PLUS) {
            String l=literal(((BinaryTree)e).getLeftOperand()), r=literal(((BinaryTree)e).getRightOperand());
            return l==null||r==null?null:l+r;
        }
        return null;
    }
    static void add(ExpressionTree e) {
        if(e instanceof ConditionalExpressionTree) { add(((ConditionalExpressionTree)e).getTrueExpression());add(((ConditionalExpressionTree)e).getFalseExpression());return; }
        String s=literal(e);
        if(s!=null && s.codePoints().anyMatch(c->c>=0x4e00&&c<=0x9fff) && !s.isBlank()) values.add(s);
    }
    static String q(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""; }
    public static void main(String[] args)throws Exception {
        Path root=Paths.get(args[0]);List<java.io.File> files=new ArrayList<>();
        for(String n:new String[]{"MainActivity","EnvCheck","ShizukuBridge"})files.add(root.resolve("module/src/main/java/xwdy/deepseek/test/"+n+".java").toFile());
        JavaCompiler c=ToolProvider.getSystemJavaCompiler();
        try(StandardJavaFileManager fm=c.getStandardFileManager(null,null,null)) {
            JavacTask task=(JavacTask)c.getTask(null,fm,null,Arrays.asList("-proc:none"),null,fm.getJavaFileObjectsFromFiles(files));
            for(CompilationUnitTree unit:task.parse()) new TreeScanner<Void,Void>() {
                String method="";
                @Override public Void visitMethod(MethodTree t,Void p) {String old=method;method=t.getName().toString();super.visitMethod(t,p);method=old;return null;}
                @Override public Void visitMethodInvocation(MethodInvocationTree t,Void p) {
                    String n=t.getMethodSelect().toString();n=n.substring(n.lastIndexOf('.')+1);
                    if(CALLS.contains(n))for(ExpressionTree arg:t.getArguments())add(arg);
                    return super.visitMethodInvocation(t,p);
                }
                @Override public Void visitReturn(ReturnTree t,Void p) {if(RETURNS.contains(method))add(t.getExpression());return super.visitReturn(t,p);}
                @Override public Void visitAssignment(AssignmentTree t,Void p) {if(t.getVariable().toString().equals("r.verdict"))add(t.getExpression());return super.visitAssignment(t,p);}
            }.scan(unit,null);
        }
        values.add("找到 %n 个候选主题文件，选择后前往商店应用：");
        values.add("切换深浅色时重启 SystemUI（可能黑屏）：开");
        values.add("切换深浅色时重启 SystemUI（可能黑屏）：关");
        values.add("；资源缺失可使用「深色适配」中的补齐功能。");
        StringJoiner j=new StringJoiner(",\n","{\n","\n}\n");for(String s:values)j.add("  "+q(s)+": "+q(s));
        Files.writeString(root.resolve("texts-defaults.json"),j.toString());
        System.out.println("COMPLETE_UI_KEYS="+values.size());
    }
}
