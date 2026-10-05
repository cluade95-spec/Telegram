package org.telegram.messenger.localhistory;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;

/**
 * Turns an archived revision into a message that is safe to render (plan B15 "network safety", guard G5):
 * not outgoing, not unread, nothing that could ask the server (views, replies, reactions, markup, reply headers,
 * remote media locations). Media is replaced by a text label until it is preserved locally.
 */
public final class LocalHistoryRenderModel {

    private LocalHistoryRenderModel() {
    }

    public static TLRPC.Message deserialize(byte[] data, String fallbackText, int date) {
        TLRPC.Message message = null;
        if (data != null && data.length > 4) {
            try {
                SerializedData in = new SerializedData(data);
                message = TLRPC.Message.TLdeserialize(in, in.readInt32(false), false);
            } catch (Throwable ignore) {
            }
        }
        if (message == null) {
            TLRPC.TL_message plain = new TLRPC.TL_message();
            plain.message = fallbackText == null ? "" : fallbackText;
            plain.date = date;
            message = plain;
        }
        return message;
    }

    /** Applies the G5 rules in place and returns the same message. */
    public static TLRPC.Message sanitize(TLRPC.Message m, long entryId, long sourceUserId) {
        String label = mediaLabel(m.media);
        m.media = new TLRPC.TL_messageMediaEmpty();
        if (label != null) {
            String note = LocaleController.formatString(R.string.LocalHistoryMediaNotSaved, label);
            m.message = (m.message == null || m.message.isEmpty()) ? note : m.message + "\n" + note;
        } else if (m.message == null) {
            m.message = "";
        }
        m.out = false;
        m.unread = false;
        m.media_unread = false;
        m.mentioned = false;
        m.post = false;
        m.silent = true;
        m.views = 0;
        m.forwards = 0;
        m.replies = null;
        m.reactions = null;
        m.reply_markup = null;
        m.reply_to = null;
        m.replyMessage = null;
        m.via_bot_id = 0;
        m.via_business_bot_id = 0;
        m.grouped_id = 0;
        m.send_state = 0;
        m.ttl = 0;
        m.ttl_period = 0;
        m.destroyTime = 0;
        m.attachPath = "";
        m.id = (int) Math.min(entryId, Integer.MAX_VALUE);
        m.dialog_id = LocalDialogIds.LOCAL_HISTORY;
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = sourceUserId;
        m.peer_id = peer;
        TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
        from.user_id = sourceUserId;
        m.from_id = from;
        return m;
    }

    public static MessageObject toMessageObject(int account, TLRPC.Message m, long entryId, HashMap<Long, TLRPC.User> users) {
        return new MessageObject(account, m, users, null, true, false, entryId);
    }

    private static String mediaLabel(TLRPC.MessageMedia media) {
        if (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage) {
            return null;
        }
        if (media.photo != null) {
            return LocaleController.getString(R.string.AttachPhoto);
        }
        if (media.document != null) {
            return documentLabel(media.document);
        }
        if (media.geo != null) {
            return LocaleController.getString(R.string.AttachLocation);
        }
        if (media.phone_number != null) {
            return LocaleController.getString(R.string.AttachContact);
        }
        return LocaleController.getString(R.string.AttachDocument);
    }

    private static String documentLabel(TLRPC.Document document) {
        if (MessageObject.isStickerDocument(document)) {
            return LocaleController.getString(R.string.AttachSticker);
        }
        if (MessageObject.isVoiceDocument(document)) {
            return LocaleController.getString(R.string.AttachAudio);
        }
        if (MessageObject.isRoundVideoDocument(document)) {
            return LocaleController.getString(R.string.AttachRound);
        }
        if (MessageObject.isGifDocument(document)) {
            return LocaleController.getString(R.string.AttachGif);
        }
        if (MessageObject.isVideoDocument(document)) {
            return LocaleController.getString(R.string.AttachVideo);
        }
        if (MessageObject.isMusicDocument(document)) {
            return LocaleController.getString(R.string.AttachMusic);
        }
        return LocaleController.getString(R.string.AttachDocument);
    }
}
