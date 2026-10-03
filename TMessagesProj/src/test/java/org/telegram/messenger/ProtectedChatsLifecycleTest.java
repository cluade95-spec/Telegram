package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Timing, Saved Messages and multi-chat scenarios that need no Android runtime. */
public class ProtectedChatsLifecycleTest {

    private static final long ACC = 4242;
    private static final long SAVED = ACC; // Saved Messages dialog id == own user id
    private static final long CHAT_A = 900, CHAT_B = -901;

    private final Map<String, String> store = new HashMap<>();
    private long now;
    private boolean hasCredential;
    private ProtectedChatsState state;

    private ProtectedChatsState create() {
        return new ProtectedChatsState(new ProtectedChatsState.Storage() {
            public String get(String k) {
                return store.get(k);
            }

            public void put(String k, String v) {
                store.put(k, v);
            }

            public void remove(String k) {
                store.remove(k);
            }
        }, () -> now, new ProtectedChatsState.Credential() {
            public boolean hasCredential() {
                return hasCredential;
            }

            public ProtectedChatsState.Verification verify(String s) {
                return "0000".equals(s) ? ProtectedChatsState.Verification.OK : ProtectedChatsState.Verification.WRONG;
            }

            public boolean biometricAvailable() {
                return false;
            }
        });
    }

    private ProtectedChatsState.AuthProof proof() {
        return state.proofFromPasscode("0000", null);
    }

    @Before
    public void setUp() {
        now = 10_000_000L;
        hasCredential = true;
        state = create();
    }

    private void open(long id) {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACC, id, proof()));
        state.chatEntered(ACC, id);
    }

    // ------------------------------------------------------------ Saved Messages

    @Test
    public void savedMessagesBehavesLikeAnyDialog() {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC, SAVED, proof()));
        assertTrue(state.isProtected(ACC, SAVED));
        state.relock(ACC, SAVED);
        assertTrue(state.isLockedProtected(ACC, SAVED));

        // authenticate and open
        open(SAVED);
        assertTrue(state.isUnlocked(ACC, SAVED));

        // leave and re-enter before timeout
        state.setRelockSeconds(ProtectedChatsState.RELOCK_5_MINUTES);
        state.chatLeft(ACC, SAVED);
        now += 60_000;
        assertTrue(state.isUnlocked(ACC, SAVED));
        state.chatEntered(ACC, SAVED);
        assertTrue(state.canManuallyRelock(ACC, SAVED));

        // manual quick re-lock
        assertTrue(state.relock(ACC, SAVED));
        assertTrue(state.isLockedProtected(ACC, SAVED));

        // timeout re-lock
        open(SAVED);
        state.chatLeft(ACC, SAVED);
        now += 5 * 60_000 + 1;
        assertTrue(state.isLockedProtected(ACC, SAVED));

        // preview hiding
        assertTrue(state.shouldHideContent(ACC, SAVED));

        // process restart
        open(SAVED);
        ProtectedChatsState restarted = create();
        assertTrue(restarted.isProtected(ACC, SAVED));
        assertTrue(restarted.isLockedProtected(ACC, SAVED));

        // remove protection requires authentication
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.unprotect(ACC, SAVED, null));
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC, SAVED, proof()));
        assertFalse(state.isProtected(ACC, SAVED));
        assertFalse(state.shouldHideContent(ACC, SAVED));
    }

    @Test
    public void savedMessagesStaysProtectedWhileAppLockIsOff() {
        // The app lock is a SharedConfig flag; the model only needs the credential.
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC, SAVED, proof()));
        boolean appLockFlag = false;
        assertFalse(PasscodeLockPolicy.isAppLockEnabled(hasCredential, appLockFlag));
        assertTrue(state.isProtected(ACC, SAVED));
        state.relock(ACC, SAVED);
        open(SAVED);
        assertTrue(state.isUnlocked(ACC, SAVED));
    }

    // ------------------------------------------------------------ intervals

    @Test
    public void everyConfiguredIntervalLocksExactlyAtItsBoundary() {
        for (int seconds : ProtectedChatsState.RELOCK_CHOICES) {
            setUp();
            assertTrue(state.setRelockSeconds(seconds));
            assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC, CHAT_A, proof()));
            open(CHAT_A);
            state.chatLeft(ACC, CHAT_A);
            if (seconds == 0) {
                assertTrue("immediate", state.isLockedProtected(ACC, CHAT_A));
                continue;
            }
            now += seconds * 1000L - 1;
            assertTrue("before " + seconds, state.isUnlocked(ACC, CHAT_A));
            now += 1;
            assertTrue("at " + seconds, state.isLockedProtected(ACC, CHAT_A));
        }
    }

    @Test
    public void appLockAutoLockSettingIsUntouchedByChatInterval() {
        // The chat interval lives in the model's own storage keys only.
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        for (String key : store.keySet()) {
            assertFalse(key, key.toLowerCase().contains("autolock"));
        }
    }

    @Test
    public void unknownStoredIntervalFallsBackToDefault() {
        store.put("relockSeconds", "123456");
        assertEquals(ProtectedChatsState.DEFAULT_RELOCK_SECONDS, create().getRelockSeconds());
        store.put("relockSeconds", "garbage");
        assertEquals(ProtectedChatsState.DEFAULT_RELOCK_SECONDS, create().getRelockSeconds());
    }

    // ------------------------------------------------------------ switching, sleep, expiry

    @Test
    public void switchingBetweenProtectedChatsKeepsEachCountdownSeparate() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE);
        state.protect(ACC, CHAT_A, proof());
        state.protect(ACC, CHAT_B, proof());
        state.relock(ACC, CHAT_A);
        state.relock(ACC, CHAT_B);
        open(CHAT_A);
        state.chatLeft(ACC, CHAT_A);
        now += 40_000;
        open(CHAT_B);
        state.chatLeft(ACC, CHAT_B);
        now += 30_000; // A left 70s ago, B 30s ago
        assertTrue(state.isLockedProtected(ACC, CHAT_A));
        assertTrue(state.isUnlocked(ACC, CHAT_B));
    }

    @Test
    public void deviceSleepCountsBecauseTheClockIsMonotonicElapsedTime() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_5_MINUTES);
        state.protect(ACC, CHAT_A, proof());
        open(CHAT_A);
        state.appPaused();
        now += 3 * 3600 * 1000L; // slept for hours
        state.appResumed();
        assertTrue(state.isLockedProtected(ACC, CHAT_A));
    }

    @Test
    public void authorizationExpiringWhileContentWasVisibleIsDetectedOnResume() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE);
        state.protect(ACC, CHAT_A, proof());
        open(CHAT_A);
        state.appPaused();
        now += 61_000;
        state.appResumed();
        // the UI asks isLockedProtected on resume and pops the chat
        assertTrue(state.isLockedProtected(ACC, CHAT_A));
    }

    @Test
    public void systemActivityRoundTripKeepsAnOpenChatEvenWithImmediateSetting() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_IMMEDIATELY);
        state.protect(ACC, CHAT_A, proof());
        open(CHAT_A);
        state.appPaused();
        now += 120_000; // user picks a file
        state.appResumedFromActivityResult();
        state.appResumed();
        assertTrue(state.isUnlocked(ACC, CHAT_A));
    }

    @Test
    public void backgroundingWithoutResultFollowsTheInterval() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_5_MINUTES);
        state.protect(ACC, CHAT_A, proof());
        open(CHAT_A);
        state.appPaused();
        now += 4 * 60_000;
        state.appResumed();
        assertTrue(state.isUnlocked(ACC, CHAT_A));
        state.chatEntered(ACC, CHAT_A);
        state.appPaused();
        now += 6 * 60_000;
        state.appResumed();
        assertTrue(state.isLockedProtected(ACC, CHAT_A));
    }

    @Test
    public void manualRelockWhileVisibleLocksImmediately() {
        state.protect(ACC, CHAT_A, proof());
        open(CHAT_A);
        assertTrue(state.relock(ACC, CHAT_A));
        assertTrue(state.isLockedProtected(ACC, CHAT_A));
        assertFalse(state.relock(ACC, CHAT_A));
    }

    // ------------------------------------------------------------ credential lifecycle

    @Test
    public void disablingAppLockDoesNotTouchProtectedChatConfiguration() {
        state.protect(ACC, CHAT_A, proof());
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        state.setHidePreviewWhenLocked(false);
        // turning the app lock off is a SharedConfig change; nothing in the model is notified
        ProtectedChatsState reloaded = create();
        assertTrue(reloaded.isProtected(ACC, CHAT_A));
        assertEquals(ProtectedChatsState.RELOCK_1_HOUR, reloaded.getRelockSeconds());
        assertFalse(reloaded.isHidePreviewWhenLocked());
    }

    @Test
    public void removingTheCredentialClearsProtectionAndNewCredentialStartsClean() {
        state.protect(ACC, CHAT_A, proof());
        hasCredential = false;
        state.onCredentialRemoved();
        assertEquals(0, state.protectedCount());
        hasCredential = true;
        assertFalse(create().isProtected(ACC, CHAT_A));
        assertTrue(create().isFeatureEnabled());
    }

    @Test
    public void protectionIsRefusedWithoutCredentialAndNeverCreatesOne() {
        ProtectedChatsState.AuthProof p = proof();
        hasCredential = false;
        assertEquals(ProtectedChatsState.Result.NO_CREDENTIAL, state.protect(ACC, CHAT_A, p));
        assertEquals(0, state.protectedCount());
    }

    // ------------------------------------------------------------ scope

    @Test
    public void protectingOneChatNeverAffectsOthers() {
        state.protect(ACC, CHAT_A, proof());
        assertFalse(state.isProtected(ACC, CHAT_B));
        assertTrue(state.isUnlocked(ACC, CHAT_B));
        assertFalse(state.shouldHideContent(ACC, CHAT_B));
        assertFalse(state.canManuallyRelock(ACC, CHAT_B));
    }

    // ------------------------------------------------------------ management list

    @Test
    public void managementListShowsEveryTypeOfCurrentAccountAndShrinksOnRemoval() {
        final long secret = ProtectedDialogIds.fromArgs(0, 0, 7, 0, false);
        final long[] dialogs = {SAVED, 900L /* user or bot */, -901L /* group */, -1_000_000_555L /* channel */, secret};
        for (long id : dialogs) {
            assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC, id, proof()));
        }
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC + 1, 123L, proof()));

        java.util.List<Long> list = state.protectedDialogs(ACC);
        assertEquals(5, list.size());
        assertTrue(list.contains(SAVED) && list.contains(secret));
        assertFalse("other account is not listed", list.contains(123L));

        // remove one: needs authentication, row and count disappear, authorization is cleared
        state.relock(ACC, 900L);
        open(900L);
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, state.unprotect(ACC, 900L, null));
        assertEquals(5, state.protectedDialogs(ACC).size());
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC, 900L, proof()));
        assertEquals(4, state.protectedDialogs(ACC).size());
        assertEquals(4, state.protectedCount(ACC));
        assertFalse(state.canManuallyRelock(ACC, 900L));
        assertTrue(state.isUnlocked(ACC, 900L));
        assertFalse(state.shouldHideContent(ACC, 900L));
        assertFalse(create().protectedDialogs(ACC).contains(900L));
    }

    @Test
    public void managementListZeroState() {
        assertTrue(state.protectedDialogs(ACC).isEmpty());
        state.protect(ACC, CHAT_A, proof());
        state.unprotect(ACC, CHAT_A, proof());
        assertTrue(state.protectedDialogs(ACC).isEmpty());
        assertEquals(0, state.protectedCount(ACC));
        assertTrue(create().protectedDialogs(ACC).isEmpty());
    }

    @Test
    public void turningTheFeatureOffEmptiesTheList() {
        state.protect(ACC, CHAT_A, proof());
        state.disableFeatureRemovingAllProtection();
        assertTrue(state.protectedDialogs(ACC).isEmpty());
    }
}
