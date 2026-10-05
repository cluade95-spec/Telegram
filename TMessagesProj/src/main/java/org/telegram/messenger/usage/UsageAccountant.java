package org.telegram.messenger.usage;

import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** Event-driven accounting. All calls are serialized by the owner, normally the UI thread. */
public final class UsageAccountant {
    private final UsageClock clock;
    private final UsageLedger ledger = new UsageLedger();
    private long cursorElapsed;
    private long cursorWall;
    private ZoneId cursorZone;
    private long lastInput;
    private boolean foreground;
    private boolean screenOn;
    private boolean passive;
    private SurfaceKey surface;
    private SurfaceKey call;

    public UsageAccountant(UsageClock clock) {
        this.clock = Objects.requireNonNull(clock);
        cursorElapsed = clock.elapsed();
        cursorWall = clock.wallMillis();
        cursorZone = clock.zone();
        lastInput = Long.MIN_VALUE;
    }

    private void settle() {
        long now = clock.elapsed();
        long duration = Math.max(0, now - cursorElapsed);
        SurfaceKey owner = call != null ? call : surface;
        if (owner != null && duration > 0) {
            long credit = 0;
            if (call != null) {
                credit = duration;
            } else if (foreground && screenOn) {
                if (passive) {
                    credit = duration;
                } else if (lastInput != Long.MIN_VALUE && lastInput <= Long.MAX_VALUE - UsagePolicy.IDLE_MS) {
                    credit = Math.min(duration, Math.max(0, lastInput + UsagePolicy.IDLE_MS - cursorElapsed));
                }
            }
            ledger.add(owner, cursorWall, credit, cursorZone);
        }
        cursorElapsed = Math.max(cursorElapsed, now);
        cursorWall = clock.wallMillis();
        cursorZone = clock.zone();
    }

    public void onInput() {
        settle();
        lastInput = cursorElapsed;
    }

    public void onForeground(boolean active) {
        settle();
        if (active && !foreground) lastInput = cursorElapsed;
        foreground = active;
    }

    public void onScreen(boolean on) {
        settle();
        screenOn = on;
    }

    public void onSurface(SurfaceKey key) {
        settle();
        surface = key;
    }

    public void onPassive(boolean engaged) {
        settle();
        passive = engaged;
    }

    public void onCall(SurfaceKey connectedCall) {
        if (connectedCall != null && connectedCall.surface != UsageSurface.CALL) {
            throw new IllegalArgumentException("Connected call requires CALL surface");
        }
        settle();
        call = connectedCall;
    }

    public void checkpoint() {
        settle();
    }

    public long pendingMillis() {
        return ledger.totalMillis();
    }

    public Map<UsageLedger.BucketKey, Long> drain() {
        settle();
        return ledger.drain();
    }
}
