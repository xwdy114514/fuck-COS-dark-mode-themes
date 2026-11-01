# 构建工具链准备（另一台 Android 设备）

本项目在**手机本地**编译，不需要电脑。需要下面四样东西。

## 1. 目录约定（开发机上现成的路径）

```
/data/local/tmp/tc/prefix/
├── bin/aapt2                       # aarch64 的 aapt2
├── share/java/d8.jar               # R8/D8
├── share/java/apksigner.jar        # apksigner
├── lib/                            # JDK 依赖的 so（libz.so.1 等），必须进 LD_LIBRARY_PATH
└── lib/jvm/java-21-openjdk/        # JDK 21 (aarch64)
/data/local/tmp/sdk/android-35/android.jar
```

`build.sh` 支持用环境变量覆盖这些路径：

```sh
export TOOLCHAIN_PREFIX=/data/local/tmp/tc/prefix   # 覆盖前缀
export ANDROID_JAR=/data/local/tmp/sdk/android-35/android.jar
export XPOSED_API=/path/to/XposedBridgeAPI-82.jar    # 本包 tools/ 里自带
export KEYSTORE=/path/to/eta.keystore                # 本包 tools/ 里自带
```

## 2. JDK 21（必需）

- 必须是 **aarch64** 版本（Android 上跑不了 x86 版）
- 只需要 `java / javac / jar / keytool`
- 运行时需要 `LD_LIBRARY_PATH` 指向 `lib/`，否则会报 `library "libz.so.1" not found`

## 3. aapt2 / d8 / apksigner

- `aapt2`：aarch64 可执行文件，用来编译资源、链接 APK
- `d8.jar` / `apksigner.jar`：纯 Java，用 `java -cp ...` 或 `java -jar ...` 运行
- 注意：有些打包版里的 `d8`、`apksigner` 是包装脚本，shebang 指向 Termux 路径，直接执行会失败。
  `build.sh` 已经绕开：`java -cp $TOOLCHAIN/share/java/d8.jar com.android.tools.r8.D8 ...`

## 4. android.jar（API 35）

- 从 Android SDK `platforms/android-35/android.jar` 取
- 只作编译/链接用（`aapt2 link -I`、`javac -classpath`、`d8 --lib`）

## 5. 自检

```sh
export LD_LIBRARY_PATH=/data/local/tmp/tc/prefix/lib
/data/local/tmp/tc/prefix/lib/jvm/java-21-openjdk/bin/java -version
/data/local/tmp/tc/prefix/bin/aapt2 version
sh build.sh     # 结尾应打印 完成：out/MCTGA-0.5.4-beta.apk
```

## 6. 签名说明

`tools/eta.keystore`（alias `eta`，口令 `etatata`）与当前已装版本是同一张证书。
**不要重新生成 keystore**，否则覆盖安装会报 `INSTALLED_FAILED_UPDATE_INCOMPATIBLE`，
而且 LSPosed 作用域、KernelSU 授权都要重做。
