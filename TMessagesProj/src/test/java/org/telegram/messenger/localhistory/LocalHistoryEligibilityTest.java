package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.localhistory.LocalHistoryEligibility.SourceMessage;
import org.telegram.messenger.localhistory.LocalHistoryEligibility.UserFacts;

public class LocalHistoryEligibilityTest {

    private static final long SELF = 100;
    private static final long ALICE = 555;
    private static final int NOW = 10_000;
    private static final UserFacts PERSON = new UserFacts(false, false, false);

    private static SourceMessage msg() {
        return new SourceMessage(false, ALICE, false, true, 0, 0, NOW - 100);
    }

    private static boolean ok(long dialog, SourceMessage m, UserFacts u, boolean deletion) {
        return LocalHistoryEligibility.isEligible(SELF, dialog, m, u, NOW, deletion);
    }

    @Test
    public void ordinaryIncomingMessageIsEligible() {
        assertTrue(ok(ALICE, msg(), PERSON, false));
        assertTrue(ok(ALICE, msg(), PERSON, true));
    }

    @Test
    public void nullFromIdIsAccepted() {
        assertTrue(ok(ALICE, new SourceMessage(false, 0, false, true, 0, 0, NOW), PERSON, false));
    }

    @Test
    public void deletedAccountsStayEligible() {
        assertTrue(ok(ALICE, msg(), new UserFacts(false, false, true), true));
    }

    @Test
    public void dialogKindsOtherThanUsersAreRejected() {
        assertFalse(ok(-ALICE, msg(), PERSON, false));
        assertFalse(ok(0x4000000000000000L | 5, msg(), PERSON, false)); // secret chat
        assertFalse(ok(0x2000000000000000L | 1, msg(), PERSON, false)); // folder
        assertFalse(ok(LocalDialogIds.LOCAL_HISTORY, msg(), PERSON, false));
        assertFalse(ok(0, msg(), PERSON, false));
    }

    @Test
    public void savedMessagesAreRejected() {
        assertFalse(ok(SELF, new SourceMessage(false, SELF, false, true, 0, 0, NOW), PERSON, false));
    }

    @Test
    public void outgoingAndForeignSendersAreRejected() {
        assertFalse(ok(ALICE, new SourceMessage(true, SELF, false, true, 0, 0, NOW), PERSON, false));
        assertFalse(ok(ALICE, new SourceMessage(false, 999, false, true, 0, 0, NOW), PERSON, false));
        assertFalse(ok(ALICE, new SourceMessage(false, 0, true, true, 0, 0, NOW), PERSON, false));
    }

    @Test
    public void serviceAndEmptyMessagesAreRejected() {
        assertFalse(ok(ALICE, new SourceMessage(false, ALICE, false, false, 0, 0, NOW), PERSON, false));
    }

    @Test
    public void botsSelfAndUnknownUsersAreRejected() {
        assertFalse(ok(ALICE, msg(), new UserFacts(true, false, false), false));
        assertFalse(ok(ALICE, msg(), new UserFacts(false, true, false), false));
        assertFalse(ok(ALICE, msg(), null, false));
    }

    @Test
    public void serviceAccountsAreRejected() {
        for (long id : new long[]{333000, 777000, 42777, 1271266957L, 708513L, 489000L, 2666000L}) {
            assertFalse("id " + id, ok(id, new SourceMessage(false, id, false, true, 0, 0, NOW), PERSON, false));
        }
    }

    @Test
    public void selfDestructingMediaIsRejected() {
        assertFalse(ok(ALICE, new SourceMessage(false, ALICE, false, true, 10, 0, NOW), PERSON, false));
    }

    @Test
    public void autoDeleteExpiryIsNotADeletionButEditsAreUnaffected() {
        int date = NOW - 1000;
        // expires at NOW + 500: still far from expiry (more than 60 s away)
        assertTrue(ok(ALICE, new SourceMessage(false, ALICE, false, true, 0, 1500, date), PERSON, true));
        // expired or within the 60 s slack: this is the timer, not a person
        assertFalse(ok(ALICE, new SourceMessage(false, ALICE, false, true, 0, 1000, date), PERSON, true));
        assertFalse(ok(ALICE, new SourceMessage(false, ALICE, false, true, 0, 1030, date), PERSON, true));
        assertFalse(ok(ALICE, new SourceMessage(false, ALICE, false, true, 0, 900, date), PERSON, true));
        // edits do not depend on the timer
        assertTrue(ok(ALICE, new SourceMessage(false, ALICE, false, true, 0, 900, date), PERSON, false));
    }
}
