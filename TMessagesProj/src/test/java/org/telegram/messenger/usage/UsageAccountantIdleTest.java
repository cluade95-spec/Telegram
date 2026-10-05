package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsageAccountantIdleTest {

    @Test
    public void twoMinutesScrollingThenTwentyMinutesIdleIsThreeMinutes() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        for (int i = 0; i < 24; i++) { // input every 5 s for 2 minutes
            c.advanceSec(5);
            a.onInput();
        }
        c.advanceSec(20 * 60);
        a.onInput();
        assertEquals(180, UsageTestUtil.totalSec(a));
    }

    @Test
    public void gapOf59SecondsIsFullyCounted() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(59);
        a.onInput();
        assertEquals(59, UsageTestUtil.totalSec(a));
    }

    @Test
    public void gapOf61SecondsCounts60() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(61);
        a.onInput();
        assertEquals(60, UsageTestUtil.totalSec(a));
    }

    @Test
    public void idleTimeIsNotCreditedByLaterNavigation() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(30);
        a.onSurface(UsageSurface.SETTINGS, 0);
        c.advanceSec(600); // no input
        a.onSurface(UsageSurface.CHAT_LIST, 0);
        assertEquals(60, UsageTestUtil.totalSec(a));
    }

    @Test
    public void continuousInputIsSettledInChunksSoProcessDeathLosesLittle() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        for (int i = 0; i < 60; i++) { // 30 minutes, input every 30 s, no navigation
            c.advanceSec(30);
            a.onInput();
        }
        // everything except the last open chunk (< flush threshold) is already in the ledger
        long inLedger = a.ledger().totalMs();
        assertTrue(inLedger >= 25 * 60_000L);
        a.tick();
        assertEquals(30 * 60, UsageTestUtil.totalSec(a));
    }
}
