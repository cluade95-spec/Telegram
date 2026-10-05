package org.telegram.messenger.localhistory;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.io.File;

/**
 * Per-account entry point of Local History (plan B29). Owns the feature database, lazily opened. The feature is off
 * until the user turns it on; nothing is captured and no file is created while it is off.
 */
public class LocalHistory {

    private static final String KEY_ENABLED = "localHistoryEnabled";
    private static final LocalHistory[] instances = new LocalHistory[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
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
    }

    private synchronized void forget() {
        if (database != null) {
            database.close();
        }
        database = null;
        ledger = null;
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
