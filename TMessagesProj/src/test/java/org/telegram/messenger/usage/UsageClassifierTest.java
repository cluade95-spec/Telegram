package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UsageClassifierTest {

    private static UsageClassifier.FragmentFacts chat(long id) {
        UsageClassifier.FragmentFacts f = UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.CHAT);
        f.dialogId = id;
        return f;
    }

    private static UsageClassifier.SurfaceKey c(UsageClassifier.FragmentFacts f) {
        return UsageClassifier.classify(f, new UsageClassifier.OverlayFacts(), false);
    }

    private static void assertKey(UsageSurface s, long dialog, UsageClassifier.SurfaceKey k) {
        assertEquals(s, k.surface);
        assertEquals(dialog, k.dialog);
    }

    @Test
    public void privateChat() {
        UsageClassifier.FragmentFacts f = chat(5);
        f.isUser = true;
        assertKey(UsageSurface.CHAT_PRIVATE, 5, c(f));
        assertEquals(UsageSurface.Category.PRIVATE, c(f).surface.category);
    }

    @Test
    public void secretChatIsPrivateCategory() {
        UsageClassifier.FragmentFacts f = chat(0x4000000000000001L);
        f.isUser = true;
        f.isEncrypted = true;
        assertKey(UsageSurface.CHAT_SECRET, 0x4000000000000001L, c(f));
        assertEquals(UsageSurface.Category.PRIVATE, c(f).surface.category);
    }

    @Test
    public void botSavedAndSelf() {
        UsageClassifier.FragmentFacts f = chat(7);
        f.isUser = true;
        f.isBot = true;
        assertKey(UsageSurface.CHAT_BOT, 7, c(f));
        f = chat(9);
        f.isUser = true;
        f.isSelf = true;
        assertKey(UsageSurface.CHAT_SAVED, 9, c(f));
        f = chat(-100);
        f.chatMode = UsageClassifier.MODE_SAVED;
        assertKey(UsageSurface.CHAT_SAVED, -100, c(f));
        assertEquals(UsageSurface.Category.OTHER, UsageSurface.CHAT_SAVED.category);
    }

    @Test
    public void groupsChannelsAndTopics() {
        assertKey(UsageSurface.CHAT_GROUP, -5, c(chat(-5)));
        UsageClassifier.FragmentFacts f = chat(-6);
        f.isChannel = true;
        assertKey(UsageSurface.CHAT_CHANNEL, -6, c(f));
        UsageClassifier.FragmentFacts t = UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.TOPICS);
        t.dialogId = -7;
        assertKey(UsageSurface.CHAT_GROUP, -7, c(t));
    }

    @Test
    public void chatModes() {
        UsageClassifier.FragmentFacts f = chat(5);
        f.isUser = true;
        f.chatMode = UsageClassifier.MODE_SEARCH;
        assertKey(UsageSurface.SEARCH, 0, c(f));
        for (int m : new int[]{UsageClassifier.MODE_QUICK_REPLIES, UsageClassifier.MODE_EDIT_BUSINESS_LINK, UsageClassifier.MODE_WELCOME_MESSAGES}) {
            f.chatMode = m;
            assertKey(UsageSurface.SETTINGS, 0, c(f));
        }
        for (int m : new int[]{UsageClassifier.MODE_SCHEDULED, UsageClassifier.MODE_PINNED, UsageClassifier.MODE_SUGGESTIONS}) {
            f.chatMode = m;
            assertKey(UsageSurface.CHAT_PRIVATE, 5, c(f));
        }
    }

    @Test
    public void dialogsListSearchAndPicker() {
        UsageClassifier.FragmentFacts f = UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.DIALOGS);
        assertKey(UsageSurface.CHAT_LIST, 0, c(f));
        f.searchShown = true;
        assertKey(UsageSurface.SEARCH, 0, c(f));
        f.selectMode = true;
        assertKey(UsageSurface.OTHER, 0, c(f));
    }

    @Test
    public void profileSettingsAndOthers() {
        assertKey(UsageSurface.PROFILE, 0, c(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.PROFILE)));
        assertKey(UsageSurface.SETTINGS, 0, c(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.SETTINGS)));
        assertKey(UsageSurface.OTHER, 0, c(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.CALL_LOG)));
        assertKey(UsageSurface.OTHER, 0, c(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.CONTACTS)));
        assertKey(UsageSurface.OTHER, 0, c(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.OTHER)));
        assertKey(UsageSurface.OTHER, 0, c(null));
    }

    @Test
    public void settingsContextOnlyAppliesToUnknownScreens() {
        UsageClassifier.OverlayFacts o = new UsageClassifier.OverlayFacts();
        assertKey(UsageSurface.SETTINGS, 0, UsageClassifier.classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.OTHER), o, true));
        assertKey(UsageSurface.OTHER, 0, UsageClassifier.classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.CONTACTS), o, true));
        UsageClassifier.FragmentFacts f = chat(5);
        f.isUser = true;
        assertKey(UsageSurface.CHAT_PRIVATE, 5, UsageClassifier.classify(f, o, true));
        assertKey(UsageSurface.PROFILE, 0, UsageClassifier.classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.PROFILE), o, true));
    }

    @Test
    public void localHistoryScreensAreTheLocalHistorySurface() {
        assertKey(UsageSurface.LOCAL_HISTORY, 0, c(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.LOCAL_HISTORY)));
        UsageClassifier.OverlayFacts o = new UsageClassifier.OverlayFacts();
        o.mediaViewer = true;
        assertKey(UsageSurface.MEDIA_VIEWER, 0, UsageClassifier.classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.LOCAL_HISTORY), o, false));
        assertKey(UsageSurface.LOCAL_HISTORY, 0, UsageClassifier.classify(UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.LOCAL_HISTORY), null, true));
    }

    @Test
    public void overlayPriority() {
        UsageClassifier.FragmentFacts f = chat(5);
        f.isUser = true;
        UsageClassifier.OverlayFacts o = new UsageClassifier.OverlayFacts();
        o.instantView = true;
        assertKey(UsageSurface.MEDIA_VIEWER, 0, UsageClassifier.classify(f, o, false));
        o.stories = true;
        assertKey(UsageSurface.STORIES, 0, UsageClassifier.classify(f, o, false));
        o.mediaViewer = true;
        assertKey(UsageSurface.MEDIA_VIEWER, 0, UsageClassifier.classify(f, o, false));
        o.passcode = true;
        assertKey(UsageSurface.OTHER, 0, UsageClassifier.classify(f, o, false));
        o.callConnected = true;
        assertKey(UsageSurface.CALL, 0, UsageClassifier.classify(f, o, false));
    }

    @Test
    public void nonChatSurfacesNeverCarryADialog() {
        assertEquals(0, new UsageClassifier.SurfaceKey(UsageSurface.SETTINGS, 99).dialog);
        assertEquals(99, new UsageClassifier.SurfaceKey(UsageSurface.CHAT_GROUP, 99).dialog);
    }
}
