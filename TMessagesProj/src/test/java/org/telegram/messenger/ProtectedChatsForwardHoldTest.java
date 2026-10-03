package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * The phases of the forward a protected chat starts, on the state alone, with Immediate Auto-lock:
 * PICKER (the picker, and a destination's authentication sheet over it, cover the chat),
 * HANDING_OVER (the delegate runs), DESTINATION (the forward opened a chat over this one) and
 * COMPLETING (the forward came back and Telegram's success and tag interaction is up). None of
 * them is the user leaving the chat. The transitions are the ones the gate makes
 * ({@link ProtectedGateLifecycle}); ForwardNavigationTest drives them through the navigation callbacks.
 *
 * Calls as the UI makes them: the picker covers the chat (beginForwardHold, then the covered chat is
 * reported left, posted after the transition: chatLeft). Back, or the end of the forward, shows the
 * chat again (chatEntered, only if it is not locked) and the picker or the destination is destroyed
 * after that.
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

    /** The source shown again, as ProtectedGateLifecycle.resumed does it. */
    private boolean sourceResumes() {
        final boolean isLocked = state.isLockedProtected(ACCOUNT, SOURCE);
        if (!isLocked) {
            state.chatEntered(ACCOUNT, SOURCE);
        }
        return !isLocked;
    }

    private boolean locked(long dialog) {
        return state.isLockedProtected(ACCOUNT, dialog);
    }

    private ProtectedChatsState.ForwardPhase phase() {
        return state.getForwardPhase(ACCOUNT, SOURCE);
    }

    /** The picker over the open chat: the hold starts, the covered chat's leave is decided afterwards. */
    private void pickerOverSource() {
        assertTrue("the open, authorized source can hold", state.beginForwardHold(ACCOUNT, SOURCE));
        state.chatLeft(ACCOUNT, SOURCE);
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertFalse("not locked while the picker is up", locked(SOURCE));
    }

    @Test
    public void withoutAForwardImmediateAutoLockClosesACoveredChat() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        assertTrue(locked(SOURCE));
        assertFalse(sourceResumes());
    }

    // ---- the picker is cancelled: Back, system back, gesture back, toolbar back ----------------

    @Test
    public void cancellingThePickerShowsTheSourceAgainAsItWasAndEndsTheHoldCleanly() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();

        assertTrue("Back: the source is shown again first, authorized as it was", sourceResumes());
        assertEquals("the picker is still animating out", ProtectedChatsState.ForwardPhase.PICKER, phase());
        state.forwardPickerClosed(ACCOUNT, SOURCE);           // the picker is destroyed when the transition ends
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertFalse(locked(SOURCE));
        assertTrue(state.canManuallyRelock(ACCOUNT, SOURCE));
    }

    @Test
    public void aSwipeBackThatIsGivenUpDefersTheCoverAgain() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();

        assertTrue(sourceResumes());                           // the swipe starts: the source is shown
        state.chatLeft(ACCOUNT, SOURCE);                       // the swipe is given up: covered again
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertFalse(locked(SOURCE));
        assertTrue(sourceResumes());                           // and a real back later
        state.forwardPickerClosed(ACCOUNT, SOURCE);
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void aPickerThatIsGoneWhileTheSourceIsStillCoveredCountsFromWhenItWasCovered() {
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE));
        protect(SOURCE);
        openAuthorized(SOURCE);
        assertTrue(state.beginForwardHold(ACCOUNT, SOURCE));
        now += 1_000;
        state.chatLeft(ACCOUNT, SOURCE);                  // covered at t0
        now += 30_000;                                    // the user browses the picker
        state.forwardPickerClosed(ACCOUNT, SOURCE);       // the picker is gone, the source is covered by something else

        assertFalse("30 s into the minute: still open, as without the picker", locked(SOURCE));
        now += 29_000;
        assertFalse(locked(SOURCE));
        now += 2_000;
        assertTrue("the minute counted from when it was covered, not from now", locked(SOURCE));
    }

    // ---- the forward comes back to the source (Saved Messages, the same chat, several chats) ----

    @Test
    public void completionInteractionIsAllowedToFinishWithImmediateAutoLock() {
        protect(SOURCE);
        protect(SAVED);
        openAuthorized(SOURCE);
        pickerOverSource();

        state.forwardHandOver(ACCOUNT, SOURCE);
        assertEquals(ProtectedChatsState.ForwardPhase.HANDING_OVER, phase());
        assertTrue("the picker finishes and the source is back: not kicked out", sourceResumes());
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED);
        assertEquals(ProtectedChatsState.ForwardPhase.COMPLETING, phase());
        state.forwardPickerClosed(ACCOUNT, SOURCE);           // the picker is destroyed later: part of the hand-over, nothing to settle
        assertEquals(ProtectedChatsState.ForwardPhase.COMPLETING, phase());
        assertFalse(locked(SOURCE));
        assertTrue("Saved Messages itself was only written to", locked(SAVED));
    }

    @Test
    public void theCompletionStaysOnlyForTheCompletionInteraction() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED);

        now += 2_000;                                         // the tag emojis are up
        assertEquals(ProtectedChatsState.ForwardPhase.COMPLETING, phase());
        assertFalse(locked(SOURCE));

        state.forwardCompletionEnded(ACCOUNT, SOURCE);
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertFalse("the chat is still on screen, like any open chat", locked(SOURCE));

        // From here it is an ordinary chat: Immediate Auto-lock applies the moment it is left.
        state.chatLeft(ACCOUNT, SOURCE);
        assertTrue(locked(SOURCE));
    }

    @Test
    public void unrelatedNavigationDuringTheCompletionReleasesTheHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED);

        // The user opens something else over the chat while the tags are up.
        state.chatLeft(ACCOUNT, SOURCE);
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertTrue("and Immediate Auto-lock applies", locked(SOURCE));
        assertFalse(sourceResumes());
    }

    @Test
    public void aCompletionThatNeverComesBackOnScreenStillStartsTheCountdown() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED);
        // The source is never entered again (something else stays on top) and the interaction ends.
        state.forwardCompletionEnded(ACCOUNT, SOURCE);
        assertTrue("no authorization is left behind a chat that is not on screen", locked(SOURCE));
    }

    // ---- the forward opens a destination over the source ------------------------------------------

    @Test
    public void aDestinationOverTheSourceIsPartOfTheForwardAndBackFromItIsClean() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.DESTINATION_OPENED);
        state.forwardPickerClosed(ACCOUNT, SOURCE);           // the picker is removed under the destination: not a cancel
        assertEquals(ProtectedChatsState.ForwardPhase.DESTINATION, phase());
        now += 60_000;                                        // the user works in the destination
        assertFalse("covered by the forward's destination, not left", locked(SOURCE));

        assertTrue("Back: the source is shown again first", sourceResumes());
        state.forwardDestinationLeft(ACCOUNT, SOURCE);        // the destination goes after
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertFalse(locked(SOURCE));
    }

    @Test
    public void leavingTheDestinationForSomethingElseEndsTheForwardAndTheSourceCountsFromWhenItWasCovered() {
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE));
        protect(SOURCE);
        openAuthorized(SOURCE);
        assertTrue(state.beginForwardHold(ACCOUNT, SOURCE));
        now += 1_000;
        state.chatLeft(ACCOUNT, SOURCE);                      // covered by the picker at t0
        state.forwardHandOver(ACCOUNT, SOURCE);
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.DESTINATION_OPENED);

        now += 45_000;                                        // in the destination
        state.forwardDestinationLeft(ACCOUNT, SOURCE);        // it opens something else
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertFalse("45 s into the minute", locked(SOURCE));
        now += 20_000;
        assertTrue("the minute counted from when the source was covered", locked(SOURCE));
    }

    @Test
    public void aDestinationEndingWhileNoForwardIsRunningChangesNothing() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.forwardDestinationLeft(ACCOUNT, SOURCE);
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    // ---- other endings -----------------------------------------------------------------------------

    @Test
    public void aDelegateThatDoesNotCompleteReturnsToThePickerPhase() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.PICKER_REMAINS);
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertFalse(locked(SOURCE));
    }

    @Test
    public void anAbandonedForwardEndsWithTheCoverCountingFromWhenItBegan() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.ABANDONED);
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertTrue(locked(SOURCE));
    }

    @Test
    public void settlingOutsideAHandOverChangesNothing() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.DESTINATION_OPENED);
        assertEquals("no hand-over was running", ProtectedChatsState.ForwardPhase.PICKER, phase());
    }

    @Test
    public void aSourceThatIsGoneHoldsNothing() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardSourceGone(ACCOUNT, SOURCE);
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertTrue("it was covered when it went", locked(SOURCE));
    }

    @Test
    public void goingToTheBackgroundDuringTheCompletionReleasesTheHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED);

        state.appPaused();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        state.appResumed();
        assertTrue("security wins: Immediate Auto-lock applies on return", locked(SOURCE));
    }

    @Test
    public void goingToTheBackgroundWhileThePickerIsOpenReleasesTheHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();

        state.appPaused();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        state.appResumed();
        assertTrue(locked(SOURCE));
    }

    @Test
    public void aCoveredChatIsNotRevivedByAReturnFromASystemActivity() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();

        state.appPaused();
        state.appResumedFromActivityResult();
        state.appResumed();
        assertTrue("it was covered by its forward, not open: nothing to revive", locked(SOURCE));
    }

    @Test
    public void anOpenChatIsStillRevivedByAReturnFromASystemActivity() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.appPaused();
        state.appResumedFromActivityResult();
        state.appResumed();
        assertFalse(locked(SOURCE));
    }

    @Test
    public void theHoldDoesNotAuthorizeAnotherProtectedChat() {
        protect(SOURCE);
        protect(OTHER);
        protect(SAVED);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED);

        assertTrue("another protected chat stays locked", locked(OTHER));
        assertTrue("Saved Messages stays locked", locked(SAVED));
        assertFalse("and can not start a hold of its own", state.beginForwardHold(ACCOUNT, OTHER));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, state.getForwardPhase(ACCOUNT, OTHER));
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
        state.forwardHandOver(ACCOUNT, SOURCE);
        assertFalse("and not while the delegate runs", state.beginForwardHold(ACCOUNT, SOURCE));
        assertFalse("a chat that is not protected holds nothing", state.beginForwardHold(ACCOUNT, 5555L));
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
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED);
        state.forwardCompletionEnded(ACCOUNT, SOURCE);

        assertTrue("a new forward holds again", state.beginForwardHold(ACCOUNT, SOURCE));
        state.chatLeft(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());                          // cancelled this time
        state.forwardPickerClosed(ACCOUNT, SOURCE);
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void aNewForwardSupersedesTheCompletionOfAnEarlierOne() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        pickerOverSource();
        state.forwardHandOver(ACCOUNT, SOURCE);
        assertTrue(sourceResumes());
        state.forwardSettled(ACCOUNT, SOURCE, ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED);

        assertTrue(state.beginForwardHold(ACCOUNT, SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        state.forwardCompletionEnded(ACCOUNT, SOURCE);        // the earlier bar times out: it is not this forward's
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
    }
}
