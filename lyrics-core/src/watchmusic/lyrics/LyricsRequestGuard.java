package watchmusic.lyrics;

/** 由协调层使用。generation 与请求的 PlaybackEntry 绑定；取消任务后仍检查回执。 */
public final class LyricsRequestGuard {
    private long generation;
    private LyricsDocument current;

    public synchronized long beginEntry() { current = null; return ++generation; }

    public synchronized boolean complete(long requestGeneration, LyricsDocument lyrics) {
        if (requestGeneration != generation) return false;
        current = lyrics;
        return true;
    }

    public synchronized LyricsDocument current() { return current; }
}
