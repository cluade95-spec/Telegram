package org.telegram.messenger;

/**
 * Layout metrics (in dp) of the unlock screen when it is shown inside the bottom popup used for
 * protected chats. The full-screen app lock derives its layout from the display size; the popup
 * derives it from the height it is offered. Everything is computed here so it fits intentionally:
 * when the offered height is smaller than the roomy layout, the lock icon is dropped and the keypad
 * buttons get smaller steps, never a scaled-down copy of the full-screen layout.
 */
public final class ChatLockLayout {

    public static final int ICON_SIZE = 58;
    public static final int ICON_TOP = 14;
    public static final int DIGITS_HEIGHT = 50;
    public static final int KEYS_ROWS = 4;
    public static final int KEYS_COLUMNS = 3;

    public static final int MAX_BUTTON = 56;
    public static final int MIN_BUTTON = 36;

    private static final int ROOMY_GAP_Y = 12;
    private static final int COMPACT_GAP_Y = 10;
    /** Clearance under the last keypad row: two row gaps, so the "0" key has the room the rows have. */
    private static final int ROOMY_BOTTOM = 2 * ROOMY_GAP_Y;
    private static final int COMPACT_BOTTOM = 12;

    /**
     * The most a pixel total can exceed its dp total: every dp value is rounded up on its own
     * ({@code AndroidUtilities.dp}), and the keypad column adds the digits top, the header, three
     * row steps, the last key and the bottom clearance.
     */
    public static final int MAX_ROUNDING_PX = 7;

    // password layout
    private static final int PASSWORD_INPUT_AREA = 64;

    public final boolean pin;
    public final boolean showIcon;
    /** Top of the band that holds the PIN digits (and, while empty, the title). */
    public final int digitsTop;
    public final int buttonSize;
    public final int gapX;
    public final int gapY;
    /** Distance from the top of the digits band to the first keypad row. */
    public final int headerHeight;
    /** Clearance between the last keypad row and the bottom edge of the popup. */
    public final int bottomMargin;
    public final int totalHeight;

    /** The dp to pixel conversion of the device ({@code AndroidUtilities::dp}), so pixels are summed the way they are drawn. */
    public interface Px {
        int dp(int value);
    }

    private ChatLockLayout(boolean pin, boolean showIcon, int digitsTop, int buttonSize, int gapX, int gapY, int headerHeight, int bottomMargin, int totalHeight) {
        this.pin = pin;
        this.showIcon = showIcon;
        this.digitsTop = digitsTop;
        this.buttonSize = buttonSize;
        this.gapX = gapX;
        this.gapY = gapY;
        this.headerHeight = headerHeight;
        this.bottomMargin = bottomMargin;
        this.totalHeight = totalHeight;
    }

    public int keysHeight() {
        return buttonSize * KEYS_ROWS + gapY * (KEYS_ROWS - 1);
    }

    public int keysWidth() {
        return buttonSize * KEYS_COLUMNS + gapX * (KEYS_COLUMNS - 1);
    }

    public int iconTop() {
        return ICON_TOP;
    }

    // Pixel geometry. Each dp term is rounded up on its own, so the position of the last key is
    // not dp(headerHeight + 3 * (buttonSize + gapY)): it can end a few pixels lower, and a frame
    // sized by the dp total cut the bottom off the "0" key. The frame and the popup are sized from
    // the same pixel sums the keys are placed with.

    /** Top of the key at {@code row}, from the top of the keypad frame. */
    public int keyTopPx(int row, Px px) {
        return px.dp(headerHeight) + px.dp(buttonSize + gapY) * row;
    }

    /** Left of the key at {@code column}, from the left of the keypad frame. */
    public int keyLeftPx(int column, Px px) {
        return px.dp(buttonSize + gapX) * column;
    }

    /** Height of the keypad frame: the bottom of the last key, exactly. */
    public int keysFrameHeightPx(Px px) {
        return keyTopPx(KEYS_ROWS - 1, px) + px.dp(buttonSize);
    }

    /** Width of the keypad frame: the right edge of the last column, exactly. */
    public int keysFrameWidthPx(Px px) {
        return keyLeftPx(KEYS_COLUMNS - 1, px) + px.dp(buttonSize);
    }

    /** Height of the popup content: the keypad frame and the clearance under it. */
    public int totalHeightPx(Px px) {
        if (!pin) {
            return px.dp(totalHeight);
        }
        return px.dp(digitsTop) + keysFrameHeightPx(px) + px.dp(bottomMargin);
    }

    public static ChatLockLayout compute(int availableHeightDp, boolean pin) {
        if (pin) {
            final int roomyTotal = ICON_TOP + ICON_SIZE + DIGITS_HEIGHT + 8 + (MAX_BUTTON * KEYS_ROWS + ROOMY_GAP_Y * (KEYS_ROWS - 1)) + ROOMY_BOTTOM;
            if (availableHeightDp >= roomyTotal) {
                final int digitsTop = ICON_TOP + ICON_SIZE;
                return new ChatLockLayout(true, true, digitsTop, MAX_BUTTON, 28, ROOMY_GAP_Y, DIGITS_HEIGHT + 8, ROOMY_BOTTOM, roomyTotal);
            }
            final int digitsTop = 10;
            final int header = DIGITS_HEIGHT + 6;
            final int fixed = digitsTop + header + COMPACT_BOTTOM + COMPACT_GAP_Y * (KEYS_ROWS - 1);
            int button = (availableHeightDp - fixed) / KEYS_ROWS;
            button = Math.max(MIN_BUTTON, Math.min(MAX_BUTTON, button));
            final int gapX = button >= 52 ? 28 : button >= 44 ? 22 : 16;
            final int total = fixed + button * KEYS_ROWS;
            return new ChatLockLayout(true, false, digitsTop, button, gapX, COMPACT_GAP_Y, header, COMPACT_BOTTOM, total);
        }
        final int roomy = ICON_TOP + ICON_SIZE + 8 + PASSWORD_INPUT_AREA;
        if (availableHeightDp >= roomy) {
            return new ChatLockLayout(false, true, ICON_TOP + ICON_SIZE, MAX_BUTTON, 28, ROOMY_GAP_Y, 0, 0, roomy);
        }
        return new ChatLockLayout(false, false, 0, MAX_BUTTON, 28, ROOMY_GAP_Y, 0, 0, 12 + PASSWORD_INPUT_AREA);
    }
}
