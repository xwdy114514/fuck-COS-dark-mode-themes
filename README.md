# fuck COS dark mod theme

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

## 功能

- 夜间强制加载 `/data/theme/<包名>` 主题资源
- 把 `res/drawable*` 请求重定向到 `res/drawable-night-xxhdpi/`
- 移除设置首页 AppBar 三层背景（消除顶部黑块）
- 模块内提供配置界面：
  - 选择 `.theme` 文件（通过 SAF，路径持久化）
  - 补齐 `com.android.contacts` / `com.android.mms` 到 `/data/theme`
  - 重启 SystemUI / 设置 / 联系人 / 短信

## 原理（hook 点）

| 类 | 方法 | 作用 |
|---|---|---|
| `android.content.res.OplusThemeResourcesPackage` | `getThemeResources(IResourcesImplExt, String)` | 夜间补回主题 zip |
| `android.content.res.OplusThemeZipFile` | `getInputStreamInner(int, String, MultiZipFile, boolean)`、`getInputStream(int, String)` | 路径重定向到 `drawable-night-xxhdpi` |
| `com.oplus.settings.feature.homepage.OplusSettingsHomepageActivity` | `onCreate` / `onResume` | 清 AppBar 背景 |

## 构建

需要：Termux + OpenJDK 17 + `aapt` + `d8` + `apksigner`，以及：

- `android.jar`（android-35）
- `XposedBridgeAPI-82.jar`

```bash
cd module
./build.sh
```

生成 `signed.apk`，安装后用 LSPosed 启用，作用域：

```
com.android.settings
com.android.systemui
com.android.contacts
com.android.mms
```

## 使用（必须先勾选作用域）

1. 安装模块，并在 LSPosed 中勾选作用域：com.android.settings、com.android.systemui、com.android.contacts、com.android.mms
2. 打开 CosNightTheme → 选择 `.theme` 文件
3. 正常应用主题（主题商店试用）
4. 点“补齐 contacts/mms 并重启应用”
5. 下拉一次控制中心（或重启 SystemUI）

## 已知限制

- 主题引擎不会自动解压 contacts/mms，每次重新应用主题后需点一次“补齐”
- 控制中心面板背景不是主题资源，只能显示系统模糊层
- AppBar 顶部黑块在首次打开时可能闪现，切后台回来会消失

## 免责声明

仅供学习与研究。修改主题/系统资源存在风险，请自行备份并承担后果。

## License

本项目采用 **GPL-3.0 + Commons Clause**：

- 基于本项目的修改/衍生作品必须继续开源（GPL-3.0 copyleft）
- 不得销售本软件，或销售其价值主要来自本软件功能的产品/服务（Commons Clause）

完整条款见 `LICENSE` 文件。
