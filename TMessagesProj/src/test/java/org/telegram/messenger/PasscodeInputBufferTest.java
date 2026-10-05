package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Input state of the PIN keypad: a single value, delete always removes the latest digit. */
public class PasscodeInputBufferTest {

    private static PasscodeInputBuffer typed(String digits) {
        PasscodeInputBuffer b = new PasscodeInputBuffer();
        for (char c : digits.toCharArray()) {
            assertTrue(b.append(String.valueOf(c)));
        }
        return b;
    }

    @Test
    public void deleteAfterOneDigit() {
        PasscodeInputBuffer b = typed("1");
        assertTrue(b.eraseLast());
        assertEquals("", b.value());
        assertTrue(b.isEmpty());
    }

    @Test
    public void deleteAfterTwoDigits() {
        PasscodeInputBuffer b = typed("12");
        assertTrue(b.eraseLast());
        assertEquals("1", b.value());
    }

    @Test
    public void deleteAfterThreeDigitsRemovesTheWrongThirdDigitWithoutAnySelection() {
        // device reproduction: 1, 2, wrong 3, then backspace must give "12" immediately
        PasscodeInputBuffer b = typed("123");
        assertTrue(b.eraseLast());
        assertEquals("12", b.value());
        assertEquals(2, b.length());
        assertTrue(b.append("4"));
        assertEquals("124", b.value());
    }

    @Test
    public void deleteWhenFullBeforeValidation() {
        PasscodeInputBuffer b = typed("1234");
        assertTrue(b.isFull());
        assertTrue(b.eraseLast());
        assertEquals("123", b.value());
        assertFalse(b.isFull());
    }

    @Test
    public void repeatedDeleteAndDeleteWhenEmpty() {
        PasscodeInputBuffer b = typed("1234");
        for (int i = 0; i < 4; i++) {
            assertTrue(b.eraseLast());
        }
        assertEquals("", b.value());
        assertFalse(b.eraseLast());
        assertFalse(b.eraseLast());
        assertEquals(0, b.length());
    }

    @Test
    public void inputBeyondTheLengthIsIgnored() {
        PasscodeInputBuffer b = typed("1234");
        assertFalse(b.append("5"));
        assertEquals("1234", b.value());
    }

    @Test
    public void rapidEntryAndDelete() {
        PasscodeInputBuffer b = new PasscodeInputBuffer();
        String ops = "1d2d34d5d6d7dd89";
        StringBuilder expected = new StringBuilder();
        for (char c : ops.toCharArray()) {
            if (c == 'd') {
                b.eraseLast();
                if (expected.length() > 0) {
                    expected.deleteCharAt(expected.length() - 1);
                }
            } else {
                b.append(String.valueOf(c));
                if (expected.length() < 4) {
                    expected.append(c);
                }
            }
            assertEquals(expected.toString(), b.value());
        }
    }

    @Test
    public void wrongPasscodeResetsToEmptyAndAcceptsNewInput() {
        PasscodeInputBuffer b = typed("1234");
        b.clear();
        assertTrue(b.isEmpty());
        assertEquals("", b.value());
        assertTrue(b.append("9"));
        assertEquals("9", b.value());
    }

    @Test
    public void passwordLengthCanBeCustomized() {
        PasscodeInputBuffer b = new PasscodeInputBuffer(2);
        assertTrue(b.append("a"));
        assertTrue(b.append("b"));
        assertFalse(b.append("c"));
    }
}
