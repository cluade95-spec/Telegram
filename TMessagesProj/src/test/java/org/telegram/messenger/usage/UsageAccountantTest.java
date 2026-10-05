package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Random;

public class UsageAccountantTest {
    private static final class Clock implements UsageClock {
        long elapsed;
        long wall;
        ZoneId zone = ZoneId.of("UTC");
        Clock(String instant) { wall = Instant.parse(instant).toEpochMilli(); }
        public long elapsed() { return elapsed; }
        public long wallMillis() { return wall; }
        public ZoneId zone() { return zone; }
        void advance(long ms) { elapsed += ms; wall += ms; }
    }

    private static final SurfaceKey LIST = new SurfaceKey(11, UsageSurface.CHAT_LIST, 0);
    private static final SurfaceKey CHAT = new SurfaceKey(11, UsageSurface.CHAT_CHANNEL, -8);
    private static final SurfaceKey CALL = new SurfaceKey(11, UsageSurface.CALL, 0);
    private static final SurfaceKey OTHER_ACCOUNT = new SurfaceKey(22, UsageSurface.CHAT_LIST, 0);

    private UsageAccountant open(Clock clock) {
        UsageAccountant accountant = new UsageAccountant(clock);
        accountant.onSurface(LIST);
        accountant.onScreen(true);
        accountant.onForeground(true);
        return accountant;
    }

    private long total(Map<UsageLedger.BucketKey, Long> rows) {
        long sum = 0;
        for (long value : rows.values()) sum += value;
        return sum;
    }

    private long surface(Map<UsageLedger.BucketKey, Long> rows, SurfaceKey key) {
        long sum = 0;
        for (Map.Entry<UsageLedger.BucketKey, Long> row : rows.entrySet()) {
            if (row.getKey().surface.equals(key)) sum += row.getValue();
        }
        return sum;
    }

    @Test
    public void idleIsCappedAfterScrollingAndLongSilence() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        clock.advance(60_000);
        accountant.onInput();
        clock.advance(60_000);
        accountant.onInput();
        clock.advance(20 * 60_000);
        Map<UsageLedger.BucketKey, Long> rows = accountant.drain();
        assertEquals(180_000, total(rows));
    }

    @Test
    public void idleBoundaryAndRestartAreExact() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        clock.advance(59_000);
        accountant.onInput();
        clock.advance(61_000);
        accountant.onInput();
        clock.advance(1_000);
        assertEquals(120_000, total(accountant.drain()));
    }

    @Test
    public void backgroundAndScreenOffCloseImmediately() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        clock.advance(10_000);
        accountant.onForeground(false);
        clock.advance(300_000);
        accountant.onForeground(true);
        clock.advance(10_000);
        accountant.onScreen(false);
        clock.advance(300_000);
        accountant.onScreen(true);
        clock.advance(10_000);
        assertEquals(20_000, total(accountant.drain()));
    }

    @Test
    public void transitionsAndAccountsAreExclusive() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        clock.advance(10_000);
        accountant.onSurface(CHAT);
        clock.advance(20_000);
        accountant.onSurface(OTHER_ACCOUNT);
        clock.advance(30_000);
        Map<UsageLedger.BucketKey, Long> rows = accountant.drain();
        assertEquals(10_000, surface(rows, LIST));
        assertEquals(20_000, surface(rows, CHAT));
        assertEquals(30_000, surface(rows, OTHER_ACCOUNT));
        assertEquals(60_000, total(rows));
    }

    @Test
    public void passiveVideoAndVoiceCountButMusicDoesNot() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        accountant.onPassive(true);
        clock.advance(300_000);
        accountant.onPassive(false);
        clock.advance(300_000); // music: no passive extension
        assertEquals(300_000, total(accountant.drain()));
    }

    @Test
    public void connectedCallOwnsScreenOffAndBackgroundTime() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        clock.advance(10_000);
        accountant.onCall(CALL);
        accountant.onScreen(false);
        accountant.onForeground(false);
        clock.advance(300_000);
        accountant.onCall(null);
        Map<UsageLedger.BucketKey, Long> rows = accountant.drain();
        assertEquals(10_000, surface(rows, LIST));
        assertEquals(300_000, surface(rows, CALL));
        assertEquals(310_000, total(rows));
    }

    @Test
    public void midnightAndHourBoundariesSplitCredits() {
        Clock clock = new Clock("2026-10-05T23:59:30Z");
        UsageAccountant accountant = open(clock);
        clock.advance(60_000);
        Map<UsageLedger.BucketKey, Long> rows = accountant.drain();
        assertEquals(2, rows.size());
        assertEquals(60_000, total(rows));
        for (UsageLedger.BucketKey key : rows.keySet()) {
            assertEquals(30_000, (long) rows.get(key));
        }
    }

    @Test
    public void clockAndZoneChangesNeverDuplicateElapsedTime() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        clock.advance(20_000);
        clock.wall -= 86_400_000;
        accountant.checkpoint();
        clock.zone = ZoneId.of("America/New_York");
        clock.advance(20_000);
        clock.wall += 86_400_000;
        accountant.checkpoint();
        assertEquals(40_000, total(accountant.drain()));
    }

    @Test
    public void daylightSavingOverlapKeepsBothElapsedHours() {
        Clock clock = new Clock("2026-11-01T05:30:00Z");
        clock.zone = ZoneId.of("America/New_York");
        UsageAccountant accountant = open(clock);
        accountant.onCall(CALL);
        clock.advance(2 * 60 * 60_000L);
        Map<UsageLedger.BucketKey, Long> rows = accountant.drain();
        assertEquals(2 * 60 * 60_000L, total(rows));
        assertEquals(2 * 60 * 60_000L, surface(rows, CALL));
    }

    @Test
    public void backwardElapsedReadingNeverMakesNegativeCredit() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        clock.advance(10_000);
        accountant.checkpoint();
        clock.elapsed -= 5_000;
        accountant.checkpoint();
        assertEquals(10_000, total(accountant.drain()));
    }

    @Test
    public void fixedSeedEventScriptNeverDoubleBooks() {
        Clock clock = new Clock("2026-10-05T12:00:00Z");
        UsageAccountant accountant = open(clock);
        Random random = new Random(91);
        long elapsed = 0;
        for (int i = 0; i < 500; i++) {
            long step = random.nextInt(1_000);
            clock.advance(step);
            elapsed += step;
            if ((i & 1) == 0) accountant.onInput();
            if (i % 7 == 0) accountant.onSurface(i % 14 == 0 ? CHAT : LIST);
            assertTrue(accountant.pendingMillis() <= elapsed);
        }
        assertEquals(elapsed, total(accountant.drain()));
    }
}
