package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UsageAccountantForegroundTest {

    @Test
    public void backgroundStopsCreditingImmediately() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(10);
        a.onBackground();
        c.advanceSec(30);
        a.onInput();
        c.advanceSec(30);
        a.tick();
        assertEquals(10, UsageTestUtil.totalSec(a));
    }

    @Test
    public void screenOffStopsCreditingImmediately() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(10);
        a.onScreen(false);
        c.advanceSec(40);
        a.tick();
        assertEquals(10, UsageTestUtil.totalSec(a));
    }

    @Test
    public void screenBackOnStartsFreshIdleWindow() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onScreen(false);
        c.advanceSec(500);
        a.onScreen(true);
        c.advanceSec(20);
        a.tick();
        assertEquals(20, UsageTestUtil.totalSec(a));
    }

    @Test
    public void pauseWithoutStopSendsNoEvents() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(30); // permission dialog: no events at all
        a.onInput();
        assertEquals(30, UsageTestUtil.totalSec(a));
    }

    @Test
    public void opensAndSessionsAreCounted() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onBackground();
        a.onForeground();
        UsageLedger.Snapshot s = a.drain();
        assertEquals(1, s.daily.size());
        assertEquals(2, s.daily.get(0).opens);
    }

    @Test
    public void longestSessionKeepsTheLongestRunAcrossLongGaps() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(50);
        a.onInput();
        c.advanceSec(50);
        a.onInput(); // run of 100 s
        c.advanceSec(1000);
        a.onInput(); // the idle cap credits 60 s more: run of 160 s
        c.advanceSec(10);
        a.onInput(); // a new run of 10 s after the long gap
        UsageLedger.Snapshot s = a.drain();
        assertEquals(1, s.daily.size());
        assertEquals(160, s.daily.get(0).longestSessionSeconds);
    }
}
