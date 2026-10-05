package org.telegram.messenger.usage;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Pure retention/anonymization rules, shared by tests and the persistence adapter. */
public final class UsageRollup {
    private UsageRollup() { }
    public static int hourlyCutoff(LocalDate today) { return UsageMetrics.day(today.minusDays(90)); }
    public static int dialogCutoff(LocalDate today) { return UsageMetrics.day(today.minusYears(2)); }
    public static Map<UsageLedger.BucketKey, Long> anonymize(Map<UsageLedger.BucketKey, Long> rows, long account) {
        Map<UsageLedger.BucketKey, Long> result = new HashMap<>();
        for (Map.Entry<UsageLedger.BucketKey, Long> row : rows.entrySet()) {
            UsageLedger.BucketKey k = row.getKey();
            if (k.surface.accountUserId == account) k = new UsageLedger.BucketKey(k.day, k.hour, new SurfaceKey(account, k.surface.surface, 0));
            result.merge(k, row.getValue(), Long::sum);
        }
        return result;
    }
}
