# 歌词计算 PoC

这是可由 Kotlin 调用的 Java 8 纯计算代码。无 Android 依赖、网络、播放器、线程或独立播放时钟。运行 `./test.ps1` 可使用本机 JDK 17 编译并执行合成样本测试。

Android Studio 接入时为此目录增加了 `java-library` 验证目标，可在项目根目录运行 `gradlew.bat :lyrics-core:verifyLyrics`。它仍是纯 Java 验证库，尚未接入播放器或原生 UI。

## 已实现

- KRC：检查 `krc1` 文件头，循环 XOR 后以 zlib 解压，严格 UTF-8；压缩输入和解压输出均限制为 1 MiB，释放解压器。
- 文本解析：KRC 行与相对逐字时间；LRC 多时间标签、1/2/3 位小数、带时间空行、BOM/CRLF。最多 5000 行、单行 4096 字符、时间不超过 24 小时。
- `parsePreferred(krc, lrc)`：KRC 失败时回退至传入的 LRC，保留 KRC 错误类型；两种均失败由上层映射 `LyricsUnavailable`，不阻止音频。
- `LyricsTimeline.lineAt(document, playerPositionMs)` 二分查找当前行；`wordProgress` 返回各字 0–1 进度。调用方每次提供播放器实际位置；暂停、定位和倍速不需要维护第二个时钟。
- `LyricsRequestGuard`：每个播放实例开始新代次并清空旧文档，旧请求的完成回执不能提交。协调层还须实际取消旧协程/HTTP 请求；此守卫不能替代取消操作。

## 时间与异常约定

内部统一为毫秒，`effectiveStart = rawStart + offsetMs`；正数延后、负数提前。负时间不先裁成 0，以保留播放位置为 0 时已经经过的逐字进度。网关接入真实样本时，须核对来源的 offset/adjust 符号并转换为此约定，避免重复应用偏移。

重复时间戳稳定保留最后一条。此策略目前不合并原文/翻译；多语言层属于后续能力。LRC 行在下句前保持选中，最后一句保持；KRC 在行时长结束后进入间奏空态。缺少逐字标签的 KRC 保留整行，不合成虚假的逐字时间。倒序或超出行时长的字时间拒绝整个 KRC，走 LRC 回退。

2026-10-21：51 项检查通过，输出见 `build/test-results.txt`。这些是原创合成样本和边界验证，不能证明酷狗实际候选匹配、真实歌词的偏移语义或 Watch X 播放同步已通过。原生 UI 包目前仍用独立的示意歌词绘制，尚未集成本核心与 Media3。

KRC 容器协议依据已核查的 [KuGouMusicApi 解码实现](https://github.com/MakcRe/KuGouMusicApi/blob/514f870db7e60ca37bc5535be5da36d32b571c6f/util/util.js)，保留 [MIT 声明](../licenses/Kugou-reference-MIT.txt)。解析器与时间轴为本项目实现，不内置歌曲歌词。
