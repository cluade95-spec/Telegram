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
    private static final int ROOMY_BOTTOM = 16;
    private static final int COMPACT_BOTTOM = 12;

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
    public final int totalHeight;

    private ChatLockLayout(boolean pin, boolean showIcon, int digitsTop, int buttonSize, int gapX, int gapY, int headerHeight, int totalHeight) {
        this.pin = pin;
        this.showIcon = showIcon;
        this.digitsTop = digitsTop;
        this.buttonSize = buttonSize;
        this.gapX = gapX;
        this.gapY = gapY;
        this.headerHeight = headerHeight;
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

    public static ChatLockLayout compute(int availableHeightDp, boolean pin) {
        if (pin) {
            final int roomyTotal = ICON_TOP + ICON_SIZE + DIGITS_HEIGHT + 8 + (MAX_BUTTON * KEYS_ROWS + ROOMY_GAP_Y * (KEYS_ROWS - 1)) + ROOMY_BOTTOM;
            if (availableHeightDp >= roomyTotal) {
                final int digitsTop = ICON_TOP + ICON_SIZE;
                return new ChatLockLayout(true, true, digitsTop, MAX_BUTTON, 28, ROOMY_GAP_Y, DIGITS_HEIGHT + 8, roomyTotal);
            }
            final int digitsTop = 10;
            final int header = DIGITS_HEIGHT + 6;
            final int fixed = digitsTop + header + COMPACT_BOTTOM + COMPACT_GAP_Y * (KEYS_ROWS - 1);
            int button = (availableHeightDp - fixed) / KEYS_ROWS;
            button = Math.max(MIN_BUTTON, Math.min(MAX_BUTTON, button));
            final int gapX = button >= 52 ? 28 : button >= 44 ? 22 : 16;
            final int total = fixed + button * KEYS_ROWS;
            return new ChatLockLayout(true, false, digitsTop, button, gapX, COMPACT_GAP_Y, header, total);
        }
        final int roomy = ICON_TOP + ICON_SIZE + 8 + PASSWORD_INPUT_AREA;
        if (availableHeightDp >= roomy) {
            return new ChatLockLayout(false, true, ICON_TOP + ICON_SIZE, MAX_BUTTON, 28, ROOMY_GAP_Y, 0, roomy);
        }
        return new ChatLockLayout(false, false, 0, MAX_BUTTON, 28, ROOMY_GAP_Y, 0, 12 + PASSWORD_INPUT_AREA);
    }
}
