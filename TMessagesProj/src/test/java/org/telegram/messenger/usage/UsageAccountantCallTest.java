package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UsageAccountantCallTest {

    @Test
    public void callIsExclusiveAndCountsWithScreenOff() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onSurface(UsageSurface.CHAT_PRIVATE, 7);
        c.advanceSec(10);
        a.onCall(true);
        a.onScreen(false); // proximity sensor
        c.advanceSec(180);
        a.onCall(false);
        c.advanceSec(5);
        a.onScreen(true);
        c.advanceSec(7);
        a.tick();
        UsageLedger.Snapshot s = a.drain();
        long call = 0, chat = 0, total = 0;
        for (UsageLedger.Row r : s.rows) {
            total += r.seconds;
            if (r.key.surface == UsageSurface.CALL.id) {
                call += r.seconds;
                assertEquals(0, r.key.dialog);
            }
            if (r.key.surface == UsageSurface.CHAT_PRIVATE.id) {
                chat += r.seconds;
            }
        }
        assertEquals(180, call);
        assertEquals(10 + 7, chat);
        assertEquals(180 + 17, total);
    }

    @Test
    public void callCountsWhileIdleAndInBackground() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onCall(true);
        a.onBackground();
        c.advanceSec(1200);
        a.onCall(false);
        assertEquals(1200, UsageTestUtil.totalSec(a));
    }
}
