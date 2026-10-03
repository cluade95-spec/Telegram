package org.telegram.messenger;

import org.telegram.ui.Components.LyricsTuning;

/**
 * Whether a lyric is active at a playback position, for presentations that show one lyric or
 * nothing (the compact player bar). The rules are the large player's: a line is active from its
 * own time until the next line starts, except during an instrumental gap (at least
 * {@link LyricsTuning#INTERLUDE_MIN_GAP_MS} from the end of one line's singing to the next line,
 * the intro included, where the large player shows its dots) and once the last stated word of a
 * word-timed document has ended. A timed blank line and the time before the first line have no
 * active lyric either.
 *
 * <p>Everything is a pure function of the position and the document, so the answer is the same
 * whether the position was reached by playing, seeking, pausing or opening the presentation there.
 */
public final class LyricsActivity {

    public static final long NONE = Long.MAX_VALUE;

    /** The document as this class needs to read it. */
    public interface Document {
        int size();

        /** Whether the document has timestamps at all (the first line is timed). */
        boolean isSynced();

        long timeMs(int line);

        boolean timed(int line);

        boolean hasText(int line);

        /** The line carries word timing. */
        boolean hasWords(int line);

        /**
         * The latest stated end among the line's words if its last word states one, else
         * {@link Long#MAX_VALUE}; {@link Long#MAX_VALUE} for a line without word timing.
         */
        long statedEndMs(int line);

        /** Start time of the line's last word (a line with word timing). */
        long lastWordStartMs(int line);

        /** Stated duration of the line's last word, or 0 when it states none. */
        long lastWordStatedMs(int line);

        /** Plain (not synced) lyric text: every line takes part. */
        boolean isPlain();
    }

    private final Document document;
    private final int lines;
    private final boolean synced;
    private long[] gapStart = new long[0];
    private long[] gapEnd = new long[0];
    private long endMs = Long.MAX_VALUE;

    public LyricsActivity(Document document) {
        this.document = document;
        this.lines = document.size();
        this.synced = document.isSynced();
        if (!synced) return;
        buildGaps();
        buildEnd();
    }

    /** AudioPlayerAlert.buildLyricsInterludes. */
    private void buildGaps() {
        final long[] starts = new long[lines + 1];
        final long[] ends = new long[lines + 1];
        int count = 0;
        int previous = -1;
        for (int line = 0; line < lines; line++) {
            if (!(document.isPlain() || document.hasText(line))) continue;
            if (!document.timed(line)) continue;
            final long gapStartMs = previous < 0 ? 0 : singingEnd(previous, line);
            final long gapEndMs = document.timeMs(line);
            if (gapStartMs != Long.MAX_VALUE && gapEndMs - gapStartMs >= LyricsTuning.INTERLUDE_MIN_GAP_MS) {
                starts[count] = gapStartMs;
                ends[count] = gapEndMs;
                count++;
            }
            previous = line;
        }
        gapStart = java.util.Arrays.copyOf(starts, count);
        gapEnd = java.util.Arrays.copyOf(ends, count);
    }

    /** AudioPlayerAlert.lyricsSingingEnd: a timed blank ends the singing, else the last word's stated end. */
    private long singingEnd(int line, int next) {
        for (int i = line + 1; i < next; i++) {
            if (document.timed(i) && !document.hasText(i)) return Math.max(document.timeMs(i), document.timeMs(line));
        }
        if (!document.hasWords(line)) return Long.MAX_VALUE;
        return document.statedEndMs(line);
    }

    /** AudioPlayerAlert.indexLyricsDocument: only word timing states where the singing stops. */
    private void buildEnd() {
        boolean wordTimed = false;
        for (int i = 0; i < lines && !wordTimed; i++) {
            wordTimed = document.timed(i) && document.hasWords(i) && document.hasText(i);
        }
        if (!wordTimed) return;
        for (int i = lines - 1; i >= 0; i--) {
            if (!document.timed(i) || !document.hasText(i)) continue;
            if (!document.hasWords(i)) return;
            final long stated = document.lastWordStatedMs(i);
            endMs = document.lastWordStartMs(i) + (stated > 0 ? stated : LyricsTuning.END_OF_SONG_DERIVED_MS);
            return;
        }
    }

    /** The last line that has started at {@code position}, or -1 (Lyrics.lineAt). */
    public int lineAt(long position) {
        if (!synced) return -1;
        int low = 0, high = lines - 1, result = -1;
        while (low <= high) {
            final int middle = (low + high) >>> 1;
            if (document.timeMs(middle) <= position) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    /** True inside an instrumental gap, or after the last stated word of the song. */
    public boolean isIdle(long position) {
        if (!synced) return false;
        if (position >= endMs) return true;
        for (int i = 0; i < gapStart.length; i++) {
            if (position >= gapStart[i] && position < gapEnd[i]) return true;
        }
        return false;
    }

    /** The line whose lyric is active at {@code position}, or -1 when there is none. */
    public int activeLine(long position) {
        final int line = lineAt(position);
        if (line < 0 || !document.hasText(line) || isIdle(position)) return -1;
        return line;
    }

    /**
     * The line the compact player bar shows at {@code position}, or -1 for the song's title and
     * artist.
     *
     * <p>While paused it is always the title and artist, wherever playback is paused (inside a lyric,
     * in a gap, before the first line, after the last one, after a seek), so a lyric is never left
     * on the bar merely because it was active when playback stopped. While playing it is the active
     * lyric if there is one, else the title and artist; the next lyric rolls in a little before its
     * time (at most {@code rollMs}, at most half the gap to it), so the bar is already on it when it
     * starts. A pure function of the position and the play state, never of how the song got there
     * or of any earlier call: resuming derives the playing state afresh.
     */
    public int compactLine(long position, boolean playing, long rollMs) {
        if (!playing) return -1;
        final int line = lineAt(position);
        int shown = activeLine(position);
        if (synced && line + 1 < lines) {
            final long untilNext = document.timeMs(line + 1) - position;
            final long previousTime = line < 0 ? 0 : document.timeMs(line);
            final long gap = Math.max(1, document.timeMs(line + 1) - previousTime);
            final long lead = Math.min(rollMs, Math.max(80, gap / 2));
            if (untilNext <= lead) shown = document.hasText(line + 1) ? line + 1 : -1;
        }
        return shown;
    }

    /**
     * The next time after {@code position} at which an active lyric turns into none (the start of
     * a gap, the end of the song), or {@link #NONE}. A presentation that follows the position
     * re-reads it then; the start of the next line is the caller's own.
     */
    public long nextIdleStartMs(long position) {
        if (!synced) return NONE;
        long next = NONE;
        if (endMs != Long.MAX_VALUE && endMs > position) next = endMs;
        for (int i = 0; i < gapStart.length; i++) {
            if (gapStart[i] > position && gapStart[i] < next) next = gapStart[i];
        }
        return next;
    }
}
