package watchmusic.lyrics;

public final class LyricsTimeline {
    /** 每次传入播放器的实际位置；定位、暂停、倍速均不需要维护第二套时钟。 */
    public int lineAt(LyricsDocument document, long playerPositionMs) {
        if (playerPositionMs < 0) return -1;
        long relative = relativePosition(playerPositionMs, document.offsetMs);
        int low = 0, high = document.lines.size() - 1, result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (document.lines.get(middle).startMs <= relative) { result = middle; low = middle + 1; }
            else high = middle - 1;
        }
        if (result >= 0 && document.lines.get(result).endMs != Long.MAX_VALUE && relative >= document.lines.get(result).endMs) return -1;
        return result;
    }

    public double wordProgress(LyricsDocument document, int lineIndex, int wordIndex, long playerPositionMs) {
        if (playerPositionMs < 0) return 0;
        LyricsDocument.Line line = document.lines.get(lineIndex);
        LyricsDocument.Word word = line.words.get(wordIndex);
        long elapsed = relativePosition(playerPositionMs, document.offsetMs) - line.startMs - word.startMs;
        if (elapsed < 0) return 0;
        if (word.durationMs == 0) return 1;
        return Math.max(0, Math.min(1, elapsed / (double) word.durationMs));
    }

    private long relativePosition(long position, long offset) {
        if (offset < 0 && position > Long.MAX_VALUE + offset) return Long.MAX_VALUE;
        return position - offset;
    }
}
