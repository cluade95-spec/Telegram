package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A long note's glow stops with its line: it is the envelope while the line sings, and none from the
 * moment the line's last sound ends (the list moves on then), however the position got there.
 */
public class LyricsGlowTest {

    // A held last word: starts 10 s, its envelope (the time up to the next line) lasts 2.9 s, the
    // voice stops at 12.5 s and the next line starts at 12.9 s.
    private static final long WORD_START = 10_000;
    private static final long ENVELOPE_MS = 2_900;
    private static final long LINE_END = 12_500;
    private static final long NEXT_LINE = 12_900;
    private static final int GRAPHEMES = 4;
    private static final float STRENGTH = 1.6f;

    /** Up along the first half, back down along the second; the shape is not what is tested. */
    private static float ease(float x) {
        return x <= 0f || x >= 1f ? 0f : (float) Math.sin(Math.PI * x);
    }

    private static long stepMs() {
        return Math.round(ENVELOPE_MS / 2.5 / GRAPHEMES);
    }

    /** The envelope of grapheme {@code i} before anything gates it. */
    private static float envelope(long now, int i) {
        long delay = WORD_START + stepMs() * i;
        return ease((now - delay) / (float) ENVELOPE_MS) * STRENGTH;
    }

    private static float glow(long now, int i, long glowEnd) {
        long delay = WORD_START + stepMs() * i;
        return LyricsGlow.glow(ease((now - delay) / (float) ENVELOPE_MS), STRENGTH, now, glowEnd);
    }

    @Test
    public void whileTheLineSingsTheGlowIsTheEnvelopeUnchanged() {
        for (int i = 0; i < GRAPHEMES; i++) {
            for (long now = WORD_START - 500; now < LINE_END; now += 25) {
                assertEquals("grapheme " + i + " at " + now, envelope(now, i), glow(now, i, LINE_END), 0f);
            }
        }
        assertTrue(glow(WORD_START + ENVELOPE_MS / 2, 0, LINE_END) > 1f);
    }

    @Test
    public void theGlowEndsWhenTheLineBecomesInactive() {
        // The envelope still has a strong glow where the line ends: that is what used to linger.
        assertTrue(envelope(LINE_END, 3) > 0.2f);
        assertEquals(0f, glow(LINE_END, 3, LINE_END), 0f);
        assertEquals(0f, glow(LINE_END, 0, LINE_END), 0f);
        // Right before it, it is still there.
        assertTrue(glow(LINE_END - 1, 3, LINE_END) > 0f);
    }

    @Test
    public void theLastGraphemesTailPastTheNextLineIsGone() {
        long last = WORD_START + stepMs() * (GRAPHEMES - 1) + ENVELOPE_MS;
        assertTrue("the staggered tail runs past the next line", last > NEXT_LINE);
        assertTrue(envelope(NEXT_LINE, GRAPHEMES - 1) > 0f);
        for (long now = LINE_END; now <= last + 500; now += 20) {
            for (int i = 0; i < GRAPHEMES; i++) {
                assertEquals("old row at " + now, 0f, glow(now, i, LINE_END), 0f);
            }
        }
    }

    @Test
    public void anOldRowNeverGlowsAgain() {
        for (long now = LINE_END; now < LINE_END + 60_000; now += 1_000) {
            assertEquals(0f, glow(now, 1, LINE_END), 0f);
        }
    }

    @Test
    public void seekingFromInsideTheWordToAfterTheTransitionShowsNoGlowAtAnyBlendWeight() {
        long from = WORD_START + ENVELOPE_MS / 2;
        long to = NEXT_LINE + 400;
        float fromGlow = glow(from, 1, LINE_END);
        float toGlow = glow(to, 1, LINE_END);
        assertTrue(fromGlow > 1f);
        assertEquals(0f, toGlow, 0f);
        for (float weight = 0f; weight <= 1f; weight += 0.05f) {
            assertEquals("weight " + weight, 0f, LyricsGlow.blendedGlow(fromGlow, toGlow, weight, to, LINE_END), 0f);
        }
    }

    @Test
    public void seekingIntoTheWordFromElsewhereStillBlendsIn() {
        long to = WORD_START + ENVELOPE_MS / 2;
        float toGlow = glow(to, 1, LINE_END);
        assertEquals(0f, LyricsGlow.blendedGlow(0f, toGlow, 0f, to, LINE_END), 0f);
        assertEquals(toGlow / 2f, LyricsGlow.blendedGlow(0f, toGlow, 0.5f, to, LINE_END), 1e-6f);
        assertEquals(toGlow, LyricsGlow.blendedGlow(0f, toGlow, 1f, to, LINE_END), 1e-6f);
    }

    @Test
    public void pausingDuringTheActiveWordHoldsTheGlowAndResumeContinuesIt() {
        long paused = WORD_START + 1_000;
        float held = glow(paused, 0, LINE_END);
        assertTrue(held > 0f);
        // Frames while paused read the same position.
        for (int frame = 0; frame < 200; frame++) {
            assertEquals(held, glow(paused, 0, LINE_END), 0f);
        }
        // Resuming carries on along the envelope and still ends with the line.
        assertEquals(envelope(paused + 300, 0), glow(paused + 300, 0, LINE_END), 0f);
        assertEquals(0f, glow(LINE_END + 1, 0, LINE_END), 0f);
    }

    @Test
    public void pausingAfterTheLineEndedShowsNoGlowEither() {
        long paused = LINE_END + 200;
        for (int frame = 0; frame < 100; frame++) {
            assertEquals(0f, glow(paused, 2, LINE_END), 0f);
        }
    }

    @Test
    public void anOrdinaryEmphasisedWordInTheMiddleOfALineIsNotChanged() {
        // A word held 1.4 s well before the line's end: its whole envelope is inside the singing.
        long start = 4_000, duration = 1_400, lineEnd = 12_500;
        for (long now = start - 300; now <= start + duration + 300; now += 10) {
            float progress = ease((now - start) / (float) duration);
            assertEquals(progress * 1.2f, LyricsGlow.glow(progress, 1.2f, now, lineEnd), 0f);
        }
    }

    @Test
    public void theGlowEndNeverOutlivesTheLineOrItsActivity() {
        // The end of the last sound is when the list moves on.
        assertEquals(12_500, LyricsGlow.glowEnd(12_900, 12_500));
        // A line held over the next one keeps its own, later end (it is also its active end).
        assertEquals(14_000, LyricsGlow.glowEnd(14_000, 14_000));
        // Never after the line stops being active.
        assertEquals(12_900, LyricsGlow.glowEnd(12_900, 13_500));
        // A line with no known end is gated by its active end, as before.
        assertEquals(12_900, LyricsGlow.glowEnd(12_900, LyricsOverlap.UNKNOWN));
        assertEquals(Long.MAX_VALUE, LyricsGlow.glowEnd(Long.MAX_VALUE, LyricsOverlap.UNKNOWN));
    }

    @Test
    public void aRowWithoutAnEndKeepsTheOldBehaviour() {
        long glowEnd = LyricsGlow.glowEnd(Long.MAX_VALUE, LyricsOverlap.UNKNOWN);
        for (long now = WORD_START; now < NEXT_LINE + 3_000; now += 50) {
            assertEquals(envelope(now, 0), glow(now, 0, glowEnd), 0f);
        }
    }
}
