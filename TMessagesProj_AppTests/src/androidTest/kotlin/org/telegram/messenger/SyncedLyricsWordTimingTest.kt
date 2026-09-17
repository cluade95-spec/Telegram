package org.telegram.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.SyncedLyricsController.Line
import org.telegram.messenger.SyncedLyricsController.parse

/**
 * Phase 1a: genuine Enhanced-LRC inline timing is captured instead of discarded.
 *
 * Two things are asserted throughout. First, that the visible text, line count, timestamps and kind
 * are exactly what they were before inline timing was captured - the metadata is additive and
 * nothing else may move. Second, that timing is only ever reported when the source actually stated
 * it: every malformed, partial, out-of-order or out-of-range case loses its optional metadata and
 * keeps its line.
 */
class SyncedLyricsWordTimingTest {

    private fun lineAt(source: String, index: Int): Line = parse(source).lines[index]

    /** "start-end@time:[text]" per segment, or "-" when the line carries no genuine timing. */
    private fun segments(line: Line): String {
        val segments = line.segments ?: return "-"
        return (0 until segments.size()).joinToString(" ") { i ->
            val start = segments.startOffset(i)
            val end = segments.endOffset(i)
            "$start-$end@${segments.startTimeMs(i)}:[${line.text.substring(start, end)}]"
        }
    }

    // ------------------------------------------------------------------ text compatibility

    @Test
    fun standardLrcIsUnchanged() {
        val lyrics = parse("[00:01.00]one\n[00:02.00]two\n[00:03.50]three")
        assertEquals(SyncedLyricsController.Kind.SYNCED, lyrics.kind)
        assertEquals(3, lyrics.lines.size)
        assertEquals("one", lyrics.lines[0].text)
        assertEquals(1000L, lyrics.lines[0].timeMs)
        assertEquals(3500L, lyrics.lines[2].timeMs)
    }

    @Test
    fun standardLrcCarriesNoTimingMetadata() {
        for (line in parse("[00:01.00]one\n[00:02.00]two").lines) assertNull(line.segments)
    }

    @Test
    fun plainLyricsCarryNoTimingMetadata() {
        val lyrics = parse("just words\n\nmore words")
        assertEquals(SyncedLyricsController.Kind.PLAIN, lyrics.kind)
        for (line in lyrics.lines) assertNull(line.segments)
    }

    @Test
    fun enhancedLrcVisibleTextHasTagsRemoved() {
        val line = lineAt(ENHANCED, 0)
        assertEquals("I've seen trees", line.text)
        assertEquals(12500L, line.timeMs)
        assertTrue(line.timed)
    }

    @Test
    fun oneEnhancedSourceLineBecomesExactlyOneLine() {
        assertEquals(1, parse(ENHANCED).lines.size)
        assertEquals(1, parse("[00:01.00]<00:01.0>a <00:02.0>b <00:03.0>c <00:04.0>d").lines.size)
        assertEquals(2, parse("$ENHANCED\n[00:20.00]<00:20.0>next <00:20.5>line").lines.size)
    }

    @Test
    fun enhancedLrcIsStillSynced() {
        assertEquals(SyncedLyricsController.Kind.SYNCED, parse(ENHANCED).kind)
    }

    // ------------------------------------------------------------------ segment mapping

    @Test
    fun segmentsMapToTheExpectedVisibleSubstrings() {
        assertEquals(
            "0-5@12500:[I've ] 5-10@12800:[seen ] 10-15@13100:[trees]",
            segments(lineAt(ENHANCED, 0))
        )
    }

    @Test
    fun segmentsWithoutSpacesBetweenThem() {
        assertEquals(
            "0-1@1000:[a] 1-2@1500:[b] 2-3@2000:[c]",
            segments(lineAt("[00:01.00]<00:01.00>a<00:01.50>b<00:02.00>c", 0))
        )
    }

    @Test
    fun repeatedWordsArePositional() {
        assertEquals(
            "0-3@1000:[na ] 3-6@1200:[na ] 6-8@1400:[na]",
            segments(lineAt("[00:01.00]<00:01.00>na <00:01.20>na <00:01.40>na", 0))
        )
    }

    @Test
    fun punctuationAndContractionsRideAlong() {
        val source = "[00:01.00]<00:01.00>Don't <00:01.30>stop, <00:01.60>now!"
        assertEquals("Don't stop, now!", lineAt(source, 0).text)
        assertEquals(
            "0-6@1000:[Don't ] 6-12@1300:[stop, ] 12-16@1600:[now!]",
            segments(lineAt(source, 0))
        )
    }

    @Test
    fun offsetsSurviveTrimming() {
        assertEquals(
            "0-4@1000:[pad ] 4-7@1500:[ded]",
            segments(lineAt("[00:01.00]  <00:01.00>pad <00:01.50>ded  ", 0))
        )
    }

    @Test
    fun offsetTagAppliesToWordTimesToo() {
        assertEquals(
            "0-2@1500:[a ] 2-4@2000:[bb]",
            segments(lineAt("[offset:+500]\n[00:01.00]<00:01.00>a <00:01.50>bb", 0))
        )
    }

    // ------------------------------------------------------------------ unicode

    @Test
    fun amharicIsPreserved() {
        val source = "[00:01.00]<00:01.00>\u12a0\u1263 <00:01.50>\u12cd"
        assertEquals("\u12a0\u1263 \u12cd", lineAt(source, 0).text)
        assertEquals("0-3@1000:[\u12a0\u1263 ] 3-4@1500:[\u12cd]", segments(lineAt(source, 0)))
    }

    @Test
    fun emojiSurrogatePairsAreNotCorrupted() {
        val source = "[00:01.00]<00:01.00>love <00:01.50>\ud83d\udc9c"
        val line = lineAt(source, 0)
        assertEquals("love \ud83d\udc9c", line.text)
        assertEquals("0-5@1000:[love ] 5-7@1500:[\ud83d\udc9c]", segments(line))
        assertNoSplitSurrogate(line)
    }

    @Test
    fun combiningMarksAreUnchanged() {
        val source = "[00:01.00]<00:01.00>cafe\u0301 <00:01.50>nai\u0308ve"
        assertEquals("cafe\u0301 nai\u0308ve", lineAt(source, 0).text)
        assertEquals(
            "0-6@1000:[cafe\u0301 ] 6-12@1500:[nai\u0308ve]",
            segments(lineAt(source, 0))
        )
    }

    @Test
    fun rightToLeftTextIsUnchanged() {
        val source = "[00:01.00]<00:01.00>\u0645\u0631\u062d\u0628\u0627 <00:01.50>\u0628\u0643"
        assertEquals("\u0645\u0631\u062d\u0628\u0627 \u0628\u0643", lineAt(source, 0).text)
        assertEquals(
            "0-6@1000:[\u0645\u0631\u062d\u0628\u0627 ] 6-8@1500:[\u0628\u0643]",
            segments(lineAt(source, 0))
        )
    }

    @Test
    fun aTagBetweenTheHalvesOfOneCharacterDisqualifiesTheLine() {
        // The tag sits between a high and a low surrogate, so an offset there would cut one
        // character in half. The timing is dropped; the text is still assembled correctly.
        val source = "[00:01.00]<00:01.00>ab\ud83d<00:01.50>\udc9ccd"
        assertEquals("ab\ud83d\udc9ccd", lineAt(source, 0).text)
        assertNull(lineAt(source, 0).segments)
    }

    // ------------------------------------------------------------------ nothing is fabricated

    @Test
    fun malformedTagYieldsNoTimingButStillStripsTheText() {
        val source = "[00:01.00]<00:99.00>bad seconds <00:01.50>here"
        assertEquals("bad seconds here", lineAt(source, 0).text)
        assertNull(lineAt(source, 0).segments)
    }

    @Test
    fun decreasingTimestampsAreNotReordered() {
        val source = "[00:01.00]<00:05.00>late <00:02.00>early"
        assertEquals("late early", lineAt(source, 0).text)
        assertNull(lineAt(source, 0).segments)
    }

    @Test
    fun aWordTimeBeforeItsLineIsRejected() {
        assertNull(lineAt("[00:01.00]<00:00.10>before <00:00.50>the line", 0).segments)
    }

    @Test
    fun aWordTimeReachingTheNextLineIsRejected() {
        val source = "[00:01.00]<00:01.10>spills <00:03.00>over\n[00:02.00]<00:02.10>next <00:02.50>line"
        assertNull(lineAt(source, 0).segments)
        assertNotNull(lineAt(source, 1).segments)
    }

    @Test
    fun aLoneTagSpanningTheWholeLineIsNotWordTiming() {
        // It states nothing the line timestamp does not already state, and it is exactly what a
        // converter emits when it only ever had line timing.
        assertNull(lineAt("[00:01.00]<00:01.10>a whole line under one tag", 0).segments)
    }

    @Test
    fun partialTimingBelowTheCoverageFloorIsRejected() {
        val source = "[00:01.00]eight untimed words lead this line <00:01.50>then <00:01.70>two"
        assertEquals("eight untimed words lead this line then two", lineAt(source, 0).text)
        assertNull(lineAt(source, 0).segments)
    }

    @Test
    fun aSmallUntimedLeadInStaysAboveTheFloor() {
        assertEquals(
            "2-6@1200:[see ] 6-10@1400:[the ] 10-15@1600:[trees]",
            segments(lineAt("[00:01.00]I <00:01.20>see <00:01.40>the <00:01.60>trees", 0))
        )
    }

    @Test
    fun timedBlanksBehaveExactlyAsBefore() {
        val source = "[00:01.00]a\n[00:02.00]<00:02.00>\n[00:03.00]b"
        val lyrics = parse(source)
        assertEquals(3, lyrics.lines.size)
        assertEquals("", lyrics.lines[1].text)
        assertEquals(2000L, lyrics.lines[1].timeMs)
        assertNull(lyrics.lines[1].segments)
    }

    @Test
    fun linesCombinedAtOneTimestampLoseTheirTiming() {
        // Two source lines merge into one visible line, so offsets measured against either half no
        // longer address the text they belong to.
        val source = "[00:01.00]<00:01.00>fi <00:01.10>rst\n[00:01.00]<00:01.20>sec <00:01.30>ond"
        assertEquals("fi rst\nsec ond", lineAt(source, 0).text)
        assertNull(lineAt(source, 0).segments)
    }

    @Test
    fun repeatedLineTimestampsKeepTimingOnlyWhereItBelongs() {
        val source = "[00:01.00][00:05.00]<00:01.10>a <00:01.50>bb\n[00:09.00]end"
        assertEquals(3, parse(source).lines.size)
        assertEquals("a bb", lineAt(source, 0).text)
        assertEquals("a bb", lineAt(source, 1).text)
        assertEquals("0-2@1100:[a ] 2-4@1500:[bb]", segments(lineAt(source, 0)))
        assertNull(lineAt(source, 1).segments)
    }

    @Test
    fun duplicateTimestampsAreKeptAsStated() {
        assertEquals(
            "0-2@1000:[a ] 2-3@1000:[b]",
            segments(lineAt("[00:01.00]<00:01.00>a <00:01.00>b", 0))
        )
    }

    @Test
    fun tagsIntroducingNoVisibleTextAreDropped() {
        assertEquals(
            "0-3@1200:[wo ] 3-5@1400:[rd]",
            segments(lineAt("[00:01.00]<00:01.00><00:01.20>wo <00:01.40>rd<00:01.60>", 0))
        )
    }

    // ------------------------------------------------------------------ invariants

    @Test
    fun everySegmentIsInsideItsLineAndInOrder() {
        val sources = listOf(
            ENHANCED,
            "[00:01.00]<00:01.00>a<00:01.50>b<00:02.00>c",
            "[00:01.00]<00:01.00>na <00:01.20>na <00:01.40>na",
            "[00:01.00]<00:01.00>love <00:01.50>\ud83d\udc9c",
            "[00:01.00]<00:01.00>\u12a0\u1263 <00:01.50>\u12cd",
            "[00:01.00]I <00:01.20>see <00:01.40>the <00:01.60>trees"
        )
        for (source in sources) {
            for (line in parse(source).lines) {
                val segments = line.segments ?: continue
                assertTrue(segments.size() >= 2)
                assertTrue(line.text.isNotEmpty())
                for (i in 0 until segments.size()) {
                    assertTrue(segments.startOffset(i) >= 0)
                    assertTrue(segments.endOffset(i) <= line.text.length)
                    assertTrue(segments.startOffset(i) < segments.endOffset(i))
                    assertTrue(segments.startTimeMs(i) >= line.timeMs)
                    if (i > 0) {
                        assertTrue(segments.startOffset(i) >= segments.endOffset(i - 1))
                        assertTrue(segments.startTimeMs(i) >= segments.startTimeMs(i - 1))
                    }
                }
                assertNoSplitSurrogate(line)
            }
        }
    }

    private fun assertNoSplitSurrogate(line: Line) {
        val segments = line.segments ?: return
        for (i in 0 until segments.size()) {
            assertTrue(!splitsPair(line.text, segments.startOffset(i)))
            assertTrue(!splitsPair(line.text, segments.endOffset(i)))
        }
    }

    private fun splitsPair(text: String, offset: Int): Boolean =
        offset > 0 && offset < text.length &&
            Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])

    private companion object {
        const val ENHANCED = "[00:12.50]<00:12.50>I've <00:12.80>seen <00:13.10>trees"
    }
}
