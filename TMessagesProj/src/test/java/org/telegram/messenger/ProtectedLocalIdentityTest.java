package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.telegram.messenger.localhistory.LocalDialogIds;

import java.util.HashMap;
import java.util.Map;

/** The reserved Local History id is the single folder-family id the protection admits (plan B28). */
public class ProtectedLocalIdentityTest {

    private static final long FOLDER_BIT = 0x2000000000000000L;
    private static final long LOCAL = LocalDialogIds.LOCAL_HISTORY;
    private static final long ACC_A = 1001, ACC_B = 2002;

    @Test
    public void localIdIsSupported() {
        assertTrue(ProtectedDialogIds.isSupported(LOCAL));
    }

    @Test
    public void everyOtherFolderIdStaysUnsupported() {
        for (long folder : new long[]{FOLDER_BIT, FOLDER_BIT | 1, FOLDER_BIT | 0xffffffffL, FOLDER_BIT | 0x20004C4800000000L, LOCAL + 1, LOCAL - 1}) {
            assertFalse("folder id " + Long.toHexString(folder), ProtectedDialogIds.isSupported(folder));
        }
    }

    @Test
    public void ordinaryAndZeroIdsKeepTheirMeaning() {
        assertFalse(ProtectedDialogIds.isSupported(0));
        assertTrue(ProtectedDialogIds.isSupported(777));
        assertTrue(ProtectedDialogIds.isSupported(-1001234));
        assertTrue(ProtectedDialogIds.isSupported(0x4000000000000000L | 5)); // secret chat
    }

    // state machine for the local key

    private static class MemStorage implements ProtectedChatsState.Storage {
        final Map<String, String> map = new HashMap<>();

        public String get(String key) {
            return map.get(key);
        }

        public void put(String key, String value) {
            map.put(key, value);
        }

        public void remove(String key) {
            map.remove(key);
        }
    }

    private long now;
    private ProtectedChatsState state;

    @Before
    public void setUp() {
        now = 1_000_000;
        state = new ProtectedChatsState(new MemStorage(), () -> now, new ProtectedChatsState.Credential() {
            public boolean hasCredential() {
                return true;
            }

            public ProtectedChatsState.Verification verify(String s) {
                return "1234".equals(s) ? ProtectedChatsState.Verification.OK : ProtectedChatsState.Verification.WRONG;
            }

            public boolean biometricAvailable() {
                return false;
            }
        });
        assertEquals(ProtectedChatsState.Result.OK, state.enableFeature());
    }

    private ProtectedChatsState.AuthProof proof() {
        return state.proofFromPasscode("1234", null);
    }

    @Test
    public void protectUnlockAndRelockTheLocalChat() {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC_A, LOCAL, proof()));
        assertTrue(state.isProtected(ACC_A, LOCAL));
        assertTrue(state.isUnlocked(ACC_A, LOCAL));
        assertTrue(state.relock(ACC_A, LOCAL));
        assertTrue(state.isLockedProtected(ACC_A, LOCAL));
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACC_A, LOCAL, proof()));
        assertTrue(state.isUnlocked(ACC_A, LOCAL));
        assertTrue(state.relock(ACC_A, LOCAL));
        assertTrue(state.isLockedProtected(ACC_A, LOCAL));
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC_A, LOCAL, proof()));
        assertFalse(state.isProtected(ACC_A, LOCAL));
    }

    @Test
    public void localProtectionIsPerAccount() {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC_A, LOCAL, proof()));
        assertTrue(state.isProtected(ACC_A, LOCAL));
        assertFalse(state.isProtected(ACC_B, LOCAL));
    }

    @Test
    public void clearingTheAccountRemovesTheLocalKeyToo() {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC_A, LOCAL, proof()));
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC_A, 555, proof()));
        state.clearAccount(ACC_A);
        assertFalse(state.isProtected(ACC_A, LOCAL));
        assertFalse(state.isProtected(ACC_A, 555));
    }

    @Test
    public void lockedLocalChatHidesItsContent() {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC_A, LOCAL, proof()));
        assertTrue(state.shouldHideContent(ACC_A, LOCAL));
        assertFalse(state.shouldHideContent(ACC_A, 555));
    }
}
