package xwdy.deepseek.test;

import android.content.pm.PackageManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 通道：shell 身份（uid 2000），可以读写 /storage/emulated/0/Android/data，
 * 也是锁定 / 解锁主题商店唯一可用的身份。
 */
public final class ShizukuBridge {

    public static final int REQ_PERMISSION = 100;

    private ShizukuBridge() {
    }

    public static boolean connected() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean granted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String state() {
        try {
            if (!Shizuku.pingBinder()) return "服务未连接（先启动 Shizuku）";
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return "已授权";
            return "未授权";
        } catch (Throwable t) {
            return "不可用（" + t.getClass().getSimpleName() + "）";
        }
    }

    /** 起一个远端 shell 进程；shell() 与 process() 共用这一处反射，别再各写一遍。 */
    private static Process newProcess(String command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        try {
            return (Process) method.invoke(null,
                    new Object[]{new String[]{"sh", "-c", command}, null, null});
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("远端进程启动失败", cause);
        }
    }

    public static ShellRunner.Result shell(final String command, long timeoutMs) {
        return ShellRunner.run(new ShellRunner.Factory() {
            @Override
            public Process start(String cmd) throws Exception {
                return newProcess(cmd);
            }
        }, command, timeoutMs);
    }

    /** 起一个 Shizuku shell 进程（stdout 当字节流用），用于把外部文件流式读进 app 缓存。 */
    public static Process process(final String command) throws Exception {
        return newProcess(command);
    }
}
