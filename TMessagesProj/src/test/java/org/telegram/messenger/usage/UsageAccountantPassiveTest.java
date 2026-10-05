package org.telegram.messenger.usage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UsageAccountantPassiveTest {

    @Test
    public void playingVideoExtendsPastTheIdleWindow() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onSurface(UsageSurface.MEDIA_VIEWER, 0);
        a.onPassive(true);
        c.advanceSec(600);
        a.onPassive(false);
        assertEquals(600, UsageTestUtil.totalSec(a));
    }

    @Test
    public void afterPlaybackStopsNothingMoreIsCreditedWithoutInput() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onPassive(true);
        c.advanceSec(100);
        a.onPassive(false);
        c.advanceSec(500);
        a.tick();
        assertEquals(100, UsageTestUtil.totalSec(a)); // passiveUntil is the end of playback, no extra window
    }

    @Test
    public void musicIsNotReportedAsPassive() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        c.advanceSec(3600); // music plays, the tracker sends nothing
        a.tick();
        assertEquals(60, UsageTestUtil.totalSec(a));
    }

    @Test
    public void passivePlaybackInBackgroundOrScreenOffDoesNotCount() {
        FakeClock c = new FakeClock();
        UsageAccountant a = UsageTestUtil.started(c);
        a.onPassive(true);
        a.onBackground();
        c.advanceSec(300);
        a.tick();
        assertEquals(0, UsageTestUtil.totalSec(a));
        a.onForeground();
        a.onScreen(false);
        c.advanceSec(300);
        a.tick();
        assertEquals(0, UsageTestUtil.totalSec(a));
    }
}
