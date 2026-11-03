# 图标来源

`icon_source.png` 是启动图标的原图（1080×1080）。

切图方法（开发期一次性操作，不进 APK）：

1. 把原图放进 `module/assets/` 或临时的 IconMake 工具里，切成 5 档：
   - `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`（48dp 基准，满幅裁切）
   - `mipmap-*/ic_launcher_foreground.png`（108dp 画布，图片缩到 74dp 居中）
   - `mipmap-*/ic_launcher_background.png`（同图满幅裁切，作为底）
2. `res/mipmap-anydpi/ic_launcher.xml`、`ic_launcher_round.xml` 引用上面两个图层。
3. `AndroidManifest.xml` 的 `<application>` 挂 `android:icon` / `android:roundIcon`。

换图时按同样规则重新生成五个目录里的 15 张 PNG 即可，不需要改 Manifest。
