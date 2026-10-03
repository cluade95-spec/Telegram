package org.telegram.messenger;

/**
 * Which lines of word-timed lyrics the list keeps on screen while the singing of neighbouring
 * lines overlaps (a line, or its background vocals, still sung when the next line starts).
 *
 * <p>The list changes what it keeps when a lyric line STARTS, not when a line's own end is
 * reached. At each start, the rows that have finished are retired from the top, one by one, up to
 * the first row that is genuinely still singing; that row, and everything after it, stays. So an
 * overlapping pair stays put, finished first line included, until the next line starts; then the
 * finished one scrolls out and the other stays. A chain of overlaps is never one block: A
 * overlapping B and B overlapping C keeps A and B until C starts, then B and C (if B still sings),
 * and the next start retires whatever has finished by then, however long the chain runs.
 *
 * <p>Once nothing is singing any more (every line up to the current one has ended), the list moves
 * on to the next line, exactly as it does for ordinary non-overlapping lines.
 *
 * <p>Everything is a pure function of the position, so a seek lands on exactly the state playing
 * would have reached, and pausing or resuming changes nothing. The lookup is a binary search over
 * the running maximum of the line ends.
 */
public final class LyricsOverlap {

    public static final long UNKNOWN = Long.MIN_VALUE;

    /** Whether a line may take the anchor once everything before it has ended (not blank, has a row, no dots). */
    public interface Followable {
        boolean canFollow(int line);
    }

    private final long[] startMs;
    private final long[] endMs;
    private final boolean[] known;
    /** First line of the run of known lines each line belongs to, or -1. */
    private final int[] runStart;
    /** Running maximum of the ends within the run, up to and including the line. */
    private final long[] runMaxEnd;
    /** The next line with a time, or -1. */
    private final int[] nextTimed;

    /**
     * @param startMs  per line: its own time
     * @param endMs    per line: when its last sound ends, background vocals included
     * @param known    per line: it has word timing and text, so it has an end
     * @param untimed  per line: it has no time at all; it neither takes part nor ends a run
     * @param nextTimed per line: the next line that has a time, or -1
     */
    public LyricsOverlap(long[] startMs, long[] endMs, boolean[] known, boolean[] untimed, int[] nextTimed) {
        final int lines = endMs.length;
        this.startMs = startMs.clone();
        this.endMs = endMs.clone();
        this.known = known.clone();
        this.nextTimed = nextTimed.clone();
        runStart = new int[lines];
        runMaxEnd = new long[lines];
        int start = -1;
        long max = UNKNOWN;
        for (int i = 0; i < lines; i++) {
            if (untimed[i]) {
                runStart[i] = -1;
                runMaxEnd[i] = max;
                continue;
            }
            if (!known[i]) {
                // A blank or an unknown end: nothing before it can still be kept by what follows it.
                start = -1;
                max = UNKNOWN;
                runStart[i] = -1;
                runMaxEnd[i] = UNKNOWN;
                continue;
            }
            if (start < 0) {
                start = i;
                max = endMs[i];
            } else {
                max = Math.max(max, endMs[i]);
            }
            runStart[i] = start;
            runMaxEnd[i] = max;
        }
    }

    public int size() {
        return known.length;
    }

    /** True when {@code line} has an end of its own and so takes part. */
    public boolean isKnown(int line) {
        return line >= 0 && line < known.length && known[line] && runStart[line] >= 0;
    }

    /** When the last sound of {@code line} ends, or {@link #UNKNOWN} when the line takes no part. */
    public long endOf(int line) {
        return isKnown(line) ? endMs[line] : UNKNOWN;
    }

    /**
     * The oldest line, among the known lines up to {@code line}, that has not finished at
     * {@code time}; -1 when every one of them has, or when {@code line} takes no part.
     */
    public int oldestUnfinished(int line, long time) {
        if (!isKnown(line) || runMaxEnd[line] <= time) return -1;
        int low = runStart[line], high = line;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (runMaxEnd[middle] > time) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    /** True when every line up to {@code line} has ended by {@code position}. */
    public boolean allFinished(int line, long position) {
        return isKnown(line) && runMaxEnd[line] <= position;
    }

    /**
     * The first line the list keeps while something is singing: the oldest line still unfinished
     * when {@code line} STARTED. It does not change until the next line starts, whatever finishes
     * in between. -1 when the line takes no part (the caller's own rules apply).
     */
    public int anchorLine(int line) {
        if (!isKnown(line)) return -1;
        final int oldest = oldestUnfinished(line, startMs[line]);
        // A line that has already ended when it starts (no length) is its own anchor.
        return oldest >= 0 ? oldest : line;
    }

    /**
     * The line the list moves to once everything up to {@code line} has ended, or -1 while
     * something still sings, when nothing follows, or when the line after may not take the anchor.
     */
    public int afterLine(int line, long position, Followable followable) {
        if (!allFinished(line, position)) return -1;
        final int next = nextTimed[line];
        if (next < 0 || !followable.canFollow(next)) return -1;
        return next;
    }

    /** The line on the anchor: the next line once nothing sings, else the anchor line. -1: no part. */
    public int followLine(int line, long position, Followable followable) {
        if (!isKnown(line)) return -1;
        final int after = afterLine(line, position, followable);
        return after >= 0 ? after : anchorLine(line);
    }

    /** First lit line: the next line alone once nothing sings, else the anchor line. */
    public int litFirst(int line, long position, Followable followable) {
        return followLine(line, position, followable);
    }

    /** Last lit line: the next line alone once nothing sings, else {@code line} itself. */
    public int litLast(int line, long position, Followable followable) {
        if (!isKnown(line)) return line;
        final int after = afterLine(line, position, followable);
        return after >= 0 ? after : line;
    }
}
