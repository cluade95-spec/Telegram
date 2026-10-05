package org.telegram.messenger.usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure report computation for the Activity dashboard (Reference A, A15-A16): period ranges, comparison, category
 * percentages, per-bucket chart series, most used chats, secondary metrics.
 */
public final class UsageReportMath {

    private UsageReportMath() {
    }

    public enum Period {
        TODAY, WEEK, MONTH
    }

    public static final class Range {
        public final int from, to; // inclusive, yyyymmdd

        Range(int from, int to) {
            this.from = from;
            this.to = to;
        }
    }

    public static final class CategoryRow {
        public final UsageSurface.Category category;
        public final long seconds;
        public final int percent;

        CategoryRow(UsageSurface.Category category, long seconds, int percent) {
            this.category = category;
            this.seconds = seconds;
            this.percent = percent;
        }
    }

    public static final class DialogRow {
        public final long account, dialog;
        public final UsageSurface surface;
        public final long seconds;

        DialogRow(long account, long dialog, UsageSurface surface, long seconds) {
            this.account = account;
            this.dialog = dialog;
            this.surface = surface;
            this.seconds = seconds;
        }
    }

    public static final class Report {
        public Period period;
        public long totalSeconds;
        /** Time in the previous period over the same elapsed part (see {@link #previousRange}). */
        public long previousSeconds;
        public final List<CategoryRow> categories = new ArrayList<>();
        public final List<DialogRow> dialogs = new ArrayList<>();
        /** Chart buckets: hours (Today), days of the week, days of the month. */
        public int bucketCount;
        /** chartSeconds[categoryIndex][bucket], categoryIndex = ordinal of the category. */
        public long[][] chartSeconds;
        public int messagesSent, opens, longestSessionSeconds;
        public long callSeconds;
        /** First day of the chart range (bucket 0 for week and month). */
        public int firstDay;

        public boolean isEmpty() {
            return totalSeconds == 0 && messagesSent == 0 && opens == 0;
        }
    }

    /** Monday-0 index of the locale's first day of the week from a Calendar.getFirstDayOfWeek() value (1 = Sunday). */
    public static int firstDowMonday0(int calendarFirstDayOfWeek) {
        return (calendarFirstDayOfWeek + 5) % 7;
    }

    public static Range currentRange(Period p, int today, int firstDowMonday0) {
        switch (p) {
            case WEEK:
                return new Range(weekStart(today, firstDowMonday0), today);
            case MONTH:
                return new Range(UsageDays.firstOfMonth(today), today);
            default:
                return new Range(today, today);
        }
    }

    /**
     * The previous period cut to the same number of elapsed days as the current one (a single day for Today, which
     * {@link #build} further cuts to the elapsed hours), so "by this time yesterday" compares like with like.
     */
    public static Range previousRange(Period p, int today, int firstDowMonday0) {
        Range cur = currentRange(p, today, firstDowMonday0);
        int elapsed = UsageDays.daysBetween(cur.from, cur.to) + 1;
        switch (p) {
            case WEEK: {
                int start = UsageDays.addDays(cur.from, -7);
                return new Range(start, UsageDays.addDays(start, elapsed - 1));
            }
            case MONTH: {
                int prevMonthLast = UsageDays.addDays(cur.from, -1);
                int start = UsageDays.firstOfMonth(prevMonthLast);
                int len = Math.min(elapsed, UsageDays.daysInMonth(prevMonthLast));
                return new Range(start, UsageDays.addDays(start, len - 1));
            }
            default: {
                int y = UsageDays.addDays(today, -1);
                return new Range(y, y);
            }
        }
    }

    /** Earliest day any query for the period needs. */
    public static int queryFrom(Period p, int today, int firstDowMonday0) {
        return previousRange(p, today, firstDowMonday0).from;
    }

    private static int weekStart(int day, int firstDowMonday0) {
        int back = (UsageDays.dayOfWeekMonday0(day) - firstDowMonday0 + 7) % 7;
        return UsageDays.addDays(day, -back);
    }

    public static Report build(Period p, int today, int firstDowMonday0, int nowHour, int nowMinute,
                               List<UsageLedger.Row> rows, List<UsageLedger.DailyRow> daily) {
        Range cur = currentRange(p, today, firstDowMonday0);
        Range prev = previousRange(p, today, firstDowMonday0);
        Report r = new Report();
        r.period = p;
        r.firstDay = cur.from;
        UsageSurface.Category[] cats = UsageSurface.Category.values();
        r.bucketCount = p == Period.TODAY ? 24 : p == Period.WEEK ? 7 : UsageDays.daysInMonth(today);
        r.chartSeconds = new long[cats.length][r.bucketCount];

        long[] perCategory = new long[cats.length];
        Map<String, long[]> perDialog = new HashMap<>(); // key -> {account, dialog, surfaceId, seconds}
        double previous = 0;
        for (UsageLedger.Row row : rows) {
            int day = row.key.day;
            if (day >= cur.from && day <= cur.to) {
                UsageSurface s = UsageSurface.fromId(row.key.surface);
                int ci = s.category.ordinal();
                perCategory[ci] += row.seconds;
                r.totalSeconds += row.seconds;
                int bucket = p == Period.TODAY ? Math.max(0, row.key.hour) : UsageDays.daysBetween(cur.from, day);
                if (bucket >= 0 && bucket < r.bucketCount) {
                    r.chartSeconds[ci][bucket] += row.seconds;
                }
                if (s.isChat() && row.key.dialog != 0) {
                    String key = row.key.account + ":" + row.key.dialog;
                    long[] d = perDialog.get(key);
                    if (d == null) {
                        d = new long[]{row.key.account, row.key.dialog, s.id, 0};
                        perDialog.put(key, d);
                    }
                    d[3] += row.seconds;
                }
            } else if (day >= prev.from && day <= prev.to) {
                if (p == Period.TODAY) {
                    if (row.key.hour < nowHour) {
                        previous += row.seconds;
                    } else if (row.key.hour == nowHour) {
                        previous += row.seconds * (nowMinute / 60.0);
                    }
                } else {
                    previous += row.seconds;
                }
            }
        }
        r.previousSeconds = Math.round(previous);

        for (UsageLedger.DailyRow d : daily) {
            if (d.key.day >= cur.from && d.key.day <= cur.to) {
                r.messagesSent += d.messagesSent;
                r.opens += d.opens;
                r.longestSessionSeconds = Math.max(r.longestSessionSeconds, d.longestSessionSeconds);
            }
        }
        r.callSeconds = perCategory[UsageSurface.Category.CALLS.ordinal()];

        // categories sorted by time, zero hidden, percentages summing to 100
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < cats.length; i++) {
            if (perCategory[i] > 0) {
                order.add(i);
            }
        }
        final long[] pc = perCategory;
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int c = Long.compare(pc[b], pc[a]);
                return c != 0 ? c : Integer.compare(a, b);
            }
        });
        int[] percents = percentages(order, perCategory, r.totalSeconds);
        for (int i = 0; i < order.size(); i++) {
            int ci = order.get(i);
            r.categories.add(new CategoryRow(cats[ci], perCategory[ci], percents[i]));
        }

        for (long[] d : perDialog.values()) {
            r.dialogs.add(new DialogRow(d[0], d[1], UsageSurface.fromId((int) d[2]), d[3]));
        }
        Collections.sort(r.dialogs, new Comparator<DialogRow>() {
            @Override
            public int compare(DialogRow a, DialogRow b) {
                int c = Long.compare(b.seconds, a.seconds);
                return c != 0 ? c : Long.compare(a.dialog, b.dialog);
            }
        });
        return r;
    }

    /** Largest-remainder rounding: the percentages add up to exactly 100 when there is any time. */
    static int[] percentages(List<Integer> order, long[] perCategory, long total) {
        int n = order.size();
        int[] out = new int[n];
        if (total <= 0 || n == 0) {
            return out;
        }
        double[] rem = new double[n];
        int sum = 0;
        for (int i = 0; i < n; i++) {
            double exact = perCategory[order.get(i)] * 100.0 / total;
            out[i] = (int) Math.floor(exact);
            rem[i] = exact - out[i];
            sum += out[i];
        }
        while (sum < 100) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (rem[i] > rem[best]) {
                    best = i;
                }
            }
            out[best]++;
            rem[best] = -1;
            sum++;
        }
        return out;
    }
}
