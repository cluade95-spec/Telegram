package org.telegram.messenger.usage;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.messenger.ApplicationLoader;
import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real bundled SQLite adapter/device checks; intentionally separate from executable host SQL tests. */
@RunWith(AndroidJUnit4.class)
public class UsageStoreTest {
    private void await(CountDownLatch done) throws Exception { assertTrue("usageQueue callback",done.await(10,TimeUnit.SECONDS)); }
    private UsageStore.Report query(UsageStore store) throws Exception {
        CountDownLatch done=new CountDownLatch(1); AtomicReference<UsageStore.Report> result=new AtomicReference<>();
        store.query(20000101,20991231,r->{result.set(r);done.countDown();}); await(done); assertNotNull(result.get()); return result.get();
    }
    private void close(UsageStore store) throws Exception { CountDownLatch done=new CountDownLatch(1);store.closeForTests(done::countDown);await(done); }
    private void credit(UsageStore store,int day,int hour,long uid,long dialog,long ms,long removed) {
        Map<UsageLedger.BucketKey,Long> rows=new HashMap<>();
        rows.put(new UsageLedger.BucketKey(day,hour,new SurfaceKey(uid,UsageSurface.CHAT_PRIVATE,dialog)),ms);
        store.flush(rows,new HashMap<>(),new ArrayList<>(),removed);
    }
    @Test public void upsertsFractionsRollupLogoutResetAndDurableSendDedup() throws Exception {
        File file=new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),"usage-test/"+UUID.randomUUID()+".db");
        UsageStore store=new UsageStore(file);
        int today=UsageMetrics.day(LocalDate.now());
        credit(store,today,12,11,123,600,0); credit(store,today,12,11,123,600,0);
        UsageStore.Report report=query(store); assertEquals(1,report.rows.size()); assertEquals(1,report.rows.get(0).seconds);
        store.confirmed(11,123,999,false,System.currentTimeMillis(),ZoneId.systemDefault());
        report=query(store); assertEquals(1,report.daily.get(new UsageMetrics.Key(today,11)).messages);
        store.confirmed(11,123,888,false,System.currentTimeMillis()-86_400_000L,ZoneId.systemDefault());
        int yesterday=UsageMetrics.day(LocalDate.now().minusDays(1));
        assertEquals(1,query(store).daily.get(new UsageMetrics.Key(yesterday,11)).messages);
        close(store); store=new UsageStore(file);
        store.confirmed(11,123,999,false,System.currentTimeMillis(),ZoneId.systemDefault());
        assertEquals(1,query(store).daily.get(new UsageMetrics.Key(today,11)).messages);
        credit(store,today,12,22,123,2000,0); credit(store,today,12,11,456,2000,11);
        report=query(store);
        for(UsageStore.Row r:report.rows) if(r.account==11) assertEquals(0,r.dialog); else assertEquals(123,r.dialog);
        int old=UsageMetrics.day(LocalDate.now().minusDays(100));
        credit(store,old,1,11,123,30_000,0);
        // The first flush has already rolled up today; reopening preserves its once-per-day marker.
        close(store); store=new UsageStore(file);
        assertEquals(3,query(store).rows.size());
        CountDownLatch done=new CountDownLatch(1); AtomicReference<Boolean> reset=new AtomicReference<>();
        store.reset(ok->{reset.set(ok);done.countDown();}); await(done); assertEquals(Boolean.TRUE,reset.get());
        assertTrue(query(store).rows.isEmpty()); assertTrue(query(store).daily.isEmpty()); close(store);
    }
    @Test public void firstFlushRollsUpOldHoursAndQueryFollowsQueuedWrites() throws Exception {
        File file=new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),"usage-test/"+UUID.randomUUID()+".db");
        UsageStore store=new UsageStore(file);
        int old=UsageMetrics.day(LocalDate.now().minusDays(100));
        credit(store,old,1,11,123,30_000,0);
        UsageStore.Report report=query(store); assertEquals(1,report.rows.size()); assertEquals(-1,report.rows.get(0).hour);
        assertEquals(30,report.rows.get(0).seconds); close(store);
    }
}
