package org.telegram.messenger.usage;

import org.junit.Test;
import java.time.ZoneId;
import static org.junit.Assert.assertEquals;

/** Verifies what event-driven signals can observe during long, silent connected intervals. */
public class UsageSignalBoundaryTest {
    @Test public void silentConnectedIntervalIsBookedOnlyAtNextEvent() {
        long[] elapsed={0};
        UsageClock clock=new UsageClock() {
            public long elapsed() { return elapsed[0]; }
            public long wallMillis() { return 1_780_315_200_000L+elapsed[0]; }
            public ZoneId zone() { return ZoneId.of("UTC"); }
        };
        UsageAccountant accountant=new UsageAccountant(clock);
        accountant.onCall(new SurfaceKey(7,UsageSurface.CALL,0));
        elapsed[0]=2*60*60_000L;
        // A ledger threshold alone cannot observe the open interval without an event.
        assertEquals(0,accountant.pendingMillis());
        accountant.onCall(null);
        assertEquals(2*60*60_000L,accountant.pendingMillis());
    }
}
