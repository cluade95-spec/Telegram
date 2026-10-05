package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The compact player shows the title and artist whenever playback is paused, and while playing the
 * active lyric or, when there is none, the title and artist. Everything is derived from the position
 * and the play state alone: at the start, in a gap in the middle of the song, after seeking,
 * paused or playing, on resume, and when the bar is opened mid-song.
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

    /** What the compact bar shows while playing. */
    private static int playing(LyricsActivity a, long position) {
        return a.compactLine(position, true, ROLL);
    }

    /** What the compact bar shows while paused. */
    private static int paused(LyricsActivity a, long position) {
        return a.compactLine(position, false, ROLL);
    }

    // ---- playing ------------------------------------------------------------------------------

    @Test
    public void atTheStartOfTheSongThereIsNoLyric() {
        LyricsActivity a = song();
        assertEquals(SHOWS_TITLE, playing(a, 0));
        assertEquals(SHOWS_TITLE, playing(a, 5_000));
    }

    @Test
    public void aLyricBecomesActiveAtItsTimeAndTheBarIsOnItBefore() {
        LyricsActivity a = song();
        assertEquals(0, playing(a, 10_000));
        assertEquals(0, playing(a, 12_000));
        // Rolled in a little early while playing.
        assertEquals(0, playing(a, 9_700));
        assertEquals(SHOWS_TITLE, playing(a, 9_000));
    }

    @Test
    public void inAGapInTheMiddleOfTheSongThereIsNoLyric() {
        LyricsActivity a = song();
        // Line 1 ended at 19 s and line 2 starts at 30 s: no stale lyric for the break.
        assertEquals(1, playing(a, 18_999));
        assertEquals(SHOWS_TITLE, playing(a, 19_000));
        assertEquals(SHOWS_TITLE, playing(a, 24_000));
        assertEquals(SHOWS_TITLE, playing(a, 29_000));
    }

    @Test
    public void theLyricReturnsWhenOneBecomesActiveAgain() {
        LyricsActivity a = song();
        assertEquals(2, playing(a, 29_700));   // rolled in
        assertEquals(2, playing(a, 30_000));
        assertEquals(2, playing(a, 31_000));
    }

    @Test
    public void afterTheLastWordHasEndedThereIsNoLyric() {
        LyricsActivity a = song();
        assertEquals(3, playing(a, 38_999));
        assertEquals(SHOWS_TITLE, playing(a, 39_000));
        assertEquals(SHOWS_TITLE, playing(a, 60_000));
    }

    // ---- paused: always the title and artist ---------------------------------------------------

    @Test
    public void pauseDuringAnActiveLyricShowsTheTitleAndArtist() {
        LyricsActivity a = song();
        assertEquals(1, playing(a, 16_000));          // the lyric while playing
        assertEquals(SHOWS_TITLE, paused(a, 16_000)); // the same position, paused
        assertEquals(SHOWS_TITLE, paused(a, 10_000));
        assertEquals(SHOWS_TITLE, paused(a, 18_999));
        assertEquals(SHOWS_TITLE, paused(a, 35_000));
    }

    @Test
    public void pauseDuringAGapShowsTheTitleAndArtist() {
        LyricsActivity a = song();
        assertEquals(SHOWS_TITLE, playing(a, 24_000));
        assertEquals(SHOWS_TITLE, paused(a, 24_000));
        assertEquals(SHOWS_TITLE, paused(a, 19_000));
        assertEquals(SHOWS_TITLE, paused(a, 29_999));
    }

    @Test
    public void pauseBeforeTheFirstLyricShowsTheTitleAndArtist() {
        LyricsActivity a = song();
        assertEquals(SHOWS_TITLE, paused(a, 0));
        assertEquals(SHOWS_TITLE, paused(a, 5_000));
        // Even where the next lyric would already have rolled in while playing.
        assertEquals(0, playing(a, 9_700));
        assertEquals(SHOWS_TITLE, paused(a, 9_700));
    }

    @Test
    public void pauseAfterTheFinalLyricShowsTheTitleAndArtist() {
        LyricsActivity a = song();
        assertEquals(3, playing(a, 38_000));
        assertEquals(SHOWS_TITLE, paused(a, 38_000));
        assertEquals(SHOWS_TITLE, paused(a, 39_000));
        assertEquals(SHOWS_TITLE, paused(a, 120_000));
    }

    @Test
    public void seekWhilePausedIntoAnActiveLyricShowsTheTitleAndArtist() {
        LyricsActivity a = song();
        // Paused in a gap, then seeking into lyrics: nothing but the title and artist, at each target.
        for (long target : new long[] {10_000, 12_000, 16_000, 31_000, 36_000}) {
            assertTrue("a lyric is active at " + target, a.activeLine(target) >= 0);
            assertEquals("paused at " + target, SHOWS_TITLE, paused(a, target));
        }
    }

    @Test
    public void seekWhilePausedIntoAGapShowsTheTitleAndArtist() {
        LyricsActivity a = song();
        for (long target : new long[] {19_000, 20_000, 25_000, 29_000}) {
            assertEquals("no lyric is active at " + target, -1, a.activeLine(target));
            assertEquals("paused at " + target, SHOWS_TITLE, paused(a, target));
        }
    }

    @Test
    public void enteringTheCompactPlayerWhilePausedShowsTheTitleAndArtist() {
        // A fresh object (the bar opened or restored mid-song), asked cold while paused.
        for (long position : new long[] {0, 9_999, 10_000, 15_000, 19_000, 26_000, 30_500, 38_999, 39_000, 45_000}) {
            assertEquals("opened paused at " + position, SHOWS_TITLE, paused(song(), position));
        }
    }

    @Test
    public void pausedIsTheTitleAndArtistAtEveryPositionOfTheSong() {
        LyricsActivity a = song();
        for (long position = 0; position < 45_000; position += 7) {
            assertEquals("paused at " + position, SHOWS_TITLE, paused(a, position));
        }
    }

    // ---- resume --------------------------------------------------------------------------------

    @Test
    public void resumeIntoAnActiveLyricShowsItAtOnce() {
        LyricsActivity a = song();
        // Paused inside lyric 1, then resumed at the same position: derived afresh, not remembered.
        assertEquals(SHOWS_TITLE, paused(a, 16_000));
        assertEquals(1, playing(a, 16_000));
        // Paused in a gap, sought into a lyric while paused, then resumed there.
        assertEquals(SHOWS_TITLE, paused(a, 24_000));
        assertEquals(SHOWS_TITLE, paused(a, 33_000));
        assertEquals(2, playing(a, 33_000));
        // Resumed into the last lyric.
        assertEquals(3, playing(a, 36_000));
    }

    @Test
    public void resumeIntoAGapShowsTheTitleAndArtist() {
        LyricsActivity a = song();
        assertEquals(SHOWS_TITLE, paused(a, 24_000));
        assertEquals(SHOWS_TITLE, playing(a, 24_000));
        // Paused inside a lyric, sought into the gap while paused, resumed there.
        assertEquals(SHOWS_TITLE, paused(a, 16_000));
        assertEquals(SHOWS_TITLE, paused(a, 22_000));
        assertEquals(SHOWS_TITLE, playing(a, 22_000));
        // And the lyric returns when playback reaches the next one.
        assertEquals(2, playing(a, 29_700));
        assertEquals(2, playing(a, 30_000));
    }

    @Test
    public void resumingNeverKeepsAnythingFromBeforeThePause() {
        LyricsActivity a = song();
        // Pause/resume at awkward places in any order: each answer depends only on its own position and state.
        long[] positions = {16_000, 24_000, 12_000, 36_000, 9_700, 19_000, 38_999, 39_000, 30_000};
        for (long position : positions) {
            int expectedPlaying = song().compactLine(position, true, ROLL);
            assertEquals(SHOWS_TITLE, paused(a, position));
            assertEquals("resumed at " + position, expectedPlaying, playing(a, position));
            assertEquals(SHOWS_TITLE, paused(a, position));
        }
    }

    @Test
    public void whilePlayingTheBarMatchesTheActiveLyricExceptForTheRollIn() {
        LyricsActivity a = song();
        for (long target = 0; target <= 42_000; target += 100) {
            int shown = playing(a, target);
            int active = a.activeLine(target);
            // Playing differs from the active lyric only by the roll-in just before a line starts.
            if (shown != active) {
                int next = a.lineAt(target) + 1;
                assertEquals("only the roll-in differs at " + target, next, shown);
            }
        }
    }

    @Test
    public void openingThePlayingBarMidSongGivesTheSameAnswerAsHavingPlayedThere() {
        long[] positions = {500, 9_999, 10_000, 15_000, 19_000, 26_000, 30_500, 34_100, 38_999, 39_000, 45_000};
        for (long position : positions) {
            int cold = playing(song(), position);
            LyricsActivity played = song();
            int warm = -2;
            for (long p = 0; p <= position; p += 250) warm = playing(played, p);
            warm = playing(played, position);
            assertEquals("position " + position, cold, warm);
        }
    }

    // ---- other documents -----------------------------------------------------------------------

    @Test
    public void aShortBreakBetweenLinesKeepsTheLyricLikeTheLargePlayer() {
        // 3 s between the end of one line and the next is less than an instrumental gap: no dots,
        // no title while playing.
        LyricsActivity a = document(true, sung(0, 2_000, 4_000), sung(7_000, 9_000, 11_000));
        assertEquals(0, playing(a, 5_500));
        assertEquals(0, playing(a, 6_300));
        assertEquals(1, playing(a, 7_000));
        // Paused: the title and artist, as everywhere.
        assertEquals(SHOWS_TITLE, paused(a, 5_500));
    }

    @Test
    public void aLineThatStatesNoEndNeverStartsAGap() {
        LyricsActivity a = document(true, plainLine(0), plainLine(20_000), plainLine(60_000));
        assertEquals(0, playing(a, 10_000));
        assertEquals(1, playing(a, 40_000));
        assertEquals(2, playing(a, 100_000));
    }

    @Test
    public void aTimedBlankHasNoLyricAndAnIntroOfSevenSecondsIsAGap() {
        LyricsActivity a = document(true, plainLine(8_000), blank(20_000), plainLine(40_000));
        assertEquals(SHOWS_TITLE, playing(a, 3_000));   // before the first line
        assertEquals(0, playing(a, 12_000));
        assertEquals(SHOWS_TITLE, playing(a, 20_000));  // blank
        assertEquals(SHOWS_TITLE, playing(a, 30_000));  // blank, also an interlude (singing ended at the blank)
        assertEquals(2, playing(a, 40_000));
    }

    @Test
    public void unsyncedLyricsNeverHaveAnActiveLine() {
        LyricsActivity a = document(false, plainLine(0), plainLine(1_000));
        assertEquals(SHOWS_TITLE, playing(a, 500));
        assertEquals(-1, a.lineAt(500));
        assertFalse(a.isIdle(500));
    }

    @Test
    public void lineSyncedLyricsHaveNoEndOfSong() {
        LyricsActivity a = document(true, plainLine(0), plainLine(5_000));
        assertEquals(1, playing(a, 600_000));
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
