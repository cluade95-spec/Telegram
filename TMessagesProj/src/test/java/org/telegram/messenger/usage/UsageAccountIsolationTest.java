package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UsageAccountIsolationTest {

    private static long sec(UsageLedger.Snapshot s, long account) {
        long t = 0;
        for (UsageLedger.Row r : s.rows) {
            if (r.key.account == account) {
                t += r.seconds;
            }
        }
        return t;
    }

    @Test
    public void creditsAreKeyedByTheAccountAtThatMoment() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onSurface(UsageSurface.CHAT_PRIVATE, 5);
        c.advanceSec(20);
        a.onAccount(2); // account switch closes the segment
        c.advanceSec(30);
        a.onInput();
        UsageLedger.Snapshot s = a.drain();
        assertEquals(20, sec(s, 1));
        assertEquals(30, sec(s, 2));
    }

    @Test
    public void messagesSentBelongToTheConfirmingAccount() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onMessagesSent(2, 3);
        a.onMessagesSent(1, 1);
        UsageLedger.Snapshot s = a.drain();
        int one = 0, two = 0;
        for (UsageLedger.DailyRow d : s.daily) {
            if (d.key.account == 1) {
                one += d.messagesSent;
            }
            if (d.key.account == 2) {
                two += d.messagesSent;
            }
        }
        assertEquals(1, one);
        assertEquals(3, two);
    }

    @Test
    public void drainKeepsSubSecondRemaindersSoNothingIsLost() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        long total = 0;
        for (int i = 0; i < 10; i++) {
            c.advance(700);
            a.onSurface(UsageSurface.CHAT_LIST, 0);
            for (UsageLedger.Row r : a.drain().rows) {
                total += r.seconds;
            }
        }
        assertEquals(7, total); // 10 x 0.7 s, remainders carried between drains
    }
}
