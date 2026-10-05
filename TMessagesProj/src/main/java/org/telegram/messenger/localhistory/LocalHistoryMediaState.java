package org.telegram.messenger.localhistory;

/** States and kinds of archived media (plan B10). */
public final class LocalHistoryMediaState {

    public static final int PRESERVED = 0;
    public static final int PENDING_COPY = 1;
    /** never downloaded on this device, only the inline thumbnail is known */
    public static final int NOT_DOWNLOADED = 2;
    public static final int TOO_LARGE = 3;
    public static final int OVER_BUDGET = 4;
    /** the original disappeared before it could be copied */
    public static final int LOST = 5;
    /** removed again by the budget or by the user */
    public static final int EVICTED = 6;

    public static final int KIND_PHOTO = 0;
    public static final int KIND_DOCUMENT = 1;

    /** Files larger than this are not copied. */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    /** Per account budget; the oldest preserved media is evicted first. */
    public static final long BUDGET_BYTES = 1024L * 1024 * 1024;

    private LocalHistoryMediaState() {
    }
}
