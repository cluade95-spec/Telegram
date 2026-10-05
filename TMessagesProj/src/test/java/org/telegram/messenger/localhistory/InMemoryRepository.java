package org.telegram.messenger.localhistory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class InMemoryRepository implements LocalHistoryRepository {
    final List<Entry> entries = new ArrayList<>();
    final List<Revision> revisions = new ArrayList<>();
    private long nextId = 1;
    private long nextBatch = 1;
    int transactions;

    @Override
    public void inTransaction(Runnable body) {
        transactions++;
        body.run();
    }

    @Override
    public Entry findEntry(long d, int mid) {
        for (Entry e : entries) {
            if (e.sourceDialogId == d && e.sourceMid == mid) {
                return copy(e);
            }
        }
        return null;
    }

    @Override
    public long insertEntry(Entry e) {
        Entry c = copy(e);
        c.id = nextId++;
        entries.add(c);
        return c.id;
    }

    @Override
    public void updateEntry(Entry e) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == e.id) {
                entries.set(i, copy(e));
                return;
            }
        }
        throw new IllegalStateException("no entry " + e.id);
    }

    @Override
    public boolean hasRevision(long entryId, long hash, int editDate) {
        for (Revision r : revisions) {
            if (r.entryId == entryId && r.contentHash == hash && r.editDate == editDate) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int revisionCount(long entryId) {
        int n = 0;
        for (Revision r : revisions) {
            if (r.entryId == entryId) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void insertRevision(Revision r) {
        if (hasRevision(r.entryId, r.contentHash, r.editDate)) {
            throw new IllegalStateException("duplicate revision");
        }
        revisions.add(r);
    }

    @Override
    public long nextBatchId() {
        return nextBatch++;
    }

    @Override
    public List<Entry> pageFeed(int afterAt, long afterId, int limit) {
        List<Entry> out = new ArrayList<>();
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.<Entry>comparingInt(e -> e.lastEventAt).thenComparingLong(e -> e.id));
        for (Entry e : sorted) {
            if ((e.lastEventAt > afterAt || (e.lastEventAt == afterAt && e.id > afterId)) && out.size() < limit) {
                out.add(copy(e));
            }
        }
        return out;
    }

    @Override
    public List<Entry> pageFeedBefore(int beforeAt, long beforeId, int limit) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.<Entry>comparingInt(e -> e.lastEventAt).thenComparingLong(e -> e.id).reversed());
        List<Entry> out = new ArrayList<>();
        for (Entry e : sorted) {
            if ((e.lastEventAt < beforeAt || (e.lastEventAt == beforeAt && e.id < beforeId)) && out.size() < limit) {
                out.add(copy(e));
            }
        }
        return out;
    }

    @Override
    public List<Revision> revisions(long entryId) {
        List<Revision> out = new ArrayList<>();
        for (Revision r : revisions) {
            if (r.entryId == entryId) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingInt(r -> r.idx));
        return out;
    }

    @Override
    public int entryCount() {
        return entries.size();
    }

    @Override
    public void deleteEntry(long id) {
        entries.removeIf(e -> e.id == id);
        revisions.removeIf(r -> r.entryId == id);
    }

    @Override
    public void clear() {
        entries.clear();
        revisions.clear();
    }

    private static Entry copy(Entry e) {
        Entry c = new Entry();
        c.id = e.id;
        c.sourceDialogId = e.sourceDialogId;
        c.sourceMid = e.sourceMid;
        c.sourceDate = e.sourceDate;
        c.firstSeenAt = e.firstSeenAt;
        c.lastEventAt = e.lastEventAt;
        c.editCount = e.editCount;
        c.deletedAt = e.deletedAt;
        c.batchId = e.batchId;
        c.nameSnapshot = e.nameSnapshot;
        c.searchText = e.searchText;
        return c;
    }
}
