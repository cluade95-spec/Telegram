package org.telegram.messenger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which destinations of a forward (or share) must be unlocked first, and the continuation that
 * resumes the forward once they are.
 *
 * <p>The rule is the same for every kind of destination: users, bots, groups, supergroups,
 * channels, topics, secret chats. A destination that is protected and locked asks for
 * authentication before anything is sent, and the user's own Saved Messages is the single
 * exception: depositing a forward there never asks (it is write-only: it neither unlocks Saved
 * Messages nor opens it, so its history stays behind its own lock). A destination that is already
 * unlocked is not asked again.
 *
 * <p>The pending operation is the original selection itself, started again with exactly the
 * arguments it had (messages, destinations with their topics, order, options): nothing of it is
 * consumed before the destinations are open. After a successful authentication it runs once;
 * a cancelled authentication runs nothing, ever.
 */
public final class ForwardDestinations {

    private ForwardDestinations() {
    }

    /** Whether a destination is protected and locked right now. */
    public interface Locks {
        boolean isLockedProtected(long dialogId);
    }

    /** Asks the user to unlock one destination. Exactly one of the callbacks may run, once. */
    public interface Authenticator {
        void authenticate(long dialogId, Runnable onUnlocked, Runnable onCancelled);
    }

    /**
     * @param ownSavedMessagesExempt true for a forward: the user's own Saved Messages never asks.
     *                               False for a share, which opens the chat it lands in.
     */
    public static boolean needsAuthentication(long dialogId, long ownSavedMessagesId, boolean ownSavedMessagesExempt, Locks locks) {
        if (ownSavedMessagesExempt && ownSavedMessagesId != 0 && dialogId == ownSavedMessagesId) {
            return false;
        }
        return locks.isLockedProtected(dialogId);
    }

    /**
     * Destinations the user has just authenticated for the operation that is running right now. The
     * operation starts again from the top (it is the original selection), and it must not ask again
     * for what was unlocked a moment ago for it, however long the other prompts took.
     */
    private static final ThreadLocal<Set<Long>> RESUMING = new ThreadLocal<>();

    /** The first destination that must be unlocked, or 0 when none. */
    public static long firstLocked(List<Long> dialogIds, long ownSavedMessagesId, boolean ownSavedMessagesExempt, Locks locks) {
        final Set<Long> resuming = RESUMING.get();
        for (int i = 0; i < dialogIds.size(); i++) {
            final long id = dialogIds.get(i);
            if (resuming != null && resuming.contains(id)) {
                continue;
            }
            if (needsAuthentication(id, ownSavedMessagesId, ownSavedMessagesExempt, locks)) {
                return id;
            }
        }
        return 0;
    }

    /** One forward waiting for the unlock of its destinations. It can run once and never after a cancel. */
    public static final class Pending {
        private Runnable operation;
        private boolean finished;
        private final Set<Long> unlockedForThis = new HashSet<>();

        private Pending(Runnable operation) {
            this.operation = operation;
        }

        public synchronized boolean isOpen() {
            return !finished;
        }

        /** Runs the operation if it was neither run nor cancelled; true if it ran now. */
        synchronized boolean run() {
            if (finished) {
                return false;
            }
            finished = true;
            final Runnable toRun = operation;
            operation = null;
            final Set<Long> before = RESUMING.get();
            RESUMING.set(unlockedForThis);
            try {
                toRun.run();
            } finally {
                RESUMING.set(before);
            }
            return true;
        }

        /** The user did not unlock: the operation is dropped and can not run later. */
        public synchronized void cancel() {
            finished = true;
            operation = null;
        }
    }

    /**
     * If a destination is locked: starts authenticating it and returns true. The caller stops.
     * {@code operation} (the original forward, with its original arguments) runs once after every
     * locked destination has been unlocked, and never if the user cancels. If nothing is locked it
     * returns false and does not run the operation: the caller simply continues.
     */
    public static boolean holdUntilUnlocked(List<Long> dialogIds, long ownSavedMessagesId, boolean ownSavedMessagesExempt, Locks locks, Authenticator authenticator, Runnable operation) {
        final long locked = firstLocked(dialogIds, ownSavedMessagesId, ownSavedMessagesExempt, locks);
        if (locked == 0) {
            return false;
        }
        authenticate(new Pending(operation), locked, dialogIds, ownSavedMessagesId, ownSavedMessagesExempt, locks, authenticator);
        return true;
    }

    private static long firstLockedFor(Pending pending, List<Long> dialogIds, long own, boolean exempt, Locks locks) {
        final Set<Long> before = RESUMING.get();
        RESUMING.set(pending.unlockedForThis);
        try {
            return firstLocked(dialogIds, own, exempt, locks);
        } finally {
            RESUMING.set(before);
        }
    }

    private static void authenticate(Pending pending, long dialogId, List<Long> dialogIds, long own, boolean exempt, Locks locks, Authenticator authenticator) {
        authenticator.authenticate(dialogId, () -> {
            if (!pending.isOpen()) {
                return;
            }
            pending.unlockedForThis.add(dialogId);
            // Look again: the next locked destination asks in its turn, then the operation runs.
            final long next = firstLockedFor(pending, dialogIds, own, exempt, locks);
            if (next == 0) {
                pending.run();
            } else {
                authenticate(pending, next, dialogIds, own, exempt, locks, authenticator);
            }
        }, pending::cancel);
    }
}
