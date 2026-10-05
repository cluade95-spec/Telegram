package org.telegram.messenger.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/** Secondary metrics use earned active milliseconds, never the length of an idle interval. */
public final class UsageMetrics implements UsageAccountant.CreditListener {
    public static final class Key {
        public final int day;
        public final long account;
        public Key(int day, long account) { this.day = day; this.account = account; }
        @Override public boolean equals(Object o) { return o instanceof Key && day == ((Key)o).day && account == ((Key)o).account; }
        @Override public int hashCode() { return 31 * day + Long.hashCode(account); }
    }
    public static final class Daily {
        public long opens, sessions, longestMillis, messages;
        public void merge(Daily d) { opens += d.opens; sessions += d.sessions; longestMillis = Math.max(longestMillis, d.longestMillis); messages += d.messages; }
    }
    private final Map<Key, Daily> pending = new HashMap<>();
    private final Map<Integer, Long> sessionDays = new HashMap<>();
    private long sessionAccount, lastEnd = -1;
    public static int day(LocalDate d) { return d.getYear()*10000 + d.getMonthValue()*100 + d.getDayOfMonth(); }
    public static int day(long wall, ZoneId zone) { return day(Instant.ofEpochMilli(wall).atZone(zone).toLocalDate()); }
    private Daily row(int day, long account) { return pending.computeIfAbsent(new Key(day, account), k -> new Daily()); }
    public void opened(long account, long wall, ZoneId zone) { if (account != 0) row(day(wall, zone), account).opens++; }
    public void sent(long account, long count, long wall, ZoneId zone) { if (account != 0 && count > 0) row(day(wall, zone), account).messages += count; }
    public void endSession() { lastEnd = -1; sessionAccount = 0; sessionDays.clear(); }
    @Override public void credited(SurfaceKey owner, long start, long wall, long duration, ZoneId zone) {
        boolean newSession = lastEnd < 0 || owner.accountUserId != sessionAccount || start - lastEnd > 300_000;
        if (newSession) {
            endSession();
            sessionAccount = owner.accountUserId;
            row(day(wall, zone), sessionAccount).sessions++;
        }
        long left = duration;
        while (left > 0) {
            LocalDate date = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate();
            long next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long amount = Math.min(left, Math.max(1, next - wall));
            int d = day(date);
            long total = sessionDays.getOrDefault(d, 0L) + amount;
            sessionDays.put(d, total);
            row(d, sessionAccount).longestMillis = Math.max(row(d, sessionAccount).longestMillis, total);
            wall += amount;
            left -= amount;
        }
        lastEnd = start + duration;
    }
    public Map<Key, Daily> drain() {
        Map<Key, Daily> result = new HashMap<>(pending);
        pending.clear();
        return result;
    }
    public void reset() { pending.clear(); endSession(); }
}
