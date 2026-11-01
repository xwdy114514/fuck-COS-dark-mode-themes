# fuck COS dark mod theme

> **AI 生成声明**
>
> 本项目的模块源码（`module/`）、辅助工具（`tools/`）、README 与 Release 说明均由 AI 助手生成，所用模型为 `deepseek-flash`（Eta）。
> 作者负责提出需求、在 ColorOS 17 真机上测试与发布，不对代码的完整性与安全性作额外保证；使用前请自行审阅代码。

> **免责声明**
>
> 本项目仅供个人学习、研究与技术交流使用，不提供任何形式的保证。
>
> - 使用本模块需要 Root 与 LSPosed，可能修改系统主题行为，存在导致系统异常、主题丢失甚至无法开机的风险，**使用风险完全由使用者自行承担**。
> - 本项目不包含、也不分发任何第三方主题包（`.theme`）、系统 APK 或反编译的系统代码。
> - 修改系统主题可能违反设备厂商的用户协议，请自行评估并遵守当地法律法规。
> - 作者不对因使用本项目造成的任何直接或间接损失负责。

让 ColorOS 17 在深色模式下也能加载 `.theme` 主题（含主题内 `drawable-night` 资源）的 LSPosed 模块。

## 背景

ColorOS 的主题引擎在深色模式下会跳过主题资源：

- `android.content.res.OplusBaseFile.rejectTheme()` 的夜间白名单只有 `icons`、`lockscreen`、`com.android.launcher`、`com.coloros.alarmclock`
- `OplusThemeResourcesPackage.getThemeResources()` 在夜间不会把主题 zip 注入进程
- 主题引擎应用主题时不会解压 `com.android.contacts` / `com.android.mms`，且不读取 `drawable-night-*` 目录

## 已适配界面

| 界面 | 包名 |
|---|---|
| 设置 | `com.android.settings` |
| 通知栏 / 控制中心（SystemUI） | `com.android.systemui` |
| 联系人 / 拨号 | `com.android.contacts` |
| 信息 | `com.android.mms` |
| 文件管理 | `com.coloros.filemanager` |
| 时钟 | `com.coloros.alarmclock` |
| 日历 | `com.coloros.calendar` |
| 录音 | `com.coloros.soundrecorder` |
| 相册 | `com.coloros.gallery3d` |
| 视频 | `com.coloros.video` |
| SIM 卡工具 | `com.android.stk` |
| 数字健康 | `com.coloros.digitalwellbeing` |
| 家庭守护 | `com.coloros.familyguard` |
| 快捷指令 | `com.coloros.shortcuts` |
| 云服务 | `com.heytap.cloud` |
| 健康 | `com.heytap.health` |
| 小布助手 | `com.heytap.speechassist` |
| 主题商店 | `com.heytap.themestore` |
| AI 记忆 | `com.oplus.aimemory` |
| 使用技巧 | `com.oplus.tips` |

> 全屏内容型应用（相机、游戏空间、钱包）背景基本看不到，主题主要影响其配色；不想要可以在 LSPosed 作用域里取消勾选。

## 功能

- 夜间强制加载 `/data/theme/<包名>` 主题资源
- 把 `res/drawable*` 请求重定向到 `res/drawable-night-xxhdpi/`
- 移除设置首页 AppBar 三层背景（消除顶部黑块）
- 日夜配色分离：日间把 SystemUI 的 `notification_scrim_bg_mask_color`、`notification_material_blur_bg` 改写为浅色，夜间沿用主题值
- 深浅色切换时自动重建 SystemUI，避免主题 drawable 缓存导致磁贴一半白一半深灰
- 模块内提供配置界面：
  - 选择 `.theme` 文件（通过 SAF，路径持久化）
  - 一次性补齐主题内所有 `com.*` 包到 `/data/theme`（自动跳过 `_004`、`_007` 这类版本变体）
  - 重启全部适配应用 + SystemUI

## 原理（hook 点）

| 类 | 方法 | 作用 |
|---|---|---|
| `android.content.res.OplusThemeResourcesPackage` | `getThemeResources(IResourcesImplExt, String)` | 夜间补回主题 zip |
| `android.content.res.OplusThemeZipFile` | `getInputStreamInner(int, String, MultiZipFile, boolean)`、`getInputStream(int, String)` | 路径重定向到 `drawable-night-xxhdpi` |
| `com.oplus.settings.feature.homepage.OplusSettingsHomepageActivity` | `onCreate` / `onResume` | 清 AppBar 背景 |
| `android.content.res.Resources` | `getColor(int)`、`getColor(int, Theme)` | 日间改写 SystemUI 遮罩色 |
| `android.app.ActivityThread` | `handleConfigurationChanged` | 检测深浅切换并重建 SystemUI |

## 构建

需要：Termux + OpenJDK 17 + `aapt` + `d8` + `apksigner`，以及：

- `android.jar`（android-35）
- `XposedBridgeAPI-82.jar`

```bash
cd module
./build.sh
```

生成 `signed.apk`，安装后用 LSPosed 启用并勾选上表 20 个作用域。

## 使用

1. 安装模块，在 LSPosed 中勾选 20 个作用域
2. 打开模块 → 选择 `.theme` 文件（会自动把主题内所有包补齐到 `/data/theme`）
3. 到主题商店应用主题
4. 回到模块点“一键生效”
5. 切换深浅色时 SystemUI 会短暂重建，属正常现象

## 已知限制

- 首次使用需在 KernelSU / Magisk 中给本模块 root 授权；未授权时点“一键生效”会提示“没有拿到 root 权限”，而不是误报主题未应用
- 主题引擎不会自动解压 contacts/mms，重新应用主题后需要再点一次“补齐”（选定主题文件时自动执行）
- 主题只有一份 `assets/colors.xml`，颜色的深浅分离依赖模块 hook；不启用模块时亮色下背景仍偏深
- 深浅色切换后 SystemUI 会被强制重建（约闪一下），用于清掉主题 drawable 缓存
- AppBar 顶部黑块在首次打开时可能闪现，切后台回来会消失
- ColorOS 15 不兼容（hook 点基于 C17 的 oplus-framework.jar）

## License

本项目采用 **GPL-3.0 + Commons Clause**：

- 基于本项目的修改/衍生作品必须继续开源（GPL-3.0 copyleft）
- 不得销售本软件，或销售其价值主要来自本软件功能的产品/服务（Commons Clause）

完整条款见 `LICENSE` 文件。

## Known Issues

- 设置首页首次打开时顶部可能闪一下黑块，切后台再回来即恢复
- 每次重新应用主题后，`com.android.contacts` / `com.android.mms` 需要重新点一次“补齐”
- 切换深浅色会导致 SystemUI 重建，屏幕会短暂闪烁
