package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Enhanced LRC through the real parser. A line with per-word timing stays word-timed when its main
 * words and its background words (the echo in parentheses) are timed independently: the echo may
 * start before the main words written ahead of it have ended, and it may run over lines that follow.
 */
public class SyncedLyricsEnhancedLrcTest {

    /** The reported block, verbatim. */
    private static final String FIXTURE =
            "[03:08.242]<03:08.242>What <03:08.596>are <03:08.696>you <03:08.914>doing, <03:09.562>love?<03:10.642> (<03:10.052>What <03:10.414>are <03:10.556>you <03:10.823>doing <03:11.340>to <03:11.552>me?<03:11.710> <03:11.860>What <03:12.200>are <03:12.412>you <03:12.650>doing <03:13.214>now?<03:13.777>)\n"
            + "[03:12.794]<03:12.794>Yeah, <03:13.190>you <03:13.506>just <03:13.874>want <03:14.204>attention<03:15.103> (<03:14.789>What <03:15.183>are <03:15.502>you <03:15.848>doing <03:16.288>to <03:16.524>me? <03:16.777>What <03:17.052>are <03:17.308>you <03:17.513>doing <03:18.265>now?<03:18.823>)\n"
            + "[03:15.363]<03:15.363>I <03:15.682>knew <03:16.110>from <03:16.372>the <03:16.723>start<03:17.882>\n"
            + "[03:17.892]<03:17.892>You're <03:18.288>just <03:18.693>making <03:19.199>sure <03:19.526>I'm <03:19.925>never <03:20.502>getting <03:21.174>over <03:21.590>you,<03:22.203> <03:22.710>oh<03:24.732> (<03:19.598>What <03:20.046>are <03:20.294>you <03:20.454>doing <03:20.983>to <03:21.083>me?<03:21.321> <03:21.527>What <03:21.825>are <03:21.961>you <03:22.244>doing <03:22.825>now?<03:23.583>)\n";

    private static final int BROKEN = 1;

    private static long ms(int minutes, int seconds, int millis) {
        return (minutes * 60L + seconds) * 1000 + millis;
    }

    private static SyncedLyricsController.Lyrics fixture() {
        return SyncedLyricsController.parse(FIXTURE);
    }

    private static String word(SyncedLyricsController.Line line, int k) {
        return line.text.substring(line.segments.startOffset(k), line.segments.endOffset(k)).trim();
    }

    private static List<String> words(SyncedLyricsController.Line line) {
        List<String> result = new ArrayList<>();
        for (int k = 0; k < line.segments.size(); k++) result.add(word(line, k));
        return result;
    }

    private static int segmentStartingAt(SyncedLyricsController.Segments segments, long time) {
        for (int k = 0; k < segments.size(); k++) {
            if (segments.startTimeMs(k) == time) return k;
        }
        return -1;
    }

    @Test
    public void allFourLinesParseAndEveryOneIsWordTimed() {
        SyncedLyricsController.Lyrics lyrics = fixture();
        assertEquals(SyncedLyricsController.Kind.SYNCED, lyrics.kind);
        assertEquals(4, lyrics.lines.size());
        for (int i = 0; i < 4; i++) {
            SyncedLyricsController.Line line = lyrics.lines.get(i);
            assertTrue("line " + i + " is timed", line.timed);
            assertNotNull("line " + i + " keeps its word timing", line.segments);
        }
        assertEquals(ms(3, 8, 242), lyrics.lines.get(0).timeMs);
        assertEquals(ms(3, 12, 794), lyrics.lines.get(1).timeMs);
        assertEquals(ms(3, 15, 363), lyrics.lines.get(2).timeMs);
        assertEquals(ms(3, 17, 892), lyrics.lines.get(3).timeMs);
    }

    @Test
    public void theBrokenLineRemainsWordTimedWithBothPartsIntact() {
        SyncedLyricsController.Line line = fixture().lines.get(BROKEN);
        assertEquals("Yeah, you just want attention (What are you doing to me? What are you doing now?)", line.text);
        SyncedLyricsController.Segments segments = line.segments;
        assertNotNull(segments);
        // Five main words and eleven background words; the two end tags are not words.
        assertEquals(16, segments.size());
        assertEquals(Arrays.asList("Yeah,", "you", "just", "want", "attention",
                "What", "are", "you", "doing", "to", "me?", "What", "are", "you", "doing", "now?"), sortedByTextOffset(line));
    }

    /** The words in text order, whatever order the segments are kept in. */
    private static List<String> sortedByTextOffset(SyncedLyricsController.Line line) {
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < line.segments.size(); k++) order.add(k);
        order.sort((a, b) -> Integer.compare(line.segments.startOffset(a), line.segments.startOffset(b)));
        List<String> result = new ArrayList<>();
        for (int k : order) result.add(word(line, k));
        return result;
    }

    @Test
    public void everyWordKeepsItsOwnStartTime() {
        SyncedLyricsController.Line line = fixture().lines.get(BROKEN);
        long[] main = {ms(3, 12, 794), ms(3, 13, 190), ms(3, 13, 506), ms(3, 13, 874), ms(3, 14, 204)};
        long[] background = {ms(3, 14, 789), ms(3, 15, 183), ms(3, 15, 502), ms(3, 15, 848), ms(3, 16, 288), ms(3, 16, 524),
                ms(3, 16, 777), ms(3, 17, 52), ms(3, 17, 308), ms(3, 17, 513), ms(3, 18, 265)};
        for (long time : main) assertTrue("main start " + time, segmentStartingAt(line.segments, time) >= 0);
        for (long time : background) assertTrue("background start " + time, segmentStartingAt(line.segments, time) >= 0);
        // The last background word starts after the line after next has (03:17.892): still its own time.
        assertTrue(ms(3, 18, 265) > fixture().lines.get(3).timeMs);
    }

    @Test
    public void theOverlapBetweenTheMainEndAndTheBackgroundStartIsPreserved() {
        SyncedLyricsController.Line line = fixture().lines.get(BROKEN);
        SyncedLyricsController.Segments segments = line.segments;
        int attention = segmentStartingAt(segments, ms(3, 14, 204));
        int what = segmentStartingAt(segments, ms(3, 14, 789));
        assertTrue(attention >= 0 && what >= 0);
        assertEquals("attention", word(line, attention));
        assertEquals("What", word(line, what));
        // The explicit end of "attention" is kept, and it is after the echo has started.
        assertTrue(segments.hasEndTime(attention));
        assertEquals(ms(3, 15, 103), segments.endTimeMs(attention));
        assertTrue(segments.startTimeMs(what) < segments.endTimeMs(attention));
    }

    @Test
    public void explicitEndsAreKeptOnlyWhereTheSourceStatedThem() {
        SyncedLyricsController.Line line = fixture().lines.get(BROKEN);
        SyncedLyricsController.Segments segments = line.segments;
        int ends = 0;
        for (int k = 0; k < segments.size(); k++) {
            if (segments.hasEndTime(k)) ends++;
        }
        // "attention" (an end tag before the parenthesis) and "now?" (an end tag before the closing one).
        assertEquals(2, ends);
        assertEquals(ms(3, 15, 103), segments.endTimeMs(segmentStartingAt(segments, ms(3, 14, 204))));
        // The end of the last background word is later than the line after next's start, and is kept.
        assertEquals(ms(3, 18, 823), segments.endTimeMs(segmentStartingAt(segments, ms(3, 18, 265))));
        assertFalse(segments.hasEndTime(segmentStartingAt(segments, ms(3, 12, 794))));
    }

    @Test
    public void timingOrderUsedInternallyIsValidForRendering() {
        SyncedLyricsController.Segments segments = fixture().lines.get(BROKEN).segments;
        for (int k = 1; k < segments.size(); k++) {
            assertTrue("segments are in time order at " + k, segments.startTimeMs(k) >= segments.startTimeMs(k - 1));
        }
        // A position inside the overlap resolves to the echo that has started, not to the main word.
        int index = segments.indexAt(ms(3, 14, 800));
        assertEquals(ms(3, 14, 789), segments.startTimeMs(index));
        // Before the echo, the main word.
        assertEquals(ms(3, 14, 204), segments.startTimeMs(segments.indexAt(ms(3, 14, 700))));
        // Before the first word, none.
        assertEquals(-1, segments.indexAt(ms(3, 12, 700)));
        // Every range is inside the text, and non-empty.
        String text = fixture().lines.get(BROKEN).text;
        for (int k = 0; k < segments.size(); k++) {
            assertTrue(segments.startOffset(k) >= 0 && segments.endOffset(k) <= text.length());
            assertTrue(segments.endOffset(k) > segments.startOffset(k));
        }
    }

    @Test
    public void textOrderAndMainBackgroundIdentityAreKept() {
        SyncedLyricsController.Line line = fixture().lines.get(BROKEN);
        int open = line.text.indexOf('(');
        List<Integer> main = new ArrayList<>(), background = new ArrayList<>();
        for (int k = 0; k < line.segments.size(); k++) {
            (line.segments.startOffset(k) < open ? main : background).add(k);
        }
        assertEquals(5, main.size());
        assertEquals(11, background.size());
        // Each part is in text order and in time order on its own.
        for (List<Integer> part : Arrays.asList(main, background)) {
            for (int i = 1; i < part.size(); i++) {
                int a = part.get(i - 1), b = part.get(i);
                assertTrue(line.segments.startOffset(b) > line.segments.startOffset(a));
                assertTrue(line.segments.startTimeMs(b) > line.segments.startTimeMs(a));
            }
        }
    }

    @Test
    public void noWordDisappears() {
        for (SyncedLyricsController.Line line : fixture().lines) {
            for (int i = 0; i < line.text.length(); i++) {
                char c = line.text.charAt(i);
                if (Character.isWhitespace(c) || c == '(' || c == ')') continue;
                boolean covered = false;
                for (int k = 0; k < line.segments.size() && !covered; k++) {
                    covered = i >= line.segments.startOffset(k) && i < line.segments.endOffset(k);
                }
                assertTrue("'" + c + "' at " + i + " of \"" + line.text + "\" is timed", covered);
            }
        }
    }

    @Test
    public void theDisplayLineKeepsBothPartsTimed() {
        SyncedLyricsController.Line display = SyncedLyricsController.splitBackgroundVocals(fixture().lines.get(BROKEN));
        assertEquals("Yeah, you just want attention\nWhat are you doing to me? What are you doing now?", display.text);
        assertNotNull(display.segments);
        assertEquals(16, display.segments.size());
        int lineBreak = display.text.indexOf('\n');
        int main = 0, background = 0;
        for (int k = 0; k < display.segments.size(); k++) {
            String word = word(display, k);
            assertFalse(word.isEmpty());
            if (display.segments.startOffset(k) < lineBreak) main++; else background++;
        }
        assertEquals(5, main);
        assertEquals(11, background);
        // The explicit end of "attention" and the echo's start survive the split.
        int attention = segmentStartingAt(display.segments, ms(3, 14, 204));
        assertEquals("attention", word(display, attention));
        assertEquals(ms(3, 15, 103), display.segments.endTimeMs(attention));
        assertTrue(segmentStartingAt(display.segments, ms(3, 14, 789)) >= 0);
    }

    @Test
    public void surroundingLinesAreStillWordTimedWithTheirWords() {
        SyncedLyricsController.Lyrics lyrics = fixture();
        assertEquals(16, lyrics.lines.get(0).segments.size());
        assertEquals(Arrays.asList("I", "knew", "from", "the", "start"), words(lyrics.lines.get(2)));
        assertEquals(21, lyrics.lines.get(3).segments.size());
        assertEquals(ms(3, 17, 882), lyrics.lines.get(2).segments.endTimeMs(4));
        assertEquals(ms(3, 10, 642), lyrics.lines.get(0).segments.endTimeMs(segmentStartingAt(lyrics.lines.get(0).segments, ms(3, 9, 562))));
        // Background words of the first line start before its main words have all started.
        assertTrue(segmentStartingAt(lyrics.lines.get(0).segments, ms(3, 10, 52)) >= 0);
    }

    @Test
    public void everyLineOfTheFixtureIsEligibleForWordTimedRendering() {
        // The renderer takes a row as word-timed from its display line: timed, with segments and text.
        for (SyncedLyricsController.Line source : fixture().lines) {
            SyncedLyricsController.Line display = SyncedLyricsController.splitBackgroundVocals(source);
            assertTrue(display.timed);
            assertNotNull(display.segments);
            assertTrue(display.segments.size() > 0);
            assertFalse(display.text.isEmpty());
        }
    }

    // ---- ordinary Enhanced LRC, and the rules that still hold -------------------------------------

    @Test
    public void ordinaryEnhancedLrcIsUnchanged() {
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.parse(
                "[00:01.000]<00:01.000>Hello <00:01.500>world\n[00:04.000]<00:04.000>Second <00:04.400>line\n");
        assertEquals(2, lyrics.lines.size());
        SyncedLyricsController.Segments first = lyrics.lines.get(0).segments;
        assertEquals(2, first.size());
        assertEquals("Hello", word(lyrics.lines.get(0), 0));
        assertEquals("world", word(lyrics.lines.get(0), 1));
        assertEquals(1000, first.startTimeMs(0));
        assertEquals(1500, first.startTimeMs(1));
        assertFalse(first.hasEndTime(0));
        assertEquals(2, lyrics.lines.get(1).segments.size());
    }

    @Test
    public void aLineWithoutInlineTimingHasNoSegments() {
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.parse("[00:01.000]Plain line\n[00:04.000]Another\n");
        assertNull(lyrics.lines.get(0).segments);
        assertNull(lyrics.lines.get(1).segments);
    }

    @Test
    public void aMainWordStartingAfterTheLineAfterNextStillRejectsTheLine() {
        // The integrity bound for the main part is unchanged: the third word of the first line starts after
        // the third line has.
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.parse(
                "[00:01.000]<00:01.000>One <00:02.000>two <00:09.000>three\n[00:03.000]Next\n[00:05.000]After\n");
        assertNull(lyrics.lines.get(0).segments);
        assertEquals(3, lyrics.lines.size());
    }

    @Test
    public void aMainPartRunningBackwardsStillRejectsTheLine() {
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.parse("[00:01.000]<00:01.000>One <00:00.500>two\n[00:04.000]Next\n");
        assertNull(lyrics.lines.get(0).segments);
    }

    @Test
    public void aBackgroundPartRunningBackwardsStillRejectsTheLine() {
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.parse(
                "[00:01.000]<00:01.000>One (<00:02.500>echo <00:02.000>again)\n[00:04.000]Next\n");
        assertNull(lyrics.lines.get(0).segments);
    }

    @Test
    public void aWordBeforeItsOwnLineStillRejectsTheLine() {
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.parse("[00:05.000]<00:04.000>One <00:06.000>two\n[00:09.000]Next\n");
        assertNull(lyrics.lines.get(0).segments);
    }

    @Test
    public void aBackgroundPartMayRunOverAnyNumberOfLaterLines() {
        // The echo's last word starts well after the lines two and three have started.
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.parse(
                "[00:10.000]<00:10.000>Main <00:10.500>words (<00:10.800>echo <00:18.000>far <00:19.000>away<00:20.000>)\n"
                        + "[00:12.000]<00:12.000>Next <00:12.500>line\n"
                        + "[00:14.000]<00:14.000>Then <00:14.500>this\n"
                        + "[00:16.000]<00:16.000>And <00:16.500>this\n");
        SyncedLyricsController.Line line = lyrics.lines.get(0);
        assertNotNull(line.segments);
        assertEquals(5, line.segments.size());
        assertEquals(18000, line.segments.startTimeMs(segmentStartingAt(line.segments, 18000)));
        assertEquals(20000, line.segments.endTimeMs(segmentStartingAt(line.segments, 19000)));
    }

    @Test
    public void aMainWordsEndBeyondTheLineAfterNextIsStillNotStated() {
        // The line stays word-timed; only the end that cannot be trusted is left unstated.
        SyncedLyricsController.Lyrics lyrics = SyncedLyricsController.parse(
                "[00:01.000]<00:01.000>One <00:02.000>two<00:30.000>\n[00:03.000]Next\n[00:05.000]After\n");
        SyncedLyricsController.Segments segments = lyrics.lines.get(0).segments;
        assertNotNull(segments);
        assertEquals(2, segments.size());
        assertFalse(segments.hasEndTime(1));
    }
}
