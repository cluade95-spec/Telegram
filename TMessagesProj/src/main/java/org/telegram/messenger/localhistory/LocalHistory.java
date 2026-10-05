package org.telegram.messenger.localhistory;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.util.List;

/**
 * Per-account entry point of Local History (plan B29). Owns the feature database, lazily opened. The feature is off
 * until the user turns it on; nothing is captured and no file is created while it is off.
 */
public class LocalHistory {

    private static final String KEY_ENABLED = "localHistoryEnabled";
    private static final LocalHistory[] instances = new LocalHistory[UserConfig.MAX_ACCOUNT_COUNT];

    private static final String META_LAST_READ_AT = "last_read_at";
    private static final String META_TITLE = "title";
    private static DispatchQueue queue;

    /** What the chat-list row shows; computed on the feature queue, read from anywhere. */
    public static final class Summary {
        public int count;
        public int deleted;
        public int edited;
        public int unread;
        public int lastEventAt;
        public String lastSender;
        public String lastText;
        public boolean lastDeleted;
        public String title;
    }

    private final int currentAccount;
    private volatile Summary summary;
    private LocalHistoryDatabase database;
    private LocalHistoryLedger ledger;
    private volatile boolean enabledLoaded;
    private volatile boolean enabled;

    private LocalHistory(int account) {
        currentAccount = account;
    }

    public static LocalHistory getInstance(int account) {
        LocalHistory local = instances[account];
        if (local == null) {
            synchronized (LocalHistory.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new LocalHistory(account);
                }
            }
        }
        return local;
    }

    public static synchronized DispatchQueue getQueue() {
        if (queue == null) {
            queue = new DispatchQueue("localHistoryQueue");
        }
        return queue;
    }

    /** Cached summary; null until the first {@link #refreshSummary()} finished or while the feature is off or empty. */
    public Summary getSummary() {
        return summary;
    }

    private boolean summaryRequested;

    /** Starts the first summary load, once per process, so the chat-list row can appear without a loop of refreshes. */
    public void ensureSummary() {
        synchronized (this) {
            if (summaryRequested) {
                return;
            }
            summaryRequested = true;
        }
        refreshSummary();
    }

    /** Recomputes the summary on the feature queue and posts localHistoryChanged on the UI thread. */
    public void refreshSummary() {
        getQueue().postRunnable(() -> {
            Summary next = null;
            if (isEnabled() || databaseExists()) {
                next = computeSummary();
            }
            summary = next;
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.localHistoryChanged));
        });
    }

    private boolean databaseExists() {
        return database != null || new File(directoryFor(UserConfig.getInstance(currentAccount).getClientUserId()), "history.db").exists();
    }

    private Summary computeSummary() {
        LocalHistoryRepository repo = getRepository();
        int count = repo.entryCount();
        if (count == 0) {
            return null;
        }
        Summary s = new Summary();
        s.count = count;
        s.deleted = repo.deletedCount();
        s.edited = repo.editedCount();
        s.unread = repo.countAfter(lastReadAt(repo));
        s.title = repo.getMeta(META_TITLE);
        List<LocalHistoryRepository.Entry> last = repo.pageFeedBefore(Integer.MAX_VALUE, Long.MAX_VALUE, 1);
        if (!last.isEmpty()) {
            LocalHistoryRepository.Entry e = last.get(0);
            s.lastEventAt = e.lastEventAt;
            s.lastSender = e.nameSnapshot;
            s.lastDeleted = e.isDeleted();
            List<LocalHistoryRepository.Revision> revisions = repo.revisions(e.id);
            if (!revisions.isEmpty()) {
                s.lastText = revisions.get(revisions.size() - 1).text;
            }
        }
        return s;
    }

    private static int lastReadAt(LocalHistoryRepository repo) {
        try {
            String v = repo.getMeta(META_LAST_READ_AT);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Opening the chat marks everything read locally; never touches Telegram's read state. */
    public void markAllRead() {
        getQueue().postRunnable(() -> {
            LocalHistoryRepository repo = getRepository();
            List<LocalHistoryRepository.Entry> last = repo.pageFeedBefore(Integer.MAX_VALUE, Long.MAX_VALUE, 1);
            if (!last.isEmpty()) {
                repo.setMeta(META_LAST_READ_AT, String.valueOf(last.get(0).lastEventAt));
            }
            refreshSummary();
        });
    }

    /** Deletes every entry and resets unread; the title and the on/off setting stay. */
    public void clearHistory() {
        getQueue().postRunnable(() -> {
            LocalHistoryRepository repo = getRepository();
            getLedger().clear();
            repo.setMeta(META_LAST_READ_AT, null);
            refreshSummary();
        });
    }

    public boolean isEnabled() {
        if (!enabledLoaded) {
            enabled = UserConfig.getInstance(currentAccount).getPreferences().getBoolean(KEY_ENABLED, false);
            enabledLoaded = true;
        }
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        enabledLoaded = true;
        UserConfig.getInstance(currentAccount).getPreferences().edit().putBoolean(KEY_ENABLED, value).apply();
        refreshSummary();
    }

    public synchronized LocalHistoryLedger getLedger() {
        if (ledger == null) {
            ledger = new LocalHistoryLedger(getRepository());
        }
        return ledger;
    }

    public synchronized LocalHistoryRepository getRepository() {
        if (database == null) {
            database = new LocalHistoryDatabase(directoryFor(UserConfig.getInstance(currentAccount).getClientUserId()));
        }
        return database;
    }

    /** Erases everything stored for this account and keeps the feature settings. */
    public synchronized void deleteAll() {
        if (database != null) {
            database.destroy();
        } else {
            deleteDirectory(directoryFor(UserConfig.getInstance(currentAccount).getClientUserId()));
        }
        summary = null;
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.localHistoryChanged));
    }

    private synchronized void forget() {
        if (database != null) {
            database.close();
        }
        database = null;
        ledger = null;
        summary = null;
        enabledLoaded = false;
    }

    public static File directoryFor(long userId) {
        Context context = ApplicationLoader.applicationContext;
        return new File(new File(context.getNoBackupFilesDir(), "local_history"), String.valueOf(userId));
    }

    /** Called from UserConfig.clearConfig before the user id is reset: logout removes the archive of that account. */
    public static void onAccountRemoved(int account, long userId) {
        LocalHistory local = instances[account];
        if (local != null) {
            local.forget();
        }
        if (userId != 0) {
            deleteDirectory(directoryFor(userId));
        }
    }

    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
