package org.telegram.messenger.usage;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure calendar/report math. Stored seconds and categories remain an exact partition. */
public final class UsageReportMath {
    public enum Period { TODAY, WEEK, MONTH }
    public static final class Range {
        public final LocalDate start, end, previousStart, previousEnd;
        public final Period period;
        public Range(Period period, LocalDate today, Locale locale) {
            this.period=period;
            if(period==Period.TODAY) {
                start=end=today; previousStart=previousEnd=today.minusDays(1);
            } else if(period==Period.WEEK) {
                int offset=Math.floorMod(today.getDayOfWeek().getValue()-WeekFields.of(locale).getFirstDayOfWeek().getValue(),7);
                start=today.minusDays(offset); end=start.plusDays(6); previousStart=start.minusDays(7); previousEnd=start.minusDays(1);
            } else {
                start=today.withDayOfMonth(1); end=start.plusMonths(1).minusDays(1);
                previousStart=start.minusMonths(1); previousEnd=start.minusDays(1);
            }
        }
        public int firstDay() { return UsageMetrics.day(previousStart); }
        public int lastDay() { return UsageMetrics.day(end); }
        public int bins() { return period==Period.TODAY?24:(int)(end.toEpochDay()-start.toEpochDay()+1); }
    }
    public static final class Record {
        public final int day,hour;
        public final long account,dialog,seconds;
        public final UsageSurface surface;
        public Record(int day,int hour,long account,UsageSurface surface,long dialog,long seconds) {
            this.day=day; this.hour=hour; this.account=account; this.surface=surface; this.dialog=dialog; this.seconds=seconds;
        }
    }
    public static final class DialogKey {
        public final long account,dialog;
        public DialogKey(long account,long dialog) { this.account=account; this.dialog=dialog; }
        @Override public boolean equals(Object o) { return o instanceof DialogKey && account==((DialogKey)o).account && dialog==((DialogKey)o).dialog; }
        @Override public int hashCode() { return 31*Long.hashCode(account)+Long.hashCode(dialog); }
    }
    public static final class Chat {
        public final DialogKey key;
        public long seconds;
        private Chat(DialogKey key,long seconds) { this.key=key; this.seconds=seconds; }
    }
    public static final class Report {
        public long total,previous,opens,messages,longestSessionMillis,calls;
        public final EnumMap<UsageSurface.Category,Long> categories=new EnumMap<>(UsageSurface.Category.class);
        public final EnumMap<UsageSurface.Category,long[]> chart=new EnumMap<>(UsageSurface.Category.class);
        public final List<Chat> chats=new ArrayList<>();
        public final EnumMap<UsageSurface.Category,Integer> percentages=new EnumMap<>(UsageSurface.Category.class);
    }
    private UsageReportMath() { }
    public static UsageSurface surface(int id) {
        for(UsageSurface s:UsageSurface.values()) if(s.id==id) return s;
        return UsageSurface.OTHER;
    }
    public static LocalDate date(int day) { return LocalDate.of(day/10000,day/100%100,day%100); }
    public static Report summarize(Range range,ZonedDateTime now,List<Record> rows,
                                   Map<UsageMetrics.Key,UsageMetrics.Daily> daily,Map<DialogKey,DialogKey> migrations) {
        Report r=new Report(); Map<DialogKey,Long> chats=new HashMap<>();
        int first=UsageMetrics.day(range.start),last=UsageMetrics.day(range.end);
        int previousFirst=UsageMetrics.day(range.previousStart),previousLast=UsageMetrics.day(range.previousEnd);
        double previous=0;
        for(Record row:rows) {
            if(row.seconds<=0) continue;
            if(row.day>=previousFirst && row.day<=previousLast) {
                double weight=1;
                if(range.period==Period.TODAY && row.hour>=0) {
                    weight=row.hour<now.getHour()?1:row.hour>now.getHour()?0:(now.getMinute()*60+now.getSecond())/3600.0;
                }
                previous+=row.seconds*weight;
            }
            if(row.day<first || row.day>last) continue;
            r.total+=row.seconds;
            r.categories.merge(row.surface.category,row.seconds,Long::sum);
            long[] series=r.chart.computeIfAbsent(row.surface.category,k->new long[range.bins()]);
            int bin=range.period==Period.TODAY?row.hour:(int)(date(row.day).toEpochDay()-range.start.toEpochDay());
            if(bin>=0 && bin<series.length) series[bin]+=row.seconds;
            if(row.dialog!=0) {
                DialogKey key=new DialogKey(row.account,row.dialog);
                key=migrations.getOrDefault(key,key);
                chats.merge(key,row.seconds,Long::sum);
            }
        }
        r.previous=Math.round(previous);
        for(Map.Entry<UsageMetrics.Key,UsageMetrics.Daily> entry:daily.entrySet()) if(entry.getKey().day>=first && entry.getKey().day<=last) {
            UsageMetrics.Daily d=entry.getValue(); r.opens+=d.opens; r.messages+=d.messages;
            r.longestSessionMillis=Math.max(r.longestSessionMillis,d.longestMillis);
        }
        r.calls=r.categories.getOrDefault(UsageSurface.Category.CALLS,0L);
        for(Map.Entry<DialogKey,Long> entry:chats.entrySet()) r.chats.add(new Chat(entry.getKey(),entry.getValue()));
        r.chats.sort(Comparator.<Chat>comparingLong(c->c.seconds).reversed().thenComparingLong(c->c.key.account).thenComparingLong(c->c.key.dialog));
        if(r.chats.size()>50) r.chats.subList(50,r.chats.size()).clear();
        r.percentages.putAll(percentages(r.categories));
        return r;
    }
    /** Largest-remainder rounding gives exactly 100% for every nonempty breakdown. */
    public static EnumMap<UsageSurface.Category,Integer> percentages(Map<UsageSurface.Category,Long> values) {
        EnumMap<UsageSurface.Category,Integer> result=new EnumMap<>(UsageSurface.Category.class);
        long total=0; for(long value:values.values()) total+=value;
        if(total<=0) return result;
        final long denominator=total;
        List<UsageSurface.Category> order=new ArrayList<>(); int sum=0;
        for(UsageSurface.Category category:UsageSurface.Category.values()) {
            long value=values.getOrDefault(category,0L); if(value<=0) continue;
            int percent=(int)(value*100/total); result.put(category,percent); sum+=percent; order.add(category);
        }
        order.sort((a,b)->Long.compare(values.get(b)*100%denominator,values.get(a)*100%denominator));
        for(int i=0;i<100-sum;i++) { UsageSurface.Category category=order.get(i); result.put(category,result.get(category)+1); }
        return result;
    }
}
