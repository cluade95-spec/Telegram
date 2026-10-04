package org.telegram.messenger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the protected-chat gate does when a navigation layout moves fragments around and when the
 * host activity changes state, without any Android type, so that it runs unchanged in the unit
 * tests. {@code ProtectedChatGate} is the Android adapter: it maps a fragment to its {@link Node},
 * its dialog and its account and forwards the lifecycle calls.
 *
 * <h3>The model</h3>
 * "Paused" and "covered" are not "left". Telegram and Android put things above a chat all the time
 * (a picker, the comments of a channel post, a profile, a permission dialog) and the chat is the
 * place the user comes back to. A protected chat that the user authenticated therefore stays
 * authorized while it is still part of the navigation stack and the user has not genuinely left it.
 * Every fragment that shows a protected dialog is a {@link Node} in one of these states:
 *
 * <pre>
 *   VISIBLE      on screen, in use.
 *   HOST_PAUSED  the host activity is paused (a system permission dialog, a split-screen focus
 *                change, picture-in-picture) but the chat is still what the user sees: the system
 *                overlay is not navigation and not leaving. Ends with the activity resuming; if the
 *                activity is stopped instead, the app-background boundary applies.
 *   COVERED      a child fragment (forward picker or destination, channel comments, a profile, a
 *                media or search screen, a contact/location picker ...) was presented over it and
 *                it is the place Back returns to. Still in use, still authorized.
 *   RETURNING    it was resumed while its transition is still running (Back, swipe, predictive
 *                back). Nothing is evaluated as a newly exposed chat, nothing is closed mid-transition.
 *   LEFT         the user genuinely left it while it stays in a stack: it is closed as soon as that
 *                can be done silently, and normal Auto-lock applies from then.
 *   DESTROYED    popped or removed; normal Auto-lock applies from then.
 * </pre>
 *
 * A chat is genuinely left when it is destroyed, when the app goes to the background (the host
 * activity stops or the screen turns off, see {@link #appBackgrounded}), on a manual lock, and when
 * a child opens a different conversation than the ones that belong to the chat's own context
 * ({@link #presented}: the chat's dialog, the child's conversation and non-conversation screens
 * such as pickers or media are related; another chat or profile is not). Where a countdown applies
 * (Auto-lock intervals) it starts from that moment.
 *
 * <p>Nothing is ever popped because a fragment is becoming visible again: a chat that lost its
 * authorization is closed at the moment it loses it (it is not on screen then) or, if the layout is
 * in the middle of a transition, as soon as that transition ended (see {@link Closer}).
 *
 * <p>Each dialog has its own authorization ({@code ProtectedChatsState}); the nodes of different
 * dialogs never share state, so a destination, a comments chat or a child that shows another
 * protected dialog is gated and authorized on its own account. A forward started from the chat has
 * additional state ({@link ProtectedChatsState.ForwardPhase}); it is described at the forward
 * methods below.
 *
 * <p>All methods run on the UI thread.
 */
public final class ProtectedGateLifecycle {

    /** Runs a task after the current message, like {@code AndroidUtilities.runOnUIThread}. */
    public interface Poster {
        void post(Runnable task);
    }

    /**
     * Closes a fragment that lost its authorization. Returns true when it is gone (or was not
     * there), false when it can not be done right now because the layout is in a transition that
     * involves it; the gate then retries when the transition ended.
     */
    public interface Closer {
        boolean tryClose();
    }

    public enum NodeState {
        /** Not registered: the fragment shows no protected dialog. */
        IDLE,
        VISIBLE,
        HOST_PAUSED,
        COVERED,
        RETURNING,
        LEFT,
        DESTROYED
    }

    /** The gate data one fragment carries ({@code BaseFragment.protectedGate}). */
    public static final class Node {
        private NodeState state = NodeState.IDLE;
        private long dialogId;
        private long accountKey;
        private boolean finished;
        private Closer closer;
        private boolean pendingClose;
        /** Any fragment inside a protected chat's context: the conversation it belongs to (its own, or the one it was opened from). */
        private long conversation;
        /** The protected fragment this fragment is a child of, or null. */
        private Node owner;
        /** On a forward picker: the screen it was opened over. */
        private Node forwardSource;
        /** On a destination: the protected dialog whose forward opened it. */
        private long originDialogId;
        private long originAccountKey;

        /** The fragment shows a protected dialog and is registered for it. */
        public boolean isRegistered() {
            return dialogId != 0;
        }

        public NodeState getState() {
            return state;
        }

        /** The fragment counts as open for the re-lock countdown of its dialog. */
        private boolean isCounted() {
            return state == NodeState.VISIBLE || state == NodeState.HOST_PAUSED || state == NodeState.COVERED || state == NodeState.RETURNING;
        }

        /** The user can see it (a system overlay over it is not navigation). */
        public boolean isVisible() {
            return state == NodeState.VISIBLE || state == NodeState.HOST_PAUSED || state == NodeState.RETURNING;
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

        /** Nothing is registered on it and it takes part in no protected context: the gate has nothing to do. */
        public boolean isIdle() {
            return dialogId == 0 && forwardSource == null && originDialogId == 0 && owner == null && !pendingClose;
        }

        /** The fragment is part of a protected chat's context (registered, or a child of one). */
        public boolean isInContext() {
            return dialogId != 0 || owner != null;
        }

        public boolean hasForwardSource() {
            return forwardSource != null;
        }
    }

    private static final class Open {
        final long accountKey;
        final long dialogId;
        int count;
        final List<Node> nodes = new ArrayList<>();

        Open(long accountKey, long dialogId) {
            this.accountKey = accountKey;
            this.dialogId = dialogId;
        }
    }

    private final ProtectedChatsState state;
    private final Poster poster;
    private final Map<String, Open> open = new HashMap<>();
    private boolean hostWindow;

    public ProtectedGateLifecycle(ProtectedChatsState state, Poster poster) {
        this.state = state;
        this.poster = poster;
    }

    private static String key(long accountKey, long dialogId) {
        return accountKey + ":" + dialogId;
    }

    // ------------------------------------------------------------------ open chats

    private void enter(Node node) {
        final String k = key(node.accountKey, node.dialogId);
        Open o = open.get(k);
        if (o == null) {
            o = new Open(node.accountKey, node.dialogId);
            open.put(k, o);
        }
        o.count++;
        o.nodes.add(node);
        state.chatEntered(node.accountKey, node.dialogId);
    }

    /** The node stops counting; the last one of its dialog starts the re-lock countdown (decided after the transition settled). */
    private void leave(Node node, long accountKey, long dialogId) {
        final String k = key(accountKey, dialogId);
        final Open o = open.get(k);
        if (o == null) {
            return;
        }
        o.nodes.remove(node);
        if (--o.count <= 0) {
            open.remove(k);
            // Fragment transitions pause one fragment and resume or create the next in the same pass;
            // decide after they settled so moving between fragments of one chat never re-locks it.
            poster.post(() -> {
                if (!open.containsKey(k)) {
                    state.chatLeft(accountKey, dialogId);
                }
            });
        }
    }

    // ------------------------------------------------------------------ host activity

    /**
     * The navigation layout forwards the activity's own pause and resume to its last fragment inside
     * this window. A fragment that is paused or resumed inside it was not covered or revealed by
     * navigation: the host went away from under it, which a system permission dialog, a
     * split-screen focus change or picture-in-picture do without the activity being stopped.
     */
    public void hostLifecycle(boolean active) {
        hostWindow = active;
    }

    /**
     * The app went to the background: the host activity stopped or the screen turned off. This is
     * a security boundary: every authorization that was in use stops being in use now and the normal
     * Auto-lock countdown starts, whatever was covering what.
     */
    public void appBackgrounded() {
        state.appPaused();
    }

    /**
     * The app came back from the background. Authorizations whose interval ran out are revoked
     * (the caller then closes the fragments that show them, none of which is on screen yet); the
     * chats that are still in use, visible or covered by their child, are in use again.
     */
    public void appForegrounded() {
        state.appResumed();
        for (Open o : new ArrayList<>(open.values())) {
            state.chatEntered(o.accountKey, o.dialogId);
        }
    }

    // ------------------------------------------------------------------ fragment callbacks

    /** The fragment passed the gate and is now part of a navigation stack; the dialog is protected. */
    public void created(Node node, long accountKey, long dialogId, Closer closer) {
        if (node.dialogId != 0 || dialogId == 0) {
            return;
        }
        node.dialogId = dialogId;
        node.accountKey = accountKey;
        node.conversation = dialogId;
        node.closer = closer;
        node.state = NodeState.VISIBLE;
        enter(node);
    }

    /**
     * A child fragment is presented over {@code below} (the layout's last fragment). If {@code below}
     * shows a protected dialog that is in use, the child belongs to its context and covers it. A
     * child of a child stays inside that context while it shows no other conversation than the ones
     * that belong to it; a child that opens another chat or profile takes the user out of the
     * context and the protected chat is left (see the class comment).
     *
     * @param conversation the dialog the new fragment shows when it is a chat or a profile, else 0
     * @param removeLast   the new fragment replaces {@code below}
     */
    public void presented(Node fragment, long conversation, Node below, boolean removeLast, boolean isForwardPicker) {
        if (below == null || fragment == below) {
            return;
        }
        if (isForwardPicker) {
            pickerPresented(fragment, below);
        }
        final Node owner;
        if (below.dialogId != 0 && below.isCounted()) {
            // a protected fragment that is in use; a replaced one is going away
            owner = removeLast ? null : below;
        } else if (below.owner != null) {
            final Node root = below.owner;
            if (removeLast || isRelated(root, below, conversation)) {
                owner = root;
            } else if (forwardIsOpen(root)) {
                // the forward picker's own screens (a forum's topics, a search) belong to the forward
                owner = root;
            } else {
                release(root.accountKey, root.dialogId);
                owner = null;
            }
        } else {
            owner = null;
        }
        if (owner != null) {
            fragment.owner = owner;
            // a screen that shows no conversation stays in the conversation it was opened from
            fragment.conversation = conversation != 0 ? conversation : below.conversation;
        }
    }

    private static boolean isRelated(Node root, Node below, long conversation) {
        return conversation == 0 || conversation == root.dialogId || conversation == below.conversation;
    }

    private boolean forwardIsOpen(Node root) {
        final ProtectedChatsState.ForwardPhase phase = state.getForwardPhase(root.accountKey, root.dialogId);
        return phase == ProtectedChatsState.ForwardPhase.PICKER || phase == ProtectedChatsState.ForwardPhase.HANDING_OVER;
    }

    /**
     * The fragment was paused by navigation (another fragment was presented over it, a swipe back
     * was given up) or, inside the host window, by the activity. Navigation cover is not leaving; a
     * host pause is not navigation at all.
     */
    public void paused(Node node) {
        if (hostWindow) {
            if (node.state == NodeState.VISIBLE || node.state == NodeState.RETURNING) {
                node.state = NodeState.HOST_PAUSED;
            }
            return;
        }
        if (node.state == NodeState.VISIBLE || node.state == NodeState.RETURNING || node.state == NodeState.HOST_PAUSED) {
            node.state = NodeState.COVERED;
        }
        destinationLeft(node);
    }

    /**
     * Back on screen: after a host pause, after its child was popped, or when a swipe back starts.
     * A chat that is still in use needs no evaluation. One that lost its authorization anyway (no
     * path is known to reveal it) is never closed from here, the transition that resumed it is
     * running: it is closed after that transition ended.
     */
    public void resumed(Node node) {
        if (node.dialogId == 0) {
            return;
        }
        switch (node.state) {
            case HOST_PAUSED:
                node.state = NodeState.VISIBLE;
                break;
            case COVERED:
                node.state = NodeState.RETURNING;
                break;
            case LEFT:
                if (!state.isLockedProtected(node.accountKey, node.dialogId)) {
                    // authorized again through another fragment of the dialog: a new entry
                    node.state = NodeState.VISIBLE;
                    enter(node);
                }
                break;
            default:
                break;
        }
        if (node.state == NodeState.LEFT || node.state == NodeState.DESTROYED) {
            requireClose(node);
            return;
        }
        state.chatEntered(node.accountKey, node.dialogId);
        if (state.isLockedProtected(node.accountKey, node.dialogId)) {
            requireClose(node);
        }
    }

    /** The layout finished the transition this fragment took part in (it is fully visible or fully hidden now). */
    public void transitionSettled(Node node) {
        if (node.state == NodeState.RETURNING) {
            node.state = NodeState.VISIBLE;
        }
        retryClose(node);
    }

    /** The fragment is gone. */
    public void destroyed(Node node) {
        destinationLeft(node);
        pickerDestroyed(node);
        node.finished = true;
        node.owner = null;
        node.pendingClose = false;
        if (node.dialogId != 0) {
            final long accountKey = node.accountKey;
            final long dialogId = node.dialogId;
            // A chat that is gone holds nothing.
            state.forwardSourceGone(accountKey, dialogId);
            final boolean counted = node.isCounted();
            node.dialogId = 0;
            node.state = NodeState.DESTROYED;
            if (counted) {
                leave(node, accountKey, dialogId);
            }
        }
    }

    // ------------------------------------------------------------------ leaving and closing

    /**
     * The user genuinely left the dialog while its fragments remain in a stack: they stop counting,
     * normal Auto-lock applies, and whatever lost its authorization is closed once that is safe.
     */
    public void release(long accountKey, long dialogId) {
        final Open o = open.get(key(accountKey, dialogId));
        if (o == null) {
            return;
        }
        final List<Node> nodes = new ArrayList<>(o.nodes);
        // The user left: a forward the chat had started does not keep it any longer.
        state.forwardSourceGone(accountKey, dialogId);
        for (Node node : nodes) {
            if (node.isCounted()) {
                node.state = NodeState.LEFT;
                leave(node, accountKey, dialogId);
            }
        }
        // A fragment of a chat the user left does not wait in the stack to be revealed later, whatever
        // the Auto-lock interval: re-opening it goes through the gate (and needs no authentication
        // while the interval lasts).
        poster.post(() -> {
            for (Node node : nodes) {
                if (node.state == NodeState.LEFT) {
                    requireClose(node);
                }
            }
        });
    }

    /** A fragment that shows a locked protected dialog was found (the authorization ended, a manual lock, the app came back). */
    public void closeLocked(Node node, Closer closer) {
        node.closer = closer;
        node.pendingClose = true;
        retryClose(node);
    }

    /** Never from inside the call that resumed the fragment: that call is part of a transition. */
    private void requireClose(Node node) {
        node.pendingClose = true;
        poster.post(() -> retryClose(node));
    }

    private void retryClose(Node node) {
        if (!node.pendingClose || node.finished) {
            node.pendingClose = false;
            return;
        }
        if (node.state != NodeState.LEFT && node.dialogId != 0 && !state.isLockedProtected(node.accountKey, node.dialogId)) {
            node.pendingClose = false;     // authorized again in the meantime
            return;
        }
        if (node.closer == null || node.closer.tryClose()) {
            node.pendingClose = false;
        }
    }

    // ------------------------------------------------------------------ forward transaction

    /**
     * A forward picker is being presented over {@code below}. Whatever screen that is (a chat, a
     * profile or its shared media, the chat behind a viewer), if it shows a protected dialog that is
     * authorized and visible right now, the forward is a transaction of it (see
     * {@link ProtectedChatsState.ForwardPhase}). The picker remembers the screen it belongs to.
     */
    public boolean pickerPresented(Node picker, Node below) {
        if (below == null || below.dialogId == 0 || picker == below) {
            return false;
        }
        if (state.beginForwardHoldOver(below.accountKey, below.dialogId, below.isVisible())) {
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
        } else if (source.isVisible()) {
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
