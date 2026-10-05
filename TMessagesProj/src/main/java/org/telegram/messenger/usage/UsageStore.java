package org.telegram.messenger.usage;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Feature-owned SQLite file (no_backup/usage/usage.db), WAL, own queue (Reference A, A11-A13). Not cache4.db, so it
 * survives "clear database" and logout. Every public method hops to the usage queue; nothing here runs on the UI thread.
 */
public final class UsageStore {

    private static final int SCHEMA_VERSION = 1;
    private static final UsageStore INSTANCE = new UsageStore();

    public static UsageStore getInstance() {
        return INSTANCE;
    }

    public static final class Result {
        public final List<UsageLedger.Row> rows;
        public final List<UsageLedger.DailyRow> daily;

        Result(List<UsageLedger.Row> rows, List<UsageLedger.DailyRow> daily) {
            this.rows = rows;
            this.daily = daily;
        }
    }

    public interface Callback {
        /** Called on the UI thread. */
        void onResult(Result result);
    }

    private final DispatchQueue queue = new DispatchQueue("usageQueue");
    private SQLiteDatabase db; // usage queue only
    private boolean failed;

    private UsageStore() {
    }

    /** Persists a drained snapshot (upserts), then runs the daily rollup if due. */
    public void write(UsageLedger.Snapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        queue.postRunnable(() -> {
            if (!open()) {
                return;
            }
            try {
                db.beginTransaction();
                upsertRows(snapshot.rows);
                upsertDaily(snapshot.daily);
                db.commitTransaction();
                rollupIfDue();
            } catch (Throwable e) {
                FileLog.e(e);
                safeCommit();
            }
        });
    }

    /** Loads bucket and daily rows of the days fromDay..toDay (inclusive, yyyymmdd). */
    public void query(int fromDay, int toDay, Callback callback) {
        queue.postRunnable(() -> {
            ArrayList<UsageLedger.Row> rows = new ArrayList<>();
            ArrayList<UsageLedger.DailyRow> daily = new ArrayList<>();
            if (open()) {
                SQLiteCursor c = null;
                try {
                    c = db.queryFinalized("SELECT day, hour, account, surface, dialog, seconds FROM bucket WHERE day >= ? AND day <= ?", fromDay, toDay);
                    while (c.next()) {
                        rows.add(readRow(c));
                    }
                    c.dispose();
                    c = db.queryFinalized("SELECT day, account, opens, sessions, longest_session, messages_sent FROM daily WHERE day >= ? AND day <= ?", fromDay, toDay);
                    while (c.next()) {
                        UsageLedger.DailyRow d = new UsageLedger.DailyRow(new UsageLedger.DailyKey(c.intValue(0), c.longValue(1)));
                        d.opens = c.intValue(2);
                        d.sessions = c.intValue(3);
                        d.longestSessionSeconds = c.intValue(4);
                        d.messagesSent = c.intValue(5);
                        daily.add(d);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (c != null) {
                        c.dispose();
                    }
                }
            }
            Result result = new Result(rows, daily);
            AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
        });
    }

    /** Deletes everything (Reset Activity). */
    public void reset(Runnable done) {
        queue.postRunnable(() -> {
            if (open()) {
                try {
                    db.beginTransaction();
                    exec("DELETE FROM bucket");
                    exec("DELETE FROM daily");
                    db.commitTransaction();
                } catch (Throwable e) {
                    FileLog.e(e);
                    safeCommit();
                }
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    /** Logout: per-dialog rows of the account move to dialog = 0; time stays in totals and categories. */
    public void onAccountRemoved(long account) {
        if (account == 0) {
            return;
        }
        queue.postRunnable(() -> {
            if (!open()) {
                return;
            }
            SQLiteCursor c = null;
            try {
                ArrayList<UsageLedger.Row> rows = new ArrayList<>();
                c = db.queryFinalized("SELECT day, hour, account, surface, dialog, seconds FROM bucket WHERE account = ? AND dialog != 0", account);
                while (c.next()) {
                    rows.add(readRow(c));
                }
                c.dispose();
                c = null;
                UsageRollup.Fold fold = UsageRollup.rekeyAccount(rows, account);
                applyFold(fold);
            } catch (Throwable e) {
                FileLog.e(e);
                safeCommit();
            } finally {
                if (c != null) {
                    c.dispose();
                }
            }
        });
    }

    // internals (usage queue)

    private boolean open() {
        if (db != null) {
            return true;
        }
        if (failed) {
            return false;
        }
        File dir = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "usage");
        dir.mkdirs();
        File file = new File(dir, "usage.db");
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                db = new SQLiteDatabase(file.getPath());
                db.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
                db.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();
                int version = db.executeInt("PRAGMA user_version");
                if (version == 0) {
                    createSchema();
                }
                return true;
            } catch (Throwable e) {
                FileLog.e(e);
                closeQuietly();
                // unreadable file: start over once, statistics are not worth blocking the feature
                file.delete();
                new File(file.getPath() + "-wal").delete();
                new File(file.getPath() + "-shm").delete();
            }
        }
        failed = true;
        return false;
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

    private void createSchema() throws Exception {
        db.beginTransaction();
        exec("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value BLOB)");
        exec("CREATE TABLE IF NOT EXISTS bucket(day INTEGER NOT NULL, hour INTEGER NOT NULL, account INTEGER NOT NULL, surface INTEGER NOT NULL, dialog INTEGER NOT NULL, seconds INTEGER NOT NULL, PRIMARY KEY(day, hour, account, surface, dialog)) WITHOUT ROWID");
        exec("CREATE INDEX IF NOT EXISTS bucket_dialog ON bucket(account, dialog, day)");
        exec("CREATE TABLE IF NOT EXISTS daily(day INTEGER NOT NULL, account INTEGER NOT NULL, opens INTEGER NOT NULL DEFAULT 0, sessions INTEGER NOT NULL DEFAULT 0, longest_session INTEGER NOT NULL DEFAULT 0, messages_sent INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, account)) WITHOUT ROWID");
        exec("PRAGMA user_version = " + SCHEMA_VERSION);
        db.commitTransaction();
    }

    private void exec(String sql) throws Exception {
        db.executeFast(sql).stepThis().dispose();
    }

    private void safeCommit() {
        try {
            if (db != null) {
                db.commitTransaction();
            }
        } catch (Throwable ignore) {
        }
    }

    private static UsageLedger.Row readRow(SQLiteCursor c) throws Exception {
        return new UsageLedger.Row(new UsageLedger.BucketKey(c.intValue(0), c.intValue(1), c.longValue(2), c.intValue(3), c.longValue(4)), c.intValue(5));
    }

    private static final String UPSERT_BUCKET = "INSERT INTO bucket(day, hour, account, surface, dialog, seconds) VALUES(?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT(day, hour, account, surface, dialog) DO UPDATE SET seconds = seconds + excluded.seconds";

    private void upsertRows(List<UsageLedger.Row> rows) throws Exception {
        if (rows.isEmpty()) {
            return;
        }
        SQLitePreparedStatement st = db.executeFast(UPSERT_BUCKET);
        try {
            for (UsageLedger.Row r : rows) {
                st.requery();
                st.bindInteger(1, r.key.day);
                st.bindInteger(2, r.key.hour);
                st.bindLong(3, r.key.account);
                st.bindInteger(4, r.key.surface);
                st.bindLong(5, r.key.dialog);
                st.bindInteger(6, r.seconds);
                st.step();
            }
        } finally {
            st.dispose();
        }
    }

    private void upsertDaily(List<UsageLedger.DailyRow> rows) throws Exception {
        if (rows.isEmpty()) {
            return;
        }
        SQLitePreparedStatement st = db.executeFast("INSERT INTO daily(day, account, opens, sessions, longest_session, messages_sent) VALUES(?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(day, account) DO UPDATE SET opens = opens + excluded.opens, sessions = sessions + excluded.sessions, "
                + "longest_session = MAX(longest_session, excluded.longest_session), messages_sent = messages_sent + excluded.messages_sent");
        try {
            for (UsageLedger.DailyRow r : rows) {
                st.requery();
                st.bindInteger(1, r.key.day);
                st.bindLong(2, r.key.account);
                st.bindInteger(3, r.opens);
                st.bindInteger(4, r.sessions);
                st.bindInteger(5, r.longestSessionSeconds);
                st.bindInteger(6, r.messagesSent);
                st.step();
            }
        } finally {
            st.dispose();
        }
    }

    private void applyFold(UsageRollup.Fold fold) throws Exception {
        if (fold.remove.isEmpty() && fold.add.isEmpty()) {
            return;
        }
        db.beginTransaction();
        SQLitePreparedStatement del = db.executeFast("DELETE FROM bucket WHERE day = ? AND hour = ? AND account = ? AND surface = ? AND dialog = ?");
        try {
            for (UsageLedger.Row r : fold.remove) {
                del.requery();
                del.bindInteger(1, r.key.day);
                del.bindInteger(2, r.key.hour);
                del.bindLong(3, r.key.account);
                del.bindInteger(4, r.key.surface);
                del.bindLong(5, r.key.dialog);
                del.step();
            }
        } finally {
            del.dispose();
        }
        upsertRows(fold.add);
        db.commitTransaction();
    }

    /** At most once per day, at the first flush after midnight. */
    private void rollupIfDue() throws Exception {
        long wall = System.currentTimeMillis();
        int today = UsageDays.dayOfLocal(wall + TimeZone.getDefault().getOffset(wall));
        String last = null;
        SQLiteCursor c = db.queryFinalized("SELECT value FROM meta WHERE key = 'last_rollup_day'");
        try {
            if (c.next()) {
                last = c.stringValue(0);
            }
        } finally {
            c.dispose();
        }
        if (last != null && last.equals(String.valueOf(today))) {
            return;
        }
        int rollupBefore = UsageDays.addDays(today, -UsagePolicy.ROLLUP_AFTER_DAYS);
        ArrayList<UsageLedger.Row> hourly = new ArrayList<>();
        c = db.queryFinalized("SELECT day, hour, account, surface, dialog, seconds FROM bucket WHERE day < ? AND hour >= 0", rollupBefore);
        try {
            while (c.next()) {
                hourly.add(readRow(c));
            }
        } finally {
            c.dispose();
        }
        if (!hourly.isEmpty()) {
            db.beginTransaction();
            SQLitePreparedStatement del = db.executeFast("DELETE FROM bucket WHERE day < ? AND hour >= 0");
            del.requery();
            del.bindInteger(1, rollupBefore);
            del.step();
            del.dispose();
            upsertRows(UsageRollup.rollup(hourly));
            db.commitTransaction();
        }
        int foldBefore = UsageDays.addDays(today, -UsagePolicy.FOLD_AFTER_DAYS);
        ArrayList<UsageLedger.Row> old = new ArrayList<>();
        c = db.queryFinalized("SELECT day, hour, account, surface, dialog, seconds FROM bucket WHERE day < ? AND dialog != 0", foldBefore);
        try {
            while (c.next()) {
                old.add(readRow(c));
            }
        } finally {
            c.dispose();
        }
        applyFold(UsageRollup.fold(old, UsagePolicy.FOLD_BELOW_SECONDS));
        SQLitePreparedStatement st = db.executeFast("INSERT INTO meta(key, value) VALUES('last_rollup_day', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value");
        st.requery();
        st.bindString(1, String.valueOf(today));
        st.step();
        st.dispose();
    }
}
