package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The per-chat Lock Settings against the navigation model: the lifecycle, the gate before reveal and
 * the protection of a chat that is open, driven through the same stand-in for the navigation layout
 * and the host activity as the other gate tests ({@link GateNavigationTestBase}).
 *
 * The base runs with Immediate Auto-lock on the Protected Chats page; tests that need another value
 * for the page say so, and the dialog's own value is set with {@code state.setChatRelockSeconds}.
 */
public class ProtectedChatSettingsNavigationTest extends GateNavigationTestBase {

    private Frag child(String name, long dialog) {
        final Frag f = new Frag(name, dialog, false);
        assertTrue(layout.present(f, false, true));
        settle();
        return f;
    }

    private void own(long dialog, int relockSeconds) {
        assertEquals(ProtectedChatsState.Result.OK, state.setChatRelockSeconds(ACC, dialog, relockSeconds));
    }

    // ------------------------------------------------------------------ Auto-lock in the lifecycle

    @Test
    public void g_everyChatsLifecycleUsesItsOwnAutoLock() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        own(SOURCE, ProtectedChatsState.RELOCK_IMMEDIATELY);
        own(PROT_B, ProtectedChatsState.RELOCK_5_MINUTES);
        protect(SOURCE);
        protect(PROT_B);
        authenticate(SOURCE);
        authenticate(PROT_B);
        final Frag a = new Frag("A", SOURCE, false);
        assertTrue(layout.present(a, false, true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list", layout.names());
        assertTrue("A: Immediate, locked as soon as it was left", locked(SOURCE));

        authenticate(PROT_B);
        final Frag b = new Frag("B", PROT_B, false);
        assertTrue(layout.present(b, false, true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        now += 4 * 60_000;
        assertFalse("B: within its own five minutes", locked(PROT_B));
        now += 2 * 60_000;
        assertTrue("B: after its own five minutes", locked(PROT_B));
        assertTrue("A is not affected by B", locked(SOURCE));
        assertTrue(layout.removedByGate.isEmpty());
    }

    @Test
    public void h_aTemporaryChildOverAChatIsNotADepartureWhateverItsAutoLockIs() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        final Frag a = openSource();
        own(SOURCE, ProtectedChatsState.RELOCK_IMMEDIATELY);
        final Frag profile = child("profile", SOURCE);
        final Frag media = child("media", 0);
        now += 3 * 60 * 60_000L;
        idle();
        assertFalse("covered is not left, however long and however short its Auto-lock", locked(SOURCE));

        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A", layout.names());
        assertTrue("Back found the chat as it was: no authentication", reveals.isEmpty());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));

        // genuinely leaving it is when its own Auto-lock starts: Immediate
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(locked(SOURCE));
        assertEquals("the chat was resumed once more, by Back", 2, a.resumes);
        assertEquals(2, profile.resumes);
        assertEquals(1, media.resumes);
    }

    @Test
    public void permissionDialogAndCommentsStayTemporaryWithAnOwnAutoLock() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        openSource();
        own(SOURCE, ProtectedChatsState.RELOCK_IMMEDIATELY);
        // a system permission dialog: the activity pauses and resumes, nothing is navigation
        activityPaused();
        activityResumed();
        assertFalse(locked(SOURCE));
        assertNothingWasPopped();
        // the comments: the discussion chat is protected too and has its own policy
        protect(PROT_C);
        authenticate(PROT_C);
        own(PROT_C, ProtectedChatsState.RELOCK_IMMEDIATELY);
        final Frag comments = child("comments", PROT_C);
        assertFalse(locked(SOURCE));
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A", layout.names());
        assertTrue(reveals.isEmpty());
        assertNothingWasPopped();
        assertTrue(comments.destroyed);
        assertFalse(locked(SOURCE));
    }

    @Test
    public void forwardPickerOverAChatWithAnOwnAutoLockKeepsTheSource() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        final Frag a = openSource();
        own(SOURCE, ProtectedChatsState.RELOCK_IMMEDIATELY);
        final Frag picker = new Frag("picker", 0, true);
        assertTrue(layout.present(picker, false, true));
        settle();
        assertFalse(locked(SOURCE));
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A", layout.names());
        assertTrue("cancelling the picker does not ask and does not kick", reveals.isEmpty());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertEquals(2, a.resumes);
    }

    @Test
    public void i_realBackgroundAppliesTheChatsOwnAutoLock_shortStaysAuthorized() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        final Frag a = openSource();
        own(SOURCE, ProtectedChatsState.RELOCK_1_MINUTE);
        child("media", 0);
        activityStopped();
        now += 30_000;
        activityResumed();
        assertFalse("30 seconds is within the chat's own minute", locked(SOURCE));
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A", layout.names());
        assertTrue(reveals.isEmpty());
        assertEquals(2, a.resumes);
    }

    @Test
    public void i_j_realBackgroundPastTheChatsOwnAutoLock_authenticatesBeforeTheChatAppears() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);     // the page alone would keep A open
        final Frag a = openSource();
        own(SOURCE, ProtectedChatsState.RELOCK_1_MINUTE);
        final Frag media = child("media", 0);
        activityStopped();
        now += 90_000;
        activityResumed();
        assertTrue("A's own minute ran out", locked(SOURCE));
        assertEquals("the screen on top stays, A stays under it", "list, A, media", layout.names());

        final int resumesBefore = a.resumes;
        assertFalse("Back does not start", layout.closeLast(true));
        idle();
        assertEquals(1, reveals.size());
        assertEquals(SOURCE, reveals.get(0).dialog);
        assertEquals("A was not shown", resumesBefore, a.resumes);
        assertFalse(a.destroyed);

        // a second Back while the sheet is up swallows, no second sheet
        assertFalse(layout.closeLast(true));
        idle();
        assertEquals(1, reveals.size());

        // cancel: the user stays where they are, nothing removed, A never revealed
        answerCancel();
        assertEquals("list, A, media", layout.names());
        assertEquals(resumesBefore, a.resumes);
        assertFalse(media.destroyed);
        assertNothingWasPopped();

        // Back asks again; success reveals A exactly once
        assertFalse(layout.closeLast(true));
        idle();
        answerUnlock();
        settle();
        assertEquals("list, A", layout.names());
        assertEquals("A was revealed exactly once", resumesBefore + 1, a.resumes);
        assertEquals(2, reveals.size());
        assertFalse(locked(SOURCE));
        assertNothingWasPopped();
    }

    @Test
    public void j_predictiveBackOverAnExpiredChatShowsNoPreviewAndThenAsks() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_HOUR);
        final Frag a = openSource();
        own(SOURCE, ProtectedChatsState.RELOCK_1_MINUTE);
        child("media", 0);
        activityStopped();
        now += 120_000;
        activityResumed();
        final int resumesBefore = a.resumes;
        final boolean started = layout.predictiveStart();
        assertFalse("no preview of the locked chat", started);
        assertEquals(resumesBefore, a.resumes);
        layout.predictiveInvoked(started);
        idle();
        assertEquals(1, reveals.size());
        assertEquals(resumesBefore, a.resumes);
        answerCancel();
        assertEquals("list, A, media", layout.names());
    }

    @Test
    public void i_ownAutoLockLongerThanThePage_keepsTheChatAuthorizedThroughALongBackground() {
        // the page says Immediate, the chat's own value says an hour
        own(SOURCE, ProtectedChatsState.RELOCK_1_HOUR);
        final Frag a = openSource();
        child("media", 0);
        activityStopped();
        now += 10 * 60_000;
        activityResumed();
        assertFalse(locked(SOURCE));
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A", layout.names());
        assertTrue(reveals.isEmpty());
        assertEquals(2, a.resumes);
    }

    // ------------------------------------------------------------------ Chat Lock ON from the profile

    private Frag openUnprotectedChatWithItsSettings() {
        final Frag a = new Frag("A", SOURCE, false);
        assertTrue(layout.present(a, false, true));
        settle();
        assertFalse(state.isProtected(ACC, SOURCE));
        child("profile", SOURCE);
        child("settings", SOURCE);
        assertEquals("list, A, profile, settings", layout.names());
        return a;
    }

    @Test
    public void a_chatLockOnFromTheProfile_protectsTheChatEverywhereAndItStaysInUse() {
        final Frag a = openUnprotectedChatWithItsSettings();
        own(SOURCE, ProtectedChatsState.RELOCK_IMMEDIATELY);
        assertEquals(ProtectedChatsState.Result.OK, protectWhileOpen(SOURCE));
        assertTrue(state.isProtected(ACC, SOURCE));
        assertEquals(1, state.protectedCount());
        assertFalse("the user just proved the passcode", locked(SOURCE));
        assertTrue("the fragments that were open are tracked now", a.node.isRegistered());
        assertEquals(ProtectedGateLifecycle.NodeState.COVERED, a.node.getState());

        // Back to the chat they were viewing: no second authentication, even with Immediate
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A", layout.names());
        assertTrue(reveals.isEmpty());
        assertNothingWasPopped();
        assertFalse(locked(SOURCE));
        assertEquals(ProtectedGateLifecycle.NodeState.VISIBLE, a.node.getState());

        // leaving the chat starts its own Auto-lock
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(locked(SOURCE));
        assertFalse("and it is gated like every protected chat", layout.present(new Frag("again", SOURCE, false), false, true));
    }

    @Test
    public void a_theChatStaysInUseAsLongAsItsFragmentsAreOpen_noMatterHowLongTheUserStaysOnTheSettings() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE);
        openUnprotectedChatWithItsSettings();
        assertEquals(ProtectedChatsState.Result.OK, protectWhileOpen(SOURCE));
        now += 20 * 60_000;
        idle();
        assertFalse(locked(SOURCE));
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(reveals.isEmpty());
        assertEquals("list, A", layout.names());
    }

    @Test
    public void a_theAppGoingToTheBackgroundOnTheSettingsStillEndsTheAuthorization() {
        state.setRelockSeconds(ProtectedChatsState.RELOCK_1_MINUTE);
        final Frag a = openUnprotectedChatWithItsSettings();
        assertEquals(ProtectedChatsState.Result.OK, protectWhileOpen(SOURCE));
        activityStopped();
        now += 120_000;
        activityResumed();
        assertTrue("a security boundary applies to a chat protected a moment ago too", locked(SOURCE));
        // the settings screen is part of the chat and is on top: it is closed with the locked run
        assertEquals("list", layout.names());
        assertTrue(a.destroyed);
    }

    @Test
    public void a_onlyTheContextOnTopOfTheStackIsAdopted() {
        // A -> B (another conversation) -> A's profile -> A's settings: the first A was reached through B
        final Frag a = new Frag("A", SOURCE, false);
        assertTrue(layout.present(a, false, true));
        settle();
        final Frag b = child("B", PLAIN);
        final Frag profile = child("profile", SOURCE);
        final Frag settings = child("settings", SOURCE);
        own(SOURCE, ProtectedChatsState.RELOCK_IMMEDIATELY);
        assertEquals(ProtectedChatsState.Result.OK, protectWhileOpen(SOURCE));
        assertFalse("the chat under another conversation is not in use", a.node.isRegistered());
        assertTrue(profile.node.isRegistered());
        assertTrue(settings.node.isRegistered());
        assertFalse(locked(SOURCE));

        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A, B", layout.names());
        assertTrue(reveals.isEmpty());
        assertTrue("every fragment of the chat was left: Immediate", locked(SOURCE));

        // revealing the first A is gated like any other locked chat
        final int before = a.resumes;
        assertFalse("Back does not start", layout.closeLast(true));
        idle();
        assertEquals(1, reveals.size());
        assertEquals(SOURCE, reveals.get(0).dialog);
        assertEquals(before, a.resumes);
    }

    @Test
    public void a_enablingForAChatThatIsNotOpenCountsItsAutoLockFromNow() {
        own(SOURCE, ProtectedChatsState.RELOCK_IMMEDIATELY);
        // nothing of the chat is in the stack (the own profile as a main tab, say)
        assertEquals(ProtectedChatsState.Result.OK, protectWhileOpen(SOURCE));
        assertTrue(state.isProtected(ACC, SOURCE));
        assertTrue("nothing keeps it in use: it is not authorized forever", locked(SOURCE));
    }

    @Test
    public void c_cancelledAuthenticationWhileEnabling_adoptsNothingAndProtectsNothing() {
        final Frag a = openUnprotectedChatWithItsSettings();
        // the sheet was dismissed: the page never calls protectOpen with a proof; a null proof is refused
        final java.util.List<ProtectedGateLifecycle.StackEntry> entries = new java.util.ArrayList<>();
        for (Frag f : layout.stack) {
            entries.add(new ProtectedGateLifecycle.StackEntry(f.node, f.dialog, () -> layout.tryClose(f)));
        }
        assertEquals(ProtectedChatsState.Result.NOT_AUTHENTICATED, gate.protectOpen(ACC, SOURCE, null, entries));
        assertFalse(state.isProtected(ACC, SOURCE));
        assertFalse(a.node.isRegistered());
        assertEquals(0, state.protectedCount());
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A, profile", layout.names());
        assertTrue(reveals.isEmpty());
    }

    // ------------------------------------------------------------------ Chat Lock OFF from the profile

    @Test
    public void s_chatLockOffFromTheProfile_cleansUpWithoutClosingOrGlitchingWhatIsViewed() {
        final Frag a = openSource();
        final Frag profile = child("profile", SOURCE);
        final Frag settings = child("settings", SOURCE);
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC, SOURCE, state.proofFromPasscode("1234", null)));
        idle();
        closeLockedFragments();                     // protectedChatsChanged closes what is locked: nothing is
        idle();
        assertFalse(state.isProtected(ACC, SOURCE));
        assertEquals("list, A, profile, settings", layout.names());
        assertNothingWasPopped();

        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list", layout.names());
        assertTrue("an unprotected chat is never asked for", reveals.isEmpty());
        assertNothingWasPopped();
        assertTrue(a.destroyed && profile.destroyed && settings.destroyed);
        assertFalse(locked(SOURCE));
    }

    @Test
    public void s_chatLockOffThenOnAgainOnTheSameScreens() {
        openSource();
        child("profile", SOURCE);
        child("settings", SOURCE);
        own(SOURCE, ProtectedChatsState.RELOCK_IMMEDIATELY);
        assertEquals(ProtectedChatsState.Result.OK, state.unprotect(ACC, SOURCE, state.proofFromPasscode("1234", null)));
        idle();
        assertEquals(ProtectedChatsState.Result.OK, protectWhileOpen(SOURCE));
        assertFalse(locked(SOURCE));
        assertEquals(1, state.protectedCount());
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A", layout.names());
        assertTrue(reveals.isEmpty());
        assertNothingWasPopped();
    }

    // ------------------------------------------------------------------ other chats, storage

    @Test
    public void m_anUnprotectedChatWithSavedSettingsIsOpenedAndLeftLikeAnyOtherChat() {
        assertEquals(ProtectedChatsState.Result.OK, state.setChatHidePreview(ACC, PLAIN, true));
        own(PLAIN, ProtectedChatsState.RELOCK_IMMEDIATELY);
        final Frag p = new Frag("P", PLAIN, false);
        assertTrue("not gated", layout.present(p, false, true));
        settle();
        assertFalse(p.node.isRegistered());
        child("profile", PLAIN);
        activityStopped();
        now += 60 * 60_000L;
        activityResumed();
        assertEquals("list, P, profile", layout.names());
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(layout.closeLast(true));
        settle();
        assertTrue(reveals.isEmpty());
        assertNothingWasPopped();
        assertFalse(state.shouldHideContent(ACC, PLAIN));
    }

    @Test
    public void k_processDeathKeepsTheSettingsAndNeverTheAuthorization() {
        openSource();
        own(SOURCE, ProtectedChatsState.RELOCK_1_HOUR);
        assertEquals(ProtectedChatsState.Result.OK, state.setChatHidePreview(ACC, SOURCE, false));
        restartProcess();
        assertTrue(state.isProtected(ACC, SOURCE));
        assertEquals(ProtectedChatsState.RELOCK_1_HOUR, state.getRelockSeconds(ACC, SOURCE));
        assertFalse(state.getHidePreview(ACC, SOURCE));
        assertTrue(locked(SOURCE));
        assertFalse("the restored chat is gated", layout.present(new Frag("A", SOURCE, false), false, true));
    }

    @Test
    public void independentProtectedChatsKeepTheirOwnPolicyAndAuthorization() {
        own(SOURCE, ProtectedChatsState.RELOCK_1_HOUR);
        own(PROT_B, ProtectedChatsState.RELOCK_IMMEDIATELY);
        final Frag a = openSource();
        protect(PROT_B);
        authenticate(PROT_B);
        final Frag media = child("media", 0);
        activityStopped();
        now += 5 * 60_000;
        activityResumed();
        assertFalse("A: its own hour", locked(SOURCE));
        assertTrue("B: Immediate", locked(PROT_B));
        assertTrue(layout.closeLast(true));
        settle();
        assertEquals("list, A", layout.names());
        assertTrue(reveals.isEmpty());
        assertNotNull(media);
        assertEquals(2, a.resumes);
    }
}
