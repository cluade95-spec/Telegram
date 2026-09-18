package org.telegram.ui.Components

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.SyncedLyricsController
import org.telegram.messenger.SyncedLyricsController.Line
import org.telegram.messenger.SyncedLyricsController.parse
import org.telegram.ui.Components.AudioPlayerAlert.KaraokeFrame
import org.telegram.ui.Components.AudioPlayerAlert.KaraokeGeometry

/**
 * The large player's karaoke rendering maths.
 *
 * Two properties are asserted throughout, and they are the whole point of the design.
 *
 * The first is that the picture is a pure function of the playback position. Every assertion here
 * resolves a frame from a position and nothing else - there is no animator to start, no elapsed
 * time to accumulate, no previous frame to carry - so a seek, a pause, a track change, a theme
 * change and a recycled row rebinding all land on exactly the frame the position says they should.
 * Resolving the same position twice is asserted to produce the same frame.
 *
 * The second is that no part of the presentation is allowed to decide what is semantically current.
 * The word being sung is always the one the source's own timestamps select at that position; the
 * fill progress and the lift are derived from it afterwards and can never move it. That is asserted
 * directly, across every position of a line, against the parser's own segment index.
 *
 * Timing comes from real documents parsed by the real parser, so nothing here depends on a
 * hand-built model that could drift away from what the app actually reads.
 */
class LyricsKaraokeRenderingTest {

    private fun ttml(body: String): Line =
        parse("""<tt xmlns="http://www.w3.org/ns/ttml"><body><div>$body</div></body></tt>""").lines[0]

    private fun lrc(source: String): Line = parse(source).lines[0]

    /** "Hello world", with a stated start AND a stated end on each word, as TTML alone can. */
    private fun statedEnds(): Line = ttml(
        """<p begin="00:01.000" end="00:03.000">""" +
            """<span begin="00:01.000" end="00:01.800">Hello</span> """ +
            """<span begin="00:02.000" end="00:02.600">world</span></p>"""
    )

    /** "one two three", start-only, as Enhanced LRC alone can. */
    private fun startsOnly(): Line =
        lrc("[00:01.000]<00:01.000>one <00:01.200>two <00:01.400>three")

    private fun start(line: Line, index: Int) = line.segments.startOffset(index)
    private fun end(line: Line, index: Int) = line.segments.endOffset(index)

    // ------------------------------------------------------------------ TTML: start and end

    @Test
    fun aLineIsInertUntilItsOwnTimestamp() {
        val frame = KaraokeFrame()
        assertFalse(frame.resolve(statedEnds(), 900, Long.MAX_VALUE))
    }

    @Test
    fun aWordIsAtZeroUntilItsStatedStart() {
        val line = statedEnds()
        val frame = KaraokeFrame()
        frame.resolve(line, 999, Long.MAX_VALUE)
        assertEquals(0f, frame.sweep, 0.0001f)
        assertEquals(frame.wordStart, frame.wordEnd)
    }

    @Test
    fun theFillBeginsExactlyAtTheStatedStart() {
        val line = statedEnds()
        val frame = KaraokeFrame()
        frame.resolve(line, 1000, Long.MAX_VALUE)
        assertEquals(start(line, 0), frame.wordStart)
        assertEquals(end(line, 0), frame.wordEnd)
        assertEquals(0f, frame.sweep, 0.0001f)
    }

    @Test
    fun theFillFollowsTheStatedIntervalAndCompletesAtTheStatedEnd() {
        val line = statedEnds()
        val frame = KaraokeFrame()
        frame.resolve(line, 1400, Long.MAX_VALUE)
        assertEquals(0.5f, frame.sweep, 0.01f)
        frame.resolve(line, 1800, Long.MAX_VALUE)
        assertEquals(1f, frame.sweep, 0.0001f)
        // Held complete for the rest of the gap, rather than restarting or fading back.
        frame.resolve(line, 1950, Long.MAX_VALUE)
        assertEquals(1f, frame.sweep, 0.0001f)
        assertEquals(start(line, 0), frame.wordStart)
    }

    @Test
    fun aStatedEndIsNotWidenedByTheGapToTheNextWord() {
        // The next word is two seconds away; the fill still follows the 800ms the source stated.
        val line = statedEnds()
        val frame = KaraokeFrame()
        frame.resolve(line, 1200, Long.MAX_VALUE)
        assertEquals(0.25f, frame.sweep, 0.01f)
    }

    @Test
    fun theNextWordTakesOverExactlyOnItsStatedTime() {
        val line = statedEnds()
        val frame = KaraokeFrame()
        frame.resolve(line, 2000, Long.MAX_VALUE)
        assertEquals(start(line, 1), frame.wordStart)
        assertEquals(start(line, 1), frame.sungEnd)
        assertEquals(0f, frame.sweep, 0.0001f)
    }

    // ------------------------------------------------------------ Enhanced LRC: start only

    @Test
    fun startOnlyTimingIsBoundedByTheNextStatedStart() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        frame.resolve(line, 1100, 1600)
        assertEquals(0.5f, frame.sweep, 0.01f)
        frame.resolve(line, 1200, 1600)
        assertEquals(0f, frame.sweep, 0.0001f)
        assertEquals(start(line, 1), frame.wordStart)
    }

    @Test
    fun theLastWordOfALineIsBoundedByTheNextLine() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        frame.resolve(line, 1500, 1600)
        assertEquals(0.5f, frame.sweep, 0.01f)
        frame.resolve(line, 1599, 1600)
        // Complete before the line can change, which is the difference between a final word being
        // seen and being swallowed.
        assertTrue(frame.sweep > 0.99f)
    }

    @Test
    fun aLongGapIsCappedRatherThanTurnedIntoAWordLength() {
        val line = lrc("[00:00.000]<00:00.000>aa <00:04.000>bb")
        val frame = KaraokeFrame()
        frame.resolve(line, KaraokeFrame.SWEEP_DERIVED_MAX_MS / 2, Long.MAX_VALUE)
        assertEquals(0.5f, frame.sweep, 0.01f)
        frame.resolve(line, KaraokeFrame.SWEEP_DERIVED_MAX_MS, Long.MAX_VALUE)
        assertEquals(1f, frame.sweep, 0.0001f)
        // Still the same word three seconds later: a rendering interval never advances anything.
        frame.resolve(line, 3000, Long.MAX_VALUE)
        assertEquals(1f, frame.sweep, 0.0001f)
        assertEquals(start(line, 0), frame.wordStart)
    }

    @Test
    fun aStartOnlyWordWithNothingToBoundItUsesTheFallback() {
        val line = lrc("[00:00.000]<00:00.000>solo")
        val frame = KaraokeFrame()
        frame.resolve(line, KaraokeFrame.SWEEP_DERIVED_FALLBACK_MS / 2, Long.MAX_VALUE)
        assertEquals(0.5f, frame.sweep, 0.02f)
    }

    // ------------------------------------------------------------------------ word lengths

    @Test
    fun shortWordsFillAcrossExactlyTheStatedInterval() {
        for (duration in longArrayOf(500, 200, 100, 50)) {
            val line = ttml(
                """<p begin="00:00.000" end="00:20.000">""" +
                    """<span begin="00:00.000" end="${clock(duration)}">Aa</span> """ +
                    """<span begin="00:10.000" end="00:10.500">Bb</span></p>"""
            )
            val frame = KaraokeFrame()
            frame.resolve(line, duration / 2, Long.MAX_VALUE)
            assertEquals("${duration}ms word", 0.5f, frame.sweep, 0.02f)
            frame.resolve(line, duration, Long.MAX_VALUE)
            assertEquals("${duration}ms word", 1f, frame.sweep, 0.0001f)
        }
    }

    @Test
    fun aVeryShortWordStillMovesVisibly() {
        for (duration in longArrayOf(500, 200, 100, 50)) {
            val line = ttml(
                """<p begin="00:00.000" end="00:20.000">""" +
                    """<span begin="00:00.000" end="${clock(duration)}">Aa</span></p>"""
            )
            val frame = KaraokeFrame()
            frame.resolve(line, 0, Long.MAX_VALUE)
            assertEquals("${duration}ms word starts at rest", 0f, frame.lift, 0.0001f)
            // Two frames in, at any of these lengths, the word is already most of the way up: the
            // motion never dies halfway through a ramp because the word was short.
            frame.resolve(line, 33, Long.MAX_VALUE)
            assertTrue("${duration}ms word two frames in: ${frame.lift}", frame.lift > 0.5f)
        }
    }

    @Test
    fun adjacentWordsWithTinyGapsStillResolveCleanly() {
        val line = lrc("[00:00.000]<00:00.000>a <00:00.030>b <00:00.060>c")
        val frame = KaraokeFrame()
        frame.resolve(line, 15, Long.MAX_VALUE)
        assertEquals(0.5f, frame.sweep, 0.02f)
        assertEquals(start(line, 0), frame.wordStart)
        frame.resolve(line, 29, Long.MAX_VALUE)
        assertTrue(frame.sweep > 0.95f)
        frame.resolve(line, 30, Long.MAX_VALUE)
        assertEquals(start(line, 1), frame.wordStart)
    }

    // ------------------------------------------------------------------ seeking, pause, state

    @Test
    fun aSeekLandsOnTheFrameThePositionStatesWithNoRamp() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        frame.resolve(line, 1150, 1600)
        assertEquals(0.75f, frame.sweep, 0.02f)
        assertEquals(start(line, 0), frame.wordStart)
    }

    @Test
    fun aSeekPastEveryWordLeavesTheLastOneComplete() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        frame.resolve(line, 9000, Long.MAX_VALUE)
        assertEquals(start(line, 2), frame.wordStart)
        assertEquals(1f, frame.sweep, 0.0001f)
    }

    @Test
    fun seekingBackwardsReconstructsTheEarlierFrameExactly() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        frame.resolve(line, 1150, 1600)
        val sweep = frame.sweep
        val word = frame.wordStart
        frame.resolve(line, 9000, Long.MAX_VALUE)
        frame.resolve(line, 1150, 1600)
        assertEquals(sweep, frame.sweep, 0f)
        assertEquals(word, frame.wordStart)
    }

    @Test
    fun pausingAndResumingOnTheSamePositionChangesNothing() {
        val line = startsOnly()
        val paused = KaraokeFrame()
        val resumed = KaraokeFrame()
        paused.resolve(line, 1310, 1600)
        // A frame that has never seen this line before - exactly what a resume, a rebind or a
        // reopened player has - lands on the same picture.
        resumed.resolve(line, 1310, 1600)
        assertEquals(paused.sweep, resumed.sweep, 0f)
        assertEquals(paused.lift, resumed.lift, 0f)
        assertEquals(paused.wordStart, resumed.wordStart)
        assertEquals(paused.wordEnd, resumed.wordEnd)
    }

    @Test
    fun theCurrentWordIsAlwaysTheOneTheSourceStated() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        for (position in 990L..1700L) {
            frame.resolve(line, position, 1600)
            val index = line.segments.indexAt(position)
            val expectedStart = if (index < 0) start(line, 0) else start(line, index)
            val expectedEnd = if (index < 0) start(line, 0) else end(line, index)
            assertEquals("at $position", expectedStart, frame.wordStart)
            assertEquals("at $position", expectedEnd, frame.wordEnd)
        }
    }

    // --------------------------------------------------------------------- rows of a document

    @Test
    fun aRowTheFollowIsBringingInEarlyShowsNothingLit() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        assertTrue(frame.resolveRow(line, 2, 1, 1150, 1600))
        assertEquals(0, frame.sungEnd)
        assertEquals(frame.wordStart, frame.wordEnd)
    }

    @Test
    fun aRowTheLineHasPassedIsWhollySungWithNothingInFlight() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        assertTrue(frame.resolveRow(line, 0, 1, 1150, 1600))
        assertEquals(line.text.length, frame.sungEnd)
        assertEquals(frame.wordStart, frame.wordEnd)
    }

    @Test
    fun aRowWithNoInlineTimingFallsBackRatherThanInventingAnything() {
        val line = parse("[00:01.00]just a line").lines[0]
        assertFalse(KaraokeFrame().resolveRow(line, 0, 0, 2000, Long.MAX_VALUE))
    }

    @Test
    fun aRecycledRowRebindsToTheSameFrame() {
        val line = startsOnly()
        val first = KaraokeFrame()
        first.resolveRow(line, 1, 1, 1150, 1600)
        // A recycled holder is a frame that has been used for something else first.
        val recycled = KaraokeFrame()
        recycled.resolveRow(line, 0, 5, 40000, Long.MAX_VALUE)
        recycled.resolveRow(line, 1, 1, 1150, 1600)
        assertEquals(first.wordStart, recycled.wordStart)
        assertEquals(first.wordEnd, recycled.wordEnd)
        assertEquals(first.sweep, recycled.sweep, 0f)
        assertEquals(first.lift, recycled.lift, 0f)
    }

    // ------------------------------------------------------------------------- the lift curve

    @Test
    fun theLiftStartsAndEndsAtTheWordsNormalPosition() {
        assertEquals(0f, KaraokeFrame.liftCurve(0f), 0f)
        assertEquals(0f, KaraokeFrame.liftCurve(1f), 0f)
        assertEquals(0f, KaraokeFrame.liftCurve(1.5f), 0f)
        assertEquals(0f, KaraokeFrame.liftCurve(-1f), 0f)
    }

    @Test
    fun theLiftRisesOnceAndSettlesWithoutBouncing() {
        var previous = 0f
        var peak = 0f
        for (step in 0..1000) {
            val t = step / 1000f
            val value = KaraokeFrame.liftCurve(t)
            assertTrue("never below the resting position at $t", value >= -0.0001f)
            assertTrue("never beyond full travel at $t", value <= 1.0001f)
            if (t < 0.34f) assertTrue("rises without wobbling at $t", value >= previous - 0.0001f)
            if (t > 0.34f) assertTrue("settles without wobbling at $t", value <= previous + 0.0001f)
            if (value > peak) peak = value
            previous = value
        }
        assertEquals(1f, peak, 0.001f)
    }

    // -------------------------------------------------------------------------- fill geometry

    @Test
    fun aWrappedWordFillsItsFirstVisualLineBeforeItsSecond() {
        assertEquals(30f, KaraokeGeometry.revealedWidth(30f, 0f, 40f), 0.001f)
        assertEquals(0f, KaraokeGeometry.revealedWidth(30f, 40f, 20f), 0.001f)
        assertEquals(40f, KaraokeGeometry.revealedWidth(48f, 0f, 40f), 0.001f)
        assertEquals(8f, KaraokeGeometry.revealedWidth(48f, 40f, 20f), 0.001f)
        assertEquals(40f, KaraokeGeometry.revealedWidth(500f, 0f, 40f), 0.001f)
    }

    @Test
    fun theFillGrowsFromTheSideTheRunIsReadFrom() {
        assertEquals(10f, KaraokeGeometry.revealedLeft(10f, 50f, 10f, false), 0.001f)
        assertEquals(40f, KaraokeGeometry.revealedLeft(10f, 50f, 10f, true), 0.001f)
    }

    @Test
    fun aRunsDirectionComesFromWhereItsEndsLanded() {
        assertTrue(KaraokeGeometry.isRightToLeft(50f, 10f, false))
        assertFalse(KaraokeGeometry.isRightToLeft(10f, 50f, true))
        assertTrue(KaraokeGeometry.isRightToLeft(10f, 10f, true))
        assertFalse(KaraokeGeometry.isRightToLeft(10f, 10f, false))
    }

    @Test
    fun anArabicWordFillsRightToLeftAndALatinOneLeftToRight() {
        val arabic = layoutOf("مرحبا بالعالم", 10000)
        val rtlFrom = KaraokeGeometry.horizontalAt(arabic, 0, 0)
        val rtlTo = KaraokeGeometry.horizontalAt(arabic, 0, 5)
        assertTrue(
            "an Arabic word's logical end must land left of its start",
            KaraokeGeometry.isRightToLeft(rtlFrom, rtlTo, arabic.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT)
        )
        val latin = layoutOf("Hello world", 10000)
        assertFalse(
            KaraokeGeometry.isRightToLeft(
                KaraokeGeometry.horizontalAt(latin, 0, 0),
                KaraokeGeometry.horizontalAt(latin, 0, 5),
                latin.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
            )
        )
    }

    @Test
    fun theEndOfAWrappedWordIsTakenFromItsOwnVisualLine() {
        // Narrow enough that the text certainly wraps.
        val layout = layoutOf("wrapping behaviour matters here", 120)
        assertTrue("the fixture must actually wrap", layout.lineCount > 1)
        val firstLineEnd = layout.getLineEnd(0)
        val x = KaraokeGeometry.horizontalAt(layout, 0, firstLineEnd)
        // Without the wrap correction this would come back as the *next* line's starting edge.
        assertTrue("end of line 0 must sit on line 0, not at the next line's left edge", x > 1f)
        assertTrue(x <= layout.getLineRight(0) + 1f)
    }

    private fun layoutOf(text: String, width: Int): Layout {
        val paint = TextPaint()
        paint.textSize = 48f
        paint.typeface = Typeface.DEFAULT_BOLD
        @Suppress("DEPRECATION")
        return StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
    }

    // -------------------------------------------------------------------- grapheme boundaries

    @Test
    fun aBoundaryNeverLandsInsideSomethingDrawnAsOneUnit() {
        // A surrogate pair, a combining mark, a ZWJ sequence, a flag and a variation selector.
        assertEquals(2, KaraokeGeometry.clusterEnd("😀ab", 1))
        assertEquals(2, KaraokeGeometry.clusterEnd("ábc", 1))
        assertEquals(5, KaraokeGeometry.clusterEnd("👨‍👩", 2))
        assertEquals(4, KaraokeGeometry.clusterEnd("🇪🇹", 2))
        assertEquals(2, KaraokeGeometry.clusterEnd("❤️x", 1))
    }

    @Test
    fun anOrdinaryBoundaryIsLeftExactlyWhereTheSourcePutIt() {
        assertEquals(1, KaraokeGeometry.clusterEnd("abc", 1))
        assertEquals(3, KaraokeGeometry.clusterEnd("abc", 3))
        assertEquals(0, KaraokeGeometry.clusterEnd("abc", 0))
        assertEquals(3, KaraokeGeometry.clusterEnd("abc", 99))
        assertEquals(0, KaraokeGeometry.clusterEnd("abc", -5))
        // Two flags in a row break between them rather than merging into one.
        assertEquals(4, KaraokeGeometry.clusterEnd("🇪🇹🇪🇹", 4))
        // Amharic, which is neither combining nor surrogate: every boundary is already a cluster.
        assertEquals(2, KaraokeGeometry.clusterEnd("ሰላም", 2))
    }

    /** Whole milliseconds as the clock TTML states, e.g. 50 -> "00:00.050". */
    private fun clock(ms: Long): String =
        String.format("%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000)

    @Test
    fun theParserIsWhatSaysWhichWordsExist() {
        // Guards the fixtures themselves: if these ever stop being word-timed, every assertion
        // above would pass vacuously.
        assertEquals(SyncedLyricsController.Kind.SYNCED, parse("[00:01.000]<00:01.000>one").kind)
        assertEquals(3, startsOnly().segments.size())
        assertEquals(2, statedEnds().segments.size())
        assertTrue(statedEnds().segments.hasEndTime(0))
        assertFalse(startsOnly().segments.hasEndTime(0))
    }
}
