package org.telegram.messenger.usage;

/**
 * Local day arithmetic. A "day" is the integer yyyymmdd; an "epoch day" counts days since 1970-01-01. Pure, no
 * java.time (minSdk 21).
 */
public final class UsageDays {

    private UsageDays() {
    }

    /** Local day number of a local-time millisecond value (wall millis plus zone offset). */
    public static int dayOfLocal(long localMillis) {
        return fromEpochDay(Math.floorDiv(localMillis, 86_400_000L));
    }

    public static int hourOfLocal(long localMillis) {
        return (int) (Math.floorMod(localMillis, 86_400_000L) / 3_600_000L);
    }

    public static int fromEpochDay(long epochDay) {
        long z = epochDay + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) {
            y++;
        }
        return (int) (y * 10000 + m * 100 + d);
    }

    public static long toEpochDay(int day) {
        long y = day / 10000;
        long m = (day / 100) % 100;
        long d = day % 100;
        if (m <= 2) {
            y--;
        }
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    public static int addDays(int day, int delta) {
        return fromEpochDay(toEpochDay(day) + delta);
    }

    /** 0 = Monday ... 6 = Sunday. */
    public static int dayOfWeekMonday0(int day) {
        return (int) Math.floorMod(toEpochDay(day) + 3, 7L);
    }

    public static int daysInMonth(int day) {
        int y = day / 10000;
        int m = (day / 100) % 100;
        int first = y * 10000 + m * 100 + 1;
        int nextFirst = m == 12 ? (y + 1) * 10000 + 101 : y * 10000 + (m + 1) * 100 + 1;
        return (int) (toEpochDay(nextFirst) - toEpochDay(first));
    }

    public static int firstOfMonth(int day) {
        return day / 100 * 100 + 1;
    }

    /** Whole days from a to b (b - a), both yyyymmdd. */
    public static int daysBetween(int a, int b) {
        return (int) (toEpochDay(b) - toEpochDay(a));
    }
}
