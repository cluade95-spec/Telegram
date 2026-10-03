package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The popup layout must fit the height it is offered; it never relies on scaling a full-screen layout. */
public class ChatLockLayoutTest {

    @Test
    public void roomyPinLayoutWhenThereIsEnoughHeight() {
        ChatLockLayout l = ChatLockLayout.compute(700, true);
        assertTrue(l.showIcon);
        assertEquals(ChatLockLayout.MAX_BUTTON, l.buttonSize);
        assertEquals(414, l.totalHeight);
    }

    @Test
    public void pinLayoutAlwaysFitsWhenAtLeastTheMinimumIsOffered() {
        for (int available = 252; available <= 1400; available++) {
            ChatLockLayout l = ChatLockLayout.compute(available, true);
            assertTrue("height " + available, l.totalHeight <= available);
            assertTrue(l.buttonSize >= ChatLockLayout.MIN_BUTTON && l.buttonSize <= ChatLockLayout.MAX_BUTTON);
        }
    }

    @Test
    public void keypadAndDigitsAreInsideTheContentHeight() {
        for (int available = 252; available <= 900; available += 7) {
            ChatLockLayout l = ChatLockLayout.compute(available, true);
            int keysBottom = l.digitsTop + l.headerHeight + l.keysHeight();
            assertTrue("keys end inside the popup at " + available, keysBottom < l.totalHeight);
            assertTrue(l.digitsTop + ChatLockLayout.DIGITS_HEIGHT <= l.digitsTop + l.headerHeight);
            if (l.showIcon) {
                assertTrue("digits start below the icon", l.digitsTop >= l.iconTop() + ChatLockLayout.ICON_SIZE);
            }
        }
    }

    @Test
    public void smallerHeightDropsTheIconInsteadOfShrinkingEverything() {
        ChatLockLayout roomy = ChatLockLayout.compute(414, true);
        ChatLockLayout tight = ChatLockLayout.compute(413, true);
        assertTrue(roomy.showIcon);
        assertFalse(tight.showIcon);
        assertTrue(tight.buttonSize >= 48);
    }

    @Test
    public void buttonSizeNeverGrowsWhenTheHeightShrinks() {
        int previous = Integer.MAX_VALUE;
        for (int available = 1000; available >= 200; available--) {
            int size = ChatLockLayout.compute(available, true).buttonSize;
            assertTrue(size <= previous);
            previous = size;
        }
    }

    @Test
    public void keypadWidthFitsNarrowPopups() {
        for (int available = 200; available <= 900; available += 11) {
            assertTrue(ChatLockLayout.compute(available, true).keysWidth() <= 3 * ChatLockLayout.MAX_BUTTON + 2 * 28);
        }
    }

    @Test
    public void passwordLayoutFitsAndDropsTheIconWhenShort() {
        ChatLockLayout roomy = ChatLockLayout.compute(400, false);
        assertTrue(roomy.showIcon);
        assertTrue(roomy.totalHeight <= 400);
        ChatLockLayout shortLayout = ChatLockLayout.compute(120, false);
        assertFalse(shortLayout.showIcon);
        assertTrue(shortLayout.totalHeight <= 120);
    }

    /** AndroidUtilities.dp: every value is rounded up on its own. */
    private static ChatLockLayout.Px px(final double density) {
        return value -> value == 0 ? 0 : (int) Math.ceil(density * value);
    }

    private static final double[] DENSITIES = {1.0, 1.5, 2.0, 2.2, 2.625, 2.75, 2.8125, 3.0, 3.5, 4.0};

    @Test
    public void bottomRowIsInsideTheKeypadFrameAtEveryDensity() {
        for (double density : DENSITIES) {
            ChatLockLayout.Px px = px(density);
            for (int available = 252; available <= 900; available += 3) {
                ChatLockLayout l = ChatLockLayout.compute(available, true);
                int lastRowBottom = l.keyTopPx(ChatLockLayout.KEYS_ROWS - 1, px) + px.dp(l.buttonSize);
                int lastColumnRight = l.keyLeftPx(ChatLockLayout.KEYS_COLUMNS - 1, px) + px.dp(l.buttonSize);
                assertTrue("rows inside frame at density " + density + " height " + available, lastRowBottom <= l.keysFrameHeightPx(px));
                assertTrue("columns inside frame at density " + density, lastColumnRight <= l.keysFrameWidthPx(px));
            }
        }
    }

    @Test
    public void theDpTotalAloneWouldHaveCutTheBottomRow() {
        // The reason the frame is summed in pixels: at 2.625 the rounded terms end below the rounded total.
        ChatLockLayout.Px px = px(2.625);
        ChatLockLayout l = ChatLockLayout.compute(700, true);
        int lastRowBottom = l.keyTopPx(ChatLockLayout.KEYS_ROWS - 1, px) + px.dp(l.buttonSize);
        assertTrue(lastRowBottom > px.dp(l.headerHeight + l.keysHeight()));
        assertEquals(lastRowBottom, l.keysFrameHeightPx(px));
    }

    @Test
    public void thereIsAlwaysClearanceUnderTheBottomRow() {
        for (double density : DENSITIES) {
            ChatLockLayout.Px px = px(density);
            for (int available = 252; available <= 900; available += 3) {
                ChatLockLayout l = ChatLockLayout.compute(available, true);
                int keysBottom = px.dp(l.digitsTop) + l.keysFrameHeightPx(px);
                assertEquals(keysBottom + px.dp(l.bottomMargin), l.totalHeightPx(px));
                assertTrue(l.bottomMargin >= 12);
            }
        }
    }

    @Test
    public void roomyPopupGivesTheBottomRowTwiceTheRowGap() {
        ChatLockLayout l = ChatLockLayout.compute(900, true);
        assertEquals(2 * l.gapY, l.bottomMargin);
        assertEquals(l.digitsTop + l.headerHeight + l.keysHeight() + l.bottomMargin, l.totalHeight);
    }

    @Test
    public void pixelTotalFitsTheOfferedHeightOnceTheRoundingIsKeptBack() {
        for (double density : DENSITIES) {
            ChatLockLayout.Px px = px(density);
            for (int offeredPx = (int) (252 * density); offeredPx <= (int) (1100 * density); offeredPx += 5) {
                // What PasscodeView passes in.
                int offeredDp = (int) ((offeredPx - ChatLockLayout.MAX_ROUNDING_PX) / density);
                if (offeredDp < 252) continue;
                ChatLockLayout l = ChatLockLayout.compute(offeredDp, true);
                assertTrue("density " + density + " offered " + offeredPx + " total " + l.totalHeightPx(px),
                        l.totalHeightPx(px) <= offeredPx);
            }
        }
    }

    @Test
    public void buttonSizeAndKeypadStayCompatibleWithTheTallerPopup() {
        ChatLockLayout roomy = ChatLockLayout.compute(414, true);
        assertTrue(roomy.showIcon);
        assertEquals(ChatLockLayout.MAX_BUTTON, roomy.buttonSize);
        assertTrue(roomy.digitsTop >= roomy.iconTop() + ChatLockLayout.ICON_SIZE);
        // One dp below it the icon goes, and the keys keep their size.
        ChatLockLayout tight = ChatLockLayout.compute(413, true);
        assertFalse(tight.showIcon);
        assertEquals(ChatLockLayout.MAX_BUTTON, tight.buttonSize);
    }
}
