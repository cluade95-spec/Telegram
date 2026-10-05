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

    final List<Media> medias = new ArrayList<>();
    private long nextMediaId = 1;

    @Override
    public long insertMedia(Media m) {
        m.id = nextMediaId++;
        medias.add(copy(m));
        return m.id;
    }

    @Override
    public void updateMedia(Media m) {
        for (int i = 0; i < medias.size(); i++) {
            if (medias.get(i).id == m.id) {
                medias.set(i, copy(m));
                return;
            }
        }
        throw new IllegalStateException("no media " + m.id);
    }

    @Override
    public List<Media> mediaForEntry(long entryId) {
        List<Media> out = new ArrayList<>();
        for (Media m : medias) {
            if (m.entryId == entryId) {
                out.add(copy(m));
            }
        }
        return out;
    }

    @Override
    public List<Media> mediaByState(int state) {
        List<Media> out = new ArrayList<>();
        for (Media m : medias) {
            if (m.state == state) {
                out.add(copy(m));
            }
        }
        return out;
    }

    @Override
    public long preservedBytes() {
        long n = 0;
        for (Media m : medias) {
            if (m.state == LocalHistoryMediaState.PRESERVED) {
                n += m.size;
            }
        }
        return n;
    }

    @Override
    public List<Media> oldestPreserved(int limit) {
        List<Media> sorted = new ArrayList<>();
        for (Media m : medias) {
            if (m.state == LocalHistoryMediaState.PRESERVED) {
                sorted.add(copy(m));
            }
        }
        sorted.sort(Comparator.<Media>comparingInt(m -> m.createdAt).thenComparingLong(m -> m.id));
        return sorted.size() > limit ? new ArrayList<>(sorted.subList(0, limit)) : sorted;
    }

    private static Media copy(Media m) {
        Media c = new Media();
        c.id = m.id;
        c.entryId = m.entryId;
        c.revisionIdx = m.revisionIdx;
        c.state = m.state;
        c.kind = m.kind;
        c.sourcePath = m.sourcePath;
        c.localPath = m.localPath;
        c.size = m.size;
        c.createdAt = m.createdAt;
        return c;
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

    private final java.util.Map<String, String> meta = new java.util.HashMap<>();

    @Override
    public int deletedCount() {
        int n = 0;
        for (Entry e : entries) {
            if (e.isDeleted()) {
                n++;
            }
        }
        return n;
    }

    @Override
    public int editedCount() {
        int n = 0;
        for (Entry e : entries) {
            if (e.editCount > 0) {
                n++;
            }
        }
        return n;
    }

    @Override
    public int countAfter(int lastEventAt) {
        int n = 0;
        for (Entry e : entries) {
            if (e.lastEventAt > lastEventAt) {
                n++;
            }
        }
        return n;
    }

    @Override
    public String getMeta(String key) {
        return meta.get(key);
    }

    @Override
    public void setMeta(String key, String value) {
        if (value == null) {
            meta.remove(key);
        } else {
            meta.put(key, value);
        }
    }

    @Override
    public void deleteEntry(long id) {
        entries.removeIf(e -> e.id == id);
        revisions.removeIf(r -> r.entryId == id);
        medias.removeIf(m -> m.entryId == id);
    }

    @Override
    public void clear() {
        entries.clear();
        revisions.clear();
        medias.clear();
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
