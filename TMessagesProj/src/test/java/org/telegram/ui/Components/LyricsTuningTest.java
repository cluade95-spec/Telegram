package org.telegram.ui.Components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The brightness hierarchy of the small background-vocals line against the main line. The line is
 * drawn at its own stage alpha times BACKGROUND_VOCALS_ALPHA, on top of the same sung / unsung
 * stages as the main line, so these are the values that reach the screen.
 */
public class LyricsTuningTest {

    private static float sungMain() {
        return LyricsTuning.ALPHA_SUNG;
    }

    private static float unsungMain() {
        return LyricsTuning.ALPHA_UNSUNG;
    }

    private static float sungSecondary() {
        return LyricsTuning.ALPHA_SUNG * LyricsTuning.BACKGROUND_VOCALS_ALPHA;
    }

    private static float unsungSecondary() {
        return LyricsTuning.ALPHA_UNSUNG * LyricsTuning.BACKGROUND_VOCALS_ALPHA;
    }

    @Test
    public void hierarchyIsUnsungSecondaryThenSungSecondaryThenSungMain() {
        assertTrue(unsungSecondary() < sungSecondary());
        assertTrue(sungSecondary() < sungMain());
    }

    @Test
    public void sungSecondaryIsClearlyWhiterThanBefore() {
        // It used to be 0.4: as dim as the main line's text still to come.
        assertTrue(sungSecondary() > unsungMain() + 0.15f);
        assertTrue(sungSecondary() >= 0.6f);
    }

    @Test
    public void secondaryStaysSubordinateToTheMainLine() {
        // Not fully white, and a visible step below the sung main line.
        assertTrue(sungSecondary() <= 0.8f);
        assertTrue(sungMain() - sungSecondary() >= 0.2f);
    }

    @Test
    public void theFillStillTravelsAcrossTheSecondaryLine() {
        // Sung and unsung differ by a visible step: the line does not read as static.
        assertTrue(sungSecondary() - unsungSecondary() >= 0.3f);
        // The same proportion as the main line, so the sweep reads the same way.
        assertEquals(sungMain() / unsungMain(), sungSecondary() / unsungSecondary(), 1e-4f);
    }

    @Test
    public void unsungSecondaryNeverRisesAboveTheUnsungMainLine() {
        assertTrue(unsungSecondary() < unsungMain());
    }

    @Test
    public void sizeIsUnchanged() {
        assertEquals(0.7f, LyricsTuning.BACKGROUND_VOCALS_SCALE, 0f);
    }
}
