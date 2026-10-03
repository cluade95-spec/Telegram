package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The colours the small background-vocals line is filled between: the unsung end is what it always
 * was, the sung end is whiter and still below the sung main line, and the fill moves between them.
 */
public class LyricsBackgroundVocalsTest {

    private static final int WHITE = 0xFFFFFFFF;

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    /** AudioPlayerAlert.lyricsAlphaColor: the colour's own alpha scaled by the stage. */
    private static int stageColor(int color, float stageAlpha) {
        return LyricsBackgroundVocals.scaleAlpha(color, stageAlpha);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** The main line's colours at a focus (0 inactive .. 1 active), as the player computes them. */
    private static int mainSung(int sweep, float focus) {
        return stageColor(sweep, lerp(org.telegram.ui.Components.LyricsTuning.ALPHA_INACTIVE, org.telegram.ui.Components.LyricsTuning.ALPHA_SUNG, focus));
    }

    private static int mainUnsung(int sweep, float focus) {
        return stageColor(sweep, lerp(org.telegram.ui.Components.LyricsTuning.ALPHA_INACTIVE, org.telegram.ui.Components.LyricsTuning.ALPHA_UNSUNG, focus));
    }

    /** What drawing used to do to the small line's colour: the whole line at 0.4 of the stage colour. */
    private static int originalEnd(int mainArgb) {
        return Math.round(alpha(mainArgb) * 0.4f);
    }

    @Test
    public void activeLineEndpointsAreTheExpectedValues() {
        int sung = LyricsBackgroundVocals.sung(mainSung(WHITE, 1f));
        int unsung = LyricsBackgroundVocals.unsung(mainUnsung(WHITE, 1f));
        assertEquals(255, alpha(mainSung(WHITE, 1f)));
        assertEquals(102, alpha(mainUnsung(WHITE, 1f)));
        assertEquals(166, alpha(sung));      // 0.65 of the sung main line
        assertEquals(41, alpha(unsung));     // 0.4 of the main line's text still to come (0.16)
    }

    @Test
    public void unsungEndIsExactlyWhatItWasBeforeEveryStageOfTheFocus() {
        for (float focus = 0f; focus <= 1.0001f; focus += 0.05f) {
            int main = mainUnsung(WHITE, focus);
            assertEquals("focus " + focus, originalEnd(main), alpha(LyricsBackgroundVocals.unsung(main)));
        }
    }

    @Test
    public void sungEndIsStrongerThanItWasAndStillBelowTheSungMainLine() {
        for (float focus = 0.1f; focus <= 1.0001f; focus += 0.05f) {
            int main = mainSung(WHITE, focus);
            int sung = LyricsBackgroundVocals.sung(main);
            assertTrue("stronger than before at " + focus, alpha(sung) > originalEnd(main));
            assertTrue("below the sung main line at " + focus, alpha(sung) < alpha(main));
        }
    }

    @Test
    public void hierarchyHoldsAsColoursAtFullFocus() {
        int unsung = alpha(LyricsBackgroundVocals.unsung(mainUnsung(WHITE, 1f)));
        int sung = alpha(LyricsBackgroundVocals.sung(mainSung(WHITE, 1f)));
        int main = alpha(mainSung(WHITE, 1f));
        assertTrue(unsung < sung);
        assertTrue(sung < main);
    }

    @Test
    public void theFillMovesBetweenTheTwoEndpoints() {
        int unsung = LyricsBackgroundVocals.unsung(mainUnsung(WHITE, 1f));
        int sung = LyricsBackgroundVocals.sung(mainSung(WHITE, 1f));
        // A gradient is linear between its two colours: the alpha across the fill edge.
        int previous = alpha(sung);
        assertEquals(166, previous);
        for (int step = 1; step <= 20; step++) {
            float t = step / 20f;
            int at = Math.round(lerp(alpha(sung), alpha(unsung), t));
            assertTrue(at <= previous);
            assertTrue(at >= alpha(unsung) && at <= alpha(sung));
            previous = at;
        }
        assertEquals(41, previous);
        // Half way along the edge it is half way between the two, not between the old ones.
        assertEquals(Math.round((166 + 41) / 2f), Math.round(lerp(alpha(sung), alpha(unsung), 0.5f)));
    }

    @Test
    public void onlyTheAlphaChanges() {
        int sweep = 0xFF20A0E0;
        int sung = LyricsBackgroundVocals.sung(mainSung(sweep, 1f));
        int unsung = LyricsBackgroundVocals.unsung(mainUnsung(sweep, 1f));
        assertEquals(0x20A0E0, sung & 0xFFFFFF);
        assertEquals(0x20A0E0, unsung & 0xFFFFFF);
    }

    @Test
    public void scaleAlphaClampsAndRounds() {
        assertEquals(0x80FFFFFF, LyricsBackgroundVocals.scaleAlpha(0xFFFFFFFF, 0.5f));
        assertEquals(0xFFFFFFFF, LyricsBackgroundVocals.scaleAlpha(0xFFFFFFFF, 2f));
        assertEquals(0x00FFFFFF, LyricsBackgroundVocals.scaleAlpha(0xFFFFFFFF, -1f));
        assertEquals(0x00123456, LyricsBackgroundVocals.scaleAlpha(0x00123456, 0.65f));
    }
}
