package org.telegram.messenger.usage;
import org.junit.Test;
import java.util.Map;
import java.util.HashMap;
import static org.junit.Assert.*;
public class UsageAccountIsolationTest {
    @Test public void removalFoldsOnlyRemovedAccountsAndKeepsTotals() {
        Map<UsageLedger.BucketKey,Long> rows=new HashMap<>();
        rows.put(new UsageLedger.BucketKey(20261005,12,new SurfaceKey(11,UsageSurface.CHAT_PRIVATE,123)),2000L);
        rows.put(new UsageLedger.BucketKey(20261005,12,new SurfaceKey(11,UsageSurface.CHAT_PRIVATE,0)),3000L);
        rows.put(new UsageLedger.BucketKey(20261005,12,new SurfaceKey(22,UsageSurface.CHAT_PRIVATE,123)),7000L);
        Map<UsageLedger.BucketKey,Long> result=UsageRollup.anonymize(rows,11);
        assertEquals(2,result.size());
        long total=0;
        for(Map.Entry<UsageLedger.BucketKey,Long> r:result.entrySet()) {
            total+=r.getValue();
            if(r.getKey().surface.accountUserId==11) { assertEquals(0,r.getKey().surface.dialogId); assertEquals(5000,r.getValue().longValue()); }
            else { assertEquals(123,r.getKey().surface.dialogId); assertEquals(7000,r.getValue().longValue()); }
        }
        assertEquals(12000,total); assertEquals(3,rows.size());
    }
}
