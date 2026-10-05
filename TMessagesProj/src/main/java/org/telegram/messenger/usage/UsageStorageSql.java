package org.telegram.messenger.usage;

/** SQL is kept independent of Android so host SQLite tests exercise the production statements. */
public final class UsageStorageSql {
    private UsageStorageSql() { }
    public static final String META = "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value BLOB)";
    public static final String BUCKET = "CREATE TABLE IF NOT EXISTS bucket(day INTEGER NOT NULL, hour INTEGER NOT NULL, account INTEGER NOT NULL, surface INTEGER NOT NULL, dialog INTEGER NOT NULL, seconds INTEGER NOT NULL, PRIMARY KEY(day,hour,account,surface,dialog)) WITHOUT ROWID";
    public static final String INDEX = "CREATE INDEX IF NOT EXISTS bucket_dialog ON bucket(account,dialog,day)";
    public static final String DAILY = "CREATE TABLE IF NOT EXISTS daily(day INTEGER NOT NULL, account INTEGER NOT NULL, opens INTEGER NOT NULL DEFAULT 0, sessions INTEGER NOT NULL DEFAULT 0, longest_session INTEGER NOT NULL DEFAULT 0, messages_sent INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day,account)) WITHOUT ROWID";
    public static final String SENT = "CREATE TABLE IF NOT EXISTS sent(account INTEGER NOT NULL, token TEXT NOT NULL, PRIMARY KEY(account,token)) WITHOUT ROWID";
    public static final String HAS_SENT = "SELECT 1 FROM sent WHERE account=? AND token=?";
    public static final String INSERT_SENT = "INSERT INTO sent VALUES(?,?)";
    public static final String SENT_METRIC = "INSERT INTO daily(day,account,messages_sent) VALUES(?,?,1) ON CONFLICT(day,account) DO UPDATE SET messages_sent=messages_sent+1";
    public static final String UPSERT_BUCKET = "INSERT INTO bucket VALUES(?,?,?,?,?,?) ON CONFLICT(day,hour,account,surface,dialog) DO UPDATE SET seconds=seconds+excluded.seconds";
    public static final String UPSERT_DAILY = "INSERT INTO daily VALUES(?,?,?,?,?,?) ON CONFLICT(day,account) DO UPDATE SET opens=opens+excluded.opens, sessions=sessions+excluded.sessions, longest_session=MAX(longest_session,excluded.longest_session), messages_sent=messages_sent+excluded.messages_sent";
    public static final String ROLLUP = "INSERT INTO bucket SELECT day,-1,account,surface,dialog,SUM(seconds) FROM bucket WHERE day<? AND hour<>-1 GROUP BY day,account,surface,dialog ON CONFLICT(day,hour,account,surface,dialog) DO UPDATE SET seconds=seconds+excluded.seconds";
    public static final String DELETE_HOURS = "DELETE FROM bucket WHERE day<? AND hour<>-1";
    public static final String FOLD = "INSERT INTO bucket SELECT day,hour,account,surface,0,SUM(seconds) FROM bucket WHERE day<? AND dialog<>0 AND (day,account,dialog) IN (SELECT day,account,dialog FROM bucket WHERE day<? AND dialog<>0 GROUP BY day,account,dialog HAVING SUM(seconds)<60) GROUP BY day,hour,account,surface ON CONFLICT(day,hour,account,surface,dialog) DO UPDATE SET seconds=seconds+excluded.seconds";
    public static final String DELETE_FOLDED = "DELETE FROM bucket WHERE day<? AND dialog<>0 AND (day,account,dialog) IN (SELECT day,account,dialog FROM bucket WHERE day<? AND dialog<>0 GROUP BY day,account,dialog HAVING SUM(seconds)<60)";
    public static final String ANONYMIZE = "INSERT INTO bucket SELECT day,hour,account,surface,0,SUM(seconds) FROM bucket WHERE account=? AND dialog<>0 GROUP BY day,hour,account,surface ON CONFLICT(day,hour,account,surface,dialog) DO UPDATE SET seconds=seconds+excluded.seconds";
    public static final String DELETE_IDENTITIES = "DELETE FROM bucket WHERE account=? AND dialog<>0";
    public static final String MIGRATE = "INSERT INTO bucket SELECT day,hour,account,surface,?,SUM(seconds) FROM bucket WHERE account=? AND dialog=? GROUP BY day,hour,account,surface ON CONFLICT(day,hour,account,surface,dialog) DO UPDATE SET seconds=seconds+excluded.seconds";
    public static final String DELETE_MIGRATED = "DELETE FROM bucket WHERE account=? AND dialog=?";
    public static final String QUERY_BUCKET = "SELECT day,hour,account,surface,dialog,seconds FROM bucket WHERE day>=? AND day<=?";
    public static final String QUERY_DAILY = "SELECT day,account,opens,sessions,longest_session,messages_sent FROM daily WHERE day>=? AND day<=?";
}
