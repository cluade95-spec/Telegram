package org.telegram.messenger.localhistory;

/**
 * Single decision point for "may this source message enter Local History" (plan B3). Pure Java: callers
 * translate TLRPC objects into {@link SourceMessage} and {@link UserFacts}.
 */
public final class LocalHistoryEligibility {

    private static final long ENCRYPTED_BIT = 0x4000000000000000L;
    private static final long FOLDER_BIT = 0x2000000000000000L;
    private static final long REPLY_BOT = 1271266957L;
    private static final long ANONYMOUS = 2666000L;
    private static final long VERIFY = 489000L;
    private static final int TTL_EXPIRY_SLACK_SEC = 60;

    private LocalHistoryEligibility() {
    }

    /** What the rules need to know about a stored message. */
    public static final class SourceMessage {
        public final boolean out;
        /** user id of a TL_peerUser from_id, or 0 when from_id is null (or not a user peer, see fromIsOtherPeer). */
        public final long fromUserId;
        /** from_id is set to something other than a user peer (chat, channel). */
        public final boolean fromIsOtherPeer;
        /** TL_message, not TL_messageService / TL_messageEmpty. */
        public final boolean realMessage;
        /** media.ttl_seconds, 0 when there is none. */
        public final int mediaTtlSeconds;
        public final int ttlPeriod;
        public final int date;

        public SourceMessage(boolean out, long fromUserId, boolean fromIsOtherPeer, boolean realMessage, int mediaTtlSeconds, int ttlPeriod, int date) {
            this.out = out;
            this.fromUserId = fromUserId;
            this.fromIsOtherPeer = fromIsOtherPeer;
            this.realMessage = realMessage;
            this.mediaTtlSeconds = mediaTtlSeconds;
            this.ttlPeriod = ttlPeriod;
            this.date = date;
        }
    }

    /** Resolved from the user cache by the caller; null user is passed as {@code null}. */
    public static final class UserFacts {
        public final boolean bot;
        public final boolean self;
        public final boolean deleted;

        public UserFacts(boolean bot, boolean self, boolean deleted) {
            this.bot = bot;
            this.self = self;
            this.deleted = deleted;
        }
    }

    public static boolean isUserDialog(long dialogId) {
        return dialogId > 0 && (dialogId & ENCRYPTED_BIT) == 0 && (dialogId & FOLDER_BIT) == 0;
    }

    public static boolean isServiceUser(long id) {
        return id == 333000 || id == 777000 || id == 42777;
    }

    /**
     * @param forDeletion true when deciding about a removal; an auto-delete (ttl_period) expiry is not "the other side deleted it".
     */
    public static boolean isEligible(long selfId, long sourceDialogId, SourceMessage m, UserFacts user, int nowSec, boolean forDeletion) {
        if (m == null || user == null) {
            return false;
        }
        if (LocalDialogIds.isLocal(sourceDialogId) || !isUserDialog(sourceDialogId) || sourceDialogId == selfId) {
            return false;
        }
        if (m.out || m.fromIsOtherPeer || (m.fromUserId != 0 && m.fromUserId != sourceDialogId)) {
            return false;
        }
        if (!m.realMessage) {
            return false;
        }
        if (user.bot || user.self) {
            return false;
        }
        if (isServiceUser(sourceDialogId) || sourceDialogId == REPLY_BOT || sourceDialogId == 708513L || sourceDialogId == VERIFY || sourceDialogId == ANONYMOUS) {
            return false;
        }
        if (m.mediaTtlSeconds != 0) {
            return false;
        }
        if (forDeletion && m.ttlPeriod != 0 && !(nowSec < m.date + m.ttlPeriod - TTL_EXPIRY_SLACK_SEC)) {
            return false;
        }
        return true;
    }
}
