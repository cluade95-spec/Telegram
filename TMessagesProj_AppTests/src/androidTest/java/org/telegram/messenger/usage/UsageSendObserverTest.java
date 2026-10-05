package org.telegram.messenger.usage;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Exercises the production adapter with actual Telegram TL types; requires Android test compilation. */
@RunWith(AndroidJUnit4.class)
public class UsageSendObserverTest {
    private TLRPC.TL_message message(boolean outgoing,boolean scheduled) {
        TLRPC.TL_message m=new TLRPC.TL_message(); m.id=123; m.date=1791190800; m.out=outgoing; m.from_scheduled=scheduled;
        m.peer_id=new TLRPC.TL_peerUser(); m.peer_id.user_id=77; return m;
    }
    @Test public void schedulingAcknowledgmentsNeverDeliverButActualUpdatesDo() {
        List<Long> delivered=new ArrayList<>(); UsageSendObserver.Delivery sink=(uid,dialog,id,date)->delivered.add(id);
        TLRPC.TL_updates response=new TLRPC.TL_updates();
        TL_update.TL_updateNewScheduledMessage scheduling=new TL_update.TL_updateNewScheduledMessage(); scheduling.message=message(true,true);
        response.updates.add(scheduling); UsageSendObserver.reply(11,77,response,sink); assertTrue(delivered.isEmpty());
        TL_update.TL_updateNewMessage sent=new TL_update.TL_updateNewMessage(); sent.message=message(true,true);
        response.updates.clear(); response.updates.add(sent); UsageSendObserver.reply(11,77,response,sink);
        assertEquals(1,delivered.size()); assertEquals(Long.valueOf(123),delivered.get(0));
        delivered.clear(); UsageSendObserver.scheduledDelivery(11,sent.message,sink); assertEquals(1,delivered.size());
    }
    @Test public void ordinaryShortRepliesCountAndIncomingLocalIdsAndNullDoNot() {
        List<Long> delivered=new ArrayList<>(); UsageSendObserver.Delivery sink=(uid,dialog,id,date)->delivered.add(id);
        TLRPC.TL_updateShortSentMessage shortReply=new TLRPC.TL_updateShortSentMessage(); shortReply.out=true; shortReply.id=123;
        UsageSendObserver.reply(11,77,shortReply,sink); assertEquals(1,delivered.size());
        delivered.clear(); UsageSendObserver.reply(11,77,shortReply,true,sink); assertTrue(delivered.isEmpty());
        shortReply.out=false; UsageSendObserver.reply(11,77,shortReply,sink);
        shortReply.out=true; shortReply.id=-123; UsageSendObserver.reply(11,77,shortReply,sink);
        UsageSendObserver.reply(11,77,null,sink); assertTrue(delivered.isEmpty());
        UsageSendObserver.scheduledDelivery(11,message(false,true),sink); assertTrue(delivered.isEmpty());
        UsageSendObserver.scheduledDelivery(11,message(true,false),sink); assertTrue(delivered.isEmpty());
    }
}
