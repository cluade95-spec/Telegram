package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ProtectedChatsStateTest {

    private static final long ACC_A = 1001, ACC_B = 2002;
    private static final long CHAT_1 = 111, CHAT_2 = -222, SECRET = 4_000_000_000_000L;

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

    private static class FakeCredential implements ProtectedChatsState.Credential {
        boolean has = true;
        boolean throttled;
        boolean biometric;
        String secret = "1234";
        int wrongTries;

        public boolean hasCredential() {
            return has;
        }

        public ProtectedChatsState.Verification verify(String s) {
            if (throttled) {
                return ProtectedChatsState.Verification.THROTTLED;
            }
            if (secret.equals(s)) {
                return ProtectedChatsState.Verification.OK;
            }
            wrongTries++;
            return ProtectedChatsState.Verification.WRONG;
        }

        public boolean biometricAvailable() {
            return biometric;
        }
    }

    private MemStorage storage;
    private FakeCredential credential;
    private long now;
    private ProtectedChatsState state;

    private ProtectedChatsState create() {
        return new ProtectedChatsState(storage, () -> now, credential);
    }

    @Before
    public void setUp() {
        storage = new MemStorage();
        credential = new FakeCredential();
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

    private void unlock(long account, long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(account, dialog, proof()));
    }

    @Test
    public void protectWithExistingCredential() {
        protect(ACC_A, CHAT_1);
        assertTrue(state.isProtected(ACC_A, CHAT_1));
        assertFalse(state.isProtected(ACC_A, CHAT_2));
    }

    @Test
    public void protectWithoutCredentialIsRefusedAndNothingIsCreated() {
        ProtectedChatsState.AuthProof p = proof();
        credential.has = false;
        assertNull("no proof can be produced without a credential", state.proofFromPasscode("1234", null));
        assertEquals(ProtectedChatsState.Result.NO_CREDENTIAL, state.protect(ACC_A, CHAT_1, p));
        assertFalse(state.isProtected(ACC_A, CHAT_1));
        assertEquals(0, state.protectedCount());
    }

    @Test
    public void enableWithoutCredentialIsRefused() {
        state.disableFeatureRemovingAllProtection();
        credential.has = false;
        state = create();
        assertFalse(state.isFeatureEnabled());
        assertEquals(ProtectedChatsState.Result.NO_CREDENTIAL, state.enableFeature());
        assertFalse(state.isFeatureEnabled());
    }

    @Test
    public void protectNeedsAuthentication() {
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.protect(ACC_A, CHAT_1, null));
        ProtectedChatsState.Verification[] v = new ProtectedChatsState.Verification[1];
        assertNull(state.proofFromPasscode("0000", v));
        assertEquals(ProtectedChatsState.Verification.WRONG, v[0]);
        assertFalse(state.isProtected(ACC_A, CHAT_1));
    }

    @Test
    public void proofIsSingleUse() {
        ProtectedChatsState.AuthProof p = proof();
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC_A, CHAT_1, p));
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.protect(ACC_A, CHAT_2, p));
    }

    @Test
    public void failedAuthenticationDoesNotUnlock() {
        protect(ACC_A, CHAT_1);
        state.relock(ACC_A, CHAT_1);
        assertNull(state.proofFromPasscode("9999", null));
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.unlock(ACC_A, CHAT_1, null));
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
        assertEquals(1, credential.wrongTries);
    }

    @Test
    public void throttledVerificationYieldsNoProof() {
        credential.throttled = true;
        ProtectedChatsState.Verification[] v = new ProtectedChatsState.Verification[1];
        assertNull(state.proofFromPasscode("1234", v));
        assertEquals(ProtectedChatsState.Verification.THROTTLED, v[0]);
    }

    @Test
    public void biometricProofOnlyWhenAvailable() {
        assertNull(state.proofFromBiometric());
        credential.biometric = true;
        assertTrue(state.proofFromBiometric() != null);
    }

    @Test
    public void authenticateAndOpen() {
        protect(ACC_A, CHAT_1);
        state.relock(ACC_A, CHAT_1);
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
        unlock(ACC_A, CHAT_1);
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));
        state.chatEntered(ACC_A, CHAT_1);
        now += 24L * 3600 * 1000;
        assertTrue("stays open while foreground", state.isUnlocked(ACC_A, CHAT_1));
    }

    @Test
    public void unlockWithoutOpeningExpiresAfterGrace() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_IMMEDIATELY);
        protect(ACC_A, CHAT_1);
        state.relock(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        now += ProtectedChatsState.GRACE_MS - 1;
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));
        now += 2;
        assertTrue("never opened: locks again", state.isLockedProtected(ACC_A, CHAT_1));
    }

    @Test
    public void authorizationIsPerChat() {
        protect(ACC_A, CHAT_1);
        protect(ACC_A, CHAT_2);
        state.relock(ACC_A, CHAT_1);
        state.relock(ACC_A, CHAT_2);
        unlock(ACC_A, CHAT_1);
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));
        assertTrue(state.isLockedProtected(ACC_A, CHAT_2));
    }

    @Test
    public void timeoutAfterLeavingChat() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_5_MINUTES);
        protect(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        state.chatLeft(ACC_A, CHAT_1);
        now += 299_000;
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));
        now += 2_000;
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
        // does not come back by itself
        now -= 1_000_000;
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
    }

    @Test
    public void immediateTimeoutLocksOnLeave() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_IMMEDIATELY);
        protect(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));
        state.chatLeft(ACC_A, CHAT_1);
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
    }

    @Test
    public void clockGoingBackwardsLocks() {
        protect(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        state.chatLeft(ACC_A, CHAT_1);
        now -= 5_000;
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
    }

    @Test
    public void backgroundingStartsCountdownForOpenChat() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE);
        protect(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        state.appPaused();
        now += 30_000;
        state.appResumed();
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));

        state.appPaused();
        now += 61_000;
        state.appResumed();
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
    }

    @Test
    public void backgroundingLocksImmediatelyWithImmediateSetting() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_IMMEDIATELY);
        protect(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        state.appPaused();
        state.appResumed();
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
    }

    @Test
    public void manualRelock() {
        protect(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        assertTrue(state.canManuallyRelock(ACC_A, CHAT_1));
        assertTrue(state.relock(ACC_A, CHAT_1));
        assertTrue(state.isLockedProtected(ACC_A, CHAT_1));
        assertFalse("not offered for locked chats", state.canManuallyRelock(ACC_A, CHAT_1));
        assertTrue("still protected: relock is not removal", state.isProtected(ACC_A, CHAT_1));
    }

    @Test
    public void relockDoesNotAffectOtherChats() {
        protect(ACC_A, CHAT_1);
        protect(ACC_A, CHAT_2);
        unlock(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_2);
        state.relock(ACC_A, CHAT_1);
        assertTrue(state.isUnlocked(ACC_A, CHAT_2));
    }

    @Test
    public void removalRequiresAuthentication() {
        protect(ACC_A, CHAT_1);
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.unprotect(ACC_A, CHAT_1, null));
        assertTrue(state.isProtected(ACC_A, CHAT_1));
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC_A, CHAT_1, proof()));
        assertFalse(state.isProtected(ACC_A, CHAT_1));
        assertEquals(ProtectedChatsState.Result.NOT_PROTECTED, state.unprotect(ACC_A, CHAT_1, proof()));
    }

    @Test
    public void multiSelectionNeedsOneAuthentication() {
        java.util.List<Long> ids = java.util.Arrays.asList(CHAT_1, CHAT_2);
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.protectMany(ACC_A, ids, null));
        assertEquals(0, state.protectedCount());
        assertEquals(ProtectedChatsState.Result.OK, state.protectMany(ACC_A, ids, proof()));
        assertEquals(2, state.protectedCount(ACC_A));
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.unprotectMany(ACC_A, ids, null));
        assertEquals(ProtectedChatsState.Result.OK, state.unprotectMany(ACC_A, ids, proof()));
        assertEquals(0, state.protectedCount());
    }

    @Test
    public void unprotectManyIsAtomicWhenOneIsNotProtected() {
        protect(ACC_A, CHAT_1);
        java.util.List<Long> ids = java.util.Arrays.asList(CHAT_1, CHAT_2);
        assertEquals(ProtectedChatsState.Result.NOT_PROTECTED, state.unprotectMany(ACC_A, ids, proof()));
        assertTrue(state.isProtected(ACC_A, CHAT_1));
    }

    @Test
    public void returningFromActivityResultKeepsOpenChatOpen() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_IMMEDIATELY);
        protect(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        state.appPaused();
        state.appResumedFromActivityResult();
        state.appResumed();
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));
    }

    @Test
    public void protectedChatsSurviveAppLockBeingDisabled() {
        // App-wide lock lives in SharedConfig; this model only depends on the credential.
        protect(ACC_A, CHAT_1);
        state.relock(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));
        assertTrue(state.isProtected(ACC_A, CHAT_1));
    }

    @Test
    public void credentialRemovalClearsEverythingTogether() {
        protect(ACC_A, CHAT_1);
        protect(ACC_B, CHAT_2);
        credential.has = false;
        state.onCredentialRemoved();
        assertEquals(0, state.protectedCount());
        assertTrue("feature is back to its default so a new passcode can use it", state.isFeatureEnabled());
        assertFalse(create().isProtected(ACC_A, CHAT_1));
        credential.has = true;
        assertFalse("old protections do not come back with a new credential", create().isProtected(ACC_A, CHAT_1));
    }

    @Test
    public void staleProtectionWithoutCredentialIsDroppedOnLoad() {
        protect(ACC_A, CHAT_1);
        credential.has = false;
        ProtectedChatsState reloaded = create();
        assertEquals(0, reloaded.protectedCount());
        assertFalse(reloaded.isProtected(ACC_A, CHAT_1));
    }

    @Test
    public void disablingFeatureRemovesProtectionDeliberately() {
        protect(ACC_A, CHAT_1);
        assertEquals(1, state.protectedCount());
        state.disableFeatureRemovingAllProtection();
        assertEquals(0, state.protectedCount());
        assertEquals(ProtectedChatsState.Result.FEATURE_DISABLED, state.protect(ACC_A, CHAT_1, proof()));
    }

    @Test
    public void hidePreviewsOffNeverAllowsAnExternalReplyIntoAProtectedChat() {
        protect(ACC_A, CHAT_1);
        state.relock(ACC_A, CHAT_1);
        for (boolean hide : new boolean[] {true, false}) {
            state.setHidePreviewWhenLocked(hide);
            assertEquals("hide previews = " + hide, hide, state.shouldHideContent(ACC_A, CHAT_1));
            assertFalse("a reply, popup, Wear/car action or bot button is not allowed while locked, hide previews = " + hide,
                    state.allowsExternalInteraction(ACC_A, CHAT_1));
        }
    }

    @Test
    public void externalInteractionStaysClosedWhileTheChatIsTemporarilyOpen() {
        protect(ACC_A, CHAT_1);
        state.relock(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        for (boolean hide : new boolean[] {true, false}) {
            state.setHidePreviewWhenLocked(hide);
            assertFalse("authorization only opens the conversation, hide previews = " + hide, state.allowsExternalInteraction(ACC_A, CHAT_1));
        }
    }

    @Test
    public void externalInteractionIsOnlyAllowedForUnprotectedChats() {
        protect(ACC_A, CHAT_1);
        for (boolean hide : new boolean[] {true, false}) {
            state.setHidePreviewWhenLocked(hide);
            assertTrue("another chat of the account", state.allowsExternalInteraction(ACC_A, CHAT_2));
            assertTrue("the same chat id on another account", state.allowsExternalInteraction(ACC_B, CHAT_1));
        }
        state.unprotect(ACC_A, CHAT_1, proof());
        assertTrue("after protection is removed", state.allowsExternalInteraction(ACC_A, CHAT_1));
    }

    @Test
    public void previewHiddenOutsideTheConversationEvenWhenAuthorized() {
        protect(ACC_A, CHAT_1);
        state.relock(ACC_A, CHAT_1);
        assertTrue(state.isHidePreviewWhenLocked());
        assertTrue(state.shouldHideContent(ACC_A, CHAT_1));
        assertFalse("unprotected chat is never hidden", state.shouldHideContent(ACC_A, CHAT_2));
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        assertTrue("authorization only opens the conversation, previews elsewhere stay hidden", state.shouldHideContent(ACC_A, CHAT_1));
        state.setHidePreviewWhenLocked(false);
        assertFalse("preference off keeps normal behavior", state.shouldHideContent(ACC_A, CHAT_1));
        assertTrue("but the conversation still needs authentication", state.isProtected(ACC_A, CHAT_1));
    }

    @Test
    public void processRestartLosesAuthorization() {
        protect(ACC_A, CHAT_1);
        unlock(ACC_A, CHAT_1);
        state.chatEntered(ACC_A, CHAT_1);
        assertTrue(state.isUnlocked(ACC_A, CHAT_1));
        ProtectedChatsState restarted = create();
        assertTrue(restarted.isProtected(ACC_A, CHAT_1));
        assertTrue(restarted.isLockedProtected(ACC_A, CHAT_1));
    }

    @Test
    public void accountsAreSeparated() {
        protect(ACC_A, CHAT_1);
        assertFalse(state.isProtected(ACC_B, CHAT_1));
        protect(ACC_B, CHAT_2);
        state.clearAccount(ACC_A);
        assertFalse(state.isProtected(ACC_A, CHAT_1));
        assertTrue(state.isProtected(ACC_B, CHAT_2));
        assertFalse(create().isProtected(ACC_A, CHAT_1));
        assertTrue(create().isProtected(ACC_B, CHAT_2));
    }

    @Test
    public void unlockInOneAccountDoesNotUnlockSameDialogInAnother() {
        protect(ACC_A, CHAT_1);
        protect(ACC_B, CHAT_1);
        state.relock(ACC_A, CHAT_1);
        state.relock(ACC_B, CHAT_1);
        unlock(ACC_A, CHAT_1);
        assertTrue(state.isLockedProtected(ACC_B, CHAT_1));
    }

    @Test
    public void secretAndMigratedDialogs() {
        protect(ACC_A, SECRET);
        assertTrue(state.isProtected(ACC_A, SECRET));
        state.migrateDialog(ACC_A, SECRET, -999);
        assertFalse(state.isProtected(ACC_A, SECRET));
        assertTrue(state.isProtected(ACC_A, -999));
        state.removeDialog(ACC_A, -999);
        assertFalse(create().isProtected(ACC_A, -999));
    }

    @Test
    public void settingsPersistAndValidate() {
        assertFalse(state.setRelockSeconds(7));
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR));
        state.setHidePreviewWhenLocked(false);
        ProtectedChatsState reloaded = create();
        assertEquals(ProtectedChatsState.RELOCK_1_HOUR, reloaded.getRelockSeconds());
        assertFalse(reloaded.isHidePreviewWhenLocked());
        assertTrue(reloaded.isFeatureEnabled());
    }

    @Test
    public void protectionPersists() {
        protect(ACC_A, CHAT_1);
        protect(ACC_A, CHAT_2);
        ProtectedChatsState reloaded = create();
        assertTrue(reloaded.isProtected(ACC_A, CHAT_1));
        assertTrue(reloaded.isProtected(ACC_A, CHAT_2));
        assertEquals(2, reloaded.protectedCount(ACC_A));
    }
}
