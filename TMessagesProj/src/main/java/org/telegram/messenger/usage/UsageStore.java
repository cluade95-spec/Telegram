package org.telegram.messenger.usage;

/**
 * Placeholder for phase A4 (SQLite persistence). Until then drained snapshots are dropped.
 */
public final class UsageStore {

    private static final UsageStore INSTANCE = new UsageStore();

    public static UsageStore getInstance() {
        return INSTANCE;
    }

    public void write(UsageLedger.Snapshot snapshot) {
    }
}
