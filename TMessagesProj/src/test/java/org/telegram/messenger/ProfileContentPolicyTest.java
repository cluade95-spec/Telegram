package org.telegram.messenger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Protection withholds message content from a profile and nothing else. These tests run the lock
 * state of the real protection model through the policy the profile uses, and check, tab by tab,
 * what a profile still offers: Stories, Gifts, members, common groups, recommendations and bot
 * previews never depend on protection, a chat being locked or unlocked, or the app lock.
 */
public class ProfileContentPolicyTest {

    private static final long ACCOUNT = 1L;
    private static final long SELF = 1000L;
    private static final long FRIEND = 2000L;
    private static final long OTHER = 3000L;
    private static final long GROUP = -4000L;

    /** Everything that is not a tab of messages. */
    private static final int[] UNRELATED_TABS = {
            ProfileContentPolicy.TAB_COMMON_GROUPS, ProfileContentPolicy.TAB_GROUPUSERS, ProfileContentPolicy.TAB_STORIES,
            ProfileContentPolicy.TAB_ARCHIVED_STORIES, ProfileContentPolicy.TAB_RECOMMENDED_CHANNELS,
            ProfileContentPolicy.TAB_BOT_PREVIEWS, ProfileContentPolicy.TAB_GIFTS};

    private static final int[] MESSAGE_TABS_OF_THE_CHAT = {
            ProfileContentPolicy.TAB_PHOTOVIDEO, ProfileContentPolicy.TAB_FILES, ProfileContentPolicy.TAB_VOICE,
            ProfileContentPolicy.TAB_LINKS, ProfileContentPolicy.TAB_AUDIO, ProfileContentPolicy.TAB_GIF, ProfileContentPolicy.TAB_POLL};

    private static final int[] SAVED_MESSAGES_TABS = {ProfileContentPolicy.TAB_SAVED_DIALOGS, ProfileContentPolicy.TAB_SAVED_MESSAGES};

    private long now;
    private boolean credential;
    private boolean appLockFlag;
    private ProtectedChatsState state;

    @Before
    public void setUp() {
        now = 10_000;
        credential = true;
        appLockFlag = true;
        final Map<String, String> map = new HashMap<>();
        state = new ProtectedChatsState(new ProtectedChatsState.Storage() {
            public String get(String key) {
                return map.get(key);
            }

            public void put(String key, String value) {
                map.put(key, value);
            }

            public void remove(String key) {
                map.remove(key);
            }
        }, () -> now, new ProtectedChatsState.Credential() {
            public boolean hasCredential() {
                // The credential, as protected chats see it: independent of the app lock flag.
                return PasscodeLockPolicy.canProtectChats(credential);
            }

            public ProtectedChatsState.Verification verify(String secret) {
                return "1234".equals(secret) ? ProtectedChatsState.Verification.OK : ProtectedChatsState.Verification.WRONG;
            }

            public boolean biometricAvailable() {
                return false;
            }
        });
        assertEquals(ProtectedChatsState.Result.OK, state.enableFeature());
    }

    private void protect(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACCOUNT, dialog, state.proofFromPasscode("1234", null)));
        state.relock(ACCOUNT, dialog);
    }

    private void unlock(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACCOUNT, dialog, state.proofFromPasscode("1234", null)));
    }

    private boolean locked(long dialog) {
        return state.isLockedProtected(ACCOUNT, dialog);
    }

    /** Whether a profile of {@code profileDialog} offers {@code tab}, with the locks as the model reports them. */
    private boolean offered(long profileDialog, int tab) {
        return ProfileContentPolicy.tabAvailable(tab, locked(profileDialog), locked(SELF));
    }

    // ---- the policy, tab by tab ---------------------------------------------------------------

    @Test
    public void onlyTabsOfMessagesAreEverWithheld() {
        for (boolean dialogLocked : new boolean[] {false, true}) {
            for (boolean savedLocked : new boolean[] {false, true}) {
                for (int tab : UNRELATED_TABS) {
                    assertTrue("tab " + tab + " dialogLocked=" + dialogLocked + " savedLocked=" + savedLocked,
                            ProfileContentPolicy.tabAvailable(tab, dialogLocked, savedLocked));
                    assertFalse(ProfileContentPolicy.isMessageDerivedTab(tab));
                }
                for (int tab : MESSAGE_TABS_OF_THE_CHAT) {
                    assertEquals(!dialogLocked, ProfileContentPolicy.tabAvailable(tab, dialogLocked, savedLocked));
                    assertTrue(ProfileContentPolicy.isMessageDerivedTab(tab));
                }
                for (int tab : SAVED_MESSAGES_TABS) {
                    assertEquals(!savedLocked, ProfileContentPolicy.tabAvailable(tab, dialogLocked, savedLocked));
                    assertTrue(ProfileContentPolicy.isMessageDerivedTab(tab));
                }
            }
        }
    }

    @Test
    public void everyTabIdIsClassified() {
        // 0..15: the tabs of SharedMediaLayout. Each is either of messages or unrelated, never neither.
        for (int tab = 0; tab <= 15; tab++) {
            boolean listed = ProfileContentPolicy.isMessageDerivedTab(tab);
            boolean inUnrelated = false;
            for (int u : UNRELATED_TABS) inUnrelated |= u == tab;
            assertTrue("tab " + tab + " is classified exactly once", listed != inUnrelated);
        }
    }

    @Test
    public void countsOfALockedDialogAreWithheldAndNothingElseIsTouched() {
        int[] raw = {5, 3, 1, 4, 2, 7, 11, 6, 9};   // layout counts; index 6 = common groups
        assertSame("nothing withheld: the same array", raw, ProfileContentPolicy.visibleLayoutCounts(raw, false));
        int[] withheld = ProfileContentPolicy.visibleLayoutCounts(raw, true);
        assertArrayEquals(new int[] {0, 0, 0, 0, 0, 0, 11, 0, 0}, withheld);
        assertArrayEquals("the raw counts are kept, so unlocking needs no reload", new int[] {5, 3, 1, 4, 2, 7, 11, 6, 9}, raw);
    }

    @Test
    public void preloaderCountsAreAllMessagesAndAreWithheldAsOne() {
        int[] raw = {5, 3, 1, 4, 2, 7, 5, 2, 9, 0, 0};
        assertSame(raw, ProfileContentPolicy.visiblePreloaderCounts(raw, false));
        assertArrayEquals(new int[raw.length], ProfileContentPolicy.visiblePreloaderCounts(raw, true));
        assertEquals(5, raw[0]);
    }

    @Test
    public void countMaskingAgreesWithTheTabPolicy() {
        // SharedMediaLayout's count index -> its tab.
        int[][] map = {
                {0, ProfileContentPolicy.TAB_PHOTOVIDEO}, {1, ProfileContentPolicy.TAB_FILES}, {2, ProfileContentPolicy.TAB_VOICE},
                {3, ProfileContentPolicy.TAB_LINKS}, {4, ProfileContentPolicy.TAB_AUDIO}, {5, ProfileContentPolicy.TAB_GIF},
                {6, ProfileContentPolicy.TAB_COMMON_GROUPS}, {8, ProfileContentPolicy.TAB_POLL}};
        int[] raw = {9, 9, 9, 9, 9, 9, 9, 9, 9};
        for (boolean locked : new boolean[] {false, true}) {
            int[] visible = ProfileContentPolicy.visibleLayoutCounts(raw, locked);
            for (int[] entry : map) {
                assertEquals("count " + entry[0], ProfileContentPolicy.tabAvailable(entry[1], locked, false), visible[entry[0]] > 0);
            }
        }
    }

    @Test
    public void savedMessagesListsFollowTheSavedMessagesLock() {
        assertTrue(ProfileContentPolicy.showSavedMessagesTab(true, false));
        assertFalse(ProfileContentPolicy.showSavedMessagesTab(true, true));
        assertFalse("no tab appears out of nothing", ProfileContentPolicy.showSavedMessagesTab(false, false));
        assertTrue(ProfileContentPolicy.showSavedDialogsTab(true, false));
        assertFalse(ProfileContentPolicy.showSavedDialogsTab(true, true));
        assertFalse(ProfileContentPolicy.showSavedDialogsTab(false, false));
    }

    @Test
    public void contentIsWithheldExactlyWhenADialogOfTheProfileIsLocked() {
        assertFalse(ProfileContentPolicy.isContentWithheld(false, false));
        assertTrue(ProfileContentPolicy.isContentWithheld(true, false));
        assertTrue(ProfileContentPolicy.isContentWithheld(false, true));
        assertTrue(ProfileContentPolicy.isContentWithheld(true, true));
    }

    // ---- through the real protection model ----------------------------------------------------

    @Test
    public void protectingSavedMessagesDoesNotHideStoriesGiftsOrAnythingUnrelatedOnTheOwnProfile() {
        protect(SELF);
        assertTrue(locked(SELF));
        for (int tab : UNRELATED_TABS) {
            assertTrue("own profile keeps tab " + tab, offered(SELF, tab));
        }
        assertTrue(offered(SELF, ProfileContentPolicy.TAB_STORIES));
        assertTrue(offered(SELF, ProfileContentPolicy.TAB_GIFTS));
        // What is withheld is the messages of Saved Messages.
        for (int tab : MESSAGE_TABS_OF_THE_CHAT) assertFalse(offered(SELF, tab));
        for (int tab : SAVED_MESSAGES_TABS) assertFalse(offered(SELF, tab));
    }

    @Test
    public void protectingSavedMessagesDoesNotHideStoriesOrGiftsOnAnotherProfile() {
        protect(SELF);
        for (int tab : UNRELATED_TABS) {
            assertTrue("a friend's profile keeps tab " + tab, offered(FRIEND, tab));
        }
        // The friend's own media is not withheld; only the Saved Messages kept from them is.
        for (int tab : MESSAGE_TABS_OF_THE_CHAT) assertTrue(offered(FRIEND, tab));
        assertFalse(offered(FRIEND, ProfileContentPolicy.TAB_SAVED_MESSAGES));
        assertEquals(1, ProfileContentPolicy.visibleLayoutCounts(new int[] {1, 0, 0, 0, 0, 0, 0, 0, 0}, locked(FRIEND))[0]);
    }

    @Test
    public void protectingAUsersChatDoesNotRemoveTheirProfileFeatures() {
        protect(FRIEND);
        assertTrue(locked(FRIEND));
        for (int tab : UNRELATED_TABS) {
            assertTrue("the protected user's profile keeps tab " + tab, offered(FRIEND, tab));
        }
        for (int tab : MESSAGE_TABS_OF_THE_CHAT) assertFalse("their chat's media is withheld", offered(FRIEND, tab));
        // Saved Messages is not protected: its tabs stay, and other profiles are untouched.
        for (int tab : SAVED_MESSAGES_TABS) assertTrue(offered(FRIEND, tab));
        for (int tab = 0; tab <= 15; tab++) assertTrue(offered(OTHER, tab));
        for (int tab = 0; tab <= 15; tab++) assertTrue(offered(SELF, tab));
    }

    @Test
    public void protectingAGroupKeepsItsMembersAndStories() {
        protect(GROUP);
        assertTrue(offered(GROUP, ProfileContentPolicy.TAB_GROUPUSERS));
        assertTrue(offered(GROUP, ProfileContentPolicy.TAB_STORIES));
        assertTrue(offered(GROUP, ProfileContentPolicy.TAB_GIFTS));
        assertFalse(offered(GROUP, ProfileContentPolicy.TAB_PHOTOVIDEO));
    }

    @Test
    public void lockedAndUnlockedStateRemovesNoUnrelatedFeature() {
        protect(SELF);
        protect(FRIEND);
        for (boolean unlocked : new boolean[] {false, true, false}) {
            if (unlocked) {
                unlock(SELF);
                unlock(FRIEND);
            } else {
                state.relock(ACCOUNT, SELF);
                state.relock(ACCOUNT, FRIEND);
            }
            assertEquals(!unlocked, locked(SELF));
            for (long profile : new long[] {SELF, FRIEND, OTHER}) {
                for (int tab : UNRELATED_TABS) {
                    assertTrue("profile " + profile + " tab " + tab + " unlocked=" + unlocked, offered(profile, tab));
                }
            }
            // Messages are available exactly while their dialog is unlocked.
            for (int tab : MESSAGE_TABS_OF_THE_CHAT) {
                assertEquals(unlocked, offered(SELF, tab));
                assertEquals(unlocked, offered(FRIEND, tab));
                assertTrue(offered(OTHER, tab));
            }
        }
    }

    @Test
    public void unlockingBringsTheMessageTabsBackWithoutAReload() {
        protect(SELF);
        int[] raw = {5, 3, 1, 4, 2, 7, 0, 0, 1};
        assertArrayEquals(new int[9], ProfileContentPolicy.visibleLayoutCounts(raw, locked(SELF)));
        unlock(SELF);
        assertSame(raw, ProfileContentPolicy.visibleLayoutCounts(raw, locked(SELF)));
    }

    /** Every tab of every profile, as offered now. */
    private boolean[][] snapshot() {
        long[] profiles = {SELF, FRIEND, OTHER, GROUP};
        boolean[][] result = new boolean[profiles.length][16];
        for (int p = 0; p < profiles.length; p++) {
            for (int tab = 0; tab <= 15; tab++) {
                result[p][tab] = offered(profiles[p], tab);
            }
        }
        return result;
    }

    @Test
    public void theAppLockDoesNotChangeWhatAProfileOffers() {
        protect(SELF);
        protect(FRIEND);
        // The credential is what protection needs; the app lock is a separate switch on top of it.
        appLockFlag = true;
        assertTrue(PasscodeLockPolicy.isAppLockEnabled(credential, appLockFlag));
        boolean[][] withAppLock = snapshot();
        boolean selfLockedWithAppLock = locked(SELF);

        appLockFlag = false;
        assertFalse(PasscodeLockPolicy.isAppLockEnabled(credential, appLockFlag));
        boolean[][] withoutAppLock = snapshot();

        for (int p = 0; p < withAppLock.length; p++) {
            assertArrayEquals("profile " + p, withAppLock[p], withoutAppLock[p]);
        }
        // Protected chats stay protected with the app lock off, and stay locked.
        assertTrue(selfLockedWithAppLock);
        assertTrue(locked(SELF));
        assertTrue(locked(FRIEND));
        assertFalse(locked(OTHER));
        // And with the app lock off, Stories and Gifts are on every profile.
        for (long profile : new long[] {SELF, FRIEND, OTHER, GROUP}) {
            assertTrue(offered(profile, ProfileContentPolicy.TAB_STORIES));
            assertTrue(offered(profile, ProfileContentPolicy.TAB_GIFTS));
        }
    }

    @Test
    public void withoutAProtectedDialogNothingIsEverWithheld() {
        for (long profile : new long[] {SELF, FRIEND, OTHER, GROUP}) {
            for (int tab = 0; tab <= 15; tab++) {
                assertTrue(offered(profile, tab));
            }
        }
        assertFalse(ProfileContentPolicy.isContentWithheld(locked(SELF), locked(FRIEND)));
    }
}
