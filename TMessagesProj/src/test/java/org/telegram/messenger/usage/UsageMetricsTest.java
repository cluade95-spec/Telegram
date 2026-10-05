package org.telegram.messenger.usage;

import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import static org.junit.Assert.*;

public class UsageMetricsTest {
    private final ZoneId zone=ZoneId.of("UTC");
    private final SurfaceKey chat=new SurfaceKey(11,UsageSurface.CHAT_PRIVATE,123);
    private final long wall=Instant.parse("2026-10-05T12:00:00Z").toEpochMilli();
    private UsageMetrics.Daily row(Map<UsageMetrics.Key,UsageMetrics.Daily> rows,int day,long uid) {
        return rows.get(new UsageMetrics.Key(day,uid));
    }
    @Test public void shortIdleGapsDoNotBecomeEarnedSessionTime() {
        UsageMetrics m=new UsageMetrics();
        m.opened(11,wall,zone);
        m.credited(chat,0,wall,60_000,zone);
        m.credited(chat,360_000,wall+360_000,10_000,zone); // exactly five-minute inactive gap
        UsageMetrics.Daily d=row(m.drain(),20261005,11);
        assertEquals(1,d.opens); assertEquals(1,d.sessions); assertEquals(70_000,d.longestMillis);
        m.credited(chat,670_001,wall+670_001,20_000,zone);
        d=row(m.drain(),20261005,11);
        assertEquals(1,d.sessions); assertEquals(20_000,d.longestMillis);
    }
    @Test public void flushDoesNotRestartSessionButBackgroundDoes() {
        UsageMetrics m=new UsageMetrics();
        m.credited(chat,0,wall,50_000,zone); m.drain();
        m.credited(chat,50_000,wall+50_000,50_000,zone);
        UsageMetrics.Daily d=row(m.drain(),20261005,11);
        assertEquals(0,d.sessions); assertEquals(100_000,d.longestMillis);
        m.endSession(); m.credited(chat,100_000,wall+100_000,1000,zone);
        assertEquals(1,row(m.drain(),20261005,11).sessions);
    }
    @Test public void dayBoundaryAndAccountSwitchStaySeparate() {
        UsageMetrics m=new UsageMetrics();
        long midnight=Instant.parse("2026-10-05T23:59:30Z").toEpochMilli();
        m.credited(chat,0,midnight,60_000,zone);
        Map<UsageMetrics.Key,UsageMetrics.Daily> rows=m.drain();
        assertEquals(30_000,row(rows,20261005,11).longestMillis);
        assertEquals(30_000,row(rows,20261006,11).longestMillis);
        assertEquals(1,row(rows,20261005,11).sessions);
        assertEquals(0,row(rows,20261006,11).sessions);
        m.credited(new SurfaceKey(22,UsageSurface.CALL,0),60_000,midnight+60_000,90_000,zone);
        assertEquals(90_000,row(m.drain(),20261006,22).longestMillis);
    }
    @Test public void loggingOutAnotherAccountDoesNotEndTheCurrentSession() {
        UsageMetrics m=new UsageMetrics();
        m.credited(chat,0,wall,30_000,zone); m.drain();
        m.onAccountRemoved(22);
        m.credited(chat,30_000,wall+30_000,30_000,zone);
        UsageMetrics.Daily d=row(m.drain(),20261005,11);
        assertEquals(0,d.sessions); assertEquals(60_000,d.longestMillis);
        m.onAccountRemoved(11);
        m.credited(chat,60_000,wall+60_000,30_000,zone);
        d=row(m.drain(),20261005,11);
        assertEquals(1,d.sessions); assertEquals(30_000,d.longestMillis);
    }
    @Test public void resetDiscardsPendingAndEndsSession() {
        UsageMetrics m=new UsageMetrics(); m.credited(chat,0,wall,1000,zone); m.reset();
        assertTrue(m.drain().isEmpty());
        m.credited(chat,1000,wall+1000,1000,zone);
        assertEquals(1000,row(m.drain(),20261005,11).longestMillis);
    }
}
