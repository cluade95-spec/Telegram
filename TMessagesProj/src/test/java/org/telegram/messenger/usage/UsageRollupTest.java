package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class UsageRollupTest {

    private static UsageLedger.Row row(int day, int hour, long account, UsageSurface s, long dialog, int sec) {
        return new UsageLedger.Row(new UsageLedger.BucketKey(day, hour, account, s.id, dialog), sec);
    }

    private static int total(List<UsageLedger.Row> rows) {
        int t = 0;
        for (UsageLedger.Row r : rows) {
            t += r.seconds;
        }
        return t;
    }

    @Test
    public void rollupSumsHoursPerDayAccountSurfaceDialog() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20240101, 8, 1, UsageSurface.CHAT_PRIVATE, 5, 100));
        rows.add(row(20240101, 9, 1, UsageSurface.CHAT_PRIVATE, 5, 50));
        rows.add(row(20240101, 9, 1, UsageSurface.CHAT_LIST, 0, 30));
        rows.add(row(20240101, 9, 2, UsageSurface.CHAT_LIST, 0, 20));
        rows.add(row(20240102, 9, 1, UsageSurface.CHAT_PRIVATE, 5, 10));
        List<UsageLedger.Row> out = UsageRollup.rollup(rows);
        assertEquals(4, out.size());
        assertEquals(total(rows), total(out));
        for (UsageLedger.Row r : out) {
            assertEquals(-1, r.key.hour);
            if (r.key.day == 20240101 && r.key.account == 1 && r.key.surface == UsageSurface.CHAT_PRIVATE.id) {
                assertEquals(150, r.seconds);
            }
        }
    }

    @Test
    public void rollupIsIdempotent() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20240101, 8, 1, UsageSurface.CHAT_PRIVATE, 5, 100));
        rows.add(row(20240101, 9, 1, UsageSurface.CHAT_PRIVATE, 5, 50));
        List<UsageLedger.Row> once = UsageRollup.rollup(rows);
        List<UsageLedger.Row> twice = UsageRollup.rollup(once);
        assertEquals(once.size(), twice.size());
        assertEquals(total(once), total(twice));
    }

    @Test
    public void foldMovesSmallDialogsToZeroAndKeepsTotals() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20220101, -1, 1, UsageSurface.CHAT_PRIVATE, 5, 59));
        rows.add(row(20220101, -1, 1, UsageSurface.CHAT_PRIVATE, 6, 10));
        rows.add(row(20220101, -1, 1, UsageSurface.CHAT_PRIVATE, 7, 60)); // exactly at the limit stays
        rows.add(row(20220102, -1, 1, UsageSurface.CHAT_PRIVATE, 5, 500)); // other day: stays
        UsageRollup.Fold f = UsageRollup.fold(rows, UsagePolicy.FOLD_BELOW_SECONDS);
        assertEquals(2, f.remove.size());
        assertEquals(1, f.add.size());
        assertEquals(0, f.add.get(0).key.dialog);
        assertEquals(69, f.add.get(0).seconds);
        assertEquals(total(f.remove), total(f.add));
    }

    @Test
    public void foldSumsAHourlyDialogAcrossRowsBeforeComparing() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20220101, 3, 1, UsageSurface.CHAT_GROUP, -9, 40));
        rows.add(row(20220101, 4, 1, UsageSurface.CHAT_GROUP, -9, 40)); // 80 s that day: stays
        assertEquals(0, UsageRollup.fold(rows, 60).remove.size());
    }

    @Test
    public void rekeyOnlyTouchesTheRemovedAccountAndKeepsTime() {
        List<UsageLedger.Row> rows = new ArrayList<>();
        rows.add(row(20240101, 8, 1, UsageSurface.CHAT_PRIVATE, 5, 100));
        rows.add(row(20240101, 8, 1, UsageSurface.CHAT_PRIVATE, 6, 20));
        rows.add(row(20240101, 8, 1, UsageSurface.CHAT_LIST, 0, 7));
        rows.add(row(20240101, 8, 2, UsageSurface.CHAT_PRIVATE, 5, 33));
        UsageRollup.Fold f = UsageRollup.rekeyAccount(rows, 1);
        assertEquals(2, f.remove.size());
        assertEquals(1, f.add.size());
        assertEquals(120, f.add.get(0).seconds);
        assertEquals(0, f.add.get(0).key.dialog);
        assertEquals(1, f.add.get(0).key.account);
    }
}
