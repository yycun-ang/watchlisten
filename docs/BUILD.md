# 从源码构建

## 环境

- JDK 17。
- Android SDK Platform 35 和 Build Tools 35.0.0。
- 使用仓库中的 Gradle Wrapper。
- Python 3，用于构建前生成矢量资源和运行发布签名工具。

Android Studio 可直接打开仓库根目录。为本机配置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，或在未跟踪的 `local.properties` 中设置 `sdk.dir`。不要提交自己的 SDK 路径、账号或签名文件。

项目包含 `app` 与 `lyrics-core` 两个模块。运行时资源在 `app/src/main/res`，图标的 SVG 编辑源在 `assets`。歌词核心不依赖 Android。

## 检查与开发包

Windows：

```powershell
.\gradlew.bat :app:testDebugUnitTest :lyrics-core:check :app:assembleDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew :app:testDebugUnitTest :lyrics-core:check :app:assembleDebug
```

开发包输出到 `app/build/outputs/apk/debug/app-debug.apk`，使用本机自动生成的开发密钥。它不等同于作者正式发布包，不能据此假定可以覆盖正式版。

## Release 构建与签名

先生成包含 R8 与资源压缩的未签名包：

```sh
./gradlew :app:assembleRelease
```

输出为 `app/build/outputs/apk/release/app-release-unsigned.apk`。未签名 APK 不能直接安装，正式附件由作者在本地使用独立发布密钥签名。

发布签名工具为 `tools/sign_release.py`。它从 `WATCHLISTEN_RELEASE_*` 环境变量读取配置，不把密钥或密码写入仓库：

| 环境变量 | 用途 |
| --- | --- |
| `WATCHLISTEN_RELEASE_KEYSTORE` | 发布 keystore 的本机路径 |
| `WATCHLISTEN_RELEASE_ALIAS` | 发布密钥别名 |
| `WATCHLISTEN_RELEASE_STORE_PASSWORD` | keystore 密码 |
| `WATCHLISTEN_RELEASE_KEY_PASSWORD` | 密钥密码 |
| `WATCHLISTEN_RELEASE_LINEAGE` | 需要兼容旧开发签名时使用的签名历史文件，按工具实际要求配置 |

签名历史需要兼容旧证书时，还可配置 `WATCHLISTEN_LEGACY_KEYSTORE`、`WATCHLISTEN_LEGACY_ALIAS`、`WATCHLISTEN_LEGACY_STORE_PASSWORD` 和 `WATCHLISTEN_LEGACY_KEY_PASSWORD`，供工具使用历史中的旧密钥生成兼容签名。正式密钥从 Android 9（API 28）的签名轮换机制开始生效；项目最低支持 API 30。

使用当前工具的参数说明执行签名：

```sh
python tools/sign_release.py --help
python tools/sign_release.py --input app/build/outputs/apk/release/app-release-unsigned.apk --output build/WatchListen-0.10.0.apk
```

本机密码应通过受控环境提供，不要写进命令历史、文档、工作流或问题反馈。签名历史文件不包含私钥，但用于旧版兼容的签名流程仍需要相应授权密钥。本版在 OWW231 / API 30 上验证了原测试签名 0.8.0 到正式签名 0.10.0 的保留数据覆盖升级，其他签名来源不自动兼容，详见 [发布验证](./VERIFICATION.md)。

当前正式包在支持的 Android 版本上使用 APK v3 正式签名；工具同时验证旧平台兼容签名分段。发布证书指纹与附件 SHA-256 见 [安装说明](./INSTALL.md)。

自行构建时请使用你自己的密钥和不同安装环境；作者发布密钥不公开。GitHub Actions 只编译、运行测试并检查未签名 Release，不保存发布密钥，也不会自动发布正式 APK。

## 持续集成

工作流位于 `.github/workflows/android.yml`，在分支更新、拉取请求和手动触发时执行单元测试、歌词核心检查与 Release 编译。未配置发布权限或密钥。

Actions 用法依据 [actions/checkout](https://github.com/actions/checkout) 和 [actions/setup-java](https://github.com/actions/setup-java) 的官方说明；构建版本由仓库的 Gradle Wrapper 与 Gradle 配置固定。
