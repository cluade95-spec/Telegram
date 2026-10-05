package org.telegram.messenger.usage;

import java.util.TimeZone;

/**
 * Pure active-time accounting (Reference A, A4-A6, A13). Event driven: nothing runs while the user is idle, the credit
 * for the open segment is computed lazily at the next event. Not thread safe; callers use the UI thread.
 *
 * Every state change first settles the open segment with the old state, then mutates, then restarts the segment, so a
 * second is credited at most once.
 */
public final class UsageAccountant {

    private final UsageClock clock;
    private final UsageLedger ledger = new UsageLedger();

    private boolean foreground, screenOn, call, passive;
    private long account;
    private UsageSurface surface = UsageSurface.OTHER;
    private long dialog;

    private long segStart;
    private long lastInputAt = Long.MIN_VALUE / 4;

    private long runMs;
    private long runLastEnd = Long.MIN_VALUE / 4;

    public UsageAccountant(UsageClock clock) {
        this.clock = clock;
        this.segStart = clock.elapsed();
    }

    // events

    /**
     * Hot path (every touch): while inputs keep arriving within the idle window the open segment is contiguous, so
     * one long store is enough; the segment is only settled after a gap or once it has grown to the flush threshold.
     */
    public void onInput() {
        long now = clock.elapsed();
        if (now - lastInputAt > UsagePolicy.IDLE_MS || now - segStart >= UsagePolicy.FLUSH_THRESHOLD_MS) {
            settle(now);
        }
        lastInputAt = now;
    }

    public void onForeground() {
        long now = clock.elapsed();
        settle(now);
        if (!foreground) {
            foreground = true;
            lastInputAt = now;
            runMs = 0;
            runLastEnd = Long.MIN_VALUE / 4;
            long wall = clock.wallMillis();
            int day = UsageDays.dayOfLocal(wall + clock.zone().getOffset(wall));
            ledger.addOpen(day, account);
        }
    }

    public void onBackground() {
        long now = clock.elapsed();
        settle(now);
        foreground = false;
        runMs = 0;
        runLastEnd = Long.MIN_VALUE / 4;
    }

    public void onScreen(boolean on) {
        long now = clock.elapsed();
        settle(now);
        if (on && !screenOn) {
            lastInputAt = now;
        }
        screenOn = on;
        if (!on) {
            runMs = 0;
            runLastEnd = Long.MIN_VALUE / 4;
        }
    }

    public void onSurface(UsageSurface surface, long dialog) {
        long now = clock.elapsed();
        settle(now);
        this.surface = surface;
        this.dialog = surface.isChat() ? dialog : 0;
    }

    /** Engaged passive activity (video, stories, voice playback); true while it plays. */
    public void onPassive(boolean playing) {
        settle(clock.elapsed());
        passive = playing;
    }

    public void onCall(boolean connected) {
        settle(clock.elapsed());
        call = connected;
    }

    public void onAccount(long account) {
        settle(clock.elapsed());
        this.account = account;
    }

    public void onMessagesSent(int n) {
        onMessagesSent(account, n);
    }

    /** The confirmation can belong to an account that is not the selected one. */
    public void onMessagesSent(long accountUserId, int n) {
        long wall = clock.wallMillis();
        ledger.addMessagesSent(UsageDays.dayOfLocal(wall + clock.zone().getOffset(wall)), accountUserId, n);
    }

    /** Settles the open segment (used by flush triggers and the dashboard). */
    public void tick() {
        settle(clock.elapsed());
    }

    /** Settles and hands out everything credited so far. */
    public UsageLedger.Snapshot drain() {
        settle(clock.elapsed());
        return ledger.drain();
    }

    public boolean flushDue() {
        return ledger.pendingMs() >= UsagePolicy.FLUSH_THRESHOLD_MS;
    }

    public UsageLedger ledger() {
        return ledger;
    }

    // internals

    private boolean segmentOpen() {
        return call || (foreground && screenOn);
    }

    private void settle(long now) {
        long start = segStart;
        segStart = now;
        if (!segmentOpen() || now <= start) {
            return;
        }
        long end;
        if (call) {
            end = now;
        } else {
            long activeUntil = passive ? Long.MAX_VALUE : lastInputAt + UsagePolicy.IDLE_MS;
            end = Math.min(now, activeUntil);
        }
        long d = end - start;
        if (d <= 0) {
            return;
        }
        book(start, end, now);
    }

    private void book(long start, long end, long now) {
        UsageSurface s = call ? UsageSurface.CALL : surface;
        long dlg = call ? 0 : dialog;
        long wallEnd = clock.wallMillis() - (now - end);
        TimeZone zone = clock.zone();
        long remaining = end - start;
        long wall = wallEnd - remaining;
        int lastDay = 0;
        while (remaining > 0) {
            long local = wall + zone.getOffset(wall);
            long toNext = 3_600_000L - Math.floorMod(local, 3_600_000L);
            long chunk = Math.min(remaining, toNext);
            lastDay = UsageDays.dayOfLocal(local);
            ledger.add(lastDay, UsageDays.hourOfLocal(local), account, s.id, dlg, chunk);
            wall += chunk;
            remaining -= chunk;
        }
        // longest session: continuous active time with no gap above SESSION_GAP_MS
        if (start - runLastEnd > UsagePolicy.SESSION_GAP_MS) {
            runMs = 0;
        }
        runMs += end - start;
        runLastEnd = end;
        ledger.noteSession(lastDay, account, (int) (runMs / 1000));
    }
}
