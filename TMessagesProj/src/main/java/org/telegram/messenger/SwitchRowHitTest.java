package org.telegram.messenger;

/**
 * Hit test of rows that have both a switch and a details page (same rule as Appearance > Auto-Night
 * Theme): the switch end of the row toggles, the rest of the row opens the details. A touch is never both.
 */
public final class SwitchRowHitTest {

    private SwitchRowHitTest() {
    }

    public static boolean isSwitchArea(boolean rtl, float x, int rowWidth, int switchAreaWidth) {
        return rtl ? x <= switchAreaWidth : x >= rowWidth - switchAreaWidth;
    }
}
