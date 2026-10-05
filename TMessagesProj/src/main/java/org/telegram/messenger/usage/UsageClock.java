package org.telegram.messenger.usage;

import java.time.ZoneId;

public interface UsageClock {
    long elapsed();
    long wallMillis();
    ZoneId zone();
}
