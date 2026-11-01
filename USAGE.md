# Make ColorOS Themes Great Again (MCTGA) · 使用说明
==================================

> **项目守则**
>
> - 不要向任何主题作者催促、讨要适配本模块的深色主题，否则此项目停更删库。
> - 严禁倒卖。倒卖我会挂到 GitHub，酷安上“光宗耀祖”。

对应模块版本：MCTGA 0.2.1（versionCode 53）
适用系统：ColorOS 17（Android 17 / SDK 37）
主题文件：美游艾德费尔特ColorOS17主题_dark_v7.theme（本说明以它为例，其他 .theme 同理）

────────────────────────────

一、这个模块是干什么的

ColorOS 的主题引擎在深色模式下会跳过主题资源：夜间不注入主题包、不读 drawable-night-* 目录、
下拉的控制中心磁贴还用系统默认色。结果是「主题装了，但深色模式里看不出效果」。

本模块用 LSPosed 在目标应用进程里把主题资源补回去，并额外做两件事：
1. 让 13 个应用的窗口背景能透出主题插画（日夜分别是 bg_day / bg_night）；
2. 修掉深色模式下控制中心未激活磁贴发白、切换深浅色后磁贴一半白一半深灰的问题。

────────────────────────────

二、前提条件（缺一不可）

1. ColorOS 17。ColorOS 15/16 不兼容，因为 hook 点基于 C17 的 oplus-framework.jar。
2. Root：KernelSU 或 Magisk。
3. LSPosed（Zygisk 版）。
4. 主题包 `.theme` 文件（本仓库/Release 不提供主题包，需要单独获取）。

────────────────────────────

三、安装步骤

1. 安装 `CosNightTheme-0.2-fix.apk`。

2. 给模块授权 root
   打开 KernelSU / Magisk，找到包名 `xwdy.deepseek.test` 的应用并授权。
   没授权时模块里的「一键生效」会提示「没有拿到 root 权限」。

3. LSPosed 里启用模块
   LSPosed 管理器 → 模块 → 勾选 CosNightTheme → 作用域里勾选下表 20 个包
   （只勾你需要的也行，不勾的界面不会有任何变化）。

4. 导入并应用主题
   打开主题商店 → 我的 → 主题 → 导入本地主题（选那个 `.theme`）→ 应用。

5. 打开本模块
   - 点「选择主题文件 (.theme)」，选同一个 `.theme`；
     选完会自动把主题里所有 `com.*` 应用包补齐到 `/data/theme`（自动跳过 `_004`、`_007` 这类版本变体）。
   - 再点「一键生效」，它会重启所有适配应用 + SystemUI（屏幕会闪一下，属正常）。

6. 完成
   深色模式下进设置、时钟、相册等界面即可看到主题效果。

────────────────────────────

四、作用域 20 个包（勾选清单）

主题资源（配色/图标/形状）
- com.android.settings          设置
- com.android.systemui          通知栏 / 控制中心
- com.android.contacts          联系人 / 拨号
- com.android.mms               信息
- com.coloros.alarmclock        时钟
- com.coloros.calendar          日历
- com.coloros.soundrecorder     录音
- com.coloros.filemanager       文件管理
- com.coloros.gallery3d         相册
- com.coloros.video             视频（系统无入口，无法验证）
- com.coloros.digitalwellbeing  数字健康
- com.coloros.familyguard       家人守护
- com.heytap.cloud              云服务
- com.heytap.health             健康
- com.heytap.themestore         主题商店
- com.coloros.shortcuts         快捷指令
- com.heytap.speechassist       小布助手
- com.oplus.aimemory            AI 记忆
- com.oplus.tips                使用技巧
- com.android.stk               SIM 卡工具（启动即退出，无法验证）

其中 13 个额外带「插画背景透出」：
时钟、录音、日历、文件管理、相册、家人守护、健康、云服务、数字健康、小布助手、AI 记忆、使用技巧、快捷指令

────────────────────────────

五、日常使用

· 换深浅色模式
  控制中心或 设置 → 显示与亮度 切换即可。模块会自动重建 SystemUI，
  所以切完屏幕会黑一下再回来，属于正常现象，不用手动重启。

· 主题更新了怎么办
  重新在模块里「选择主题文件」选新包（会自动补齐），再点「一键生效」。

· 模块版本更新了怎么办
  覆盖安装后打开模块，点一次「一键生效」；如果界面没变化，手动重启一次目标应用。

· 想知道主题有没有生效
  root 下确认 `/data/theme/com.android.settings` 是否存在；
  模块里点「一键生效」时如果提示「检测到主题还没有应用」，说明主题商店里还没应用成功。

────────────────────────────

六、常见问题

Q：某些界面顶部有一块黑色？
A：0.2 已修掉时钟（闹钟页 + 世界时钟/秒表/计时器）、设置等页面的顶部黑块。
   如果还有个别第三方界面出现，用 `setprop debug.cosnight.dump 1` 让模块在 logcat
   打印视图树（含 z 序和背景类型），把日志发出来就行。

Q：进某个页面会先黑一下再变正常？
A：0.2 已改为首帧就设置背景，正常不会再有。若仍出现，多半是该应用自身启动画面。

Q：相册编辑界面变透明、按钮看不清？
A：这是有意排除的——类名含 `editor` 的 Activity 一律跳过（照片编辑、扩图、设为壁纸、视频编辑）。

Q：控制中心未激活的磁贴是白的？
A：0.2 已处理（换成深色版 `status_bar_qs_tile_bg_inactive`）。
   如果切完深浅色后还残留，下拉一次通知栏或重启 SystemUI 即可。

Q：主题商店里「免费试用」5 分钟后自动重置？
A：官方机制，模块无法绕过，请正常购买/使用。

Q：主题商店打不开 / 显示「已暂停」？
A：被禁用或挂起过。用 root 执行 `pm enable com.heytap.themestore`
   和 `pm unsuspend com.heytap.themestore` 恢复。

Q：健康、云服务、数字健康的插画只露一点点？
A：这三个应用自己有较厚的不透明卡片和浅色蒙层，插画只能在边缘露出，属已知限制。

Q：日历、文件管理、相册里有些白字看不清？
A：这几个应用是整页自绘底色，模块用深度清理把底色清掉了才能透出插画，
   代价是个别白色文字压在插画上对比度偏低。

Q：视频、SIM 卡工具没效果？
A：视频在 ColorOS 17 里没有独立入口（被相册接管），SIM 卡工具启动即退出，两者都无法验证。

────────────────────────────

七、回滚 / 卸载

· 只想临时关掉：LSPosed 里取消勾选模块（或在作用域里去掉对应应用）→ 重启相关应用。
· 想彻底恢复默认主题：主题商店里换回默认主题；再卸载本模块。
· 模块不会修改任何系统文件，`/data/theme` 下的内容由主题引擎自己管理。

────────────────────────────

八、排错用的调试开关

    su -c "setprop debug.cosnight.dump 1"

开启后重启目标应用，模块会在 logcat（tag: CosNightTheme）打印 DecorView 的完整视图树：
层级、类名、id、bounds、可见性、alpha、elevation、背景类型与颜色。
看完记得关掉：

    su -c "setprop debug.cosnight.dump 0"
