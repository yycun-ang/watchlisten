package watchmusic.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 不持有时钟、线程、播放器或 Android 对象。 */
public final class LyricsDocument {
    public enum Format { KRC, LRC, TEXT }
    public final Format format;
    public final long offsetMs;
    public final List<Line> lines;

    LyricsDocument(Format format, long offsetMs, List<Line> lines) {
        this.format = format;
        this.offsetMs = offsetMs;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    public static final class Line {
        public final long startMs;
        public final long endMs;
        public final String text;
        public final List<Word> words;

        Line(long startMs, long endMs, String text, List<Word> words) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
            this.words = Collections.unmodifiableList(new ArrayList<>(words));
        }
    }

    public static final class Word {
        public final long startMs;
        public final long durationMs;
        public final String text;

        Word(long startMs, long durationMs, String text) {
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.text = text;
        }
    }
}
