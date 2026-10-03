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
        assertEquals(406, l.totalHeight);
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
        ChatLockLayout roomy = ChatLockLayout.compute(406, true);
        ChatLockLayout tight = ChatLockLayout.compute(405, true);
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
}
