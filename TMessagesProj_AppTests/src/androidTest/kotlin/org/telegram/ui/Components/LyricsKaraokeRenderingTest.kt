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
import org.telegram.ui.Components.AudioPlayerAlert.KaraokeWave

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

    // ============================================ the vertical decoration is a grapheme wave
    // Device QA, comparing against a polished reference: "vertical motion is a VERY subtle upward
    // wave through the letters. Letters do not wait for one another. Words do not wait for the
    // previous word. It is slow and smooth even when the sung timing is fast. There is no normal
    // downward return."
    //
    // So the whole of the decoration is KaraokeWave: a fixed, slow rise per grapheme, started at
    // its own displayed word's genuine time plus a tiny offset along the word. Nothing about it is
    // derived from how long the word was sung, and nothing about it can hold anything back.

    @Test
    fun theWholeTravelIsATinyFractionOfALine() {
        // Smaller than the model it replaces, which device QA still called too much.
        assertTrue("the lift must stay around 0.9dp: ${AudioPlayerAlert.KARAOKE_LIFT_DP}",
            AudioPlayerAlert.KARAOKE_LIFT_DP <= 1.0f)
        assertTrue("but not be removed entirely", AudioPlayerAlert.KARAOKE_LIFT_DP > 0f)
        assertTrue("and stay far under the type size",
            AudioPlayerAlert.KARAOKE_LIFT_DP < AudioPlayerAlert.LYRICS_TEXT_SIZE_DP / 8f)
    }

    @Test
    fun theRiseIsSlowAndNeverComesBackDown() {
        // A. Monotonic for the whole of forward playback, and it ends raised.
        var previous = -1f
        for (position in 0L..4000L step 3L) {
            val value = KaraokeWave.graphemeLift(position, 1000, 2, 1f)
            assertTrue("never travels downward at $position: $previous -> $value",
                value >= previous - 0.0001f)
            assertTrue("never beyond the raised position at $position", value <= 1.0001f)
            previous = value
        }
        assertEquals("and it stays raised", 1f, previous, 0f)
        // F. Once there, it is there for good.
        for (position in longArrayOf(2000, 5000, 60000, 600000)) {
            assertEquals("still raised at $position", 1f,
                KaraokeWave.graphemeLift(position, 1000, 0, 1f), 0f)
        }
        // Eases away from rest rather than kicking off it.
        assertTrue("eases out of rest", KaraokeWave.graphemeLift(1005, 1000, 0, 1f) < 0.01f)
        // And it really is slow: not even half way after a fifth of a second.
        assertTrue("a fifth of a second is not most of the travel",
            KaraokeWave.graphemeLift(1200, 1000, 0, 1f) < 0.5f)
        assertTrue("the normal rise is in the half-second territory",
            KaraokeWave.RISE_MS in 400L..700L)
    }

    @Test
    fun theSameSlowRiseIsUsedWhateverTheWordsOwnDuration() {
        // B. One word owns 150ms, the other 900ms. Their fills differ by six times; their
        // decorative rise is the same function, sampled at the same elapsed times.
        val fast = pairWithGap(150)
        val slow = pairWithGap(900)
        val fastWave = KaraokeWave().apply { build(fast, Long.MAX_VALUE) }
        val slowWave = KaraokeWave().apply { build(slow, Long.MAX_VALUE) }
        assertEquals("both fixtures state two displayed words", 2, fastWave.count)
        assertEquals(2, slowWave.count)
        assertEquals("neither first word is bounded, so neither is compressed",
            Long.MAX_VALUE, fastWave.availableMs[0])
        assertEquals(Long.MAX_VALUE, slowWave.availableMs[0])
        val fastStart = fastWave.startMs[0]
        val slowStart = slowWave.startMs[0]
        for (elapsed in longArrayOf(0, 40, 130, 260, 400, 520, 900)) {
            assertEquals("at ${elapsed}ms into each word the two are at the same height",
                KaraokeWave.graphemeLift(fastStart + elapsed, fastStart, 0, 1f),
                KaraokeWave.graphemeLift(slowStart + elapsed, slowStart, 0, 1f), 0f)
        }
        // The genuine difference stays where it belongs: in the fill.
        val frame = KaraokeFrame()
        frame.resolve(fast, 75, Long.MAX_VALUE)
        assertEquals("the 150ms word is half filled at 75ms", 0.5f, frame.sweep, 0.02f)
        frame.resolve(slow, 75, Long.MAX_VALUE)
        assertTrue("the 900ms word is barely filled at 75ms: ${frame.sweep}", frame.sweep < 0.12f)
        // And the 150ms word's decoration is still climbing long after its fill has finished.
        frame.resolve(fast, 149, Long.MAX_VALUE)
        assertTrue("its fill is essentially done", frame.sweep > 0.95f)
        assertTrue("while its letters are still rising",
            KaraokeWave.graphemeLift(149, 0, 0, 1f) < 0.5f)
    }

    @Test
    fun adjacentGraphemesOverlapHeavilyRatherThanQueueing() {
        // D. A soft wave, not a typewriter: the trail between neighbours is a small fraction of
        // the rise, so a whole word is in motion at once.
        assertTrue("the stagger is tens of milliseconds",
            KaraokeWave.STAGGER_MS in 10L..60L)
        assertTrue("and far smaller than the rise it trails",
            KaraokeWave.STAGGER_MS * 6 < KaraokeWave.RISE_MS)
        val at = 200L
        var previous = 2f
        for (grapheme in 0..5) {
            val value = KaraokeWave.graphemeLift(at, 0, grapheme, 1f)
            assertTrue("grapheme $grapheme trails the one before it: $previous -> $value",
                value < previous)
            assertTrue("but is already moving with it: $value", value > 0f)
            previous = value
        }
        // The last letter of a nine-letter word starts long before the first has finished.
        val lastStarts = KaraokeWave.STAGGER_MS * 8
        assertTrue("letter 9 sets off while letter 1 is still climbing",
            lastStarts < KaraokeWave.RISE_MS)
        assertTrue("and letter 1 is nowhere near done when it does",
            KaraokeWave.graphemeLift(lastStarts, 0, 0, 1f) < 0.85f)
    }

    @Test
    fun aWordBeginsItsWaveAtItsOwnTimeWhileTheWordBeforeIsStillRising() {
        // C. Word A at 10.000, word B at 10.180, a rise of about half a second. At 10.250 both
        // are in motion. There is no queue and nothing waits.
        val aStart = 10000L
        val bStart = 10180L
        val at = 10250L
        val a = KaraokeWave.graphemeLift(at, aStart, 0, 1f)
        val b = KaraokeWave.graphemeLift(at, bStart, 0, 1f)
        assertTrue("A is still rising: $a", a > 0f && a < 1f)
        assertTrue("B is already rising: $b", b > 0f && b < 1f)
        assertTrue("and B really did start on its own millisecond",
            KaraokeWave.graphemeLift(bStart - 1, bStart, 0, 1f) == 0f &&
                KaraokeWave.graphemeLift(bStart + 5, bStart, 0, 1f) > 0f)
        // The overlap is genuine, not a rounding artefact: A is well past half way.
        assertTrue("A is substantially ahead of B: $a vs $b", a > b + 0.2f)
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

    // ============================================== only a skipped last word is ever hurried
    // The reference behaviour has one exception to the fixed speed: a final word that genuinely
    // starts so late that its normal wave would still be climbing when the line is carried away.
    // Only that word, only by as much as it needs, and continuously rather than at a threshold.

    @Test
    fun aFinalWordWithEnoughRoomKeepsTheNormalSlowRise() {
        // G. Six letters need RISE + 5 * STAGGER to finish. Given more than that, nothing changes.
        val needed = KaraokeWave.spanMs(6)
        assertEquals("nothing is compressed when the room is ample", 1f,
            KaraokeWave.compression(needed + 200, 6), 0f)
        assertEquals("nor when it exactly fits", 1f, KaraokeWave.compression(needed, 6), 0f)
        assertEquals("nor when nothing bounds it at all", 1f,
            KaraokeWave.compression(Long.MAX_VALUE, 6), 0f)
    }

    @Test
    fun aFinalWordThatWouldBeSkippedIsCompressedJustEnoughToFinish() {
        // H. The same six letters with far less room. The lift is shortened only as much as it
        // must be, and it really does complete before the line goes.
        for (room in longArrayOf(500, 400, 280, 200)) {
            val scale = KaraokeWave.compression(room, 6)
            assertTrue("${room}ms: compressed", scale < 1f)
            assertTrue("${room}ms: but never to nothing", scale >= KaraokeWave.MIN_COMPRESSION)
            assertTrue("${room}ms: the last letter is raised by the transition, not skipped",
                KaraokeWave.graphemeLift(room, 0, 5, scale) >= 0.999f)
        }
        // Continuous: a millisecond more room never changes the character of the motion.
        var previous = KaraokeWave.compression(100, 6)
        for (room in 101L..900L) {
            val scale = KaraokeWave.compression(room, 6)
            assertTrue("never decreases as room grows at $room", scale >= previous - 0.0001f)
            assertTrue("no visible step at $room", scale - previous < 0.02f)
            previous = scale
        }
        assertEquals("and it lands on the normal speed rather than jumping to it", 1f, previous, 0f)
    }

    @Test
    fun anOrdinaryFastWordIsNeverHurried() {
        // I. Speed is not the trigger; being the last word with no room is. A three-word line's
        // middle word owns 40ms and is still given the full, slow rise.
        val line = lrc("[00:00.000]<00:00.000>aa <00:00.040>bb <00:00.080>cc")
        val wave = KaraokeWave().apply { build(line, 5000) }
        assertEquals(3, wave.count)
        assertEquals("only the final word is ever bounded", Long.MAX_VALUE, wave.availableMs[0])
        assertEquals(Long.MAX_VALUE, wave.availableMs[1])
        assertEquals("and the final word is bounded by the next line's stated time",
            5000L - 80L, wave.availableMs[2])
        for (index in 0..1) {
            assertEquals("word $index keeps the normal speed however fast it is sung", 1f,
                KaraokeWave.compression(wave.availableMs[index], 2), 0f)
        }
        // The last word here has plenty of room too, so nothing on this line is hurried at all.
        assertEquals(1f, KaraokeWave.compression(wave.availableMs[2], 2), 0f)
    }

    @Test
    fun theWaveCarriesNoTimingOfItsOwnIntoTheSemantics() {
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
        // The wave reads those stated starts and invents nothing.
        val wave = KaraokeWave().apply { build(starts, Long.MAX_VALUE) }
        assertEquals(3, wave.count)
        assertEquals(1000L, wave.startMs[0])
        assertEquals(1200L, wave.startMs[1])
        assertEquals(1400L, wave.startMs[2])
    }

    @Test
    fun ownershipStillPassesExactlyOnTheStatedMillisecond() {
        // The hand-over is the source's, not the decoration's: whatever any letter is doing, the
        // next stated time takes the frame on its own millisecond and its fill starts at zero.
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
        // allowed to be quick, because the words genuinely are. Only the decoration is not.
        val line = lrc("[00:00.000]<00:00.000>a <00:00.030>b <00:00.060>c")
        val nextLine = 90L
        val frame = KaraokeFrame()
        frame.resolve(line, 29, nextLine)
        assertTrue(frame.sweep > 0.95f)
        frame.resolve(line, 30, nextLine)
        assertEquals(start(line, 1), frame.wordStart)
        // Every one of them still gets the same unhurried rise: none is a final word short of room.
        val wave = KaraokeWave().apply { build(line, nextLine) }
        assertEquals(3, wave.count)
        assertEquals(1f, KaraokeWave.compression(wave.availableMs[0], 1), 0f)
        assertEquals(1f, KaraokeWave.compression(wave.availableMs[1], 1), 0f)
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
    fun aSplitWordIsOneWaveAcrossTheWholeWordInsteadOfOncePerSyllable() {
        // E. "beau|ti|ful day" states four times and shows two words. The wave has two entries,
        // and the first of them begins at the FIRST of the three syllable times.
        val line = syllables()
        val wave = KaraokeWave().apply { build(line, Long.MAX_VALUE) }
        assertEquals("three stated syllables are one displayed word", 2, wave.count)
        assertEquals("and its wave starts at the first of them", 0L, wave.startMs[0])
        assertEquals("the next displayed word keeps its own stated start", 800L, wave.startMs[1])
        assertEquals("the word covers the whole of the written word",
            0, wave.startOffset[0])
        assertTrue("and runs up to the next one", wave.endOffset[0] >= "beautiful".length)
        assertEquals(wave.endOffset[0], wave.startOffset[1])
        // One continuous rise across it: sampled at the syllable boundaries the source states,
        // the travel never returns to the baseline and never restarts.
        var previous = -1f
        for (position in 0L..800L) {
            val value = KaraokeWave.graphemeLift(position, wave.startMs[0], 0, 1f)
            assertTrue("no restart at $position: $previous -> $value", value >= previous - 0.0001f)
            previous = value
        }
        assertEquals("and it is fully raised well before the word ends", 1f, previous, 0f)
        for (boundary in longArrayOf(200, 400)) {
            assertTrue("the stated syllable at $boundary does not drop it back",
                KaraokeWave.graphemeLift(boundary, wave.startMs[0], 0, 1f) > 0f)
        }
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
            val liftBefore = KaraokeWave.graphemeLift(gap - 1, 0, 0, 1f)
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
        val wave = KaraokeWave().apply { build(line, Long.MAX_VALUE) }
        val frame = KaraokeFrame()
        // Mid-word the decoration is in flight - its later letters are still climbing at 800.
        val stillRising = KaraokeWave.graphemeLift(800, wave.startMs[0], 8, 1f)
        assertTrue("the first word is still rising when the next one starts: $stillRising",
            stillRising < 1f)
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
            // And its decoration is compressed exactly as far as it has to be to be seen at all.
            val wave = KaraokeWave().apply { build(line, nextLine) }
            val scale = KaraokeWave.compression(wave.availableMs[1], 4)
            assertTrue("${tail}ms tail: the last word is raised by the transition, not skipped",
                KaraokeWave.graphemeLift(nextLine, wave.startMs[1], 3, scale) >= 0.999f)
            assertTrue("${tail}ms tail: and compressed only as far as it had to be",
                scale <= 1f && scale >= KaraokeWave.MIN_COMPRESSION)
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
            1f, frame.uniformLift, 0f)
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
        assertEquals("nothing on it is raised either", 0f, frame.uniformLift, 0f)
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
    fun pausingDoesNotAnimateTheLettersBackDown() {
        // The whole of the decoration is a function of the playback position, so there is nothing
        // for a pause to do. The letters stay exactly where the clock left them - no settle, no
        // replay on resume, and no wall-clock animator to cancel.
        val line = pairWithGap(1000)
        val wave = KaraokeWave().apply { build(line, Long.MAX_VALUE) }
        for (position in longArrayOf(60, 120, 380, 700, 2000)) {
            val held = KaraokeWave.graphemeLift(position, wave.startMs[0], 0, 1f)
            for (again in 0..4) {
                assertEquals("a paused position yields the same height every time it is asked",
                    held, KaraokeWave.graphemeLift(position, wave.startMs[0], 0, 1f), 0f)
            }
            // Resuming a millisecond later continues upward; it never starts from rest again.
            assertTrue("resuming continues rather than replaying",
                KaraokeWave.graphemeLift(position + 1, wave.startMs[0], 0, 1f) >= held)
        }
        val frame = KaraokeFrame()
        frame.resolve(line, 380, Long.MAX_VALUE)
        val word = frame.wordStart
        val sweep = frame.sweep
        frame.resolve(line, 380, Long.MAX_VALUE)
        assertEquals("the paused word never moves", word, frame.wordStart)
        assertEquals("the paused fill never moves", sweep, frame.sweep, 0f)
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
        assertEquals(paused.uniformLift, resumed.uniformLift, 0f)
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
        // The decoration is reconstructed just as exactly, because it too is a pure function of
        // the position: a seek backwards lowers the letters again rather than leaving them stuck.
        assertEquals(KaraokeWave.graphemeLift(1150, 1000, 0, 1f),
            KaraokeWave.graphemeLift(1150, 1000, 0, 1f), 0f)
        assertEquals("a position before the word states no lift at all", 0f,
            KaraokeWave.graphemeLift(999, 1000, 0, 1f), 0f)
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

    /**
     * The enumeration the row itself uses to decide what a "letter" of the wave is: walk the text
     * cluster by cluster with [clusterEnd], exactly as LyricsTextView.ensureClusters does.
     */
    private fun waveGraphemes(text: String): List<String> {
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
    fun theWaveRisesThroughWholeGraphemesAndNeverThroughHalfOfOne() {
        // K. A letter of the wave is a user-visible grapheme cluster, never a UTF-16 char. Every
        // piece the row would raise separately has to be a piece the platform segmenter agrees is
        // one, or the wave would be built by cutting a character in half.
        val breaks = java.text.BreakIterator.getCharacterInstance()
        for (text in listOf(
            "Hello world",
            "ȩ́x ábc",
            "😀 and 👨\u200D👩\u200D👧\u200D👦 and 🇪🇹 and 👍🏽",
            "ሰላም ለዓለም",
            "مرحبا بالعالم",
            "नमस्ते दुनिया"
        )) {
            val pieces = waveGraphemes(text)
            assertEquals("the pieces must reassemble the text exactly", text, pieces.joinToString(""))
            breaks.setText(text)
            var offset = 0
            for (piece in pieces) {
                assertTrue("$text: a wave letter must be a whole cluster, got '$piece' at $offset",
                    breaks.isBoundary(offset))
                offset += piece.length
            }
            assertTrue("$text: and the walk must terminate on the end", offset == text.length)
            assertTrue("$text: a wave of one letter is not a wave", pieces.size > 1)
        }
        // A four-person family emoji is ONE letter of the wave, not eleven.
        assertEquals(1, waveGraphemes("👨\u200D👩\u200D👧\u200D👦").size)
        assertEquals(1, waveGraphemes("🇪🇹").size)
        assertEquals(1, waveGraphemes("👍🏽").size)
        assertEquals("a combining mark rides its base", 1, waveGraphemes("ȩ́").size)
        assertEquals("an Amharic syllable is one letter", 4, waveGraphemes("ሰላም ").size)
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
}
