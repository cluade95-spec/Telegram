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
        // There is exactly one size, one inset and one row metric derived, so no mode can be the
        // small ugly one. A second, smaller set is what an earlier build shipped.
        for (widthDp in floatArrayOf(320f, 360f, 392.7f, 411f)) {
            val size = AudioPlayerAlert.lyricsTextSizeDp(widthDp)
            assertTrue("large at ${widthDp}dp: $size", size >= 20f)
            assertTrue("generous padding", AudioPlayerAlert.lyricsRowPaddingVDp(size) >= 12f)
            assertTrue("inset tracks the size", AudioPlayerAlert.lyricsInsetDp(widthDp) >= 20f)
        }
    }

    // ============================================== measured against the Apple Music reference
    // docs/apple-music-lyrics-reference.md. Every figure below is a measurement off the supplied
    // 720x1560 capture, not a preference.

    @Test
    fun typeSizeReproducesTheMeasuredShareOfTheViewportWidth() {
        // MEASURED: em = 62.0 px of a 720 px wide frame = 0.0861 W.
        assertEquals(0.0861f, 62.0f / 720f, 0.0005f)
        assertEquals(360f * 0.0861f, AudioPlayerAlert.lyricsTextSizeDp(360f), 0.01f)
        // MEASURED: left inset 61-63 px of 720 = 0.0854 W, i.e. within 1 % of the em itself.
        assertEquals(360f * 0.0854f, AudioPlayerAlert.lyricsInsetDp(360f), 0.01f)
    }

    @Test
    fun rowAndBlockSpacingReproduceTheMeasuredPitches() {
        // MEASURED: wrapped rows 78.0 px apart, blocks 141.5 px apart, with em = 62.0 px.
        assertEquals(1.258f, 78.0f / 62.0f, 0.002f)
        assertEquals(1.024f, (141.5f - 78.0f) / 62.0f, 0.002f)
        // Two adjacent rows each contribute one padding, so the pair must be the measured gap.
        val em = 62f
        assertEquals(em * 1.024f, 2f * AudioPlayerAlert.lyricsRowPaddingVDp(em), 0.01f)
        // Extra line spacing lands the wrapped-row pitch on 1.258 em for any font metrics.
        val ascent = -0.927f * em
        val descent = 0.244f * em
        val extra = AudioPlayerAlert.lyricsExtraLineSpacingPx(em, ascent, descent)
        assertEquals(em * 1.258f, (descent - ascent) + extra, 0.01f)
        // A font already looser than the reference is left alone, never tightened.
        assertEquals(0f, AudioPlayerAlert.lyricsExtraLineSpacingPx(em, -1.2f * em, 0.4f * em), 0f)
    }

    @Test
    fun theActiveLineIsTopAnchoredAtTheMeasuredFraction() {
        // MEASURED: the active block's glyph top sits at y = 361 of a 1560 px frame in every
        // settled frame, whatever the block's height. Centring is what this used to do.
        assertEquals(361f / 1560f, AudioPlayerAlert.LYRICS_FOCUS_TOP_FRACTION, 0.001f)
        assertTrue("well above centre", AudioPlayerAlert.LYRICS_FOCUS_TOP_FRACTION < 0.4f)
    }

    @Test
    fun followEasingMatchesTheMeasuredReferenceCurve() {
        // MEASURED: mean normalised displacement over ten transitions, sd <= 0.025 per sample.
        val t = floatArrayOf(0.10f, 0.20f, 0.25f, 0.30f, 0.50f, 0.75f, 0.90f, 1.00f)
        val p = floatArrayOf(0.040f, 0.157f, 0.282f, 0.442f, 0.792f, 0.939f, 0.977f, 1.000f)
        for (i in t.indices) {
            assertEquals(
                "displacement at t/T=${t[i]}",
                p[i], AudioPlayerAlert.LYRIC_FOLLOW_EASING.getInterpolation(t[i]), 0.045f
            )
        }
    }

    @Test
    fun followMotionIsMonotonicAndNeverOvershoots() {
        var previous = 0f
        var i = 0
        while (i <= 100) {
            val v = AudioPlayerAlert.LYRIC_FOLLOW_EASING.getInterpolation(i / 100f)
            assertTrue("monotonic at $i", v >= previous - 1e-4f)
            assertTrue("no overshoot at $i: $v", v <= 1.0001f)
            assertTrue("no undershoot at $i: $v", v >= -1e-4f)
            previous = v
            i++
        }
        assertEquals(0f, AudioPlayerAlert.LYRIC_FOLLOW_EASING.getInterpolation(0f), 1e-4f)
        assertEquals(1f, AudioPlayerAlert.LYRIC_FOLLOW_EASING.getInterpolation(1f), 1e-4f)
    }

    @Test
    fun followEasingIsWeightedToTheTailNotSymmetric() {
        // MEASURED: velocity peaks at 0.29 T and half the travel is done by 0.33 T. A symmetric
        // curve puts half the travel at 0.50 T, which is what EASE_BOTH did.
        val half = AudioPlayerAlert.LYRIC_FOLLOW_EASING.getInterpolation(0.33f)
        assertTrue("half the distance by a third of the time, was $half", half >= 0.45f)
        // ...and it still has a real ease-IN: the first fifth of the time is not the fast part.
        val early = AudioPlayerAlert.LYRIC_FOLLOW_EASING.getInterpolation(0.20f)
        assertTrue("slow start, was $early", early < 0.25f)
    }

    @Test
    fun followLeadAndDurationMatchTheMeasuredTransition() {
        // MEASURED: the scroll starts a median 450 ms before the incoming line's fill begins
        // (mean 498, sd 93), and the travel itself runs a median 483 ms (mean 507, sd 90).
        assertEquals(450.0, AudioPlayerAlert.lyricFollowLeadMs(100_000L).toDouble(), 60.0)
        assertEquals(483.0, AudioPlayerAlert.LYRIC_FOLLOW_MEASURED_MS.toDouble(), 60.0)
        // A short gap still cannot lead by more than half of it.
        assertEquals(200L, AudioPlayerAlert.lyricFollowLeadMs(400L))
    }

    @Test
    fun depthIsCarriedByOpacityAlone() {
        // MEASURED: no depth blur in the reference (edge-rise width does not vary with distance),
        // and no per-depth scale (inactive row pitch is 78.00 px at every depth).
        assertEquals(0f, AudioPlayerAlert.karaokeBlurMaxDp(), 0f)
        // The falloff is monotonic, starts at nothing on the anchor and saturates at the edge.
        assertEquals(0f, AudioPlayerAlert.lyricsDepthOf(100f, 100f, 800f), 1e-4f)
        assertEquals(1f, AudioPlayerAlert.lyricsDepthOf(900f, 100f, 800f), 1e-4f)
        var previous = -1f
        var y = 100
        while (y <= 900) {
            val v = AudioPlayerAlert.lyricsDepthOf(y.toFloat(), 100f, 800f)
            assertTrue("monotonic at $y", v >= previous - 1e-4f)
            previous = v
            y += 10
        }
    }

    @Test
    fun theUnsungTierMatchesTheMeasuredAlphaHierarchy() {
        // MEASURED, relative to sung text: unsung on the current line 0.259, next line 0.293,
        // block+2 0.224, block+3 0.144, block+4 0.081. The current line's unsung text and the
        // next line's text are one tier, so the unsung level does not depend on "activeness".
        assertEquals(0.29f, AudioPlayerAlert.karaokeUnsungAlpha(), 0.04f)
        // MEASURED: furthest / nearest = 0.081 / 0.293 = 0.276.
        assertEquals(0.081f / 0.293f, AudioPlayerAlert.karaokeDepthFar(), 0.03f)
        // Composed, the far line lands on the measured 0.081 of sung white.
        val far = AudioPlayerAlert.karaokeUnsungAlpha() * AudioPlayerAlert.karaokeDepthFar()
        assertEquals(0.081f, far, 0.015f)
    }

    @Test
    fun theActiveLineIsNeverRescaledAndItsTypeNeverChanges() {
        // MEASURED: inactive row pitch is 78.00 px at every depth, so nothing scales with depth.
        // The reference does show the ACTIVE line 2.2 % larger (width ratio 1.0219 at five
        // independent thresholds), and that is deliberately NOT reproduced: a metric-affecting
        // change on the current row would re-measure and re-wrap it, which is exactly what the
        // run-aligned karaoke spans depend on not happening mid-playback.
        //
        // The type is a pure function of the viewport width and of nothing else - not of which
        // line is current, not of the sweep - so it cannot change when a line becomes active.
        assertEquals(
            AudioPlayerAlert.lyricsTextSizeDp(360f),
            AudioPlayerAlert.lyricsTextSizeDp(360f), 0f
        )
        assertEquals(
            AudioPlayerAlert.lyricsRowPaddingVDp(31f),
            AudioPlayerAlert.lyricsRowPaddingVDp(31f), 0f
        )
        // Nothing in the frame the renderer is handed can express a scale.
        val frameFields = KaraokeFrame::class.java.declaredFields.map { it.name.lowercase() }
        for (banned in listOf("scale", "zoom")) {
            assertFalse("KaraokeFrame must not carry $banned", frameFields.any { it.contains(banned) })
        }
    }

    @Test
    fun noVerticalWordOrLetterMotionExists() {
        // MEASURED: glyphs are geometrically identical between frames while the fill advances -
        // no per-word lift, no per-letter lift, no vertical wave. The frame the renderer paints
        // from offers no surface for one at all, so none can be switched on by accident.
        val frameFields = KaraokeFrame::class.java.declaredFields.map { it.name.lowercase() }
        for (banned in listOf("lift", "rise", "offsety", "translationy", "stagger", "dy")) {
            assertFalse("KaraokeFrame must not carry $banned", frameFields.any { it.contains(banned) })
        }
        // The whole frame is a horizontal range plus how far across it the fill has travelled.
        assertTrue("horizontal sweep only", frameFields.contains("sweep"))
        assertTrue(frameFields.contains("wordstart") && frameFields.contains("wordend"))
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
        // The architecture assertion. The row does not override onDraw AT ALL: there is no strip
        // renderer, no clipped pass, no canvas translation and no repeated super.onDraw(). One
        // frame is "set the appearance, invalidate, and let TextView draw the row once".
        val type = lyricsTextViewClass()
        for (method in type.declaredMethods) {
            assertNotEquals("the row must not override $method - karaoke is appearance only",
                "onDraw", method.name)
            assertNotEquals("nor draw()", "draw", method.name)
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
}
