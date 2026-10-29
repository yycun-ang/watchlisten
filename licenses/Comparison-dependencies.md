# 接口比较测试依赖

- Meting：MIT，Copyright (c) 2018 METO；[完整声明](Meting-MIT.txt)，[源码](https://github.com/metowolf/Meting)。协议比较的必要来源保存在研究报告中。
- OkHttp 4.12.0：Apache License 2.0，Square；[官方源码和许可证](https://github.com/square/okhttp/tree/parent-4.12.0)，[Apache 2.0 全文](ZXing-Apache-2.0.txt)。仅 comparison 构建引入，附带 Maven 解析的 Okio、Kotlin 运行时依赖，均不因本测试进入原 debug 构建。
- 桌面 `org.json:json:20240303`：用于在 JVM 运行 Android 同源的协议测试器，构建时从 Maven Central 获取，不提交 JAR。[项目与许可证](https://github.com/stleary/JSON-java/tree/20240303)。

本轮没有复制未声明许可证的旧播放器脚本，也未引入 Aerome 的 GPL 派生实现。
