package org.telegram.messenger;

/**
 * Pure state of the PIN entered on the app lock keypad. There is one input state, no selected
 * position: append adds after the last digit, erase always removes the most recent digit.
 * Used by the digit display of {@code PasscodeView} for the app lock and for chat authentication.
 */
public final class PasscodeInputBuffer {

    public static final int PIN_LENGTH = 4;

    private final StringBuilder digits = new StringBuilder(PIN_LENGTH);
    private final int maxLength;

    public PasscodeInputBuffer() {
        this(PIN_LENGTH);
    }

    public PasscodeInputBuffer(int maxLength) {
        this.maxLength = maxLength;
    }

    /** @return true if the character was added, false when the buffer is already full. */
    public boolean append(String c) {
        if (digits.length() >= maxLength) {
            return false;
        }
        digits.append(c);
        return true;
    }

    /** Removes the most recently entered character. @return false when already empty. */
    public boolean eraseLast() {
        if (digits.length() == 0) {
            return false;
        }
        digits.deleteCharAt(digits.length() - 1);
        return true;
    }

    public void clear() {
        digits.setLength(0);
    }

    public int length() {
        return digits.length();
    }

    public boolean isFull() {
        return digits.length() >= maxLength;
    }

    public boolean isEmpty() {
        return digits.length() == 0;
    }

    public String value() {
        return digits.toString();
    }
}
