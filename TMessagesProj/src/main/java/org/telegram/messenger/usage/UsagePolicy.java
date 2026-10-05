package org.telegram.messenger.usage;

/**
 * Tunable constants of the Activity feature (docs/implementation-plan.md, Reference A). Tests pin the behavior these
 * numbers produce.
 */
public final class UsagePolicy {

    private UsagePolicy() {
    }

    /** A gap of g ms without input credits min(g, IDLE_MS). */
    public static final long IDLE_MS = 60_000L;

    /** Unflushed time that triggers a flush at the next accounting event. */
    public static final long FLUSH_THRESHOLD_MS = 5 * 60_000L;

    /** Longest session: continuous active time without a gap above this value. */
    public static final long SESSION_GAP_MS = 5 * 60_000L;

    /** Rows older than this many days are rolled up to hour = -1. */
    public static final int ROLLUP_AFTER_DAYS = 90;

    /** Rows older than this many days fold small dialogs into dialog = 0. */
    public static final int FOLD_AFTER_DAYS = 730;

    /** Per-day dialog time below this value is folded after FOLD_AFTER_DAYS. */
    public static final int FOLD_BELOW_SECONDS = 60;
}
