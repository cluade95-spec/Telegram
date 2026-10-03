package org.telegram.messenger;

import java.util.HashMap;
import java.util.Map;

/**
 * What the protected-chat gate does when a navigation layout moves fragments around, without any
 * Android type, so that it runs unchanged in the unit tests.
 *
 * <p>{@code ProtectedChatGate} is the Android adapter: it maps a fragment to its {@link Node}, its
 * dialog and its account, and calls the methods below from {@code BaseFragment.onResume /
 * onPause / onFragmentDestroy} and from the layout hooks. Everything that decides what a pause, a
 * resume or a destroy means for a protected dialog is here:
 *
 * <ul>
 *   <li>a dialog counts as "being used" while at least one fragment showing it is visible
 *       ({@link #enter} / {@link #leave}); the countdown starts when the last one is covered, decided
 *       after the transition settled so moving between fragments of one chat never re-locks it;</li>
 *   <li>a fragment that comes back on screen after its authorization ended must not show;</li>
 *   <li>the forward the open chat started is a transaction of that chat, with its own phases (see
 *       {@link ProtectedChatsState.ForwardPhase}): the picker, the destination it opens and the
 *       completion interaction are not the user leaving the chat, and every way of leaving them
 *       (Back, system back, gesture back, toolbar back, the swipe that is cancelled halfway) reaches
 *       the same end through the same lifecycle calls.</li>
 * </ul>
 *
 * <p>The source of a forward and its destination are different fragments with different
 * dialogs and different authorizations. Nothing here knows "the current forward chat": the picker
 * points to its source, a destination points back to the dialog whose forward opened it, and every
 * transition is addressed to one dialog.
 *
 * <p>All methods run on the UI thread.
 */
public final class ProtectedGateLifecycle {

    /** Runs a task after the current message, like {@code AndroidUtilities.runOnUIThread}. */
    public interface Poster {
        void post(Runnable task);
    }

    /** The gate data one fragment carries ({@code BaseFragment.protectedGate}). */
    public static final class Node {
        private long dialogId;
        private long accountKey;
        private boolean visible;
        private boolean finished;
        /** On a forward picker: the screen it was opened over. */
        private Node forwardSource;
        /** On a destination: the protected dialog whose forward opened it. */
        private long originDialogId;
        private long originAccountKey;

        /** The fragment shows a protected dialog and is registered for it. */
        public boolean isRegistered() {
            return dialogId != 0;
        }

        /** The fragment currently counts as visible for the re-lock countdown of its dialog. */
        public boolean isVisible() {
            return visible;
        }

        public boolean isFinished() {
            return finished;
        }

        public long getDialogId() {
            return dialogId;
        }

        public long getAccountKey() {
            return accountKey;
        }

        /** Nothing is registered on it and it takes part in no forward: the gate has nothing to do. */
        public boolean isIdle() {
            return dialogId == 0 && forwardSource == null && originDialogId == 0;
        }

        public boolean hasForwardSource() {
            return forwardSource != null;
        }
    }

    private final ProtectedChatsState state;
    private final Poster poster;
    private final Map<String, Integer> openCounts = new HashMap<>();

    public ProtectedGateLifecycle(ProtectedChatsState state, Poster poster) {
        this.state = state;
        this.poster = poster;
    }

    private static String key(long accountKey, long dialogId) {
        return accountKey + ":" + dialogId;
    }

    // ------------------------------------------------------------------ open chats

    /** A fragment showing this dialog (chat, profile, topics) became visible; reference counted. */
    void enter(long accountKey, long dialogId) {
        final String k = key(accountKey, dialogId);
        final Integer c = openCounts.get(k);
        openCounts.put(k, c == null ? 1 : c + 1);
        state.chatEntered(accountKey, dialogId);
    }

    /** The last visible fragment of the dialog was covered or closed: the re-lock countdown starts. */
    void leave(long accountKey, long dialogId) {
        final String k = key(accountKey, dialogId);
        final Integer c = openCounts.get(k);
        if (c == null || c <= 1) {
            openCounts.remove(k);
            // Fragment transitions pause one fragment and resume or create the next in the same pass;
            // decide after they settled so moving between fragments of one chat never re-locks it.
            poster.post(() -> {
                if (!openCounts.containsKey(k)) {
                    state.chatLeft(accountKey, dialogId);
                }
            });
        } else {
            openCounts.put(k, c - 1);
        }
    }

    // ------------------------------------------------------------------ fragment callbacks

    /** The fragment passed the gate and is now part of a navigation stack; the dialog is protected. */
    public void created(Node node, long accountKey, long dialogId) {
        if (node.dialogId != 0 || dialogId == 0) {
            return;
        }
        node.dialogId = dialogId;
        node.accountKey = accountKey;
        node.visible = true;
        enter(accountKey, dialogId);
    }

    /**
     * A covered fragment (another chat, a picker, the app in the background) is not "being used":
     * the re-lock countdown of its dialog starts once no fragment of it is visible. If it was the
     * destination of a forward, the forward has left it.
     */
    public void paused(Node node) {
        if (node.dialogId != 0 && node.visible) {
            node.visible = false;
            leave(node.accountKey, node.dialogId);
        }
        destinationLeft(node);
    }

    /**
     * Back on screen. If the authorization ended while it was covered, it must not show again
     * ({@code closeSelf} removes it, once the transition that resumed it is over). Nothing about a
     * forward is decided here: a cover that was part of the forward never ended the authorization,
     * and coming back onto the screen is exactly what makes the forward's cover not count.
     */
    public void resumed(Node node, Runnable closeSelf) {
        if (node.dialogId == 0 || node.visible) {
            return;
        }
        final long accountKey = node.accountKey;
        final long dialogId = node.dialogId;
        if (state.isLockedProtected(accountKey, dialogId)) {
            poster.post(() -> {
                if (!node.finished && state.isLockedProtected(accountKey, dialogId)) {
                    closeSelf.run();
                }
            });
            return;
        }
        node.visible = true;
        enter(accountKey, dialogId);
    }

    /** The fragment is gone. */
    public void destroyed(Node node) {
        destinationLeft(node);
        pickerDestroyed(node);
        node.finished = true;
        if (node.dialogId != 0) {
            final long accountKey = node.accountKey;
            final long dialogId = node.dialogId;
            // A chat that is gone holds nothing.
            state.forwardSourceGone(accountKey, dialogId);
            node.dialogId = 0;
            if (node.visible) {
                node.visible = false;
                leave(accountKey, dialogId);
            }
        }
    }

    // ------------------------------------------------------------------ forward transaction

    /**
     * A forward picker is being presented over {@code below}. Whatever screen that is (a chat, a
     * profile or its shared media, the chat behind a viewer), if it shows a protected dialog that is
     * authorized and visible right now, that authorization is held while the forward runs. The
     * picker remembers the screen it belongs to.
     */
    public boolean pickerPresented(Node picker, Node below) {
        if (below == null || below.dialogId == 0 || picker == below) {
            return false;
        }
        if (state.beginForwardHoldOver(below.accountKey, below.dialogId, below.visible)) {
            picker.forwardSource = below;
            return true;
        }
        return false;
    }

    /** The picker was destroyed: see {@link ProtectedChatsState#forwardPickerClosed}. */
    private void pickerDestroyed(Node picker) {
        final Node source = picker.forwardSource;
        picker.forwardSource = null;
        if (source != null && source.dialogId != 0) {
            state.forwardPickerClosed(source.accountKey, source.dialogId);
        }
    }

    /**
     * The picker hands its selection to the delegate.
     *
     * @return the source the forward belongs to, to be passed to {@link #settled}; null when the
     * picker is not tracking a source
     */
    public Node handOver(Node picker) {
        final Node source = picker.forwardSource;
        if (source == null || source.dialogId == 0) {
            return null;
        }
        state.forwardHandOver(source.accountKey, source.dialogId);
        return source;
    }

    /**
     * The delegate returned. {@code destination} is the fragment that is on top of the navigation
     * layout now (null when none). What the delegate did decides the phase:
     * not completed: the picker goes on; the source is shown again: completion interaction; a
     * destination chat is on top of the source: the destination phase, and that chat remembers whose
     * forward opened it; otherwise the forward is over.
     */
    public void settled(Node picker, Node source, boolean handled, Node destination) {
        if (source == null || source.dialogId == 0) {
            return;
        }
        final ProtectedChatsState.ForwardOutcome outcome;
        if (!handled) {
            outcome = ProtectedChatsState.ForwardOutcome.PICKER_REMAINS;
        } else if (source.visible) {
            outcome = ProtectedChatsState.ForwardOutcome.SOURCE_RETURNED;
        } else if (destination != null && destination != picker && destination != source && !destination.finished) {
            outcome = ProtectedChatsState.ForwardOutcome.DESTINATION_OPENED;
        } else if (!picker.finished) {
            outcome = ProtectedChatsState.ForwardOutcome.PICKER_REMAINS;
        } else {
            outcome = ProtectedChatsState.ForwardOutcome.ABANDONED;
        }
        state.forwardSettled(source.accountKey, source.dialogId, outcome);
        if (outcome == ProtectedChatsState.ForwardOutcome.DESTINATION_OPENED
                && state.getForwardPhase(source.accountKey, source.dialogId) == ProtectedChatsState.ForwardPhase.DESTINATION) {
            destination.originDialogId = source.dialogId;
            destination.originAccountKey = source.accountKey;
        }
    }

    /** The destination the forward opened was covered or destroyed: see {@link ProtectedChatsState#forwardDestinationLeft}. */
    private void destinationLeft(Node destination) {
        if (destination.originDialogId != 0) {
            final long accountKey = destination.originAccountKey;
            final long dialogId = destination.originDialogId;
            destination.originDialogId = 0;
            destination.originAccountKey = 0;
            state.forwardDestinationLeft(accountKey, dialogId);
        }
    }

    /** Telegram's success and tag interaction on the source ended. */
    public void completionEnded(Node source) {
        if (source.dialogId != 0) {
            state.forwardCompletionEnded(source.accountKey, source.dialogId);
        }
    }
}
