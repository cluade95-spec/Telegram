package org.telegram.messenger.usage;
import org.junit.Test;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import static org.junit.Assert.*;
public class UsageReportMathTest {
    @Test public void localeWeeksAndCalendarMonthsIncludeLeapDays() {
        LocalDate today=LocalDate.of(2024,2,29);
        UsageReportMath.Range us=new UsageReportMath.Range(UsageReportMath.Period.WEEK,today,Locale.US);
        UsageReportMath.Range uk=new UsageReportMath.Range(UsageReportMath.Period.WEEK,today,Locale.UK);
        assertEquals(LocalDate.of(2024,2,25),us.start); assertEquals(LocalDate.of(2024,2,26),uk.start);
        UsageReportMath.Range month=new UsageReportMath.Range(UsageReportMath.Period.MONTH,today,Locale.US);
        assertEquals(29,month.bins()); assertEquals(LocalDate.of(2024,1,1),month.previousStart); assertEquals(LocalDate.of(2024,1,31),month.previousEnd);
    }
    @Test public void todayComparesOnlyElapsedHoursWithPartialHourEstimate() {
        ZonedDateTime now=ZonedDateTime.parse("2026-10-05T12:30:00Z");
        UsageReportMath.Range range=new UsageReportMath.Range(UsageReportMath.Period.TODAY,now.toLocalDate(),Locale.US);
        List<UsageReportMath.Record> rows=Arrays.asList(
            new UsageReportMath.Record(20261004,11,11,UsageSurface.CHAT_LIST,0,100),
            new UsageReportMath.Record(20261004,12,11,UsageSurface.CHAT_LIST,0,100),
            new UsageReportMath.Record(20261004,13,11,UsageSurface.CHAT_LIST,0,900),
            new UsageReportMath.Record(20261005,12,11,UsageSurface.CHAT_LIST,0,160));
        UsageReportMath.Report report=UsageReportMath.summarize(range,now,rows,new HashMap<>(),new HashMap<>());
        assertEquals(160,report.total); assertEquals(150,report.previous); assertEquals(24,range.bins());
    }
    @Test public void chartCategoriesTopChatsAndMigrationPreserveExactTotalAcrossAccounts() {
        ZonedDateTime now=ZonedDateTime.parse("2026-10-05T12:30:00Z");
        UsageReportMath.Range range=new UsageReportMath.Range(UsageReportMath.Period.WEEK,now.toLocalDate(),Locale.UK);
        List<UsageReportMath.Record> rows=Arrays.asList(
            new UsageReportMath.Record(20261005,11,11,UsageSurface.CHAT_GROUP,-1,10),
            new UsageReportMath.Record(20261005,12,11,UsageSurface.CHAT_GROUP,-2,20),
            new UsageReportMath.Record(20261005,12,22,UsageSurface.CHAT_GROUP,-2,40),
            new UsageReportMath.Record(20261005,12,11,UsageSurface.CALL,0,50),
            new UsageReportMath.Record(20261005,12,33,UsageSurface.CHAT_PRIVATE,0,60));
        Map<UsageReportMath.DialogKey,UsageReportMath.DialogKey> migrations=new HashMap<>();
        migrations.put(new UsageReportMath.DialogKey(11,-1),new UsageReportMath.DialogKey(11,-2));
        UsageReportMath.Report report=UsageReportMath.summarize(range,now,rows,new HashMap<>(),migrations);
        assertEquals(180,report.total); assertEquals(50,report.calls); assertEquals(2,report.chats.size());
        assertEquals(40,report.chats.get(0).seconds); assertEquals(30,report.chats.get(1).seconds);
        long chart=0,categories=0;
        for(long[] series:report.chart.values()) for(long value:series) chart+=value;
        for(long value:report.categories.values()) categories+=value;
        assertEquals(report.total,chart); assertEquals(report.total,categories);
    }
    @Test public void daylightSavingDoesNotChangeCalendarBinsOrTotal() {
        ZonedDateTime now=ZonedDateTime.parse("2026-11-01T12:00:00-05:00[America/New_York]");
        UsageReportMath.Range range=new UsageReportMath.Range(UsageReportMath.Period.WEEK,now.toLocalDate(),Locale.US);
        assertEquals(7,range.bins());
        List<UsageReportMath.Record> rows=Arrays.asList(
            new UsageReportMath.Record(20261101,1,11,UsageSurface.CALL,0,7200),
            new UsageReportMath.Record(20261102,2,11,UsageSurface.CHAT_LIST,0,60));
        UsageReportMath.Report report=UsageReportMath.summarize(range,now,rows,new HashMap<>(),new HashMap<>());
        assertEquals(7260,report.total); assertEquals(7200,report.chart.get(UsageSurface.Category.CALLS)[0]);
    }
    @Test public void percentagesRoundExactlyToOneHundredAndEmptyStaysEmpty() {
        Map<UsageSurface.Category,Long> values=new EnumMap<>(UsageSurface.Category.class);
        assertTrue(UsageReportMath.percentages(values).isEmpty());
        for(UsageSurface.Category c:UsageSurface.Category.values()) values.put(c,1L);
        int sum=0; for(int percent:UsageReportMath.percentages(values).values()) sum+=percent;
        assertEquals(100,sum);
    }
    @Test public void secondaryMetricsAndTopFiftyAreBoundedAndAccountIsolated() {
        ZonedDateTime now=ZonedDateTime.parse("2026-10-05T12:00:00Z");
        UsageReportMath.Range range=new UsageReportMath.Range(UsageReportMath.Period.MONTH,now.toLocalDate(),Locale.US);
        List<UsageReportMath.Record> rows=new ArrayList<>();
        for(int i=1;i<=100;i++) rows.add(new UsageReportMath.Record(20261005,12,i%2+11,UsageSurface.CHAT_PRIVATE,i,i));
        Map<UsageMetrics.Key,UsageMetrics.Daily> metrics=new HashMap<>();
        UsageMetrics.Daily a=new UsageMetrics.Daily(); a.opens=2; a.messages=3; a.longestMillis=40_000;
        UsageMetrics.Daily b=new UsageMetrics.Daily(); b.opens=5; b.messages=7; b.longestMillis=30_000;
        metrics.put(new UsageMetrics.Key(20261005,11),a); metrics.put(new UsageMetrics.Key(20261005,22),b);
        UsageReportMath.Report report=UsageReportMath.summarize(range,now,rows,metrics,new HashMap<>());
        assertEquals(50,report.chats.size()); assertEquals(5050,report.total); assertEquals(7,report.opens); assertEquals(10,report.messages); assertEquals(40_000,report.longestSessionMillis);
    }
}
