package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.telegram.messenger.localhistory.LocalHistoryLedger.Snapshot;
import org.telegram.messenger.localhistory.LocalHistoryRepository.Entry;

public class LocalHistoryLedgerTest {

    private static final long ALICE = 555;
    private InMemoryRepository repo;
    private LocalHistoryLedger ledger;

    @Before
    public void setUp() {
        repo = new InMemoryRepository();
        ledger = new LocalHistoryLedger(repo);
    }

    private static Snapshot snap(long dialog, int mid, String text, int editDate) {
        return new Snapshot(dialog, mid, 1000 + mid, editDate, text.hashCode(), text.getBytes(), text, "Alice");
    }

    @Test
    public void firstEditCreatesEntryWithOriginalAndEdit() {
        long id = ledger.recordEdit(snap(ALICE, 7, "hello", 0), snap(ALICE, 7, "hello world", 2000), 2001);
        assertTrue(id > 0);
        assertEquals(1, repo.entryCount());
        Entry e = repo.findEntry(ALICE, 7);
        assertEquals(1, e.editCount);
        assertEquals(2001, e.lastEventAt);
        assertFalse(e.isDeleted());
        List<LocalHistoryRepository.Revision> revs = ledger.revisions(e.id);
        assertEquals(2, revs.size());
        assertEquals(LocalHistoryRepository.KIND_ORIGINAL, revs.get(0).kind);
        assertEquals("hello", revs.get(0).text);
        assertEquals(LocalHistoryRepository.KIND_EDIT, revs.get(1).kind);
        assertEquals("hello world", revs.get(1).text);
    }

    @Test
    public void repeatedEditsAppendRevisionsToTheSameEntry() {
        ledger.recordEdit(snap(ALICE, 7, "a", 0), snap(ALICE, 7, "b", 2000), 2001);
        ledger.recordEdit(snap(ALICE, 7, "b", 2000), snap(ALICE, 7, "c", 3000), 3001);
        assertEquals(1, repo.entryCount());
        Entry e = repo.findEntry(ALICE, 7);
        assertEquals(2, e.editCount);
        assertEquals(3, ledger.revisions(e.id).size());
        assertEquals(3001, e.lastEventAt);
    }

    @Test
    public void identicalReplayIsIgnored() {
        Snapshot before = snap(ALICE, 7, "a", 0);
        Snapshot after = snap(ALICE, 7, "b", 2000);
        assertTrue(ledger.recordEdit(before, after, 2001) > 0);
        assertEquals(0, ledger.recordEdit(before, after, 2005));
        Entry e = repo.findEntry(ALICE, 7);
        assertEquals(1, e.editCount);
        assertEquals(2, ledger.revisions(e.id).size());
        assertEquals(2001, e.lastEventAt);
    }

    @Test
    public void editThenDeleteKeepsOneEntryMarkedDeleted() {
        ledger.recordEdit(snap(ALICE, 7, "a", 0), snap(ALICE, 7, "b", 2000), 2001);
        ArrayList<Snapshot> removed = new ArrayList<>();
        removed.add(snap(ALICE, 7, "b", 2000));
        assertEquals(1, ledger.recordDeletions(removed, 3000));
        assertEquals(1, repo.entryCount());
        Entry e = repo.findEntry(ALICE, 7);
        assertTrue(e.isDeleted());
        assertEquals(3000, e.deletedAt);
        assertEquals(3000, e.lastEventAt);
        assertEquals(1, e.editCount);
    }

    @Test
    public void deleteThenEditIsIgnored() {
        List<Snapshot> removed = new ArrayList<>();
        removed.add(snap(ALICE, 7, "a", 0));
        ledger.recordDeletions(removed, 3000);
        assertEquals(0, ledger.recordEdit(snap(ALICE, 7, "a", 0), snap(ALICE, 7, "late", 3500), 3501));
        Entry e = repo.findEntry(ALICE, 7);
        assertEquals(0, e.editCount);
        assertEquals(1, ledger.revisions(e.id).size());
    }

    @Test
    public void pushAndUpdateDeletionsConverge() {
        List<Snapshot> removed = new ArrayList<>();
        removed.add(snap(ALICE, 7, "a", 0));
        assertEquals(1, ledger.recordDeletions(removed, 3000));
        assertEquals(0, ledger.recordDeletions(removed, 3002));
        assertEquals(1, repo.entryCount());
        assertEquals(3000, repo.findEntry(ALICE, 7).deletedAt);
    }

    @Test
    public void smallRemovalHasNoBatch() {
        List<Snapshot> removed = new ArrayList<>();
        for (int i = 1; i <= LocalHistoryLedger.BULK_THRESHOLD; i++) {
            removed.add(snap(ALICE, i, "m" + i, 0));
        }
        ledger.recordDeletions(removed, 3000);
        for (Entry e : repo.entries) {
            assertEquals(0, e.batchId);
        }
    }

    @Test
    public void bulkRemovalSharesABatchPerDialog() {
        List<Snapshot> removed = new ArrayList<>();
        for (int i = 1; i <= LocalHistoryLedger.BULK_THRESHOLD + 1; i++) {
            removed.add(snap(ALICE, i, "m" + i, 0));
        }
        removed.add(snap(777, 1, "other", 0));
        ledger.recordDeletions(removed, 3000);
        long batch = repo.findEntry(ALICE, 1).batchId;
        assertNotEquals(0, batch);
        for (int i = 1; i <= LocalHistoryLedger.BULK_THRESHOLD + 1; i++) {
            assertEquals(batch, repo.findEntry(ALICE, i).batchId);
        }
        assertEquals(0, repo.findEntry(777, 1).batchId);
        List<List<Entry>> groups = LocalHistoryLedger.groupBatches(ledger.pageFeed(0, 0, 100));
        assertEquals(2, groups.size());
    }

    @Test
    public void everyWriteRunsInsideOneTransaction() {
        ledger.recordEdit(snap(ALICE, 7, "a", 0), snap(ALICE, 7, "b", 2000), 2001);
        assertEquals(1, repo.transactions);
    }

    @Test
    public void feedIsOrderedByLastEventAndEditedEntryMovesToTheBottom() {
        ledger.recordEdit(snap(ALICE, 1, "a", 0), snap(ALICE, 1, "a2", 100), 101);
        ledger.recordEdit(snap(ALICE, 2, "b", 0), snap(ALICE, 2, "b2", 200), 201);
        ledger.recordEdit(snap(ALICE, 1, "a2", 100), snap(ALICE, 1, "a3", 300), 301);
        List<Entry> feed = ledger.pageFeed(0, 0, 10);
        assertEquals(2, feed.get(0).sourceMid);
        assertEquals(1, feed.get(1).sourceMid);
    }

    @Test
    public void clearAndDeleteEntryRemoveEverythingAndAllowRecapture() {
        ledger.recordEdit(snap(ALICE, 1, "a", 0), snap(ALICE, 1, "b", 100), 101);
        ledger.recordEdit(snap(ALICE, 2, "a", 0), snap(ALICE, 2, "b", 100), 101);
        ledger.deleteEntry(repo.findEntry(ALICE, 1).id);
        assertNull(repo.findEntry(ALICE, 1));
        assertEquals(1, repo.entryCount());
        ledger.clear();
        assertEquals(0, repo.entryCount());
        assertEquals(0, repo.revisions.size());
        assertTrue(ledger.recordEdit(snap(ALICE, 2, "a", 0), snap(ALICE, 2, "b", 100), 500) > 0);
    }

    @Test
    public void deletionOfAnUnseenMessageStoresItsLastKnownState() {
        List<Snapshot> removed = new ArrayList<>();
        removed.add(snap(ALICE, 9, "gone", 0));
        ledger.recordDeletions(removed, 4000);
        Entry e = repo.findEntry(ALICE, 9);
        assertTrue(e.isDeleted());
        List<LocalHistoryRepository.Revision> revs = ledger.revisions(e.id);
        assertEquals(1, revs.size());
        assertEquals("gone", revs.get(0).text);
    }
}
