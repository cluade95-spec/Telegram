package org.telegram.messenger.usage;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

/** Read-only adapters for verified actual-delivery replies and scheduled-delivery updates. */
public final class UsageSendObserver {
    private UsageSendObserver() { }
    public static void reply(long account, long dialog, TLObject response) {
        if (response instanceof TLRPC.TL_updateShortSentMessage) {
            TLRPC.TL_updateShortSentMessage sent=(TLRPC.TL_updateShortSentMessage)response;
            confirm(account,dialog,sent.id,sent.date,sent.out);
        } else if (response instanceof TLRPC.Updates) {
            for (TLRPC.Update update:((TLRPC.Updates)response).updates) {
                TLRPC.Message message=null;
                if (update instanceof TL_update.TL_updateNewMessage) message=((TL_update.TL_updateNewMessage)update).message;
                else if (update instanceof TL_update.TL_updateNewChannelMessage) message=((TL_update.TL_updateNewChannelMessage)update).message;
                // NewScheduledMessage, quick-reply templates, and errors are never delivery confirmations.
                if (message!=null && !(message instanceof TLRPC.TL_messageService))
                    confirm(account,MessageObject.getDialogId(message),message.id,message.date,outgoing(account,message));
            }
        }
    }
    private static boolean outgoing(long account, TLRPC.Message message) {
        return message.out || message.from_id!=null && message.from_id.user_id==account;
    }
    public static void scheduledDelivery(long account, TLRPC.Message message) {
        if (message.from_scheduled && !(message instanceof TLRPC.TL_messageService))
            confirm(account,MessageObject.getDialogId(message),message.id,message.date,outgoing(account,message));
    }
    private static void confirm(long account,long dialog,long id,int date,boolean outgoing) {
        if (UsageSendIdentity.eligible(account,id,outgoing,true,false,false))
            UsageTracker.onMessageSent(account,dialog,id,false,date*1000L);
    }
}
