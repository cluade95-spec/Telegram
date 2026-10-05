package org.telegram.messenger.usage;

final class UsageTestUtil {
    private UsageTestUtil() {
    }

    static long totalSec(UsageAccountant a) {
        return a.ledger().totalMs() / 1000;
    }

    static long secFor(UsageAccountant a, UsageSurface s) {
        a.tick();
        UsageLedger.Snapshot snap = a.drain();
        long t = 0;
        for (UsageLedger.Row r : snap.rows) {
            if (r.key.surface == s.id) {
                t += r.seconds;
            }
        }
        return t;
    }

    static UsageAccountant started(FakeClock c) {
        UsageAccountant a = new UsageAccountant(c);
        a.onAccount(1);
        a.onScreen(true);
        a.onForeground();
        a.onSurface(UsageSurface.CHAT_LIST, 0);
        return a;
    }
}
