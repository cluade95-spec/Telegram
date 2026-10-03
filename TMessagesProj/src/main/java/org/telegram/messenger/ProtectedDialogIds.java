package org.telegram.messenger;

/**
 * Pure helper that maps the argument conventions used by fragments that show a conversation
 * (ChatActivity: user_id / chat_id / enc_id; ProfileActivity, TopicsFragment: additionally
 * dialog_id) to a dialog id. Every route into a chat (list rows, search, notifications, links,
 * shares, forwards, pinned/reply jumps, restored state, tablet panes) builds one of these argument
 * sets, so the gate keys on this single mapping.
 */
public final class ProtectedDialogIds {

    private ProtectedDialogIds() {
    }

    public static long fromArgs(long userId, long chatId, int encId, long dialogIdArg, boolean allowDialogIdArg) {
        if (userId != 0) {
            return userId;
        }
        if (chatId != 0) {
            return -chatId;
        }
        if (encId != 0) {
            return 0x4000000000000000L | (encId & 0x00000000ffffffffL);
        }
        return allowDialogIdArg ? dialogIdArg : 0;
    }
}
