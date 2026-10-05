package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Gate before reveal. A protected chat that lost its authorization while a child covered it is not
 * closed when the user comes back; Back, a swipe and the start of a predictive back ask for
 * authentication before the chat's view is created or resumed:
 *
 * <pre>
 *   AUTHORIZED + temporary child -> Back        = returns directly, no authentication
 *   UNAUTHORIZED + Back would reveal the chat   = authentication first
 *       success -> the original Back runs once, the chat shows
 *       cancel  -> the user stays where they are, nothing was shown, nothing was closed
 * </pre>
 *
 * "Not revealed" is checked as: the chat's fragment was not resumed (its view is created and shown on
 * resume), no transition started, the stack is unchanged. See {@link GateNavigationTestBase}.
 */
public class ProtectedRevealGateTest extends GateNavigationTestBase {

    private Frag open(Frag child) {
        assertTrue(layout.present(child, false, true));
        settle();
        return child;
    }

    private void backAndSettle() {
        assertTrue(layout.closeLast(true));
        idle();
        layout.transitionEnds();
        idle();
    }

    /** Back that must not start: the gate asks for authentication instead. */
    private Reveal backIsGated() {
        assertFalse("the navigation did not start", layout.closeLast(true));
        idle();
        assertFalse("no transition started", layout.inTransition());
        final Reveal sheet = pendingReveal();
        assertNotNull("authentication is asked for", sheet);
        return sheet;
    }

    private void assertNoAuthenticationWasAsked() {
        assertEquals(0, reveals.size());
        assertNull(pendingReveal());
    }

    /** A chat that is covered, locked, and the user comes back toward it. */
    private Frag lockedUnderChild(String childName, long childDialog) {
        final Frag a = openSource();
        open(new Frag(childName, childDialog, false));
        manualLock(SOURCE);
        assertTrue(locked(SOURCE));
        assertEquals("list, A, " + childName, layout.names());
        return a;
    }

    // ================================================================== authorized: no authentication

    @Test
    public void case1_authorizedChatAndATemporaryChild_backReturnsDirectly() {
        final Frag a = openSource();
        open(new Frag("media", 0, false));
        backAndSettle();
        assertEquals("list, A", layout.names());
        assertNoAuthenticationWasAsked();
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
        assertNothingWasPopped();
    }

    @Test
    public void case2_permissionAllowed_noAuthentication() {
        final Frag a = openSource();
        activityPaused();
        activityResumed();
        assertEquals("list, A", layout.names());
        assertNoAuthenticationWasAsked();
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
        assertNothingWasPopped();
    }

    @Test
    public void case3_permissionDenied_noAuthentication() {
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));
        activityPaused();                                // the camera or microphone is asked for from the child
        activityResumed();                               // Deny
        backAndSettle();
        assertEquals("list, A", layout.names());
        assertNoAuthenticationWasAsked();
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
        assertNothingWasPopped();
    }

    @Test
    public void case4_authorizedChannelAndItsComments_backReturnsDirectly() {
        final Frag channel = openSource();
        open(new Frag("comments", PLAIN, false));        // the linked discussion chat
        backAndSettle();
        assertEquals("list, A", layout.names());
        assertNoAuthenticationWasAsked();
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, channel.node.getState());
        assertNothingWasPopped();
    }

    @Test
    public void case5_authorizedChatAndTheForwardPicker_backReturnsDirectly() {
        final Frag a = openSource();
        open(new Frag("picker", 0, true));
        backAndSettle();
        assertEquals("list, A", layout.names());
        assertNoAuthenticationWasAsked();
        assertEquals(ProtectedChatsState.ForwardPhase.NONE, state.getForwardPhase(ACC, SOURCE));
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
        assertNothingWasPopped();
    }

    // ================================================================== manual lock

    @Test
    public void case6_manualLockWhileCovered_backAsksBeforeTheChatIsShown() {
        final Frag a = lockedUnderChild("media", 0);
        final int resumesBefore = a.resumes;

        final Reveal sheet = backIsGated();
        assertEquals("for A", SOURCE, sheet.dialog);
        assertEquals("A's view was neither created nor resumed", resumesBefore, a.resumes);
        assertFalse(a.node.isVisible());
        assertEquals("the stack is untouched", "list, A, media", layout.names());
        assertNothingWasPopped();
    }

    @Test
    public void case7_authenticationSucceeds_theChatIsRevealedExactlyOnce() {
        final Frag a = lockedUnderChild("media", 0);
        final int resumesBefore = a.resumes;
        final Reveal sheet = backIsGated();

        answerUnlock();                                  // the original Back runs
        assertTrue("the Back is running now", layout.inTransition());
        assertEquals("A is resumed once, by that Back", resumesBefore + 1, a.resumes);
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
        assertEquals("one sheet", 1, reveals.size());
        assertNothingWasPopped();

        sheet.onUnlocked.run();                          // a duplicate answer does nothing
        idle();
        assertEquals("list, A", layout.names());
        assertEquals(resumesBefore + 1, a.resumes);
        assertFalse(layout.inTransition());
        assertEquals("still one sheet", 1, reveals.size());
    }

    @Test
    public void case8_authenticationCancelled_theChatIsNeverShownAndTheUserStaysSafe() {
        final Frag a = lockedUnderChild("media", 0);
        final Frag child = layout.top();
        final int resumesBefore = a.resumes;
        backIsGated();

        answerCancel();
        assertEquals("list, A, media", layout.names());
        assertEquals("A was never shown", resumesBefore, a.resumes);
        assertFalse(a.node.isVisible());
        assertFalse("the child was not closed", child.destroyed);
        assertFalse("A was not closed either", a.destroyed);
        assertNothingWasPopped();
        assertTrue(locked(SOURCE));

        // Back again asks again.
        backIsGated();
        assertEquals(2, reveals.size());
    }

    // ================================================================== background and screen off

    @Test
    public void case9_appStopsWhileCovered_authenticationBeforeTheChatOnBack() {
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));
        final int resumesBefore = a.resumes;

        activityPaused();
        activityStopped();                               // Home
        activityResumed();                               // back in the app, the child on top
        assertTrue(locked(SOURCE));
        assertEquals("list, A, comments", layout.names());
        assertNothingWasPopped();

        backIsGated();
        assertEquals(resumesBefore, a.resumes);
        answerUnlock();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertFalse(locked(SOURCE));
        assertEquals(resumesBefore + 1, a.resumes);
    }

    @Test
    public void case10_screenOffWhileCovered_authenticationBeforeTheChatOnBack() {
        final Frag a = openSource();
        open(new Frag("media", 0, false));
        final int resumesBefore = a.resumes;

        activityStopped();                               // ACTION_SCREEN_OFF
        activityResumed();                               // screen on again
        assertTrue(locked(SOURCE));
        backIsGated();
        assertEquals(resumesBefore, a.resumes);
        answerCancel();
        assertEquals("list, A, media", layout.names());
        assertEquals(resumesBefore, a.resumes);
    }

    @Test
    public void aTimedIntervalKeepsTheChatAuthorizedForAShortBackgroundAndAsksAfterALongOne() {
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE));
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));

        activityPaused();
        activityStopped();
        now += 20_000;
        activityResumed();
        backAndSettle();                                 // within the minute: directly
        assertEquals("list, A", layout.names());
        assertNoAuthenticationWasAsked();

        open(new Frag("comments2", PLAIN, false));
        activityPaused();
        activityStopped();
        now += 61_000;
        activityResumed();
        assertTrue(locked(SOURCE));
        backIsGated();                                   // beyond the minute: asks
        assertEquals("list, A, comments2", layout.names());
    }

    // ================================================================== predictive and swipe back

    @Test
    public void case11_predictiveBackOverALockedChat_showsNoPreviewOfIt() {
        final Frag a = lockedUnderChild("media", 0);
        final int resumesBefore = a.resumes;

        assertFalse("no preview starts", layout.predictiveStart());
        idle();
        assertEquals("nothing of A was created or resumed for a preview", resumesBefore, a.resumes);
        assertFalse(a.node.isVisible());
        assertFalse(layout.inTransition());
        assertNull("the sheet is asked for by the Back that follows, not by the preview", pendingReveal());
        assertEquals("list, A, media", layout.names());
    }

    @Test
    public void case12_predictiveBackAuthenticationCancelled_theTopFragmentIsIntact() {
        final Frag a = lockedUnderChild("media", 0);
        final Frag child = layout.top();
        final int resumesBefore = a.resumes;

        final boolean started = layout.predictiveStart();
        assertFalse(started);
        layout.predictiveCancelled(started);             // the gesture is given up: nothing happens
        idle();
        assertNull(pendingReveal());
        assertEquals("list, A, media", layout.names());

        final boolean started2 = layout.predictiveStart();
        layout.predictiveInvoked(started2);              // the gesture is committed: the ordinary Back
        idle();
        assertNotNull("authentication is asked for", pendingReveal());
        answerCancel();
        assertEquals("list, A, media", layout.names());
        assertFalse(child.destroyed);
        assertEquals(resumesBefore, a.resumes);
        assertFalse(a.node.isVisible());
        assertNothingWasPopped();
    }

    @Test
    public void case13_predictiveBackAuthenticationSucceeds_navigationToTheChatWorks() {
        final Frag a = lockedUnderChild("media", 0);
        final int resumesBefore = a.resumes;

        final boolean started = layout.predictiveStart();
        layout.predictiveInvoked(started);
        idle();
        assertNotNull(pendingReveal());
        answerUnlock();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertEquals(resumesBefore + 1, a.resumes);
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
    }

    @Test
    public void predictiveBackOverAnAuthorizedChat_keepsItsPreview() {
        final Frag a = openSource();
        open(new Frag("media", 0, false));
        final boolean started = layout.predictiveStart();
        assertTrue("an authorized chat is previewed as always", started);
        layout.predictiveInvoked(started);
        idle();
        assertEquals("list, A", layout.names());
        assertNoAuthenticationWasAsked();
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());
    }

    @Test
    public void aTouchSwipeOverALockedChatAsksInsteadOfStartingTheGesture() {
        final Frag a = lockedUnderChild("media", 0);
        final int resumesBefore = a.resumes;

        assertFalse("no gesture starts", layout.swipeStart());
        idle();
        assertEquals("nothing of A was shown during a swipe", resumesBefore, a.resumes);
        assertNotNull("authentication is asked for", pendingReveal());
        answerUnlock();                                  // the swipe becomes the ordinary Back
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertEquals(resumesBefore + 1, a.resumes);
    }

    @Test
    public void aTouchSwipeAuthenticationCancelledLeavesTheTopFragmentAlone() {
        final Frag a = lockedUnderChild("media", 0);
        final int resumesBefore = a.resumes;
        assertFalse(layout.swipeStart());
        idle();
        answerCancel();
        assertEquals("list, A, media", layout.names());
        assertEquals(resumesBefore, a.resumes);
        assertNothingWasPopped();
    }

    // ================================================================== one sheet, one navigation

    @Test
    public void pressingBackAgainWhileTheSheetIsUpDoesNotStackSheetsOrNavigations() {
        final Frag a = lockedUnderChild("media", 0);
        backIsGated();
        assertFalse(layout.closeLast(true));
        idle();
        assertFalse(layout.swipeStart());
        idle();
        assertEquals("one sheet", 1, reveals.size());
        answerUnlock();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertEquals(1, reveals.size());
        assertNothingWasPopped();
    }

    // ================================================================== direct access and independence

    @Test
    public void case14_unauthorizedChatReachedDirectly_theExistingGateStillApplies() {
        protect(SOURCE);
        final Frag a = new Frag("A", SOURCE, false);
        assertFalse("not created until the user authenticates", layout.present(a, false, true));
        assertTrue(a.node.isIdle());
        assertEquals("list", layout.names());
        authenticate(SOURCE);
        assertTrue(layout.present(a, false, true));
        settle();
        assertEquals("list, A", layout.names());
    }

    @Test
    public void case15_twoProtectedChatsKeepTheirOwnAuthorizations() {
        final Frag a = openSource();
        protect(PROT_B);
        authenticate(PROT_B);
        final Frag b = open(new Frag("B", PROT_B, false));

        // B is locked by hand: it is on screen, so it is closed; A is untouched.
        manualLock(PROT_B);
        assertTrue(locked(PROT_B));
        assertFalse(locked(SOURCE));
        assertEquals("list, A", layout.names());
        assertNoAuthenticationWasAsked();

        // The other way round: A locked while B is open over it: asking for A, never for B.
        authenticate(PROT_B);
        final Frag b2 = open(new Frag("B2", PROT_B, false));
        manualLock(SOURCE);
        assertFalse("B is not affected", locked(PROT_B));
        final Reveal sheet = backIsGated();
        assertEquals(SOURCE, sheet.dialog);
        assertEquals("list, A, B2", layout.names());
        answerUnlock();
        layout.transitionEnds();
        idle();
        assertEquals("list, A", layout.names());
        assertTrue("B was closed on the way: left, so locked on its own account (Immediate)", locked(PROT_B));
        assertFalse("and A is authorized again by its own authentication", locked(SOURCE));
    }

    @Test
    public void twoLockedFragmentsOnTopAreClosedTogetherWhenTheAppComesBack() {
        final Frag a = openSource();
        protect(PROT_B);
        authenticate(PROT_B);
        final Frag b = open(new Frag("B", PROT_B, false));
        activityPaused();
        activityStopped();
        activityResumed();
        assertTrue(locked(SOURCE));
        assertTrue(locked(PROT_B));
        // B is what the user was looking at: closed before it shows. A under it would need its own
        // authentication to be revealed, and B could not be closed meanwhile, so it goes too.
        assertEquals("list", layout.names());
        assertEquals("from the bottom of the locked run up", Arrays.asList("A", "B"), layout.removedByGate);
        assertNoAuthenticationWasAsked();
    }

    // ================================================================== stale state

    @Test
    public void case16_theNavigationChangesWhileTheSheetIsUp_aLateAnswerCannotTouchTheWrongFragment() {
        final Frag a = lockedUnderChild("media", 0);
        backIsGated();

        // Meanwhile the child is replaced by something else.
        final Frag other = new Frag("other", 0, false);
        assertTrue(layout.present(other, true, false));
        idle();
        assertEquals("list, A, other", layout.names());

        answerUnlock();                                  // the user authenticates for A anyway
        assertEquals("the stale Back did not run", "list, A, other", layout.names());
        assertFalse(layout.inTransition());
        assertFalse("the new top fragment was not closed", other.destroyed);
        assertNothingWasPopped();
        assertFalse("A is authorized now", locked(SOURCE));
    }

    @Test
    public void case17_manualLockWhileAChildIsOpen_noChildStateRestoresTheAuthorization() {
        final Frag a = openSource();
        open(new Frag("comments", PLAIN, false));
        manualLock(SOURCE);
        assertTrue(locked(SOURCE));

        // Everything that can happen on the child while it is open.
        activityPaused();
        activityResumed();
        open(new Frag("profile", 0, false));
        backAndSettle();
        activityPaused();
        activityStopped();
        activityResumed();
        assertTrue("nothing restored it", locked(SOURCE));
        assertEquals("list, A, comments", layout.names());
        backIsGated();
        assertEquals("only an authentication unlocks it", SOURCE, pendingReveal().dialog);
    }

    @Test
    public void case18_processRecreation_aTemporaryChildCannotRestoreTheAuthorization() {
        openSource();
        open(new Frag("comments", PLAIN, false));
        activityPaused();
        activityStopped();
        restartProcess();                                // the process died; a new one starts
        assertTrue("the protection persists", state.isProtected(ACC, SOURCE));
        assertTrue("the authorization does not", locked(SOURCE));
        final Frag restored = new Frag("A", SOURCE, false);
        assertFalse("the restored chat is gated before it is created", layout.present(restored, false, true));
        assertEquals("list", layout.names());
        assertNoAuthenticationWasAsked();
    }

    // ================================================================== the pure gate

    @Test
    public void withNoWayToAskTheRevealStaysBlocked() {
        protect(SOURCE);
        assertTrue(gate.blockReveal(ACC, SOURCE, null, () -> true, () -> fail("must not proceed")));
        idle();
        assertTrue(locked(SOURCE));
    }

    @Test
    public void anAuthorizedOrUnprotectedChatIsNeverBlocked() {
        protect(SOURCE);
        authenticate(SOURCE);
        assertFalse(gate.blockReveal(ACC, SOURCE, null, () -> true, () -> fail("not asked")));
        assertFalse(gate.blockReveal(ACC, PLAIN, null, () -> true, () -> fail("not asked")));
        assertFalse(gate.blockReveal(ACC, 0, null, () -> true, () -> fail("not asked")));
        assertFalse(gate.revealsLockedChat(ACC, SOURCE));
        assertFalse(gate.revealsLockedChat(ACC, PLAIN));
        assertTrue(Collections.<Reveal>emptyList().equals(reveals));
    }

    @Test
    public void aSheetThatIsAnsweredAfterTheAuthorizationAlreadyExistsAsksNothing() {
        final Frag a = lockedUnderChild("media", 0);
        assertFalse(layout.closeLast(true));             // blocked, the sheet is posted but not run yet
        authenticate(SOURCE);                            // authorized another way before the sheet is shown
        idle();
        assertNull("no sheet for a chat that is authorized", pendingReveal());
        assertTrue("and Back works", layout.closeLast(true));
    }
}
