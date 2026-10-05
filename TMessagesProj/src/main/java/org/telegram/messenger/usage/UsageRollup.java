package org.telegram.messenger.usage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure maintenance transforms of stored rows (Reference A, A10 and A12). {@link UsageStore} only loads the affected rows,
 * applies one of these and writes the result back in a transaction, so the logic is unit-testable on the JVM.
 */
public final class UsageRollup {

    private UsageRollup() {
    }

    /** Sums hourly rows into one hour = -1 row per (day, account, surface, dialog). Rows already at -1 merge too. */
    public static List<UsageLedger.Row> rollup(List<UsageLedger.Row> rows) {
        LinkedHashMap<UsageLedger.BucketKey, Integer> sum = new LinkedHashMap<>();
        for (UsageLedger.Row r : rows) {
            UsageLedger.BucketKey k = new UsageLedger.BucketKey(r.key.day, -1, r.key.account, r.key.surface, r.key.dialog);
            Integer old = sum.get(k);
            sum.put(k, (old == null ? 0 : old) + r.seconds);
        }
        return toRows(sum);
    }

    public static final class Fold {
        /** Rows to delete (as stored). */
        public final List<UsageLedger.Row> remove = new ArrayList<>();
        /** Rows to add with upsert semantics (seconds are added to an existing row). */
        public final List<UsageLedger.Row> add = new ArrayList<>();
    }

    /**
     * Folds dialog rows whose per-day total (per account and surface) is below minSeconds into dialog = 0. Rows must be
     * the dialog != 0 rows of days past the retention horizon.
     */
    public static Fold fold(List<UsageLedger.Row> rows, int minSeconds) {
        Map<String, Integer> perDialogDay = new HashMap<>();
        for (UsageLedger.Row r : rows) {
            if (r.key.dialog == 0) {
                continue;
            }
            perDialogDay.merge(dialogDayKey(r.key), r.seconds, Integer::sum);
        }
        Fold result = new Fold();
        LinkedHashMap<UsageLedger.BucketKey, Integer> folded = new LinkedHashMap<>();
        for (UsageLedger.Row r : rows) {
            if (r.key.dialog == 0 || perDialogDay.get(dialogDayKey(r.key)) >= minSeconds) {
                continue;
            }
            result.remove.add(r);
            UsageLedger.BucketKey k = new UsageLedger.BucketKey(r.key.day, r.key.hour, r.key.account, r.key.surface, 0);
            folded.merge(k, r.seconds, Integer::sum);
        }
        result.add.addAll(toRows(folded));
        return result;
    }

    /** Account removal: per-dialog rows of the account move to dialog = 0, time stays in totals and categories. */
    public static Fold rekeyAccount(List<UsageLedger.Row> rows, long account) {
        Fold result = new Fold();
        LinkedHashMap<UsageLedger.BucketKey, Integer> moved = new LinkedHashMap<>();
        for (UsageLedger.Row r : rows) {
            if (r.key.account != account || r.key.dialog == 0) {
                continue;
            }
            result.remove.add(r);
            moved.merge(new UsageLedger.BucketKey(r.key.day, r.key.hour, r.key.account, r.key.surface, 0), r.seconds, Integer::sum);
        }
        result.add.addAll(toRows(moved));
        return result;
    }

    private static String dialogDayKey(UsageLedger.BucketKey k) {
        return k.day + ":" + k.account + ":" + k.surface + ":" + k.dialog;
    }

    private static List<UsageLedger.Row> toRows(Map<UsageLedger.BucketKey, Integer> m) {
        ArrayList<UsageLedger.Row> out = new ArrayList<>(m.size());
        for (Map.Entry<UsageLedger.BucketKey, Integer> e : m.entrySet()) {
            out.add(new UsageLedger.Row(e.getKey(), e.getValue()));
        }
        return out;
    }
}
