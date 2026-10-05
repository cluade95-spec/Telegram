package org.telegram.messenger.usage;

import java.util.TimeZone;

final class FakeClock implements UsageClock {
    long elapsed = 1_000_000L;
    long wall;
    TimeZone zone = TimeZone.getTimeZone("UTC");

    FakeClock(long wall) {
        this.wall = wall;
    }

    /** 2024-03-10 12:00:00 UTC. */
    FakeClock() {
        this(1_710_072_000_000L);
    }

    void advance(long ms) {
        elapsed += ms;
        wall += ms;
    }

    void advanceSec(long s) {
        advance(s * 1000);
    }

    public long elapsed() {
        return elapsed;
    }

    public long wallMillis() {
        return wall;
    }

    public TimeZone zone() {
        return zone;
    }
}
