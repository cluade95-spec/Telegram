package org.telegram.messenger.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** In-memory millisecond credits. A persistence adapter converts whole seconds at flush. */
public final class UsageLedger {
    public static final class BucketKey {
        public final int day;
        public final int hour;
        public final SurfaceKey surface;

        BucketKey(int day, int hour, SurfaceKey surface) {
            this.day = day;
            this.hour = hour;
            this.surface = surface;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BucketKey)) return false;
            BucketKey key = (BucketKey) other;
            return day == key.day && hour == key.hour && surface.equals(key.surface);
        }

        @Override
        public int hashCode() {
            return Objects.hash(day, hour, surface);
        }
    }

    private final Map<BucketKey, Long> buckets = new HashMap<>();
    private long totalMillis;

    public void add(SurfaceKey surface, long wallStart, long duration, ZoneId zone) {
        if (surface == null || duration <= 0) return;
        long remaining = duration;
        long cursor = wallStart;
        while (remaining > 0) {
            ZonedDateTime local = Instant.ofEpochMilli(cursor).atZone(zone);
            long next = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli();
            long portion = Math.min(remaining, Math.max(1, next - cursor));
            LocalDate date = local.toLocalDate();
            int day = date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth();
            BucketKey key = new BucketKey(day, local.getHour(), surface);
            buckets.merge(key, portion, Long::sum);
            totalMillis += portion;
            cursor += portion;
            remaining -= portion;
        }
    }

    public long totalMillis() {
        return totalMillis;
    }

    public Map<BucketKey, Long> snapshot() {
        return new HashMap<>(buckets);
    }

    public Map<BucketKey, Long> drain() {
        Map<BucketKey, Long> result = snapshot();
        buckets.clear();
        totalMillis = 0;
        return result;
    }
}
