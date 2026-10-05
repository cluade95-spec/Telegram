package org.telegram.messenger.localhistory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules of the archive (plan B8, B9, B6): one entry per source message, revisions for edits, idempotent replays,
 * deletion marks, bulk grouping. Pure; talks to storage only through {@link LocalHistoryRepository}.
 */
public final class LocalHistoryLedger {

    /** More than this many messages removed from one dialog in one update share a batch id. */
    public static final int BULK_THRESHOLD = 20;

    /** The state of a source message as it was stored, ready to archive. */
    public static final class Snapshot {
        public final long sourceDialogId;
        public final int sourceMid;
        public final int sourceDate;
        public final int editDate;
        public final long contentHash;
        public final byte[] data;
        /** plain text for search and as a fallback if {@code data} cannot be read later. */
        public final String text;
        public final String nameSnapshot;

        public Snapshot(long sourceDialogId, int sourceMid, int sourceDate, int editDate, long contentHash, byte[] data, String text, String nameSnapshot) {
            this.sourceDialogId = sourceDialogId;
            this.sourceMid = sourceMid;
            this.sourceDate = sourceDate;
            this.editDate = editDate;
            this.contentHash = contentHash;
            this.data = data;
            this.text = text == null ? "" : text;
            this.nameSnapshot = nameSnapshot;
        }
    }

    private final LocalHistoryRepository repo;

    public LocalHistoryLedger(LocalHistoryRepository repo) {
        this.repo = repo;
    }

    /**
     * Records that {@code before} became {@code after}. Returns the entry id when something new was stored, 0 otherwise
     * (replay, or the message was already deleted).
     */
    public long recordEdit(Snapshot before, Snapshot after, int now) {
        long[] result = new long[1];
        repo.inTransaction(() -> {
            LocalHistoryRepository.Entry entry = repo.findEntry(after.sourceDialogId, after.sourceMid);
            if (entry == null) {
                entry = newEntry(before, now);
                entry.id = repo.insertEntry(entry);
                repo.insertRevision(revision(entry.id, 0, LocalHistoryRepository.KIND_ORIGINAL, before, now));
            } else if (entry.isDeleted()) {
                return; // a deleted message cannot be edited, anything arriving now is a stale replay
            }
            if (repo.hasRevision(entry.id, after.contentHash, after.editDate)) {
                return;
            }
            int idx = repo.revisionCount(entry.id);
            repo.insertRevision(revision(entry.id, idx, LocalHistoryRepository.KIND_EDIT, after, now));
            entry.editCount++;
            entry.lastEventAt = now;
            entry.searchText = joinSearch(entry.searchText, after.text);
            repo.updateEntry(entry);
            result[0] = entry.id;
        });
        return result[0];
    }

    /**
     * Records removals as of {@code now}. Messages of one dialog above {@link #BULK_THRESHOLD} get a shared batch id.
     * Returns the number of entries newly marked deleted.
     */
    public int recordDeletions(List<Snapshot> removed, int now) {
        int[] count = new int[1];
        repo.inTransaction(() -> {
            Map<Long, Integer> perDialog = new HashMap<>();
            for (Snapshot s : removed) {
                LocalHistoryRepository.Entry existing = repo.findEntry(s.sourceDialogId, s.sourceMid);
                if (existing == null || !existing.isDeleted()) {
                    perDialog.merge(s.sourceDialogId, 1, Integer::sum);
                }
            }
            Map<Long, Long> batches = new HashMap<>();
            for (Snapshot s : removed) {
                LocalHistoryRepository.Entry entry = repo.findEntry(s.sourceDialogId, s.sourceMid);
                if (entry != null && entry.isDeleted()) {
                    continue; // replay or the push/update twin
                }
                long batch = 0;
                Integer n = perDialog.get(s.sourceDialogId);
                if (n != null && n > BULK_THRESHOLD) {
                    Long b = batches.get(s.sourceDialogId);
                    if (b == null) {
                        b = repo.nextBatchId();
                        batches.put(s.sourceDialogId, b);
                    }
                    batch = b;
                }
                if (entry == null) {
                    entry = newEntry(s, now);
                    entry.id = repo.insertEntry(entry);
                    repo.insertRevision(revision(entry.id, 0, LocalHistoryRepository.KIND_ORIGINAL, s, now));
                }
                entry.deletedAt = now;
                entry.lastEventAt = now;
                entry.batchId = batch;
                repo.updateEntry(entry);
                count[0]++;
            }
        });
        return count[0];
    }

    public List<LocalHistoryRepository.Entry> pageFeed(int afterLastEventAt, long afterId, int limit) {
        return repo.pageFeed(afterLastEventAt, afterId, limit);
    }

    public List<LocalHistoryRepository.Revision> revisions(long entryId) {
        return repo.revisions(entryId);
    }

    public void clear() {
        repo.inTransaction(repo::clear);
    }

    public void deleteEntry(long entryId) {
        repo.inTransaction(() -> repo.deleteEntry(entryId));
    }

    /** Collapses the entries of one bulk removal into groups for display; non-batch entries stay single. */
    public static List<List<LocalHistoryRepository.Entry>> groupBatches(List<LocalHistoryRepository.Entry> feed) {
        List<List<LocalHistoryRepository.Entry>> out = new ArrayList<>();
        Map<Long, List<LocalHistoryRepository.Entry>> open = new HashMap<>();
        for (LocalHistoryRepository.Entry e : feed) {
            if (e.batchId == 0) {
                List<LocalHistoryRepository.Entry> one = new ArrayList<>(1);
                one.add(e);
                out.add(one);
                continue;
            }
            List<LocalHistoryRepository.Entry> group = open.get(e.batchId);
            if (group == null) {
                group = new ArrayList<>();
                open.put(e.batchId, group);
                out.add(group);
            }
            group.add(e);
        }
        return out;
    }

    private static LocalHistoryRepository.Entry newEntry(Snapshot s, int now) {
        LocalHistoryRepository.Entry e = new LocalHistoryRepository.Entry();
        e.sourceDialogId = s.sourceDialogId;
        e.sourceMid = s.sourceMid;
        e.sourceDate = s.sourceDate;
        e.firstSeenAt = now;
        e.lastEventAt = now;
        e.nameSnapshot = s.nameSnapshot;
        e.searchText = s.text;
        return e;
    }

    private static LocalHistoryRepository.Revision revision(long entryId, int idx, int kind, Snapshot s, int now) {
        LocalHistoryRepository.Revision r = new LocalHistoryRepository.Revision();
        r.entryId = entryId;
        r.idx = idx;
        r.kind = kind;
        r.editDate = s.editDate;
        r.observedAt = now;
        r.contentHash = s.contentHash;
        r.data = s.data;
        r.text = s.text;
        return r;
    }

    private static String joinSearch(String existing, String more) {
        if (more == null || more.isEmpty() || (existing != null && existing.contains(more))) {
            return existing == null ? "" : existing;
        }
        return existing == null || existing.isEmpty() ? more : existing + "\n" + more;
    }
}
