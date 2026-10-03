package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SwitchRowHitTestTest {

    private static final int WIDTH = 1080, AREA = 228;

    @Test
    public void switchEndTogglesAndBodyOpensDetailsLtr() {
        assertTrue(SwitchRowHitTest.isSwitchArea(false, WIDTH - 10, WIDTH, AREA));
        assertTrue(SwitchRowHitTest.isSwitchArea(false, WIDTH - AREA, WIDTH, AREA));
        assertFalse(SwitchRowHitTest.isSwitchArea(false, WIDTH - AREA - 1, WIDTH, AREA));
        assertFalse(SwitchRowHitTest.isSwitchArea(false, 5, WIDTH, AREA));
    }

    @Test
    public void mirroredInRtl() {
        assertTrue(SwitchRowHitTest.isSwitchArea(true, 10, WIDTH, AREA));
        assertTrue(SwitchRowHitTest.isSwitchArea(true, AREA, WIDTH, AREA));
        assertFalse(SwitchRowHitTest.isSwitchArea(true, AREA + 1, WIDTH, AREA));
        assertFalse(SwitchRowHitTest.isSwitchArea(true, WIDTH - 5, WIDTH, AREA));
    }

    @Test
    public void everyPointIsExactlyOneOfToggleOrDetails() {
        for (boolean rtl : new boolean[]{false, true}) {
            int toggles = 0;
            for (int x = 0; x <= WIDTH; x++) {
                if (SwitchRowHitTest.isSwitchArea(rtl, x, WIDTH, AREA)) {
                    toggles++;
                }
            }
            assertTrue(toggles > 0 && toggles < WIDTH);
        }
    }
}
