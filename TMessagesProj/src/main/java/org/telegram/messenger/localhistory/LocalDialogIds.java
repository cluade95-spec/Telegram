package org.telegram.messenger.localhistory;

import java.util.function.Supplier;

/**
 * The reserved dialog id of the Local History Chat (docs/implementation-plan.md, B13). It exists only for Protected Chats,
 * Activity and the feature's own screens; Telegram's dialog model, cache4.db and the network layer never see it. The
 * network guards (G1-G3) refuse it defensively in case it ever leaks.
 */
public final class LocalDialogIds {

    private LocalDialogIds() {
    }

    /** Bit 61 set, bits 62-63 clear, 0x4C48 in bits 32-47: collides with no user, chat, secret chat or folder id. */
    public static final long LOCAL_HISTORY = 0x20004C4800000001L;

    public static boolean isLocal(long dialogId) {
        return dialogId == LOCAL_HISTORY;
    }

    /** G1 helper: the empty object for the local id, the real one for everything else. */
    public static <T> T guard(long dialogId, T empty, Supplier<T> real) {
        return isLocal(dialogId) ? empty : real.get();
    }
}
