# MCTGA · ColorOS 主题增强

让 ColorOS 17 在深色模式下也能加载 `.theme` 主题，并补齐主题引擎不会解压的联系人 / 短信主题包。

- 包名：`xwdy.deepseek.test`
- 版本：`0.4`（versionCode 152）
- 系统：ColorOS 11+ / realme UI（深色适配仍只在 ColorOS 17 上启用）

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

- ColorOS 11+；深色适配要求 ColorOS 17（`ro.build.version.oplusrom` 以 `V17` 开头）
- LSPosed（Zygisk）
- Root（KernelSU / Magisk）或已启动的 Shizuku

## 安装

1. 安装 `out/MCTGA-0.4.apk`
2. LSPosed 中启用模块，作用域按 `module/res/values/arrays.xml` 勾满 **20 个包**
3. 给 MCTGA 授予 Root（KernelSU / Magisk）或 Shizuku 授权

> 没有 Root / Shizuku 时，「附加 · 深色适配」整块会隐藏并提示「没有 lsposed 和 root，无法使用」。

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

`resources.arsc` 必须是 STORED 且 4 字节对齐。构建时设置：

```sh
javac -d /path/to/align tools/Align.java
export ALIGN_CLASSES=/path/to/align
```

`build.sh` 会在签名前调用 `Align`（`tools/Align.java`），把 `resources.arsc` / `.so` 保持 STORED 并用 0xD935 额外字段对齐。
