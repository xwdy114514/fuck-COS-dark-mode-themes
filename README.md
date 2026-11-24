# MCTGA · ColorOS 主题增强

让 ColorOS 17 在深色模式下也能加载 `.theme` 主题，并补齐主题引擎不会解压的联系人 / 短信主题包。

- 包名：`xwdy.deepseek.test`
- 版本：`0.5.8`（versionCode 228）；云控配置带 ECDSA 签名，改完配置跑 `tools/sign-cloud.sh --upload`
- 系统门槛：ColorOS 11+ / realme UI 2.0+；跨版本兼容仍需对应设备验证，不能把本机测试等同全机型通过。

## 功能

| 功能 | 说明 | 依赖 |
|---|---|---|
| 深色模式主题适配 | 夜间强制加载主题 zip、`drawable` 重定向到 `drawable-night-xxhdpi` | LSPosed |
| 顶栏黑块清理 | 清理 appbar / toolbar / header 背景，拦截 `setBackground*` | LSPosed |
| 联系人 / 短信补齐 | 从 `.theme` 取对应主题包，写入应用数据目录，模块注入目标进程；**不改 `/data/theme`** | LSPosed + Root/Shizuku |
| 交给主题商店 | 选 `.theme` 后复制到 `Download/MCTGA` 并打开商店详情页，由商店写入 | 无 |
| 主题锁定 / 解锁 | `Themes` 空文件 + `pm suspend`，防止试用到期恢复默认 | Root / Shizuku |
| 诊断 | 一键收集状态与通道输出，可复制 / 分享 | 无 |

## 环境要求

- ColorOS 11+ / realme UI 2.0+，由 `OsVersion` 统一判定
- LSPosed（Zygisk）
- Root（KernelSU / Magisk）或已启动的 Shizuku

## 安装

1. 安装 `out/MCTGA-0.5.8.apk`（或在工程根目录执行 `sh install.sh`）
2. LSPosed 中启用模块，作用域按 `module/res/values/arrays.xml` 勾满 **22 个包**
3. 给 MCTGA 授予 Root（KernelSU / Magisk）或 Shizuku 授权

> 「附加 · 深色适配」仅在 Root 与 LSPosed 守护进程探测就绪时显示；无密码门。模块启用和作用域是否正确还需在 LSPosed 确认。

## 使用

1. 在 MCTGA 选 `.theme` → 交给主题商店 → 商店里点「免费试用」
2. 回到 MCTGA 点「补齐联系人/短信主题包」，让深色模式下的联系人 / 短信也套上主题
3. 需要防止试用到期恢复默认时点「锁定主题」；换主题前先「解锁」

## 构建

```sh
export TOOLCHAIN_PREFIX=/path/to/toolchain   # bin/aapt2、share/java/d8.jar、apksigner.jar、JDK
export ANDROID_JAR=/path/to/android-35/android.jar
export XPOSED_API=$PWD/tools/XposedBridgeAPI-82.jar
export KEYSTORE=$PWD/tools/eta.keystore       # 必须沿用，否则无法覆盖安装
sh build.sh
```

产物名取自 `module/AndroidManifest.xml` 的 `versionName`，输出到 `out/`。

## 已知限制

- 每次重新应用主题后需要再点一次「补齐联系人/短信主题包」
- 主题商店的「默认主题」详情页没有对外入口，只能打开「我的资源」列表再点进去
- 控制中心面板背景不是主题资源，只能显示系统模糊层
- 主题包按 `themeOsVersion` 选变体；变体缺 `-night` 时会从同名基础包补同名夜间图

## 许可

GPL-3.0 + Commons Clause，见 `LICENSE`。基于 **fuck COS dark mod theme** 继续开发；
修改 / 衍生作品必须继续开源，不得销售。

### 对齐（Android 11+ 安装必需）

`resources.arsc` 必须是 STORED 且 4 字节对齐。0.5.5 起主包 `build.sh` 自动编译并运行 `tools/Align.java`，不再依赖手工设置 `ALIGN_CLASSES`。

### 0.5.5 整理与验证

- 构建前统一校验 `tools/toolchain.env`；缺原密钥会停止，不会自动换证书。`D8_JAR` 可指向同哈希的 9.4.34 文件。
- `sh tests/run.sh`：24 项隔离逻辑回归；它使用最小 Android View 替身，不代表真机 UI 全面通过。

### 0.5.6 无主题时停用注入

必须在 /data/theme 根目录找到真实有效的应用主题 ZIP 才允许注入；配置文件、残留 UUID、补齐缓存和下载的 .theme 不能单独激活。默认主题/状态无法确认时走原生界面。切换主题或更新模块后重新打开目标应用；已运行进程中的旧资源不会凭空还原。不主动重启 SystemUI。

## 版本记录（0.5.2 → 0.5.8）

| 版本 | versionCode | 时间 | 要点 |
|---|---|---|---|
| 0.5.2 | 222 | 2026-11-05 | 撤销深色适配的密码门：删除连点版本号入口、`DARK_SALT/DARK_HASH`、`dark_unlocked`；深色卡片改为 Root + LSPosed 就绪即可见（仅一个判据） |
| 0.5.3 | 223 | 2026-11-06 | 云控配置加 ECDSA P-256 验签 + `seq` 防重放；`tools/CloudSign.java`、`tools/sign-cloud.sh`。内部冒烟包，无独立发布 APK |
| 0.5.4 | 224 | 2026-11-06 | 验签正式生效；云控编辑器 1.1→1.3（「签名并发布」、su 多路径探测、无 root 时落文件 + 剪贴板兜底）；响应体限 64KiB |
| 0.5.5 | 225 | 2026-11-18 | 第一轮保守整理：`Texts` 弱引用状态、RootShell/quote 统一、生命周期与销毁保护、删三个自递归 Hook、`build-common.sh` 统一校验、24 项隔离回归 |
| 0.5.6 | 226 | 2026-11-18 | 新增 `AppliedTheme` 门槛：只有 `/data/theme` 根目录真实有效的 `com.*` ZIP 才允许注入；旧缓存、下载的 `.theme`、残留 UUID 都不算 |
| 0.5.7 | 227 | 2026-11-18 | 主应用文案统一：152 种/154 处字符串，默认文本表 163 项（`tools/CopyCatalog.java` 生成），旧覆盖备份迁移 |
| 0.5.8 | 228 | 2026-11-18 | 现有单页 UI 优化：设备/手动操作/更多选项/帮助改为可折叠区，按钮自适应高度 ≥48dp，窄屏 <380dp 与 fontScale >1.15 走纵向分支，辅助文字对比度提升；业务逻辑未改 |

完整逐版本改动由维护者单独记录，不随仓库发布；各版本测试范围见对应 Release 说明。
