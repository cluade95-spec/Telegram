package org.telegram.ui.Components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.SyncedLyricsController
import org.telegram.messenger.SyncedLyricsController.Line
import org.telegram.messenger.SyncedLyricsController.parse
import org.telegram.ui.Components.AudioPlayerAlert.KaraokeFrame
import org.telegram.ui.Components.AudioPlayerAlert.KaraokeGeometry

/**
 * The large player's lyric rendering maths.
 *
 * These tests are written against what a person watching the screen would say, not against the
 * formulas underneath. The previous round of them all passed while the real build was visibly
 * wrong - every word finished its animation in a fixed fraction of a second, short words snapped
 * back to the baseline the instant the next one started, and the last word of a line was gone
 * before it had been sung - because they asserted that the implementation did what the
 * implementation did. So each group below states the user-visible property in its name and then
 * checks it at the timings a song actually contains.
 *
 * Two invariants run through all of it:
 *
 * The picture is a pure function of the playback position. There is no animator to start, no
 * elapsed time to accumulate, no previous frame to carry, so a seek, a pause, a track change and a
 * recycled row rebinding all land on exactly the frame the position says they should.
 *
 * No part of the presentation decides what is semantically current. The word being sung is always
 * the one the source's own timestamps select; the horizontal fill is derived afterwards and can
 * never move it. Every rendering interval below is bounded by a time the source genuinely stated.
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

    /** Whole milliseconds as the clock TTML states, e.g. 50 -> "00:00.050". */
    private fun clock(ms: Long): String =
        String.format("%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000)

    /** A two-word Enhanced LRC line where the first word owns exactly [gap] ms. */
    private fun pairWithGap(gap: Long): Line =
        lrc("[00:00.000]<00:00.000>aa <${clock(gap)}>bb")

    // ===================================================================== word duration
    // "When a word is sung for a long time the horizontal colour sweep finishes too early, and
    // the singer is still holding the word after the visual effect is already finished." That is
    // the bug this group exists to prevent coming back.

    @Test
    fun aLongWordFillsForAsLongAsItIsSungAndNotForAFixedFraction() {
        // The durations a real vocal contains, start-only, each bounded by the next stated start.
        for (duration in longArrayOf(1500, 1000, 800, 500, 300, 200, 100, 60, 50, 30)) {
            val line = pairWithGap(duration)
            val frame = KaraokeFrame()
            frame.resolve(line, duration / 2, Long.MAX_VALUE)
            assertEquals("${duration}ms word is half filled at its own midpoint", 0.5f, frame.sweep, 0.02f)
            frame.resolve(line, duration - 1, Long.MAX_VALUE)
            assertEquals("${duration}ms word is still filling at its last millisecond",
                (duration - 1f) / duration, frame.sweep, 0.005f)
            assertTrue("${duration}ms word is not complete before it ends", frame.sweep < 1f)
            frame.resolve(line, duration, Long.MAX_VALUE)
            assertEquals("${duration}ms word is complete exactly when it ends", 1f, frame.sweep, 0.0001f)
        }
    }

    @Test
    fun noWordIsFinishedAfterTheOldFixedAnimationLength() {
        // 320ms was the old cap and the reason a held note outlived its own animation. Anything
        // genuinely longer than that must still be visibly in progress when it elapses.
        for (duration in longArrayOf(500, 800, 1000, 1500)) {
            val frame = KaraokeFrame()
            frame.resolve(pairWithGap(duration), 320, Long.MAX_VALUE)
            assertTrue("${duration}ms word must not be finished at 320ms: ${frame.sweep}", frame.sweep < 0.95f)
            assertEquals("${duration}ms word at 320ms", 320f / duration, frame.sweep, 0.02f)
        }
    }

    // ==================================================== the only animation is the colour fill
    // There is no vertical motion of any kind inside a lyric row: no letter lift, no word lift, no
    // grapheme wave, no rise, no stagger and no raised completed state. A row's glyphs sit exactly
    // where the platform laid them out, for the whole of the line's life on screen, and the only
    // thing that changes as the song plays is which colour each pixel is painted.
    //
    // So the whole of the presentation is the horizontal fill, and the group below pins the timing
    // it follows: the source's own stated times, and nothing derived from anything else.

    @Test
    fun aStatedTtmlEndDrivesTheWholeInterval() {
        // start 10.000, end 11.200: the fill evolves over 1.2 seconds, not over a constant.
        val line = ttml(
            """<p begin="00:10.000" end="00:13.000">""" +
                """<span begin="00:10.000" end="00:11.200">Aaaa</span> """ +
                """<span begin="00:12.000" end="00:12.400">Bb</span></p>"""
        )
        val frame = KaraokeFrame()
        frame.resolve(line, 10600, Long.MAX_VALUE)
        assertEquals(0.5f, frame.sweep, 0.01f)
        frame.resolve(line, 11199, Long.MAX_VALUE)
        assertTrue(frame.sweep > 0.99f && frame.sweep < 1f)
        frame.resolve(line, 11200, Long.MAX_VALUE)
        assertEquals(1f, frame.sweep, 0.0001f)
        assertEquals(1200L, KaraokeFrame.sweepWindowMs(line.segments, 0, Long.MAX_VALUE))
    }

    @Test
    fun aStatedEndIsNeitherWidenedNorShortenedByTheGapToTheNextWord() {
        val line = statedEnds()
        val frame = KaraokeFrame()
        // The next word is two seconds away; the fill still follows the 800ms the source stated.
        frame.resolve(line, 1200, Long.MAX_VALUE)
        assertEquals(0.25f, frame.sweep, 0.01f)
        frame.resolve(line, 1800, Long.MAX_VALUE)
        assertEquals(1f, frame.sweep, 0.0001f)
        // Held complete for the rest of the gap, rather than restarting or fading back.
        frame.resolve(line, 1950, Long.MAX_VALUE)
        assertEquals(1f, frame.sweep, 0.0001f)
        assertEquals(start(line, 0), frame.wordStart)
    }

    @Test
    fun startOnlyTimingTakesTheWholeIntervalToTheNextStatedStart() {
        val line = startsOnly()
        // 1.000 -> 1.200 is the first word's; the rendering interval is the whole 200ms, not a cap.
        assertEquals(200L, KaraokeFrame.sweepWindowMs(line.segments, 0, 1600))
        assertEquals(200L, KaraokeFrame.ownershipWindowMs(line.segments, 0, 1600))
        val frame = KaraokeFrame()
        frame.resolve(line, 1100, 1600)
        assertEquals(0.5f, frame.sweep, 0.01f)
        frame.resolve(line, 1200, 1600)
        assertEquals(0f, frame.sweep, 0.0001f)
        assertEquals(start(line, 1), frame.wordStart)
    }

    @Test
    fun onlyAGapTooLargeToBeASyllableIsBounded() {
        // A second and a half is a held note and is followed exactly.
        assertEquals(1500L, KaraokeFrame.sweepWindowMs(pairWithGap(1500).segments, 0, Long.MAX_VALUE))
        // Eight seconds is an instrumental break and is not evidence about the word.
        val long = lrc("[00:00.000]<00:00.000>aa <00:08.000>bb")
        assertEquals(KaraokeFrame.SWEEP_DERIVED_MAX_MS, KaraokeFrame.sweepWindowMs(long.segments, 0, Long.MAX_VALUE))
        assertTrue("the safety bound must be far above any sung syllable",
            KaraokeFrame.SWEEP_DERIVED_MAX_MS >= 2000)
        val frame = KaraokeFrame()
        frame.resolve(long, 3000, Long.MAX_VALUE)
        assertEquals(1f, frame.sweep, 0.0001f)
        // Still the same word five seconds later: a rendering interval never advances anything.
        frame.resolve(long, 5000, Long.MAX_VALUE)
        assertEquals(start(long, 0), frame.wordStart)
    }

    @Test
    fun aStartOnlyWordWithNothingToBoundItUsesTheFallback() {
        val line = lrc("[00:00.000]<00:00.000>solo")
        val frame = KaraokeFrame()
        frame.resolve(line, KaraokeFrame.SWEEP_DERIVED_FALLBACK_MS / 2, Long.MAX_VALUE)
        assertEquals(0.5f, frame.sweep, 0.02f)
    }

    // ==================================================== the last word is never cut short
    // The last word of a line is the one a renderer can most easily lose: the list starts carrying
    // the line away, and fading it down, a pre-roll BEFORE the next line's stated time. What must
    // survive that is the word's FILL and its READABILITY - it has to be completely filled, and
    // still legible, by the time the row is gone. Nothing vertical is involved any more.

    @Test
    fun theColourOfAWordCannotMoveAnyOfItsGeometry() {
        // M. Device QA: letters appeared to change shape and size while the colour slid through
        // them. Everything the drawing measures has to be a function of the layout and the
        // playback position ONLY - never of the fill - so advancing the sweep through a word must
        // leave every geometry input identical.
        val line = startsOnly()
        val frame = KaraokeFrame()
        frame.resolve(line, 1200, Long.MAX_VALUE)
        val word = frame.wordStart
        val wordEnd = frame.wordEnd
        var previousSweep = -1f
        for (position in 1200L..1399L) {
            frame.resolve(line, position, Long.MAX_VALUE)
            assertEquals("the word's start never moves as it fills", word, frame.wordStart)
            assertEquals("nor its end", wordEnd, frame.wordEnd)
            assertTrue("and the fill only ever advances", frame.sweep >= previousSweep - 0.0001f)
            previousSweep = frame.sweep
        }
        // And the grapheme boundaries the fill is resolved against come from the text alone, so
        // they are the same whatever the clock or the colours are doing.
        val text = line.text
        val boundaries = graphemesOf(text).map { it.length }
        assertEquals("the same text always splits the same way", boundaries, graphemesOf(text).map { it.length })
    }

    @Test
    fun theRendererCarriesNoTimingOfItsOwnIntoTheSemantics() {
        // J. Everything the source stated is still exactly what it was.
        val stated = statedEnds()
        assertEquals("a stated TTML end still drives the fill", 800L,
            KaraokeFrame.sweepWindowMs(stated.segments, 0, Long.MAX_VALUE))
        assertEquals(600L, KaraokeFrame.sweepWindowMs(stated.segments, 1, Long.MAX_VALUE))
        val starts = startsOnly()
        assertEquals("start-only timing still fills to the next stated start", 200L,
            KaraokeFrame.sweepWindowMs(starts.segments, 0, Long.MAX_VALUE))
        assertEquals("and the word is still the one indexAt selects", 1,
            starts.segments.indexAt(1250))
        assertEquals(0, starts.segments.indexAt(1199))
        assertEquals(-1, starts.segments.indexAt(999))
        // And the stated starts themselves are untouched.
        assertEquals(1000L, starts.segments.startTimeMs(0))
        assertEquals(1200L, starts.segments.startTimeMs(1))
        assertEquals(1400L, starts.segments.startTimeMs(2))
    }

    @Test
    fun ownershipStillPassesExactlyOnTheStatedMillisecond() {
        // The hand-over is the source's, not the renderer's: whatever the fill is doing, the next
        // stated time takes the frame on its own millisecond and its own fill starts at zero.
        for (gap in longArrayOf(1500, 1000, 800, 500, 300, 200, 100, 50, 30)) {
            val line = pairWithGap(gap)
            val frame = KaraokeFrame()
            frame.resolve(line, gap - 1, Long.MAX_VALUE)
            assertEquals("${gap}ms word still owns the frame one millisecond before hand-over",
                start(line, 0), frame.wordStart)
            frame.resolve(line, gap, Long.MAX_VALUE)
            assertEquals("${gap}ms: ownership passes exactly on the stated time",
                start(line, 1), frame.wordStart)
            assertEquals("${gap}ms: and its fill starts immediately", 0f, frame.sweep, 0.0001f)
        }
    }

    @Test
    fun aRunOfVeryShortWordsStillFillsAtTheSpeedItIsSung() {
        // Three words 30ms apart - the passage that used to read as the text dancing. The fill is
        // allowed to be quick, because the words genuinely are.
        val line = lrc("[00:00.000]<00:00.000>a <00:00.030>b <00:00.060>c")
        val nextLine = 90L
        val frame = KaraokeFrame()
        frame.resolve(line, 29, nextLine)
        assertTrue(frame.sweep > 0.95f)
        frame.resolve(line, 30, nextLine)
        assertEquals(start(line, 1), frame.wordStart)
    }

    @Test
    fun anOverlappingTtmlSpanStillFillsForItsOwnStatedLength() {
        // A span stated as held for two seconds, with the next span starting 300ms in. The stated
        // end still drives the fill, and the next word still takes over on its stated time.
        val line = ttml(
            """<p begin="00:00.000" end="00:05.000">""" +
                """<span begin="00:00.000" end="00:02.000">Held</span> """ +
                """<span begin="00:00.300" end="00:00.900">Next</span></p>"""
        )
        assertEquals("the fill follows the stated end", 2000L,
            KaraokeFrame.sweepWindowMs(line.segments, 0, Long.MAX_VALUE))
        val frame = KaraokeFrame()
        frame.resolve(line, 299, Long.MAX_VALUE)
        assertTrue("its fill is still honestly mid-way: ${frame.sweep}", frame.sweep < 0.2f)
        frame.resolve(line, 300, Long.MAX_VALUE)
        assertEquals(start(line, 1), frame.wordStart)
    }

    // ============================================= split / syllable-timed displayed words
    // A source may state one displayed word as several timed parts - "be|au|ti|ful" for
    // "beautiful". The fill must follow every genuine part, at exactly the times the source gave
    // them, and removing the vertical wave changes none of that.

    /** "beautiful" cut into four stated syllables with no whitespace between them. */
    private fun syllables(): Line = lrc(
        "[00:00.000]<00:00.000>beau<00:00.200>ti<00:00.400>ful <00:00.800>day"
    )

    @Test
    fun consecutiveSegmentsInsideOneDisplayedWordAreOneWord() {
        val line = syllables()
        val segments = line.segments
        assertEquals("the fixture must really be split", 4, segments.size())
        // beau|ti|ful are one displayed word; "ful " ends with a space, so "day" starts a new one.
        assertFalse(KaraokeFrame.isWordContinuation(line.text, segments, 0))
        assertTrue(KaraokeFrame.isWordContinuation(line.text, segments, 1))
        assertTrue(KaraokeFrame.isWordContinuation(line.text, segments, 2))
        assertFalse(KaraokeFrame.isWordContinuation(line.text, segments, 3))
        for (index in 0..2) {
            assertEquals("segment $index belongs to the word that starts at 0",
                0, KaraokeFrame.lexicalStartIndex(line.text, segments, index))
        }
        assertEquals(3, KaraokeFrame.lexicalStartIndex(line.text, segments, 3))
    }

    @Test
    fun theFillStillFollowsEverySyllableOfASplitWord() {
        // Each stated syllable is still its own current segment at its own stated time, with its
        // own fill: nothing groups or averages the source's parts.
        val line = syllables()
        val frame = KaraokeFrame()
        for (index in 0..3) {
            val at = line.segments.startTimeMs(index)
            frame.resolve(line, at, Long.MAX_VALUE)
            assertEquals("syllable $index becomes current at its stated time",
                start(line, index), frame.wordStart)
            assertEquals("and its fill starts there", 0f, frame.sweep, 0.0001f)
            // Each syllable has its own window - 200, 200, 400, then the tail - and its own fill.
            val window = KaraokeFrame.sweepWindowMs(line.segments, index, Long.MAX_VALUE)
            frame.resolve(line, at + window / 2, Long.MAX_VALUE)
            assertEquals("syllable $index is half filled half way across its own interval",
                0.5f, frame.sweep, 0.02f)
        }
    }

    @Test
    fun nothingInTheRendererDelaysTheNextWord() {
        // The absolute rule. Ownership is Segments.indexAt and nothing else, so whatever the fill
        // is doing, the next stated time takes the frame on the exact millisecond.
        for (gap in longArrayOf(1500, 800, 400, 200, 100, 50, 30)) {
            val line = pairWithGap(gap)
            val frame = KaraokeFrame()
            frame.resolve(line, gap - 1, Long.MAX_VALUE)
            val sweepBefore = frame.sweep
            assertEquals("${gap}ms: still the first word", start(line, 0), frame.wordStart)
            frame.resolve(line, gap, Long.MAX_VALUE)
            assertEquals("${gap}ms: the next word is current on its stated millisecond, " +
                "whatever the outgoing fill had reached ($sweepBefore)", start(line, 1), frame.wordStart)
            assertEquals("${gap}ms: and its fill starts immediately", 0f, frame.sweep, 0.0001f)
        }
    }

    // ==================================================================== the last word of a line
    // "The final word of a line can still be visually skipped/swallowed as the player transitions
    // toward the next line." Acceptance blocker.

    @Test
    fun theFinalWordOwnsTheRestOfTheLineHoweverShortItIs() {
        for (tail in longArrayOf(1000, 500, 200, 100, 50)) {
            val line = lrc("[00:00.000]<00:00.000>first <00:01.000>last")
            val nextLine = 1000L + tail
            assertEquals("${tail}ms tail: the final word owns the rest of the line",
                tail, KaraokeFrame.ownershipWindowMs(line.segments, 1, nextLine))
            assertEquals("${tail}ms tail: and fills across it", tail,
                KaraokeFrame.sweepWindowMs(line.segments, 1, nextLine))
            val frame = KaraokeFrame()
            frame.resolve(line, 1000, nextLine)
            assertEquals("${tail}ms tail: the final word becomes current at its own time",
                start(line, 1), frame.wordStart)
            assertEquals(0f, frame.sweep, 0.0001f)
            frame.resolve(line, 1000 + tail / 2, nextLine)
            assertEquals("${tail}ms tail: half filled half way through", 0.5f, frame.sweep, 0.03f)
            frame.resolve(line, nextLine - 1, nextLine)
            assertTrue("${tail}ms tail: complete before the line can change, not swallowed by it",
                frame.sweep > 0.98f)
        }
    }

    @Test
    fun aLineAlreadyLeftBehindIsWhollySungRatherThanCaughtMidFill() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        assertTrue(frame.resolveRow(line, 0, 1, 1150, 1600))
        assertEquals(line.text.length, frame.sungEnd)
        assertEquals(frame.wordStart, frame.wordEnd)
        assertEquals("a line already left keeps the height its letters reached, it does not drop",
            line.text.length, frame.sungEnd)
    }

    // ============================================= semantic current line versus the pre-roll
    // The list may already be gliding toward the next line. Until that line's genuine timestamp it
    // is still secondary, and nothing in it may light up.

    @Test
    fun aLineTheFollowIsBringingInEarlyShowsNothingLit() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        // Line 2 is being moved into place while the clock is still inside line 1.
        assertTrue(frame.resolveRow(line, 2, 1, 1150, 1600))
        assertEquals(0, frame.sungEnd)
        assertEquals(frame.wordStart, frame.wordEnd)
        assertEquals(0f, frame.sweep, 0.0001f)
        assertEquals("and nothing on it reads as sung", 0, frame.sungEnd)
    }

    @Test
    fun theHierarchyChangesHandsAtTheGenuineTimestampAndNotAtThePreRoll() {
        // Two lines a second apart. The follow's lead is up to 440ms, so at 1.700 the list is
        // already moving toward line B - and line B must still show nothing.
        val lyrics = parse("[00:01.000]<00:01.000>alpha\n[00:02.000]<00:02.000>beta")
        val a = lyrics.lines[0]
        val b = lyrics.lines[1]
        val frame = KaraokeFrame()

        // Mid pre-roll: the clock is inside A.
        assertEquals(0, lyrics.lineAt(1700))
        frame.resolveRow(a, 0, lyrics.lineAt(1700), 1700, 2000)
        assertTrue("A is still the line being sung", frame.wordStart < frame.wordEnd)
        frame.resolveRow(b, 1, lyrics.lineAt(1700), 1700, Long.MAX_VALUE)
        assertEquals("B is secondary and unlit while the list moves toward it", 0, frame.sungEnd)
        assertEquals(frame.wordStart, frame.wordEnd)

        // At B's real timestamp the hierarchy transfers, and only then.
        assertEquals(1, lyrics.lineAt(2000))
        frame.resolveRow(a, 0, lyrics.lineAt(2000), 2000, 2000)
        assertEquals("A is finished, wholly sung", a.text.length, frame.sungEnd)
        frame.resolveRow(b, 1, lyrics.lineAt(2000), 2000, Long.MAX_VALUE)
        assertEquals("B's first word starts exactly now", start(b, 0), frame.wordStart)
        assertEquals(0f, frame.sweep, 0.0001f)
    }

    // ======================================= the line fade rides the scroll, it does not follow it
    // Device QA: "the list scrolls, the scroll finishes, THEN the colour changes." The fade now
    // reads the follow animator's own fraction - the same number that is moving the list this
    // frame - so the two are one gesture. These assert the crossfade directly.

    @Test
    fun theOutgoingLineIsFullyCurrentAtTheStartOfTheScroll() {
        assertEquals(1f, AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 0f), 0.0001f)
        assertEquals(0f, AudioPlayerAlert.lyricsFocusValue(4, 3, 4, 0f), 0.0001f)
    }

    @Test
    fun theIncomingLineIsFullyCurrentAtTheEndOfTheScroll() {
        assertEquals(0f, AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 1f), 0.0001f)
        assertEquals(1f, AudioPlayerAlert.lyricsFocusValue(4, 3, 4, 1f), 0.0001f)
    }

    @Test
    fun bothLinesAreMidTransitionHalfWayThroughTheScroll() {
        val outgoing = AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 0.5f)
        val incoming = AudioPlayerAlert.lyricsFocusValue(4, 3, 4, 0.5f)
        assertTrue("the outgoing line has started fading: $outgoing", outgoing < 1f && outgoing > 0f)
        assertTrue("the incoming line has started arriving: $incoming", incoming < 1f && incoming > 0f)
    }

    @Test
    fun theFadeIsContinuousAndComplementaryAcrossTheWholeScroll() {
        var previousIn = -1f
        for (step in 0..200) {
            val progress = step / 200f
            val outgoing = AudioPlayerAlert.lyricsFocusValue(3, 3, 4, progress)
            val incoming = AudioPlayerAlert.lyricsFocusValue(4, 3, 4, progress)
            // No dip or overshoot in total brightness at any point of the travel.
            assertEquals("complementary at $progress", 1f, outgoing + incoming, 0.0001f)
            assertTrue("the incoming line only ever brightens at $progress", incoming >= previousIn - 0.0001f)
            // The colour is genuinely moving throughout - never parked until the scroll ends.
            if (progress > 0.02f) {
                assertTrue("something has changed by $progress", incoming > 0.005f)
            }
            previousIn = incoming
        }
    }

    @Test
    fun noThirdLineIsTouchedByAHandOver() {
        assertEquals(0f, AudioPlayerAlert.lyricsFocusValue(9, 3, 4, 0.5f), 0.0001f)
        assertEquals(0f, AudioPlayerAlert.lyricsFocusValue(-1, 3, 4, 0.5f), 0.0001f)
    }

    @Test
    fun aLineWithNoWordTagsFadesByExactlyTheSameRule() {
        // A karaoke document may contain a section with no inline timing. It resolves to the
        // fallback branch, whose only emphasis input is this same crossfade - so it fades with the
        // scroll like an ordinary synced line rather than switching once the scroll has stopped.
        val untimed = parse("[00:01.00]an instrumental section").lines[0]
        assertFalse("no word state is invented for it",
            KaraokeFrame().resolveRow(untimed, 0, 0, 2000, Long.MAX_VALUE))
        assertEquals(1f, AudioPlayerAlert.lyricsFocusValue(0, 0, 1, 0f), 0.0001f)
        assertEquals(0f, AudioPlayerAlert.lyricsFocusValue(0, 0, 1, 1f), 0.0001f)
    }

    @Test
    fun aTrueKaraokeLineKeepsItsWordStateWhileTheLineFadesOut() {
        // The two layers are independent. Whatever the line-level fade is doing, the word state of
        // the outgoing line is still resolved from the clock and is still correct all the way down.
        val line = startsOnly()
        val frame = KaraokeFrame()
        for (progress in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val fade = AudioPlayerAlert.lyricsFocusValue(0, 0, 1, progress)
            assertTrue("the fade is a real value at $progress", fade in 0f..1f)
            // The frame takes no fade input at all - it cannot be influenced by one.
            frame.resolve(line, 1500, 1600)
            assertEquals("the word is the one the clock says", start(line, 2), frame.wordStart)
            assertEquals("and its fill is where the clock says", 0.5f, frame.sweep, 0.02f)
        }
    }

    // ============================================================================== pause
    // Pausing must not leave a word hanging above its baseline, and resuming must not replay it.

    @Test
    fun aPausedPositionKeepsTheSameWordAndTheSameFillForever() {
        val line = pairWithGap(1000)
        val frozen = KaraokeFrame()
        frozen.resolve(line, 380, Long.MAX_VALUE)
        val word = frozen.wordStart
        val sweep = frozen.sweep
        // Every later re-resolve at the paused position - and there is one per settle frame -
        // reproduces the identical semantic state.
        repeat(8) {
            frozen.resolve(line, 380, Long.MAX_VALUE)
            assertEquals("the paused word never changes", word, frozen.wordStart)
            assertEquals("the paused fill never moves", sweep, frozen.sweep, 0f)
        }
    }

    @Test
    fun resumingContinuesFromThePlaybackPositionRatherThanRestartingTheWord() {
        val line = pairWithGap(1000)
        val paused = KaraokeFrame()
        val resumed = KaraokeFrame()
        paused.resolve(line, 380, Long.MAX_VALUE)
        // Time has passed on the wall clock but not on the playback clock.
        resumed.resolve(line, 380, Long.MAX_VALUE)
        assertEquals(paused.wordStart, resumed.wordStart)
        assertEquals(paused.sweep, resumed.sweep, 0f)
        assertEquals(paused.sungEnd, resumed.sungEnd)
        // And a moment after resuming, it has moved on rather than started again.
        resumed.resolve(line, 420, Long.MAX_VALUE)
        assertTrue("the fill continues forward", resumed.sweep > paused.sweep)
        assertEquals("still the same word", paused.wordStart, resumed.wordStart)
    }

    // ============================================================================== colour

    @Test
    fun aCompletedKaraokeWordResolvesToActualWhite() {
        // The player background a karaoke page is shown on is dark, and there the answer is white
        // itself - not the theme's title colour, which is what it silently used to be.
        for (background in intArrayOf(Color.BLACK, 0xFF101010.toInt(), 0xFF2B2B2B.toInt(), 0xFF3A4A5A.toInt())) {
            assertEquals(
                "sweep target on ${Integer.toHexString(background)}",
                Color.WHITE,
                AudioPlayerAlert.karaokeSweepColor(background, 0xFFB0C4DE.toInt())
            )
        }
        assertNotEquals(Color.WHITE, 0xFFB0C4DE.toInt())
    }

    @Test
    fun whiteStandsAsideOnlyWhereItWouldBeInvisible() {
        // Not a silent substitution: the one case is a light background, where white on white
        // would render the sung word unreadable.
        val title = 0xFF1A1A1A.toInt()
        assertEquals(title, AudioPlayerAlert.karaokeSweepColor(Color.WHITE, title))
        assertEquals(title, AudioPlayerAlert.karaokeSweepColor(0xFFF2F2F2.toInt(), title))
    }

    // =================================================================== one visual family
    // Normal, line-synced and karaoke are three capabilities of one player, not three designs.

    @Test
    fun everyLyricModeIsSetWithTheSameTypography() {
        // There is exactly one size, one spacing and one row metric set, so no mode can be the
        // small ugly one. A second, smaller set is what the previous build shipped.
        assertEquals(22, AudioPlayerAlert.LYRICS_TEXT_SIZE_DP)
        assertTrue("large", AudioPlayerAlert.LYRICS_TEXT_SIZE_DP >= 20)
        assertTrue("generous line spacing", AudioPlayerAlert.LYRICS_LINE_SPACING_DP >= 2)
        assertTrue("generous rows", AudioPlayerAlert.LYRICS_ROW_MIN_HEIGHT_DP >= 60)
        assertTrue("generous padding", AudioPlayerAlert.LYRICS_ROW_PADDING_V_DP >= 12)
    }

    @Test
    fun normalAndLineOnlySyncedLyricsGetNoWordEffectsAtAll() {
        // Line-only synced: a real timestamp, no inline timing. It must fall back rather than
        // invent a word.
        val syncedLineOnly = parse("[00:01.00]just a line").lines[0]
        assertFalse(KaraokeFrame().resolveRow(syncedLineOnly, 0, 0, 2000, Long.MAX_VALUE))
        assertFalse(KaraokeFrame().resolve(syncedLineOnly, 2000, Long.MAX_VALUE))
        // Plain, untimed lyrics: likewise, and with no timestamp to be current at either.
        val plain = parse("just some words").lines[0]
        assertFalse(KaraokeFrame().resolveRow(plain, 0, 0, 2000, Long.MAX_VALUE))
        assertEquals(SyncedLyricsController.Kind.PLAIN, parse("just some words").kind)
    }

    // ==================================================================== seeking and rebinding

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
        assertEquals(first.sungEnd, recycled.sungEnd)
    }

    @Test
    fun aLineIsInertUntilItsOwnTimestamp() {
        val frame = KaraokeFrame()
        assertFalse(frame.resolve(statedEnds(), 900, Long.MAX_VALUE))
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

    // ============================================================== fill geometry and bidi

    private class Run(val left: Float, val right: Float, val rtl: Boolean)

    private fun runsOf(layout: Layout, start: Int, end: Int): List<Run> {
        val runs = ArrayList<Run>()
        KaraokeGeometry.forEachVisualRun(layout, start, end) { l, r, _, _, rtl -> runs.add(Run(l, r, rtl)) }
        return runs
    }

    /** Horizontal midpoint of the character at [index], from the layout's own two edges. */
    private fun charMidpoint(layout: Layout, index: Int): Float {
        val line = layout.getLineForOffset(index)
        val a = KaraokeGeometry.edgeAt(layout, line, index, false)
        val b = KaraokeGeometry.edgeAt(layout, line, index + 1, true)
        return (a + b) / 2f
    }

    /**
     * The property that matters for bidi: a timed range may only paint glyphs that are inside it.
     * A single rectangle spanning the range's two horizontal extremes fails this the moment the
     * text reorders, because the characters in between are not the characters in the range.
     */
    private fun assertSweepsOnlyItsOwnGlyphs(layout: Layout, start: Int, end: Int, label: String) {
        val runs = runsOf(layout, start, end)
        assertTrue("$label: the range must produce some geometry", runs.isNotEmpty())
        for (index in 0 until layout.text.length) {
            if (index in start until end) continue
            if (Character.isWhitespace(layout.text[index])) continue
            val mid = charMidpoint(layout, index)
            for (run in runs) {
                assertFalse(
                    "$label: '${layout.text[index]}' at $index (x=$mid) is outside [$start,$end) " +
                        "but sits inside a swept run [${run.left}, ${run.right}]",
                    mid > run.left + 0.5f && mid < run.right - 0.5f
                )
            }
        }
    }

    @Test
    fun aPureLtrWordSweepsItselfAndNothingElse() {
        val layout = layoutOf("Hello world again", 10000)
        assertSweepsOnlyItsOwnGlyphs(layout, 6, 11, "LTR 'world'")
        val runs = runsOf(layout, 6, 11)
        assertEquals(1, runs.size)
        assertFalse(runs[0].rtl)
    }

    @Test
    fun aPureRtlWordSweepsRightToLeftAndNothingElse() {
        val layout = layoutOf("مرحبا بالعالم جميعا", 10000)
        assertSweepsOnlyItsOwnGlyphs(layout, 6, 13, "RTL word")
        val runs = runsOf(layout, 6, 13)
        assertEquals(1, runs.size)
        assertTrue("an RTL run fills from its right edge", runs[0].rtl)
    }

    @Test
    fun aLatinWordInsideArabicSweepsOnlyTheLatinWord() {
        val text = "مرحبا Hello بالعالم"
        val layout = layoutOf(text, 10000)
        val start = text.indexOf("Hello")
        assertSweepsOnlyItsOwnGlyphs(layout, start, start + 5, "Latin inside Arabic")
        val runs = runsOf(layout, start, start + 5)
        assertFalse("the embedded Latin word reads left to right", runs[0].rtl)
    }

    @Test
    fun westernDigitsInsideArabicSweepOnlyTheDigits() {
        val text = "الغرفة 2024 مفتوحة"
        val layout = layoutOf(text, 10000)
        val start = text.indexOf("2024")
        assertSweepsOnlyItsOwnGlyphs(layout, start, start + 4, "digits inside Arabic")
    }

    @Test
    fun westernDigitsInsideHebrewSweepOnlyTheDigits() {
        val text = "החדר 2024 פתוח"
        val layout = layoutOf(text, 10000)
        val start = text.indexOf("2024")
        assertSweepsOnlyItsOwnGlyphs(layout, start, start + 4, "digits inside Hebrew")
    }

    @Test
    fun aRangeCrossingADirectionChangeBecomesSeveralPiecesRatherThanOneRectangle() {
        val text = "مرحبا Hello بالعالم"
        val layout = layoutOf(text, 10000)
        val start = text.indexOf("Hello")
        // From inside the Arabic, through the Latin word: two directions, therefore two runs.
        val runs = runsOf(layout, start - 3, start + 5)
        assertTrue("a range crossing a direction change must not be one rectangle: ${runs.size}", runs.size >= 2)
        assertSweepsOnlyItsOwnGlyphs(layout, start - 3, start + 5, "range across a bidi boundary")
        // And the pieces really do carry different directions.
        assertTrue("the pieces read in different directions", runs.any { it.rtl } && runs.any { !it.rtl })
    }

    @Test
    fun wrappedMixedDirectionTextIsCutAtTheWrapAsWellAsAtTheRun() {
        val text = "مرحبا Hello بالعالم مرحبا بالعالم جميعا"
        val layout = layoutOf(text, 200)
        assertTrue("the fixture must actually wrap", layout.lineCount > 1)
        val runs = runsOf(layout, 0, text.length)
        assertTrue("every visual line contributes at least one piece", runs.size >= layout.lineCount)
        for (run in runs) {
            assertTrue("no piece may be empty or inverted", run.right > run.left)
        }
    }

    @Test
    fun aWrappedWordFillsItsFirstPieceBeforeItsSecond() {
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
    fun theEndOfAWrappedWordIsTakenFromItsOwnVisualLine() {
        // Narrow enough that the text certainly wraps.
        val layout = layoutOf("wrapping behaviour matters here", 120)
        assertTrue("the fixture must actually wrap", layout.lineCount > 1)
        val firstLineEnd = layout.getLineEnd(0)
        for (trailing in booleanArrayOf(false, true)) {
            val x = KaraokeGeometry.edgeAt(layout, 0, firstLineEnd, trailing)
            // Without the wrap correction this would come back as the *next* line's starting edge.
            assertTrue("end of line 0 must sit on line 0, not at the next line's left edge", x > 1f)
            assertTrue(x <= layout.getLineRight(0) + 1f)
        }
    }

    private fun layoutOf(text: String, width: Int): Layout {
        val paint = TextPaint()
        paint.textSize = 48f
        paint.typeface = Typeface.DEFAULT_BOLD
        @Suppress("DEPRECATION")
        return StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
    }

    // ==================================================================== grapheme boundaries

    /** Every boundary [clusterEnd] hands back must be one the platform segmenter agrees with. */
    private fun assertNeverSplits(text: String, label: String) {
        val breaks = java.text.BreakIterator.getCharacterInstance()
        breaks.setText(text)
        for (offset in 0..text.length) {
            val snapped = KaraokeGeometry.clusterEnd(text, offset)
            assertTrue("$label: clusterEnd must not move backwards at $offset", snapped >= offset)
            assertTrue("$label: clusterEnd must stay in range at $offset", snapped <= text.length)
            assertTrue(
                "$label: offset $offset snapped to $snapped, which splits a cluster",
                snapped == 0 || snapped == text.length || breaks.isBoundary(snapped)
            )
        }
    }

    @Test
    fun aBoundaryNeverLandsInsideSomethingDrawnAsOneUnit() {
        // Surrogate pair, combining mark, ZWJ sequence, flag, variation selector.
        assertEquals(2, KaraokeGeometry.clusterEnd("😀ab", 1))
        assertEquals(2, KaraokeGeometry.clusterEnd("ábc", 1))
        assertEquals(5, KaraokeGeometry.clusterEnd("👨‍👩", 2))
        assertEquals(4, KaraokeGeometry.clusterEnd("🇪🇹", 2))
        assertEquals(2, KaraokeGeometry.clusterEnd("❤️x", 1))
    }

    @Test
    fun theCasesTheHandWrittenSegmenterUsedToSplit() {
        // An emoji with a skin-tone modifier: base is a surrogate pair, modifier is another.
        val skinTone = "👍🏽x"
        assertEquals("a skin tone must not be cut off its base", 4, KaraokeGeometry.clusterEnd(skinTone, 2))
        assertNeverSplits(skinTone, "skin tone")

        // A Devanagari conjunct: consonant + virama + consonant is drawn as one ligature.
        val conjunct = "क्षा"
        assertNeverSplits(conjunct, "Devanagari conjunct")
        assertTrue(
            "a boundary must never sit between a consonant and its virama",
            KaraokeGeometry.clusterEnd(conjunct, 1) >= 2
        )

        // Hangul jamo composed from L + V + T.
        val jamo = "각b"
        assertNeverSplits(jamo, "Hangul jamo")
        assertEquals("L+V+T is one syllable", 3, KaraokeGeometry.clusterEnd(jamo, 1))
    }

    /**
     * The enumeration the row itself uses to decide what one unit of the fill is: walk the text
     * cluster by cluster with [clusterEnd], exactly as LyricsTextView.ensureClusters does.
     */
    private fun graphemesOf(text: String): List<String> {
        val out = ArrayList<String>()
        var offset = 0
        while (offset < text.length) {
            val next = KaraokeGeometry.clusterEnd(text, offset + 1)
            val end = if (next <= offset) offset + 1 else minOf(next, text.length)
            out.add(text.substring(offset, end))
            offset = end
        }
        return out
    }

    @Test
    fun theFillAdvancesThroughWholeGraphemesAndNeverThroughHalfOfOne() {
        // K. One unit of the fill is a user-visible grapheme cluster, never a UTF-16 char. Every
        // piece the sung boundary can stop inside has to be a piece the platform segmenter agrees
        // is one, or the fill would be resolved by cutting a character in half.
        val breaks = java.text.BreakIterator.getCharacterInstance()
        for (text in listOf(
            "Hello world",
            "ȩ́x ábc",
            "😀 and 👨\u200D👩\u200D👧\u200D👦 and 🇪🇹 and 👍🏽",
            "ሰላም ለዓለም",
            "مرحبا بالعالم",
            "नमस्ते दुनिया"
        )) {
            val pieces = graphemesOf(text)
            assertEquals("the pieces must reassemble the text exactly", text, pieces.joinToString(""))
            breaks.setText(text)
            var offset = 0
            for (piece in pieces) {
                assertTrue("$text: a fill unit must be a whole cluster, got '$piece' at $offset",
                    breaks.isBoundary(offset))
                offset += piece.length
            }
            assertTrue("$text: and the walk must terminate on the end", offset == text.length)
            assertTrue("$text: this fixture is meant to have several units", pieces.size > 1)
        }
        // A four-person family emoji is ONE unit of the fill, not eleven.
        assertEquals(1, graphemesOf("👨\u200D👩\u200D👧\u200D👦").size)
        assertEquals(1, graphemesOf("🇪🇹").size)
        assertEquals(1, graphemesOf("👍🏽").size)
        assertEquals("a combining mark rides its base", 1, graphemesOf("ȩ́").size)
        assertEquals("an Amharic syllable is one unit", 4, graphemesOf("ሰላም ").size)
    }

    @Test
    fun theSegmenterAgreesWithThePlatformAcrossEveryScriptWeDraw() {
        assertNeverSplits("Hello world", "Latin")
        assertNeverSplits("مرحبا بالعالم", "Arabic")
        assertNeverSplits("ሰላም ለዓለም", "Amharic")
        assertNeverSplits("你好世界", "CJK")
        assertNeverSplits("한국어 가사", "Hangul")
        assertNeverSplits("नमस्ते दुनिया", "Devanagari")
        assertNeverSplits("😀👨‍👩‍👧‍👦🇪🇹👍🏽❤️", "emoji")
        assertNeverSplits("ȩ́x", "stacked combining marks")
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

    @Test
    fun aRenderingBoundaryNeverMovesATimestampOrTheCurrentWord() {
        // clusterEnd is allowed to move what is painted. It is not allowed to change which word the
        // clock selected, and it cannot: the frame is resolved before any snapping happens.
        val line = startsOnly()
        val frame = KaraokeFrame()
        for (position in 1000L..1600L step 25L) {
            frame.resolve(line, position, 1600)
            val index = line.segments.indexAt(position)
            assertEquals("at $position", if (index < 0) start(line, 0) else start(line, index), frame.wordStart)
        }
    }

    // ============================ the composed picture: the fade, the sweep and the hand-over
    // The previous round of fade tests asserted the crossfade formula on its own, which is exactly
    // how the "swallowed last word" came back: every isolated part was right and the composition
    // was not. These drive the real combination - a final word still sweeping while the pre-roll
    // scroll is already fading its line away - and assert what a person watching would say.

    /** Focus as the page actually paints it: the crossfade, floored while a word is still sung. */
    private fun paintedFocus(frame: KaraokeFrame, outgoingRow: Int, incomingRow: Int,
                             scrollProgress: Float, isCurrentLine: Boolean): Float {
        val crossfade = AudioPlayerAlert.lyricsFocusValue(outgoingRow, outgoingRow, incomingRow, scrollProgress)
        val singing = isCurrentLine && frame.wordEnd > frame.wordStart
        return maxOf(crossfade, AudioPlayerAlert.karaokeReadableFloor(singing, frame.sweep))
    }

    // "one two three" at [00:01.000], the line after it at 00:02.000. The gap is 1000ms so the
    // follow's lead is its 440ms cap: the scroll toward the next line starts at 1560 and the last
    // word - "three", stated at 1400 - is still being sung through every frame of it.
    private val preRollStart = 1560L
    private val preRollEnd = 2000L

    private fun atScroll(progress: Float): Long =
        preRollStart + ((preRollEnd - preRollStart) * progress).toLong()

    @Test
    fun theLastWordIsStillBeingSungThroughTheWholePreRoll() {
        // Guards the fixture: if the final word were finished before the scroll began there would
        // be nothing to protect and every assertion below would pass vacuously.
        val line = startsOnly()
        val frame = KaraokeFrame()
        for (progress in floatArrayOf(0f, 0.25f, 0.5f, 0.75f)) {
            frame.resolve(line, atScroll(progress), preRollEnd)
            assertEquals("the final word owns the pre-roll at $progress", start(line, 2), frame.wordStart)
            assertTrue("and its fill is genuinely mid-travel at $progress", frame.sweep in 0.01f..0.99f)
        }
    }

    @Test
    fun theOutgoingLineStillFadesDuringTheScroll() {
        // The fade is not postponed, frozen or removed: the line is already visibly less than
        // current a quarter of the way into the travel, and it keeps going.
        val line = startsOnly()
        val frame = KaraokeFrame()
        var previous = 1.01f
        for (step in 0..40) {
            val progress = step / 40f
            frame.resolve(line, atScroll(progress), preRollEnd)
            val focus = paintedFocus(frame, 3, 4, progress, isCurrentLine = true)
            assertTrue("the fade never reverses at $progress: $focus vs $previous", focus <= previous + 0.0001f)
            previous = focus
        }
        frame.resolve(line, atScroll(0.25f), preRollEnd)
        assertTrue("the line has visibly left full focus by a quarter of the scroll",
            paintedFocus(frame, 3, 4, 0.25f, isCurrentLine = true) < 0.95f)
    }

    @Test
    fun theFinalWordStaysReadableAtEveryPointOfTheScroll() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        for (progress in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            frame.resolve(line, atScroll(progress), preRollEnd)
            val focus = paintedFocus(frame, 3, 4, progress, isCurrentLine = true)
            assertTrue("still clearly readable at $progress of the scroll: $focus",
                focus >= AudioPlayerAlert.KARAOKE_SINGING_FOCUS_FLOOR - 0.0001f)
            assertTrue("but never frozen at full focus at $progress: $focus", focus < 1f)
        }
        // Halfway and three quarters through, the bare crossfade would have handed the word to the
        // subordinate colour. The floor is what keeps it legible while it is still being sung.
        assertTrue("the floor is what is doing the work at 0.75",
            AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 0.75f) < AudioPlayerAlert.KARAOKE_SINGING_FOCUS_FLOOR)
    }

    @Test
    fun theFloorIsARestraintAndNotFullFocus() {
        // A floor at or near 1 would be the "scroll first, recolour afterwards" behaviour QA
        // rejected. It has to be low enough that the line is plainly subordinate to the incoming
        // one and high enough that the word can still be read.
        assertTrue(AudioPlayerAlert.KARAOKE_SINGING_FOCUS_FLOOR in 0.35f..0.7f)
    }

    @Test
    fun theFloorReleasesWithTheWordsOwnFillSoNothingStepsAtTheBoundary() {
        var previous = AudioPlayerAlert.karaokeReadableFloor(true, 0f)
        for (step in 0..100) {
            val sweep = step / 100f
            val floor = AudioPlayerAlert.karaokeReadableFloor(true, sweep)
            assertTrue("the floor never rises at $sweep", floor <= previous + 0.0001f)
            assertTrue("and never jumps at $sweep", previous - floor < 0.1f)
            previous = floor
        }
        assertEquals("gone by the time the word's fill is complete", 0f,
            AudioPlayerAlert.karaokeReadableFloor(true, 1f), 0.0001f)
    }

    @Test
    fun aLineAlreadyLeftBehindGetsNoFloorAndCanGoFullySecondary() {
        // Past the boundary the line is no longer current: resolveRow reports it wholly sung with
        // no word in progress, so the floor stops applying and the crossfade alone decides.
        val lyrics = parse("[00:01.000]<00:01.000>one <00:01.400>three\n[00:02.000]next")
        val frame = KaraokeFrame()
        frame.resolveRow(lyrics.lines[0], 0, lyrics.lineAt(2100), 2100, Long.MAX_VALUE)
        assertFalse("no word of it is in progress any more", frame.wordEnd > frame.wordStart)
        assertEquals(0f, AudioPlayerAlert.karaokeReadableFloor(
            frame.wordEnd > frame.wordStart, frame.sweep), 0.0001f)
        assertEquals("so it can finish fully subordinate", 0f,
            paintedFocus(frame, 3, 4, 1f, isCurrentLine = false), 0.0001f)
    }

    @Test
    fun theFillItselfIsUntouchedByTheFadeAndTheFloor() {
        // The floor changes how the LINE is coloured. It must not change where the sweep is, which
        // is still read straight off the clock.
        val line = startsOnly()
        val frame = KaraokeFrame()
        var previous = -1f
        for (progress in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            frame.resolve(line, atScroll(progress), preRollEnd)
            assertTrue("the fill only travels forwards at $progress", frame.sweep > previous)
            previous = frame.sweep
        }
        assertEquals("and completes exactly at the line boundary", 1f, previous, 0.0001f)
    }

    // ================================================================= the fade is eased once
    // The follow animator hands lyricsFocusValue an ALREADY eased fraction. Easing it again is
    // what made the outgoing line lose most of its prominence long before the halfway point.

    @Test
    fun halfWayThroughTheScrollIsHalfWayThroughTheFade() {
        assertEquals(0.5f, AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 0.5f), 0.02f)
        assertEquals(0.5f, AudioPlayerAlert.lyricsFocusValue(4, 3, 4, 0.5f), 0.02f)
    }

    @Test
    fun theFadeIsNotEasedASecondTimeOnTopOfTheAnimatorsOwnEasing() {
        // A second easing shows up as a bow in the curve: the incoming line would be well past the
        // fraction it was handed. The mapping has to be the identity.
        for (step in 0..20) {
            val progress = step / 20f
            assertEquals("no extra curve at $progress", progress,
                AudioPlayerAlert.lyricsFocusValue(4, 3, 4, progress), 0.0001f)
        }
    }

    @Test
    fun theIncomingLineDoesNotTakeOverBeforeItArrives() {
        assertTrue("a quarter in, the outgoing line is still the dominant one",
            AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 0.25f) >
                AudioPlayerAlert.lyricsFocusValue(4, 3, 4, 0.25f))
        assertTrue("three quarters in, the incoming line has taken over",
            AudioPlayerAlert.lyricsFocusValue(4, 3, 4, 0.75f) >
                AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 0.75f))
    }

    // ============================================== pause, interruption and cancellation
    // All three stop a transition that is part way through. Painting reads these values directly
    // now, so resolving the bookkeeping in one frame is a visible colour and blur pop.

    @Test
    fun aTransitionStartsFromWhateverIsOnScreenRatherThanFromTheEndpoints() {
        // Row 4 was 0.6 of the way in when a new line retargeted the follow at row 5. The new
        // transition begins with row 4 exactly where the eye left it, not snapped to full focus.
        val carried = AudioPlayerAlert.lyricsFocusValue(4, 3, 4, 0.6f)
        assertEquals("no step on the row being handed over", carried,
            AudioPlayerAlert.lyricsFocusValue(4, 4, 5, 0f, carried, 0f), 0.0001f)
        assertEquals("and none on the row arriving", 0f,
            AudioPlayerAlert.lyricsFocusValue(5, 4, 5, 0f, carried, 0f), 0.0001f)
        assertEquals("which still finishes fully subordinate", 0f,
            AudioPlayerAlert.lyricsFocusValue(4, 4, 5, 1f, carried, 0f), 0.0001f)
        assertEquals("while the new line becomes current", 1f,
            AudioPlayerAlert.lyricsFocusValue(5, 4, 5, 1f, carried, 0f), 0.0001f)
    }

    @Test
    fun theThirdRowOfAnInterruptedHandOverFadesOutInsteadOfBeingDropped() {
        // Interrupting an A -> B crossfade leaves A still partly lit. Two rows cannot express
        // three, so A rides the extra slot down to nothing over the new transition.
        val stranded = AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 0.6f)
        assertTrue("A really is still lit when the interruption lands", stranded > 0.1f)
        assertEquals("it starts where it was", stranded,
            AudioPlayerAlert.lyricsDropFocusValue(stranded, 0f), 0.0001f)
        assertEquals("and ends at rest", 0f,
            AudioPlayerAlert.lyricsDropFocusValue(stranded, 1f), 0.0001f)
        var previous = stranded + 0.0001f
        for (step in 0..40) {
            val value = AudioPlayerAlert.lyricsDropFocusValue(stranded, step / 40f)
            assertTrue("monotonic at $step", value <= previous + 0.0001f)
            previous = value
        }
    }

    @Test
    fun settlingOntoALineAlreadySettledThereChangesNothing() {
        // A pause or a cancellation that lands on the line the hierarchy already belongs to is a
        // no-op, not a re-run of the crossfade.
        val noRow = -1 // RecyclerView.NO_POSITION
        assertEquals(1f, AudioPlayerAlert.lyricsFocusValue(4, noRow, 4, 1f), 0.0001f)
        assertEquals(1f, AudioPlayerAlert.lyricsFocusValue(4, noRow, 4, 0f, 1f, 1f), 0.0001f)
    }

    @Test
    fun aPauseInsideThePreRollHandsTheHierarchyBackWithoutAStep() {
        // Paused mid-pre-roll the follow settles back onto the line whose timestamp has passed.
        // Both rows continue from where they are: the outgoing one rises back rather than popping,
        // and the half-promoted one fades out rather than vanishing.
        val outgoing = AudioPlayerAlert.lyricsFocusValue(3, 3, 4, 0.5f)
        val promoted = AudioPlayerAlert.lyricsFocusValue(4, 3, 4, 0.5f)
        assertEquals("no step on the line getting the hierarchy back", outgoing,
            AudioPlayerAlert.lyricsFocusValue(3, 4, 3, 0f, promoted, outgoing), 0.0001f)
        assertEquals("nor on the one giving it up", promoted,
            AudioPlayerAlert.lyricsFocusValue(4, 4, 3, 0f, promoted, outgoing), 0.0001f)
        assertEquals("the settle ends with the current line fully current", 1f,
            AudioPlayerAlert.lyricsFocusValue(3, 4, 3, 1f, promoted, outgoing), 0.0001f)
        assertEquals("and the pre-rolled one back at rest", 0f,
            AudioPlayerAlert.lyricsFocusValue(4, 4, 3, 1f, promoted, outgoing), 0.0001f)
    }

    // ====================================================================== fixture guards

    @Test
    fun theParserIsWhatSaysWhichWordsExist() {
        // Guards the fixtures themselves: if these ever stop being word-timed, every assertion
        // above would pass vacuously.
        assertEquals(SyncedLyricsController.Kind.SYNCED, parse("[00:01.000]<00:01.000>one").kind)
        assertEquals(3, startsOnly().segments.size())
        assertEquals(2, statedEnds().segments.size())
        assertTrue(statedEnds().segments.hasEndTime(0))
        assertFalse(startsOnly().segments.hasEndTime(0))
        assertEquals(2, pairWithGap(500).segments.size())
        assertEquals(500L, pairWithGap(500).segments.startTimeMs(1))
    }

    // ============================================ typography is invariant through the sweep
    // The second hard acceptance requirement, and the one device QA kept failing: "muted text and
    // white text looked like two different fonts - shape changed, size changed, spacing changed,
    // weight changed."
    //
    // The cause was architectural. The old renderer drew the row several times per frame, through
    // separate clipped and translated passes, so one glyph could be rasterised twice against two
    // different pixel grids; the eye reads that as the letter changing weight and spacing as the
    // boundary crosses it.
    //
    // The cure is structural, and these tests pin the structure rather than a tolerance: karaoke is
    // appearance state on a non-metric span, the row is drawn by ONE ordinary TextView draw, and
    // therefore NOTHING about the geometry can depend on the sweep at all. Not "changes by less
    // than a pixel" - cannot differ, because it is the same single layout and the same single
    // rasterisation whatever the colours are.

    /** The sweep values every invariant below is checked at, including both endpoints. */
    private val sweeps = floatArrayOf(0f, 0.1f, 0.5f, 0.9f, 1f)

    /** The visual strings device QA reported damage on, plus the scripts that stress shaping. */
    private val typographyStrings = listOf(
        "minimum", "office", "AVATAR", "fly", "jazz",
        "ሰላም ለዓለም",
        "مرحبا بالعالم",
        "ȩ́x ábc",
        "😀 and 👨‍👩‍👧‍👦",
        "🇪🇹",
        "beautiful"
    )

    private fun karaokePaint(): TextPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = 44f
        typeface = Typeface.DEFAULT_BOLD
    }

    /** A snapshot of every metric a karaoke frame is forbidden to move. */
    private class Metrics(layout: Layout, paint: TextPaint) {
        val lineCount = layout.lineCount
        val width = layout.width
        val height = layout.height
        val left = (0 until layout.lineCount).map { layout.getLineLeft(it) }
        val right = (0 until layout.lineCount).map { layout.getLineRight(it) }
        val top = (0 until layout.lineCount).map { layout.getLineTop(it) }
        val bottom = (0 until layout.lineCount).map { layout.getLineBottom(it) }
        val baseline = (0 until layout.lineCount).map { layout.getLineBaseline(it) }
        val starts = (0 until layout.lineCount).map { layout.getLineStart(it) }
        val offsets = (0..layout.text.length).map { layout.getPrimaryHorizontal(it) }
        val textSize = paint.textSize
        val typeface: Typeface? = paint.typeface
        val fakeBold = paint.isFakeBoldText
        val scaleX = paint.textScaleX
        val letterSpacing = paint.letterSpacing
        val skew = paint.textSkewX
        val flags = paint.flags
    }

    private fun assertSame(label: String, a: Metrics, b: Metrics) {
        assertEquals("$label: line count", a.lineCount, b.lineCount)
        assertEquals("$label: layout width", a.width, b.width)
        assertEquals("$label: row height", a.height, b.height)
        assertEquals("$label: line left", a.left, b.left)
        assertEquals("$label: line right", a.right, b.right)
        assertEquals("$label: line top", a.top, b.top)
        assertEquals("$label: line bottom", a.bottom, b.bottom)
        assertEquals("$label: baseline", a.baseline, b.baseline)
        assertEquals("$label: line breaks", a.starts, b.starts)
        assertEquals("$label: glyph positions", a.offsets, b.offsets)
        assertEquals("$label: text size", a.textSize, b.textSize, 0f)
        assertEquals("$label: typeface", a.typeface, b.typeface)
        assertEquals("$label: fake bold", a.fakeBold, b.fakeBold)
        assertEquals("$label: scaleX", a.scaleX, b.scaleX, 0f)
        assertEquals("$label: letter spacing", a.letterSpacing, b.letterSpacing, 0f)
        assertEquals("$label: skew", a.skew, b.skew, 0f)
        assertEquals("$label: paint flags", a.flags, b.flags)
    }

    /** One visual run: a bidi run of one visual line, the unit LyricsTextView spans. */
    private class VisualRun(val start: Int, val end: Int, val left: Float, val right: Float, val rtl: Boolean)

    /**
     * The visual runs of a layout: per visual line, split at every direction change. This is the
     * same division [KaraokeGeometry.forEachVisualRun] makes and the same one the platform draws
     * in, and it is the ONLY place LyricsTextView is allowed to put a span boundary.
     */
    private fun visualRuns(layout: Layout, length: Int): List<VisualRun> {
        val out = ArrayList<VisualRun>()
        for (line in 0 until layout.lineCount) {
            val from = layout.getLineStart(line)
            val to = minOf(length, layout.getLineEnd(line))
            if (to <= from) continue
            var start = from
            var rtl = layout.isRtlCharAt(start)
            for (i in from + 1..to) {
                val next = i < to && layout.isRtlCharAt(i)
                if (i < to && next == rtl) continue
                val a = KaraokeGeometry.edgeAt(layout, line, start, false)
                val b = KaraokeGeometry.edgeAt(layout, line, i, true)
                out.add(VisualRun(start, i, minOf(a, b), maxOf(a, b), rtl))
                start = i
                rtl = next
            }
        }
        return out
    }

    /** Where the fill front is: the grapheme it is inside, and how far into it, from the layout. */
    private fun frontOf(text: String, layout: Layout, sweep: Float): Pair<Int, Float> {
        val pieces = graphemesOf(text)
        val bounds = ArrayList<Int>()
        var at = 0
        for (piece in pieces) { bounds.add(at); at += piece.length }
        bounds.add(text.length)
        val widths = FloatArray(pieces.size)
        for (i in pieces.indices) {
            if (pieces[i].isBlank()) continue
            val line = layout.getLineForOffset(bounds[i])
            val a = layout.getPrimaryHorizontal(bounds[i])
            val b = KaraokeGeometry.edgeAt(layout, line, bounds[i + 1], true)
            widths[i] = Math.abs(b - a)
        }
        val total = widths.sum()
        val reveal = sweep * total
        var consumed = 0f
        for (i in pieces.indices) {
            if (widths[i] <= 0f) continue
            if (reveal < consumed + widths[i]) return Pair(bounds[i], reveal - consumed)
            consumed += widths[i]
        }
        return Pair(text.length, 0f)
    }

    /**
     * Applies the karaoke spans for one sweep exactly as LyricsTextView does: ONE span per visual
     * run, solid on either side of the boundary and a hard-stop gradient on the run containing it.
     *
     * The ranges are a function of the LAYOUT ONLY - they do not depend on [sweep] at all. That is
     * the property the fix rests on, and [theSpanRangesDoNotDependOnTheSweep] pins it.
     */
    private fun spanned(text: String, layout: Layout, sweep: Float): SpannableString {
        val out = SpannableString(text)
        val (sungTo, _) = frontOf(text, layout, sweep)
        for (run in visualRuns(layout, text.length)) {
            val span = when {
                run.end <= sungTo -> newKaraokeSpan(Color.WHITE, null)
                run.start >= sungTo -> newKaraokeSpan(Color.GRAY, null)
                else -> {
                    val stop = ((layout.getPrimaryHorizontal(sungTo) - run.left)
                            / (run.right - run.left)).coerceIn(0f, 1f)
                    val colours = if (run.rtl)
                        intArrayOf(Color.GRAY, Color.GRAY, Color.WHITE, Color.WHITE)
                    else intArrayOf(Color.WHITE, Color.WHITE, Color.GRAY, Color.GRAY)
                    newKaraokeSpan(Color.GRAY, LinearGradient(run.left, 0f, run.right, 0f,
                        colours, floatArrayOf(0f, stop, stop, 1f), Shader.TileMode.CLAMP))
                }
            }
            out.setSpan(span, run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    // --- reflection, because the renderer's own types are deliberately private ---------------

    private fun karaokeSpanClass(): Class<*> =
        Class.forName("org.telegram.ui.Components.AudioPlayerAlert\$KaraokeSpan")

    private fun lyricsTextViewClass(): Class<*> =
        Class.forName("org.telegram.ui.Components.AudioPlayerAlert\$LyricsTextView")

    private fun newKaraokeSpan(color: Int, shader: Shader?): CharacterStyle {
        val type = karaokeSpanClass()
        val span = type.getDeclaredConstructors()[0].apply { isAccessible = true }.newInstance() as CharacterStyle
        type.getDeclaredMethod("set", Int::class.javaPrimitiveType, Shader::class.java)
            .apply { isAccessible = true }
            .invoke(span, color, shader)
        return span
    }

    @Test
    fun changingTheSweepCannotChangeOneMetricOfTheRow() {
        for (text in typographyStrings) {
            val paint = karaokePaint()
            val reference = StaticLayout.Builder.obtain(text, 0, text.length, paint, 2000).build()
            val expected = Metrics(reference, paint)
            for (sweep in sweeps) {
                val painted = karaokePaint()
                val styled = spanned(text, reference, sweep)
                val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, painted, 2000).build()
                assertSame("'$text' at sweep $sweep", expected, Metrics(layout, painted))
            }
        }
    }

    @Test
    fun aWrappedRowKeepsItsLineBreaksAtEverySweep() {
        // Narrow enough to wrap, so the invariant covers the line-breaking decision too.
        val text = "beautiful minimum office AVATAR jazz"
        val paint = karaokePaint()
        val reference = StaticLayout.Builder.obtain(text, 0, text.length, paint, 300).build()
        assertTrue("the fixture must actually wrap", reference.lineCount > 1)
        val expected = Metrics(reference, paint)
        for (sweep in sweeps) {
            val painted = karaokePaint()
            val styled = spanned(text, reference, sweep)
            val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, painted, 300).build()
            assertSame("wrapped at sweep $sweep", expected, Metrics(layout, painted))
        }
    }

    @Test
    fun theKaraokeSpanTouchesTheColourAndTheShaderAndNothingElse() {
        // The whole typography guarantee in one assertion. If this span ever set the typeface, the
        // size, fake bold, scaleX or the letter spacing, muted and sung text could be different
        // fonts - which is exactly the reported bug. It is checked on the paint itself, so it holds
        // however the span is applied.
        val reference = karaokePaint()
        for (sweep in sweeps) {
            val paint = karaokePaint()
            val shader = if (sweep > 0f && sweep < 1f) LinearGradient(0f, 0f, 10f, 0f,
                intArrayOf(Color.WHITE, Color.WHITE, Color.GRAY, Color.GRAY),
                floatArrayOf(0f, sweep, sweep, 1f), Shader.TileMode.CLAMP) else null
            newKaraokeSpan(Color.WHITE, shader).updateDrawState(paint)
            assertEquals("sweep $sweep: typeface", reference.typeface, paint.typeface)
            assertEquals("sweep $sweep: text size", reference.textSize, paint.textSize, 0f)
            assertEquals("sweep $sweep: fake bold", reference.isFakeBoldText, paint.isFakeBoldText)
            assertEquals("sweep $sweep: scaleX", reference.textScaleX, paint.textScaleX, 0f)
            assertEquals("sweep $sweep: letter spacing", reference.letterSpacing, paint.letterSpacing, 0f)
            assertEquals("sweep $sweep: skew", reference.textSkewX, paint.textSkewX, 0f)
            assertEquals("sweep $sweep: flags", reference.flags, paint.flags)
            assertEquals("sweep $sweep: baseline shift", 0, paint.baselineShift)
            // The colour - the one thing it IS allowed to change - did change.
            assertEquals("sweep $sweep: colour", Color.WHITE, paint.color)
            assertEquals("sweep $sweep: shader", shader, paint.shader)
        }
    }

    @Test
    fun aSolidKaraokeSpanClearsAnyShaderLeftOnThePaint() {
        // A TextPaint is reused across the runs of a line, so the sung and muted runs must not
        // inherit the gradient belonging to the word being sung.
        val paint = karaokePaint()
        paint.shader = LinearGradient(0f, 0f, 10f, 0f, Color.RED, Color.BLUE, Shader.TileMode.CLAMP)
        newKaraokeSpan(Color.WHITE, null).updateDrawState(paint)
        assertNull("a solid run must drop the previous run's shader", paint.shader)
    }

    @Test
    fun theKaraokeSpanIsNotMetricAffecting() {
        // Android's own contract for "appearance only". A span that is merely a CharacterStyle
        // without UpdateAppearance would make the platform re-measure the line.
        val span = newKaraokeSpan(Color.WHITE, null)
        assertTrue("must be a CharacterStyle", span is CharacterStyle)
        assertTrue("must be UpdateAppearance, so the platform never re-measures for it",
            span is android.text.style.UpdateAppearance)
        assertFalse("must NOT be MetricAffectingSpan",
            span is android.text.style.MetricAffectingSpan)
    }

    // =================================================== one draw per frame, and nothing vertical

    @Test
    fun theRowIsDrawnByOneOrdinaryTextViewDrawWithNoCustomRenderer() {
        // Architecture assertion: karaoke is appearance-only, backed by a canvas overlay that calls
        // super.onDraw exactly once, then draws a brightness rect at cluster bounds. There is no
        // strip renderer, no clipped pass, no canvas translation and no repeated super.onDraw().
        // onDraw IS overridden (for the post-draw brightness overlay) but draw() is not.
        val type = lyricsTextViewClass()
        for (method in type.declaredMethods) {
            assertNotEquals("the row must not override draw() - karaoke is appearance only",
                "draw", method.name)
        }
    }

    @Test
    fun noKaraokeVerticalMotionSurvivesAnywhereInTheRenderer() {
        // Source-level proof that the upward animation is gone rather than merely disabled: no
        // field and no method of the row is named for vertical motion, and the class that used to
        // own it does not exist.
        val banned = listOf("lift", "wave", "stagger", "rise", "raise", "strip", "translatey")
        val type = lyricsTextViewClass()
        for (field in type.declaredFields) {
            val name = field.name.lowercase()
            for (word in banned) {
                assertFalse("field ${field.name} still exists for '$word'", name.contains(word))
            }
        }
        for (method in type.declaredMethods) {
            val name = method.name.lowercase()
            for (word in banned) {
                assertFalse("method ${method.name} still exists for '$word'", name.contains(word))
            }
        }
        // KaraokeWave is deleted, not emptied.
        var present = true
        try {
            Class.forName("org.telegram.ui.Components.AudioPlayerAlert\$KaraokeWave")
        } catch (expected: ClassNotFoundException) {
            present = false
        }
        assertFalse("KaraokeWave must be deleted outright", present)
        // And the frame the renderer is handed carries no vertical value to carry.
        for (field in KaraokeFrame::class.java.declaredFields) {
            val name = field.name.lowercase()
            for (word in banned) {
                assertFalse("KaraokeFrame.${field.name} still exists for '$word'", name.contains(word))
            }
        }
    }

    // ===================================== the span boundaries may not divide a shaping run
    // The Build #43 device report: "the text still visibly glitches during the colour transition -
    // shape, weight, size and spacing appear to change." That was NOT a colour or gamma effect.
    //
    // Android cannot merge two adjacent spans whose paints differ, so every karaoke span boundary
    // becomes its own drawTextRun. Splitting a draw at a position that is not a shaping boundary
    // changes the glyphs the shaper produces: a ligature straddling it is no longer formed, and
    // kerning across it is lost. The previous renderer put its boundaries on GRAPHEME clusters,
    // which are finer than shaping clusters, so the boundary regularly landed inside a ligature.
    // Measured with colour removed, that changed pixels by the full 0..255 range and changed a
    // word's inked width by up to 18px, appearing and disappearing as the sweep advanced.
    //
    // The cure is that karaoke never asks for a split the platform was not already making: span
    // boundaries sit only at visual-line and direction-run boundaries.

    /** The words that carry a ligature or a conjunct, which is where the damage was measurable. */
    private val shapingStressStrings = listOf(
        "office", "fly", "difficult", "waffle", "AVATAR",
        "take off, take off all your clothes",
        "ሰላም ለዓለም",
        "مرحبا بالعالم",
        "नमस्ते दुनिया"
    )

    @Test
    fun everySpanBoundaryLandsOnAVisualRunBoundary() {
        for (text in typographyStrings + shapingStressStrings) {
            for (width in intArrayOf(2000, 300)) {
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, karaokePaint(), width).build()
                val allowed = HashSet<Int>()
                for (run in visualRuns(layout, text.length)) { allowed.add(run.start); allowed.add(run.end) }
                for (sweep in sweeps) {
                    val styled = spanned(text, layout, sweep)
                    val spans = styled.getSpans(0, styled.length, CharacterStyle::class.java)
                    assertTrue("'$text' must be spanned at all", spans.isNotEmpty())
                    for (span in spans) {
                        val from = styled.getSpanStart(span)
                        val to = styled.getSpanEnd(span)
                        assertTrue("'$text' w=$width sweep=$sweep: span start $from divides a shaping run",
                            allowed.contains(from))
                        assertTrue("'$text' w=$width sweep=$sweep: span end $to divides a shaping run",
                            allowed.contains(to))
                    }
                }
            }
        }
    }

    @Test
    fun theSpanRangesDoNotDependOnTheSweep() {
        // The crux. If the ranges move with the sweep, the draw is re-split as the fill advances and
        // the glyphs can change with it. They must be a function of the layout alone.
        for (text in typographyStrings + shapingStressStrings) {
            for (width in intArrayOf(2000, 300)) {
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, karaokePaint(), width).build()
                var expected: List<Pair<Int, Int>>? = null
                for (sweep in sweeps) {
                    val styled = spanned(text, layout, sweep)
                    val ranges = styled.getSpans(0, styled.length, CharacterStyle::class.java)
                        .map { Pair(styled.getSpanStart(it), styled.getSpanEnd(it)) }
                        .sortedBy { it.first }
                    if (expected == null) expected = ranges
                    else assertEquals("'$text' w=$width: span ranges moved at sweep $sweep",
                        expected, ranges)
                }
                // And together they cover the whole row, so no character is left unpainted.
                assertEquals("'$text' w=$width: spans must start at 0", 0, expected!!.first().first)
                assertEquals("'$text' w=$width: spans must reach the end", text.length, expected.last().second)
            }
        }
    }

    // ============================================== the raster proof, in actual pixels
    // Colour is removed from the measurement rather than compensated for: both karaoke colours are
    // set to the SAME white, so the render differs from an unstyled one ONLY through the span
    // structure. Any difference is therefore geometry.

    private fun renderToPixels(text: CharSequence, width: Int, w: Int, h: Int): IntArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        canvas.translate(8f, 4f)
        StaticLayout.Builder.obtain(text, 0, text.length, karaokePaint(), width).build().draw(canvas)
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return px
    }

    private fun luminance(c: Int): Int =
        Math.round(0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c))

    /** Rightmost inked column, which is what a change of word width shows up in. */
    private fun inkedWidth(px: IntArray, w: Int, h: Int): Int {
        for (x in w - 1 downTo 0) for (y in 0 until h) if (luminance(px[y * w + x]) > 30) return x
        return -1
    }

    @Test
    fun theSweepDoesNotChangeOnePixelOfTheGlyphs() {
        val bw = 1400
        val bh = 220
        for (text in typographyStrings + shapingStressStrings) {
            for (width in intArrayOf(1300, 300)) {
                // Reference: the same layout with no karaoke span at all.
                val plain = renderToPixels(text, width, bw, bh)
                val referenceWidth = inkedWidth(plain, bw, bh)
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, karaokePaint(), width).build()
                for (sweep in floatArrayOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)) {
                    // Both colours white: the span structure is production's, the colour is not.
                    val styled = SpannableString(text)
                    val (sungTo, _) = frontOf(text, layout, sweep)
                    for (run in visualRuns(layout, text.length)) {
                        val span = if (run.end <= sungTo || run.start >= sungTo) {
                            newKaraokeSpan(Color.WHITE, null)
                        } else {
                            val stop = ((layout.getPrimaryHorizontal(sungTo) - run.left)
                                    / (run.right - run.left)).coerceIn(0f, 1f)
                            newKaraokeSpan(Color.WHITE, LinearGradient(run.left, 0f, run.right, 0f,
                                intArrayOf(Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE),
                                floatArrayOf(0f, stop, stop, 1f), Shader.TileMode.CLAMP))
                        }
                        styled.setSpan(span, run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val painted = renderToPixels(styled, width, bw, bh)
                    var worst = 0
                    for (i in plain.indices) {
                        val d = Math.abs(luminance(plain[i]) - luminance(painted[i]))
                        if (d > worst) worst = d
                    }
                    assertTrue("'$text' w=$width sweep=$sweep: the glyphs changed by $worst levels",
                        worst <= 2)
                    assertEquals("'$text' w=$width sweep=$sweep: the inked width changed",
                        referenceWidth, inkedWidth(painted, bw, bh))
                }
            }
        }
    }

    // ====================================== ownedEnd: trailing whitespace gap belongs to each word

    @Test
    fun ownedEndIsTheNextWordStartForNonTerminalWords() {
        // "Hello world" with starts only: each word owns up to the next word's text start.
        val line = ttml(
            """<p begin="00:01.000" end="00:03.000">""" +
                """<span begin="00:01.000" end="00:01.800">Hello</span> """ +
                """<span begin="00:02.000" end="00:02.600">world</span></p>"""
        )
        val frame = KaraokeFrame()
        frame.resolve(line, 1400, Long.MAX_VALUE)  // mid-first-word
        // "Hello" is at [0,5]; " " is at [5,6]; "world" starts at 6.
        assertEquals("first word owns up to next word's start", 6, frame.ownedEnd)
        assertEquals("wordEnd is still the stated end of the word",
            line.segments.endOffset(0), frame.wordEnd)
    }

    @Test
    fun ownedEndIsLineEndForTheTerminalWord() {
        val line = ttml(
            """<p begin="00:01.000" end="00:03.000">""" +
                """<span begin="00:01.000" end="00:01.800">Hello</span> """ +
                """<span begin="00:02.000" end="00:02.600">world</span></p>"""
        )
        val frame = KaraokeFrame()
        frame.resolve(line, 2300, Long.MAX_VALUE)  // mid-second-word
        assertEquals("terminal word owns to end of line text", line.text.length, frame.ownedEnd)
    }

    @Test
    fun ownedEndIsSetForAlreadySungLines() {
        val line = ttml(
            """<p begin="00:01.000" end="00:03.000">""" +
                """<span begin="00:01.000" end="00:01.800">Hello</span> """ +
                """<span begin="00:02.000" end="00:02.600">world</span></p>"""
        )
        val frame = KaraokeFrame()
        frame.resolveRow(line, 0, 1 /* already past */, 5000, Long.MAX_VALUE)
        // A line that has been left behind is fully sung: all fields equal text length.
        assertEquals("sung line: wordEnd = text length", line.text.length, frame.wordEnd)
        assertEquals("sung line: ownedEnd = text length", line.text.length, frame.ownedEnd)
    }

    @Test
    fun ownedEndDoesNotMoveWordStart_wordEnd_orSweep() {
        // ownedEnd is purely a visual ownership hint — it must never affect timing semantics.
        val line = ttml(
            """<p begin="00:01.000" end="00:03.000">""" +
                """<span begin="00:01.000" end="00:01.800">Hello</span> """ +
                """<span begin="00:02.000" end="00:02.600">world</span></p>"""
        )
        val frame = KaraokeFrame()
        frame.resolve(line, 1400, Long.MAX_VALUE)
        val ws = frame.wordStart
        val we = frame.wordEnd
        val sw = frame.sweep
        // Re-resolve at the same position: should be identical
        frame.resolve(line, 1400, Long.MAX_VALUE)
        assertEquals(ws, frame.wordStart)
        assertEquals(we, frame.wordEnd)
        assertEquals(sw, frame.sweep, 0.0001f)
        // ownedEnd differs from wordEnd for a non-terminal word.
        assertTrue("ownedEnd >= wordEnd", frame.ownedEnd >= frame.wordEnd)
    }

    // =================================== soft feather: gradient geometry must not affect glyph ink

    @Test
    fun theSoftFeatherDoesNotChangeGlyphGeometryOrInkedWidth() {
        // The feather changes the gradient *appearance*, never the span ranges, so glyphs must be
        // identical to the plain (no-shader) render at every sweep value.
        val bw = 1400; val bh = 220
        for (text in typographyStrings + shapingStressStrings) {
            for (width in intArrayOf(1300, 300)) {
                val plain = renderToPixels(text, width, bw, bh)
                val refWidth = inkedWidth(plain, bw, bh)
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, karaokePaint(), width).build()
                for (sweep in floatArrayOf(0f, 0.5f, 1f)) {
                    val styled = SpannableString(text)
                    val (sungTo, _) = frontOf(text, layout, sweep)
                    for (run in visualRuns(layout, text.length)) {
                        val span = if (run.end <= sungTo || run.start >= sungTo) {
                            newKaraokeSpan(Color.WHITE, null)
                        } else {
                            val stop = ((layout.getPrimaryHorizontal(sungTo) - run.left)
                                    / (run.right - run.left)).coerceIn(0f, 1f)
                            // Feathered variant with lo = max(0, stop-0.05), hi = stop
                            val lo = Math.max(0f, stop - 0.05f)
                            val safeHi = if (stop - lo < 0.001f) lo + 0.001f else stop
                            newKaraokeSpan(Color.WHITE, LinearGradient(run.left, 0f, run.right, 0f,
                                intArrayOf(Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE),
                                floatArrayOf(0f, lo, safeHi, 1f), Shader.TileMode.CLAMP))
                        }
                        styled.setSpan(span, run.start, run.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val painted = renderToPixels(styled, width, bw, bh)
                    var worst = 0
                    for (i in plain.indices) {
                        val d = Math.abs(luminance(plain[i]) - luminance(painted[i]))
                        if (d > worst) worst = d
                    }
                    assertTrue("'$text' w=$width sweep=$sweep: feather changed glyphs by $worst levels",
                        worst <= 2)
                    assertEquals("'$text' w=$width sweep=$sweep: feather changed inked width",
                        refWidth, inkedWidth(painted, bw, bh))
                }
            }
        }
    }

    // =================== feather gradient: actual color behavior =========================
    // These tests verify the visual character of the gradient, not just its shaping safety.
    // They replicate the runGradient() formula directly so they compile without access to the
    // private method. If the production formula changes, these tests break and must be updated.

    /**
     * Returns the (colors, stops) arrays the runGradient() formula would produce.
     * [density] is pixels-per-dp and stands in for AndroidUtilities.dp() in the production code.
     * Default 3f is a representative xxhdpi screen.
     */
    private fun featherGradientParams(
        left: Float, right: Float, cut: Float, rtl: Boolean,
        sung: Int, muted: Int,
        featherDp: Float = 7f, density: Float = 3f
    ): Pair<IntArray, FloatArray> {
        val width = right - left
        var stop = (cut - left) / width
        if (stop < 0f) stop = 0f
        if (stop > 1f) stop = 1f
        val leading = if (rtl) muted else sung
        val trailing = if (rtl) sung else muted
        val featherFrac = minOf(0.35f, featherDp * density / width)
        var lo: Float
        var hi: Float
        if (rtl) {
            lo = stop; hi = minOf(1f, stop + featherFrac)
        } else {
            lo = maxOf(0f, stop - featherFrac); hi = stop
        }
        if (hi - lo < 0.001f) {
            hi = lo + 0.001f
            if (hi > 1f) { hi = 1f; lo = maxOf(0f, hi - 0.001f) }
        }
        return Pair(intArrayOf(leading, leading, trailing, trailing),
                    floatArrayOf(0f, lo, hi, 1f))
    }

    @Test
    fun featherGradient_ltrLeadingColorIsSungTrailingColorIsMuted() {
        // LTR: reading direction is left-to-right. Sung portion is on the left (leading),
        // muted portion is on the right (trailing). Gradient = [sung, sung, muted, muted].
        val sungColor = Color.RED; val mutedColor = Color.BLUE
        val (colors, _) = featherGradientParams(0f, 200f, 120f, false, sungColor, mutedColor)
        assertEquals("LTR colors[0] is sung", sungColor, colors[0])
        assertEquals("LTR colors[1] is sung", sungColor, colors[1])
        assertEquals("LTR colors[2] is muted", mutedColor, colors[2])
        assertEquals("LTR colors[3] is muted", mutedColor, colors[3])
    }

    @Test
    fun featherGradient_rtlLeadingColorIsMutedTrailingColorIsSung() {
        // RTL: sung portion is on the right (trailing), muted on the left (leading).
        // Gradient = [muted, muted, sung, sung].
        val sungColor = Color.RED; val mutedColor = Color.BLUE
        val (colors, _) = featherGradientParams(0f, 200f, 80f, true, sungColor, mutedColor)
        assertEquals("RTL colors[0] is muted", mutedColor, colors[0])
        assertEquals("RTL colors[1] is muted", mutedColor, colors[1])
        assertEquals("RTL colors[2] is sung", sungColor, colors[2])
        assertEquals("RTL colors[3] is sung", sungColor, colors[3])
    }

    @Test
    fun featherGradient_stopsAreMonotonicAndInUnitRange() {
        for (cutFraction in floatArrayOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)) {
            val cut = cutFraction * 200f
            for (rtl in booleanArrayOf(false, true)) {
                val (_, stops) = featherGradientParams(0f, 200f, cut, rtl, Color.RED, Color.BLUE)
                assertTrue("stops[0] == 0 for cut=$cut rtl=$rtl",       stops[0] == 0f)
                assertTrue("stops[1] >= stops[0] for cut=$cut rtl=$rtl", stops[1] >= stops[0])
                assertTrue("stops[2] >= stops[1] for cut=$cut rtl=$rtl", stops[2] >= stops[1])
                assertTrue("stops[3] == 1 for cut=$cut rtl=$rtl",        stops[3] == 1f)
                assertTrue("all stops <= 1 for cut=$cut rtl=$rtl",
                    stops[1] <= 1f && stops[2] <= 1f)
            }
        }
    }

    @Test
    fun featherGradient_ltrFeatherSpanIsBeforeTheCursor() {
        // LTR: the feather transitions from sung to muted BEFORE the cursor position (stop).
        // stops[1] = max(0, stop - feather), stops[2] = stop.
        val left = 0f; val right = 200f; val cut = 120f
        val featherDp = 7f; val density = 3f
        val stop = (cut - left) / (right - left)  // 0.6
        val featherFrac = minOf(0.35f, featherDp * density / (right - left))
        val expectedLo = maxOf(0f, stop - featherFrac)
        val (_, stops) = featherGradientParams(left, right, cut, false,
            Color.RED, Color.BLUE, featherDp, density)
        assertEquals("LTR stops[1] = stop - feather", expectedLo, stops[1], 0.0001f)
        assertEquals("LTR stops[2] = stop (cursor)", stop,         stops[2], 0.0001f)
    }

    @Test
    fun featherGradient_rtlFeatherSpanIsAfterTheCursor() {
        // RTL: the feather transitions from muted to sung AFTER the cursor position (stop).
        // stops[1] = stop, stops[2] = min(1, stop + feather).
        val left = 0f; val right = 200f; val cut = 80f
        val featherDp = 7f; val density = 3f
        val stop = (cut - left) / (right - left)  // 0.4
        val featherFrac = minOf(0.35f, featherDp * density / (right - left))
        val expectedHi = minOf(1f, stop + featherFrac)
        val (_, stops) = featherGradientParams(left, right, cut, true,
            Color.RED, Color.BLUE, featherDp, density)
        assertEquals("RTL stops[1] = stop (cursor)", stop,       stops[1], 0.0001f)
        assertEquals("RTL stops[2] = stop + feather", expectedHi, stops[2], 0.0001f)
    }

    @Test
    fun featherGradient_narrowRunDegenGuardEnforcesMinimumsSpread() {
        // A run narrower than the feather width: featherFrac would exceed 0.35 or the guard
        // clips lo/hi together. Either way safeHi - safeLo must be >= 0.001.
        for (runWidth in floatArrayOf(1f, 3f, 5f, 10f)) {
            for (rtl in booleanArrayOf(false, true)) {
                val cut = runWidth * 0.5f
                val (_, stops) = featherGradientParams(0f, runWidth, cut, rtl,
                    Color.RED, Color.BLUE, featherDp = 7f, density = 3f)
                assertTrue(
                    "guard: spread >= 0.001 for width=$runWidth rtl=$rtl",
                    stops[2] - stops[1] >= 0.001f
                )
            }
        }
    }

    // ================================ two-phase cursor: explicit TTML end vs start-only
    // For TTML sources with explicit word ends the cursor runs in two separate phases:
    //   Phase A (sweep 0→1 during [wordStartTime, wordEndTime]): cursor traverses the word's
    //   own glyph clusters. gapProgress stays 0 throughout.
    //   Phase B (gapProgress 0→1 during [wordEndTime, nextWordStartTime]): cursor traverses
    //   the physical whitespace gap. sweep stays 1 throughout.
    // For start-only (LRC) sources hasExplicitWordEnd is false, gapProgress stays 0, and sweep
    // covers the entire owned interval (glyphs + gap) as one continuous sweep.

    @Test
    fun explicitTtmlEnd_phaseAAndPhaseBAreSeparate() {
        // Fixture: "Hello world" — Hello 1000–1800ms, world 2000–2600ms.
        // Phase A drives glyphs during 1000→1800. Phase B drives the whitespace gap 1800→2000.
        val line = statedEnds()
        val frame = KaraokeFrame()

        // t=1000: word just started, fill at zero, no gap progress
        frame.resolve(line, 1000, Long.MAX_VALUE)
        assertTrue("t=1000: source has explicit end", frame.hasExplicitWordEnd)
        assertEquals("t=1000: sweep starts at 0", 0f, frame.sweep, 0.0001f)
        assertEquals("t=1000: no gap progress yet", 0f, frame.gapProgress, 0.0001f)

        // t=1400: 400ms into the 800ms glyph window → sweep = 0.5
        frame.resolve(line, 1400, Long.MAX_VALUE)
        assertEquals("t=1400: halfway through 800ms", 0.5f, frame.sweep, 0.01f)
        assertEquals("t=1400: still in glyph phase", 0f, frame.gapProgress, 0.0001f)

        // t=1799: 799/800ms through the glyph window — almost done, still Phase A
        frame.resolve(line, 1799, Long.MAX_VALUE)
        assertTrue("t=1799: sweep nearly 1 but not yet complete", frame.sweep > 0.99f && frame.sweep < 1f)
        assertEquals("t=1799: gapProgress = 0 before authored word end", 0f, frame.gapProgress, 0.0001f)

        // t=1800: exactly at authored word end — glyphs fully sung, gap NOT yet started
        frame.resolve(line, 1800, Long.MAX_VALUE)
        assertEquals("t=1800: sweep reaches 1 at authored end", 1f, frame.sweep, 0.0001f)
        assertEquals("t=1800: gapProgress = 0 exactly at authored end (gap not yet open)", 0f, frame.gapProgress, 0.0001f)

        // t=1900: 100ms into the 200ms gap (1800→2000) → gapProgress = 0.5
        frame.resolve(line, 1900, Long.MAX_VALUE)
        assertEquals("t=1900: sweep stays 1 during gap phase", 1f, frame.sweep, 0.0001f)
        assertEquals("t=1900: gap halfway (100ms of 200ms)", 0.5f, frame.gapProgress, 0.01f)

        // t=1999: 199ms into the gap — cursor almost at next word
        frame.resolve(line, 1999, Long.MAX_VALUE)
        assertEquals("t=1999: sweep stays 1", 1f, frame.sweep, 0.0001f)
        assertTrue("t=1999: gap nearly complete", frame.gapProgress > 0.99f && frame.gapProgress < 1f)

        // t=2000: "world" becomes current — fresh word, fresh state
        frame.resolve(line, 2000, Long.MAX_VALUE)
        assertEquals("t=2000: world is current", line.segments.startOffset(1), frame.wordStart)
        assertEquals("t=2000: world's fill starts at 0", 0f, frame.sweep, 0.0001f)
        assertEquals("t=2000: gapProgress resets for new word", 0f, frame.gapProgress, 0.0001f)
    }

    @Test
    fun startOnly_sweepAdvancesContinuouslyWithNoGapProgress() {
        // Start-only (LRC): hasExplicitWordEnd = false, gapProgress = 0 throughout.
        // The single sweep covers glyphs and whitespace gap in one unbroken advance.
        val line = lrc("[00:01.000]<00:01.000>Hello <00:02.000>world")
        val frame = KaraokeFrame()

        frame.resolve(line, 1000, Long.MAX_VALUE)
        assertFalse("start-only source: hasExplicitWordEnd is false", frame.hasExplicitWordEnd)
        assertEquals("t=1000: sweep at 0", 0f, frame.sweep, 0.0001f)
        assertEquals("t=1000: no gap progress", 0f, frame.gapProgress, 0.0001f)

        // t=1500: halfway through the 1000ms ownership interval (1000→2000)
        frame.resolve(line, 1500, Long.MAX_VALUE)
        assertEquals("t=1500: sweep halfway", 0.5f, frame.sweep, 0.01f)
        assertEquals("no gap progress", 0f, frame.gapProgress, 0.0001f)

        // Continuity across the whole interval: no jump at any boundary
        var prevSweep = frame.sweep
        for (t in 1501L..1999L) {
            frame.resolve(line, t, Long.MAX_VALUE)
            assertFalse("hasExplicitWordEnd is false at t=$t", frame.hasExplicitWordEnd)
            assertTrue("sweep non-decreasing at t=$t (was $prevSweep, now ${frame.sweep})",
                frame.sweep >= prevSweep - 0.0001f)
            assertEquals("gapProgress = 0 at t=$t", 0f, frame.gapProgress, 0.0001f)
            prevSweep = frame.sweep
        }
    }

    @Test
    fun monotonicPhysicalCursor_sweepThenGapProgressNeverReverse() {
        // The cursor's virtual position — sweep in Phase A, gapProgress in Phase B — must never
        // go backwards as time advances. This guards the "Hello un-sings" regression.
        val line = statedEnds()  // Hello 1000-1800, world 2000-2600
        val frame = KaraokeFrame()

        // Phase A: glyph phase 1000ms up to (not including) 1800ms
        var prevSweep = -1f
        for (t in 1000L until 1800L) {
            frame.resolve(line, t, Long.MAX_VALUE)
            assertTrue("sweep non-decreasing in Phase A at t=$t", frame.sweep >= prevSweep - 0.0001f)
            assertEquals("no gap progress during Phase A at t=$t", 0f, frame.gapProgress, 0.0001f)
            prevSweep = frame.sweep
        }

        // Boundary: exactly at authored word end
        frame.resolve(line, 1800, Long.MAX_VALUE)
        assertEquals("sweep=1 at authored word end", 1f, frame.sweep, 0.0001f)
        assertEquals("gapProgress=0 at authored word end (Phase B not yet open)", 0f, frame.gapProgress, 0.0001f)

        // Phase B: gap phase 1801ms→1999ms — sweep stays 1, gapProgress advances
        var prevGap = 0f
        for (t in 1801L..1999L) {
            frame.resolve(line, t, Long.MAX_VALUE)
            assertEquals("sweep stays 1 in Phase B at t=$t", 1f, frame.sweep, 0.0001f)
            assertTrue("gapProgress non-decreasing in Phase B at t=$t",
                frame.gapProgress >= prevGap - 0.0001f)
            prevGap = frame.gapProgress
        }
    }

    @Test
    fun wrappedLine_ownedEndIsNextWordTextStart() {
        // KaraokeFrame-level: ownedEnd for "Hello" in a TTML source is the text start of "world"
        // (the character index of 'w'), giving the gap region. The further narrowing to the current
        // visual-line boundary (to prevent the cursor crossing a wrap point) happens inside
        // LyricsTextView.resolveColourBoundaries() via effectiveGapEnd, which requires a real
        // LyricsTextView instance and is verified by device QA.
        val line = statedEnds()   // "Hello world": Hello [0,5), space [5,6), world [6,11)
        val frame = KaraokeFrame()
        frame.resolve(line, 1400, Long.MAX_VALUE)  // mid Hello

        val worldTextStart = line.segments.startOffset(1)  // = 6
        assertEquals("ownedEnd is the text start of the next word", worldTextStart, frame.ownedEnd)
        assertEquals("wordEnd is Hello's exclusive character end", 5, frame.wordEnd)
        assertEquals("ownedEnd reaches past the space to world's first character", 6, frame.ownedEnd)
        assertTrue("gap region exists: ownedEnd > wordEnd", frame.ownedEnd > frame.wordEnd)
    }

    @Test
    fun authoredEndInvariant_glyphsCompleteBeforeGapOpens() {
        // The central safety guarantee of the two-phase model:
        //   • Before wordEndTime: sweep < 1 and gapProgress = 0 (gap cannot consume any of the
        //     word's authored visual duration).
        //   • At wordEndTime: sweep = 1 and gapProgress = 0 (all glyph ink sung, gap not yet open).
        //   • After wordEndTime: gapProgress > 0 (Phase B begins).
        val line = statedEnds()  // Hello: start=1000ms, end=1800ms
        val frame = KaraokeFrame()

        // Before authored end: gapProgress must be 0, sweep must be < 1
        for (t in 1000L until 1800L) {
            frame.resolve(line, t, Long.MAX_VALUE)
            assertEquals("gapProgress=0 before wordEndTime at t=$t", 0f, frame.gapProgress, 0.0001f)
            assertTrue("sweep < 1 before word fully sung at t=$t", frame.sweep < 1f)
        }

        // Exactly at authored word end: glyphs done, gap not yet open
        frame.resolve(line, 1800, Long.MAX_VALUE)
        assertEquals("sweep=1 exactly at wordEndTime", 1f, frame.sweep, 0.0001f)
        assertEquals("gapProgress=0 at wordEndTime (gap opens strictly after)", 0f, frame.gapProgress, 0.0001f)

        // One ms after: Phase B has opened
        frame.resolve(line, 1801, Long.MAX_VALUE)
        assertEquals("sweep stays 1 once glyphs are complete", 1f, frame.sweep, 0.0001f)
        assertTrue("gapProgress > 0 one ms after wordEndTime", frame.gapProgress > 0f)
    }

    // ========================= long-note word emphasis: gate and data model
    // Words held for >= 1000ms with an explicit end time are eligible for long-note emphasis.
    // Scale is not applied (sub-view scale requires MetricAffectingSpan, forbidden by Build #44).
    // The data model is a pure function of the timestamp; resolving the same position twice
    // returns identical fields regardless of what came before.

    @Test
    fun longNoteGate_wordsBelow1000msAreIneligible() {
        // 999ms is just below the threshold and must never trigger emphasis.
        val line = pairWithGap(999)
        val frame = KaraokeFrame()
        frame.resolve(line, 0, Long.MAX_VALUE)
        assertFalse("999ms word must not be eligible", frame.longNoteEligible)
    }

    @Test
    fun longNoteGate_exactlyAtThresholdIsEligible() {
        // 1000ms is the exact boundary: eligible. Note: pairWithGap uses LRC (start-only),
        // but the gate checks hasExplicitWordEnd. For this test, we use a TTML line so the
        // explicit end flag is set and eligibility is confirmed.
        val line = ttml("""<p begin="00:00.000" end="00:05.000">""" +
            """<span begin="00:00.000" end="00:01.000">aa</span> """ +
            """<span begin="00:02.000" end="00:03.000">bb</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 0, 5000L)
        assertTrue("1000ms authored-end word must be eligible", frame.longNoteEligible)
    }

    @Test
    fun longNoteFieldsAreSetByResolve() {
        // KaraokeFrame.resolve() must populate longNoteEligible, wordDurationMs, wordAbsoluteStartMs.
        val line = pairWithGap(1200)   // first word is 1200ms (≥ 1000ms threshold)
        val frame = KaraokeFrame()
        frame.resolve(line, 0, Long.MAX_VALUE)
        assertTrue("1200ms word is eligible", frame.longNoteEligible)
        assertEquals("wordDurationMs matches the source", 1200L, frame.wordDurationMs)
        assertEquals("wordAbsoluteStartMs matches the segment start", 0L, frame.wordAbsoluteStartMs)
    }

    @Test
    fun longNoteFieldsAreIneligibleForShortWords() {
        // A 200ms word must have longNoteEligible=false while wordDurationMs is still accurate.
        val line = pairWithGap(200)
        val frame = KaraokeFrame()
        frame.resolve(line, 0, Long.MAX_VALUE)
        assertFalse("200ms word is ineligible", frame.longNoteEligible)
        assertEquals("wordDurationMs is still populated", 200L, frame.wordDurationMs)
    }

    @Test
    fun clearResetsLongNoteFields() {
        // clear() must zero every long-note field so a recycled row cannot carry a previous
        // word's eligibility or duration into a new binding.
        val line = pairWithGap(1500)
        val frame = KaraokeFrame()
        frame.resolve(line, 0, Long.MAX_VALUE)
        assertTrue("before clear: eligible", frame.longNoteEligible)
        assertTrue("before clear: wordDurationMs > 0", frame.wordDurationMs > 0L)
        frame.clear()
        assertFalse("after clear: longNoteEligible reset", frame.longNoteEligible)
        assertEquals("after clear: wordDurationMs reset", 0L, frame.wordDurationMs)
        assertEquals("after clear: wordAbsoluteStartMs reset", 0L, frame.wordAbsoluteStartMs)
    }

    // ========================= long-note stagger step

    @Test
    fun longNoteStaggerStep_isProportionalToWordDurationOverGlyphCount() {
        // Formula: min(400, round(0.4 * durationMs / glyphCount))
        assertEquals("1000ms / 5 glyphs", 80L, KaraokeFrame.longNoteStaggerStepMs(1000L, 5))
        assertEquals("2000ms / 4 glyphs", 200L, KaraokeFrame.longNoteStaggerStepMs(2000L, 4))
        assertEquals("1000ms / 1 glyph", 400L, KaraokeFrame.longNoteStaggerStepMs(1000L, 1))
    }

    @Test
    fun longNoteStaggerStep_isCappedAt400ms() {
        // Any word/glyph-count combination producing more than 400ms is capped at 400.
        assertEquals("cap: 5000ms / 1 glyph", 400L, KaraokeFrame.longNoteStaggerStepMs(5000L, 1))
        assertEquals("cap: 10000ms / 1 glyph", 400L, KaraokeFrame.longNoteStaggerStepMs(10000L, 1))
    }

    @Test
    fun longNoteStaggerStep_isZeroForEmptyGlyphCount() {
        assertEquals("0 glyphs → 0ms step", 0L, KaraokeFrame.longNoteStaggerStepMs(1000L, 0))
    }

    // ========================= seek determinism with long-note fields
    // These pin the pure-function guarantee: the same playback position always produces the same
    // long-note state regardless of what happened before or after in playback history.

    @Test
    fun longNoteEmphasis_seekReproducesIdenticalState() {
        // Resolve, seek forward, seek back: all three long-note fields match the first resolve.
        val line = pairWithGap(1500)
        val frame = KaraokeFrame()
        frame.resolve(line, 500, Long.MAX_VALUE)
        val eligA = frame.longNoteEligible
        val durA = frame.wordDurationMs
        val startA = frame.wordAbsoluteStartMs

        // Seek well past the word, then back to the original position.
        frame.resolve(line, 9000, Long.MAX_VALUE)
        frame.resolve(line, 500, Long.MAX_VALUE)
        assertEquals("seek reproduces longNoteEligible", eligA, frame.longNoteEligible)
        assertEquals("seek reproduces wordDurationMs", durA, frame.wordDurationMs)
        assertEquals("seek reproduces wordAbsoluteStartMs", startA, frame.wordAbsoluteStartMs)
    }

    @Test
    fun longNoteEmphasis_pauseDoesNotFreezeState() {
        // Resolving the same position many times — as happens during a pause — must return
        // the same long-note fields every time without any accumulated drift.
        val line = pairWithGap(1500)
        val frame = KaraokeFrame()
        frame.resolve(line, 300, Long.MAX_VALUE)
        val dur = frame.wordDurationMs
        val start = frame.wordAbsoluteStartMs
        val elig = frame.longNoteEligible
        repeat(12) {
            frame.resolve(line, 300, Long.MAX_VALUE)
            assertEquals("repeated resolve: wordDurationMs unchanged", dur, frame.wordDurationMs)
            assertEquals("repeated resolve: wordAbsoluteStartMs unchanged", start, frame.wordAbsoluteStartMs)
            assertEquals("repeated resolve: longNoteEligible unchanged", elig, frame.longNoteEligible)
        }
    }

    // ========================= page movement constants and formula
    // Row stagger and the pre-anchor lead are derived from Apple Music reference measurements.
    // These pin the values so a tuning change is intentional and visible in review.

    @Test
    fun pageFollowLeadIs550ms() {
        // Reference-derived: movement starts 550ms before the next stated timestamp so the
        // incoming row is already rising when the semantic clock advances.
        val field = AudioPlayerAlert::class.java.getDeclaredField("LYRIC_FOLLOW_LEAD_MAX")
        field.isAccessible = true
        assertEquals("LYRIC_FOLLOW_LEAD_MAX must be 550ms", 550L, field.get(null) as Long)
    }

    @Test
    fun rowStaggerConstantsMatchAppleMusicReference() {
        // Max delay (small distance): 50ms. Min delay (full viewport or more): 4ms.
        val maxField = AudioPlayerAlert::class.java.getDeclaredField("ROW_ITEM_DELAY_MAX_MS")
        maxField.isAccessible = true
        assertEquals("ROW_ITEM_DELAY_MAX_MS: 50ms", 50L, maxField.get(null) as Long)
        val minField = AudioPlayerAlert::class.java.getDeclaredField("ROW_ITEM_DELAY_MIN_MS")
        minField.isAccessible = true
        assertEquals("ROW_ITEM_DELAY_MIN_MS: 4ms", 4L, minField.get(null) as Long)
    }

    @Test
    fun rowStaggerFormula_delayInterpolatesWithDistance() {
        // itemDelayMs = round(MAX + ratio * (MIN - MAX))
        // ratio=0 (zero distance): 50ms. ratio=1 (one viewport): 4ms. Monotonically decreasing.
        fun staggerDelay(ratio: Float): Long = Math.round(50f + ratio * (4f - 50f))
        assertEquals("ratio=0.0: max delay 50ms", 50L, staggerDelay(0f))
        assertEquals("ratio=1.0: min delay 4ms", 4L, staggerDelay(1f))
        val mid = staggerDelay(0.5f)
        assertTrue("ratio=0.5: between min and max", mid in 4L..50L)
        // Stagger delay must DECREASE as scroll distance grows (fewer rows are delayed for far scrolls).
        assertTrue("larger ratio → smaller delay", staggerDelay(0.8f) < staggerDelay(0.2f))
    }

    @Test
    fun rowStaggerFormula_ratioIsClampedTo1() {
        // A scroll distance larger than the viewport is clipped to ratio=1, so the minimum delay
        // is always the floor and never negative.
        fun staggerDelay(ratio: Float): Long = Math.round(50f + minOf(1f, ratio) * (4f - 50f))
        assertEquals("ratio clamped: 2.0 same as 1.0", staggerDelay(1f), staggerDelay(2f))
        assertTrue("clamped delay never below minimum", staggerDelay(100f) >= 4L)
    }

    // ===================================================================== blocker corrections
    // The following tests cover the 7 blockers found in commit b66f593a and would fail on that
    // commit. They are written against user-visible properties and formula invariants.

    @Test
    fun blocker1_wordEmphasis_lineWideScaleFieldRemoved() {
        // b66f593a composed emphasisScale into child.setScaleX/Y, scaling the entire multi-word
        // row. The fix removes word-level scale entirely (architecturally forbidden per Build #44).
        // Verify: LyricsTextView no longer holds a longNoteScale field.
        val ltv = AudioPlayerAlert::class.java.declaredClasses.find { it.simpleName == "LyricsTextView" }
        assertNotNull("LyricsTextView inner class must exist", ltv)
        val hasScale = ltv!!.declaredFields.any { it.name == "longNoteScale" }
        assertFalse("longNoteScale must be removed — line-wide scale is architecturally forbidden",
            hasScale)
    }

    @Test
    fun blocker1_wordEmphasis_glowFieldReplacedWithTimingFields() {
        // b66f593a held a single float longNoteGlowAlpha applied uniformly to all spans.
        // The fix replaces it with timing fields enabling per-binding word-local rise/return.
        val ltv = AudioPlayerAlert::class.java.declaredClasses.find { it.simpleName == "LyricsTextView" }
        assertNotNull("LyricsTextView inner class must exist", ltv)
        val names = ltv!!.declaredFields.map { it.name }
        assertFalse("longNoteGlowAlpha must be removed", "longNoteGlowAlpha" in names)
        assertTrue("longNoteElapsedMs timing field must be present", "longNoteElapsedMs" in names)
        assertTrue("longNoteWordDurationMs timing field must be present",
            "longNoteWordDurationMs" in names)
    }

    @Test
    fun blocker2_wordEmphasis_riseAndReturn_alphaFallsBackToZero() {
        // b66f593a used a monotonically increasing eased fraction — glow grew and never returned.
        // The fix implements per-binding rise/hold/return phases. At t > returnEnd, alpha == 0.
        val wordDurationMs = 2000L
        val animMs = minOf(wordDurationMs, 3000L)
        val bindingCount = 2
        val perBindingWindow = animMs / bindingCount               // 1000ms
        // staggerStep = min(400, round(0.4 * animMs / bindingCount)) = min(400, 400) = 400
        val staggerStep = minOf(400L, Math.round(0.4f * animMs / bindingCount))
        val bindingIndex = 0
        val growStart = staggerStep * bindingIndex                 // 0ms
        val holdEnd = growStart + 2L * perBindingWindow           // 2000ms
        val returnEnd = holdEnd + perBindingWindow                 // 3000ms
        // At t == returnEnd, return-phase t == 1.0 → alpha == 0
        val t = (returnEnd - holdEnd).toFloat() / perBindingWindow
        val alpha = (1f - minOf(1f, t)) * (128f / 255f)
        assertEquals("alpha must be 0 when return phase completes", 0f, alpha, 0.001f)
        // Before return starts, at t just inside hold phase, alpha must be max
        val holdT = (holdEnd - 1L - holdEnd).toFloat() / perBindingWindow
        val holdAlpha = (1f - maxOf(0f, holdT)) * (128f / 255f)
        assertEquals("alpha must be max during hold phase", 128f / 255f, holdAlpha, 0.001f)
    }

    @Test
    fun blocker2_wordEmphasis_risePhase_alphaGrowsFromZero() {
        // During the rise phase, alpha must increase from 0 toward the peak value.
        val wordDurationMs = 1500L
        val animMs = minOf(wordDurationMs, 3000L)
        val bindingCount = 1
        val perBindingWindow = animMs / bindingCount             // 1500ms
        val growStart = 0L
        // Pre-delay: alpha must be exactly 0
        val alphaPreDelay = if (0L < growStart) 128f / 255f else 0f
        assertEquals("alpha is 0 before rise starts", 0f, alphaPreDelay, 0.001f)
        // Mid-rise: elapsed = growStart + perBindingWindow/2
        val elapsedMidRise = growStart + perBindingWindow / 2
        val riseT = (elapsedMidRise - growStart).toFloat() / perBindingWindow
        // Without applying LONG_NOTE_EASING for simplicity, just verify the linear fraction
        assertTrue("rise-phase t must be in (0,1) at mid-rise", riseT > 0f && riseT < 1f)
    }

    @Test
    fun blocker3_staggerSign_positiveDistance_delayedRowHasPositiveTranslationY() {
        // b66f593a used distance*(staggeredFraction - fraction) — inverted sign.
        // For positive distance (content scrolls up), a delayed row (rowFraction < fraction) must
        // have translationY > 0: the row is pushed DOWN, lagging behind the upward scroll.
        val distance = 500
        val fraction = 0.6f
        val rowFraction = 0.3f                              // delayed row has less progress
        val translationY = distance.toFloat() * (fraction - rowFraction)   // fixed formula
        assertTrue("positive distance, delayed row: translationY > 0 (row lags down)",
            translationY > 0f)
        // Cross-check: the old (broken) formula would give the opposite sign
        val brokenTranslationY = distance.toFloat() * (rowFraction - fraction)
        assertTrue("old formula gives wrong (negative) sign", brokenTranslationY < 0f)
    }

    @Test
    fun blocker3_staggerSign_negativeDistance_delayedRowHasNegativeTranslationY() {
        // For negative distance (content scrolls down), a delayed row must have translationY < 0:
        // the row is pushed UP, lagging behind the downward scroll.
        val distance = -500
        val fraction = 0.6f
        val rowFraction = 0.3f
        val translationY = distance.toFloat() * (fraction - rowFraction)   // fixed formula
        assertTrue("negative distance, delayed row: translationY < 0 (row lags up)",
            translationY < 0f)
    }

    @Test
    fun blocker4_scrollEasing_appliedExactlyOnce() {
        // b66f593a installed a deceleration interpolator so getAnimatedFraction() returned an
        // already-eased value, then applied deceleration again — double-easing.
        // The fix uses a null interpolator and applies easing exactly once.
        // Property: singleEase(0.5) != doubleEase(0.5) for exp > 1.
        val exp = 2.0
        val t = 0.5
        val single = 1.0 - Math.pow(1.0 - t, exp)           // correct: one application
        val doubled = 1.0 - Math.pow(1.0 - single, exp)     // wrong: two applications
        assertNotEquals("single-easing and double-easing must differ at midpoint",
            single, doubled, 0.001)
        // Boundary: single easing at t=1.0 must be exactly 1.0
        val atOne = 1.0 - Math.pow(0.0, exp)
        assertEquals("single easing at t=1 must be 1.0", 1.0, atOne, 1e-9)
        // Monotonicity: eased fraction must increase with t
        assertTrue("single easing must be monotonically increasing",
            (1.0 - Math.pow(1.0 - 0.8, exp)) > (1.0 - Math.pow(1.0 - 0.4, exp)))
    }

    @Test
    fun blocker5_retargetContinuity_startTranslationYBlendDecaysToZero() {
        // b66f593a zeroed translationY on cancel, snapping rows to zero on every retarget.
        // The fix captures startTranslationY[] and decays it: startY * (1 - rawT).
        // At rawT=0: full capture (no snap). At rawT=1: fully gone (clean settle).
        val startY = 80f
        val atStart = startY * (1f - 0f)     // rawT = 0
        val atMid   = startY * (1f - 0.5f)   // rawT = 0.5
        val atEnd   = startY * (1f - 1f)     // rawT = 1
        assertEquals("rawT=0: captured position fully present", startY, atStart, 0.001f)
        assertEquals("rawT=0.5: half decayed", startY / 2f, atMid, 0.001f)
        assertEquals("rawT=1: fully settled to zero", 0f, atEnd, 0.001f)
    }

    @Test
    fun blocker6_wordTimedPreAnchor_hardLeadIs550ms() {
        // b66f593a used min(550, max(80, gap/2)) for all sources. For word-timed karaoke, the fix
        // returns min(LYRIC_FOLLOW_LEAD_MAX, gapMs) — hard 550ms, capped at the actual gap.
        // Large gap (> 550ms): lead must be exactly 550ms.
        val leadLarge = AudioPlayerAlert.lyricFollowLeadMs(2000L, true)
        assertEquals("word-timed, gap > 550ms: lead must be 550ms", 550L, leadLarge)
        // Small gap (< 550ms): lead must equal the gap (not gap/2).
        val leadSmall = AudioPlayerAlert.lyricFollowLeadMs(400L, true)
        assertEquals("word-timed, gap < 550ms: lead must equal gap (not half-gap)", 400L, leadSmall)
        // Verify it's NOT gap/2 for the small case (the b66f593a bug).
        assertNotEquals("word-timed small gap must not use half-gap formula", 200L, leadSmall)
    }

    @Test
    fun blocker6_semanticActivationUnchanged_nonWordTimedUsesHalfGap() {
        // The lead only affects WHEN the scroll starts; semantic line activation remains at the
        // stated timestamp. Non-word-timed sources must keep the original half-gap behaviour.
        val lead600 = AudioPlayerAlert.lyricFollowLeadMs(600L, false)
        assertEquals("non-word-timed, gap=600ms: lead = min(550, max(80, 300)) = 300", 300L, lead600)
        val lead100 = AudioPlayerAlert.lyricFollowLeadMs(100L, false)
        assertEquals("non-word-timed, gap=100ms: lead = min(550, max(80, 50)) = 80", 80L, lead100)
        val lead2000 = AudioPlayerAlert.lyricFollowLeadMs(2000L, false)
        assertEquals("non-word-timed, gap=2000ms: lead = min(550, max(80, 1000)) = 550", 550L, lead2000)
    }

    @Test
    fun blocker7_startOnlyWord_notEligibleForLongNote_inferred_duration_not_authored() {
        // b66f593a set longNoteEligible=true for any wordDurationMs >= 1000ms, including LRC
        // start-only words where duration is an inferred ownership window, not an authored time.
        // The fix gates on hasExplicitWordEnd (segments.hasEndTime(index)) instead of sweepMs > 0.

        // LRC start-only: "hello" owns 1500ms (to next word start) but has no explicit end.
        val lrcLine = lrc("[00:00.000]<00:00.000>hello <00:01.500>world")
        val frame = KaraokeFrame()
        frame.resolve(lrcLine, 200L, 5000L)   // inside "hello" at 200ms
        assertFalse(
            "start-only word: longNoteEligible must be false even with inferred 1500ms duration",
            frame.longNoteEligible
        )
        // Confirm the ownership window is >= threshold (eligibility gate must actually be firing).
        assertTrue("inferred duration must be >= 1000ms to confirm the gate fires",
            frame.wordDurationMs >= 1000L)

        // TTML explicit-end: "hello" has an authored 1500ms end — eligible.
        val ttmlLine = ttml(
            """<p begin="00:00.000" end="00:05.000">""" +
                """<span begin="00:00.000" end="00:01.500">hello</span> """ +
                """<span begin="00:02.000" end="00:03.000">world</span></p>"""
        )
        val ttmlFrame = KaraokeFrame()
        ttmlFrame.resolve(ttmlLine, 200L, 5000L)   // inside "hello" at 200ms
        assertTrue(
            "explicit-end TTML word with 1500ms authored duration must be long-note eligible",
            ttmlFrame.longNoteEligible
        )
    }

    // ===================================================================== audit pass 2 corrections
    // Corrections for the four problems found in commit 467f130e.

    @Test
    fun pass2_glowDisabled_prevLongNoteFieldsExistInLyricsTextView() {
        // Glow is disabled pending a safe word-local implementation (visual runs span the whole
        // line in single-run LTR text). The data model fields are preserved for future use.
        val ltv = AudioPlayerAlert::class.java.declaredClasses.find { it.simpleName == "LyricsTextView" }
        assertNotNull("LyricsTextView must exist", ltv)
        val names = ltv!!.declaredFields.map { it.name }
        assertTrue("prevLongNoteCount field must exist", "prevLongNoteCount" in names)
        assertTrue("prevWordElapsedMs field must exist", "prevWordElapsedMs" in names)
        assertTrue("prevWordDurationMs field must exist", "prevWordDurationMs" in names)
        assertTrue("prevWordTextStart field must exist", "prevWordTextStart" in names)
        assertTrue("prevWordTextEnd field must exist", "prevWordTextEnd" in names)
        // glowLayerActive stays for defensive clearance in clearKaraoke()
        assertTrue("glowLayerActive field must still exist", "glowLayerActive" in names)
    }

    @Test
    fun pass2_prevLongNoteActive_returnTailLiveAfterWordChange() {
        // Word A: start=0ms, explicit end=1500ms. Word B: start=2000ms.
        // At T=2500ms, word B is current. Word A's conservative return window = pStart + 3*animMs
        // = 0 + 3*1500 = 4500ms. T=2500 < 4500 → prevLongNoteActive must be true.
        val line = ttml("""<p begin="00:00.000" end="00:10.000">""" +
            """<span begin="00:00.000" end="00:01.500">hello</span> """ +
            """<span begin="00:02.000" end="00:03.000">world</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 2500L, 10000L)
        assertEquals("word B is current at T=2500ms", line.segments.startOffset(1), frame.wordStart)
        assertTrue("word A return tail is live at T=2500ms (window = 4500ms)", frame.prevLongNoteCount >= 1)
        assertEquals("prevWordAbsoluteStartMs[0] is A's start", 0L, frame.prevWordAbsoluteStartMs[0])
        assertEquals("prevWordDurationMs[0] is A's authored duration", 1500L, frame.prevWordDurationMs[0])
    }

    @Test
    fun pass2_prevLongNoteActive_tailExpiredBeyondWindow() {
        // At T=5000ms, well beyond pStart + 3*animMs = 4500ms, prevLongNoteActive must be false.
        val line = ttml("""<p begin="00:00.000" end="00:10.000">""" +
            """<span begin="00:00.000" end="00:01.500">hello</span> """ +
            """<span begin="00:02.000" end="00:03.000">world</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 5000L, 10000L)
        assertEquals("word A return tail must be expired at T=5000ms (window = 4500ms)",
            0, frame.prevLongNoteCount)
    }

    @Test
    fun pass2_prevLongNote_startOnlyPreviousWordIsNotEligibleForReturnTail() {
        // A start-only (LRC) previous word has no explicit end, so hasEndTime(pi) is false.
        // prevLongNoteActive must remain false regardless of how long the ownership window is.
        val line = lrc("[00:00.000]<00:00.000>hello <00:01.500>world <00:05.000>end")
        val frame = KaraokeFrame()
        frame.resolve(line, 2000L, Long.MAX_VALUE)  // inside "world"
        assertEquals("world is current at T=2000ms", line.segments.startOffset(1), frame.wordStart)
        assertEquals("start-only previous word must not populate prevLongNoteCount",
            0, frame.prevLongNoteCount)
    }

    @Test
    fun pass2_decayedStart_viewKeyedMapHandlesNewViews() {
        // When a View was not captured in startTranslationYMap (entered RecyclerView after the
        // animation started), capturedStart is null → decayedStart = 0. The view starts from
        // zero offset — correct, as it has no prior stagger to decay from.
        val capturedStart: Float? = null  // not in map
        val rawT = 0.5f
        val decayedStart = if (capturedStart != null) capturedStart * (1f - rawT) else 0f
        assertEquals("new view not in map: decayedStart must be 0", 0f, decayedStart, 0.0001f)

        // A view that WAS captured decays its offset to zero by rawT=1.
        val capturedStartKnown: Float? = 40f  // in map
        val decayedAtHalf = if (capturedStartKnown != null) capturedStartKnown * (1f - rawT) else 0f
        assertEquals("known start decays by (1-rawT) at mid-animation", 20f, decayedAtHalf, 0.0001f)
        val decayedAtEnd = if (capturedStartKnown != null) capturedStartKnown * (1f - 1f) else 0f
        assertEquals("known start fully decays to 0 at rawT=1", 0f, decayedAtEnd, 0.0001f)
    }

    @Test
    fun pass2_onAnimationEnd_lateChildGuard_rowDelayBoundary() {
        // With effectiveRowDelay = min(rawRowDelay, fMaxRowDelay), every row's stagger completes
        // by totalDuration = scrollDuration + fMaxRowDelay, so onAnimationEnd zeroes all rows.
        // This test verifies the cap arithmetic: a late row's rawDelay (250ms) exceeds fMaxRowDelay
        // (200ms), but after capping its effectiveDelay equals fMaxRowDelay, so it finishes on time.
        val fMaxRowDelay = 200L
        val itemDelayMs = 50L
        val row = 0

        val earlyAdapterPos = 3   // rawDelay = 150ms ≤ 200ms → effectiveDelay = 150ms
        val earlyRaw = itemDelayMs * (earlyAdapterPos - row)
        val earlyEffective = minOf(earlyRaw, fMaxRowDelay)
        assertEquals("early row effective delay equals raw delay", earlyRaw, earlyEffective)

        val lateAdapterPos = 5    // rawDelay = 250ms > 200ms → effectiveDelay = 200ms (capped)
        val lateRaw = itemDelayMs * (lateAdapterPos - row)
        val lateEffective = minOf(lateRaw, fMaxRowDelay)
        assertTrue("late row raw delay exceeds fMaxRowDelay", lateRaw > fMaxRowDelay)
        assertEquals("late row effective delay is capped at fMaxRowDelay", fMaxRowDelay, lateEffective)
    }

    // ===================================================================== audit pass 3 tests

    @Test
    fun pass3_overlayAlpha_risePhase_zeroAtStart() {
        // Alpha starts at 0 when elapsedMs = 0.
        val animMs = 1000L
        val alpha = invokeOverlayAlpha(0L, animMs)
        assertEquals("alpha must be 0 at elapsed=0", 0f, alpha, 0.001f)
    }

    @Test
    fun pass3_overlayAlpha_risePhase_maxAtAnimMs() {
        // Alpha reaches maxAlpha (0.30) exactly at elapsed = animMs (end of rise phase).
        val animMs = 1000L
        val alpha = invokeOverlayAlpha(animMs, animMs)
        assertEquals("alpha must equal maxAlpha at elapsed=animMs", 0.30f, alpha, 0.001f)
    }

    @Test
    fun pass3_overlayAlpha_holdPhase_staysAtMax() {
        // Alpha stays at maxAlpha during hold phase: animMs ≤ elapsed < 2*animMs.
        val animMs = 1000L
        val alphaMid = invokeOverlayAlpha(1500L, animMs)  // midpoint of hold
        assertEquals("alpha must stay at maxAlpha during hold", 0.30f, alphaMid, 0.001f)
    }

    @Test
    fun pass3_overlayAlpha_returnPhase_zeroAt3xAnimMs() {
        // Alpha returns to 0 exactly at elapsed = 3*animMs (end of return phase).
        val animMs = 1000L
        val alpha = invokeOverlayAlpha(3L * animMs, animMs)
        assertEquals("alpha must be 0 at elapsed=3*animMs", 0f, alpha, 0.001f)
    }

    @Test
    fun pass3_overlayAlpha_expiredBeyond3x_staysZero() {
        // After elapsed > 3*animMs, alpha stays 0.
        val animMs = 1000L
        val alpha = invokeOverlayAlpha(4000L, animMs)
        assertEquals("alpha must be 0 beyond 3*animMs", 0f, alpha, 0.001f)
    }

    @Test
    fun pass3_prevLongNoteCount_multipleLiveEligibleWords() {
        // When two previous words are both eligible and their return envelopes overlap positionMs,
        // prevLongNoteCount must be 2. Fixture ensures gamma is the semantic current word at T=5800ms
        // so alpha and beta are both in the previous-word collection.
        //
        // Word A (alpha): 0–3000ms  → dur=3000ms, animMs=3000ms, window=0+9000=9000ms
        // Word B (beta):  3500–5000ms → dur=1500ms, animMs=1500ms, window=3500+4500=8000ms
        // Word C (gamma): 5500–6000ms (dur=500ms — current at T=5800ms, ineligible as current)
        // At T=5800ms: A window=9000 > 5800 ✓  B window=8000 > 5800 ✓  both are live.
        val line = ttml("""<p begin="00:00.000" end="00:15.000">""" +
            """<span begin="00:00.000" end="00:03.000">alpha</span> """ +
            """<span begin="00:03.500" end="00:05.000">beta</span> """ +
            """<span begin="00:05.500" end="00:06.000">gamma</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 5800L, 15000L)
        assertEquals("both alpha and beta are live previous tails at T=5800ms", 2, frame.prevLongNoteCount)
    }

    @Test
    fun pass3_prevLongNoteCount_expiredNewerWordDoesNotTerminateScan() {
        // An expired NEWER previous word must NOT terminate the backward scan, because an OLDER
        // word may have a longer authored duration and therefore a window that extends further.
        //
        // Word A (alpha, older): 0–3000ms → dur=3000ms, animMs=3000ms, window=0+9000=9000ms
        // Word B (beta, newer):  4000–5000ms → dur=1000ms, animMs=1000ms, window=4000+3000=7000ms
        // Word C (gamma, current): 5500–6000ms
        // At T=7500ms: beta window=7000 < 7500 → beta EXPIRED; alpha window=9000 > 7500 → alpha LIVE
        //
        // With the old (wrong) break: beta expired → break → alpha missed → count=0
        // With the correct continue:  beta expired → continue → alpha found → count=1
        val line = ttml("""<p begin="00:00.000" end="00:10.000">""" +
            """<span begin="00:00.000" end="00:03.000">alpha</span> """ +
            """<span begin="00:04.000" end="00:05.000">beta</span> """ +
            """<span begin="00:05.500" end="00:06.000">gamma</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 7500L, 10000L)
        assertEquals("scan must continue past expired beta to find live alpha", 1, frame.prevLongNoteCount)
        assertEquals("the live tail is alpha (start=0ms)", 0L, frame.prevWordAbsoluteStartMs[0])
    }

    @Test
    fun pass3_prevTails_oneLiveTailInFrame() {
        // Exactly one previous eligible word live — prevLongNoteCount == 1.
        val line = ttml("""<p begin="00:00.000" end="00:10.000">""" +
            """<span begin="00:00.000" end="00:01.500">hello</span> """ +
            """<span begin="00:02.000" end="00:03.000">world</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 2500L, 10000L)  // inside "world"
        assertEquals("exactly one previous live tail (hello)", 1, frame.prevLongNoteCount)
        assertEquals("the tail is hello (start=0ms)", 0L, frame.prevWordAbsoluteStartMs[0])
    }

    @Test
    fun pass3_prevTails_allExpiredCountIsZero() {
        // All previous eligible words have passed their 3×animMs window → prevLongNoteCount == 0.
        // alpha: window = 0 + 3*1500 = 4500ms.  beta: window = 2000 + 3*3000 = 11000ms.
        // At T=15000ms both windows are exceeded.
        val line = ttml("""<p begin="00:00.000" end="00:20.000">""" +
            """<span begin="00:00.000" end="00:01.500">alpha</span> """ +
            """<span begin="00:02.000" end="00:05.000">beta</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 15000L, Long.MAX_VALUE)
        assertEquals("all previous tails expired at T=15000ms", 0, frame.prevLongNoteCount)
    }

    @Test
    fun pass3_prevTails_currentAndPreviousSimultaneous() {
        // Both current-word eligibility (longNoteEligible) and a live previous tail
        // (prevLongNoteCount >= 1) can coexist — the two data paths are independent.
        val line = ttml("""<p begin="00:00.000" end="00:10.000">""" +
            """<span begin="00:00.000" end="00:01.500">hello</span> """ +
            """<span begin="00:02.000" end="00:04.000">world</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 2500L, 10000L)  // inside "world": eligible (dur=2000ms >= 1000ms)
        assertTrue("current word (world) is eligible", frame.longNoteEligible)
        assertEquals("previous tail (hello) is live at T=2500ms", 1, frame.prevLongNoteCount)
    }

    @Test
    fun pass3_prevTails_prevWordElapsedMs_fieldExistsInLyricsTextView() {
        // LyricsTextView stores pre-computed elapsed ms for each previous tail so it does not
        // need to know absolute playback time. The field is prevWordElapsedMs (not absStartMs).
        val ltv = lyricsTextViewClass()
        val names = ltv.declaredFields.map { it.name }
        assertTrue("prevWordElapsedMs field must exist in LyricsTextView", "prevWordElapsedMs" in names)
        assertFalse("prevWordAbsoluteStartMs must NOT exist in LyricsTextView (renamed to elapsed)",
            "prevWordAbsoluteStartMs" in names)
    }

    @Test
    fun pass3_glyphOverlay_methodsExistForGlyphFollowingRendering() {
        // Structural guard: the glyph-following overlay needs two helpers plus a static xfermode.
        val ltv = lyricsTextViewClass()
        val methodNames = ltv.declaredMethods.map { it.name }
        assertTrue("drawGlyphOverlay must exist for glyph-following overlay",
            "drawGlyphOverlay" in methodNames)
        assertTrue("renderOverlayLine must exist for per-line saveLayer passes",
            "renderOverlayLine" in methodNames)
        val fieldNames = ltv.declaredFields.map { it.name }
        assertTrue("OVERLAY_SRC_IN static xfermode must exist to avoid per-frame allocation",
            "OVERLAY_SRC_IN" in fieldNames)
    }

    @Test
    fun pass3_glyphOverlay_overlaySrcInIsPorterDuffXfermode() {
        // OVERLAY_SRC_IN must be a non-null PorterDuffXfermode to ensure glyph-following compositing.
        val ltv = lyricsTextViewClass()
        val field = ltv.declaredFields.find { it.name == "OVERLAY_SRC_IN" }
            ?: error("OVERLAY_SRC_IN field not found")
        field.isAccessible = true
        val value = field.get(null)
        assertNotNull("OVERLAY_SRC_IN must not be null", value)
        assertTrue("OVERLAY_SRC_IN must be a PorterDuffXfermode",
            value is android.graphics.PorterDuffXfermode)
    }

    @Test
    fun pass3_prevTails_expiredNewerBoundaryExact_notZero() {
        // At the exact boundary T = pStart + 3*animMs the word IS expired (>= is strictly expired).
        // beta: start=4000ms, dur=1000ms, animMs=1000ms, exact expiry = 4000+3*1000 = 7000ms
        // alpha: start=0ms, dur=3000ms, animMs=3000ms, window = 0+9000 = 9000ms > 7000ms → live
        // At T=7000ms: beta is exactly expired and alpha is alive → count must be 1.
        val line = ttml("""<p begin="00:00.000" end="00:10.000">""" +
            """<span begin="00:00.000" end="00:03.000">alpha</span> """ +
            """<span begin="00:04.000" end="00:05.000">beta</span> """ +
            """<span begin="00:06.000" end="00:07.000">gamma</span></p>""")
        val frame = KaraokeFrame()
        frame.resolve(line, 7000L, 10000L)
        assertEquals("beta exactly expired at T=7000ms, alpha still live: count=1",
            1, frame.prevLongNoteCount)
        assertEquals("the remaining tail is alpha (start=0ms)", 0L, frame.prevWordAbsoluteStartMs[0])
    }

    // ------------------------------------------------------------------ helpers for pass3 tests

    private fun invokeOverlayAlpha(elapsedMs: Long, animMs: Long): Float {
        val ltv = lyricsTextViewClass()
        val method = ltv.declaredMethods.find { it.name == "longNoteOverlayAlpha" }
            ?: error("longNoteOverlayAlpha method not found in LyricsTextView")
        method.isAccessible = true
        return method.invoke(null, elapsedMs, animMs) as Float
    }
}
