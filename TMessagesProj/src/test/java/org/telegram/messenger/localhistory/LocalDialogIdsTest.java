package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalDialogIdsTest {

    private static final long FOLDER_BIT = 0x2000000000000000L; // DialogObject.makeFolderDialogId
    private static final long ENCRYPTED_BIT = 0x4000000000000000L;

    @Test
    public void onlyTheReservedValueIsLocal() {
        assertTrue(LocalDialogIds.isLocal(LocalDialogIds.LOCAL_HISTORY));
        for (long id : new long[]{0, 1, -1, 777000, (1L << 52) - 1, -(1L << 52), Long.MAX_VALUE, Long.MIN_VALUE, FOLDER_BIT, FOLDER_BIT | 1}) {
            assertFalse("id " + id, LocalDialogIds.isLocal(id));
        }
    }

    @Test
    public void doesNotCollideWithUsersChatsSecretChatsOrFolders() {
        long id = LocalDialogIds.LOCAL_HISTORY;
        assertTrue(id > (1L << 52)); // users are below 2^52
        assertTrue(id > 0); // chats and channels are negative
        assertEquals(0, id & ENCRYPTED_BIT); // not a secret chat
        assertTrue((id & FOLDER_BIT) != 0); // same family as folder rows, which is why Protected Chats admits it explicitly
        // folder ids for f >= 0 have bits 32-60 all zero; ours does not
        for (int f : new int[]{0, 1, 2, Integer.MAX_VALUE}) {
            assertTrue((FOLDER_BIT | (f & 0xffffffffL)) != id);
        }
        // folder ids for f < 0 sign-extend into the sign bit
        for (int f : new int[]{-1, Integer.MIN_VALUE}) {
            assertTrue((FOLDER_BIT | (long) f) < 0);
        }
        for (int enc = 0; enc < 100000; enc += 997) {
            assertTrue((ENCRYPTED_BIT | (enc & 0xffffffffL)) != id);
        }
    }

    @Test
    public void guardReturnsTheEmptyObjectForTheLocalIdOnly() {
        assertEquals("empty", LocalDialogIds.guard(LocalDialogIds.LOCAL_HISTORY, "empty", () -> "real"));
        assertEquals("real", LocalDialogIds.guard(5, "empty", () -> "real"));
    }

    @Test
    public void guardNeverEvaluatesTheRealSupplierForTheLocalId() {
        LocalDialogIds.guard(LocalDialogIds.LOCAL_HISTORY, "empty", () -> {
            throw new AssertionError("the real lookup must not run");
        });
    }
}
