package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The source side of a forward to Saved Messages, with Immediate Auto-lock: the forward picker the
 * open chat started covers the chat, and Telegram's success message with the tag emojis appears on
 * it afterwards. The chat must stay open for exactly that, and the normal lock must apply again as
 * soon as it ends, the user navigates elsewhere or the app goes to the background.
 *
 * The sequence of calls is the one the UI makes: ChatActivity opens the picker (beginForwardHold),
 * the covered chat is reported left (chatLeft, posted after the transition), the picker finishes,
 * the chat is shown again (forwardPickerClosed, then the lock question, then chatEntered).
 */
public class ProtectedChatsForwardHoldTest {

    private static final long ACCOUNT = 1L;
    private static final long SOURCE = 2000L;
    private static final long SAVED = 1000L;
    private static final long OTHER = 3000L;

    private long now;
    private ProtectedChatsState state;

    @Before
    public void setUp() {
        now = 100_000;
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
                return true;
            }

            public ProtectedChatsState.Verification verify(String secret) {
                return "1234".equals(secret) ? ProtectedChatsState.Verification.OK : ProtectedChatsState.Verification.WRONG;
            }

            public boolean biometricAvailable() {
                return false;
            }
        });
        assertEquals(ProtectedChatsState.Result.OK, state.enableFeature());
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_IMMEDIATELY));
    }

    private void protect(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACCOUNT, dialog, state.proofFromPasscode("1234", null)));
    }

    /** Authenticated and on screen. */
    private void openAuthorized(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACCOUNT, dialog, state.proofFromPasscode("1234", null)));
        state.chatEntered(ACCOUNT, dialog);
    }

    /** The source shown again, as ProtectedChatGate.onFragmentResumed does it. */
    private boolean sourceResumes() {
        state.forwardPickerClosed(ACCOUNT, SOURCE);
        final boolean locked = state.isLockedProtected(ACCOUNT, SOURCE);
        if (!locked) {
            state.chatEntered(ACCOUNT, SOURCE);
        }
        return !locked;
    }

    private boolean locked(long dialog) {
        return state.isLockedProtected(ACCOUNT, dialog);
    }

    @Test
    public void withoutAHoldImmediateAutoLockClosesACoveredChat() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        assertTrue(locked(SOURCE));
        assertFalse(sourceResumes());
    }

    @Test
    public void completionInteractionIsAllowedToFinishWithImmediateAutoLock() {
        protect(SOURCE);
        protect(SAVED);
        openAuthorized(SOURCE);

        assertTrue("the open, authorized source can hold", state.beginForwardHold(ACCOUNT, SOURCE));
        state.chatLeft(ACCOUNT, SOURCE);                       // the picker covers it
        assertFalse("not locked while the picker is up", locked(SOURCE));

        state.forwardReturnsToSource(ACCOUNT, SOURCE);   // Saved Messages was chosen
        assertTrue("the picker finishes and the source is back: not kicked out", sourceResumes());
        assertTrue("Telegram's success and tag interaction runs on an open chat", state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertFalse(locked(SOURCE));
        assertTrue("Saved Messages itself was only written to", locked(SAVED));
    }

    @Test
    public void theHoldStaysOnlyForTheCompletionInteraction() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        state.forwardReturnsToSource(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());

        // The tag emojis are up.
        now += 2_000;
        assertTrue(state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertFalse(locked(SOURCE));
    }

    @Test
    public void whenTheCompletionInteractionEndsTheHoldReleasesAndTheNormalLockApplies() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        state.forwardReturnsToSource(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());

        state.forwardCompletionEnded(ACCOUNT, SOURCE);
        assertFalse("no hold any more", state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertFalse("the chat is still on screen, like any open chat", locked(SOURCE));

        // From here it is an ordinary chat: Immediate Auto-lock applies the moment it is left.
        state.chatLeft(ACCOUNT, SOURCE);
        assertTrue(locked(SOURCE));
    }

    @Test
    public void unrelatedNavigationDuringTheCompletionReleasesTheHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        state.forwardReturnsToSource(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());

        // The user opens something else over the chat while the tags are up.
        state.chatLeft(ACCOUNT, SOURCE);
        assertFalse("the hold is gone", state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertTrue("and Immediate Auto-lock applies", locked(SOURCE));
        assertFalse(sourceResumes());
    }

    @Test
    public void goingToTheBackgroundDuringTheCompletionReleasesTheHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        state.forwardReturnsToSource(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());

        state.appPaused();
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        state.appResumed();
        assertTrue("security wins: Immediate Auto-lock applies on return", locked(SOURCE));
    }

    @Test
    public void goingToTheBackgroundWhileThePickerIsOpenReleasesTheHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);

        state.appPaused();
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        state.appResumed();
        assertTrue(locked(SOURCE));
    }

    @Test
    public void theHoldDoesNotAuthorizeAnotherProtectedChat() {
        protect(SOURCE);
        protect(OTHER);
        protect(SAVED);
        openAuthorized(SOURCE);
        assertTrue(state.beginForwardHold(ACCOUNT, SOURCE));
        state.chatLeft(ACCOUNT, SOURCE);
        state.forwardReturnsToSource(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());

        assertTrue("another protected chat stays locked", locked(OTHER));
        assertTrue("Saved Messages stays locked", locked(SAVED));
        assertFalse("and can not start a hold of its own", state.beginForwardHold(ACCOUNT, OTHER));
        assertFalse(state.isForwardHoldActive(ACCOUNT, OTHER));
        assertTrue(locked(OTHER));
    }

    @Test
    public void onlyAProtectedAuthorizedOpenChatCanHold() {
        protect(SOURCE);
        assertFalse("locked", state.beginForwardHold(ACCOUNT, SOURCE));
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACCOUNT, SOURCE, state.proofFromPasscode("1234", null)));
        assertFalse("authorized but not opened yet", state.beginForwardHold(ACCOUNT, SOURCE));
        state.chatEntered(ACCOUNT, SOURCE);
        assertTrue(state.beginForwardHold(ACCOUNT, SOURCE));
        assertFalse("one picker at a time", state.beginForwardHold(ACCOUNT, SOURCE));
        assertFalse("a chat that is not protected holds nothing", state.beginForwardHold(ACCOUNT, 5555L));
    }

    // ---- the picker ends without a deposit into Saved Messages: exactly the old behaviour ----

    @Test
    public void cancellingThePickerLocksAnImmediateChatAsItAlwaysDid() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        assertFalse(locked(SOURCE));

        assertFalse("the picker is closed with Back: no deposit, no hold", sourceResumes());
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
    }

    @Test
    public void anotherDestinationEndsTheHoldAndStartsTheNormalCountdownFromWhenTheChatWasCovered() {
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE));
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        now += 1_000;
        state.chatLeft(ACCOUNT, SOURCE);                  // covered at t0
        now += 30_000;                                    // the user browses the picker
        state.forwardPickerClosed(ACCOUNT, SOURCE);       // another chat was chosen, the picker is gone

        assertFalse("30 s into the minute: still open, as without the picker", locked(SOURCE));
        now += 29_000;
        assertFalse(locked(SOURCE));
        now += 2_000;
        assertTrue("the minute counted from when it was covered, not from now", locked(SOURCE));
    }

    @Test
    public void aCompletionThatNeverComesBackOnScreenStillStartsTheCountdown() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        state.forwardReturnsToSource(ACCOUNT, SOURCE);
        // The source is never shown again (something else stays on top) and the interaction ends.
        state.forwardCompletionEnded(ACCOUNT, SOURCE);
        assertTrue("no authorization is left behind a chat that is not on screen", locked(SOURCE));
    }

    @Test
    public void manualLockEndsTheHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        assertTrue(state.relock(ACCOUNT, SOURCE));
        assertTrue(locked(SOURCE));
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
    }

    @Test
    public void aHoldFromAnEarlierForwardDoesNotLeakIntoTheNextOne() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.beginForwardHold(ACCOUNT, SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        state.forwardReturnsToSource(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());
        state.forwardCompletionEnded(ACCOUNT, SOURCE);

        assertTrue("a new forward holds again", state.beginForwardHold(ACCOUNT, SOURCE));
        state.chatLeft(ACCOUNT, SOURCE);
        state.forwardPickerClosed(ACCOUNT, SOURCE);       // cancelled this time
        assertTrue(locked(SOURCE));
    }
}
