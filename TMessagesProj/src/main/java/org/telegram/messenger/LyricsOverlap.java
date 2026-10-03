package org.telegram.messenger;

/**
 * Which lines of word-timed lyrics the list keeps on screen while the singing of neighbouring
 * lines overlaps (a line, or its background vocals, still sung when the next line starts).
 *
 * <p>Every line retires on its own. At a position the list keeps the OLDEST line that has started
 * and is not finished yet, and the lines after it, so a pair that overlaps stays together until the
 * older one ends; then the older one scrolls out and the other stays. A chain of overlaps is not one
 * block: line A overlapping B, and B overlapping C, keeps A and B while A sings, then B and C while
 * B sings, then C. A line overlapping its neighbour never keeps the line before it alive: only its
 * own end does.
 *
 * <p>Everything is a pure function of the position, so a seek lands on exactly the state playing
 * would have reached. The lookup is a binary search over the running maximum of the line ends.
 */
public final class LyricsOverlap {

    public static final long UNKNOWN = Long.MIN_VALUE;

    /** Whether a line may take the anchor once everything before it has ended (not blank, has a row, no dots). */
    public interface Followable {
        boolean canFollow(int line);
    }

    private final long[] endMs;
    private final boolean[] known;
    /** First line of the run of known lines each line belongs to, or -1. */
    private final int[] runStart;
    /** Running maximum of the ends within the run, up to and including the line. */
    private final long[] runMaxEnd;
    /** The next line with a time, or -1. */
    private final int[] nextTimed;

    /**
     * @param endMs    per line: when its last sound ends, background vocals included
     * @param known    per line: it has word timing and text, so it has an end
     * @param untimed  per line: it has no time at all; it neither takes part nor ends a run
     * @param nextTimed per line: the next line that has a time, or -1
     */
    public LyricsOverlap(long[] endMs, boolean[] known, boolean[] untimed, int[] nextTimed) {
        final int lines = endMs.length;
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
                // A blank or an unknown end: nothing before it can still be kept by what follows.
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

    /** When the last sound of {@code line} ends, or {@link #UNKNOWN} when the line takes no part. */
    public long endOf(int line) {
        return isKnown(line) ? endMs[line] : UNKNOWN;
    }

    /** True when {@code line} has an end of its own and so takes part. */
    public boolean isKnown(int line) {
        return line >= 0 && line < known.length && known[line] && runStart[line] >= 0;
    }

    /**
     * The oldest line that has started and has not finished at {@code position}, among the known
     * lines up to {@code line} (the line the position is in). -1 when there is none, or when
     * {@code line} takes no part.
     */
    public int oldestUnfinished(int line, long position) {
        if (!isKnown(line) || runMaxEnd[line] <= position) return -1;
        int low = runStart[line], high = line;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (runMaxEnd[middle] > position) {
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

    /** The line whose end is the last of the lines up to {@code line}; the lowest one on a tie. */
    public int lastToFinish(int line) {
        if (!isKnown(line)) return -1;
        final long end = runMaxEnd[line];
        int low = runStart[line], high = line;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (runMaxEnd[middle] >= end) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    /**
     * The first line the list keeps: the oldest line still singing, or, once everything up to
     * {@code line} has ended, the one that ended last (the list stays where it was). -1 when the
     * line takes no part (the caller's own rules apply).
     */
    public int anchorLine(int line, long position) {
        if (!isKnown(line)) return -1;
        final int oldest = oldestUnfinished(line, position);
        return oldest >= 0 ? oldest : lastToFinish(line);
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

    /** The line on the anchor: the next line once everything has ended, else the anchor line. -1: no part. */
    public int followLine(int line, long position, Followable followable) {
        if (!isKnown(line)) return -1;
        final int after = afterLine(line, position, followable);
        return after >= 0 ? after : anchorLine(line, position);
    }

    /** First lit line: the next line alone once everything has ended, else the anchor line. */
    public int litFirst(int line, long position, Followable followable) {
        return followLine(line, position, followable);
    }

    /** Last lit line: the next line alone once everything has ended, else {@code line} itself. */
    public int litLast(int line, long position, Followable followable) {
        if (!isKnown(line)) return line;
        final int after = afterLine(line, position, followable);
        return after >= 0 ? after : line;
    }
}
