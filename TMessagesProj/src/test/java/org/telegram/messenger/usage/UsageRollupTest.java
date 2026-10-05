package org.telegram.messenger.usage;
import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;
public class UsageRollupTest {
    @Test public void retentionUsesCalendarDaysAndYears() {
        assertEquals(20260707,UsageRollup.hourlyCutoff(LocalDate.of(2026,10,5)));
        assertEquals(20241005,UsageRollup.dialogCutoff(LocalDate.of(2026,10,5)));
        assertEquals(20220228,UsageRollup.dialogCutoff(LocalDate.of(2024,2,29)));
    }
}
