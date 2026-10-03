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
 * The destination rule of a forward and the continuation that resumes it. The locks come from the
 * real protection model; the sheet is a fake that records who was asked and lets the test answer.
 * The forward itself is a recording stand-in for the original selection: what it must keep is the
 * messages (in order), the destination and its topic.
 */
public class ForwardDestinationsTest {

    private static final long ACCOUNT = 1L;
    private static final long SELF = 1000L;              // the user's own Saved Messages
    private static final long USER = 2000L;
    private static final long BOT = 3000L;               // a bot is a user dialog
    private static final long GROUP = -4000L;
    private static final long SUPERGROUP = -1001234567890L;
    private static final long CHANNEL = -1007654321098L;
    private static final long FORUM = -1005555555555L;   // a topic lives inside this dialog
    private static final long SECRET = ProtectedDialogIds.fromArgs(0, 0, 77, 0, false);
    private static final long[] EVERY_KIND_OF_DESTINATION = {USER, BOT, GROUP, SUPERGROUP, CHANNEL, FORUM, SECRET};

    private long now;
    private ProtectedChatsState state;

    /** One question the fake sheet was asked. */
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

    /** What the original forward did: which messages went where, in which order. */
    private static final class Sent {
        final List<Integer> messageIds;
        final long dialogId;
        final long topicId;

        Sent(List<Integer> messageIds, long dialogId, long topicId) {
            this.messageIds = messageIds;
            this.dialogId = dialogId;
            this.topicId = topicId;
        }
    }

    private final List<Sent> sent = new ArrayList<>();

    @Before
    public void setUp() {
        now = 10_000;
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
    }

    private void protectLocked(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACCOUNT, dialog, state.proofFromPasscode("1234", null)));
        state.relock(ACCOUNT, dialog);
    }

    private void unlock(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACCOUNT, dialog, state.proofFromPasscode("1234", null)));
    }

    private static List<Long> ids(long... dialogs) {
        final List<Long> list = new ArrayList<>();
        for (long d : dialogs) list.add(d);
        return list;
    }

    /** The user types the passcode correctly: the model unlocks, then the sheet reports success. */
    private void unlockAndConfirm(Request request) {
        unlock(request.dialogId);
        request.onUnlocked.run();
    }

    /** The original forward, with the arguments it had when the user chose. */
    private Runnable forward(List<Integer> messageIds, long dialogId, long topicId) {
        return () -> sent.add(new Sent(new ArrayList<>(messageIds), dialogId, topicId));
    }

    private boolean hold(List<Long> destinations, Runnable operation) {
        return ForwardDestinations.holdUntilUnlocked(destinations, SELF, true, locks, sheet, operation);
    }

    // ---- destination policy ---------------------------------------------------------------------

    @Test
    public void lockedSavedMessagesIsForwardedToWithoutAuthentication() {
        protectLocked(SELF);
        assertTrue(state.isLockedProtected(ACCOUNT, SELF));
        assertFalse(ForwardDestinations.needsAuthentication(SELF, SELF, true, locks));
        assertFalse("the caller carries on by itself", hold(ids(SELF), forward(Arrays.asList(1), SELF, 0)));
        assertTrue("nobody was asked", asked.isEmpty());
        assertTrue("and the helper ran nothing: the caller deposits inline", sent.isEmpty());
    }

    @Test
    public void authorizedSavedMessagesIsForwardedToWithoutAuthentication() {
        protectLocked(SELF);
        unlock(SELF);
        assertFalse(state.isLockedProtected(ACCOUNT, SELF));
        assertFalse(ForwardDestinations.needsAuthentication(SELF, SELF, true, locks));
        assertFalse(hold(ids(SELF), forward(Arrays.asList(1), SELF, 0)));
        assertTrue(asked.isEmpty());
    }

    @Test
    public void everyOtherKindOfProtectedDestinationAsksWhenLocked() {
        for (long destination : EVERY_KIND_OF_DESTINATION) {
            asked.clear();
            protectLocked(destination);
            assertTrue("kind " + destination, ForwardDestinations.needsAuthentication(destination, SELF, true, locks));
            assertTrue("kind " + destination, hold(ids(destination), forward(Arrays.asList(1), destination, 0)));
            assertEquals("kind " + destination, 1, asked.size());
            assertEquals("kind " + destination, destination, asked.get(0).dialogId);
        }
    }

    @Test
    public void userBotGroupSupergroupChannelTopicAndSecretChatFollowTheSameRule() {
        // No kind of destination is special: the decision reads the lock and the own Saved Messages only.
        for (long destination : EVERY_KIND_OF_DESTINATION) {
            assertFalse("not protected: " + destination, ForwardDestinations.needsAuthentication(destination, SELF, true, locks));
            protectLocked(destination);
            assertTrue("locked: " + destination, ForwardDestinations.needsAuthentication(destination, SELF, true, locks));
            unlock(destination);
            assertFalse("authorized: " + destination, ForwardDestinations.needsAuthentication(destination, SELF, true, locks));
        }
    }

    @Test
    public void anotherProtectedDestinationThatIsAlreadyAuthorizedDoesNotAskAgain() {
        protectLocked(USER);
        unlock(USER);
        assertFalse(hold(ids(USER), forward(Arrays.asList(1, 2), USER, 0)));
        assertTrue(asked.isEmpty());
    }

    @Test
    public void aShareToTheOwnSavedMessagesStillAsksBecauseItOpensTheChat() {
        protectLocked(SELF);
        assertTrue(ForwardDestinations.needsAuthentication(SELF, SELF, false, locks));
        assertTrue(ForwardDestinations.holdUntilUnlocked(ids(SELF), SELF, false, locks, sheet, forward(Arrays.asList(1), SELF, 0)));
        assertEquals(SELF, asked.get(0).dialogId);
    }

    @Test
    public void onlyTheLockedDestinationsOfASelectionAsk() {
        protectLocked(SELF);
        protectLocked(USER);
        protectLocked(GROUP);
        unlock(GROUP);
        assertTrue(hold(ids(SELF, GROUP, USER), forward(Arrays.asList(1), USER, 0)));
        assertEquals(1, asked.size());
        assertEquals("only the locked one", USER, asked.get(0).dialogId);
    }

    // ---- the continuation -------------------------------------------------------------------------

    @Test
    public void successfulAuthenticationResumesTheOriginalForwardExactlyOnce() {
        protectLocked(USER);
        assertTrue(hold(ids(USER), forward(Arrays.asList(5), USER, 0)));
        assertTrue("nothing is sent while the sheet is up", sent.isEmpty());
        final Request request = asked.get(0);
        unlockAndConfirm(request);
        assertEquals(1, sent.size());
        assertEquals(USER, sent.get(0).dialogId);
        // The sheet reporting success again (or late) must not send a second time.
        request.onUnlocked.run();
        request.onUnlocked.run();
        assertEquals("exactly once", 1, sent.size());
    }

    @Test
    public void cancellingAuthenticationForwardsNothing() {
        protectLocked(USER);
        assertTrue(hold(ids(USER), forward(Arrays.asList(5, 6), USER, 0)));
        asked.get(0).onCancelled.run();
        assertTrue("zero messages", sent.isEmpty());
        assertTrue("the destination was not authorized", state.isLockedProtected(ACCOUNT, USER));
    }

    @Test
    public void aCancelledPendingForwardCanNeverFireLater() {
        protectLocked(USER);
        assertTrue(hold(ids(USER), forward(Arrays.asList(5), USER, 0)));
        final Request cancelled = asked.get(0);
        cancelled.onCancelled.run();

        // Later the user unlocks that very chat for an unrelated reason (opening it): the sheet
        // that was cancelled is long gone, but even its callback reaching us now sends nothing.
        unlock(USER);
        cancelled.onUnlocked.run();
        assertTrue(sent.isEmpty());

        // And a new, unrelated authentication of the same chat does not revive it either.
        state.relock(ACCOUNT, USER);
        assertTrue(hold(ids(USER), forward(Arrays.asList(9), USER, 0)));
        unlockAndConfirm(asked.get(1));
        assertEquals("only the new forward ran", 1, sent.size());
        assertEquals(Arrays.asList(9), sent.get(0).messageIds);
    }

    @Test
    public void everyMessageOfAMultipleSelectionIsKeptInOrderAndSentOnce() {
        protectLocked(USER);
        final List<Integer> selection = Arrays.asList(11, 12, 13, 14, 15);
        assertTrue(hold(ids(USER), forward(selection, USER, 0)));
        unlockAndConfirm(asked.get(0));
        assertEquals(1, sent.size());
        assertEquals("all five, same order, nothing dropped or doubled", selection, sent.get(0).messageIds);
    }

    @Test
    public void aCancelledMultipleSelectionSendsNoMessage() {
        protectLocked(USER);
        assertTrue(hold(ids(USER), forward(Arrays.asList(11, 12, 13, 14, 15), USER, 0)));
        asked.get(0).onCancelled.run();
        assertTrue(sent.isEmpty());
    }

    @Test
    public void aTopicDestinationIsKeptAfterAuthentication() {
        protectLocked(FORUM);
        final long topic = 77;
        assertTrue(hold(ids(FORUM), forward(Arrays.asList(1, 2), FORUM, topic)));
        assertEquals("the forum dialog is what is locked", FORUM, asked.get(0).dialogId);
        unlockAndConfirm(asked.get(0));
        assertEquals(1, sent.size());
        assertEquals(FORUM, sent.get(0).dialogId);
        assertEquals("the same topic, not the forum's general thread", topic, sent.get(0).topicId);
    }

    @Test
    public void severalLockedDestinationsAskInTurnAndTheForwardRunsOnce() {
        protectLocked(USER);
        protectLocked(GROUP);
        assertTrue(hold(ids(USER, GROUP), forward(Arrays.asList(1, 2, 3), USER, 0)));
        assertEquals(1, asked.size());
        assertEquals(USER, asked.get(0).dialogId);
        unlockAndConfirm(asked.get(0));
        assertTrue("not before every destination is open", sent.isEmpty());
        assertEquals(2, asked.size());
        assertEquals(GROUP, asked.get(1).dialogId);
        unlockAndConfirm(asked.get(1));
        assertEquals(1, sent.size());
    }

    @Test
    public void cancellingTheSecondOfTwoDestinationsSendsNothingToEither() {
        protectLocked(USER);
        protectLocked(GROUP);
        assertTrue(hold(ids(USER, GROUP), forward(Arrays.asList(1), USER, 0)));
        unlockAndConfirm(asked.get(0));
        asked.get(1).onCancelled.run();
        assertTrue(sent.isEmpty());
        asked.get(1).onUnlocked.run();
        assertTrue("and a late success does not revive it", sent.isEmpty());
    }

    // ---- Saved Messages: write only -------------------------------------------------------------------

    @Test
    public void forwardingIntoLockedSavedMessagesDoesNotAuthorizeItForReading() {
        protectLocked(SELF);
        assertFalse(hold(ids(SELF), forward(Arrays.asList(1), SELF, 0)));
        // The caller deposits inline. Nothing in the model changed: it is as locked as before.
        assertTrue(state.isLockedProtected(ACCOUNT, SELF));
        assertFalse(state.isUnlocked(ACCOUNT, SELF));
        assertFalse("no authorization record means no grace window either", state.canManuallyRelock(ACCOUNT, SELF));
    }

    @Test
    public void forwardingIntoLockedSavedMessagesDoesNotExposeItsExistingContent() {
        protectLocked(SELF);
        hold(ids(SELF), forward(Arrays.asList(1, 2), SELF, 0));
        // Every content rule reads the lock state, and it is unchanged.
        assertTrue("previews of Saved Messages stay hidden", state.shouldHideContent(ACCOUNT, SELF));
        assertFalse("external replies stay closed", state.allowsExternalInteraction(ACCOUNT, SELF));
        assertFalse("the profile still withholds its Saved Messages tabs",
                ProfileContentPolicy.tabAvailable(ProfileContentPolicy.TAB_SAVED_MESSAGES, state.isLockedProtected(ACCOUNT, SELF), state.isLockedProtected(ACCOUNT, SELF)));
        assertFalse(ProfileContentPolicy.tabAvailable(ProfileContentPolicy.TAB_PHOTOVIDEO, state.isLockedProtected(ACCOUNT, SELF), state.isLockedProtected(ACCOUNT, SELF)));
        assertTrue("and opening it still needs authentication", state.isLockedProtected(ACCOUNT, SELF));
    }

    @Test
    public void depositIntoSavedMessagesNextToALockedChatAsksOnlyForTheChat() {
        protectLocked(SELF);
        protectLocked(USER);
        assertTrue(hold(ids(SELF, USER), forward(Arrays.asList(1), USER, 0)));
        assertEquals(1, asked.size());
        assertEquals(USER, asked.get(0).dialogId);
        unlockAndConfirm(asked.get(0));
        assertEquals(1, sent.size());
        assertTrue("Saved Messages stayed locked throughout", state.isLockedProtected(ACCOUNT, SELF));
    }

    @Test
    public void withoutAnyProtectedChatNothingIsEverAsked() {
        for (long destination : EVERY_KIND_OF_DESTINATION) {
            assertFalse(hold(ids(destination), forward(Arrays.asList(1), destination, 0)));
        }
        assertTrue(asked.isEmpty());
    }
}
