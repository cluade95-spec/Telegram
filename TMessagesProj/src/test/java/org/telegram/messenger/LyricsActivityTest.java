package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The compact player shows the active lyric or, when there is none, the song's title and artist,
 * from the position and the play state alone: at the start, in a gap in the middle of the song, after
 * seeking, paused or playing, and when it is opened mid-song.
 */
public class LyricsActivityTest {

    private static final long ROLL = 440;
    private static final long NO_END = Long.MAX_VALUE;

    private static final class Line {
        final long time;
        final boolean text;
        final boolean words;
        final long statedEnd;
        final long lastWordStart;
        final long lastWordStated;

        Line(long time, boolean text, boolean words, long statedEnd, long lastWordStart, long lastWordStated) {
            this.time = time;
            this.text = text;
            this.words = words;
            this.statedEnd = statedEnd;
            this.lastWordStart = lastWordStart;
            this.lastWordStated = lastWordStated;
        }
    }

    /** A word-timed line: sung from {@code start}, the last word starting at {@code lastStart} and ending at {@code end}. */
    private static Line sung(long start, long lastStart, long end) {
        return new Line(start, true, true, end, lastStart, end - lastStart);
    }

    /** A line-synced line (no word timing). */
    private static Line plainLine(long start) {
        return new Line(start, true, false, NO_END, 0, 0);
    }

    private static Line blank(long time) {
        return new Line(time, false, false, NO_END, 0, 0);
    }

    private static LyricsActivity document(final boolean synced, final Line... lines) {
        return new LyricsActivity(new LyricsActivity.Document() {
            @Override public int size() { return lines.length; }
            @Override public boolean isSynced() { return synced; }
            @Override public long timeMs(int line) { return lines[line].time; }
            @Override public boolean timed(int line) { return synced; }
            @Override public boolean hasText(int line) { return lines[line].text; }
            @Override public boolean hasWords(int line) { return lines[line].words; }
            @Override public long statedEndMs(int line) { return lines[line].statedEnd; }
            @Override public long lastWordStartMs(int line) { return lines[line].lastWordStart; }
            @Override public long lastWordStatedMs(int line) { return lines[line].lastWordStated; }
            @Override public boolean isPlain() { return false; }
        });
    }

    /**
     * 0: intro until 10 s. line 0 10-14 s, line 1 14.5-19 s, then an instrumental break of 11 s,
     * line 2 30-34 s, line 3 34.5-39 s (the last: the song's end is its last word's end).
     */
    private static LyricsActivity song() {
        return document(true,
                sung(10_000, 13_000, 14_000),
                sung(14_500, 17_500, 19_000),
                sung(30_000, 33_000, 34_000),
                sung(34_500, 37_500, 39_000));
    }

    private static final long SHOWS_TITLE = -1;

    private static long shown(LyricsActivity a, long position, boolean playing) {
        return a.compactLine(position, playing, ROLL);
    }

    @Test
    public void atTheStartOfTheSongThereIsNoLyric() {
        LyricsActivity a = song();
        assertEquals(SHOWS_TITLE, shown(a, 0, true));
        assertEquals(SHOWS_TITLE, shown(a, 0, false));
        assertEquals(SHOWS_TITLE, shown(a, 5_000, true));
    }

    @Test
    public void aLyricBecomesActiveAtItsTimeAndTheBarIsOnItBefore() {
        LyricsActivity a = song();
        assertEquals(0, shown(a, 10_000, true));
        assertEquals(0, shown(a, 12_000, false));
        // Playing: rolled in a little early; paused: waits for its time.
        assertEquals(0, shown(a, 9_700, true));
        assertEquals(SHOWS_TITLE, shown(a, 9_700, false));
    }

    @Test
    public void inAGapInTheMiddleOfTheSongThereIsNoLyric() {
        LyricsActivity a = song();
        // Line 1 ended at 19 s and line 2 starts at 30 s: no stale lyric for the break.
        assertEquals(1, shown(a, 18_999, true));
        assertEquals(SHOWS_TITLE, shown(a, 19_000, true));
        assertEquals(SHOWS_TITLE, shown(a, 24_000, true));
        assertEquals(SHOWS_TITLE, shown(a, 24_000, false));
        assertEquals(SHOWS_TITLE, shown(a, 29_000, true));
    }

    @Test
    public void theLyricReturnsWhenOneBecomesActiveAgain() {
        LyricsActivity a = song();
        assertEquals(2, shown(a, 29_700, true));   // rolled in
        assertEquals(SHOWS_TITLE, shown(a, 29_700, false));
        assertEquals(2, shown(a, 30_000, true));
        assertEquals(2, shown(a, 30_000, false));
        assertEquals(2, shown(a, 31_000, false));
    }

    @Test
    public void seekingIntoAGapShowsTheTitleWhetherPlayingOrPaused() {
        LyricsActivity a = song();
        for (long target : new long[] {19_000, 20_000, 25_000, 29_000}) {
            assertEquals("playing at " + target, SHOWS_TITLE, shown(a, target, true));
            assertEquals("paused at " + target, SHOWS_TITLE, shown(a, target, false));
        }
    }

    @Test
    public void seekingOutOfAGapIntoALyricShowsIt() {
        LyricsActivity a = song();
        assertEquals(SHOWS_TITLE, shown(a, 25_000, false));
        assertEquals(0, shown(a, 12_000, false));
        assertEquals(SHOWS_TITLE, shown(a, 25_000, true));
        assertEquals(3, shown(a, 36_000, true));
    }

    @Test
    public void pausingInAGapAndResumingKeepsTheTitleUntilTheNextLyric() {
        LyricsActivity a = song();
        assertEquals(SHOWS_TITLE, shown(a, 22_000, true));
        assertEquals(SHOWS_TITLE, shown(a, 22_000, false));   // paused
        assertEquals(SHOWS_TITLE, shown(a, 22_000, true));    // resumed
        assertEquals(SHOWS_TITLE, shown(a, 25_000, true));
        assertEquals(2, shown(a, 30_000, true));
    }

    @Test
    public void pausingInsideALyricKeepsItAndPausingAtTheEndOfItShowsTheTitle() {
        LyricsActivity a = song();
        assertEquals(1, shown(a, 16_000, false));
        assertEquals(1, shown(a, 16_000, true));
        assertEquals(SHOWS_TITLE, shown(a, 19_200, false));
    }

    @Test
    public void seekingWhilePausedLandsOnTheSameStateAsPlayingThere() {
        LyricsActivity a = song();
        for (long target = 0; target <= 42_000; target += 100) {
            int paused = a.compactLine(target, false, ROLL);
            int playing = a.compactLine(target, true, ROLL);
            // Playing differs only by the roll-in just before a line starts.
            if (paused != playing) {
                int next = a.lineAt(target) + 1;
                assertEquals("only the roll-in differs at " + target, next, playing);
            }
        }
    }

    @Test
    public void openingThePresentationMidSongGivesTheSameAnswerAsHavingPlayedThere() {
        // A fresh object, asked cold at positions in all kinds of states.
        long[] positions = {500, 9_999, 10_000, 15_000, 19_000, 26_000, 30_500, 34_100, 38_999, 39_000, 45_000};
        for (long position : positions) {
            int cold = song().compactLine(position, true, ROLL);
            LyricsActivity played = song();
            int warm = -2;
            for (long p = 0; p <= position; p += 250) warm = played.compactLine(p, true, ROLL);
            warm = played.compactLine(position, true, ROLL);
            assertEquals("position " + position, cold, warm);
        }
    }

    @Test
    public void afterTheLastWordHasEndedThereIsNoLyric() {
        LyricsActivity a = song();
        assertEquals(3, shown(a, 38_999, false));
        assertEquals(SHOWS_TITLE, shown(a, 39_000, false));
        assertEquals(SHOWS_TITLE, shown(a, 60_000, true));
    }

    @Test
    public void aShortBreakBetweenLinesKeepsTheLyricLikeTheLargePlayer() {
        // 3 s between the end of one line and the next is less than an instrumental gap: no dots,
        // no title.
        LyricsActivity a = document(true, sung(0, 2_000, 4_000), sung(7_000, 9_000, 11_000));
        assertEquals(0, shown(a, 5_500, false));
        assertEquals(0, shown(a, 6_900, false));
        assertEquals(1, shown(a, 7_000, false));
    }

    @Test
    public void aLineThatStatesNoEndNeverStartsAGap() {
        LyricsActivity a = document(true, plainLine(0), plainLine(20_000), plainLine(60_000));
        assertEquals(0, shown(a, 10_000, false));
        assertEquals(1, shown(a, 40_000, false));
        assertEquals(2, shown(a, 100_000, false));
    }

    @Test
    public void aTimedBlankHasNoLyricAndAnIntroOfSevenSecondsIsAGap() {
        LyricsActivity a = document(true, plainLine(8_000), blank(20_000), plainLine(40_000));
        assertEquals(SHOWS_TITLE, shown(a, 3_000, false));   // before the first line
        assertEquals(0, shown(a, 12_000, false));
        assertEquals(SHOWS_TITLE, shown(a, 20_000, false));  // blank
        assertEquals(SHOWS_TITLE, shown(a, 30_000, false));  // blank, also an interlude (singing ended at the blank)
        assertEquals(2, shown(a, 40_000, false));
    }

    @Test
    public void unsyncedLyricsNeverHaveAnActiveLine() {
        LyricsActivity a = document(false, plainLine(0), plainLine(1_000));
        assertEquals(SHOWS_TITLE, shown(a, 500, true));
        assertEquals(-1, a.lineAt(500));
        assertFalse(a.isIdle(500));
    }

    @Test
    public void lineSyncedLyricsHaveNoEndOfSong() {
        LyricsActivity a = document(true, plainLine(0), plainLine(5_000));
        assertEquals(1, shown(a, 600_000, false));
    }

    @Test
    public void nextIdleStartTellsWhenAnActiveLyricEnds() {
        LyricsActivity a = song();
        assertEquals(19_000, a.nextIdleStartMs(15_000));
        assertEquals(39_000, a.nextIdleStartMs(25_000));   // inside the gap: the next one is the song's end
        assertEquals(39_000, a.nextIdleStartMs(31_000));
        assertEquals(LyricsActivity.NONE, a.nextIdleStartMs(39_000));
        assertEquals(19_000, a.nextIdleStartMs(12_000));
        // The intro is a gap from 0 that is already running at position 0: the next start is later.
        assertEquals(19_000, a.nextIdleStartMs(0));
    }

    @Test
    public void isIdleMatchesTheGapsAndTheEnd() {
        LyricsActivity a = song();
        assertTrue(a.isIdle(0));
        assertTrue(a.isIdle(9_999));
        assertFalse(a.isIdle(10_000));
        assertFalse(a.isIdle(18_999));
        assertTrue(a.isIdle(19_000));
        assertTrue(a.isIdle(29_999));
        assertFalse(a.isIdle(30_000));
        assertTrue(a.isIdle(39_000));
    }

    @Test
    public void everyStateOfTheSongIsAFunctionOfPositionAndPlayStateOnly() {
        LyricsActivity first = song();
        LyricsActivity second = song();
        for (long position = 0; position < 45_000; position += 37) {
            assertEquals(first.compactLine(position, true, ROLL), second.compactLine(position, true, ROLL));
            assertEquals(first.compactLine(position, false, ROLL), second.compactLine(position, false, ROLL));
        }
    }
}
