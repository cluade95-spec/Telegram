package org.telegram.ui.Components;

/**
 * A damped spring solved in closed form, so its value can be read at any time without stepping
 * it every frame. Retargeting keeps the current position and velocity, and a retarget can be
 * delayed: until the delay has passed the spring carries on with its previous motion, which is
 * what the lyric stagger is made of.
 *
 * <p>Behaviour follows AMLL's utils/spring.ts (itself credited there to github.com/pushkine, MIT):
 * mass, stiffness and damping in seconds, and any spring at or above critical damping is solved
 * as exactly critical with omega = sqrt(stiffness / mass). Values are unit-free, so the same
 * spring moves pixels or a scale in percent.
 */
public final class LyricsSpring {
    private static final double SETTLE_EPSILON = 0.01;

    private float mass = 1f;
    private float stiffness = 100f;
    private float damping = 10f;

    // The solution segment currently running: starts at startNanos from (from, velocity).
    private double from;
    private double velocity;
    private double target;
    private long startNanos;

    // A retarget waiting for its delay to pass.
    private boolean pending;
    private double pendingTarget;
    private long pendingNanos;
    private float pendingMass, pendingStiffness, pendingDamping;

    public LyricsSpring(float value) {
        snap(value);
    }

    /** Puts the spring at rest at {@code value}, dropping any motion and any pending target. */
    public void snap(float value) {
        from = target = value;
        velocity = 0;
        startNanos = 0;
        pending = false;
    }

    /**
     * Moves the whole motion by {@code delta}: value, target and any pending target alike, with
     * the velocity unchanged. Used when the list is scrolled underneath the springs (the user's
     * finger), so a row's lead or lag against the list carries on settling instead of jumping.
     */
    public void shift(float delta) {
        from += delta;
        target += delta;
        pendingTarget += delta;
    }

    /**
     * Moves the target (and any pending target) by {@code delta} from now on, keeping the
     * current position and velocity: a correction to where the motion is heading, not a jump.
     */
    public void moveTarget(float delta, long nowNanos) {
        resolvePending(nowNanos);
        if (pending) pendingTarget += delta;
        restart(nowNanos, target + delta, mass, stiffness, damping);
    }

    public void setParams(float mass, float stiffness, float damping) {
        this.mass = mass;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    /**
     * Moves towards {@code newTarget}, starting {@code delayMs} after {@code nowNanos}. The params
     * given are the ones the new motion uses; the motion in flight keeps its own until then.
     */
    public void setTarget(float newTarget, long nowNanos, long delayMs, float mass, float stiffness, float damping) {
        resolvePending(nowNanos);
        if (delayMs <= 0) {
            pending = false;
            restart(nowNanos, newTarget, mass, stiffness, damping);
        } else {
            pending = true;
            pendingTarget = newTarget;
            pendingNanos = nowNanos + delayMs * 1_000_000L;
            pendingMass = mass;
            pendingStiffness = stiffness;
            pendingDamping = damping;
        }
    }

    public float getTarget() {
        return (float) (pending ? pendingTarget : target);
    }

    public float value(long nowNanos) {
        resolvePending(nowNanos);
        return (float) (target + displacement(seconds(nowNanos)));
    }

    public boolean isSettled(long nowNanos) {
        resolvePending(nowNanos);
        if (pending) return false;
        final double t = seconds(nowNanos);
        return Math.abs(displacement(t)) < SETTLE_EPSILON && Math.abs(speed(t)) < SETTLE_EPSILON;
    }

    private void resolvePending(long nowNanos) {
        if (pending && nowNanos >= pendingNanos) {
            pending = false;
            restart(pendingNanos, pendingTarget, pendingMass, pendingStiffness, pendingDamping);
        }
    }

    private void restart(long atNanos, double newTarget, float m, float k, float c) {
        final double t = seconds(atNanos);
        final double position = target + displacement(t);
        final double v = speed(t);
        from = position;
        velocity = v;
        target = newTarget;
        startNanos = atNanos;
        setParams(m, k, c);
    }

    private double seconds(long nowNanos) {
        return startNanos == 0 ? 1e6 : Math.max(0, (nowNanos - startNanos) / 1e9);
    }

    private double displacement(double t) {
        final double y0 = from - target;
        if (y0 == 0 && velocity == 0) return 0;
        final double omega = Math.sqrt(stiffness / mass);
        final double zeta = damping / (2.0 * Math.sqrt(stiffness * mass));
        if (zeta >= 1.0) {
            return (y0 + (velocity + omega * y0) * t) * Math.exp(-omega * t);
        }
        final double a = zeta * omega;
        final double wd = omega * Math.sqrt(1.0 - zeta * zeta);
        final double b = (velocity + a * y0) / wd;
        return Math.exp(-a * t) * (y0 * Math.cos(wd * t) + b * Math.sin(wd * t));
    }

    private double speed(double t) {
        final double y0 = from - target;
        if (y0 == 0 && velocity == 0) return 0;
        final double omega = Math.sqrt(stiffness / mass);
        final double zeta = damping / (2.0 * Math.sqrt(stiffness * mass));
        if (zeta >= 1.0) {
            final double c = velocity + omega * y0;
            return Math.exp(-omega * t) * (velocity - omega * c * t);
        }
        final double a = zeta * omega;
        final double wd = omega * Math.sqrt(1.0 - zeta * zeta);
        final double b = (velocity + a * y0) / wd;
        return Math.exp(-a * t) * (velocity * Math.cos(wd * t) + (-a * b - y0 * wd) * Math.sin(wd * t));
    }
}
