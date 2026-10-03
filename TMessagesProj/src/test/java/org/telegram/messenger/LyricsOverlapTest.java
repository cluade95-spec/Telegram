package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Overlapping lyric lines: the list changes what it keeps when a line STARTS. The rows that have
 * finished are retired from the top, one by one, up to the first that still sings; a finished row
 * waits for the next line to start. Once nothing sings the list moves on to the next line, as for
 * ordinary lines. Everything is a pure function of the position.
 */
public class LyricsOverlapTest {

    private static final LyricsOverlap.Followable ANY = line -> true;

    /** Lines given as {start, end}, in time order. */
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
            model = new LyricsOverlap(start, end, known, untimed, next);
        }

        int size() {
            return start.length;
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

        /**
         * The same answer worked out the way playback reaches it: walk the lines that have started in
         * order, and at each start retire the finished rows from the top, up to the first that still
         * sings; then, if nothing sings at the position, the next line takes the anchor.
         * {follow, litFirst, litLast}.
         */
        int[] byPlaying(long position) {
            int anchor = 0;
            int current = -1;
            long maxEnd = Long.MIN_VALUE;
            for (int k = 0; k < start.length && start[k] <= position; k++) {
                current = k;
                maxEnd = Math.max(maxEnd, end[k]);
                while (anchor < k && end[anchor] <= start[k]) anchor++;
            }
            if (current < 0) return new int[] {-1, -1, -1};
            if (maxEnd <= position && current + 1 < start.length) {
                return new int[] {current + 1, current + 1, current + 1};
            }
            return new int[] {anchor, anchor, current};
        }
    }

    private static long[] line(long start, long end) {
        return new long[] {start, end};
    }

    // ---- A overlaps B; A finishes while B sings; C has not started ---------------------------------

    private static Song pairThenOverlappingLine() {
        // A 0-5 s, B 3-12 s, C 10-15 s, D 16-20 s.
        return new Song(line(0, 5000), line(3000, 12000), line(10000, 15000), line(16000, 20000));
    }

    @Test
    public void aFinishedFirstLineStaysUntilTheNextLineStarts() {
        Song s = pairThenOverlappingLine();
        assertEquals(0, s.follow(1000));
        assertEquals(0, s.follow(3500));          // B has started, A unfinished
        assertEquals(0, s.follow(4999));
        // A finished at 5 s, B still sings, C starts only at 10 s: A stays on screen with B.
        assertEquals(0, s.follow(5000));
        assertEquals(0, s.follow(7500));
        assertEquals(0, s.follow(9999));
        assertEquals(0, s.litFirst(7500));
        assertEquals(1, s.litLast(7500));
    }

    @Test
    public void whenTheNextLineStartsOnlyTheFinishedOneRetires() {
        Song s = pairThenOverlappingLine();
        // C starts at 10 s while B still sings: A scrolls out, B stays, C comes forward.
        assertEquals(1, s.follow(10000));
        assertEquals(1, s.litFirst(10000));
        assertEquals(2, s.litLast(10000));
        assertEquals(1, s.follow(11999));
    }

    @Test
    public void aRowThatFinishesWhileAnotherStillSingsWaitsForTheNextStartToo() {
        Song s = pairThenOverlappingLine();
        // B finished at 12 s; C still sings and D has not started: B stays until D starts or nothing sings.
        assertEquals(1, s.follow(12000));
        assertEquals(1, s.follow(14999));
        assertEquals(1, s.litFirst(13000));
        assertEquals(2, s.litLast(13000));
    }

    @Test
    public void onceNothingSingsTheListMovesToTheNextLineLikeOrdinaryLines() {
        Song s = pairThenOverlappingLine();
        assertEquals(3, s.follow(15000));       // C ended at 15 s; D starts at 16 s
        assertEquals(3, s.litFirst(15500));
        assertEquals(3, s.litLast(15500));
        assertEquals(3, s.follow(16000));
    }

    @Test
    public void finishedRowsAreNeverRetiredByTheirOwnEndTimestamp() {
        Song s = threeOverlaps();
        // Walk every millisecond: the kept row changes only when a line starts, or when nothing sings
        // any more (the advance to the next line), never at the end of a row that has company.
        List<long[]> changes = new ArrayList<>();
        int previous = s.follow(0);
        for (long position = 1; position <= 30000; position++) {
            int now = s.follow(position);
            if (now != previous) changes.add(new long[] {position, now});
            previous = now;
        }
        assertEquals(3, changes.size());
        assertEquals(8000, changes.get(0)[0]);    // C starts: A (ended 5 s) retires, B stays
        assertEquals(1, changes.get(0)[1]);
        assertEquals(12000, changes.get(1)[0]);   // D starts: B (ended 9 s) retires, C stays
        assertEquals(2, changes.get(1)[1]);
        assertEquals(18000, changes.get(2)[0]);   // D ended: nothing sings, on to E
        assertEquals(4, changes.get(2)[1]);
        // Not at the ends of A, B and C.
        for (long[] change : changes) {
            assertFalse(change[0] == 5000 || change[0] == 9000 || change[0] == 13000);
        }
    }

    // ---- A and B both finished when C starts --------------------------------------------------------

    @Test
    public void bothFinishedWhenTheNextLineStartsRetireTogether() {
        // A 0-4 s, B 2-6 s, C 8-12 s.
        Song s = new Song(line(0, 4000), line(2000, 6000), line(8000, 12000));
        assertEquals(0, s.follow(2500));
        assertEquals(0, s.follow(5000));        // A finished, B sings: A waits
        assertEquals(2, s.follow(6000));        // nothing sings: on to C
        assertEquals(2, s.follow(8000));        // C starts: A and B are gone
        assertEquals(2, s.litFirst(8500));
        assertEquals(2, s.litLast(8500));
    }

    @Test
    public void bothFinishedExactlyWhenTheNextLineStartsRetireTogether() {
        Song s = new Song(line(0, 4000), line(2000, 8000), line(8000, 12000));
        assertEquals(0, s.follow(7999));
        assertEquals(2, s.follow(8000));
        assertEquals(2, s.litFirst(8000));
    }

    @Test
    public void aLineStartingAfterABreakFollowsTheBreak() {
        // A 0-5 s, B 3-12 s, C 14-18 s: gap between the end of B and the start of C.
        Song s = new Song(line(0, 5000), line(3000, 12000), line(14000, 18000));
        assertEquals(0, s.follow(11999));       // B sings, A waits
        assertEquals(2, s.follow(12000));       // nothing sings: A and B retire together, C comes in
        assertEquals(2, s.follow(13999));
        assertEquals(2, s.follow(14000));
    }

    @Test
    public void aGapBetweenAFinishedPairMemberAndTheNextStartKeepsThePair() {
        // B outlasts the gap: A 0-5 s, B 3-15 s, C 14-18 s.
        Song s = new Song(line(0, 5000), line(3000, 15000), line(14000, 18000));
        assertEquals(0, s.follow(13999));       // A waits through the whole gap since its end
        assertEquals(1, s.follow(14000));       // C starts: A retires, B stays
        assertEquals(1, s.litFirst(14500));
        assertEquals(2, s.litLast(14500));
    }

    // ---- B overlaps C; B overlaps C and C overlaps D ------------------------------------------------

    private static Song twoOverlaps() {
        // A 0-5 s, B 3-9 s, C 8-14 s, D 16-20 s.
        return new Song(line(0, 5000), line(3000, 9000), line(8000, 14000), line(16000, 20000));
    }

    @Test
    public void chainOfTwoOverlapsRetiresAtTheStarts() {
        Song s = twoOverlaps();
        assertEquals(0, s.follow(4000));      // A, B
        assertEquals(0, s.follow(6000));      // A finished; C has not started: A stays
        assertEquals(0, s.follow(7999));
        assertEquals(1, s.follow(8000));      // C starts: A retires, B (till 9 s) stays
        assertEquals(1, s.litFirst(8500));
        assertEquals(2, s.litLast(8500));
        assertEquals(1, s.follow(9500));      // B finished, C sings, D has not started: B stays
        assertEquals(1, s.follow(13999));
        assertEquals(3, s.follow(14000));     // nothing sings: on to D
        assertEquals(3, s.follow(16000));
    }

    private static Song threeOverlaps() {
        // A 0-5, B 4-9, C 8-13, D 12-18, E 20-24 s.
        return new Song(line(0, 5000), line(4000, 9000), line(8000, 13000), line(12000, 18000), line(20000, 24000));
    }

    @Test
    public void chainOfThreeOverlapsDoesNotFreezeTheList() {
        Song s = threeOverlaps();
        assertEquals(0, s.follow(4500));      // A, B
        assertEquals(0, s.follow(7999));      // A finished at 5, waits for C
        assertEquals(1, s.follow(8000));      // C starts: A out, B stays
        assertEquals(1, s.follow(11999));     // B finished at 9, waits for D
        assertEquals(2, s.follow(12000));     // D starts: B out, C stays
        assertEquals(2, s.follow(17999));
        assertEquals(4, s.follow(18000));     // D ended: nothing sings, on to E
        assertEquals(4, s.follow(19000));
        assertEquals(4, s.follow(20000));
    }

    @Test
    public void whenDStartsTheFinishedRowsRetireTogetherAndASingingOlderOneStays() {
        // A 0-5, B 3-9, C 8-14, D 13-18: A and B are both finished when D starts; C still sings.
        Song s = new Song(line(0, 5000), line(3000, 9000), line(8000, 14000), line(13000, 18000), line(25000, 30000));
        assertEquals(1, s.follow(8000));      // A retired at C's start
        assertEquals(1, s.follow(12999));     // B finished at 9 but waits for D
        assertEquals(2, s.follow(13000));     // D starts: B retires, C stays, A is not kept because B overlaps C
        assertEquals(2, s.follow(15000));     // C finished at 14, D sings: C waits for E / nothing singing
        assertEquals(4, s.follow(18000));
        // A genuinely active older row stays: A 0-20 s outlasts everything.
        Song long1 = new Song(line(0, 20000), line(3000, 6000), line(8000, 10000), line(12000, 14000));
        assertEquals(0, long1.follow(12000));
        assertEquals(0, long1.follow(19999));
    }

    @Test
    public void anOverlapNeverKeepsTheOldestLineAliveIndefinitely() {
        Song s = twoOverlaps();
        // The old chain model kept A on the anchor until C ended (14 s).
        assertTrue(s.follow(8000) != 0);
        assertTrue(s.follow(13000) != 0);
        assertTrue(s.follow(13000) != 1 || s.lineAt(13000) == 2);
    }

    // ---- long chains --------------------------------------------------------------------------------

    @Test
    public void anyLengthOfChainKeepsMoving() {
        // 40 lines, each overlapping the next by 1 s.
        long[][] lines = new long[40][];
        for (int i = 0; i < 40; i++) lines[i] = line(i * 3000L, i * 3000L + 4000L);
        Song s = new Song(lines);
        int previous = 0;
        for (long position = 0; position < 40 * 3000L; position += 50) {
            int current = s.lineAt(position);
            int follow = s.follow(position);
            assertTrue(follow >= previous);
            // The previous line is kept while it sings, once the current one has started; no more.
            assertEquals("at " + position, Math.max(0, current - 1), follow);
            assertEquals(follow, s.litFirst(position));
            assertEquals(current, s.litLast(position));
            previous = follow;
        }
        assertEquals(19, s.follow(20 * 3000L));
        assertEquals(19, s.follow(20 * 3000L + 2999));
        assertEquals(20, s.follow(21 * 3000L));
    }

    @Test
    public void aLongChainOfPairsThatOverlapAtEveryOtherLine() {
        // Pairs (0,1) (2,3) ... overlapping within the pair only, separate from the next pair.
        long[][] lines = new long[20][];
        for (int i = 0; i < 20; i += 2) {
            long base = i * 4000L;
            lines[i] = line(base, base + 3000);
            lines[i + 1] = line(base + 2000, base + 6000);
        }
        Song s = new Song(lines);
        for (int i = 0; i < 20; i += 2) {
            long base = i * 4000L;
            assertEquals(i, s.follow(base + 2500));          // the pair together
            assertEquals(i, s.follow(base + 3500));          // first finished, second sings: waits
            // The second pair starts 4 s later; the first pair is finished by then.
            if (i + 2 < 20) assertEquals(i + 2, s.follow(base + 8000));
        }
    }

    // ---- ordinary lines -----------------------------------------------------------------------------

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

    // ---- seeking and playing ------------------------------------------------------------------------

    private static void assertSameAsPlaying(String name, Song s, long step, long limit) {
        for (long position = 0; position <= limit; position += step) {
            int[] expected = s.byPlaying(position);
            assertEquals(name + " follow at " + position, expected[0], s.follow(position));
            assertEquals(name + " first lit at " + position, expected[1], s.litFirst(position));
            assertEquals(name + " last lit at " + position, expected[2], s.litLast(position));
        }
    }

    @Test
    public void seekingStraightIntoEveryStateGivesWhatPlayingThereGives() {
        assertSameAsPlaying("pair", pairThenOverlappingLine(), 7, 24000);
        assertSameAsPlaying("two overlaps", twoOverlaps(), 7, 24000);
        assertSameAsPlaying("three overlaps", threeOverlaps(), 7, 30000);
        assertSameAsPlaying("ordinary", new Song(line(0, 3000), line(4000, 7000), line(7000, 10000), line(12000, 15000)), 7, 20000);
    }

    @Test
    public void seekingStraightIntoEveryStateOfRandomOverlapSequences() {
        for (long seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            int n = 6 + random.nextInt(30);
            long[][] lines = new long[n][];
            long t = random.nextInt(2000);
            for (int i = 0; i < n; i++) {
                long length = 400 + random.nextInt(9000);
                lines[i] = line(t, t + length);
                t += 300 + random.nextInt(4000);
            }
            Song s = new Song(lines);
            assertSameAsPlaying("seed " + seed, s, 37, t + 12000);
        }
    }

    @Test
    public void everyDirectSeekToAnExplicitPositionMatches() {
        Song s = threeOverlaps();
        long[] positions = {0, 3999, 4000, 4500, 4999, 5000, 7999, 8000, 8500, 9000, 11999, 12000, 12500, 13000, 17999,
                18000, 19999, 20000, 23999, 24000, 60000};
        // In any order: forwards, backwards, repeated.
        int[] order = {5, 0, 12, 20, 7, 7, 3, 18, 1, 14, 9, 2, 16, 10, 4, 19, 6, 11, 13, 15, 8, 17};
        for (int index : order) {
            long position = positions[Math.min(index, positions.length - 1)];
            int[] expected = s.byPlaying(position);
            assertEquals("position " + position, expected[0], s.follow(position));
        }
    }

    @Test
    public void pauseAndResumeDoNotChangeTheResultForTheSamePosition() {
        Song s = twoOverlaps();
        // Playing through 0..24 s every 50 ms, once straight and once with pauses (the same position
        // queried over and over) at awkward places; the answers at every position are identical.
        long[] pauses = {2999, 5000, 7999, 8000, 9000, 13999, 14000, 15000};
        for (long position = 0; position <= 24000; position += 50) {
            int straight = s.follow(position);
            boolean paused = false;
            for (long pause : pauses) paused |= Math.abs(pause - position) < 50;
            if (paused) {
                for (int frame = 0; frame < 120; frame++) {
                    assertEquals("paused at " + position, straight, s.follow(position));
                }
            }
            assertEquals("resumed at " + position, straight, s.follow(position));
        }
        // Pausing inside the waiting state keeps the finished row.
        assertEquals(0, s.follow(6000));
        assertEquals(0, s.follow(6000));
    }

    // ---- lines that take no part --------------------------------------------------------------------

    @Test
    public void aLineThatMayNotFollowKeepsTheListWhereItWas() {
        // Next line is a blank / behind a dots row: the list does not move to it.
        long[] start = {0, 3000};
        long[] end = {5000, 9000};
        LyricsOverlap model = new LyricsOverlap(start, end, new boolean[] {true, true}, new boolean[2], new int[] {1, -1});
        LyricsOverlap.Followable none = line -> false;
        // Both finished at 9.5 s, nothing may follow: stays where it was (A and B were kept together).
        assertEquals(0, model.followLine(1, 9500, none));
        assertEquals(-1, model.afterLine(1, 9500, none));
        assertEquals(0, model.litFirst(1, 9500, none));
        assertEquals(1, model.litLast(1, 9500, none));
        // One line only.
        LyricsOverlap single = new LyricsOverlap(new long[] {0}, new long[] {5000}, new boolean[] {true}, new boolean[1], new int[] {-1});
        assertEquals(0, single.followLine(0, 7000, ANY));
    }

    @Test
    public void unknownLinesTakeNoPartAndBreakTheRun() {
        // Line 1 is a blank (no end): an unfinished line before it is not kept by what follows it.
        long[] start = {0, 5000, 8000};
        long[] end = {20000, LyricsOverlap.UNKNOWN, 14000};
        boolean[] known = {true, false, true};
        LyricsOverlap model = new LyricsOverlap(start, end, known, new boolean[3], new int[] {1, 2, -1});
        assertFalse(model.isKnown(1));
        assertEquals(-1, model.followLine(1, 5000, ANY));
        assertTrue(model.isKnown(2));
        // Line 2 started at 8000, line 0 would still be singing, but the blank ended the run.
        assertEquals(2, model.followLine(2, 9000, ANY));
        assertEquals(LyricsOverlap.UNKNOWN, model.endOf(1));
        assertEquals(14000, model.endOf(2));
    }

    @Test
    public void untimedLinesInsideARunAreTransparent() {
        long[] start = {0, 0, 8000};
        long[] end = {10000, LyricsOverlap.UNKNOWN, 14000};
        boolean[] known = {true, false, true};
        boolean[] untimed = {false, true, false};
        LyricsOverlap model = new LyricsOverlap(start, end, known, untimed, new int[] {2, 2, -1});
        assertFalse(model.isKnown(1));
        assertEquals(0, model.followLine(2, 9000, ANY));     // A (until 10 s) still sings when line 2 started
        // A finished at 10 s, but line 2 sings until 14 s and no line starts: A waits, as in any pair.
        assertEquals(0, model.followLine(2, 10000, ANY));
        assertEquals(0, model.followLine(2, 13999, ANY));
    }

    @Test
    public void lookupsOutsideTheDocumentTakeNoPart() {
        Song s = pairThenOverlappingLine();
        assertEquals(-1, s.model.followLine(-1, 0, ANY));
        assertEquals(-1, s.model.followLine(99, 0, ANY));
        assertEquals(-1, s.model.anchorLine(-1));
        assertEquals(-1, s.model.oldestUnfinished(-1, 0));
        assertEquals(4, s.model.size());
        assertEquals(12000, s.model.endOf(1));
    }

    @Test
    public void equalEndsKeepTheOlderRowUntilTheNextStart() {
        Song s = new Song(line(0, 8000), line(2000, 8000), line(9000, 12000));
        assertEquals(0, s.follow(5000));
        assertEquals(2, s.follow(8500));    // nothing sings: both retire, on to the next line
    }

    @Test
    public void aLineWithNoLengthIsItsOwnAnchor() {
        // Ends at its own start.
        Song s = new Song(line(0, 0), line(1000, 3000));
        assertEquals(0, s.model.anchorLine(0));
        assertEquals(1, s.follow(1500));
    }
}
