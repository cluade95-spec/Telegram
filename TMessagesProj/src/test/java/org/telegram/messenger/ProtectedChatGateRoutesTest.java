package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Every route into a conversation ends in a fragment whose arguments map to a dialog id through
 * {@link ProtectedDialogIds}; the gate only has to ask the state. These tests walk the argument
 * shapes produced by the different entry paths and make sure a locked protected chat is refused
 * for each of them, and that unlocking one chat does not open any other.
 */
public class ProtectedChatGateRoutesTest {

    private static final long ACCOUNT = 77;
    private static final long USER = 12345L;
    private static final long GROUP_CHAT_ID = 555L;
    private static final long CHANNEL_CHAT_ID = 1_000_000_123L;
    private static final int ENC_ID = 42;

    private long now = 5_000;
    private ProtectedChatsState state;

    @Before
    public void setUp() {
        final Map<String, String> map = new HashMap<>();
        state = new ProtectedChatsState(new ProtectedChatsState.Storage() {
            public String get(String key) {
                return map.get(key);
            }

            public void put(String key, String value) {
                map.put(key, value);
            }

            public void remove(String key) {
                map.remove(key);
            }
        }, () -> now, new ProtectedChatsState.Credential() {
            public boolean hasCredential() {
                return true;
            }

            public ProtectedChatsState.Verification verify(String secret) {
                return "1111".equals(secret) ? ProtectedChatsState.Verification.OK : ProtectedChatsState.Verification.WRONG;
            }

            public boolean biometricAvailable() {
                return false;
            }
        });
        for (long id : new long[]{USER, -GROUP_CHAT_ID, -CHANNEL_CHAT_ID, ProtectedDialogIds.fromArgs(0, 0, ENC_ID, 0, false)}) {
            assertEquals(ProtectedChatsState.Result.OK, state.protect(ACCOUNT, id, state.proofFromPasscode("1111", null)));
            state.relock(ACCOUNT, id);
        }
    }

    private boolean blocked(long userId, long chatId, int encId, long dialogId, boolean allowDialogId) {
        long id = ProtectedDialogIds.fromArgs(userId, chatId, encId, dialogId, allowDialogId);
        return id != 0 && state.isLockedProtected(ACCOUNT, id);
    }

    @Test
    public void mappingMatchesDialogObjectConventions() {
        assertEquals(USER, ProtectedDialogIds.fromArgs(USER, 0, 0, 0, false));
        assertEquals(-GROUP_CHAT_ID, ProtectedDialogIds.fromArgs(0, GROUP_CHAT_ID, 0, 0, false));
        long secret = ProtectedDialogIds.fromArgs(0, 0, ENC_ID, 0, false);
        assertTrue((secret & 0x4000000000000000L) != 0);
        assertEquals(ENC_ID, (int) (secret & 0xffffffffL));
        assertEquals(0, ProtectedDialogIds.fromArgs(0, 0, 0, 99, false));
        assertEquals(99, ProtectedDialogIds.fromArgs(0, 0, 0, 99, true));
    }

    @Test
    public void chatListSearchNotificationLinkAndShareRoutesAreAllGated() {
        // ChatActivity arguments are identical for list rows, search results, notification taps,
        // deep/message/pinned links, forwarded/reply jumps and restored state.
        assertTrue(blocked(USER, 0, 0, 0, false));
        assertTrue(blocked(0, GROUP_CHAT_ID, 0, 0, false));
        assertTrue(blocked(0, CHANNEL_CHAT_ID, 0, 0, false));
        assertTrue(blocked(0, 0, ENC_ID, 0, false));
    }

    @Test
    public void profileMediaAndTopicsRoutesAreGated() {
        assertTrue(blocked(0, 0, 0, USER, true));
        assertTrue(blocked(0, 0, 0, -GROUP_CHAT_ID, true));
        assertTrue(blocked(USER, 0, 0, 0, true));
    }

    @Test
    public void unprotectedChatsAreNotGated() {
        assertFalse(blocked(USER + 1, 0, 0, 0, false));
        assertFalse(blocked(0, GROUP_CHAT_ID + 1, 0, 0, false));
        assertFalse(blocked(0, 0, ENC_ID + 1, 0, false));
        assertFalse("fragments without a dialog are never gated", blocked(0, 0, 0, 0, true));
    }

    @Test
    public void unlockingOneRouteDoesNotOpenOthers() {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACCOUNT, USER, state.proofFromPasscode("1111", null)));
        assertFalse(blocked(USER, 0, 0, 0, false));
        assertFalse("profile of the same chat is opened too", blocked(0, 0, 0, USER, true));
        assertTrue(blocked(0, GROUP_CHAT_ID, 0, 0, false));
        assertTrue(blocked(0, 0, ENC_ID, 0, false));
    }
}
