package xwdy.deepseek.test;

/**
 * Root 通道：写 /data/theme、重启适配应用、读取主题目录状态。
 * 注意：锁定 / 解锁商店只需要 shell 身份（Sui / Shizuku），root 只是为了让这些操作也能在没有 Shizuku 时工作。
 */
public final class RootShell {

    /** Sui 会把 su 从被注入进程的挂载命名空间里隐藏，所以要按绝对路径逐个试。 */
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/debug_ramdisk/su", "/sbin/su", "su"
    };

    private RootShell() {
    }

    public static ShellRunner.Result shell(final String command, long timeoutMs) {
        return ShellRunner.run(new ShellRunner.Factory() {
            @Override
            public Process start(String cmd) throws Exception {
                Exception last = null;
                for (String su : SU_PATHS) {
                    try {
                        return new ProcessBuilder(su, "-c", cmd).start();
                    } catch (Exception e) {
                        last = e;
                    }
                }
                throw last != null ? last : new java.io.IOException("su not found");
            }
        }, command, timeoutMs);
    }

    public static boolean available() {
        ShellRunner.Result r = shell("id -u", 10000);
        return r.ok() && "0".equals(r.out.trim());
    }
}
