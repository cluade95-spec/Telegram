package org.telegram.messenger.usage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory aggregation of credited time. Time is kept in milliseconds so sub-second remainders survive a drain; rows
 * are handed out in whole seconds.
 */
public final class UsageLedger {

    public static final class BucketKey {
        public final int day, hour, surface;
        public final long account, dialog;

        public BucketKey(int day, int hour, long account, int surface, long dialog) {
            this.day = day;
            this.hour = hour;
            this.account = account;
            this.surface = surface;
            this.dialog = dialog;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BucketKey)) {
                return false;
            }
            BucketKey k = (BucketKey) o;
            return day == k.day && hour == k.hour && surface == k.surface && account == k.account && dialog == k.dialog;
        }

        @Override
        public int hashCode() {
            return Objects.hash(day, hour, account, surface, dialog);
        }
    }

    public static final class DailyKey {
        public final int day;
        public final long account;

        public DailyKey(int day, long account) {
            this.day = day;
            this.account = account;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DailyKey && ((DailyKey) o).day == day && ((DailyKey) o).account == account;
        }

        @Override
        public int hashCode() {
            return Objects.hash(day, account);
        }
    }

    public static final class Row {
        public final BucketKey key;
        public final int seconds;

        public Row(BucketKey key, int seconds) {
            this.key = key;
            this.seconds = seconds;
        }
    }

    public static final class DailyRow {
        public final DailyKey key;
        public int opens, sessions, longestSessionSeconds, messagesSent;

        public DailyRow(DailyKey key) {
            this.key = key;
        }

        boolean isEmpty() {
            return opens == 0 && sessions == 0 && longestSessionSeconds == 0 && messagesSent == 0;
        }
    }

    public static final class Snapshot {
        public final List<Row> rows;
        public final List<DailyRow> daily;

        Snapshot(List<Row> rows, List<DailyRow> daily) {
            this.rows = rows;
            this.daily = daily;
        }

        public boolean isEmpty() {
            return rows.isEmpty() && daily.isEmpty();
        }
    }

    private final HashMap<BucketKey, Long> millis = new HashMap<>();
    private final HashMap<DailyKey, DailyRow> daily = new HashMap<>();
    private long pendingMs;

    public void add(int day, int hour, long account, int surface, long dialog, long ms) {
        if (ms <= 0) {
            return;
        }
        BucketKey key = new BucketKey(day, hour, account, surface, dialog);
        Long old = millis.get(key);
        millis.put(key, (old == null ? 0 : old) + ms);
        pendingMs += ms;
    }

    private DailyRow daily(int day, long account) {
        DailyKey key = new DailyKey(day, account);
        DailyRow row = daily.get(key);
        if (row == null) {
            row = new DailyRow(key);
            daily.put(key, row);
        }
        return row;
    }

    public void addOpen(int day, long account) {
        DailyRow r = daily(day, account);
        r.opens++;
        r.sessions++;
    }

    public void addMessagesSent(int day, long account, int n) {
        if (n > 0) {
            daily(day, account).messagesSent += n;
        }
    }

    public void noteSession(int day, long account, int seconds) {
        DailyRow r = daily(day, account);
        if (seconds > r.longestSessionSeconds) {
            r.longestSessionSeconds = seconds;
        }
    }

    /** Milliseconds credited and not yet drained. */
    public long pendingMs() {
        return pendingMs;
    }

    public long totalMs() {
        long t = 0;
        for (Long v : millis.values()) {
            t += v;
        }
        return t;
    }

    /** Hands out whole seconds and keeps the sub-second remainders. */
    public Snapshot drain() {
        ArrayList<Row> rows = new ArrayList<>();
        HashMap<BucketKey, Long> rest = new HashMap<>();
        for (Map.Entry<BucketKey, Long> e : millis.entrySet()) {
            long sec = e.getValue() / 1000;
            long rem = e.getValue() % 1000;
            if (sec > 0) {
                rows.add(new Row(e.getKey(), (int) sec));
            }
            if (rem > 0) {
                rest.put(e.getKey(), rem);
            }
        }
        millis.clear();
        millis.putAll(rest);
        long p = 0;
        for (Long v : rest.values()) {
            p += v;
        }
        pendingMs = p;
        ArrayList<DailyRow> d = new ArrayList<>();
        for (DailyRow r : daily.values()) {
            if (!r.isEmpty()) {
                d.add(r);
            }
        }
        daily.clear();
        return new Snapshot(rows, d);
    }
}
