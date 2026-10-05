package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.telegram.messenger.localhistory.LocalHistoryLedger.Snapshot;

/** Unread is local to the feature: entries whose last event is after the stored read marker (plan B21). */
public class LocalHistoryUnreadTest {

    private static Snapshot snap(int mid, String text, int editDate) {
        return new Snapshot(555, mid, 1000, editDate, text.hashCode(), text.getBytes(), text, "Alice");
    }

    @Test
    public void newEventsAfterTheReadMarkerAreUnread() {
        InMemoryRepository repo = new InMemoryRepository();
        LocalHistoryLedger ledger = new LocalHistoryLedger(repo);
        ledger.recordEdit(snap(1, "a", 0), snap(1, "b", 100), 101);
        ledger.recordEdit(snap(2, "a", 0), snap(2, "b", 100), 102);
        assertEquals(2, repo.countAfter(0));
        repo.setMeta("last_read_at", "102");
        assertEquals(0, repo.countAfter(102));
        List<Snapshot> removed = new ArrayList<>();
        removed.add(snap(3, "x", 0));
        ledger.recordDeletions(removed, 200);
        assertEquals(1, repo.countAfter(102));
    }

    @Test
    public void anEditedEntryBecomesUnreadAgain() {
        InMemoryRepository repo = new InMemoryRepository();
        LocalHistoryLedger ledger = new LocalHistoryLedger(repo);
        ledger.recordEdit(snap(1, "a", 0), snap(1, "b", 100), 101);
        assertEquals(0, repo.countAfter(101));
        ledger.recordEdit(snap(1, "b", 100), snap(1, "c", 300), 301);
        assertEquals(1, repo.countAfter(101));
    }

    @Test
    public void summaryCountsMatchTheLedger() {
        InMemoryRepository repo = new InMemoryRepository();
        LocalHistoryLedger ledger = new LocalHistoryLedger(repo);
        ledger.recordEdit(snap(1, "a", 0), snap(1, "b", 100), 101);
        List<Snapshot> removed = new ArrayList<>();
        removed.add(snap(2, "x", 0));
        removed.add(snap(1, "b", 100));
        ledger.recordDeletions(removed, 200);
        assertEquals(2, repo.entryCount());
        assertEquals(2, repo.deletedCount());
        assertEquals(1, repo.editedCount());
    }
}
