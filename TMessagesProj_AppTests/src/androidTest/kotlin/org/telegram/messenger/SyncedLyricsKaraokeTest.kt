package org.telegram.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.SyncedLyricsController.Karaoke
import org.telegram.messenger.SyncedLyricsController.Line
import org.telegram.messenger.SyncedLyricsController.parse

/**
 * What the player is allowed to highlight at a given playback position.
 *
 * The whole point of these tests is that a boundary is always an offset the source stated, reached
 * at a time the source stated. Nothing is divided among words, inferred from how long a word looks,
 * or interpolated between two stated times - between them the boundary simply does not move. A line
 * the source did not time inside resolves to nothing, which is the signal to keep the line-level
 * behaviour the player already had.
 */
class SyncedLyricsKaraokeTest {

    private val karaoke = Karaoke()

    private fun lineAt(source: String, index: Int): Line = parse(source).lines[index]

    /** "sungEnd|fadeStart|fade:[sung text]", or "-" when the line has nothing genuine to show. */
    private fun state(line: Line, positionMs: Long, transitionMs: Long = TRANSITION): String {
        if (!karaoke.resolve(line, positionMs, transitionMs)) return "-"
        return "${karaoke.sungEnd}|${karaoke.fadeStart}|${round(karaoke.fadeProgress)}" +
            ":[${line.text.substring(0, karaoke.sungEnd)}]"
    }

    private fun round(value: Float): String = (Math.round(value * 100) / 100f).toString()

    // ------------------------------------------------------------------ stated times only

    @Test
    fun indexAtReportsOnlyASegmentTheSourceStarted() {
        val segments = lineAt(ENHANCED, 0).segments!!
        assertEquals(-1, segments.indexAt(12499L))
        assertEquals(0, segments.indexAt(12500L))
        assertEquals(0, segments.indexAt(12799L))
        assertEquals(1, segments.indexAt(12800L))
        assertEquals(2, segments.indexAt(13100L))
        assertEquals(2, segments.indexAt(999999L))
    }

    @Test
    fun theSungBoundaryMovesOnlyOnAStatedTime() {
        val line = lineAt(ENHANCED, 0)
        assertEquals("5|0|0.0:[I've ]", state(line, 12500L))
        assertEquals("5|0|0.67:[I've ]", state(line, 12600L))
        assertEquals("5|0|1.0:[I've ]", state(line, 12700L))
        assertEquals("10|5|0.0:[I've seen ]", state(line, 12800L))
        assertEquals("15|10|0.0:[I've seen trees]", state(line, 13100L))
        assertEquals("15|10|1.0:[I've seen trees]", state(line, 60000L))
    }

    @Test
    fun theBoundaryOnlyEverRestsOnAStatedOffset() {
        val line = lineAt(ENHANCED, 0)
        var previous = -1
        for (position in 12500L..13600L) {
            karaoke.resolve(line, position, TRANSITION)
            // Never between two stated offsets: no character is attributed to a time nothing gave.
            assertTrue(karaoke.sungEnd == 5 || karaoke.sungEnd == 10 || karaoke.sungEnd == 15)
            assertTrue(karaoke.sungEnd >= previous)
            previous = karaoke.sungEnd
        }
    }

    // ------------------------------------------------------------------ fallback

    @Test
    fun standardLrcHasNothingToHighlight() {
        assertEquals("-", state(lineAt("[00:01.00]one\n[00:02.00]two", 0), 5000L))
    }

    @Test
    fun plainLyricsHaveNothingToHighlight() {
        assertEquals("-", state(lineAt("just words\n\nmore words", 0), 5000L))
    }

    @Test
    fun malformedInlineTimingFallsBackToTheLine() {
        val source = "[00:01.00]<00:99.00>bad <00:01.50>here"
        assertEquals("bad here", lineAt(source, 0).text)
        assertEquals("-", state(lineAt(source, 0), 5000L))
    }

    @Test
    fun decreasingInlineTimingFallsBackToTheLine() {
        assertEquals("-", state(lineAt("[00:01.00]<00:05.00>late <00:02.00>early", 0), 5000L))
    }

    @Test
    fun aLineWhoseOwnTimeHasNotArrivedHighlightsNothing() {
        assertEquals("-", state(lineAt(ENHANCED, 0), 12499L))
    }

    @Test
    fun aFailedResolveLeavesTheHolderInert() {
        val line = lineAt(ENHANCED, 0)
        assertTrue(karaoke.resolve(line, 13100L, TRANSITION))
        assertFalse(karaoke.resolve(lineAt("[00:01.00]plain line", 0), 13100L, TRANSITION))
        assertFalse(karaoke.active)
        assertEquals(0, karaoke.sungEnd)
        assertEquals(0, karaoke.fadeStart)
        assertEquals(1f, karaoke.fadeProgress, 0f)
    }

    @Test
    fun clearReportsNothing() {
        karaoke.resolve(lineAt(ENHANCED, 0), 13100L, TRANSITION)
        karaoke.clear()
        assertFalse(karaoke.active)
        assertEquals(0, karaoke.sungEnd)
        assertEquals(0, karaoke.fadeStart)
        assertEquals(1f, karaoke.fadeProgress, 0f)
    }

    // ------------------------------------------------------------------ untimed text

    @Test
    fun untimedLeadInIsNeverGivenATimeOfItsOwn() {
        // The first eight words carry no inline timing. They belong to the line, so they take the
        // line's own granularity - and the two words that ARE timed still wait for their times.
        val source = "[00:01.00]eight untimed words lead this line <00:01.50>then <00:01.70>two"
        val line = lineAt(source, 0)
        assertEquals("35|35|1.0:[eight untimed words lead this line ]", state(line, 1000L))
        assertEquals("35|35|1.0:[eight untimed words lead this line ]", state(line, 1499L))
        assertEquals("40|35|0.0:[eight untimed words lead this line then ]", state(line, 1500L))
        assertEquals("43|40|0.0:[eight untimed words lead this line then two]", state(line, 1700L))
    }

    @Test
    fun aSingleUntimedWordStaysUntimed() {
        val line = lineAt("[00:01.00]I <00:01.20>see <00:01.40>the <00:01.60>trees", 0)
        assertEquals("2|2|1.0:[I ]", state(line, 1000L))
        assertEquals("2|2|1.0:[I ]", state(line, 1199L))
        assertEquals("6|2|0.0:[I see ]", state(line, 1200L))
    }

    @Test
    fun aSingleStatedRangeLightsUpAtItsOwnTimeNotTheLines() {
        val line = lineAt("[00:01.00]<00:01.10>a whole line under one tag", 0)
        assertEquals("0|0|1.0:[]", state(line, 1000L))
        assertEquals("0|0|1.0:[]", state(line, 1099L))
        assertEquals("26|0|0.0:[a whole line under one tag]", state(line, 1100L))
    }

    // ------------------------------------------------------------------ the arrival transition

    @Test
    fun theTransitionIsBoundedByTheNextStatedTime() {
        // 50ms between two stated starts: the 150ms transition is cut to 50ms so the first range is
        // finished before the second begins. The bound is a stated gap, never a guess at a word.
        val fast = lineAt("[00:01.00]<00:01.00>a <00:01.05>b <00:01.50>c", 0)
        assertEquals("2|0|0.5:[a ]", state(fast, 1025L))
        assertEquals("2|0|0.98:[a ]", state(fast, 1049L))
        assertEquals("4|2|0.5:[a b ]", state(fast, 1125L))
    }

    @Test
    fun theLastRangeUsesTheWholeTransitionWindow() {
        val line = lineAt(ENHANCED, 0)
        assertEquals("15|10|0.5:[I've seen trees]", state(line, 13175L))
        assertEquals("15|10|1.0:[I've seen trees]", state(line, 13250L))
    }

    @Test
    fun rangesStatingTheSameTimeDoNotInventOneToSeparateThem() {
        val line = lineAt("[00:01.00]<00:01.00>a <00:01.00>b", 0)
        assertEquals("3|2|0.0:[a b]", state(line, 1000L))
        assertEquals("3|2|0.5:[a b]", state(line, 1075L))
    }

    @Test
    fun theTransitionNeverMovesABoundary() {
        val line = lineAt(ENHANCED, 0)
        for (transition in longArrayOf(1L, 40L, 150L, 5000L)) {
            assertEquals(5, resolved(line, 12600L, transition).sungEnd)
            assertEquals(10, resolved(line, 12900L, transition).sungEnd)
        }
    }

    private fun resolved(line: Line, positionMs: Long, transitionMs: Long): Karaoke {
        karaoke.resolve(line, positionMs, transitionMs)
        return karaoke
    }

    // ------------------------------------------------------------------ one row at a time

    /** Same dump as [state], through the per-row entry point the player actually calls. */
    private fun row(line: Line, lineIndex: Int, currentLine: Int, positionMs: Long): String {
        if (!karaoke.resolveRow(line, lineIndex, currentLine, positionMs, TRANSITION)) return "-"
        return "${karaoke.sungEnd}|${karaoke.fadeStart}|${round(karaoke.fadeProgress)}" +
            ":[${line.text.substring(0, karaoke.sungEnd)}]"
    }

    @Test
    fun aLineThePositionHasLeftIsWhollySung() {
        assertEquals("15|15|1.0:[I've seen trees]", row(lineAt(ENHANCED, 0), 4, 5, 0L))
    }

    @Test
    fun aLineThePositionHasNotReachedShowsNothing() {
        // The player moves the next line in before its timestamp; none of it may light up early.
        assertEquals("0|0|1.0:[]", row(lineAt(ENHANCED, 0), 6, 5, 999999L))
    }

    @Test
    fun onlyTheCurrentLineIsResolvedAgainstTheClock() {
        assertEquals("10|5|0.0:[I've seen ]", row(lineAt(ENHANCED, 0), 5, 5, 12800L))
        assertEquals("0|0|1.0:[]", row(lineAt(ENHANCED, 0), 5, 5, 0L))
    }

    @Test
    fun aLineWithoutInlineTimingFallsBackWhereverItSits() {
        val plain = lineAt("[00:01.00]plain", 0)
        assertEquals("-", row(plain, 4, 5, 5000L))
        assertEquals("-", row(plain, 5, 5, 5000L))
        assertEquals("-", row(plain, 6, 5, 5000L))
        assertEquals("-", row(lineAt("just words", 0), 0, 0, 5000L))
    }

    @Test
    fun aTimedBlankFallsBack() {
        val blank = lineAt("[00:01.00]a\n[00:02.00]<00:02.00>\n[00:03.00]b", 1)
        assertEquals("", blank.text)
        assertEquals("-", row(blank, 1, 1, 2000L))
    }

    @Test
    fun playingAWholeDocumentThroughOnlyEverMovesForward() {
        // The property that matters once several lines are on screen at once: whatever the position
        // is, every row reports a boundary the source stated, and no row ever un-sings text it has
        // already sung while the position advances.
        val document = "[00:01.00]<00:01.00>one <00:01.40>two\n" +
            "[00:05.00]an ordinary line\n" +
            "[00:09.00]<00:09.20>three <00:09.60>four <00:10.00>five"
        val lyrics = parse(document)
        val highest = IntArray(lyrics.lines.size)
        var position = 0L
        while (position <= 12000L) {
            val current = lyrics.lineAt(position)
            for (index in lyrics.lines.indices) {
                val line = lyrics.lines[index]
                if (!karaoke.resolveRow(line, index, current, position, TRANSITION)) continue
                assertTrue(karaoke.sungEnd >= highest[index])
                highest[index] = karaoke.sungEnd
                assertTrue(karaoke.fadeStart <= karaoke.sungEnd)
                assertTrue(statesOffset(line, karaoke.sungEnd))
            }
            position += 10L
        }
        assertEquals(lyrics.lines[0].text.length, highest[0])
        assertEquals(0, highest[1]) // the ordinary line never reports anything at all
        assertEquals(lyrics.lines[2].text.length, highest[2])
    }

    /** True when [offset] is one the source stated for this line, or one of its two ends. */
    private fun statesOffset(line: Line, offset: Int): Boolean {
        if (offset == 0 || offset == line.text.length) return true
        val segments = line.segments ?: return false
        return (0 until segments.size()).any {
            offset == segments.startOffset(it) || offset == segments.endOffset(it)
        }
    }

    // ------------------------------------------------------------------ text integrity

    @Test
    fun noBoundaryEverSplitsASurrogatePair() {
        val line = lineAt("[00:01.00]<00:01.00>love <00:01.50>\ud83d\udc9c", 0)
        assertEquals("love \ud83d\udc9c", line.text)
        var position = 1000L
        while (position <= 1600L) {
            karaoke.resolve(line, position, TRANSITION)
            assertFalse(splitsPair(line.text, karaoke.sungEnd))
            assertFalse(splitsPair(line.text, karaoke.fadeStart))
            position += 10L
        }
        assertEquals("7|5|0.0:[love \ud83d\udc9c]", state(line, 1500L))
    }

    @Test
    fun theHighlightedRangeIsAlwaysInsideTheLine() {
        val sources = listOf(
            ENHANCED,
            "[00:01.00]<00:01.00>a<00:01.50>b<00:02.00>c",
            "[00:01.00]<00:01.00>\u12a0\u1263 <00:01.50>\u12cd",
            "[00:01.00]<00:01.00>\u0645\u0631\u062d\u0628\u0627 <00:01.50>\u0628\u0643",
            "[00:01.00]I <00:01.20>see <00:01.40>the <00:01.60>trees"
        )
        for (source in sources) {
            for (line in parse(source).lines) {
                var position = 0L
                while (position <= 4000L) {
                    if (karaoke.resolve(line, position, TRANSITION)) {
                        assertTrue(karaoke.sungEnd in 0..line.text.length)
                        assertTrue(karaoke.fadeStart in 0..karaoke.sungEnd)
                        assertTrue(karaoke.fadeProgress in 0f..1f)
                    }
                    position += 25L
                }
            }
        }
    }

    private fun splitsPair(text: String, offset: Int): Boolean =
        offset > 0 && offset < text.length &&
            Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])

    private companion object {
        const val ENHANCED = "[00:12.50]<00:12.50>I've <00:12.80>seen <00:13.10>trees"
        const val TRANSITION = 150L
    }
}
