package org.telegram.messenger.usage;

import java.util.TimeZone;

/**
 * Time source of the accountant. Durations come from {@link #elapsed()} (monotonic); {@link #wallMillis()} and
 * {@link #zone()} only decide which local day and hour a credit is booked under.
 */
public interface UsageClock {

    /** Monotonic milliseconds (SystemClock.elapsedRealtime). */
    long elapsed();

    /** Wall clock milliseconds (System.currentTimeMillis). */
    long wallMillis();

    TimeZone zone();
}
