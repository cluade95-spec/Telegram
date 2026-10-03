package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The forward hold does not care which screen started the forward. The picker is presented over
 * a fragment that reports the protected dialog it shows and whether it is on screen
 * (beginForwardHoldOver); a chat, a profile with its shared media and the chat behind a photo viewer
 * all report their dialog, while a screen that is not gated (MediaActivity) reports none. The hold
 * only keeps an authorization that already exists. These tests run the sequences the pickers
 * produce, with Immediate Auto-lock, through the real state and the real destination rule.
 */
public class ForwardSourceContextTest {

    private static final long ACCOUNT = 1L;
    private static final long SOURCE = 2000L;
    private static final long SAVED = 1000L;
    private static final long GROUP_A = -4000L;
    private static final long GROUP_B = -5000L;
    private static final long NOBODY = 3000L;    // not protected

    private long now;
    private ProtectedChatsState state;

    private static final class Request {
        final long dialogId;
        final Runnable onUnlocked;
        final Runnable onCancelled;

        Request(long dialogId, Runnable onUnlocked, Runnable onCancelled) {
            this.dialogId = dialogId;
            this.onUnlocked = onUnlocked;
            this.onCancelled = onCancelled;
        }
    }

    private final List<Request> asked = new ArrayList<>();
    private final ForwardDestinations.Authenticator sheet = (dialogId, onUnlocked, onCancelled) -> asked.add(new Request(dialogId, onUnlocked, onCancelled));
    private final ForwardDestinations.Locks locks = dialogId -> state.isLockedProtected(ACCOUNT, dialogId);
    private final List<List<Long>> sentTo = new ArrayList<>();

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

    private void openAuthorized(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACCOUNT, dialog, state.proofFromPasscode("1234", null)));
        state.chatEntered(ACCOUNT, dialog);
    }

    private boolean locked(long dialog) {
        return state.isLockedProtected(ACCOUNT, dialog);
    }

    private void unlockAndConfirm(Request request) {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACCOUNT, request.dialogId, state.proofFromPasscode("1234", null)));
        request.onUnlocked.run();
    }

    /** What DialogsActivity.notifyDelegate does for a selection, with a delegate that sends and comes back to the source (or opens a destination). */
    private boolean notifyDelegate(List<Long> destinations, boolean delegateHandles, boolean sourceOnScreenAfter) {
        if (ForwardDestinations.holdUntilUnlocked(destinations, SAVED, true, locks, sheet, () -> notifyDelegate(destinations, delegateHandles, sourceOnScreenAfter))) {
            return false;
        }
        state.forwardHandOver(ACCOUNT, SOURCE);
        if (delegateHandles) {
            sentTo.add(new ArrayList<>(destinations));
        }
        state.forwardSettled(ACCOUNT, SOURCE, !delegateHandles ? ProtectedChatsState.ForwardOutcome.PICKER_REMAINS
                : sourceOnScreenAfter ? ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED : ProtectedChatsState.ForwardOutcome.DESTINATION_OPENED);
        return delegateHandles;
    }

    /** ProtectedGateLifecycle.resumed for the source: shown again unless its authorization ended while it was covered. */
    private boolean sourceResumes() {
        final boolean isLocked = locked(SOURCE);
        if (!isLocked) {
            state.chatEntered(ACCOUNT, SOURCE);
        }
        return !isLocked;
    }

    /** The picker is presented over a screen that shows {@code shown}; the screen is covered. */
    private boolean pickerOver(long shownProtectedDialog, boolean onScreen) {
        final boolean held = state.beginForwardHoldOver(ACCOUNT, shownProtectedDialog, onScreen);
        if (shownProtectedDialog == SOURCE) {
            state.chatLeft(ACCOUNT, SOURCE);     // posted after the transition, as ProtectedChats.leave does
        }
        return held;
    }

    // ---- the source is a dialog, not a screen type ------------------------------------------------

    @Test
    public void anAuthorizedProtectedChatSourceCanHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));
        assertFalse("covered by its own picker, not left", locked(SOURCE));
    }

    @Test
    public void theProfileOrSharedMediaOfTheSameAuthorizedChatHoldsTheSame() {
        protect(SOURCE);
        protect(SAVED);
        openAuthorized(SOURCE);
        // ProfileActivity(SOURCE) reports the dialog it shows exactly as the chat does.
        assertTrue(pickerOver(SOURCE, true));
        assertTrue(notifyDelegate(Arrays.asList(SAVED), true, true));
        assertTrue("back on the profile, not locked out", sourceResumes());
        assertTrue("Saved Messages was only written to", locked(SAVED));
    }

    @Test
    public void theChatBehindAPhotoViewerHoldsTheSame() {
        protect(SOURCE);
        protect(SAVED);
        openAuthorized(SOURCE);
        // The viewer is a window over the chat: the picker is presented over the chat fragment.
        assertTrue(pickerOver(SOURCE, true));
        assertTrue(notifyDelegate(Arrays.asList(SAVED), true, true));
        assertTrue(sourceResumes());
    }

    @Test
    public void aScreenThatIsNotGatedCanNotManufactureAHold() {
        // MediaActivity is not a gated fragment: it reports no protected dialog. The chat and the
        // profile under it were covered when it opened, so under Immediate Auto-lock they are locked.
        protect(SOURCE);
        openAuthorized(SOURCE);
        state.chatLeft(ACCOUNT, SOURCE);
        assertTrue(locked(SOURCE));

        assertFalse("nothing to hold over", pickerOver(0, true));
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertTrue("and forwarding does not authorize the chat whose media it shows", locked(SOURCE));
    }

    @Test
    public void aLockedSourceCanNotManufactureAHold() {
        protect(SOURCE);
        assertFalse(pickerOver(SOURCE, true));
        assertTrue(locked(SOURCE));
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
    }

    @Test
    public void aSourceThatIsNotOnScreenCanNotHold() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        assertFalse(state.beginForwardHoldOver(ACCOUNT, SOURCE, false));
    }

    @Test
    public void anUnprotectedSourceNeedsNoHoldAndGetsNone() {
        assertFalse(pickerOver(NOBODY, true));
        assertFalse(state.isForwardHoldActive(ACCOUNT, NOBODY));
        assertFalse(locked(NOBODY));
    }

    // ---- Saved Messages -----------------------------------------------------------------------

    @Test
    public void aDepositIntoSavedMessagesKeepsTheCompletionLifecycle() {
        protect(SOURCE);
        protect(SAVED);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));

        assertTrue(notifyDelegate(Arrays.asList(SAVED), true, true));
        assertTrue(asked.isEmpty());
        assertTrue(sourceResumes());
        assertTrue("the success and tag interaction is running on an open chat", state.isForwardHoldActive(ACCOUNT, SOURCE));

        state.forwardCompletionEnded(ACCOUNT, SOURCE);
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        state.chatLeft(ACCOUNT, SOURCE);
        assertTrue("Immediate Auto-lock resumes", locked(SOURCE));
        assertTrue(locked(SAVED));
    }

    // ---- the same chat ------------------------------------------------------------------------------

    @Test
    public void aForwardIntoTheSameAuthorizedChatIsNotLockedOutByThePickerAndAsksNothing() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));

        assertFalse("no redundant authentication for the open chat", ForwardDestinations.needsAuthentication(SOURCE, SAVED, true, locks));
        assertTrue(notifyDelegate(Arrays.asList(SOURCE), true, true));
        assertTrue(asked.isEmpty());
        assertEquals("sent once", 1, sentTo.size());
        assertTrue("the chat is there when the picker is gone", sourceResumes());

        // This is not a permanent authorization: leaving the chat locks it as always.
        state.chatLeft(ACCOUNT, SOURCE);
        assertTrue(locked(SOURCE));
    }

    @Test
    public void aForwardIntoTheSameChatThatIsNotAuthorizedStillAsks() {
        protect(SOURCE);
        // The chat is locked: it can not hold, and as a destination it asks.
        assertFalse(pickerOver(SOURCE, true));
        assertTrue(ForwardDestinations.needsAuthentication(SOURCE, SAVED, true, locks));
        assertFalse(notifyDelegate(Arrays.asList(SOURCE), true, true));
        assertEquals(SOURCE, asked.get(0).dialogId);
        assertTrue("nothing is sent before the unlock", sentTo.isEmpty());
    }

    // ---- several destinations ---------------------------------------------------------------------

    @Test
    public void aSelectionOfSeveralChatsKeepsTheSourceThroughThePickerAndTheAuthentication() {
        protect(SOURCE);
        protect(SAVED);
        protect(GROUP_A);
        protect(GROUP_B);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));

        final List<Long> selection = Arrays.asList(SAVED, GROUP_A, GROUP_B);
        assertFalse("held for the unlocks", notifyDelegate(selection, true, true));
        assertEquals("Saved Messages never asks", GROUP_A, asked.get(0).dialogId);

        now += 20_000;                                  // typing a passcode takes its time
        unlockAndConfirm(asked.get(0));
        assertEquals(GROUP_B, asked.get(1).dialogId);
        now += 20_000;                                  // far beyond the grace window of the first unlock
        unlockAndConfirm(asked.get(1));

        assertEquals("the original selection went out once, to all three", 1, sentTo.size());
        assertEquals(selection, sentTo.get(0));
        assertEquals("the first destination was not asked again", 2, asked.size());
        assertTrue("the source is still there when the picker is gone", sourceResumes());
        assertTrue("Saved Messages stayed locked", locked(SAVED));
    }

    @Test
    public void everyLockedDestinationOfASelectionIsAsked() {
        protect(GROUP_A);
        protect(GROUP_B);
        protect(SAVED);
        notifyDelegate(Arrays.asList(GROUP_A, SAVED, GROUP_B), true, true);
        unlockAndConfirm(asked.get(0));
        unlockAndConfirm(asked.get(1));
        assertEquals(Arrays.asList(GROUP_A, GROUP_B), Arrays.asList(asked.get(0).dialogId, asked.get(1).dialogId));
        assertEquals(1, sentTo.size());
    }

    @Test
    public void anAlreadyAuthorizedDestinationInASelectionIsNotAskedAgain() {
        protect(GROUP_A);
        protect(GROUP_B);
        openAuthorized(GROUP_A);
        notifyDelegate(Arrays.asList(GROUP_A, GROUP_B), true, true);
        assertEquals(1, asked.size());
        assertEquals(GROUP_B, asked.get(0).dialogId);
    }

    @Test
    public void cancellingOneRequiredAuthenticationSendsNothingAndLeavesNoStaleContinuation() {
        protect(SOURCE);
        protect(GROUP_A);
        protect(GROUP_B);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));

        notifyDelegate(Arrays.asList(GROUP_A, GROUP_B), true, true);
        unlockAndConfirm(asked.get(0));
        asked.get(1).onCancelled.run();
        assertTrue("not even to the destination that was unlocked", sentTo.isEmpty());

        // The picker is still open with its selection and the source still holds.
        assertTrue(state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertFalse(locked(SOURCE));

        // A late success of the cancelled prompt does nothing.
        unlockAndConfirm(asked.get(1));
        assertTrue(sentTo.isEmpty());
    }

    // ---- how a selection settles --------------------------------------------------------------------

    @Test
    public void aSelectionThatOpensAnotherChatMakesThatChatPartOfTheForward() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));

        // A single other destination: the delegate opens that chat over the source.
        assertTrue(notifyDelegate(Arrays.asList(NOBODY), true, false));
        assertEquals(ProtectedChatsState.ForwardPhase.DESTINATION, state.getForwardPhase(ACCOUNT, SOURCE));
        assertFalse("covered by the forward's destination, not left", locked(SOURCE));

        // Back from the destination: the source is shown again first, the destination goes after.
        assertTrue(sourceResumes());
        state.forwardDestinationLeft(ACCOUNT, SOURCE);
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertFalse("no stale lock", locked(SOURCE));
    }

    @Test
    public void leavingTheDestinationForSomethingElseCountsFromWhenTheSourceWasCovered() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));
        assertTrue(notifyDelegate(Arrays.asList(NOBODY), true, false));

        // The user opens something else from the destination while the source is still covered.
        state.forwardDestinationLeft(ACCOUNT, SOURCE);
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertTrue("under Immediate Auto-lock the covered source is locked, as always", locked(SOURCE));
        assertFalse(sourceResumes());
    }

    @Test
    public void aSelectionThatIsNotCompletedLeavesThePickerAndItsHoldInPlace() {
        protect(SOURCE);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));

        assertFalse("an error alert, the picker stays", notifyDelegate(Arrays.asList(SAVED), false, true));
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, state.getForwardPhase(ACCOUNT, SOURCE));
        assertFalse(locked(SOURCE));

        // The user then cancels the picker: the source is shown again exactly as it was.
        assertTrue(sourceResumes());
        state.forwardPickerClosed(ACCOUNT, SOURCE);
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertFalse(locked(SOURCE));
    }

    @Test
    public void aNewForwardAfterACompletedOneHoldsAgain() {
        protect(SOURCE);
        protect(SAVED);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));
        assertTrue(notifyDelegate(Arrays.asList(SAVED), true, true));
        assertTrue(sourceResumes());

        // The source stayed on screen; its next forward holds again whether or not the last
        // completion interaction reported its end.
        assertTrue(pickerOver(SOURCE, true));
        assertTrue(notifyDelegate(Arrays.asList(SAVED), true, true));
        assertTrue(sourceResumes());
        assertEquals(2, sentTo.size());
    }

    // ---- security boundaries ----------------------------------------------------------------------

    @Test
    public void goingToTheBackgroundWhileWaitingForAnUnlockReleasesTheHold() {
        protect(SOURCE);
        protect(GROUP_A);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));
        notifyDelegate(Arrays.asList(GROUP_A), true, true);

        state.appPaused();
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        state.appResumed();
        assertTrue(locked(SOURCE));
    }

    @Test
    public void unrelatedNavigationAfterACompletedForwardReleasesTheHold() {
        protect(SOURCE);
        protect(SAVED);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));
        assertTrue(notifyDelegate(Arrays.asList(SAVED), true, true));
        assertTrue(sourceResumes());

        state.chatLeft(ACCOUNT, SOURCE);
        assertFalse(state.isForwardHoldActive(ACCOUNT, SOURCE));
        assertTrue(locked(SOURCE));
    }

    @Test
    public void theHoldNeverAuthorizesAnotherProtectedChatOrSavedMessages() {
        protect(SOURCE);
        protect(SAVED);
        protect(GROUP_A);
        openAuthorized(SOURCE);
        assertTrue(pickerOver(SOURCE, true));
        assertTrue(notifyDelegate(Arrays.asList(SAVED), true, true));
        assertTrue(sourceResumes());
        assertTrue(locked(SAVED));
        assertTrue(locked(GROUP_A));
    }
}
