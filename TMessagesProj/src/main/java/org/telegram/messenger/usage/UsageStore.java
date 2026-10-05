package org.telegram.messenger.usage;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** All database access and retry state belong to this feature's queue. No network or message content. */
public final class UsageStore {
    public static final class Row {
        public final int day, hour, surface;
        public final long account, dialog, seconds;
        Row(int day, int hour, long account, int surface, long dialog, long seconds) {
            this.day=day; this.hour=hour; this.account=account; this.surface=surface; this.dialog=dialog; this.seconds=seconds;
        }
    }
    public static final class Report {
        public final ArrayList<Row> rows = new ArrayList<>();
        public final Map<UsageMetrics.Key, UsageMetrics.Daily> daily = new HashMap<>();
    }
    public static final class Migration {
        public final long account, from, to;
        public Migration(long account, long from, long to) { this.account=account; this.from=from; this.to=to; }
    }
    private static final UsageStore INSTANCE = new UsageStore();
    public static UsageStore getInstance() { return INSTANCE; }
    private final File testFile;
    private UsageStore() { this(null); }
    UsageStore(File isolatedFile) { testFile=isolatedFile; }
    void closeForTests(Runnable done) {
        queue.postRunnable(() -> { if(db!=null) { db.close(); db=null; } done.run(); });
    }
    private final DispatchQueue queue = new DispatchQueue("usageQueue");
    private SQLiteDatabase db;
    private Map<UsageLedger.BucketKey, Long> pending = new HashMap<>();
    private final Map<UsageMetrics.Key, UsageMetrics.Daily> metrics = new HashMap<>();
    private final Set<Long> removed = new HashSet<>();
    private final ArrayList<Migration> migrations = new ArrayList<>();
    private boolean resetPending;
    private final long createdAt=System.currentTimeMillis();
    private long resetAt;
    private static final class Confirmation {
        long account, dialog, message, wall;
        boolean secret;
        ZoneId zone;
    }
    private final ArrayList<Confirmation> confirmations=new ArrayList<>();
    public void confirmed(long account,long dialog,long message,boolean secret,long wall,ZoneId zone) {
        Confirmation c=new Confirmation(); c.account=account; c.dialog=dialog; c.message=message; c.secret=secret; c.wall=wall; c.zone=zone;
        queue.postRunnable(() -> { confirmations.add(c); });
    }
    private String meta(String key) throws Exception {
        SQLiteCursor c=db.queryFinalized("SELECT CAST(value AS TEXT) FROM meta WHERE key=?",key);
        try { return c.next()?c.stringValue(0):null; } finally { c.dispose(); }
    }

    private void execute(String sql, Object... values) throws Exception {
        SQLitePreparedStatement s = db.executeFast(sql);
        try {
            for (int i=0; i<values.length; i++) {
                Object v=values[i];
                if (v instanceof String) s.bindString(i+1,(String)v);
                else s.bindLong(i+1,((Number)v).longValue());
            }
            s.step();
        } finally { s.dispose(); }
    }
    private void open() throws Exception {
        if (db != null) return;
        File dir = testFile==null ? new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "usage") : testFile.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create Activity directory");
        SQLiteDatabase opened = new SQLiteDatabase((testFile==null ? new File(dir,"usage.db") : testFile).getPath());
        db = opened;
        try {
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=FULL");
            execute(UsageStorageSql.META); execute(UsageStorageSql.BUCKET);
            execute(UsageStorageSql.INDEX); execute(UsageStorageSql.DAILY); execute(UsageStorageSql.SENT);
            execute("INSERT OR IGNORE INTO meta VALUES('schema_version',1)");
            execute("INSERT OR IGNORE INTO meta VALUES('created_at',?)", createdAt);
            execute("INSERT OR IGNORE INTO meta VALUES('dedup_salt',?)", UUID.randomUUID().toString());
        } catch (Exception e) { opened.close(); db=null; throw e; }
    }
    public void flush(Map<UsageLedger.BucketKey, Long> credits, Map<UsageMetrics.Key, UsageMetrics.Daily> daily,
                      ArrayList<Migration> knownMigrations, long removedAccount) {
        queue.postRunnable(() -> {
            for (Map.Entry<UsageLedger.BucketKey,Long> r:credits.entrySet()) pending.merge(r.getKey(),r.getValue(),Long::sum);
            for (Map.Entry<UsageMetrics.Key,UsageMetrics.Daily> r:daily.entrySet()) metrics.computeIfAbsent(r.getKey(),k->new UsageMetrics.Daily()).merge(r.getValue());
            migrations.addAll(knownMigrations);
            if (removedAccount!=0) removed.add(removedAccount);
            persist();
        });
    }
    private boolean persist() {
        boolean transaction=false;
        try {
            open();
            execute("BEGIN IMMEDIATE"); transaction=true;
            if (resetPending) { execute("DELETE FROM bucket"); execute("DELETE FROM daily"); execute("DELETE FROM sent"); execute("DELETE FROM meta"); execute("INSERT INTO meta VALUES('schema_version',1)"); execute("INSERT INTO meta VALUES('created_at',?)",resetAt); execute("INSERT INTO meta VALUES('dedup_salt',?)",UUID.randomUUID().toString()); }
            for (long account:removed) pending=UsageRollup.anonymize(pending,account);
            Map<UsageLedger.BucketKey,Long> remainder = new HashMap<>();
            for (Map.Entry<UsageLedger.BucketKey,Long> r:pending.entrySet()) {
                UsageLedger.BucketKey k=r.getKey(); long ms=r.getValue();
                if (ms>=1000) execute(UsageStorageSql.UPSERT_BUCKET,k.day,k.hour,k.surface.accountUserId,k.surface.surface.id,k.surface.dialogId,ms/1000);
                if (ms%1000!=0) remainder.put(k,ms%1000);
            }
            for (Map.Entry<UsageMetrics.Key,UsageMetrics.Daily> r:metrics.entrySet()) {
                UsageMetrics.Daily d=r.getValue(); UsageMetrics.Key k=r.getKey();
                execute(UsageStorageSql.UPSERT_DAILY,k.day,k.account,d.opens,d.sessions,d.longestMillis/1000,d.messages);
            }
            String salt=meta("dedup_salt");
            for (Confirmation c:confirmations) {
                String token=UsageSendIdentity.digest(salt,c.account,c.dialog,c.message,c.secret);
                if (db.executeInt(UsageStorageSql.HAS_SENT,c.account,token)==null) {
                    execute(UsageStorageSql.INSERT_SENT,c.account,token);
                    execute(UsageStorageSql.SENT_METRIC,UsageMetrics.day(c.wall,c.zone),c.account);
                }
            }
            for (long account:removed) { execute(UsageStorageSql.ANONYMIZE,account); execute(UsageStorageSql.DELETE_IDENTITIES,account); }
            LocalDate today=LocalDate.now(); int day=UsageMetrics.day(today);
            Integer last=db.executeInt("SELECT CAST(value AS INTEGER) FROM meta WHERE key='last_rollup_day'");
            if (last==null || last!=day) {
                for (Migration m:migrations) if (m.from!=m.to && !removed.contains(m.account)) {
                    execute(UsageStorageSql.MIGRATE,m.to,m.account,m.from);
                    execute(UsageStorageSql.DELETE_MIGRATED,m.account,m.from);
                }
                int cutoff=UsageRollup.hourlyCutoff(today); execute(UsageStorageSql.ROLLUP,cutoff); execute(UsageStorageSql.DELETE_HOURS,cutoff);
                cutoff=UsageRollup.dialogCutoff(today); execute(UsageStorageSql.FOLD,cutoff,cutoff); execute(UsageStorageSql.DELETE_FOLDED,cutoff,cutoff);
                execute("INSERT OR REPLACE INTO meta VALUES('last_rollup_day',?)",day);
            }
            execute("COMMIT"); transaction=false;
            pending=remainder; metrics.clear(); migrations.clear(); confirmations.clear(); removed.clear(); resetPending=false;
            return true;
        } catch (Exception e) {
            if (transaction) try { execute("ROLLBACK"); } catch (Exception ignored) { db.close(); db=null; }
            // Retain the exact unsaved batch for the next real event; never schedule retries.
            FileLog.e(e);
            return false;
        }
    }
    public void onAccountRemoved(long account) {
        if (account==0) return;
        queue.postRunnable(() -> { removed.add(account); persist(); });
    }
    public void reset(Consumer<Boolean> done) {
        queue.postRunnable(() -> {
            pending.clear(); metrics.clear(); migrations.clear(); confirmations.clear(); resetAt=System.currentTimeMillis(); resetPending=true;
            boolean success=persist();
            AndroidUtilities.runOnUIThread(() -> done.accept(success));
        });
    }
    public void query(int firstDay, int lastDay, Consumer<Report> done) {
        queue.postRunnable(() -> {
            Report result=null;
            if (persist()) try {
                result=new Report();
                SQLiteCursor c=db.queryFinalized(UsageStorageSql.QUERY_BUCKET,firstDay,lastDay);
                try { while(c.next()) result.rows.add(new Row(c.intValue(0),c.intValue(1),c.longValue(2),c.intValue(3),c.longValue(4),c.longValue(5))); } finally { c.dispose(); }
                c=db.queryFinalized(UsageStorageSql.QUERY_DAILY,firstDay,lastDay);
                try { while(c.next()) {
                    UsageMetrics.Daily d=new UsageMetrics.Daily(); d.opens=c.longValue(2); d.sessions=c.longValue(3); d.longestMillis=c.longValue(4)*1000; d.messages=c.longValue(5);
                    result.daily.put(new UsageMetrics.Key(c.intValue(0),c.longValue(1)),d);
                } } finally { c.dispose(); }
            } catch(Exception e) { FileLog.e(e); result=null; }
            Report report=result; AndroidUtilities.runOnUIThread(() -> done.accept(report));
        });
    }
}
