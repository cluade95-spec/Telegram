package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The forward flow of a protected chat, run through the same lifecycle code the gate runs on the
 * device ({@link ProtectedGateLifecycle}) and through the real destination rule and state, driven by
 * the navigation layout and activity stand-in of {@link GateNavigationTestBase} (which makes the
 * ActionBarLayout and LaunchActivity calls in their order: present, closeLast with the closing
 * fragment kept until the transition ends, swipe back start/cancel/complete, removeFragmentFromStack,
 * the activity's pause, stop and resume).
 *
 * The picker, a destination's authentication sheet, the destination chat and the completion
 * interaction are children of the source chat: covering a chat is not leaving it. The few steps that
 * used to assume "covered means left" (build #82) now leave the chat the way it is really left:
 * closing it, opening another conversation from the destination, the app going to the background.
 *
 * Every test runs with Immediate Auto-lock, the setting that made the regression visible.
 */
public class ForwardNavigationTest extends GateNavigationTestBase {

    // what the destination rule asked and what was sent
    private final List<Request> asked = new ArrayList<>();
    private final List<List<Long>> sentTo = new ArrayList<>();
    private final ForwardDestinations.Authenticator sheet = (dialogId, onUnlocked, onCancelled) -> asked.add(new Request(dialogId, onUnlocked, onCancelled));
    private final ForwardDestinations.Locks locks = dialogId -> state.isLockedProtected(ACC, dialogId);

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


    private ProtectedChatsState.ForwardPhase phase() {
        return state.getForwardPhase(ACC, SOURCE);
    }


    /** The forward button: the picker is presented over the chat. */
    private Frag openPicker() {
        final Frag p = new Frag("picker", 0, true);
        assertTrue(layout.present(p, false, true));
        idle();                       // in the transition the chat is still shown
        layout.transitionEnds();      // then the chat below is paused ...
        idle();                       // ... and its leave is decided after the transition settled
        assertEquals("list, A, picker", layout.names());
        return p;
    }

    /** What the picker's delegate (ChatActivity.didSelectDialogs) does with a selection. */
    private interface Delegate {
        boolean didSelect(Frag picker);
    }

    /** A deposit into Saved Messages, a forward into the same chat, a send to several chats: the picker finishes. */
    private Delegate returnsToSource() {
        return picker -> {
            sentTo.add(Collections.singletonList(SAVED));
            return layout.closeLast(true);
        };
    }

    /** A single other chat: the delegate opens it over the source in place of the picker. */
    private Delegate opens(Frag destination) {
        return picker -> {
            sentTo.add(Collections.singletonList(destination.dialog));
            return layout.present(destination, true, true);
        };
    }

    private Delegate notCompleted() {
        return picker -> false;
    }

    /** DialogsActivity.notifyDelegate. */
    private boolean select(Frag picker, List<Long> destinations, Delegate delegate) {
        if (ForwardDestinations.holdUntilUnlocked(destinations, SAVED, true, locks, sheet, () -> select(picker, destinations, delegate))) {
            return false;
        }
        // ProtectedChatGate.forwardHandOver / forwardSettled
        final ProtectedGateLifecycle.Node source = picker.node.hasForwardSource() ? gate.handOver(picker.node) : null;
        final Layout where = picker.parent;
        final boolean handled = delegate.didSelect(picker);
        if (source != null) {
            final Frag top = where.top();
            gate.settled(picker.node, source, handled, top != null ? top.node : null);
        }
        return handled;
    }

    private void unlock(Request request) {
        authenticate(request.dialogId);
        request.onUnlocked.run();
    }

    // ================================================================== CASE 1: Back from the picker

    @Test
    public void case1_backFromThePickerReturnsToTheAuthorizedSourceWithoutClosingIt() {
        final Frag a = openSource();
        final Frag p = openPicker();
        assertEquals("the picker is part of the forward, the chat is not left", ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertFalse(locked(SOURCE));

        assertTrue(layout.closeLast(true));
        assertTrue("the chat is shown again at once", a.node.isVisible());
        idle();                       // the posted work during the transition: it closes nothing
        assertEquals("still animating", "list, A, picker", layout.names());
        assertNothingWasPopped();
        layout.transitionEnds();
        idle();

        assertEquals("list, A", layout.names());
        assertTrue(p.destroyed);
        assertNothingWasPopped();
        assertFalse("the chat is open and authorized", locked(SOURCE));
        assertTrue(a.node.isVisible());
        assertEquals("the forward is over, cleanly", ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void case1_withoutAnimationsTheSameHolds() {
        final Frag a = openSource();
        final Frag p = new Frag("picker", 0, true);
        assertTrue(layout.present(p, false, false));
        idle();
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertTrue(layout.closeLast(false));
        idle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertTrue(a.node.isVisible());
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void case1_aSwipeBackThatCompletesEndsTheSameWay() {
        final Frag a = openSource();
        openPicker();
        layout.swipeStart();
        assertTrue("shown again when the swipe starts", a.node.isVisible());
        idle();
        layout.swipeComplete();
        idle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void case1_aSwipeBackThatIsGivenUpKeepsThePickerAndTheForward() {
        final Frag a = openSource();
        final Frag p = openPicker();
        layout.swipeStart();
        idle();
        layout.swipeCancel();         // the chat below is paused again, the picker stays
        idle();
        assertEquals("list, A, picker", layout.names());
        assertFalse(a.node.isVisible());
        assertEquals("the forward is still the picker's", ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertFalse("and the chat was not left", locked(SOURCE));

        // The forward goes on as if nothing happened: a deposit into Saved Messages.
        protect(SAVED);
        assertTrue(select(p, Arrays.asList(SAVED), returnsToSource()));
        assertTrue(asked.isEmpty());
        settle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.COMPLETING, phase());
    }

    @Test
    public void case1_aSwipeBackThatIsGivenUpAndThenCompletedForLaterEndsCleanly() {
        final Frag a = openSource();
        openPicker();
        layout.swipeStart();
        layout.swipeCancel();
        idle();
        assertTrue(layout.closeLast(true));
        idle();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertTrue(a.node.isVisible());
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void case1_everyKindOfBackIsTheSameCloseLastFragment() {
        // System back, gesture back (completed) and the toolbar's back arrow all end in
        // closeLastFragment / the swipe completion; the lifecycle calls they make are the ones above.
        // This test pins the observable result for the three of them in sequence.
        final Frag a = openSource();
        for (int i = 0; i < 3; i++) {
            openPicker();
            if (i == 1) {
                layout.swipeStart();
                layout.swipeComplete();
            } else {
                layout.closeLast(true);
                layout.transitionEnds();
            }
            idle();
            assertEquals("list, A", layout.names());
            assertFalse("round " + i, locked(SOURCE));
            assertTrue(a.node.isVisible());
            assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        }
        assertNothingWasPopped();
    }

    // ================================================================== CASE 2: a chat is opened, then Back

    @Test
    public void case2_aDestinationOpenedByTheForwardAndThenBackReturnsToTheSource() {
        final Frag a = openSource();
        final Frag p = openPicker();
        final Frag b = new Frag("B", PLAIN, false);

        assertTrue(select(p, Arrays.asList(PLAIN), opens(b)));
        assertEquals("the destination is part of the forward", ProtectedChatsState.ForwardPhase.DESTINATION, phase());
        idle();
        layout.transitionEnds();      // the picker is removed under B
        idle();
        assertEquals("list, A, B", layout.names());
        assertFalse("A is covered by the forward's destination, not left", locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.DESTINATION, phase());

        assertTrue(layout.closeLast(true));          // Back from B
        assertTrue("A is shown again at once", a.node.isVisible());
        idle();
        assertNothingWasPopped();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertTrue(b.destroyed);
        assertNothingWasPopped();
        assertFalse("no stale lock on the source", locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertEquals(Collections.singletonList(Collections.singletonList(PLAIN)), sentTo);
    }

    @Test
    public void case2_withoutAnimationsAndWithASwipeBackFromTheDestination() {
        final Frag a = openSource();
        final Frag p = new Frag("picker", 0, true);
        assertTrue(layout.present(p, false, false));
        idle();
        final Frag b = new Frag("B", PLAIN, false);
        assertTrue(select(p, Arrays.asList(PLAIN), (picker) -> layout.present(b, true, false)));
        idle();
        assertEquals("list, A, B", layout.names());
        assertEquals(ProtectedChatsState.ForwardPhase.DESTINATION, phase());

        layout.swipeStart();                        // A is shown when the swipe starts
        idle();
        layout.swipeCancel();                       // given up: A is covered again, B is still the destination
        idle();
        assertEquals(ProtectedChatsState.ForwardPhase.DESTINATION, phase());
        assertFalse(locked(SOURCE));

        layout.swipeStart();
        layout.swipeComplete();
        idle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertTrue(a.node.isVisible());
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void case2_openingAChatInTheForumTopicsFlowThenBackStillReachesTheSource() {
        // The picker offers a forum: its topics screen goes over the picker; Back, then Back again.
        final Frag a = openSource();
        openPicker();
        final Frag topics = new Frag("topics", 0, false);
        assertTrue(layout.present(topics, false, true));
        settle();
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertFalse(locked(SOURCE));

        layout.closeLast(true);
        idle();
        layout.transitionEnds();
        idle();
        assertEquals("list, A, picker", layout.names());
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());

        layout.closeLast(true);
        idle();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertTrue(a.node.isVisible());
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void case2_navigatingAwayFromTheDestinationIsOrdinaryNavigation() {
        final Frag a = openSource();
        final Frag p = openPicker();
        final Frag b = new Frag("B", PLAIN, false);
        assertTrue(select(p, Arrays.asList(PLAIN), opens(b)));
        settle();

        // From the destination the user opens another conversation, which is not part of the forward.
        final Frag other = new Frag("otherChat", PLAIN_2, false);
        assertTrue(layout.present(other, false, true));
        settle();
        assertEquals("the forward ended with the user leaving the destination", ProtectedChatsState.ForwardPhase.NONE, phase());
        assertTrue("A was left: Immediate Auto-lock applies", locked(SOURCE));
        assertEquals("and A, which was not on screen, was removed silently, not while a transition ran",
                Collections.singletonList("A"), layout.removedByGate);
        assertEquals(0, layout.forcedTransitionEnds);
        assertEquals("list, B, otherChat", layout.names());

        // Coming back, nothing locked shows and nothing is cut short.
        layout.closeLast(true);
        idle();
        layout.transitionEnds();
        idle();
        layout.closeLast(true);
        idle();
        layout.transitionEnds();
        idle();
        assertEquals("list", layout.names());
        assertEquals(0, layout.forcedTransitionEnds);
        assertTrue(locked(SOURCE));
        assertFalse(a.node.isVisible());
    }

    // ================================================================== CASE 3: a protected destination

    @Test
    public void case3_aProtectedDestinationAsksForItsOwnAuthenticationAndBackReturnsToTheSource() {
        final Frag a = openSource();
        final Frag p = openPicker();
        protect(PROT_B);
        final Frag b = new Frag("B", PROT_B, false);

        assertFalse("held for the unlock of B", select(p, Arrays.asList(PROT_B), opens(b)));
        assertEquals(1, asked.size());
        assertEquals("only the destination is asked, never the source", PROT_B, asked.get(0).dialogId);
        assertEquals("the sheet is a layer over the picker", ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertFalse(locked(SOURCE));
        assertTrue(locked(PROT_B));
        assertTrue("nothing is sent before the unlock", sentTo.isEmpty());

        now += 15_000;                               // typing the passcode takes its time
        unlock(asked.get(0));
        assertEquals("sent once, to B", Collections.singletonList(Collections.singletonList(PROT_B)), sentTo);
        assertEquals(ProtectedChatsState.ForwardPhase.DESTINATION, phase());
        settle();
        assertEquals("list, A, B", layout.names());
        assertFalse(locked(PROT_B));
        assertFalse("the source keeps its own authorization", locked(SOURCE));

        assertTrue(layout.closeLast(true));          // Back from B
        assertTrue(a.node.isVisible());
        idle();
        assertNothingWasPopped();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertFalse("A is not closed", locked(SOURCE));
        assertTrue("B, left under Immediate Auto-lock, is locked on its own account", locked(PROT_B));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void case3_sourceAndDestinationAuthorizationsAreIndependent() {
        final Frag a = openSource();
        final Frag p = openPicker();
        protect(PROT_B);
        final Frag b = new Frag("B", PROT_B, false);
        select(p, Arrays.asList(PROT_B), opens(b));
        unlock(asked.get(0));
        settle();
        assertFalse(locked(SOURCE));
        assertFalse(locked(PROT_B));

        // Locking B by hand does not touch A, and A's expiry does not touch B.
        state.relock(ACC, PROT_B);
        assertTrue(locked(PROT_B));
        assertFalse(locked(SOURCE));
        assertEquals("the forward is still A's", ProtectedChatsState.ForwardPhase.DESTINATION, phase());

        state.relock(ACC, SOURCE);
        assertTrue(locked(SOURCE));
        assertEquals("a manual lock ends the hold with the authorization", ProtectedChatsState.ForwardPhase.NONE, phase());
        assertTrue(locked(PROT_B));
    }

    @Test
    public void case3_cancellingTheDestinationsAuthenticationThenBackKeepsTheSource() {
        final Frag a = openSource();
        final Frag p = openPicker();
        protect(PROT_B);
        final Frag b = new Frag("B", PROT_B, false);

        select(p, Arrays.asList(PROT_B), opens(b));
        asked.get(0).onCancelled.run();
        assertTrue("nothing was sent", sentTo.isEmpty());
        assertEquals("list, A, picker", layout.names());
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertFalse(locked(SOURCE));

        // A late success of the cancelled prompt does nothing.
        unlock(asked.get(0));
        assertTrue(sentTo.isEmpty());
        assertEquals("list, A, picker", layout.names());

        layout.closeLast(true);
        idle();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertTrue(a.node.isVisible());
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void case3_aProtectedDestinationThatIsAlreadyAuthorizedIsNotAskedAgain() {
        final Frag a = openSource();
        final Frag p = openPicker();
        protect(PROT_B);
        authenticate(PROT_B);
        final Frag b = new Frag("B", PROT_B, false);
        assertTrue(select(p, Arrays.asList(PROT_B), opens(b)));
        assertTrue(asked.isEmpty());
        settle();
        assertEquals("list, A, B", layout.names());
        assertEquals(ProtectedChatsState.ForwardPhase.DESTINATION, phase());
    }

    @Test
    public void case3_theContinuationRunsOnceEvenIfTheSuccessIsReportedTwice() {
        openSource();
        final Frag p = openPicker();
        protect(PROT_B);
        final Frag b = new Frag("B", PROT_B, false);
        select(p, Arrays.asList(PROT_B), opens(b));
        final Request request = asked.get(0);
        unlock(request);
        request.onUnlocked.run();
        assertEquals("one forward", 1, sentTo.size());
    }

    // ================================================================== CASE 4: Saved Messages and the completion UI

    @Test
    public void case4_aDepositIntoSavedMessagesKeepsTheSourceThroughTheCompletionInteraction() {
        final Frag a = openSource();
        final Frag p = openPicker();
        protect(SAVED);

        assertTrue(select(p, Arrays.asList(SAVED), returnsToSource()));
        assertTrue("Saved Messages never asks for a deposit", asked.isEmpty());
        assertTrue("the picker finishes by returning to the source", a.node.isVisible());
        idle();
        assertEquals(ProtectedChatsState.ForwardPhase.COMPLETING, phase());
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertTrue("write-only: Saved Messages stays locked and was not opened", locked(SAVED));
        assertEquals(Collections.singletonList(Collections.singletonList(SAVED)), sentTo);

        // The success message with the tag emojis runs, then ends.
        now += 8_000;
        assertFalse(locked(SOURCE));
        gate.completionEnded(a.node);
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertFalse("an ordinary open chat afterwards", locked(SOURCE));
        assertTrue(locked(SAVED));

        // From here it is an ordinary chat: Immediate Auto-lock applies when it is left (closed).
        layout.closeLast(true);
        settle();
        assertEquals("list", layout.names());
        assertTrue(locked(SOURCE));
        assertNothingWasPopped();
    }

    @Test
    public void case4_childNavigationDuringTheCompletionKeepsTheChatAndClosingItLocks() {
        final Frag a = openSource();
        final Frag p = openPicker();
        protect(SAVED);
        select(p, Arrays.asList(SAVED), returnsToSource());
        settle();
        assertEquals(ProtectedChatsState.ForwardPhase.COMPLETING, phase());

        // Something is opened over the chat while the tags are up: a child, the chat is still where Back goes.
        final Frag other = new Frag("other", 0, false);
        layout.present(other, false, true);
        settle();
        assertFalse("covered by a child is not left", locked(SOURCE));
        layout.closeLast(true);
        settle();
        assertEquals("list, A", layout.names());
        assertFalse(locked(SOURCE));
        assertNothingWasPopped();

        // The chat itself is closed: it is left, normal Auto-lock applies.
        layout.closeLast(true);
        settle();
        assertTrue(locked(SOURCE));
    }

    @Test
    public void case4_anEndOfTheCompletionAfterTheChatWasLeftChangesNothing() {
        final Frag a = openSource();
        final Frag p = openPicker();
        protect(SAVED);
        select(p, Arrays.asList(SAVED), returnsToSource());
        settle();
        layout.closeLast(true);                     // the user leaves the chat
        settle();
        assertTrue(locked(SOURCE));

        gate.completionEnded(a.node);               // the undo bar times out later
        assertTrue(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    // ================================================================== CASE 5: cancelled, then unrelated navigation

    @Test
    public void case5_aCancelledForwardCanNotAffectLaterNavigation() {
        final Frag a = openSource();
        openPicker();
        layout.closeLast(true);
        layout.transitionEnds();
        idle();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertFalse(locked(SOURCE));

        // Later a child covers the chat: it is a child, exactly as without a forward.
        final Frag other = new Frag("other", 0, false);
        assertTrue(layout.present(other, false, true));
        settle();
        assertFalse(locked(SOURCE));
        layout.closeLast(true);
        settle();
        assertFalse(locked(SOURCE));
        assertEquals("list, A", layout.names());

        // Leaving the chat is leaving it, exactly as without a forward: Immediate Auto-lock applies.
        layout.closeLast(true);
        settle();
        assertEquals("list", layout.names());
        assertTrue(locked(SOURCE));
        assertNothingWasPopped();
    }

    @Test
    public void case5_aSecondForwardAfterACancelledOneWorksAsTheFirstDid() {
        final Frag a = openSource();
        openPicker();
        layout.closeLast(true);
        layout.transitionEnds();
        idle();

        final Frag p2 = openPicker();
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        protect(SAVED);
        assertTrue(select(p2, Arrays.asList(SAVED), returnsToSource()));
        settle();
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.COMPLETING, phase());
        assertEquals("list, A", layout.names());
    }

    // ================================================================== CASE 6: the app goes to the background

    @Test
    public void case6_theAppGoingToTheBackgroundWithThePickerOpenReleasesTheHold() {
        final Frag a = openSource();
        final Frag p = openPicker();

        // LaunchActivity: onPause, onStop (the app is in the background), onResume.
        activityPaused();
        assertEquals("a pause alone is not the background", ProtectedChatsState.ForwardPhase.PICKER, phase());
        activityStopped();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        activityResumed();
        assertTrue("the normal lock applies", locked(SOURCE));

        // The locked chat was removed silently when the app came back, before anything could show it.
        assertEquals(Collections.singletonList("A"), layout.removedByGate);
        assertEquals("list, picker", layout.names());
        assertEquals(0, layout.forcedTransitionEnds);
    }

    @Test
    public void case6_theAppGoingToTheBackgroundOnTheDestinationReleasesTheHold() {
        final Frag a = openSource();
        final Frag p = openPicker();
        final Frag b = new Frag("B", PLAIN, false);
        select(p, Arrays.asList(PLAIN), opens(b));
        settle();
        assertEquals(ProtectedChatsState.ForwardPhase.DESTINATION, phase());

        activityPaused();
        activityStopped();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        activityResumed();
        assertTrue(locked(SOURCE));
        assertEquals(Collections.singletonList("A"), layout.removedByGate);
        assertEquals(0, layout.forcedTransitionEnds);
    }

    @Test
    public void case6_returningFromASystemActivityDoesNotReviveAChatThatWasOnlyCoveredByItsForward() {
        openSource();
        final Frag p = openPicker();
        final Frag b = new Frag("B", PLAIN, false);
        select(p, Arrays.asList(PLAIN), opens(b));
        settle();

        // The destination starts a system activity (a file picker): the app is paused, stopped, and comes back with a result.
        activityPaused();
        activityStopped();
        state.appResumedFromActivityResult();
        activityResumed();
        assertFalse("a chat in use is kept by the round trip, as it always was", locked(SOURCE));
        assertEquals("list, A, B", layout.names());
    }

    // ================================================================== more of the lifecycle

    @Test
    public void theSourceIsNeverClosedByAnyOfTheForwardEndings() {
        // Every way the forward can end with the source shown again, under Immediate Auto-lock.
        for (int variant = 0; variant < 4; variant++) {
            setUp();
            final Frag a = openSource();
            final Frag p = openPicker();
            protect(SAVED);
            switch (variant) {
                case 0:   // Back from the picker
                    layout.closeLast(true);
                    break;
                case 1:   // deposit into Saved Messages
                    select(p, Arrays.asList(SAVED), returnsToSource());
                    break;
                case 2: { // destination, then Back
                    final Frag b = new Frag("B", PLAIN, false);
                    select(p, Arrays.asList(PLAIN), opens(b));
                    settle();
                    layout.closeLast(true);
                    break;
                }
                default: { // forward into the same chat
                    select(p, Arrays.asList(SOURCE), returnsToSource());
                    break;
                }
            }
            idle();
            layout.transitionEnds();
            idle();
            assertEquals("variant " + variant, "list, A", layout.names());
            assertNothingWasPopped();
            assertFalse("variant " + variant, locked(SOURCE));
            assertTrue(a.node.isVisible());
        }
    }

    @Test
    public void aPickerThatIsRemovedWhileTheSourceIsStillCoveredByAChildLeavesTheSourceAuthorized() {
        final Frag a = openSource();
        final Frag p = openPicker();
        final Frag x = new Frag("x", 0, false);
        assertTrue(layout.present(x, false, true));
        settle();
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());

        // Something takes the picker out from under x: the source is still covered by x, a child.
        layout.removeFragmentFromStack(p);
        idle();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertFalse("covered is not left", locked(SOURCE));
        layout.closeLast(true);
        settle();
        assertEquals("list, A", layout.names());
        assertFalse(locked(SOURCE));
    }

    @Test
    public void aSourceThatIsDestroyedWhileThePickerIsOpenReleasesTheHold() {
        final Frag a = openSource();
        final Frag p = openPicker();
        layout.removeFragmentFromStack(a);      // closeChats and similar take the chat out
        idle();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
        assertTrue(locked(SOURCE));
        // The picker closing later finds nothing to settle.
        layout.closeLast(true);
        layout.transitionEnds();
        idle();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void aDelegateThatDoesNotCompleteLeavesThePickerAndTheForwardInPlace() {
        final Frag a = openSource();
        final Frag p = openPicker();
        assertFalse(select(p, Arrays.asList(PLAIN), notCompleted()));
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertEquals("list, A, picker", layout.names());

        layout.closeLast(true);
        idle();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void aDelegateThatAnswersLaterWhileThePickerStaysUpKeepsThePickerPhase() {
        // A confirmation (for example a paid-message dialog) is shown: the delegate reports the
        // selection as handled without finishing the picker.
        final Frag a = openSource();
        final Frag p = openPicker();
        assertTrue(select(p, Arrays.asList(PLAIN), picker -> true));
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, phase());
        assertEquals("list, A, picker", layout.names());

        // The confirmation is accepted and the picker finishes.
        layout.closeLast(true);
        idle();
        layout.transitionEnds();
        idle();
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void aScreenThatIsNotAProtectedDialogStartsNoForwardHold() {
        // A forward picker over the chat list or over an unprotected chat: nothing to hold.
        final Frag p = new Frag("picker", 0, true);
        assertTrue(layout.present(p, false, true));
        settle();
        assertFalse(p.node.hasForwardSource());

        layout.closeLast(true);
        settle();
        final Frag plain = new Frag("plain", PLAIN, false);
        layout.present(plain, false, true);
        settle();
        final Frag p2 = new Frag("picker2", 0, true);
        layout.present(p2, false, true);
        settle();
        assertFalse(p2.node.hasForwardSource());
    }

    @Test
    public void aLockedChatCanNotStartAForwardHold() {
        protect(SOURCE);
        final Frag a = new Frag("A", SOURCE, false);
        assertFalse("the layout refuses a locked chat", layout.present(a, false, true));
        authenticate(SOURCE);
        assertTrue(layout.present(a, false, true));
        settle();
        state.relock(ACC, SOURCE);
        final Frag p = new Frag("picker", 0, true);
        layout.present(p, false, true);
        settle();
        assertFalse(p.node.hasForwardSource());
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, phase());
    }

    @Test
    public void theHoldNeverAuthorizesAnotherChatOrSavedMessages() {
        final Frag a = openSource();
        final Frag p = openPicker();
        protect(SAVED);
        protect(PROT_B);
        protect(PROT_C);
        select(p, Arrays.asList(SAVED), returnsToSource());
        settle();
        assertTrue(locked(SAVED));
        assertTrue(locked(PROT_B));
        assertTrue(locked(PROT_C));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, state.getForwardPhase(ACC, SAVED));
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, state.getForwardPhase(ACC, PROT_B));
    }
}
