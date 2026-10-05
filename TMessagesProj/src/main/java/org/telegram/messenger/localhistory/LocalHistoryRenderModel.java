package org.telegram.messenger.localhistory;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
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

    /**
     * Applies the G5 rules in place and returns the same message.
     *
     * @param mediaFile a local file that holds the media of this revision (preserved copy or Telegram's own cache), or null
     * @param mediaState the state of the stored media row when there is no file, used for the label
     */
    public static TLRPC.Message sanitize(TLRPC.Message m, long entryId, long sourceUserId, File mediaFile, int mediaState) {
        TLRPC.MessageMedia original = m.media;
        String label = mediaLabel(original);
        m.attachPath = "";
        TLRPC.MessageMedia local = mediaFile != null ? localMedia(original, mediaFile) : null;
        if (local != null) {
            m.media = local;
            m.attachPath = mediaFile.getAbsolutePath();
        } else {
            m.media = new TLRPC.TL_messageMediaEmpty();
        }
        if (local == null && label != null) {
            int res = mediaState == LocalHistoryMediaState.NOT_DOWNLOADED ? R.string.LocalHistoryMediaNeverDownloaded
                    : mediaState == LocalHistoryMediaState.TOO_LARGE ? R.string.LocalHistoryMediaTooLarge : R.string.LocalHistoryMediaNotSaved;
            String note = LocaleController.formatString(res, label);
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
        boolean hasLocalMedia = m.attachPath != null && !m.attachPath.isEmpty();
        return new MessageObject(account, m, users, null, true, hasLocalMedia, entryId);
    }

    /**
     * A copy of the media that points at a local file only: no remote location, no file reference, no remote
     * thumbnails, so FileLoader never has anything to request. Returns null for media that cannot be shown locally.
     */
    private static TLRPC.MessageMedia localMedia(TLRPC.MessageMedia original, File file) {
        try {
            if (original == null) {
                return null;
            }
            if (original.photo != null) {
                TLRPC.PhotoSize best = null;
                for (TLRPC.PhotoSize size : original.photo.sizes) {
                    if (size != null && size.w > 0 && size.h > 0 && (best == null || size.w * size.h > best.w * best.h)) {
                        best = size;
                    }
                }
                if (best == null) {
                    return null;
                }
                TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
                size.type = "x";
                size.w = best.w;
                size.h = best.h;
                size.size = (int) Math.min(file.length(), Integer.MAX_VALUE);
                size.location = new TLRPC.TL_fileLocationToBeDeprecated();
                TLRPC.TL_photo photo = new TLRPC.TL_photo();
                photo.id = original.photo.id;
                photo.date = original.photo.date;
                photo.file_reference = new byte[0];
                photo.sizes.add(size);
                TLRPC.TL_messageMediaPhoto media = new TLRPC.TL_messageMediaPhoto();
                media.photo = photo;
                media.flags |= 1;
                return media;
            }
            if (original.document != null) {
                TLRPC.Document document = original.document;
                document.file_reference = new byte[0];
                document.access_hash = 0;
                document.dc_id = 0;
                document.localPath = file.getAbsolutePath();
                for (int i = document.thumbs.size() - 1; i >= 0; i--) {
                    TLRPC.PhotoSize thumb = document.thumbs.get(i);
                    if (!(thumb instanceof TLRPC.TL_photoStrippedSize) && !(thumb instanceof TLRPC.TL_photoCachedSize)) {
                        document.thumbs.remove(i);
                    }
                }
                document.video_thumbs.clear();
                return original;
            }
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
        return null;
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
