package org.telegram.messenger.usage;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class UsageClassifierTest {
    private UsageClassifier.FragmentFacts chat(long dialog, int mode, boolean secret, boolean self, boolean bot, boolean broadcast, boolean mono) {
        return new UsageClassifier.FragmentFacts(UsageClassifier.Kind.CHAT, dialog, mode, secret, self, bot, broadcast, mono, false, false);
    }
    private SurfaceKey classify(UsageClassifier.FragmentFacts facts, boolean origin) {
        return UsageClassifier.classify(101, facts, null, origin);
    }

    @Test public void chatsUseStableIdentitiesAndParentDialogs() {
        Object[][] cases = {
            {chat(7,0,false,false,false,false,false), UsageSurface.CHAT_PRIVATE},
            {chat(0x4000000000000000L|9,0,true,false,false,false,false), UsageSurface.CHAT_SECRET},
            {chat(7,0,false,false,true,false,false), UsageSurface.CHAT_BOT},
            {chat(101,0,false,true,false,false,false), UsageSurface.CHAT_SAVED},
            {chat(101,3,false,false,false,false,false), UsageSurface.CHAT_SAVED},
            {chat(-8,0,false,false,false,false,false), UsageSurface.CHAT_GROUP},
            {chat(-8,1,false,false,false,false,false), UsageSurface.CHAT_GROUP},
            {chat(-9,0,false,false,false,true,false), UsageSurface.CHAT_CHANNEL},
            {chat(-10,0,false,false,false,false,true), UsageSurface.CHAT_CHANNEL}
        };
        for (Object[] item : cases) {
            UsageClassifier.FragmentFacts facts=(UsageClassifier.FragmentFacts)item[0];
            SurfaceKey result=classify(facts,true);
            assertEquals(item[1],result.surface);
            assertEquals(facts.dialog,result.dialogId);
            assertEquals(101,result.accountUserId);
        }
    }

    @Test public void modesOverrideConversationAndSelectionIsOther() {
        assertEquals(UsageSurface.SEARCH,classify(chat(7,7,false,false,false,false,false),true).surface);
        for(int mode:new int[]{5,6,9}) assertEquals(UsageSurface.SETTINGS,classify(chat(7,mode,false,false,false,false,false),false).surface);
        assertEquals(UsageSurface.CHAT_LIST,classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.LIST),true).surface);
        assertEquals(UsageSurface.CHAT_LIST,classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.TOPICS),true).surface);
        assertEquals(UsageSurface.SEARCH,classify(new UsageClassifier.FragmentFacts(UsageClassifier.Kind.LIST,0,0,false,false,false,false,false,true,false),true).surface);
        assertEquals(UsageSurface.OTHER,classify(new UsageClassifier.FragmentFacts(UsageClassifier.Kind.LIST,0,0,false,false,false,false,false,true,true),true).surface);
    }

    @Test public void settingsOriginAppliesOnlyToGenericPages() {
        assertEquals(UsageSurface.SETTINGS,classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.OTHER),true).surface);
        assertEquals(UsageSurface.OTHER,classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.OTHER),false).surface);
        assertEquals(UsageSurface.PROFILE,classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.PROFILE),true).surface);
        assertEquals(UsageSurface.OTHER,classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.EXPLICIT_OTHER),true).surface);
        assertEquals(UsageSurface.SEARCH,classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.SEARCH),true).surface);
        assertEquals(UsageSurface.SETTINGS,classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.SETTINGS),false).surface);
        assertEquals(UsageSurface.OTHER,classify(null,true).surface);
    }

    @Test public void overlayPriorityAndCallAccountAreExclusive() {
        UsageClassifier.FragmentFacts facts=chat(7,0,false,false,false,false,false);
        SurfaceKey call=UsageClassifier.classify(101,facts,new UsageClassifier.OverlayFacts(202,true,true,true,true),true);
        assertEquals(new SurfaceKey(202,UsageSurface.CALL,0),call);
        assertEquals(UsageSurface.OTHER,UsageClassifier.classify(101,facts,new UsageClassifier.OverlayFacts(0,true,true,true,true),true).surface);
        assertEquals(UsageSurface.MEDIA_VIEWER,UsageClassifier.classify(101,facts,new UsageClassifier.OverlayFacts(0,true,true,true,false),true).surface);
        assertEquals(UsageSurface.STORIES,UsageClassifier.classify(101,facts,new UsageClassifier.OverlayFacts(0,false,true,true,false),true).surface);
        assertEquals(UsageSurface.MEDIA_VIEWER,UsageClassifier.classify(101,facts,new UsageClassifier.OverlayFacts(0,false,false,true,false),true).surface);
    }
}
