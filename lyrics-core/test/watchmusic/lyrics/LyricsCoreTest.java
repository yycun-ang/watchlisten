package watchmusic.lyrics;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.io.ByteArrayOutputStream;

public final class LyricsCoreTest {
    private static final LyricsParser parser = new LyricsParser();
    private static final LyricsTimeline timeline = new LyricsTimeline();
    private static int passed;

    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("PASS " + name);
    }

    private interface Operation { void run() throws Exception; }

    private static void rejects(String name, LyricsParser.Failure reason, Operation operation) throws Exception {
        try { operation.run(); }
        catch (LyricsParser.ParseException error) { check(name, error.failure == reason); return; }
        throw new AssertionError("未拒绝：" + name);
    }

    public static void main(String[] args) throws Exception {
        lrc();
        krc();
        plainText();
        decode();
        oldRequests();
        System.out.println("RESULT {\"passed\":" + passed + ",\"failed\":0,\"scope\":\"pure JVM synthetic fixtures\"}");
    }

    private static void plainText() throws Exception {
        LyricsDocument doc = parser.parseText("﻿[ti:原创]\r\n\r\n第一句\r\n第二句\r\n[offset:+100]\r\n");
        check("纯文本保留行序", doc.lines.size() == 2 && doc.lines.get(0).text.equals("第一句")
            && doc.lines.get(1).text.equals("第二句"));
        check("纯文本标记为 TEXT 格式", doc.format == LyricsDocument.Format.TEXT);
        check("纯文本不伪造时间", doc.lines.get(0).startMs == -1 && doc.lines.get(0).words.isEmpty());
        check("纯文本没有活动行", timeline.lineAt(doc, 0) == -1 && timeline.lineAt(doc, 60000) == -1);
        check("纯文本偏移为零", doc.offsetMs == 0);
        rejects("拒绝空纯文本", LyricsParser.Failure.NO_TIMED_LINES, () -> parser.parseText("  \r\n\r\n"));
    }

    private static void lrc() throws Exception {
        LyricsDocument doc = parser.parseLrc("\uFEFF[ti:原创样本]\r\n[00:02.12]后句\n[00:01.1][00:03.123]前句\n[00:04]\n");
        check("LRC 排序、多时间标签与带时间空行", doc.lines.size() == 4 && doc.lines.get(3).text.isEmpty());
        check("十分之一秒精度", doc.lines.get(0).startMs == 1100);
        check("百分之一秒精度", doc.lines.get(1).startMs == 2120);
        check("毫秒精度", doc.lines.get(2).startMs == 3123);
        check("首句之前无活动行", timeline.lineAt(doc, 1099) == -1);
        check("精确进入首句", timeline.lineAt(doc, 1100) == 0);
        check("定位到下一句", timeline.lineAt(doc, 2120) == 1);
        check("向前拖回可重算", timeline.lineAt(doc, 1100) == 0);
        check("空行清除上句", timeline.lineAt(doc, 4000) == 3);
        check("LRC 不伪造逐字时间", doc.lines.get(0).words.isEmpty());
        LyricsDocument duplicate = parser.parseLrc("[00:01]旧\n[00:01]新");
        check("重复时间戳最后一条生效", duplicate.lines.size() == 1 && duplicate.lines.get(0).text.equals("新"));
        LyricsDocument negative = parser.parseLrc("[offset:-1500]\n[00:01]提前");
        check("负偏移保留经过的时间", timeline.lineAt(negative, 0) == 0 && negative.offsetMs == -1500);
        check("播放器位置未知时不选中歌词", timeline.lineAt(negative, Long.MIN_VALUE + 1) == -1);
        check("极大位置与负偏移不发生溢出", timeline.lineAt(negative, Long.MAX_VALUE) == 0);
        LyricsDocument positive = parser.parseLrc("[offset:500]\n[00:01]延后");
        check("正偏移延后显示", timeline.lineAt(positive, 1499) == -1 && timeline.lineAt(positive, 1500) == 0);
        rejects("拒绝越界秒", LyricsParser.Failure.INVALID_TIMING, () -> parser.parseLrc("[00:60]错误"));
        rejects("拒绝无时间歌词", LyricsParser.Failure.NO_TIMED_LINES, () -> parser.parseLrc("无时间文本"));
        rejects("拒绝超长单行", LyricsParser.Failure.TOO_LARGE, () -> parser.parseLrc("[00:01]" + repeat('字', 4097)));
        rejects("拒绝过多行", LyricsParser.Failure.TOO_LARGE, () -> parser.parseLrc(repeat('\n', 5001)));
        rejects("限制多标签展开数量", LyricsParser.Failure.TOO_LARGE, () -> parser.parseLrc(manyStamps()));
    }

    private static void krc() throws Exception {
        LyricsDocument doc = parser.parseKrc("[offset:-200]\n[1000,2000]<0,500,0>微<1000,0,0>光\n[4000,500]过门");
        check("KRC 拼接逐字文字", doc.lines.get(0).text.equals("微光") && doc.lines.get(0).words.size() == 2);
        check("KRC 行之前无高亮", timeline.lineAt(doc, 799) == -1);
        check("KRC 当前行边界", timeline.lineAt(doc, 800) == 0);
        check("字开始时进度零", timeline.wordProgress(doc, 0, 0, 800) == 0);
        check("字中间进度一半", timeline.wordProgress(doc, 0, 0, 1050) == .5);
        check("字结束钳制到一", timeline.wordProgress(doc, 0, 0, 1800) == 1);
        check("字间空隙不提前涂色", timeline.wordProgress(doc, 0, 1, 1799) == 0);
        check("零时长字不会除零", timeline.wordProgress(doc, 0, 1, 1800) == 1);
        check("行结束后的间奏无活动行", timeline.lineAt(doc, 2800) == -1);
        check("没有逐字标记的 KRC 保留整行", doc.lines.get(1).text.equals("过门") && doc.lines.get(1).words.isEmpty());
        check("KRC 后一句进入", timeline.lineAt(doc, 3800) == 1);
        check("切回旧位置不依赖上一帧", timeline.wordProgress(doc, 0, 0, 1050) == .5);
        rejects("字时间超出行", LyricsParser.Failure.INVALID_TIMING, () -> parser.parseKrc("[0,500]<0,501,0>字"));
        rejects("拒绝倒序逐字时间", LyricsParser.Failure.INVALID_TIMING, () -> parser.parseKrc("[0,500]<200,20,0>一<100,20,0>二"));
        rejects("拒绝损坏的逐字标签", LyricsParser.Failure.INVALID_TIMING, () -> parser.parseKrc("[0,500]<bad>字"));
        rejects("拒绝行时间溢出", LyricsParser.Failure.INVALID_TIMING, () -> parser.parseKrc("[999999999,100]字"));
        check("KRC 同时间戳确定选择", parser.parseKrc("[0,500]旧\n[0,500]新").lines.get(0).text.equals("新"));
        boolean immutable = false;
        try { doc.lines.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        check("解析结果不可变", immutable);
    }

    private static void decode() throws Exception {
        String sample = "[1000,500]<0,500,0>光";
        byte[] compressed = krcBytes(sample.getBytes(StandardCharsets.UTF_8));
        check("KRC 压缩容器到 UTF-8", parser.decodeKrc(compressed).equals(sample));
        check("首选 KRC 逐字文档", parser.parsePreferred(compressed, "[00:01]回退").document.format == LyricsDocument.Format.KRC);
        byte[] invalid = {1, 2, 3, 4, 5};
        rejects("错误文件头", LyricsParser.Failure.INVALID_HEADER, () -> parser.decodeKrc(invalid));
        rejects("截断 zlib", LyricsParser.Failure.INVALID_COMPRESSION, () -> parser.decodeKrc(Arrays.copyOf(compressed, compressed.length - 2)));
        rejects("损坏 UTF-8", LyricsParser.Failure.INVALID_UTF8, () -> parser.decodeKrc(krcBytes(new byte[]{(byte)0xc3, 0x28})));
        rejects("解压放大上限", LyricsParser.Failure.TOO_LARGE, () -> parser.decodeKrc(krcBytes(new byte[LyricsParser.MAX_BYTES + 1])));
        LyricsParser.Result fallback = parser.parsePreferred(invalid, "[00:01]回退");
        check("损坏 KRC 回退 LRC 并保留原因", fallback.document.format == LyricsDocument.Format.LRC && fallback.krcFailure == LyricsParser.Failure.INVALID_HEADER);
        rejects("两种歌词都失败返回明确类型", LyricsParser.Failure.NO_TIMED_LINES, () -> parser.parsePreferred(invalid, ""));
    }

    private static void oldRequests() throws Exception {
        LyricsRequestGuard guard = new LyricsRequestGuard();
        LyricsDocument a = parser.parseLrc("[00:01]A"), b = parser.parseLrc("[00:01]B");
        long old = guard.beginEntry();
        long current = guard.beginEntry();
        check("切歌立即清理旧歌词", guard.current() == null);
        check("旧请求后到不能覆盖", !guard.complete(old, a) && guard.current() == null);
        check("当前请求可提交", guard.complete(current, b) && guard.current() == b);
        check("旧请求晚于新请求也不能覆盖", !guard.complete(old, a) && guard.current() == b);
        guard.beginEntry();
        check("同一歌曲重新入队也产生新代次", !guard.complete(current, b));
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count]; Arrays.fill(values, value); return new String(values);
    }

    private static String manyStamps() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 3000; i++) input.append(String.format("[%02d:%02d][%02d:%02d]字\n", i / 60, i % 60, (i + 3000) / 60, (i + 3000) % 60));
        return input.toString();
    }

    private static byte[] krcBytes(byte[] bytes) {
        Deflater deflater = new Deflater();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write('k'); result.write('r'); result.write('c'); result.write('1');
        deflater.setInput(bytes); deflater.finish();
        byte[] buffer = new byte[4096];
        int[] key = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105};
        int total = 0;
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            for (int index = 0; index < count; index++) result.write(buffer[index] ^ key[(total + index) % key.length]);
            total += count;
        }
        deflater.end();
        return result.toByteArray();
    }
}
