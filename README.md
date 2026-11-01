# MCTGA · ColorOS 主题增强（融合版）

把两个项目合成一个 APK：

| 能力 | 来源 | 依赖 |
|---|---|---|
| 深色模式下强制加载主题资源、13 个应用透出主题插画、SystemUI 配色修正 | MCTGA 0.2.1（LSPosed 模块） | LSPosed（Root） |
| 安装 `.theme` 到 `/data/theme`、一键生效（重启适配应用 + SystemUI） | MCTGA 0.2.1 | Root |
| 主题商店锁定 / 解锁（空文件替换 + `pm suspend`），挡住试用到期恢复 | ThemeLock 2.2-rc5 | Shizuku（shell 身份） |

- 包名：`xwdy.deepseek.test`（沿用 MCTGA，LSPosed 作用域与 Root 授权无需重做）
- 版本：`0.3-beta`（versionCode 97）

> **警告：向 `/data/theme` 写入文件可能导致手机无法开机。** 本应用只在用户主动点
> 「选择并安装 .theme」并确认风险后才会写，且会逐个 `unzip -t` 校验、先备份、
> `.tmp` 原子替换，并提供「回滚上一次安装」。
- 签名：`/data/local/tmp/cosnight/eta.keystore`（alias `eta`，口令 `etatata`），与 0.2.1 一致，可直接覆盖安装
- 作用域仍是原来 20 个包，`Hook.java` 的逻辑与 0.2.1 完全相同，未改动

## 目录

```
MCTGA/
├── build.sh                 一键构建 → out/MCTGA-0.4.3.apk
├── install.sh               复制到 /data/local/tmp 再 pm install
├── out/                     当前构建 + 界面截图
├── docs/融合说明.md          两项目如何拼在一起、身份/权限分工、界面与代码重构记录
└── module/
    ├── AndroidManifest.xml  LSPosed 元数据 + Shizuku provider + queries
    ├── res/values/arrays.xml     xposed_scope（20 个包）
    ├── assets/bg_day.png bg_night.png   插画底图（打包在 APK 根目录，Hook 用 classloader 读）
    ├── dependencies/shizuku-api/       api/provider/aidl/shared 四个 jar
    └── src/main/java/xwdy/deepseek/test/
        ├── Hook.java         LSPosed 入口（0.2.1 逻辑 + 深色适配开关）
        ├── MainActivity.java 控制台（分区布局 / 状态渲染 / 各动作）
        ├── EnvCheck.java     深色适配环境检测（Root 缺失时报“无法确认”）
        ├── ThemeEngine.java  锁定/解锁/冻结/解冻脚本 + 状态解析
        ├── ShellRunner.java  有界远端命令执行
        ├── ShizukuBridge.java Shizuku / Sui 通道
        └── RootShell.java    su 通道（多路径尝试）
```

## 构建

```sh
sh /storage/emulated/0/MCTGA/build.sh
sh /storage/emulated/0/MCTGA/install.sh     # 注意：不能直接 pm install /storage 下的 APK
```

工具链：`/data/local/tmp/tc/prefix`（JDK21 + aapt2 + d8 + apksigner）、
`/data/local/tmp/sdk/android-35/android.jar`、`/data/local/tmp/cosnight/XposedBridgeAPI-82.jar`。

编译注意：android.jar 的 `LambdaMetafactory` 缺 `metafactory`，javac 必须用 `--release 8`
（ThemeLock 的写法），不能用 `-bootclasspath android.jar`，否则 lambda 编译失败。

## 界面分区

- **主题常驻（主功能）**：状态卡（系统 / Root / Shizuku / 商店 / Themes / 商店状态 / 权限通道 / Sui / 主题文件）、
  安装 .theme（**仅 Root 可用时显示**）、锁定主题、解锁、解冻/冻结商店、文件管理、刷新状态
  - 没有 Root 时：「安装 .theme」与「一键生效」隐藏，改为「打开文件管理导入 .theme」+ 说明
    （导入主题只能用文件管理：文件管理 → 点 .theme → 免费试用，不用主题商店的导入入口）
- **深色适配（附加功能）**：开关 + 环境检测结论 + 明细；下面是一键生效

环境检测（`EnvCheck.java`）用一次 root 探测给出四档结论：**可用 / 有限 / 待准备 / 不可用**
（判据：Root、`pidof lspd`、`/data/theme` 里的主题包数量、主题包内 `drawable-night` 条目数）。

开关状态存在 `Settings.Global` 的 `mctga_dark_attach`（0/1），Hook 启动时读取：
关掉后 Hook 直接返回，不挂任何 hook；改完要对目标应用「一键生效」才生效。

```sh
su -c "settings put global mctga_dark_attach 0"   # 关闭深色适配
su -c "settings put global mctga_dark_attach 1"   # 开启（默认）
```

## 使用顺序

1. 主题商店导入并应用 `.theme`（文件管理 → `.theme` → 免费试用）
2. 打开 MCTGA →「选择并安装 .theme」→ 自动补齐 `/data/theme`
3. 「一键生效」重启适配应用与 SystemUI（深色模式下 Hook 才生效）
4. 「锁定主题」挡住商店的试用到期恢复；换主题前先「解锁」
5. 装/更新本 APK 后，也要对目标应用执行一次「一键生效」

## 身份分工（重要）

- 写 `/data/theme`、`am force-stop`、`killall systemui` → **Root**
- 锁定 / 解锁商店（空文件替换）→ 需要能读写 `Android/data` 的身份：无线调试/ADB 启动的 **Shizuku**，或 Root
- 解冻 / 冻结商店（`pm suspend` / `unsuspend` / `enable`）→ **Sui 或 Shizuku 的 shell 身份就够**
- **Sui 会接管 `su`**：本机 Sui v13.5.4.3 下 app 进程里 `exec("su")` 报 ENOENT，Root 功能不可用；
  这时界面会明说，不会假装执行
- Shizuku 必须是**无线调试 / ADB** 启动；KernelSU 启动的是 ksu 身份，读不到 `Android/data`
- 不要卸载主题商店：会触发主题基础服务 `.uninstall.ThemeReceiver` 恢复默认主题

## 项目守则（发布必带）

1. 不要向任何主题作者催促、讨要适配本模块的深色主题，否则停更删库。
2. 严禁倒卖。

## 已知限制（与上游源码一致）

- 主题引擎不会自动解压 `com.android.contacts` / `com.android.mms`，每次重新应用主题后需要点一次「补齐主题资源（需 Root）」
- 控制中心面板背景不是主题资源，只能显示系统模糊层
- AppBar 顶部黑块首次打开时可能闪现，切后台回来会消失

## 来源与许可

- 深色适配部分源自 **fuck COS dark mod theme**（GPL-3.0 + Commons Clause），本工程沿用同一许可，完整条款见 `LICENSE`
- 基于本项目的修改 / 衍生作品必须继续开源；不得销售本软件，或销售其价值主要来自本软件功能的产品 / 服务

## 安装（发布版）

1. 安装 `out/MCTGA-0.3.apk`（需要 LSPosed，Android 12+）
2. LSPosed 里启用模块，作用域按 `module/res/values/arrays.xml`（20 个包）
3. 打开 MCTGA：授予 Root（补齐 / 重启用）或 Shizuku（锁定 / 解冻用）
4. 文件管理里点 `.theme` → 免费试用（由主题商店写入）
5. 回 MCTGA 点「补齐主题资源」：只写本应用数据目录，再由模块注入联系人 / 短信
6. 换主题后需要重新点一次「补齐主题资源」

## 构建（其他设备）

```sh
export TOOLCHAIN_PREFIX=/path/to/toolchain   # 需含 bin/aapt2、share/java/d8.jar、apksigner.jar、JDK
export ANDROID_JAR=/path/to/android-35/android.jar
export XPOSED_API=$PWD/tools/XposedBridgeAPI-82.jar
export KEYSTORE=$PWD/tools/eta.keystore
sh build.sh
```

产物：`out/MCTGA-0.3.apk`。签名必须沿用 `tools/eta.keystore`，否则覆盖安装会失败。
