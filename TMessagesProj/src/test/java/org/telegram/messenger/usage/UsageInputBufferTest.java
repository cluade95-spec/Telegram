package org.telegram.messenger.usage;

import org.junit.Test;
import java.time.ZoneId;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UsageInputBufferTest {
    private static final class Clock implements UsageClock {
        long now;
        long event = -1;
        public long elapsed() { return event < 0 ? now : event; }
        public long wallMillis() { return 1_780_315_200_000L + elapsed(); }
        public ZoneId zone() { return ZoneId.of("UTC"); }
    }

    @Test public void denseInputPreservesTimeWithRareDelivery() {
        Clock clock = new Clock();
        UsageAccountant accountant = new UsageAccountant(clock);
        accountant.onScreen(true);
        accountant.onSurface(new SurfaceKey(7,UsageSurface.CHAT_LIST,0));
        accountant.onForeground(true);
        int[] deliveries={0};
        UsageInputBuffer input=new UsageInputBuffer(t -> {
            clock.event=t;
            accountant.onInput();
            clock.event=-1;
            deliveries[0]++;
        });
        for (clock.now=0;clock.now<=120_000;clock.now+=10) input.record(clock.now);
        input.deliver();
        clock.now=120_000+20*60_000;
        long sum=0;
        for(long value:accountant.drain().values()) sum+=value;
        assertEquals(180_000,sum);
        assertTrue(deliveries[0]<20);
    }

    @Test public void sparseInputPreservesIdleGapsAndNavigationBoundary() {
        Clock clock=new Clock();
        UsageAccountant accountant=new UsageAccountant(clock);
        SurfaceKey list=new SurfaceKey(7,UsageSurface.CHAT_LIST,0);
        SurfaceKey chat=new SurfaceKey(7,UsageSurface.CHAT_PRIVATE,8);
        accountant.onScreen(true);
        accountant.onSurface(list);
        accountant.onForeground(true);
        UsageInputBuffer input=new UsageInputBuffer(t -> { clock.event=t;accountant.onInput();clock.event=-1; });
        input.record(0);
        clock.now=10_000;input.record(clock.now);
        clock.now=15_000;input.deliver();accountant.onSurface(chat);
        clock.now=300_000;input.record(clock.now);
        clock.now=310_000;
        Map<UsageLedger.BucketKey,Long> rows=accountant.drain();
        long listTime=0,chatTime=0;
        for(Map.Entry<UsageLedger.BucketKey,Long> row:rows.entrySet()) {
            if(row.getKey().surface.equals(list)) listTime+=row.getValue();
            else chatTime+=row.getValue();
        }
        assertEquals(15_000,listTime);
        assertEquals(65_000,chatTime);
    }
}
