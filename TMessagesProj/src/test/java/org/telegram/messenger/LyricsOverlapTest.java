package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Overlapping lyric lines retire one by one: the list keeps the oldest line still singing, and
 * everything is a pure function of the position (a seek lands where playing would have).
 */
public class LyricsOverlapTest {

    private static final LyricsOverlap.Followable ANY = line -> true;

    /** Lines given as {start, end}; the model sees only the ends, the start is where lineAt() lands. */
    private static final class Song {
        final long[] start;
        final long[] end;
        final LyricsOverlap model;

        Song(long[]... lines) {
            int n = lines.length;
            start = new long[n];
            end = new long[n];
            boolean[] known = new boolean[n];
            boolean[] untimed = new boolean[n];
            int[] next = new int[n];
            for (int i = 0; i < n; i++) {
                start[i] = lines[i][0];
                end[i] = lines[i][1];
                known[i] = true;
                next[i] = i + 1 < n ? i + 1 : -1;
            }
            model = new LyricsOverlap(end, known, untimed, next);
        }

        /** Document.lineAt: the last line that has started. */
        int lineAt(long position) {
            int result = -1;
            for (int i = 0; i < start.length; i++) {
                if (start[i] <= position) result = i;
            }
            return result;
        }

        int follow(long position) {
            return model.followLine(lineAt(position), position, ANY);
        }

        int litFirst(long position) {
            return model.litFirst(lineAt(position), position, ANY);
        }

        int litLast(long position) {
            return model.litLast(lineAt(position), position, ANY);
        }
    }

    private static long[] line(long start, long end) {
        return new long[] {start, end};
    }

    // A overlaps B, B does not overlap C.
    private static Song pairThenLine() {
        return new Song(line(0, 5000), line(3000, 9000), line(10000, 15000), line(16000, 20000));
    }

    @Test
    public void overlappingPairStaysTogetherWhileTheOlderLineSings() {
        Song s = pairThenLine();
        assertEquals(0, s.follow(1000));
        assertEquals(0, s.follow(3500));   // B has started; A is unfinished
        assertEquals(0, s.follow(4999));
        assertEquals(0, s.litFirst(4000));
        assertEquals(1, s.litLast(4000));
    }

    @Test
    public void olderLineScrollsOutWhenItEndsAndTheOtherStays() {
        Song s = pairThenLine();
        assertEquals(1, s.follow(5000));   // A is finished, B is singing
        assertEquals(1, s.follow(7000));
        assertEquals(1, s.litFirst(7000));
        assertEquals(1, s.litLast(7000));
    }

    @Test
    public void everythingFinishedMovesOnToTheNextLine() {
        Song s = pairThenLine();
        assertEquals(2, s.follow(9000));   // both finished before C starts
        assertEquals(2, s.follow(9500));
        assertEquals(2, s.litFirst(9500));
        assertEquals(2, s.litLast(9500));
        assertEquals(2, s.follow(10000));
    }

    // A overlaps B and B overlaps C: not one block.
    private static Song twoOverlaps() {
        return new Song(line(0, 5000), line(3000, 9000), line(8000, 14000), line(16000, 20000));
    }

    @Test
    public void chainOfTwoOverlapsRetiresLineByLine() {
        Song s = twoOverlaps();
        assertEquals(0, s.follow(4000));    // A, B
        assertEquals(1, s.follow(6000));    // A gone; B sings, C not yet
        assertEquals(1, s.follow(8500));    // C has started; B unfinished: B and C together
        assertEquals(1, s.litFirst(8500));
        assertEquals(2, s.litLast(8500));
        assertEquals(2, s.follow(9000));    // B ends: C stays alone
        assertEquals(2, s.follow(12000));
        assertEquals(3, s.follow(14000));   // C ends: on to D
    }

    @Test
    public void anOverlapNeverKeepsTheOldestLineAliveIndefinitely() {
        Song s = twoOverlaps();
        // The old chain model kept A on the anchor until C ended (14000).
        assertTrue(s.follow(9500) != 0);
        assertTrue(s.follow(13000) != 0);
        assertTrue(s.follow(13000) != 1);
    }

    // A-B, B-C and C-D.
    private static Song threeOverlaps() {
        return new Song(line(0, 5000), line(4000, 9000), line(8000, 13000), line(12000, 18000), line(20000, 24000));
    }

    @Test
    public void chainOfThreeOverlapsRetiresLineByLine() {
        Song s = threeOverlaps();
        assertEquals(0, s.follow(4500));
        assertEquals(1, s.follow(6000));
        assertEquals(1, s.follow(8500));
        assertEquals(2, s.follow(10000));
        assertEquals(2, s.follow(12500));
        assertEquals(3, s.follow(14000));
        assertEquals(3, s.follow(17999));
        assertEquals(4, s.follow(18000));
        assertEquals(4, s.follow(19000));
    }

    @Test
    public void anyLengthOfChainKeepsMoving() {
        // 40 lines, each overlapping the next by 1s: the list must follow one line at a time.
        long[][] lines = new long[40][];
        for (int i = 0; i < 40; i++) lines[i] = line(i * 3000L, i * 3000L + 4000L);
        Song s = new Song(lines);
        int previous = 0;
        for (long position = 0; position < 40 * 3000L; position += 250) {
            int follow = s.follow(position);
            assertTrue(follow >= previous);
            assertTrue("never more than the previous line behind at " + position, s.lineAt(position) - follow <= 1);
            previous = follow;
        }
        assertEquals(1, s.follow(4000));
        assertEquals(20, s.follow(20 * 3000L + 1000));
    }

    @Test
    public void olderLineFinishedWhenTheNextOneStartsWhileTheMiddleOneSings() {
        // A finished before C starts, B still active.
        Song s = new Song(line(0, 4000), line(2000, 12000), line(8000, 14000));
        assertEquals(1, s.follow(8000));
        assertEquals(1, s.litFirst(8000));
        assertEquals(2, s.litLast(8000));
    }

    @Test
    public void bothOlderLinesFinishedWhenTheNextOneStarts() {
        Song s = new Song(line(0, 4000), line(2000, 6000), line(8000, 14000));
        assertEquals(2, s.follow(8000));
        assertEquals(2, s.litFirst(8000));
        assertEquals(2, s.litLast(8000));
    }

    @Test
    public void aFinishedLineBetweenTwoUnfinishedOnesCannotLeaveBeforeTheOlderOne() {
        // B ends first but sits under A in the list: A keeps the anchor until it ends.
        Song s = new Song(line(0, 10000), line(2000, 5000), line(8000, 14000));
        assertEquals(0, s.follow(8500));
        assertEquals(0, s.litFirst(8500));
        assertEquals(2, s.litLast(8500));
        assertEquals(2, s.follow(10000));
    }

    @Test
    public void seekingStraightIntoAnyStateGivesTheSameAnswerAsPlayingThere() {
        Song s = threeOverlaps();
        long[] positions = {0, 4500, 6000, 8500, 10000, 12500, 14000, 17999, 18000, 19000, 21000, 24000, 30000};
        for (long target : positions) {
            // "Playing": walk up to the target in small steps; "seeking": ask once, cold.
            int walked = -2;
            for (long p = 0; p <= target; p += 50) {
                walked = s.follow(p);
            }
            walked = s.follow(target);
            assertEquals("position " + target, s.follow(target), walked);
        }
        // Backwards too: a seek back reconstructs the older state.
        assertEquals(1, s.follow(6000));
        assertEquals(3, s.follow(14000));
        assertEquals(1, s.follow(6000));
    }

    @Test
    public void seekingIntoTheMiddleOfAnOverlapKeepsBothLines() {
        Song s = twoOverlaps();
        assertEquals(1, s.follow(8500));
        assertEquals(1, s.litFirst(8500));
        assertEquals(2, s.litLast(8500));
        assertEquals(0, s.follow(3500));
    }

    @Test
    public void ordinaryLinesAreUnchanged() {
        // No overlap at all, with a gap before the last line.
        Song s = new Song(line(0, 3000), line(4000, 7000), line(7000, 10000), line(12000, 15000));
        assertEquals(0, s.follow(1000));
        assertEquals(1, s.follow(3000));    // moves on when line 0 ends, before line 1 starts
        assertEquals(1, s.follow(4000));
        assertEquals(1, s.follow(6999));
        assertEquals(2, s.follow(7000));
        assertEquals(3, s.follow(10000));
        assertEquals(3, s.follow(12500));
        // Nothing follows the last line: the list stays on it.
        assertEquals(3, s.follow(16000));
        assertEquals(3, s.litFirst(16000));
        assertEquals(3, s.litLast(16000));
    }

    @Test
    public void ordinaryLineWithNoOverlapNeverKeepsTheLineBeforeIt() {
        Song s = new Song(line(0, 3000), line(3000, 6000), line(6000, 9000));
        for (long p = 0; p < 9000; p += 100) {
            assertEquals(s.lineAt(p), s.follow(p));
            assertEquals(s.lineAt(p), s.litFirst(p));
        }
    }

    @Test
    public void aLineThatMayNotFollowKeepsTheListWhereItWas() {
        // Next line is blank / behind a dots row: the list does not move to it.
        long[] end = {5000, 9000};
        LyricsOverlap model = new LyricsOverlap(end, new boolean[] {true, true}, new boolean[2], new int[] {1, -1});
        LyricsOverlap.Followable none = line -> false;
        // B (index 1) overlaps A: both finished at 9500 and nothing may follow: stay on the one that ended last.
        assertEquals(1, model.followLine(1, 9500, none));
        assertEquals(-1, model.afterLine(1, 9500, none));
        assertEquals(1, model.litFirst(1, 9500, none));
        assertEquals(1, model.litLast(1, 9500, none));
        // One line only.
        LyricsOverlap single = new LyricsOverlap(new long[] {5000}, new boolean[] {true}, new boolean[1], new int[] {-1});
        assertEquals(0, single.followLine(0, 7000, ANY));
    }

    @Test
    public void whenEverythingEndedAndNothingFollowsTheListStaysOnTheLastLineToFinish() {
        // A 0-10000, B 3000-6000: A ends last, so the list stays on A.
        Song s = new Song(line(0, 10000), line(3000, 6000));
        assertEquals(0, s.follow(11000));
        // A 0-5000, B 3000-10000: B ends last.
        Song t = new Song(line(0, 5000), line(3000, 10000));
        assertEquals(1, t.follow(11000));
    }

    @Test
    public void unknownLinesTakeNoPartAndBreakTheRun() {
        // Line 1 is a blank (no end): an unfinished line before it is not kept by what follows it.
        long[] end = {20000, LyricsOverlap.UNKNOWN, 14000};
        boolean[] known = {true, false, true};
        LyricsOverlap model = new LyricsOverlap(end, known, new boolean[3], new int[] {1, 2, -1});
        assertFalse(model.isKnown(1));
        assertEquals(-1, model.followLine(1, 5000, ANY));
        assertTrue(model.isKnown(2));
        // Line 2 started at 8000, line 0 would still be singing, but the blank ended the run.
        assertEquals(2, model.followLine(2, 9000, ANY));
    }

    @Test
    public void untimedLinesInsideARunAreTransparent() {
        long[] end = {10000, LyricsOverlap.UNKNOWN, 14000};
        boolean[] known = {true, false, true};
        boolean[] untimed = {false, true, false};
        LyricsOverlap model = new LyricsOverlap(end, known, untimed, new int[] {2, 2, -1});
        assertFalse(model.isKnown(1));
        assertEquals(0, model.followLine(2, 9000, ANY));
        assertEquals(2, model.followLine(2, 10000, ANY));
    }

    @Test
    public void lookupsOutsideTheDocumentTakeNoPart() {
        Song s = pairThenLine();
        assertEquals(-1, s.model.followLine(-1, 0, ANY));
        assertEquals(-1, s.model.followLine(99, 0, ANY));
        assertEquals(-1, s.model.anchorLine(-1, 0));
        assertEquals(-1, s.model.oldestUnfinished(-1, 0));
        assertEquals(-1, s.model.lastToFinish(-1));
        assertEquals(4, s.model.size());
    }

    @Test
    public void equalEndsKeepTheLowestLine() {
        Song s = new Song(line(0, 8000), line(2000, 8000));
        assertEquals(0, s.follow(5000));
        assertEquals(0, s.follow(9000));   // nothing follows: stays on the lowest of the last to finish
    }
}
