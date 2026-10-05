package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class UsageReportMathTest {

    private static final int MON0 = 0; // weeks start on Monday
    private static final int SUN0 = 6;

    private static UsageLedger.Row row(int day, int hour, UsageSurface s, long dialog, int sec) {
        return new UsageLedger.Row(new UsageLedger.BucketKey(day, hour, 1, s.id, dialog), sec);
    }

    @Test
    public void firstDayOfWeekMapping() {
        assertEquals(6, UsageReportMath.firstDowMonday0(Calendar.SUNDAY));
        assertEquals(0, UsageReportMath.firstDowMonday0(Calendar.MONDAY));
        assertEquals(5, UsageReportMath.firstDowMonday0(Calendar.SATURDAY));
    }

    @Test
    public void weekRangeFollowsTheLocaleFirstDay() {
        // 2024-03-13 is a Wednesday
        UsageReportMath.Range mon = UsageReportMath.currentRange(UsageReportMath.Period.WEEK, 20240313, MON0);
        assertEquals(20240311, mon.from);
        UsageReportMath.Range sun = UsageReportMath.currentRange(UsageReportMath.Period.WEEK, 20240313, SUN0);
        assertEquals(20240310, sun.from);
        // today is the first day of the week
        assertEquals(20240311, UsageReportMath.currentRange(UsageReportMath.Period.WEEK, 20240311, MON0).from);
    }

    @Test
    public void monthRangeAndPreviousElapsedPart() {
        UsageReportMath.Range cur = UsageReportMath.currentRange(UsageReportMath.Period.MONTH, 20240331, MON0);
        assertEquals(20240301, cur.from);
        UsageReportMath.Range prev = UsageReportMath.previousRange(UsageReportMath.Period.MONTH, 20240331, MON0);
        assertEquals(20240201, prev.from);
        assertEquals(20240229, prev.to); // February 2024 has 29 days, cut to its length
        UsageReportMath.Range prev2 = UsageReportMath.previousRange(UsageReportMath.Period.MONTH, 20240310, MON0);
        assertEquals(20240210, prev2.to);
    }

    @Test
    public void todayComparesWithYesterdayByThisTime() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20240313, 9, UsageSurface.CHAT_LIST, 0, 600));
        rows.add(row(20240312, 8, UsageSurface.CHAT_LIST, 0, 300)); // before now: counts
        rows.add(row(20240312, 10, UsageSurface.CHAT_LIST, 0, 1800)); // the current hour, prorated by minute
        rows.add(row(20240312, 15, UsageSurface.CHAT_LIST, 0, 3000)); // later than now: not counted
        UsageReportMath.Report r = UsageReportMath.build(UsageReportMath.Period.TODAY, 20240313, MON0, 10, 30, rows, new ArrayList<>());
        assertEquals(600, r.totalSeconds);
        assertEquals(300 + 900, r.previousSeconds);
    }

    @Test
    public void categoriesNeverOverlapAndPercentagesSumTo100() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20240313, 9, UsageSurface.CHAT_PRIVATE, 5, 100));
        rows.add(row(20240313, 9, UsageSurface.CHAT_SECRET, 7, 100)); // same category as private
        rows.add(row(20240313, 9, UsageSurface.CHAT_GROUP, -3, 100));
        rows.add(row(20240313, 9, UsageSurface.CHAT_LIST, 0, 100));
        rows.add(row(20240313, 9, UsageSurface.SETTINGS, 0, 100)); // other
        rows.add(row(20240313, 9, UsageSurface.CHAT_SAVED, 9, 100)); // other, still listed in most used
        rows.add(row(20240313, 9, UsageSurface.CALL, 0, 100));
        UsageReportMath.Report r = UsageReportMath.build(UsageReportMath.Period.TODAY, 20240313, MON0, 12, 0, rows, new ArrayList<>());
        long sum = 0;
        int pct = 0;
        for (UsageReportMath.CategoryRow c : r.categories) {
            sum += c.seconds;
            pct += c.percent;
        }
        assertEquals(r.totalSeconds, sum);
        assertEquals(100, pct);
        assertEquals(700, sum);
        assertEquals(100, r.callSeconds);
        assertEquals(UsageSurface.Category.PRIVATE, r.categories.get(0).category); // 200 s, largest first
        assertEquals(4, r.dialogs.size()); // private, secret, group, saved
    }

    @Test
    public void largestRemainderGivesExactly100() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20240313, 9, UsageSurface.CHAT_PRIVATE, 5, 1));
        rows.add(row(20240313, 9, UsageSurface.CHAT_GROUP, -3, 1));
        rows.add(row(20240313, 9, UsageSurface.CHAT_CHANNEL, -4, 1));
        UsageReportMath.Report r = UsageReportMath.build(UsageReportMath.Period.TODAY, 20240313, MON0, 12, 0, rows, new ArrayList<>());
        int pct = 0;
        for (UsageReportMath.CategoryRow c : r.categories) {
            pct += c.percent;
        }
        assertEquals(100, pct);
    }

    @Test
    public void chartBucketsAndEmptyCategoriesHidden() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20240313, 9, UsageSurface.CHAT_PRIVATE, 5, 60));
        rows.add(row(20240313, 23, UsageSurface.CHAT_PRIVATE, 5, 30));
        UsageReportMath.Report today = UsageReportMath.build(UsageReportMath.Period.TODAY, 20240313, MON0, 23, 59, rows, new ArrayList<>());
        assertEquals(24, today.bucketCount);
        assertEquals(60, today.chartSeconds[UsageSurface.Category.PRIVATE.ordinal()][9]);
        assertEquals(30, today.chartSeconds[UsageSurface.Category.PRIVATE.ordinal()][23]);
        assertEquals(1, today.categories.size());
        UsageReportMath.Report week = UsageReportMath.build(UsageReportMath.Period.WEEK, 20240313, MON0, 23, 59, rows, new ArrayList<>());
        assertEquals(7, week.bucketCount);
        assertEquals(90, week.chartSeconds[UsageSurface.Category.PRIVATE.ordinal()][2]); // Mon, Tue, Wed
        UsageReportMath.Report month = UsageReportMath.build(UsageReportMath.Period.MONTH, 20240313, MON0, 23, 59, rows, new ArrayList<>());
        assertEquals(31, month.bucketCount);
        assertEquals(90, month.chartSeconds[UsageSurface.Category.PRIVATE.ordinal()][12]);
    }

    @Test
    public void rolledUpRowsStillCount() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20240305, -1, UsageSurface.CHAT_PRIVATE, 5, 500));
        UsageReportMath.Report month = UsageReportMath.build(UsageReportMath.Period.MONTH, 20240313, MON0, 12, 0, rows, new ArrayList<>());
        assertEquals(500, month.totalSeconds);
        assertEquals(500, month.chartSeconds[UsageSurface.Category.PRIVATE.ordinal()][4]);
    }

    @Test
    public void secondaryMetricsComeFromTheCurrentRangeOnly() {
        List<UsageLedger.DailyRow> daily = new ArrayList<>();
        UsageLedger.DailyRow a = new UsageLedger.DailyRow(new UsageLedger.DailyKey(20240312, 1));
        a.messagesSent = 4;
        a.opens = 2;
        a.longestSessionSeconds = 600;
        UsageLedger.DailyRow b = new UsageLedger.DailyRow(new UsageLedger.DailyKey(20240313, 1));
        b.messagesSent = 1;
        b.opens = 1;
        b.longestSessionSeconds = 100;
        daily.add(a);
        daily.add(b);
        UsageReportMath.Report today = UsageReportMath.build(UsageReportMath.Period.TODAY, 20240313, MON0, 12, 0, new ArrayList<>(), daily);
        assertEquals(1, today.messagesSent);
        assertEquals(1, today.opens);
        UsageReportMath.Report week = UsageReportMath.build(UsageReportMath.Period.WEEK, 20240313, MON0, 12, 0, new ArrayList<>(), daily);
        assertEquals(5, week.messagesSent);
        assertEquals(3, week.opens);
        assertEquals(600, week.longestSessionSeconds);
        assertTrue(UsageReportMath.build(UsageReportMath.Period.TODAY, 20240101, MON0, 1, 0, new ArrayList<>(), new ArrayList<>()).isEmpty());
    }

    @Test
    public void queryRangeCoversThePreviousPeriod() {
        assertEquals(20240312, UsageReportMath.queryFrom(UsageReportMath.Period.TODAY, 20240313, MON0));
        assertEquals(20240304, UsageReportMath.queryFrom(UsageReportMath.Period.WEEK, 20240313, MON0));
        assertEquals(20240201, UsageReportMath.queryFrom(UsageReportMath.Period.MONTH, 20240313, MON0));
    }
}
