package org.telegram.ui.Components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The brightness of the small background-vocals line against the main line, as numbers: the part
 * not yet sung is exactly what it always was, the sung part is whiter, and it stays below the sung
 * main line.
 */
public class LyricsTuningTest {

    /** Before the correction passes: one multiplier for both ends. */
    private static final float ORIGINAL_BACKGROUND_ALPHA = 0.4f;

    private static float sungMain() {
        return LyricsTuning.ALPHA_SUNG;
    }

    private static float unsungMain() {
        return LyricsTuning.ALPHA_UNSUNG;
    }

    private static float sungSecondary() {
        return LyricsTuning.ALPHA_SUNG * LyricsTuning.BACKGROUND_VOCALS_SUNG_ALPHA;
    }

    private static float unsungSecondary() {
        return LyricsTuning.ALPHA_UNSUNG * LyricsTuning.BACKGROUND_VOCALS_ALPHA;
    }

    @Test
    public void theUnsungEndpointIsExactlyTheOriginalValue() {
        assertEquals(ORIGINAL_BACKGROUND_ALPHA, LyricsTuning.BACKGROUND_VOCALS_ALPHA, 0f);
        assertEquals(0.16f, unsungSecondary(), 1e-6f);
        assertEquals(LyricsTuning.ALPHA_UNSUNG * ORIGINAL_BACKGROUND_ALPHA, unsungSecondary(), 0f);
    }

    @Test
    public void theSungEndpointIsStrongerThanItWas() {
        float original = LyricsTuning.ALPHA_SUNG * ORIGINAL_BACKGROUND_ALPHA;
        assertEquals(0.4f, original, 1e-6f);
        assertEquals(0.65f, sungSecondary(), 1e-6f);
        assertTrue(sungSecondary() > original);
        // It used to be as dim as the main line's text still to come; now it is clearly above it.
        assertTrue(sungSecondary() > unsungMain() + 0.2f);
    }

    @Test
    public void sungSecondaryStaysBelowTheSungMainLine() {
        assertEquals(1.0f, sungMain(), 0f);
        assertTrue(sungSecondary() < sungMain());
        assertTrue(sungMain() - sungSecondary() >= 0.3f);
    }

    @Test
    public void hierarchyIsUnsungSecondaryThenSungSecondaryThenSungMain() {
        assertTrue(unsungSecondary() < sungSecondary());
        assertTrue(sungSecondary() < sungMain());
        assertTrue(unsungSecondary() < unsungMain());
    }

    @Test
    public void theFillStillTravelsAcrossTheSecondaryLine() {
        // From 0.16 to 0.65: a visible step.
        assertTrue(sungSecondary() - unsungSecondary() >= 0.4f);
    }

    @Test
    public void sizeIsUnchanged() {
        assertEquals(0.7f, LyricsTuning.BACKGROUND_VOCALS_SCALE, 0f);
    }
}
