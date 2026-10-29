# 第三方来源与许可

本文件说明腕上听采用的第三方代码、协议参考与主要组件。第三方部分保留其原许可证和版权声明；不因本项目自有代码的版权安排而改变。

## 酷狗接口与歌词协议参考

账号、会员、扫码登录、签名、云音乐库、分类发现及 KRC 容器处理，参考下列公开 MIT 项目并以 Android/JCA、OkHttp 和独立歌词核心实现：

| 来源 | 核查提交 |
| --- | --- |
| [MakcRe/KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi) | `514f870db7e60ca37bc5535be5da36d32b571c6f`、`62730f900dc9fdc51396a663b3d33a6c4c704046`、`5d6c9f7b180279c9dd08bc6f7159de52a856d587` |
| [ghhccghk/KuGouApi_Kotlin_SDK](https://github.com/ghhccghk/KuGouApi_Kotlin_SDK) | `5d2ec16a084c51baeefeea468ef95d5025e78b32`、`de18caa9ddb29aca788a79ac05bf9bc338810a3f` |

核查源码的 MIT 声明包含 **Copyright (c) 2023 MakcRe**。完整声明保留于 [Kugou-reference-MIT.txt](./licenses/Kugou-reference-MIT.txt)，也随 APK 的 `assets/licenses/` 提供。没有引入整个 Node 服务或多平台 SDK 依赖树。

## Google Horologist 播放控制布局

[`PlayerControlLayout.kt`](./app/src/main/java/com/watchlisten/music/ui/PlayerControlLayout.kt) 改编自 [google/horologist](https://github.com/google/horologist) 的 `ControlButtonLayout.kt`，核查提交 `edfb6127893b2ac6fcb7b2ca91dc3db272d7abed`。

**Copyright 2022 The Android Open Source Project**，Apache License 2.0。修改为本应用标准 Compose / API 30 的固定槽宽、侧键居中和依赖适配。原版权、许可头和修改说明保留在源码中，完整许可为 [Horologist-Apache-2.0.txt](./licenses/Horologist-Apache-2.0.txt)，也随 APK 提供。

## ZXing

应用使用 [`com.google.zxing:core:3.5.4`](https://github.com/zxing/zxing/tree/zxing-3.5.4) 在本地生成登录和传歌二维码，采用 Apache License 2.0。完整许可和项目附加声明见 [ZXing-Apache-2.0.txt](./licenses/ZXing-Apache-2.0.txt)，也随 APK 提供。

## Android 与网络组件

应用通过 Gradle 使用 AndroidX Compose、Lifecycle、Activity、Media3、Kotlin Coroutines 与 OkHttp。组件版本以构建文件为准，各自的第三方许可继续适用。此仓库不附带其完整依赖源码或二进制依赖树。

## 图标、界面与内容

腕上听的开放圆环与声波 Logo、SVG 图标、虚构封面和布局样本为本项目制作。公开截图使用虚构歌曲、歌手与账号，不包含真实登录信息。没有把酷狗官方商标标志、原应用代码、签名或受保护媒体打包为本项目资产。

酷狗及其服务提供的头像、歌曲、歌词和资料属于相应权利人。接口参考项目的许可不授予媒体内容或商标使用授权，应用遵循服务端对当前账号和内容的授权。
