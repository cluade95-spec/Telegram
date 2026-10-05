package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Random;

public class UsageAccountantSurfaceTest {

    @Test
    public void transitionCreditsOldSurfaceUpToTheSwitch() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(20);
        a.onSurface(UsageSurface.CHAT_PRIVATE, 42);
        c.advanceSec(30);
        a.onInput();
        UsageLedger.Snapshot s = a.drain();
        long list = 0, chat = 0;
        for (UsageLedger.Row r : s.rows) {
            if (r.key.surface == UsageSurface.CHAT_LIST.id) {
                list += r.seconds;
            }
            if (r.key.surface == UsageSurface.CHAT_PRIVATE.id) {
                chat += r.seconds;
                assertEquals(42, r.key.dialog);
            }
        }
        assertEquals(20, list);
        assertEquals(30, chat);
    }

    @Test
    public void nonChatSurfacesDropTheDialog() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onSurface(UsageSurface.SETTINGS, 99);
        c.advanceSec(5);
        a.tick();
        for (UsageLedger.Row r : a.drain().rows) {
            assertEquals(0, r.key.dialog);
        }
    }

    /** Sum of credits equals the independently computed active time for random scripts. */
    @Test
    public void creditsEqualActiveTimeForRandomScripts() {
        UsageSurface[] surfaces = UsageSurface.values();
        for (int seed = 0; seed < 50; seed++) {
            Random rnd = new Random(seed);
            FakeClock c = new FakeClock();
            UsageAccountant a = UsageTestUtil.started(c);
            long expectedMs = 0;
            long lastInput = c.elapsed;
            long segStart = c.elapsed;
            UsageSurface cur = UsageSurface.CHAT_LIST;
            for (int i = 0; i < 400; i++) {
                long dt = rnd.nextInt(90_000);
                c.advance(dt);
                // reference: credit [segStart, now] up to lastInput + IDLE
                long end = Math.min(c.elapsed, lastInput + UsagePolicy.IDLE_MS);
                int op = rnd.nextInt(3);
                if (op == 0) {
                    expectedMs += Math.max(0, end - segStart);
                    segStart = c.elapsed;
                    lastInput = c.elapsed;
                    a.onInput();
                } else if (op == 1) {
                    expectedMs += Math.max(0, end - segStart);
                    segStart = c.elapsed;
                    cur = surfaces[rnd.nextInt(surfaces.length)];
                    a.onSurface(cur, rnd.nextInt(5));
                } else {
                    expectedMs += Math.max(0, end - segStart);
                    segStart = c.elapsed;
                    a.tick();
                }
            }
            a.tick();
            assertEquals("seed " + seed, expectedMs, a.ledger().totalMs());
        }
    }
}
