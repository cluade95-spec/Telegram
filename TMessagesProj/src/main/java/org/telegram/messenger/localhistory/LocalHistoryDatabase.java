package org.telegram.messenger.localhistory;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.NativeByteBuffer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature-owned SQLite file (plan B25), one per account, in no_backup. Never shares a file with cache4.db.
 * All access is serialized on this object so capture (storage thread) and reads (feature queue) cannot interleave.
 */
public class LocalHistoryDatabase implements LocalHistoryRepository {

    public static final int SCHEMA_VERSION = 1;

    private final File file;
    private SQLiteDatabase db;
    private boolean failed;
    private int txDepth;

    public LocalHistoryDatabase(File dir) {
        dir.mkdirs();
        this.file = new File(dir, "history.db");
    }

    public File getFile() {
        return file;
    }

    // lifecycle

    private boolean open() {
        if (db != null) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            db = new SQLiteDatabase(file.getPath());
            db.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
            db.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();
            db.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
            migrate();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            closeQuietly();
            failed = true;
            return false;
        }
    }

    private void migrate() throws Exception {
        int version = db.executeInt("PRAGMA user_version");
        if (version >= SCHEMA_VERSION) {
            return;
        }
        db.beginTransaction();
        if (version < 1) {
            exec("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value BLOB)");
            exec("CREATE TABLE IF NOT EXISTS entry(id INTEGER PRIMARY KEY AUTOINCREMENT, source_dialog_id INTEGER NOT NULL, source_mid INTEGER NOT NULL, source_date INTEGER NOT NULL, first_seen_at INTEGER NOT NULL, last_event_at INTEGER NOT NULL, edit_count INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER, batch_id INTEGER, source_name_snapshot TEXT, search_text TEXT NOT NULL DEFAULT '', UNIQUE(source_dialog_id, source_mid))");
            exec("CREATE INDEX IF NOT EXISTS entry_feed ON entry(last_event_at, id)");
            exec("CREATE INDEX IF NOT EXISTS entry_source ON entry(source_dialog_id, last_event_at)");
            exec("CREATE TABLE IF NOT EXISTS revision(entry_id INTEGER NOT NULL, idx INTEGER NOT NULL, kind INTEGER NOT NULL, edit_date INTEGER NOT NULL, observed_at INTEGER NOT NULL, content_hash INTEGER NOT NULL, data BLOB NOT NULL, text TEXT, PRIMARY KEY(entry_id, idx), UNIQUE(entry_id, content_hash, edit_date)) WITHOUT ROWID");
            exec("CREATE TABLE IF NOT EXISTS media(id INTEGER PRIMARY KEY AUTOINCREMENT, entry_id INTEGER NOT NULL, revision_idx INTEGER NOT NULL, state INTEGER NOT NULL, kind INTEGER NOT NULL, source_path TEXT, local_path TEXT, size INTEGER, created_at INTEGER)");
            exec("CREATE INDEX IF NOT EXISTS media_entry ON media(entry_id)");
            exec("CREATE INDEX IF NOT EXISTS media_state ON media(state)");
        }
        exec("PRAGMA user_version = " + SCHEMA_VERSION);
        db.commitTransaction();
    }

    private void exec(String sql) throws Exception {
        db.executeFast(sql).stepThis().dispose();
    }

    private void closeQuietly() {
        try {
            if (db != null) {
                db.close();
            }
        } catch (Throwable ignore) {
        }
        db = null;
    }

    public synchronized void close() {
        closeQuietly();
    }

    /** Closes the database and removes the file with its journal; used for account removal and "Delete all". */
    public synchronized void destroy() {
        closeQuietly();
        failed = false;
        file.delete();
        new File(file.getPath() + "-wal").delete();
        new File(file.getPath() + "-shm").delete();
    }

    // transactions

    @Override
    public synchronized void inTransaction(Runnable body) {
        if (!open()) {
            return;
        }
        boolean outer = txDepth == 0;
        try {
            if (outer) {
                db.beginTransaction();
            }
            txDepth++;
            try {
                body.run();
            } finally {
                txDepth--;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (outer) {
                try {
                    db.commitTransaction();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    // writes

    @Override
    public synchronized Entry findEntry(long dialogId, int mid) {
        if (!open()) {
            return null;
        }
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized("SELECT " + ENTRY_COLS + " FROM entry WHERE source_dialog_id = ? AND source_mid = ?", dialogId, mid);
            return c.next() ? readEntry(c) : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
    }

    @Override
    public synchronized long insertEntry(Entry e) {
        if (!open()) {
            return 0;
        }
        SQLitePreparedStatement s = null;
        SQLiteCursor c = null;
        try {
            s = db.executeFast("INSERT INTO entry(source_dialog_id, source_mid, source_date, first_seen_at, last_event_at, edit_count, deleted_at, batch_id, source_name_snapshot, search_text) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            bindEntry(s, e);
            s.step();
            c = db.queryFinalized("SELECT last_insert_rowid()");
            return c.next() ? c.longValue(0) : 0;
        } catch (Throwable t) {
            FileLog.e(t);
            return 0;
        } finally {
            if (s != null) {
                s.dispose();
            }
            if (c != null) {
                c.dispose();
            }
        }
    }

    @Override
    public synchronized void updateEntry(Entry e) {
        if (!open()) {
            return;
        }
        SQLitePreparedStatement s = null;
        try {
            s = db.executeFast("UPDATE entry SET source_dialog_id = ?, source_mid = ?, source_date = ?, first_seen_at = ?, last_event_at = ?, edit_count = ?, deleted_at = ?, batch_id = ?, source_name_snapshot = ?, search_text = ? WHERE id = ?");
            bindEntry(s, e);
            s.bindLong(11, e.id);
            s.step();
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            if (s != null) {
                s.dispose();
            }
        }
    }

    private static void bindEntry(SQLitePreparedStatement s, Entry e) throws Exception {
        s.requery();
        s.bindLong(1, e.sourceDialogId);
        s.bindInteger(2, e.sourceMid);
        s.bindInteger(3, e.sourceDate);
        s.bindInteger(4, e.firstSeenAt);
        s.bindInteger(5, e.lastEventAt);
        s.bindInteger(6, e.editCount);
        if (e.deletedAt == 0) {
            s.bindNull(7);
        } else {
            s.bindInteger(7, e.deletedAt);
        }
        if (e.batchId == 0) {
            s.bindNull(8);
        } else {
            s.bindLong(8, e.batchId);
        }
        if (e.nameSnapshot == null) {
            s.bindNull(9);
        } else {
            s.bindString(9, e.nameSnapshot);
        }
        s.bindString(10, e.searchText == null ? "" : e.searchText);
    }

    @Override
    public synchronized boolean hasRevision(long entryId, long hash, int editDate) {
        if (!open()) {
            return false;
        }
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized("SELECT 1 FROM revision WHERE entry_id = ? AND content_hash = ? AND edit_date = ?", entryId, hash, editDate);
            return c.next();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
    }

    @Override
    public synchronized int revisionCount(long entryId) {
        if (!open()) {
            return 0;
        }
        Integer n = null;
        try {
            n = db.executeInt("SELECT COUNT(*) FROM revision WHERE entry_id = ?", entryId);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return n == null ? 0 : n;
    }

    @Override
    public synchronized void insertRevision(Revision r) {
        if (!open()) {
            return;
        }
        SQLitePreparedStatement s = null;
        NativeByteBuffer buffer = null;
        try {
            s = db.executeFast("INSERT OR IGNORE INTO revision(entry_id, idx, kind, edit_date, observed_at, content_hash, data, text) VALUES(?, ?, ?, ?, ?, ?, ?, ?)");
            s.requery();
            s.bindLong(1, r.entryId);
            s.bindInteger(2, r.idx);
            s.bindInteger(3, r.kind);
            s.bindInteger(4, r.editDate);
            s.bindInteger(5, r.observedAt);
            s.bindLong(6, r.contentHash);
            byte[] data = r.data == null ? new byte[0] : r.data;
            buffer = new NativeByteBuffer(data.length);
            buffer.writeBytes(data);
            buffer.position(0);
            s.bindByteBuffer(7, buffer);
            if (r.text == null) {
                s.bindNull(8);
            } else {
                s.bindString(8, r.text);
            }
            s.step();
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
            if (s != null) {
                s.dispose();
            }
        }
    }

    @Override
    public synchronized long nextBatchId() {
        if (!open()) {
            return 1;
        }
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized("SELECT COALESCE(MAX(batch_id), 0) + 1 FROM entry");
            return c.next() ? c.longValue(0) : 1;
        } catch (Throwable e) {
            FileLog.e(e);
            return 1;
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
    }

    // media

    private static final String MEDIA_COLS = "id, entry_id, revision_idx, state, kind, source_path, local_path, size, created_at";

    @Override
    public synchronized long insertMedia(Media m) {
        if (!open()) {
            return 0;
        }
        SQLitePreparedStatement s = null;
        SQLiteCursor c = null;
        try {
            s = db.executeFast("INSERT INTO media(entry_id, revision_idx, state, kind, source_path, local_path, size, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)");
            s.requery();
            s.bindLong(1, m.entryId);
            s.bindInteger(2, m.revisionIdx);
            s.bindInteger(3, m.state);
            s.bindInteger(4, m.kind);
            bindNullable(s, 5, m.sourcePath);
            bindNullable(s, 6, m.localPath);
            s.bindLong(7, m.size);
            s.bindInteger(8, m.createdAt);
            s.step();
            c = db.queryFinalized("SELECT last_insert_rowid()");
            m.id = c.next() ? c.longValue(0) : 0;
            return m.id;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        } finally {
            if (s != null) {
                s.dispose();
            }
            if (c != null) {
                c.dispose();
            }
        }
    }

    private static void bindNullable(SQLitePreparedStatement s, int index, String value) throws Exception {
        if (value == null) {
            s.bindNull(index);
        } else {
            s.bindString(index, value);
        }
    }

    @Override
    public synchronized void updateMedia(Media m) {
        if (!open()) {
            return;
        }
        SQLitePreparedStatement s = null;
        try {
            s = db.executeFast("UPDATE media SET state = ?, source_path = ?, local_path = ?, size = ? WHERE id = ?");
            s.requery();
            s.bindInteger(1, m.state);
            bindNullable(s, 2, m.sourcePath);
            bindNullable(s, 3, m.localPath);
            s.bindLong(4, m.size);
            s.bindLong(5, m.id);
            s.step();
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (s != null) {
                s.dispose();
            }
        }
    }

    private static Media readMedia(SQLiteCursor c) throws Exception {
        Media m = new Media();
        m.id = c.longValue(0);
        m.entryId = c.longValue(1);
        m.revisionIdx = c.intValue(2);
        m.state = c.intValue(3);
        m.kind = c.intValue(4);
        m.sourcePath = c.isNull(5) ? null : c.stringValue(5);
        m.localPath = c.isNull(6) ? null : c.stringValue(6);
        m.size = c.isNull(7) ? 0 : c.longValue(7);
        m.createdAt = c.isNull(8) ? 0 : c.intValue(8);
        return m;
    }

    private List<Media> queryMedia(String sql, Object... args) {
        List<Media> out = new ArrayList<>();
        if (!open()) {
            return out;
        }
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized(sql, args);
            while (c.next()) {
                out.add(readMedia(c));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
        return out;
    }

    @Override
    public synchronized List<Media> mediaForEntry(long entryId) {
        return queryMedia("SELECT " + MEDIA_COLS + " FROM media WHERE entry_id = ? ORDER BY revision_idx ASC, id ASC", entryId);
    }

    @Override
    public synchronized List<Media> mediaByState(int state) {
        return queryMedia("SELECT " + MEDIA_COLS + " FROM media WHERE state = ? ORDER BY id ASC", state);
    }

    @Override
    public synchronized long preservedBytes() {
        if (!open()) {
            return 0;
        }
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized("SELECT COALESCE(SUM(size), 0) FROM media WHERE state = ?", LocalHistoryMediaState.PRESERVED);
            return c.next() ? c.longValue(0) : 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
    }

    @Override
    public synchronized List<Media> oldestPreserved(int limit) {
        return queryMedia("SELECT " + MEDIA_COLS + " FROM media WHERE state = ? ORDER BY created_at ASC, id ASC LIMIT ?", LocalHistoryMediaState.PRESERVED, limit);
    }

    // reads

    private static final String ENTRY_COLS = "id, source_dialog_id, source_mid, source_date, first_seen_at, last_event_at, edit_count, deleted_at, batch_id, source_name_snapshot, search_text";

    private static Entry readEntry(SQLiteCursor c) throws Exception {
        Entry e = new Entry();
        e.id = c.longValue(0);
        e.sourceDialogId = c.longValue(1);
        e.sourceMid = c.intValue(2);
        e.sourceDate = c.intValue(3);
        e.firstSeenAt = c.intValue(4);
        e.lastEventAt = c.intValue(5);
        e.editCount = c.intValue(6);
        e.deletedAt = c.isNull(7) ? 0 : c.intValue(7);
        e.batchId = c.isNull(8) ? 0 : c.longValue(8);
        e.nameSnapshot = c.isNull(9) ? null : c.stringValue(9);
        e.searchText = c.isNull(10) ? "" : c.stringValue(10);
        return e;
    }

    @Override
    public synchronized List<Entry> pageFeed(int afterAt, long afterId, int limit) {
        return query("SELECT " + ENTRY_COLS + " FROM entry WHERE last_event_at > ? OR (last_event_at = ? AND id > ?) ORDER BY last_event_at ASC, id ASC LIMIT ?", afterAt, afterAt, afterId, limit);
    }

    @Override
    public synchronized List<Entry> pageFeedBefore(int beforeAt, long beforeId, int limit) {
        return query("SELECT " + ENTRY_COLS + " FROM entry WHERE last_event_at < ? OR (last_event_at = ? AND id < ?) ORDER BY last_event_at DESC, id DESC LIMIT ?", beforeAt, beforeAt, beforeId, limit);
    }

    private List<Entry> query(String sql, Object... args) {
        List<Entry> out = new ArrayList<>();
        if (!open()) {
            return out;
        }
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized(sql, args);
            while (c.next()) {
                out.add(readEntry(c));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
        return out;
    }

    @Override
    public synchronized List<Revision> revisions(long entryId) {
        List<Revision> out = new ArrayList<>();
        if (!open()) {
            return out;
        }
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized("SELECT entry_id, idx, kind, edit_date, observed_at, content_hash, data, text FROM revision WHERE entry_id = ? ORDER BY idx ASC", entryId);
            while (c.next()) {
                Revision r = new Revision();
                r.entryId = c.longValue(0);
                r.idx = c.intValue(1);
                r.kind = c.intValue(2);
                r.editDate = c.intValue(3);
                r.observedAt = c.intValue(4);
                r.contentHash = c.longValue(5);
                r.data = c.byteArrayValue(6);
                r.text = c.isNull(7) ? null : c.stringValue(7);
                out.add(r);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
        return out;
    }

    @Override
    public synchronized int entryCount() {
        if (!open()) {
            return 0;
        }
        try {
            Integer n = db.executeInt("SELECT COUNT(*) FROM entry");
            return n == null ? 0 : n;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    private int scalar(String sql, Object... args) {
        if (!open()) {
            return 0;
        }
        try {
            Integer n = db.executeInt(sql, args);
            return n == null ? 0 : n;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    @Override
    public synchronized int deletedCount() {
        return scalar("SELECT COUNT(*) FROM entry WHERE deleted_at IS NOT NULL");
    }

    @Override
    public synchronized int editedCount() {
        return scalar("SELECT COUNT(*) FROM entry WHERE edit_count > 0");
    }

    @Override
    public synchronized int countAfter(int lastEventAt) {
        return scalar("SELECT COUNT(*) FROM entry WHERE last_event_at > ?", lastEventAt);
    }

    @Override
    public synchronized String getMeta(String key) {
        if (!open()) {
            return null;
        }
        SQLiteCursor c = null;
        try {
            c = db.queryFinalized("SELECT value FROM meta WHERE key = ?", key);
            if (c.next() && !c.isNull(0)) {
                byte[] bytes = c.byteArrayValue(0);
                return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                c.dispose();
            }
        }
        return null;
    }

    @Override
    public synchronized void setMeta(String key, String value) {
        if (!open()) {
            return;
        }
        SQLitePreparedStatement s = null;
        NativeByteBuffer buffer = null;
        try {
            if (value == null) {
                s = db.executeFast("DELETE FROM meta WHERE key = ?");
                s.requery();
                s.bindString(1, key);
                s.step();
                return;
            }
            s = db.executeFast("REPLACE INTO meta(key, value) VALUES(?, ?)");
            s.requery();
            s.bindString(1, key);
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buffer = new NativeByteBuffer(bytes.length);
            buffer.writeBytes(bytes);
            buffer.position(0);
            s.bindByteBuffer(2, buffer);
            s.step();
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (buffer != null) {
                buffer.reuse();
            }
            if (s != null) {
                s.dispose();
            }
        }
    }

    @Override
    public synchronized void deleteEntry(long entryId) {
        if (!open()) {
            return;
        }
        try {
            db.executeFast("DELETE FROM revision WHERE entry_id = " + entryId).stepThis().dispose();
            db.executeFast("DELETE FROM media WHERE entry_id = " + entryId).stepThis().dispose();
            db.executeFast("DELETE FROM entry WHERE id = " + entryId).stepThis().dispose();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public synchronized void clear() {
        if (!open()) {
            return;
        }
        try {
            exec("DELETE FROM revision");
            exec("DELETE FROM media");
            exec("DELETE FROM entry");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
