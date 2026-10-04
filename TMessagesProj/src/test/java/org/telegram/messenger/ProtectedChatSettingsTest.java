package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The per-chat Lock Settings: Chat Lock (protection), Hide Message Previews and Auto-lock of one
 * dialog, resolved by {@link ProtectedChatsState} so that every consumer asks the same question.
 * Navigation-level behaviour (the lifecycle, gate before reveal, protecting an open chat) is in
 * {@link ProtectedChatSettingsNavigationTest}.
 */
public class ProtectedChatSettingsTest {

    private static final long ACC_1 = 1001, ACC_2 = 2002;
    private static final long A = 111, B = -222, C = 333;
    private static final long SECRET = 0x4000000000000000L | 42;

    private static class MemStorage implements ProtectedChatsState.Storage {
        final Map<String, String> map = new HashMap<>();

        public String get(String key) {
            return map.get(key);
        }

        public void put(String key, String value) {
            map.put(key, value);
        }

        public void remove(String key) {
            map.remove(key);
        }
    }

    private MemStorage storage;
    private boolean hasCredential;
    private long now;
    private ProtectedChatsState state;

    private ProtectedChatsState create() {
        return new ProtectedChatsState(storage, () -> now, new ProtectedChatsState.Credential() {
            public boolean hasCredential() {
                return hasCredential;
            }

            public ProtectedChatsState.Verification verify(String secret) {
                return "1234".equals(secret) ? ProtectedChatsState.Verification.OK : ProtectedChatsState.Verification.WRONG;
            }

            public boolean biometricAvailable() {
                return false;
            }
        });
    }

    @Before
    public void setUp() {
        storage = new MemStorage();
        hasCredential = true;
        now = 1_000_000;
        state = create();
        assertEquals(ProtectedChatsState.Result.OK, state.enableFeature());
    }

    private ProtectedChatsState.AuthProof proof() {
        return state.proofFromPasscode("1234", null);
    }

    private void protect(long account, long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(account, dialog, proof()));
    }

    /** The chat was left at the time of the call and nothing else keeps it open. */
    private void leave(long account, long dialog) {
        state.chatEntered(account, dialog);
        state.chatLeft(account, dialog);
    }

    private ProtectedChatsState reload() {
        state = create();
        return state;
    }

    // ------------------------------------------------------------------ Chat Lock

    @Test
    public void a_chatLockOn_theChatBecomesProtected() {
        assertFalse(state.isProtected(ACC_1, A));
        protect(ACC_1, A);
        assertTrue(state.isProtected(ACC_1, A));
        assertEquals(1, state.protectedCount());
    }

    @Test
    public void b_chatLockOff_onlyThatChatBecomesUnprotected() {
        protect(ACC_1, A);
        protect(ACC_1, B);
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC_1, A, proof()));
        assertFalse(state.isProtected(ACC_1, A));
        assertTrue("another protected chat is not touched", state.isProtected(ACC_1, B));
        assertTrue("the feature and the credential are not touched", state.isFeatureEnabled());
    }

    @Test
    public void c_cancelledAuthentication_changesNothing() {
        protect(ACC_1, A);
        // enabling: the sheet was dismissed, no proof exists
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.protect(ACC_1, B, null));
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.protectOpen(ACC_1, B, null));
        assertFalse(state.isProtected(ACC_1, B));
        // disabling: likewise
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.unprotect(ACC_1, A, null));
        assertTrue(state.isProtected(ACC_1, A));
        // a wrong passcode produces no proof
        assertNull(state.proofFromPasscode("0000", null));
        assertEquals(1, state.protectedCount());
    }

    @Test
    public void chatLockNeedsTheSharedCredential_noSecondOneIsCreated() {
        hasCredential = false;
        state = create();
        assertEquals(ProtectedChatsState.Result.NO_CREDENTIAL, state.protectOpen(ACC_1, A, null));
        assertFalse(state.isProtected(ACC_1, A));
    }

    @Test
    public void q_r_bothEntryPointsModifyTheSameState_noDuplicateEntries() {
        protect(ACC_1, A);                                                     // the chat list's long-press
        assertEquals(ProtectedChatsState.Result.OK, state.protectOpen(ACC_1, A, proof()));  // the profile's Lock Settings
        assertEquals("one entry, not two", Arrays.asList(A), state.protectedDialogs(ACC_1));
        assertEquals(1, state.protectedCount());
        // removed from either place it is gone for both
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC_1, A, proof()));
        assertFalse(state.isProtected(ACC_1, A));
        assertEquals(ProtectedChatsState.Result.NOT_PROTECTED, state.unprotect(ACC_1, A, proof()));
        assertEquals(0, state.protectedCount());
        // and protected from the profile first, then listed with the others
        assertEquals(ProtectedChatsState.Result.OK, state.protectOpen(ACC_1, A, proof()));
        assertEquals(Arrays.asList(A), state.protectedDialogs(ACC_1));
        assertEquals(ProtectedChatsState.Result.OK, state.unprotectMany(ACC_1, Arrays.asList(A), proof()));
        assertTrue(state.protectedDialogs(ACC_1).isEmpty());
    }

    // ------------------------------------------------------------------ Hide Message Previews

    @Test
    public void d_previewPolicyDiffersPerDialog() {
        protect(ACC_1, A);
        protect(ACC_1, B);
        assertEquals(ProtectedChatsState.Result.OK, state.setChatHidePreview(ACC_1, A, true));
        assertEquals(ProtectedChatsState.Result.OK, state.setChatHidePreview(ACC_1, B, false));
        assertTrue(state.shouldHideContent(ACC_1, A));
        assertFalse(state.shouldHideContent(ACC_1, B));
    }

    @Test
    public void e_changingOneDialogsPreviewLeavesTheOthersAlone() {
        protect(ACC_1, A);
        protect(ACC_1, B);
        assertTrue(state.shouldHideContent(ACC_1, B));      // follows the Protected Chats page (hidden by default)
        state.setChatHidePreview(ACC_1, A, false);
        assertFalse(state.shouldHideContent(ACC_1, A));
        assertTrue("B did not change", state.shouldHideContent(ACC_1, B));
        assertTrue(state.getHidePreview(ACC_1, B));
        assertFalse(state.hasChatSettings(ACC_1, B));
        state.setChatHidePreview(ACC_1, A, true);
        assertTrue(state.shouldHideContent(ACC_1, A));
        assertTrue(state.shouldHideContent(ACC_1, B));
    }

    @Test
    public void f_previewsOff_externalInteractionStaysBlockedForTheProtectedChat() {
        protect(ACC_1, A);
        state.setChatHidePreview(ACC_1, A, false);
        assertFalse("previews are visible for A", state.shouldHideContent(ACC_1, A));
        assertFalse("replies, popups, Wear, car and bot buttons stay blocked", state.allowsExternalInteraction(ACC_1, A));
        // also while the chat is authorized and open
        state.chatEntered(ACC_1, A);
        assertFalse(state.allowsExternalInteraction(ACC_1, A));
        // and with the page's setting off for everyone
        state.setHidePreviewWhenLocked(false);
        assertFalse(state.allowsExternalInteraction(ACC_1, A));
        // an unprotected chat is unaffected
        assertTrue(state.allowsExternalInteraction(ACC_1, C));
    }

    // ------------------------------------------------------------------ Auto-lock

    @Test
    public void g_eachDialogUsesItsOwnAutoLock() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        protect(ACC_1, A);
        protect(ACC_1, B);
        assertEquals(ProtectedChatsState.Result.OK, state.setChatRelockSeconds(ACC_1, A, ProtectedChatsState.RELOCK_IMMEDIATELY));
        assertEquals(ProtectedChatsState.Result.OK, state.setChatRelockSeconds(ACC_1, B, ProtectedChatsState.RELOCK_5_MINUTES));
        for (long d : new long[]{A, B}) {
            assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACC_1, d, proof()));
            leave(ACC_1, d);
        }
        now += ProtectedChatsState.GRACE_MS;
        assertTrue("A: immediately", state.isLockedProtected(ACC_1, A));
        assertFalse("B: within its own 5 minutes", state.isLockedProtected(ACC_1, B));
        now += 4 * 60_000;
        assertFalse(state.isLockedProtected(ACC_1, B));
        now += 2 * 60_000;
        assertTrue("B: after its own 5 minutes", state.isLockedProtected(ACC_1, B));
    }

    @Test
    public void onlyTheDocumentedChoicesAreAccepted_noOtherTimingSystem() {
        protect(ACC_1, A);
        assertEquals(ProtectedChatsState.Result.UNSUPPORTED, state.setChatRelockSeconds(ACC_1, A, 17));
        assertEquals(ProtectedChatsState.Result.UNSUPPORTED, state.setChatRelockSeconds(ACC_1, A, -1));
        assertEquals(state.getRelockSeconds(), state.getRelockSeconds(ACC_1, A));
        assertFalse(state.hasChatSettings(ACC_1, A));
        for (int choice : ProtectedChatsState.RELOCK_CHOICES) {
            assertEquals(ProtectedChatsState.Result.OK, state.setChatRelockSeconds(ACC_1, A, choice));
            assertEquals(choice, state.getRelockSeconds(ACC_1, A));
        }
    }

    @Test
    public void appBackgroundAppliesTheDialogsOwnAutoLock() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_5_HOURS);
        protect(ACC_1, A);
        protect(ACC_1, B);
        state.setChatRelockSeconds(ACC_1, A, ProtectedChatsState.RELOCK_1_MINUTE);
        for (long d : new long[]{A, B}) {
            state.unlock(ACC_1, d, proof());
            state.chatEntered(ACC_1, d);
        }
        state.appPaused();
        now += 90_000;
        state.appResumed();
        assertTrue("A's own minute ran out", state.isLockedProtected(ACC_1, A));
        assertFalse("B follows the Protected Chats page", state.isLockedProtected(ACC_1, B));
    }

    // ------------------------------------------------------------------ storage

    @Test
    public void k_settingsSurviveReload() {
        protect(ACC_1, A);
        protect(ACC_1, B);
        state.setChatHidePreview(ACC_1, A, false);
        state.setChatRelockSeconds(ACC_1, A, ProtectedChatsState.RELOCK_5_MINUTES);
        state.setChatHidePreview(ACC_1, B, true);
        reload();
        assertTrue(state.isProtected(ACC_1, A));
        assertFalse(state.getHidePreview(ACC_1, A));
        assertEquals(ProtectedChatsState.RELOCK_5_MINUTES, state.getRelockSeconds(ACC_1, A));
        assertTrue(state.getHidePreview(ACC_1, B));
        assertEquals("B never set an Auto-lock of its own", state.getRelockSeconds(), state.getRelockSeconds(ACC_1, B));
        assertFalse(state.shouldHideContent(ACC_1, A));
        assertTrue(state.shouldHideContent(ACC_1, B));
    }

    @Test
    public void negativeAndSecretDialogIdsRoundTrip() {
        for (long d : new long[]{B, SECRET, -1_000_000_000_123L}) {
            protect(ACC_1, d);
            state.setChatHidePreview(ACC_1, d, false);
            state.setChatRelockSeconds(ACC_1, d, ProtectedChatsState.RELOCK_1_HOUR);
        }
        reload();
        for (long d : new long[]{B, SECRET, -1_000_000_000_123L}) {
            assertFalse(state.getHidePreview(ACC_1, d));
            assertEquals(ProtectedChatsState.RELOCK_1_HOUR, state.getRelockSeconds(ACC_1, d));
        }
    }

    @Test
    public void l_theSameDialogIdInTwoAccountsIsIsolated() {
        protect(ACC_1, A);
        protect(ACC_2, A);
        state.setChatHidePreview(ACC_1, A, false);
        state.setChatRelockSeconds(ACC_1, A, ProtectedChatsState.RELOCK_1_HOUR);
        assertFalse(state.shouldHideContent(ACC_1, A));
        assertTrue("account 2 follows the page", state.shouldHideContent(ACC_2, A));
        assertEquals(state.getRelockSeconds(), state.getRelockSeconds(ACC_2, A));
        assertFalse(state.hasChatSettings(ACC_2, A));
        reload();
        assertFalse(state.getHidePreview(ACC_1, A));
        assertTrue(state.getHidePreview(ACC_2, A));
        // removing one account's data leaves the other's
        state.clearAccount(ACC_1);
        assertFalse(state.hasChatSettings(ACC_1, A));
        state.setChatHidePreview(ACC_2, A, false);
        reload();
        assertFalse(state.getHidePreview(ACC_2, A));
        assertFalse(state.hasChatSettings(ACC_1, A));
    }

    @Test
    public void m_settingsOfAnUnprotectedChatDoNothingUntilChatLockIsOn() {
        // the page was edited while Chat Lock was off
        state.setChatHidePreview(ACC_1, C, true);
        state.setChatRelockSeconds(ACC_1, C, ProtectedChatsState.RELOCK_IMMEDIATELY);
        assertTrue(state.hasChatSettings(ACC_1, C));
        assertFalse("not protected", state.isProtected(ACC_1, C));
        assertFalse("no privacy behaviour", state.shouldHideContent(ACC_1, C));
        assertTrue("no locking behaviour", state.isUnlocked(ACC_1, C));
        assertFalse(state.isLockedProtected(ACC_1, C));
        assertTrue("no interaction block", state.allowsExternalInteraction(ACC_1, C));
        assertEquals(0, state.protectedCount());
        reload();
        assertFalse(state.isProtected(ACC_1, C));
        assertFalse(state.shouldHideContent(ACC_1, C));
        // Chat Lock ON restores what the chat was configured with
        protect(ACC_1, C);
        assertTrue(state.shouldHideContent(ACC_1, C));
        assertEquals(ProtectedChatsState.RELOCK_IMMEDIATELY, state.getRelockSeconds(ACC_1, C));
    }

    @Test
    public void chatLockOffKeepsTheChatsSettingsAndOnRestoresThem() {
        protect(ACC_1, A);
        state.setChatHidePreview(ACC_1, A, false);
        state.setChatRelockSeconds(ACC_1, A, ProtectedChatsState.RELOCK_5_HOURS);
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC_1, A, proof()));
        assertFalse(state.shouldHideContent(ACC_1, A));
        assertTrue(state.hasChatSettings(ACC_1, A));
        reload();
        protect(ACC_1, A);
        assertFalse(state.getHidePreview(ACC_1, A));
        assertEquals(ProtectedChatsState.RELOCK_5_HOURS, state.getRelockSeconds(ACC_1, A));
    }

    // ------------------------------------------------------------------ migration

    @Test
    public void n_chatsProtectedBeforePerChatSettingsKeepProtectionAndBehaviour() {
        // storage as the previous version wrote it: three global keys, the account list and its chats
        final MemStorage old = new MemStorage();
        old.put("enabled", "1");
        old.put("hidePreview", "0");
        old.put("relockSeconds", "300");
        old.put("accounts", "1001");
        old.put("chats_1001", "111,-222");
        storage = old;
        state = create();
        assertTrue(state.isProtected(ACC_1, A));
        assertTrue(state.isProtected(ACC_1, B));
        assertEquals(2, state.protectedCount());
        assertFalse("previews as the page said", state.shouldHideContent(ACC_1, A));
        assertEquals("Auto-lock as the page said", 300, state.getRelockSeconds(ACC_1, A));
        assertFalse(state.hasChatSettings(ACC_1, A));
        // the page keeps working for chats that have no value of their own
        state.setHidePreviewWhenLocked(true);
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        assertTrue(state.shouldHideContent(ACC_1, A));
        assertEquals(ProtectedChatsState.RELOCK_1_HOUR, state.getRelockSeconds(ACC_1, B));
        // a chat that was configured keeps its own value when the page changes
        state.setChatHidePreview(ACC_1, A, false);
        state.setHidePreviewWhenLocked(true);
        assertFalse(state.shouldHideContent(ACC_1, A));
        assertTrue(state.shouldHideContent(ACC_1, B));
        // nothing was reset by loading
        reload();
        assertEquals(2, state.protectedCount());
    }

    @Test
    public void corruptSettingsRecordsAreIgnoredNotFatal() {
        protect(ACC_1, A);
        storage.put("chatSettingsAccounts", "1001,xx");
        storage.put("chatsettings_1001", "111:1:17;junk;0:1:60;-222:x:300;333:0:");
        reload();
        assertTrue(state.isProtected(ACC_1, A));
        assertEquals("an invalid Auto-lock falls back to the page's", state.getRelockSeconds(), state.getRelockSeconds(ACC_1, A));
        assertTrue("its valid preview value stays", state.getHidePreview(ACC_1, A));
        assertEquals("the unknown preview token follows the page", state.isHidePreviewWhenLocked(), state.getHidePreview(ACC_1, B));
        assertEquals(300, state.getRelockSeconds(ACC_1, B));
        assertFalse(state.getHidePreview(ACC_1, C));
        assertFalse("id 0 is not a dialog", state.hasChatSettings(ACC_1, 0));
    }

    // ------------------------------------------------------------------ lifecycle of data

    @Test
    public void aMigratedDialogKeepsItsSettings() {
        protect(ACC_1, A);
        state.setChatHidePreview(ACC_1, A, false);
        state.setChatRelockSeconds(ACC_1, A, ProtectedChatsState.RELOCK_1_HOUR);
        state.migrateDialog(ACC_1, A, -999);
        assertTrue(state.isProtected(ACC_1, -999));
        assertFalse(state.getHidePreview(ACC_1, -999));
        assertEquals(ProtectedChatsState.RELOCK_1_HOUR, state.getRelockSeconds(ACC_1, -999));
        assertFalse(state.hasChatSettings(ACC_1, A));
        // also for a chat that was not protected
        state.setChatHidePreview(ACC_1, C, false);
        state.migrateDialog(ACC_1, C, -888);
        assertFalse(state.getHidePreview(ACC_1, -888));
        assertFalse(state.hasChatSettings(ACC_1, C));
        reload();
        assertFalse(state.getHidePreview(ACC_1, -999));
        assertFalse(state.getHidePreview(ACC_1, -888));
    }

    @Test
    public void aDeletedDialogTakesItsSettingsWithIt() {
        protect(ACC_1, A);
        state.setChatHidePreview(ACC_1, A, false);
        state.setChatHidePreview(ACC_1, B, false);
        state.removeDialog(ACC_1, A);
        state.removeDialog(ACC_1, B);
        assertFalse(state.hasChatSettings(ACC_1, A));
        assertFalse(state.hasChatSettings(ACC_1, B));
        reload();
        assertFalse(state.hasChatSettings(ACC_1, A));
        assertFalse(state.hasChatSettings(ACC_1, B));
    }

    @Test
    public void removingTheCredentialOrTheFeatureClearsEverythingTogether() {
        protect(ACC_1, A);
        state.setChatHidePreview(ACC_1, A, false);
        state.setChatHidePreview(ACC_1, C, false);
        state.onCredentialRemoved();
        assertFalse(state.hasChatSettings(ACC_1, A));
        assertFalse(state.hasChatSettings(ACC_1, C));
        assertEquals(0, state.protectedCount());
        reload();
        assertFalse(state.hasChatSettings(ACC_1, C));

        protect(ACC_1, A);
        state.setChatRelockSeconds(ACC_1, A, ProtectedChatsState.RELOCK_1_HOUR);
        state.disableFeatureRemovingAllProtection();
        assertFalse(state.hasChatSettings(ACC_1, A));
        reload();
        assertFalse(state.hasChatSettings(ACC_1, A));
        assertEquals(0, state.protectedCount());
    }

    // ------------------------------------------------------------------ the item in the profile menu

    @Test
    public void o_p_lockSettingsAreOfferedForSupportedDialogsOnly() {
        // supported dialog, feature on, with or without a passcode: offered whether protected or not
        assertTrue(ProtectedChatsState.isLockSettingsOffered(true, true, true));
        assertTrue("without a passcode Chat Lock leads to the passcode setup", ProtectedChatsState.isLockSettingsOffered(true, true, false));
        assertTrue(ProtectedChatsState.isLockSettingsOffered(true, false, false));
        // the feature switched off while a passcode exists: nothing to configure, as the chat list's Protect
        assertFalse(ProtectedChatsState.isLockSettingsOffered(true, false, true));
        // an unsupported dialog (a folder row): no dead item in any state
        assertFalse(ProtectedChatsState.isLockSettingsOffered(false, true, true));
        assertFalse(ProtectedChatsState.isLockSettingsOffered(false, true, false));
        assertFalse(ProtectedChatsState.isLockSettingsOffered(false, false, false));
    }

    @Test
    public void unsupportedDialogsAndAccountsStoreNothing() {
        assertEquals(ProtectedChatsState.Result.UNSUPPORTED, state.setChatHidePreview(ACC_1, 0, false));
        assertEquals(ProtectedChatsState.Result.UNSUPPORTED, state.setChatRelockSeconds(ACC_1, 0, 60));
        assertEquals(ProtectedChatsState.Result.UNSUPPORTED, state.setChatHidePreview(0, A, false));
        assertEquals(ProtectedChatsState.Result.UNSUPPORTED, state.protectOpen(ACC_1, 0, proof()));
        assertFalse(state.hasChatSettings(ACC_1, 0));
    }

    @Test
    public void topicsAndSecretChatsUseTheCanonicalDialogIdentity() {
        // a topic's profile or chat has the parent's dialog id: one protection, one set of settings
        final long parent = ProtectedDialogIds.fromArgs(0, 5000, 0, 0, false);
        protect(ACC_1, parent);
        state.setChatHidePreview(ACC_1, parent, false);
        assertFalse(state.shouldHideContent(ACC_1, ProtectedDialogIds.fromArgs(0, 5000, 0, 0, true)));
        // a secret chat keeps its own identity, distinct from the user it is with
        final long user = 777;
        final long secret = ProtectedDialogIds.fromArgs(0, 0, 42, 0, false);
        protect(ACC_1, secret);
        assertTrue(state.isProtected(ACC_1, secret));
        assertFalse(state.isProtected(ACC_1, user));
        state.setChatRelockSeconds(ACC_1, secret, ProtectedChatsState.RELOCK_1_HOUR);
        assertEquals(state.getRelockSeconds(), state.getRelockSeconds(ACC_1, user));
    }
}
