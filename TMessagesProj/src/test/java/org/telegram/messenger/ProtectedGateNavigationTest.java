package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

/**
 * The general lifecycle of a protected chat, independent of any one screen: what Telegram or
 * Android put above it is temporary, and only a genuine departure starts Auto-lock.
 *
 * <ul>
 *   <li>a system permission dialog pauses the host activity without stopping it, so the chat is
 *       neither covered by navigation nor left;</li>
 *   <li>a child fragment (channel comments, a profile, a picker, a media screen) covers the chat and
 *       Back returns to it, with the same calls for system back, toolbar back, a completed
 *       swipe and a cancelled swipe;</li>
 *   <li>the chat is left when it is closed, when the app goes to the background (the activity
 *       stops), on a manual lock, and when a child opens another conversation;</li>
 *   <li>whatever lost its authorization is closed when that happens, never from inside the call
 *       that resumes a fragment and never by cutting a transition short.</li>
 * </ul>
 *
 * The scenarios run through {@link GateNavigationTestBase}, which makes the ActionBarLayout and
 * LaunchActivity calls in their order, with Immediate Auto-lock unless a test says otherwise.
 */
public class ProtectedGateNavigationTest extends GateNavigationTestBase {

    private Frag open(Frag child) {
        assertTrue(layout.present(child, false, true));
        settle();
        return child;
    }

    private void back() {
        assertTrue(layout.closeLast(true));
        idle();                      // work posted while the transition runs
        layout.transitionEnds();
        idle();
    }

    private void assertAuthorizedAndOnScreen(Frag a) {
        assertFalse("authorized", locked(SOURCE));
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
        assertTrue(a.node.isVisible());
        assertNothingWasPopped();
    }

    // ================================================================== 1-2: system permission dialogs

    @Test
    public void permissionAllowed_theChatStaysOpenAndAuthorized() {
        final Frag a = openSource();

        // Attachment menu -> camera, or a circle message: Activity.requestPermissions. The dialog
        // pauses LaunchActivity and does not stop it; the result arrives before onResume.
        activityPaused();
        assertEquals("a system overlay is not navigation", ProtectedGateLifecycle.NodeState.HOST_PAUSED, a.node.getState());
        assertFalse(locked(SOURCE));
        now += 30_000;                                   // the dialog is read with care
        idle();
        assertFalse("Immediate Auto-lock is not triggered by the dialog", locked(SOURCE));

        activityResumed();                               // Allow
        assertAuthorizedAndOnScreen(a);
        assertEquals("list, A", layout.names());
    }

    @Test
    public void permissionDenied_theChatStaysOpenAndAuthorized() {
        final Frag a = openSource();
        activityPaused();
        activityResumed();                               // Deny: the same lifecycle, the gate never sees the answer
        assertAuthorizedAndOnScreen(a);

        // And the same again: the user opens the menu and is asked once more.
        activityPaused();
        activityResumed();
        assertAuthorizedAndOnScreen(a);
    }

    @Test
    public void permissionDialogDismissedWithoutAnAnswer_theChatStaysOpenAndAuthorized() {
        final Frag a = openSource();
        activityPaused();
        now += 120_000;
        activityResumed();                               // Back pressed on the dialog, or it was interrupted
        assertAuthorizedAndOnScreen(a);
    }

    @Test
    public void permissionDialogOverAChildOfTheChat_nothingChanges() {
        final Frag a = openSource();
        final Frag child = open(new Frag("comments", PLAIN, false));
        activityPaused();                                // the camera is asked for from the child
        assertEquals(ProtectedGateLifecycle.NodeState.COVERED, a.node.getState());
        activityResumed();
        assertFalse(locked(SOURCE));
        back();
        assertEquals("list, A", layout.names());
        assertAuthorizedAndOnScreen(a);
    }

    @Test
    public void permissionDialogDoesNotEndAForward() {
        openSource();
        final Frag picker = new Frag("picker", 0, true);
        open(picker);
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, state.getForwardPhase(ACC, SOURCE));
        activityPaused();
        activityResumed();
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, state.getForwardPhase(ACC, SOURCE));
        back();
        assertEquals("list, A", layout.names());
        assertFalse(locked(SOURCE));
        assertNothingWasPopped();
    }

    @Test
    public void severalPermissionRoundTripsInARow() {
        final Frag a = openSource();
        for (int i = 0; i < 5; i++) {
            activityPaused();
            activityResumed();
        }
        assertAuthorizedAndOnScreen(a);
    }

    // ================================================================== 3: channel comments

    @Test
    public void channelComments_backReturnsToTheChannelWithoutAGlitch() {
        final Frag channel = openSource();

        // The comments are the linked discussion chat: another dialog, pushed over the channel.
        final Frag comments = new Frag("comments", PLAIN, false);
        assertTrue(layout.present(comments, false, true));
        idle();
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, channel.node.getState());
        layout.transitionEnds();
        idle();
        assertEquals("the channel is covered by its child, not left", ProtectedGateLifecycle.NodeState.COVERED, channel.node.getState());
        assertFalse(locked(SOURCE));
        now += 20_000;                                   // reading the comments
        idle();
        assertFalse(locked(SOURCE));

        // Back: the channel is resumed at once, the comments leave when the transition ends.
        assertTrue(layout.closeLast(true));
        assertEquals("returning, while the transition still runs", ProtectedGateLifecycle.NodeState.RETURNING, channel.node.getState());
        idle();                                          // the old build closed the channel here, cutting the transition short
        assertEquals("list, A, comments", layout.names());
        assertNothingWasPopped();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertTrue(comments.destroyed);
        assertAuthorizedAndOnScreen(channel);
    }

    @Test
    public void channelComments_swipeBackAndPredictiveBack() {
        final Frag channel = openSource();
        final Frag comments = open(new Frag("comments", PLAIN, false));

        layout.swipeStart();                             // predictive back starts: the channel shows
        idle();
        layout.swipeCancel();                            // and is given up
        idle();
        assertEquals("list, A, comments", layout.names());
        assertEquals(ProtectedGateLifecycle.NodeState.COVERED, channel.node.getState());
        assertFalse(locked(SOURCE));
        assertNothingWasPopped();

        layout.swipeStart();
        idle();
        layout.swipeComplete();                          // and this time it completes
        idle();
        assertEquals("list, A", layout.names());
        assertAuthorizedAndOnScreen(channel);
    }

    @Test
    public void channelComments_thenAReplyThreadOfTheSameDiscussionThenBackTwice() {
        final Frag channel = openSource();
        open(new Frag("comments", PLAIN, false));
        open(new Frag("thread", PLAIN, false));          // the same conversation
        assertFalse(locked(SOURCE));
        back();
        back();
        assertEquals("list, A", layout.names());
        assertAuthorizedAndOnScreen(channel);
    }

    // ================================================================== 4: generic child fragment

    @Test
    public void genericChild_backReturnsToTheChat() {
        final Frag a = openSource();
        open(new Frag("media", 0, false));
        assertFalse(locked(SOURCE));
        back();
        assertEquals("list, A", layout.names());
        assertAuthorizedAndOnScreen(a);
    }

    @Test
    public void genericChild_aChainOfScreensThatShowNoOtherConversationStaysInTheContext() {
        final Frag a = openSource();
        open(new Frag("contacts", 0, false));
        open(new Frag("search", 0, false));
        open(new Frag("media", 0, false));
        assertFalse(locked(SOURCE));
        back();
        back();
        back();
        assertEquals("list, A", layout.names());
        assertAuthorizedAndOnScreen(a);
    }

    @Test
    public void genericChild_theProfileOfTheSameChatAndItsMediaAreOneContext() {
        final Frag a = openSource();
        final Frag profile = open(new Frag("profile", SOURCE, false));   // a second fragment of the same dialog
        open(new Frag("sharedMedia", 0, false));
        assertFalse(locked(SOURCE));
        back();
        back();
        assertEquals("list, A", layout.names());
        assertAuthorizedAndOnScreen(a);
        assertTrue(profile.destroyed);
    }

    @Test
    public void genericChild_aCoverLongerThanTheAutoLockIntervalIsStillNotLeaving() {
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE));
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));
        now += 10 * 60_000;                              // ten minutes in the comments
        idle();
        assertFalse("the chat was never left", locked(SOURCE));
        back();
        assertAuthorizedAndOnScreen(a);
    }

    // ================================================================== 5: a child takes the user elsewhere

    @Test
    public void childOpensAnotherConversation_theChatIsLeftAndNormalAutoLockApplies() {
        final Frag a = openSource();
        final Frag comments = open(new Frag("comments", PLAIN, false));

        // From the comments the user opens a different chat: not part of this chat's context.
        final Frag other = new Frag("otherChat", PLAIN_2, false);
        assertTrue(layout.present(other, false, true));
        idle();
        assertEquals("the transition is running, nothing is cut short", 0, layout.forcedTransitionEnds);
        layout.transitionEnds();
        idle();

        assertEquals(ProtectedGateLifecycle.NodeState.DESTROYED, a.node.getState());
        assertTrue("left, so locked", locked(SOURCE));
        assertEquals("closed while it was not on screen", Collections.singletonList("A"), layout.removedByGate);
        assertEquals("list, comments, otherChat", layout.names());
        assertEquals(0, layout.forcedTransitionEnds);

        back();
        back();
        assertEquals("list", layout.names());
        assertEquals("the stack unwound without any glitch", 0, layout.forcedTransitionEnds);
        assertTrue(comments.destroyed);
    }

    @Test
    public void childOpensAnotherConversation_theCountdownStartsFromTheDeparture() {
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE));
        openSource();
        final Frag comments = open(new Frag("comments", PLAIN, false));
        now += 5 * 60_000;                               // a long time in the comments: not counted
        idle();
        open(new Frag("otherChat", PLAIN_2, false));     // the departure
        assertFalse("within the interval from the departure", locked(SOURCE));
        now += 30_000;
        assertFalse(locked(SOURCE));
        now += 31_000;
        assertTrue("one minute after the departure", locked(SOURCE));
        assertEquals("the old instance does not wait in the stack to be revealed later",
                "list, comments, otherChat", layout.names());
    }

    @Test
    public void screensThatShowNoConversationNeverTakeTheUserOutOfTheContext() {
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));
        open(new Frag("settings", 0, false));
        open(new Frag("thread", PLAIN, false));          // the comments' own conversation again
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedGateLifecycle.NodeState.COVERED, a.node.getState());
    }

    // ================================================================== 6: the system UI becomes a real background

    @Test
    public void systemUiThenTheAppActuallyGoesToTheBackground_theSecurityBoundaryWins() {
        final Frag a = openSource();
        activityPaused();                                // the permission dialog
        assertFalse(locked(SOURCE));
        activityStopped();                               // the user pressed Home with the dialog up
        now += 5_000;
        activityResumed();                               // and came back
        assertTrue("normal Immediate Auto-lock applies", locked(SOURCE));
        assertEquals("closed before it could show, not cut out of a transition", Collections.singletonList("A"), layout.removedByGate);
        assertEquals(0, layout.forcedTransitionEnds);
        assertEquals("list", layout.names());
        assertEquals(ProtectedGateLifecycle.NodeState.DESTROYED, a.node.getState());
    }

    @Test
    public void screenOffIsTheBackgroundToo() {
        openSource();
        activityStopped();                               // ACTION_SCREEN_OFF
        activityResumed();
        assertTrue(locked(SOURCE));
        assertEquals("list", layout.names());
    }

    @Test
    public void aCoveredChatIsAlsoLockedByTheBackground_andAsksBeforeItShowsAgain() {
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));
        final int resumesBefore = a.resumes;
        activityPaused();
        activityStopped();
        activityResumed();
        assertTrue(locked(SOURCE));
        assertEquals("it stays where it is, not on screen", "list, A, comments", layout.names());
        assertNothingWasPopped();

        assertFalse("Back does not start", layout.closeLast(true));
        idle();
        assertEquals("authentication first", SOURCE, pendingReveal().dialog);
        assertEquals("the chat was not shown", resumesBefore, a.resumes);
        answerCancel();
        assertEquals("the user stays on the comments", "list, A, comments", layout.names());
    }

    @Test
    public void aTimedIntervalSurvivesAShortBackgroundAndNotALongOne() {
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE));
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));
        activityPaused();
        activityStopped();
        now += 20_000;
        activityResumed();
        assertFalse("within the minute", locked(SOURCE));
        assertEquals("list, A, comments", layout.names());
        now += 10 * 60_000;                              // back in the app, the chat is in use again
        idle();
        assertFalse("a chat in use does not expire", locked(SOURCE));
        back();
        assertAuthorizedAndOnScreen(a);

        activityPaused();
        activityStopped();
        now += 61_000;
        activityResumed();
        assertTrue("beyond the minute", locked(SOURCE));
        assertEquals("list", layout.names());
    }

    @Test
    public void anExternalActivityRoundTripKeepsTheChatAsItAlwaysDid() {
        final Frag a = openSource();
        activityPaused();                                // a file picker or the camera app covers the whole screen
        activityStopped();
        state.appResumedFromActivityResult();            // onActivityResult precedes onResume
        activityResumed();
        assertFalse(locked(SOURCE));
        assertEquals("list, A", layout.names());
        assertNothingWasPopped();
        assertTrue(a.node.isVisible());
    }

    @Test
    public void leavingForAnExternalActivityAndNeverReturningWithAResultLocks() {
        openSource();
        activityPaused();
        activityStopped();                               // for example a link opened in the browser
        activityResumed();                               // the user comes back later, with no result
        assertTrue(locked(SOURCE));
        assertEquals("list", layout.names());
    }

    // ================================================================== 7: process / activity recreation

    @Test
    public void permissionUiThenTheProcessIsRecreated_staleAuthorizationIsNotTrusted() {
        openSource();
        activityPaused();
        activityStopped();
        restartProcess();                                // the process died while the dialog was up
        assertTrue("the protection persists", state.isProtected(ACC, SOURCE));
        assertTrue("the authorization does not", locked(SOURCE));
        final Frag restored = new Frag("A", SOURCE, false);
        assertFalse("the restored chat must authenticate before it is created", layout.present(restored, false, true));
        assertEquals("list", layout.names());
        authenticate(SOURCE);
        assertTrue(layout.present(restored, false, true));
        settle();
        assertEquals("list, A", layout.names());
    }

    @Test
    public void permissionUiThenTheActivityIsRecreated_theBackgroundBoundaryAlreadyApplied() {
        final Frag a = openSource();
        activityPaused();
        activityStopped();                               // onStop always precedes onDestroy
        // The activity is destroyed with its fragments and a new one restores the chat.
        a.onDestroy();
        a.parent = null;
        layout.stack.remove(a);
        final Frag restored = new Frag("A", SOURCE, false);
        activityResumed();
        assertTrue("Immediate Auto-lock applied while it was away", locked(SOURCE));
        assertFalse(layout.present(restored, false, true));
    }

    // ================================================================== 8-9: predictive back

    @Test
    public void predictiveBackStartedAndCancelled_noLockNoPopNoGlitch() {
        final Frag a = openSource();
        open(new Frag("media", 0, false));
        for (int i = 0; i < 3; i++) {
            layout.swipeStart();
            assertEquals(ProtectedGateLifecycle.NodeState.RETURNING, a.node.getState());
            idle();
            layout.swipeCancel();
            idle();
            assertEquals(ProtectedGateLifecycle.NodeState.COVERED, a.node.getState());
        }
        assertFalse(locked(SOURCE));
        assertEquals("list, A, media", layout.names());
        assertNothingWasPopped();
        back();
        assertAuthorizedAndOnScreen(a);
    }

    @Test
    public void predictiveBackCompleted_cleanRestoration() {
        final Frag a = openSource();
        final Frag media = open(new Frag("media", 0, false));
        layout.swipeStart();
        idle();
        layout.swipeComplete();
        idle();
        assertEquals("list, A", layout.names());
        assertTrue(media.destroyed);
        assertAuthorizedAndOnScreen(a);
    }

    @Test
    public void everyKindOfBackEndsInTheSameState() {
        final Frag a = openSource();
        for (int i = 0; i < 4; i++) {
            open(new Frag("child" + i, 0, false));
            switch (i) {
                case 0:   // system back / toolbar back
                    back();
                    break;
                case 1:   // no animations
                    layout.closeLast(false);
                    idle();
                    break;
                case 2:   // a swipe that completes
                    layout.swipeStart();
                    layout.swipeComplete();
                    idle();
                    break;
                default:  // a swipe that was cancelled once, then a system back
                    layout.swipeStart();
                    layout.swipeCancel();
                    idle();
                    back();
                    break;
            }
            assertEquals("list, A", layout.names());
            assertAuthorizedAndOnScreen(a);
        }
    }

    // ================================================================== 10: a locked chat reached from elsewhere

    @Test
    public void aLockedChatReachedFromUnrelatedNavigationAuthenticatesAndIsNotAChildOfAnythingInTheStack() {
        // B is open and authorized with a child of its own; from that child the user opens the locked A.
        protect(PROT_B);
        authenticate(PROT_B);
        final Frag b = new Frag("B", PROT_B, false);
        assertTrue(layout.present(b, false, true));
        settle();
        final Frag child = open(new Frag("media", 0, false));
        protect(SOURCE);

        final Frag a = new Frag("A", SOURCE, false);
        assertFalse("A is locked: it is not created until the user authenticates", layout.present(a, false, true));
        assertEquals("list, B, media", layout.names());
        authenticate(SOURCE);
        assertTrue(layout.present(a, false, true));
        settle();
        assertEquals("another chat from B's context: B was left", ProtectedGateLifecycle.NodeState.DESTROYED, b.node.getState());
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
        assertFalse(locked(SOURCE));
        assertTrue("B left, B locked", locked(PROT_B));

        // A is a context of its own: a child of A is covered by A's rules, and leaving A locks A.
        open(new Frag("media2", 0, false));
        assertFalse(locked(SOURCE));
        back();
        assertFalse(locked(SOURCE));
    }

    @Test
    public void anOldInstanceOfAChatThatWasLeftDoesNotLingerToBeRevealed() {
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));
        open(new Frag("otherChat", PLAIN_2, false));     // A is left, whatever its interval
        assertEquals(ProtectedGateLifecycle.NodeState.DESTROYED, a.node.getState());
        back();
        back();
        assertEquals("list", layout.names());
        // Opening it again goes through the gate.
        final Frag again = new Frag("A", SOURCE, false);
        assertFalse(layout.present(again, false, true));
    }

    // ================================================================== 11: comments that are themselves protected

    @Test
    public void protectedDiscussionChat_hasItsOwnAuthenticationAndAuthorization() {
        final Frag channel = openSource();
        protect(PROT_B);
        final Frag discussion = new Frag("discussion", PROT_B, false);
        assertFalse("the discussion is protected and locked: asked first, independently of the channel", layout.present(discussion, false, true));
        assertFalse("the channel was not touched", locked(SOURCE));
        authenticate(PROT_B);
        assertTrue(layout.present(discussion, false, true));
        settle();
        assertEquals(ProtectedGateLifecycle.NodeState.COVERED, channel.node.getState());
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, discussion.node.getState());
        assertFalse(locked(SOURCE));
        assertFalse(locked(PROT_B));

        // Locking the discussion does not touch the channel, and the other way round.
        manualLock(PROT_B);
        assertTrue(locked(PROT_B));
        assertFalse(locked(SOURCE));
        assertEquals("the locked discussion was closed, Back-like, without cutting anything short", "list, A", layout.names());
        assertEquals(0, layout.forcedTransitionEnds);
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, channel.node.getState());
    }

    @Test
    public void protectedDiscussionChat_leavingItLocksItAndNotTheChannel() {
        final Frag channel = openSource();
        protect(PROT_B);
        authenticate(PROT_B);
        final Frag discussion = new Frag("discussion", PROT_B, false);
        assertTrue(layout.present(discussion, false, true));
        settle();
        back();
        assertEquals("list, A", layout.names());
        assertTrue("Immediate Auto-lock for the discussion, which was left", locked(PROT_B));
        assertFalse("the channel is in use", locked(SOURCE));
        assertNothingWasPopped();
        assertTrue(channel.node.isVisible());
    }

    // ================================================================== 12: manual lock

    @Test
    public void manualLockWhileACoveringChildIsOpen_authorizationIsClearedAndCanNotBeRevived() {
        final Frag a = openSource();
        final Frag media = open(new Frag("media", 0, false));
        final int resumesBefore = a.resumes;

        manualLock(SOURCE);
        assertTrue(locked(SOURCE));
        assertEquals("it stays under its child, locked", "list, A, media", layout.names());
        assertNothingWasPopped();

        // Neither a permission round trip nor anything else on the child restores the authorization.
        activityPaused();
        activityResumed();
        assertTrue(locked(SOURCE));

        assertFalse("Back toward it does not start", layout.closeLast(true));
        idle();
        assertEquals("authentication is asked for", SOURCE, pendingReveal().dialog);
        assertEquals("the chat was not shown", resumesBefore, a.resumes);
        assertEquals("list, A, media", layout.names());
    }

    @Test
    public void manualLockOfTheVisibleChat_closesItAtOnce() {
        openSource();
        manualLock(SOURCE);
        assertEquals("list", layout.names());
        assertTrue(locked(SOURCE));
    }

    @Test
    public void manualLockDuringAForward_endsItWithTheAuthorization() {
        openSource();
        open(new Frag("picker", 0, true));
        assertEquals(ProtectedChatsState.ForwardPhase.PICKER, state.getForwardPhase(ACC, SOURCE));
        manualLock(SOURCE);
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, state.getForwardPhase(ACC, SOURCE));
        assertEquals("the covered chat stays, locked", "list, A, picker", layout.names());
        assertNothingWasPopped();
        assertFalse(layout.closeLast(true));
        idle();
        assertEquals("Back from the picker asks first", SOURCE, pendingReveal().dialog);
    }

    // ================================================================== never pop a fragment that is becoming visible

    @Test
    public void aLockedChatThatIsRevealedAnywayIsNeverClosedFromInsideTheTransition() {
        final Frag a = openSource();
        open(new Frag("media", 0, false));
        // The authorization ended without the usual closing (no notification reached the activity),
        // and the reveal is a path the layout does not gate.
        state.relock(ACC, SOURCE);
        gateRevealEnabled = false;
        idle();
        assertEquals("list, A, media", layout.names());

        assertTrue(layout.closeLast(true));
        idle();                                          // the old build closed the chat here, mid-transition
        assertNothingWasPopped();
        assertEquals("list, A, media", layout.names());
        layout.transitionEnds();
        idle();
        assertEquals("closed once the transition ended", Collections.singletonList("A"), layout.removedByGate);
        assertEquals(0, layout.forcedTransitionEnds);
        assertEquals("list", layout.names());
    }

    @Test
    public void aLockedChatRevealedByAPathTheLayoutDoesNotGateIsNeverClosedWhileTheSwipeRuns() {
        final Frag a = openSource();
        open(new Frag("media", 0, false));
        state.relock(ACC, SOURCE);
        gateRevealEnabled = false;                       // stands in for an unforeseen path
        layout.swipeStart();
        idle();
        assertNothingWasPopped();
        layout.swipeCancel();
        idle();
        assertEquals("list, A, media", layout.names());
        assertNothingWasPopped();
    }

    @Test
    public void aLockedTopFragmentFoundWhileItsOwnTransitionRunsIsClosedWhenItEnded() {
        final Frag a = new Frag("A", SOURCE, false);
        protect(SOURCE);
        authenticate(SOURCE);
        assertTrue(layout.present(a, false, true));            // A is opening: its transition is running
        state.relock(ACC, SOURCE);
        closeLockedFragments();                                // protectedChatsChanged reached the activity now
        idle();
        assertEquals("not now: the top fragment of a running transition", Collections.emptyList(), layout.removedByGate);
        assertEquals(0, layout.forcedTransitionEnds);
        layout.transitionEnds();
        idle();
        assertEquals(Collections.singletonList("A"), layout.removedByGate);
        assertEquals("list", layout.names());
        assertEquals(0, layout.forcedTransitionEnds);
    }

    // ================================================================== independence and misc

    @Test
    public void twoProtectedChatsKeepTheirOwnAuthorizations() {
        final Frag a = openSource();
        protect(PROT_B);
        authenticate(PROT_B);
        // A covered by a child, then B opened from A's context as a child: independent nodes.
        final Frag b = open(new Frag("B", PROT_B, false));
        assertFalse(locked(SOURCE));
        assertFalse(locked(PROT_B));
        manualLock(SOURCE);
        assertTrue(locked(SOURCE));
        assertFalse("B is not affected by A", locked(PROT_B));
        assertEquals("A stays under B, locked", "list, A, B", layout.names());
        assertFalse(layout.closeLast(true));
        idle();
        assertEquals("only A is asked for", SOURCE, pendingReveal().dialog);
        assertEquals(1, reveals.size());
    }

    @Test
    public void aChatThatIsNotProtectedIsNeverTouched() {
        final Frag plain = new Frag("plain", PLAIN, false);
        assertTrue(layout.present(plain, false, true));
        settle();
        open(new Frag("media", 0, false));
        activityPaused();
        activityResumed();
        back();
        assertEquals("list, plain", layout.names());
        assertNothingWasPopped();
        assertTrue(plain.node.isIdle());
    }

    @Test
    public void closingTheChatNormallyStartsTheCountdown() {
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE));
        openSource();
        layout.closeLast(true);
        settle();
        assertEquals("list", layout.names());
        assertFalse("reopened within the minute: no authentication", locked(SOURCE));
        now += 61_000;
        assertTrue(locked(SOURCE));
    }
}
