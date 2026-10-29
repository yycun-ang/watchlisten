package watchmusic.lyrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class LyricsParser {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_LINES = 5000;
    public static final int MAX_LINE_CHARS = 4096;
    private static final long MAX_TIME_MS = 24 * 60 * 60 * 1000L;
    private static final int[] KRC_KEY = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105};
    private static final Pattern OFFSET = Pattern.compile("^\\[offset:([+-]?\\d{1,9})]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,4}):(\\d{2})(?:[.](\\d{1,3}))?]");
    private static final Pattern KRC_LINE = Pattern.compile("^\\[(\\d{1,9}),(\\d{1,9})](.*)$");
    private static final Pattern KRC_WORD = Pattern.compile("<(\\d{1,9}),(\\d{1,9}),\\d{1,9}>");

    public enum Failure { TOO_LARGE, INVALID_HEADER, INVALID_COMPRESSION, INVALID_UTF8, INVALID_TIMING, NO_TIMED_LINES }

    public static final class ParseException extends Exception {
        public final Failure failure;
        ParseException(Failure failure) { super(failure.name()); this.failure = failure; }
    }

    public static final class Result {
        public final LyricsDocument document;
        public final Failure krcFailure;
        Result(LyricsDocument document, Failure krcFailure) { this.document = document; this.krcFailure = krcFailure; }
    }

    public Result parsePreferred(byte[] krc, String lrc) throws ParseException {
        Failure failure = null;
        if (krc != null) {
            try { return new Result(parseKrc(decodeKrc(krc)), null); }
            catch (ParseException error) { failure = error.failure; }
        }
        return new Result(parseLrc(lrc), failure);
    }

    /**
     * 无时间戳的歌词按纯文本展示：保留原始行序，不伪造时间，
     * 因此不会出现逐字或逐行高亮。元数据标签与时间标签不作为正文。
     */
    public LyricsDocument parseText(String text) throws ParseException {
        List<LyricsDocument.Line> lines = new ArrayList<>();
        for (String row : rows(text)) {
            String value = row.replace("﻿", "").trim();
            if (value.isEmpty()) continue;
            if (OFFSET.matcher(value).matches()) continue;
            if (value.startsWith("[") && value.endsWith("]") && !LRC_TIME.matcher(value).lookingAt()) continue;
            lines.add(new LyricsDocument.Line(-1L, -1L, value, Collections.emptyList()));
            if (lines.size() > MAX_LINES) throw new ParseException(Failure.TOO_LARGE);
        }
        if (lines.isEmpty()) throw new ParseException(Failure.NO_TIMED_LINES);
        return new LyricsDocument(LyricsDocument.Format.TEXT, 0, lines);
    }

    public String decodeKrc(byte[] bytes) throws ParseException {
        if (bytes == null || bytes.length < 5 || bytes[0] != 'k' || bytes[1] != 'r' || bytes[2] != 'c' || bytes[3] != '1') {
            throw new ParseException(Failure.INVALID_HEADER);
        }
        if (bytes.length > MAX_BYTES) throw new ParseException(Failure.TOO_LARGE);
        byte[] compressed = new byte[bytes.length - 4];
        for (int index = 0; index < compressed.length; index++) compressed[index] = (byte) (bytes[index + 4] ^ KRC_KEY[index % KRC_KEY.length]);
        return inflateUtf8(compressed);
    }

    private String inflateUtf8(byte[] compressed) throws ParseException {
        Inflater inflater = new Inflater();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        inflater.setInput(compressed);
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && !inflater.finished()) throw new ParseException(Failure.INVALID_COMPRESSION);
                if (output.size() + count > MAX_BYTES) throw new ParseException(Failure.TOO_LARGE);
                output.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != 0) throw new ParseException(Failure.INVALID_COMPRESSION);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (DataFormatException error) {
            throw new ParseException(Failure.INVALID_COMPRESSION);
        } catch (CharacterCodingException error) {
            throw new ParseException(Failure.INVALID_UTF8);
        } finally {
            inflater.end();
        }
    }

    private String[] rows(String text) throws ParseException {
        if (text == null) throw new ParseException(Failure.NO_TIMED_LINES);
        if (text.length() > MAX_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new ParseException(Failure.TOO_LARGE);
        String[] rows = text.replace("\uFEFF", "").split("\\r?\\n", -1);
        if (rows.length > MAX_LINES) throw new ParseException(Failure.TOO_LARGE);
        for (String row : rows) if (row.length() > MAX_LINE_CHARS) throw new ParseException(Failure.TOO_LARGE);
        return rows;
    }

    public LyricsDocument parseLrc(String text) throws ParseException {
        TreeMap<Long, String> timed = new TreeMap<>();
        long offset = 0;
        for (String row : rows(text)) {
            Matcher header = OFFSET.matcher(row.trim());
            if (header.matches()) { offset = signedOffset(header.group(1)); continue; }
            parseLrcRow(row, timed);
        }
        if (timed.isEmpty()) throw new ParseException(Failure.NO_TIMED_LINES);
        List<LyricsDocument.Line> lines = new ArrayList<>();
        for (long start : timed.keySet()) {
            Long next = timed.higherKey(start);
            lines.add(new LyricsDocument.Line(start, next == null ? Long.MAX_VALUE : next, timed.get(start), Collections.emptyList()));
        }
        return new LyricsDocument(LyricsDocument.Format.LRC, offset, lines);
    }

    private void parseLrcRow(String row, TreeMap<Long, String> timed) throws ParseException {
        Matcher matcher = LRC_TIME.matcher(row);
        List<Long> stamps = new ArrayList<>();
        int end = 0;
        while (matcher.find(end) && matcher.start() == end) {
            long seconds = Long.parseLong(matcher.group(2));
            if (seconds >= 60) throw new ParseException(Failure.INVALID_TIMING);
            String fraction = matcher.group(3);
            long millis = fraction == null ? 0 : Long.parseLong((fraction + "00").substring(0, 3));
            long start = bounded(Long.parseLong(matcher.group(1)) * 60000 + seconds * 1000 + millis);
            stamps.add(start);
            end = matcher.end();
        }
        for (long stamp : stamps) timed.put(stamp, row.substring(end));
        if (timed.size() > MAX_LINES) throw new ParseException(Failure.TOO_LARGE);
    }

    public LyricsDocument parseKrc(String text) throws ParseException {
        TreeMap<Long, LyricsDocument.Line> timed = new TreeMap<>();
        long offset = 0;
        for (String row : rows(text)) {
            Matcher header = OFFSET.matcher(row.trim());
            if (header.matches()) { offset = signedOffset(header.group(1)); continue; }
            Matcher line = KRC_LINE.matcher(row);
            if (line.matches()) {
                long start = bounded(Long.parseLong(line.group(1)));
                long duration = bounded(Long.parseLong(line.group(2)));
                bounded(start + duration);
                timed.put(start, krcLine(start, duration, line.group(3)));
            }
        }
        if (timed.isEmpty()) throw new ParseException(Failure.NO_TIMED_LINES);
        return new LyricsDocument(LyricsDocument.Format.KRC, offset, new ArrayList<>(timed.values()));
    }

    private LyricsDocument.Line krcLine(long start, long duration, String body) throws ParseException {
        Matcher matcher = KRC_WORD.matcher(body);
        List<LyricsDocument.Word> words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int previousText = -1;
        long previousStart = 0;
        long previousDuration = 0;
        while (matcher.find()) {
            if (previousText < 0 && matcher.start() != 0) throw new ParseException(Failure.INVALID_TIMING);
            if (previousText >= 0) appendWord(words, text, previousStart, previousDuration, body.substring(previousText, matcher.start()));
            long wordStart = bounded(Long.parseLong(matcher.group(1)));
            long wordDuration = bounded(Long.parseLong(matcher.group(2)));
            if (wordStart < previousStart || wordStart + wordDuration > duration) throw new ParseException(Failure.INVALID_TIMING);
            previousStart = wordStart;
            previousDuration = wordDuration;
            previousText = matcher.end();
        }
        if (previousText >= 0) appendWord(words, text, previousStart, previousDuration, body.substring(previousText));
        else {
            if (body.contains("<")) throw new ParseException(Failure.INVALID_TIMING);
            text.append(body);
        }
        return new LyricsDocument.Line(start, start + duration, text.toString(), words);
    }

    private void appendWord(List<LyricsDocument.Word> words, StringBuilder text, long start, long duration, String token) throws ParseException {
        if (token.contains("<")) throw new ParseException(Failure.INVALID_TIMING);
        words.add(new LyricsDocument.Word(start, duration, token));
        text.append(token);
    }

    private long signedOffset(String value) throws ParseException {
        long offset = Long.parseLong(value);
        if (Math.abs(offset) > MAX_TIME_MS) throw new ParseException(Failure.INVALID_TIMING);
        return offset;
    }

    private long bounded(long value) throws ParseException {
        if (value < 0 || value > MAX_TIME_MS) throw new ParseException(Failure.INVALID_TIMING);
        return value;
    }
}
