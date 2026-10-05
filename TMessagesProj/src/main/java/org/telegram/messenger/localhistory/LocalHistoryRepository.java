package org.telegram.messenger.localhistory;

import java.util.List;

/**
 * Storage port for Local History (plan B25). The ledger rules live in {@link LocalHistoryLedger}; implementations
 * only persist. All methods may be called on the storage thread or the feature queue; implementations serialize access.
 */
public interface LocalHistoryRepository {

    int KIND_ORIGINAL = 0;
    int KIND_EDIT = 1;

    final class Entry {
        public long id;
        public long sourceDialogId;
        public int sourceMid;
        public int sourceDate;
        public int firstSeenAt;
        public int lastEventAt;
        public int editCount;
        /** 0 = not deleted. */
        public int deletedAt;
        /** 0 = not part of a bulk removal. */
        public long batchId;
        public String nameSnapshot;
        public String searchText;

        public boolean isDeleted() {
            return deletedAt != 0;
        }
    }

    final class Revision {
        public long entryId;
        public int idx;
        public int kind;
        public int editDate;
        public int observedAt;
        public long contentHash;
        public byte[] data;
        public String text;
    }

    /** Runs {@code body} atomically: all of it is stored or none of it. */
    void inTransaction(Runnable body);

    Entry findEntry(long sourceDialogId, int sourceMid);

    /** Inserts and returns the new id, assigned in increasing order. */
    long insertEntry(Entry entry);

    void updateEntry(Entry entry);

    boolean hasRevision(long entryId, long contentHash, int editDate);

    int revisionCount(long entryId);

    void insertRevision(Revision revision);

    long nextBatchId();

    // reads

    /** Entries ordered by (lastEventAt, id) ascending, strictly after the cursor; the first page uses (0, 0). */
    List<Entry> pageFeed(int afterLastEventAt, long afterId, int limit);

    /** Entries before the cursor, newest first, used to load older pages. */
    List<Entry> pageFeedBefore(int beforeLastEventAt, long beforeId, int limit);

    List<Revision> revisions(long entryId);

    int entryCount();

    int deletedCount();

    int editedCount();

    /** Entries whose last event is strictly after {@code lastEventAt}. */
    int countAfter(int lastEventAt);

    String getMeta(String key);

    void setMeta(String key, String value);

    void deleteEntry(long entryId);

    /** Removes every entry, revision and media row. */
    void clear();
}
