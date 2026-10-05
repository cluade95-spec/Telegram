package org.telegram.messenger.localhistory;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

/**
 * Android adapter between Telegram's storage events and the pure ledger (plan B7). Both entry points run on the
 * storage thread, synchronously, and must never throw into Telegram's own path.
 */
public class LocalHistoryCapture {

    private static final LocalHistoryCapture[] instances = new LocalHistoryCapture[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;

    private LocalHistoryCapture(int account) {
        currentAccount = account;
    }

    public static LocalHistoryCapture getInstance(int account) {
        LocalHistoryCapture capture = instances[account];
        if (capture == null) {
            synchronized (LocalHistoryCapture.class) {
                capture = instances[account];
                if (capture == null) {
                    instances[account] = capture = new LocalHistoryCapture(account);
                }
            }
        }
        return capture;
    }

    /**
     * An edit update is about to replace {@code oldMessage} with {@code newMessage} in messages_v2.
     * Called by MessagesStorage.putMessages for edit updates only, before the REPLACE.
     */
    public void onEditStored(TLRPC.Message oldMessage, TLRPC.Message newMessage) {
        try {
            if (oldMessage == null || newMessage == null || !LocalHistory.getInstance(currentAccount).isEnabled()) {
                return;
            }
            long dialogId = MessageObject.getDialogId(newMessage);
            if (!LocalHistoryEligibility.isUserDialog(dialogId)) {
                return;
            }
            int now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            if (!eligible(dialogId, newMessage, now, false)) {
                return;
            }
            LocalHistoryDiff.Content before = contentOf(oldMessage);
            LocalHistoryDiff.Content after = contentOf(newMessage);
            if (!LocalHistoryDiff.isContentChange(before, after)) {
                return;
            }
            String name = nameOf(dialogId);
            LocalHistoryLedger.Snapshot b = snapshot(dialogId, oldMessage, before, name);
            LocalHistoryLedger.Snapshot a = snapshot(dialogId, newMessage, after, name);
            if (b == null || a == null) {
                return;
            }
            LocalHistory.getInstance(currentAccount).getLedger().recordEdit(b, a, now);
            FileLog.d("local history: edit captured");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Messages are about to be removed by a remote update (TL_updateDeleteMessages, or a delete push for a user chat).
     * Must run before MessagesStorage.markMessagesAsDeleted and never from the shared local deletion code.
     */
    public void onRemoteDeleteBeforeStorage(long dialogIdOrZero, ArrayList<Integer> mids) {
        try {
            if (mids == null || mids.isEmpty() || !LocalHistory.getInstance(currentAccount).isEnabled()) {
                return;
            }
            if (dialogIdOrZero != 0 && !LocalHistoryEligibility.isUserDialog(dialogIdOrZero)) {
                return;
            }
            ArrayList<TLRPC.Message> rows = MessagesStorage.getInstance(currentAccount).getMessagesForArchiveSync(dialogIdOrZero, mids);
            if (rows.isEmpty()) {
                return;
            }
            int now = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
            List<LocalHistoryLedger.Snapshot> removed = new ArrayList<>();
            for (TLRPC.Message message : rows) {
                long dialogId = message.dialog_id != 0 ? message.dialog_id : MessageObject.getDialogId(message);
                if (!eligible(dialogId, message, now, true)) {
                    continue;
                }
                LocalHistoryLedger.Snapshot s = snapshot(dialogId, message, contentOf(message), nameOf(dialogId));
                if (s != null) {
                    removed.add(s);
                }
            }
            if (!removed.isEmpty()) {
                int n = LocalHistory.getInstance(currentAccount).getLedger().recordDeletions(removed, now);
                FileLog.d("local history: " + n + " removals captured");
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // translation

    private boolean eligible(long dialogId, TLRPC.Message message, int now, boolean forDeletion) {
        TLRPC.User user = userOf(dialogId);
        LocalHistoryEligibility.UserFacts facts = user == null ? null : new LocalHistoryEligibility.UserFacts(user.bot, user.self, user.deleted);
        return LocalHistoryEligibility.isEligible(UserConfig.getInstance(currentAccount).getClientUserId(), dialogId, sourceOf(message), facts, now, forDeletion);
    }

    static LocalHistoryEligibility.SourceMessage sourceOf(TLRPC.Message m) {
        long fromUser = 0;
        boolean other = false;
        if (m.from_id instanceof TLRPC.TL_peerUser) {
            fromUser = m.from_id.user_id;
        } else if (m.from_id != null) {
            other = true;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(m);
        return new LocalHistoryEligibility.SourceMessage(m.out, fromUser, other, m instanceof TLRPC.TL_message, media == null ? 0 : media.ttl_seconds, m.ttl_period, m.date);
    }

    static LocalHistoryDiff.Content contentOf(TLRPC.Message m) {
        StringBuilder entities = new StringBuilder();
        if (m.entities != null) {
            for (TLRPC.MessageEntity e : m.entities) {
                entities.append(e.getClass().getSimpleName()).append(':').append(e.offset).append(':').append(e.length);
                if (e.url != null) {
                    entities.append(':').append(e.url);
                }
                entities.append(';');
            }
        }
        return new LocalHistoryDiff.Content(m.message, entities.toString(), mediaIdentity(MessageObject.getMedia(m)));
    }

    private static String mediaIdentity(TLRPC.MessageMedia media) {
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) {
            return "";
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage) {
            return ""; // preview fill-in is not a content change
        }
        if (media.photo != null) {
            return "photo:" + media.photo.id;
        }
        if (media.document != null) {
            return "doc:" + media.document.id;
        }
        if (media.geo != null) {
            return media.getClass().getSimpleName() + ":" + media.geo.lat + "," + media.geo._long;
        }
        if (media.phone_number != null) {
            return "contact:" + media.phone_number;
        }
        return media.getClass().getSimpleName();
    }

    private static LocalHistoryLedger.Snapshot snapshot(long dialogId, TLRPC.Message m, LocalHistoryDiff.Content content, String name) {
        SerializedData data = new SerializedData();
        m.serializeToStream(data);
        return new LocalHistoryLedger.Snapshot(dialogId, m.id, m.date, m.edit_date, LocalHistoryDiff.contentHash(content), data.toByteArray(), m.message, name);
    }

    private TLRPC.User userOf(long userId) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(userId);
        return user != null ? user : MessagesStorage.getInstance(currentAccount).getUser(userId);
    }

    private String nameOf(long userId) {
        TLRPC.User user = userOf(userId);
        return user == null ? null : UserObject.getUserName(user);
    }
}
