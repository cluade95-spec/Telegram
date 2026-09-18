package org.telegram.ui.Components

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * the one the source's own timestamps select; the fill and the lift are derived afterwards and can
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
    // "When a word is sung for a long time the vertical motion finishes too early, the horizontal
    // colour sweep finishes too early, and the singer is still holding the word after both visual
    // effects are already finished." That is the bug this group exists to prevent coming back.

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

    @Test
    fun aLongWordAlsoMovesForAsLongAsItIsSung() {
        // The lift shares the word's interval, so what movement there is is slow on a held note
        // instead of being the same quick canned gesture on every word. `lift` is travel as a
        // fraction of KARAOKE_LIFT_DP, already scaled by how much room the word had.
        for (duration in longArrayOf(1500, 1000, 800)) {
            val line = pairWithGap(duration)
            val frame = KaraokeFrame()
            frame.resolve(line, 320, Long.MAX_VALUE)
            assertTrue("${duration}ms word is still moving at 320ms: ${frame.lift}", frame.lift > 0.05f)
            // Its peak lands around a third of the way in, not in the first fifth of a second.
            frame.resolve(line, (duration * 0.38f).toLong(), Long.MAX_VALUE)
            assertEquals("${duration}ms word peaks late", 1f, frame.lift, 0.01f)
        }
    }

    // ============================================== the lift is decoration, and stays out of sight
    // Device QA: "the words move up/down too much - in fast lyrics it feels like the text is
    // dancing." The fill already says which word is being sung. The lift is an accent on top of
    // that, and an accent the eye tracks is not an accent.

    @Test
    fun theWholeTravelIsATinyFractionOfALine() {
        // Roughly a tenth of the 22dp type, and less than half of what device QA called too much.
        assertTrue("the lift must stay around 1-1.5dp: ${AudioPlayerAlert.KARAOKE_LIFT_DP}",
            AudioPlayerAlert.KARAOKE_LIFT_DP <= 1.5f)
        assertTrue("but not be removed entirely", AudioPlayerAlert.KARAOKE_LIFT_DP > 0f)
        assertTrue("and stay far under the type size",
            AudioPlayerAlert.KARAOKE_LIFT_DP < AudioPlayerAlert.LYRICS_TEXT_SIZE_DP / 8f)
    }

    @Test
    fun aFastPassageGetsEssentiallyNoVerticalMovement() {
        // Everything at singing speed or faster: the amplitude has to be gone, not merely small.
        for (window in longArrayOf(30, 50, 80, 100, 150)) {
            assertEquals("a ${window}ms word must not move at all",
                0f, KaraokeFrame.liftAmplitudeScale(window), 0.001f)
        }
        // And only a genuinely held word reaches the full travel.
        assertTrue("a 300ms word stays subtle: ${KaraokeFrame.liftAmplitudeScale(300)}",
            KaraokeFrame.liftAmplitudeScale(300) < 0.35f)
        assertEquals("a held word gets the whole of it", 1f, KaraokeFrame.liftAmplitudeScale(900), 0.0001f)
    }

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

    // ================================================================== restrained word motion
    // "The words move up/down too rapidly - they look like they are dancing." A 30ms word cannot
    // be given a 3dp rise and settle; it must be given almost none.

    @Test
    fun aVeryShortWordIsNotForcedThroughAFullRise() {
        val tiny = floatArrayOf(
            KaraokeFrame.liftAmplitudeScale(30),
            KaraokeFrame.liftAmplitudeScale(50),
            KaraokeFrame.liftAmplitudeScale(60)
        )
        for (scale in tiny) {
            assertTrue("a word this short must barely move at all: $scale", scale < 0.02f)
        }
        // And the frame really does hand back that reduced amplitude, not a full one.
        for (duration in longArrayOf(30, 50, 60, 100, 150)) {
            val line = pairWithGap(duration)
            val frame = KaraokeFrame()
            var peak = 0f
            for (position in 0..duration) {
                frame.resolve(line, position, Long.MAX_VALUE)
                if (frame.lift > peak) peak = frame.lift
            }
            assertTrue("${duration}ms word must not dance: peak $peak", peak < 0.02f)
        }
    }

    @Test
    fun amplitudeGrowsSmoothlyWithTheRoomTheWordHasRatherThanSteppingAtAThreshold() {
        var previous = -1f
        for (window in 0L..600L step 5L) {
            val scale = KaraokeFrame.liftAmplitudeScale(window)
            assertTrue("never negative at $window", scale >= 0f)
            assertTrue("never beyond full at $window", scale <= 1f)
            assertTrue("never decreases at $window", scale >= previous - 0.0001f)
            if (previous >= 0f) {
                assertTrue("no visible step at $window: $previous -> $scale", scale - previous < 0.08f)
            }
            previous = scale
        }
        // A held word gets the whole of the intended travel.
        assertEquals(1f, KaraokeFrame.liftAmplitudeScale(620), 0.0001f)
        assertEquals(1f, KaraokeFrame.liftAmplitudeScale(1500), 0.0001f)
        // A word of middling length gets a modest fraction of it, not most of it.
        val medium = KaraokeFrame.liftAmplitudeScale(400)
        assertTrue("a 400ms word moves, but modestly: $medium", medium > 0.2f && medium < 0.8f)
    }

    @Test
    fun theLiftLeavesAndReturnsToRestWithoutAKickACornerOrABounce() {
        var previous = 0f
        var peak = 0f
        for (step in 0..2000) {
            val t = step / 2000f
            val value = KaraokeFrame.liftCurve(t)
            assertTrue("never below the resting position at $t", value >= -0.0001f)
            assertTrue("never beyond full travel at $t", value <= 1.0001f)
            if (t < 0.38f) assertTrue("rises without wobbling at $t", value >= previous - 0.0001f)
            if (t > 0.38f) assertTrue("settles without wobbling at $t", value <= previous + 0.0001f)
            if (value > peak) peak = value
            previous = value
        }
        assertEquals("reaches full travel exactly once", 1f, peak, 0.001f)
        assertEquals(0f, KaraokeFrame.liftCurve(0f), 0f)
        assertEquals(0f, KaraokeFrame.liftCurve(1f), 0f)
        assertEquals(0f, KaraokeFrame.liftCurve(1.5f), 0f)
        assertEquals(0f, KaraokeFrame.liftCurve(-1f), 0f)
        // Eases away from rest and into rest: the first and last percent of the travel are tiny,
        // which is what stops the motion reading as a flick.
        assertTrue("eases out of rest: ${KaraokeFrame.liftCurve(0.01f)}", KaraokeFrame.liftCurve(0.01f) < 0.01f)
        assertTrue("eases into rest: ${KaraokeFrame.liftCurve(0.99f)}", KaraokeFrame.liftCurve(0.99f) < 0.01f)
    }

    // ===================================================================== the word hand-over
    // At the exact moment the next word becomes current, the outgoing word must already be back at
    // its baseline. This is the group the old tests structurally could not contain: they used
    // single-word fixtures, so there was no hand-over to look at.

    @Test
    fun theOutgoingWordIsAtRestBeforeTheNextOneTakesOver() {
        for (gap in longArrayOf(1500, 1000, 800, 500, 300, 200, 100, 50, 30)) {
            val line = pairWithGap(gap)
            val frame = KaraokeFrame()
            frame.resolve(line, gap - 1, Long.MAX_VALUE)
            val leaving = frame.lift
            assertEquals("${gap}ms word still owns the frame one millisecond before hand-over",
                start(line, 0), frame.wordStart)
            frame.resolve(line, gap, Long.MAX_VALUE)
            assertEquals("${gap}ms: ownership passes exactly on the stated time", start(line, 1), frame.wordStart)
            val arriving = frame.lift
            // Nothing may jump. The outgoing word has already settled and the incoming one starts
            // from rest, so the step across the boundary is imperceptible at any amplitude.
            assertTrue("${gap}ms outgoing word must be at rest, was $leaving", leaving < 0.01f)
            assertEquals("${gap}ms incoming word starts at rest", 0f, arriving, 0.0001f)
            assertTrue("${gap}ms hand-over step: ${Math.abs(leaving - arriving)}",
                Math.abs(leaving - arriving) < 0.01f)
        }
    }

    @Test
    fun aRunOfVeryShortWordsNeverSawtooths() {
        // Three words 30ms apart - the passage that read as the text dancing.
        val line = lrc("[00:00.000]<00:00.000>a <00:00.030>b <00:00.060>c")
        val nextLine = 90L
        val frame = KaraokeFrame()
        var peak = 0f
        var worstStep = 0f
        var previous = 0f
        for (position in 0..89L) {
            frame.resolve(line, position, nextLine)
            if (frame.lift > peak) peak = frame.lift
            val step = Math.abs(frame.lift - previous)
            if (step > worstStep) worstStep = step
            previous = frame.lift
        }
        assertTrue("a 30ms passage must stay calm: peak $peak", peak < 0.02f)
        assertTrue("and never step: worst $worstStep", worstStep < 0.01f)
        // The sweep is still allowed to be quick, because the words genuinely are.
        frame.resolve(line, 29, nextLine)
        assertTrue(frame.sweep > 0.95f)
        frame.resolve(line, 30, nextLine)
        assertEquals(start(line, 1), frame.wordStart)
    }

    @Test
    fun overlappingTtmlSpansStillSettleBeforeOwnershipChanges() {
        // A span stated as held for two seconds, with the next span starting 300ms in. The stated
        // end still drives the fill; ownership still bounds the lift, so there is nothing to snap.
        val line = ttml(
            """<p begin="00:00.000" end="00:05.000">""" +
                """<span begin="00:00.000" end="00:02.000">Held</span> """ +
                """<span begin="00:00.300" end="00:00.900">Next</span></p>"""
        )
        assertEquals("the fill follows the stated end", 2000L,
            KaraokeFrame.sweepWindowMs(line.segments, 0, Long.MAX_VALUE))
        assertEquals("the lift is bounded by the next displayed word", 300L,
            KaraokeFrame.liftWindowMs(line.text, line.segments, 0, Long.MAX_VALUE))
        val frame = KaraokeFrame()
        frame.resolve(line, 299, Long.MAX_VALUE)
        assertTrue("outgoing word at rest before the overlap takes over: ${frame.lift}", frame.lift < 0.01f)
        assertTrue("its fill is still honestly mid-way: ${frame.sweep}", frame.sweep < 0.2f)
        frame.resolve(line, 300, Long.MAX_VALUE)
        assertEquals(start(line, 1), frame.wordStart)
        assertEquals(0f, frame.lift, 0.0001f)
    }

    @Test
    fun theLiftNeverOutlastsTheWordsOwnership() {
        // The property behind every case above, asserted directly.
        for (gap in longArrayOf(30, 50, 80, 120, 200, 400, 900, 2000, 4000)) {
            val line = pairWithGap(gap)
            val ownership = KaraokeFrame.ownershipWindowMs(line.segments, 0, Long.MAX_VALUE)
            val lift = KaraokeFrame.liftWindowMs(line.text, line.segments, 0, Long.MAX_VALUE)
            assertEquals("ownership is the stated gap", gap, ownership)
            assertTrue("lift window $lift must fit inside ownership $ownership", lift <= ownership)
        }
    }

    // ============================================= split / syllable-timed displayed words
    // Device QA: a displayed word cut into several timed parts lifted and settled once per part,
    // which was the busiest thing on the page. The fill must still follow every genuine part; only
    // the decoration is grouped, by the real text boundary.

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
    fun aSplitWordLiftsOnceAcrossTheWholeWordInsteadOfOncePerSyllable() {
        val line = syllables()
        // One window for all three syllables: 0 -> 800, not three windows of 200.
        for (index in 0..2) {
            assertEquals("segment $index shares the displayed word's window", 800L,
                KaraokeFrame.liftWindowMs(line.text, line.segments, index, Long.MAX_VALUE))
        }
        // Sampled across the whole word, the travel rises once and settles once. A per-syllable
        // lift would cross zero at 200 and 400 and peak three times.
        val frame = KaraokeFrame()
        var peaks = 0
        var previous = 0f
        var rising = true
        var maxLift = 0f
        for (position in 0..800L) {
            frame.resolve(line, position, Long.MAX_VALUE)
            if (frame.lift > maxLift) maxLift = frame.lift
            if (rising && frame.lift < previous - 0.0005f) {
                peaks++
                rising = false
            } else if (!rising && frame.lift > previous + 0.0005f) {
                rising = true
            }
            previous = frame.lift
        }
        assertEquals("exactly one rise and one settle across the displayed word", 1, peaks)
        assertEquals("and it does reach the full travel once", 1f, maxLift, 0.01f)
        // Zero at both ends of the displayed word.
        frame.resolve(line, 0, Long.MAX_VALUE)
        assertEquals(0f, frame.lift, 0.0001f)
        frame.resolve(line, 800, Long.MAX_VALUE)
        assertEquals("the next displayed word starts from rest", 0f, frame.lift, 0.0001f)
    }

    @Test
    fun theFillStillFollowsEverySyllableOfASplitWord() {
        // Grouping the decoration must not group the timing. Each stated syllable is still its own
        // current segment at its own stated time, with its own fill.
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
    fun theLiftNeverDelaysTheNextWord() {
        // The absolute rule. Ownership is Segments.indexAt and nothing else, so whatever the
        // decoration is doing, the next stated time takes the frame on the exact millisecond.
        for (gap in longArrayOf(1500, 800, 400, 200, 100, 50, 30)) {
            val line = pairWithGap(gap)
            val frame = KaraokeFrame()
            frame.resolve(line, gap - 1, Long.MAX_VALUE)
            val liftBefore = frame.lift
            assertEquals("${gap}ms: still the first word", start(line, 0), frame.wordStart)
            frame.resolve(line, gap, Long.MAX_VALUE)
            assertEquals("${gap}ms: the next word is current on its stated millisecond, " +
                "whatever the outgoing lift was ($liftBefore)", start(line, 1), frame.wordStart)
            assertEquals("${gap}ms: and its fill starts immediately", 0f, frame.sweep, 0.0001f)
        }
    }

    @Test
    fun aSplitWordThatIsStillLiftingDoesNotHoldBackTheNextDisplayedWord() {
        val line = syllables()
        val frame = KaraokeFrame()
        // Mid-word, the decoration is in flight.
        frame.resolve(line, 500, Long.MAX_VALUE)
        assertTrue("the word is mid-lift here: ${frame.lift}", frame.lift > 0f)
        // "day" still becomes current on its own stated millisecond.
        frame.resolve(line, 800, Long.MAX_VALUE)
        assertEquals(start(line, 3), frame.wordStart)
        assertEquals(0f, frame.sweep, 0.0001f)
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
            assertTrue("${tail}ms tail: and at rest by then", frame.lift < 0.02f)
        }
    }

    @Test
    fun aLineAlreadyLeftBehindIsWhollySungRatherThanCaughtMidFill() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        assertTrue(frame.resolveRow(line, 0, 1, 1150, 1600))
        assertEquals(line.text.length, frame.sungEnd)
        assertEquals(frame.wordStart, frame.wordEnd)
        assertEquals("nothing is left lifted on a line that has been left", 0f, frame.lift, 0.0001f)
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
        assertEquals(0f, frame.lift, 0.0001f)
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
    fun theLiftIsSeparableSoItCanSettleWhileTheFillStaysFrozen() {
        // Pause is a multiplier on the lift and on nothing else. Whatever it is scaled by, the
        // word and the fill are untouched - which is what lets the settle be wall-clock while the
        // semantics stay on the playback clock.
        val line = pairWithGap(1000)
        val frame = KaraokeFrame()
        for (position in longArrayOf(120, 380, 700)) {
            frame.resolve(line, position, Long.MAX_VALUE)
            val word = frame.wordStart
            val sweep = frame.sweep
            for (scale in floatArrayOf(1f, 0.6f, 0.2f, 0f)) {
                val lift = frame.lift * scale
                assertTrue("scaled lift stays within travel at $position", lift >= 0f && lift <= 1f)
                assertEquals("scaling the lift cannot move the word", word, frame.wordStart)
                assertEquals("scaling the lift cannot move the fill", sweep, frame.sweep, 0f)
            }
            assertEquals("fully settled means exactly rest", 0f, frame.lift * 0f, 0f)
        }
        assertTrue("the settle must be short enough to read as a settle",
            AudioPlayerAlert.KARAOKE_LIFT_SETTLE_MS >= 80L && AudioPlayerAlert.KARAOKE_LIFT_SETTLE_MS <= 400L)
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
        assertEquals(paused.lift, resumed.lift, 0f)
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
        assertTrue("the lift stays tiny", AudioPlayerAlert.KARAOKE_LIFT_DP <= 1.5f)
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
        assertEquals(0f, frame.lift, 0.0001f)
    }

    @Test
    fun seekingBackwardsReconstructsTheEarlierFrameExactly() {
        val line = startsOnly()
        val frame = KaraokeFrame()
        frame.resolve(line, 1150, 1600)
        val sweep = frame.sweep
        val lift = frame.lift
        val word = frame.wordStart
        frame.resolve(line, 9000, Long.MAX_VALUE)
        frame.resolve(line, 1150, 1600)
        assertEquals(sweep, frame.sweep, 0f)
        assertEquals(lift, frame.lift, 0f)
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
        assertEquals(first.lift, recycled.lift, 0f)
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
}
