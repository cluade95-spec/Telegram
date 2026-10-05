package org.telegram.messenger.localhistory;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Absolute paths of files that Telegram wants to delete but Local History has not copied yet (plan B10).
 * Held in memory only; after a restart the pending media rows put them back.
 */
public final class LocalHistoryMediaHolds implements LocalHistoryMediaManager.Holds {

    private static final LocalHistoryMediaHolds INSTANCE = new LocalHistoryMediaHolds();
    private static final Set<String> held = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private LocalHistoryMediaHolds() {
    }

    public static LocalHistoryMediaHolds get() {
        return INSTANCE;
    }

    /** Called by FileLoader.deleteFiles for every file it is about to delete. */
    public static boolean isHeld(File file) {
        return file != null && !held.isEmpty() && held.contains(file.getAbsolutePath());
    }

    @Override
    public void hold(String path) {
        if (path != null) {
            held.add(path);
        }
    }

    @Override
    public void release(String path) {
        if (path != null) {
            held.remove(path);
        }
    }

    /** For tests and diagnostics. */
    public static Set<String> snapshot() {
        return new HashSet<>(held);
    }
}
