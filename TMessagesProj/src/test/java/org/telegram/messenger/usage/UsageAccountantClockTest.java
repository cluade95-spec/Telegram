package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.TimeZone;

public class UsageAccountantClockTest {

    private static long wall(String zone, int y, int mo, int d, int h, int mi) {
        java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getTimeZone(zone));
        cal.clear();
        cal.set(y, mo - 1, d, h, mi, 0);
        return cal.getTimeInMillis();
    }

    private static long sec(UsageLedger.Snapshot s, int day, int hour) {
        long t = 0;
        for (UsageLedger.Row r : s.rows) {
            if (r.key.day == day && r.key.hour == hour) {
                t += r.seconds;
            }
        }
        return t;
    }

    @Test
    public void creditSplitsAtHourBoundaries() {
        FakeClock c = new FakeClock(wall("UTC", 2024, 3, 10, 12, 59) + 30_000);
        UsageAccountant a = UsageTestUtil.started(c);
        a.onPassive(true);
        c.advanceSec(90);
        a.onPassive(false);
        UsageLedger.Snapshot s = a.drain();
        assertEquals(30, sec(s, 20240310, 12));
        assertEquals(60, sec(s, 20240310, 13));
    }

    @Test
    public void creditSplitsAtMidnight() {
        FakeClock c = new FakeClock(wall("UTC", 2024, 3, 10, 23, 59) + 20_000);
        UsageAccountant a = UsageTestUtil.started(c);
        a.onPassive(true);
        c.advanceSec(100);
        a.onPassive(false);
        UsageLedger.Snapshot s = a.drain();
        assertEquals(40, sec(s, 20240310, 23));
        assertEquals(60, sec(s, 20240311, 0));
    }

    @Test
    public void creditFollowsTheLocalZone() {
        FakeClock c = new FakeClock(wall("UTC", 2024, 3, 10, 23, 30));
        c.zone = TimeZone.getTimeZone("Asia/Tokyo");
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(30);
        a.onInput();
        assertEquals(30, sec(a.drain(), 20240311, 8));
    }

    @Test
    public void dstChangeNeverDoubleCountsOrGoesNegative() {
        // America/New_York spring forward 2024-03-10 02:00 -> 03:00
        FakeClock c = new FakeClock(wall("America/New_York", 2024, 3, 10, 1, 30));
        c.zone = TimeZone.getTimeZone("America/New_York");
        UsageAccountant a = UsageTestUtil.started(c);
        a.onPassive(true);
        c.advanceSec(3600);
        a.onPassive(false);
        UsageLedger.Snapshot s = a.drain();
        long total = 0;
        for (UsageLedger.Row r : s.rows) {
            assertEquals(true, r.seconds > 0);
            total += r.seconds;
        }
        assertEquals(3600, total);
        assertEquals(1800, sec(s, 20240310, 1));
        assertEquals(1800, sec(s, 20240310, 3));
    }

    @Test
    public void wallClockJumpOnlyMovesBooking() {
        FakeClock c = new FakeClock(wall("UTC", 2024, 3, 10, 12, 0));
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(10);
        a.onInput();
        c.wall -= 3 * 86_400_000L; // user sets the clock back three days
        c.elapsed += 10_000;
        a.onInput();
        UsageLedger.Snapshot s = a.drain();
        assertEquals(10, sec(s, 20240310, 12));
        assertEquals(10, sec(s, 20240307, 12));
    }

    @Test
    public void timeZoneChangeMovesFutureCreditsOnly() {
        FakeClock c = new FakeClock(wall("UTC", 2024, 3, 10, 12, 0));
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(10);
        a.onInput();
        c.zone = TimeZone.getTimeZone("Asia/Tokyo");
        c.advanceSec(10);
        a.onInput();
        UsageLedger.Snapshot s = a.drain();
        assertEquals(10, sec(s, 20240310, 12));
        assertEquals(10, sec(s, 20240310, 21));
    }

    @Test
    public void daysArithmeticRoundTrips() {
        assertEquals(20240229, UsageDays.addDays(20240228, 1));
        assertEquals(20240301, UsageDays.addDays(20240229, 1));
        assertEquals(20231231, UsageDays.addDays(20240101, -1));
        assertEquals(0, UsageDays.dayOfWeekMonday0(20240101)); // a Monday
        assertEquals(6, UsageDays.dayOfWeekMonday0(20240107));
    }
}
