package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for the part of Telegram that drives the protected-chat gate: the navigation layout
 * ({@code ActionBarLayout}) and the host activity ({@code LaunchActivity}), making the calls they
 * make, in their order, into the real {@link ProtectedGateLifecycle} and {@link ProtectedChatsState}.
 *
 * <ul>
 *   <li>presentFragment: the fragment is created and gated, the hook tells the gate what it is
 *       presented over, it is added and resumed at once; the old top is told it is fully hidden,
 *       paused (and destroyed with removeLast) when the transition ends;</li>
 *   <li>closeLastFragment (system back, toolbar back, a fragment finishing itself): the fragment
 *       below is resumed at once; the closing fragment stays in the stack, then is paused and
 *       destroyed when the transition ends, and the one below is fully visible;</li>
 *   <li>swipe and predictive back: the fragment below is resumed when the gesture starts; a
 *       cancelled gesture pauses it again, a completed one pauses and destroys the top;</li>
 *   <li>removeFragmentFromStack: completes a running transition first (counted as a forced end),
 *       then pops; the gate must never make it do that;</li>
 *   <li>the activity: onPause forwards a pause to the top fragment inside the host-lifecycle window,
 *       onStop is the app-background boundary, onResume applies the foreground rules, closes what
 *       lost its authorization and resumes the top fragment (the order of LaunchActivity).</li>
 * </ul>
 *
 * Posted work (the countdown decision after a leave, closing something that lost its authorization)
 * runs when a test calls {@link #idle()}, which tests do between the start and the end of a
 * transition too, because that is when it ran on the device.
 *
 * Tests run with Immediate Auto-lock, the setting that made the regressions visible, unless they
 * change it.
 */
public abstract class GateNavigationTestBase {

    protected static final long ACC = 1L;
    protected static final long SOURCE = 2000L;       // the protected chat the scenarios start from (A)
    protected static final long SAVED = 1000L;        // the user's own Saved Messages, protected
    protected static final long PLAIN = 3000L;        // an unprotected chat
    protected static final long PLAIN_2 = 3500L;      // another unprotected chat
    protected static final long PROT_B = 4000L;       // another protected chat
    protected static final long PROT_C = -5000L;      // another protected chat

    protected long now;
    protected ProtectedChatsState state;
    protected ProtectedGateLifecycle gate;
    protected final ArrayDeque<Runnable> posted = new ArrayDeque<>();
    protected Layout layout;
    protected Frag list;
    private Map<String, String> storage;

    private ProtectedChatsState newState() {
        return new ProtectedChatsState(new ProtectedChatsState.Storage() {
            public String get(String key) {
                return storage.get(key);
            }

            public void put(String key, String value) {
                storage.put(key, value);
            }

            public void remove(String key) {
                storage.remove(key);
            }
        }, () -> now, new ProtectedChatsState.Credential() {
            public boolean hasCredential() {
                return true;
            }

            public ProtectedChatsState.Verification verify(String secret) {
                return "1234".equals(secret) ? ProtectedChatsState.Verification.OK : ProtectedChatsState.Verification.WRONG;
            }

            public boolean biometricAvailable() {
                return false;
            }
        });
    }

    @Before
    public void setUp() {
        now = 100_000;
        storage = new HashMap<>();
        posted.clear();
        state = newState();
        assertEquals(ProtectedChatsState.Result.OK, state.enableFeature());
        assertTrue(state.setRelockSeconds(ProtectedChatsState.RELOCK_IMMEDIATELY));
        startProcess();
    }

    /** The app process and its activity start: an empty navigation layout with the chat list. */
    protected void startProcess() {
        gate = new ProtectedGateLifecycle(state, posted::add);
        layout = new Layout();
        list = new Frag("list", 0, false);
        layout.present(list, false, false);
        idle();
    }

    /** The process died and started again: protections persist, authorizations do not. */
    protected void restartProcess() {
        posted.clear();
        state = newState();
        startProcess();
    }

    // ------------------------------------------------------------------ the navigation simulator

    /** A fragment as the layout calls it. */
    protected final class Frag {
        public final String name;
        public final long dialog;
        public final boolean picker;
        public final ProtectedGateLifecycle.Node node = new ProtectedGateLifecycle.Node();
        Layout parent;
        public boolean destroyed;

        public Frag(String name, long dialog, boolean picker) {
            this.name = name;
            this.dialog = dialog;
            this.picker = picker;
        }

        // BaseFragment.onResume / onPause / onFragmentDestroy / onBecomeFullyVisible|Hidden through ProtectedChatGate
        void onResume() {
            if (node.isRegistered()) {
                gate.resumed(node);
            }
        }

        void onPause() {
            if (!node.isIdle()) {
                gate.paused(node);
            }
        }

        void onDestroy() {
            destroyed = true;
            if (!node.isIdle()) {
                gate.destroyed(node);
            }
        }

        void onSettled() {
            if (!node.isIdle()) {
                gate.transitionSettled(node);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    protected final class Layout {
        public final List<Frag> stack = new ArrayList<>();
        /** The end of the running transition. */
        Runnable transitionEnd;
        /** Names of fragments the gate took off the stack. */
        public final List<String> removedByGate = new ArrayList<>();
        /** How often a running transition was completed early (what removeFragmentFromStack does). */
        public int forcedTransitionEnds;
        Frag swipePrevious;

        public Frag top() {
            return stack.isEmpty() ? null : stack.get(stack.size() - 1);
        }

        public Frag below() {
            return stack.size() < 2 ? null : stack.get(stack.size() - 2);
        }

        public boolean inTransition() {
            return transitionEnd != null || swipePrevious != null;
        }

        /** ActionBarLayout.presentFragment. */
        public boolean present(Frag f, boolean removeLast, boolean animated) {
            // ProtectedChatGate.block: a locked protected chat is not created
            if (f.dialog != 0 && state.isLockedProtected(ACC, f.dialog)) {
                return false;
            }
            if (transitionEnd != null) {
                return false;
            }
            // ProtectedChatGate.onFragmentCreated
            if (f.dialog != 0 && state.isProtected(ACC, f.dialog)) {
                gate.created(f.node, ACC, f.dialog, () -> tryClose(f));
            }
            final Frag current = top();
            // ProtectedChatGate.onFragmentPresented
            if (current != null && current.node.isInContext()) {
                gate.presented(f.node, f.dialog, current.node, removeLast, f.picker);
            }
            f.parent = this;
            stack.add(f);
            f.onResume();
            final Runnable end = () -> {
                if (current != null) {
                    current.onSettled();      // onBecomeFullyHidden
                    current.onPause();
                    if (removeLast) {
                        current.onDestroy();
                        current.parent = null;
                        stack.remove(current);
                    }
                }
                f.onSettled();                // onBecomeFullyVisible
            };
            if (animated) {
                transitionEnd = end;
            } else {
                end.run();
            }
            return true;
        }

        /** ActionBarLayout.closeLastFragment: system back, toolbar back, a fragment finishing itself. */
        public boolean closeLast(boolean animated) {
            if (inTransition() || stack.isEmpty()) {
                return false;
            }
            final Frag current = top();
            final Frag previous = below();
            if (previous != null) {
                previous.onResume();
            }
            final Runnable end = () -> {
                current.onPause();
                current.onDestroy();
                current.parent = null;
                stack.remove(current);
                if (previous != null) {
                    previous.onSettled();     // onBecomeFullyVisible
                }
            };
            if (animated) {
                transitionEnd = end;
            } else {
                end.run();
            }
            return true;
        }

        public void transitionEnds() {
            final Runnable end = transitionEnd;
            transitionEnd = null;
            if (end != null) {
                end.run();
            }
        }

        /** prepareForMoving (swipe, predictive back start): the previous fragment is resumed when the gesture starts. */
        public void swipeStart() {
            swipePrevious = below();
            swipePrevious.onResume();
        }

        /** onSlideAnimationEnd(backAnimation = true): the gesture was given up. */
        public void swipeCancel() {
            final Frag previous = swipePrevious;
            swipePrevious = null;
            previous.onPause();
        }

        /** onSlideAnimationEnd(backAnimation = false): the gesture completed. */
        public void swipeComplete() {
            swipePrevious = null;
            final Frag current = top();
            current.onPause();
            current.onDestroy();
            current.parent = null;
            stack.remove(current);
            top().onResume();
            top().onSettled();
        }

        /** ActionBarLayout.removeFragmentFromStack: completes a running transition first, then pops. */
        public void removeFragmentFromStack(Frag f) {
            if (f.destroyed || f.parent == null) {
                return;
            }
            final int size = stack.size();
            if (size > 0 && stack.get(size - 1) == f || size > 1 && stack.get(size - 2) == f) {
                if (transitionEnd != null) {
                    forcedTransitionEnds++;
                    transitionEnds();
                }
            }
            if (top() == f && stack.size() > 1) {
                closeLast(true);
            } else {
                f.onPause();
                f.onDestroy();
                f.parent = null;
                stack.remove(f);
            }
        }

        /** ProtectedChatGate.tryClose. */
        public boolean tryClose(Frag f) {
            if (f.destroyed || f.parent == null) {
                return true;
            }
            final int index = stack.indexOf(f);
            if (index < 0) {
                return true;
            }
            if (index >= stack.size() - 2 && inTransition()) {
                return false;
            }
            removedByGate.add(f.name);
            if (index == stack.size() - 1 && stack.size() > 1) {
                closeLast(false);
            } else {
                removeFragmentFromStack(f);
            }
            return true;
        }

        public String names() {
            final StringBuilder sb = new StringBuilder();
            for (Frag f : stack) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(f.name);
            }
            return sb.toString();
        }
    }

    /** The message loop gets to the work that was posted (and what that posts in turn). */
    protected void idle() {
        int guard = 0;
        while (!posted.isEmpty()) {
            posted.poll().run();
            assertTrue("posted work does not loop", ++guard < 1000);
        }
    }

    /** A transition that ran its course. */
    protected void settle() {
        idle();
        layout.transitionEnds();
        idle();
    }

    // ------------------------------------------------------------------ the host activity

    /** LaunchActivity.onPause: the top fragment gets the activity's pause inside the host window. */
    protected void activityPaused() {
        gate.hostLifecycle(true);
        try {
            if (layout.top() != null) {
                layout.top().onPause();
            }
        } finally {
            gate.hostLifecycle(false);
        }
        idle();
    }

    /** LaunchActivity.onStop (and the screen turning off): the app is in the background. */
    protected void activityStopped() {
        gate.appBackgrounded();
        idle();
    }

    /**
     * LaunchActivity.onResume: the foreground rules, then what lost its authorization is closed,
     * then the top fragment resumes inside the host window.
     */
    protected void activityResumed() {
        gate.appForegrounded();
        closeLockedFragments();
        idle();
        gate.hostLifecycle(true);
        try {
            if (layout.top() != null) {
                layout.top().onResume();
            }
        } finally {
            gate.hostLifecycle(false);
        }
        idle();
    }

    /** ProtectedChatGate.closeLockedFragments, as LaunchActivity.closeLockedProtectedChats runs it. */
    protected void closeLockedFragments() {
        final List<Frag> copy = new ArrayList<>(layout.stack);
        Collections.reverse(copy);
        for (Frag f : copy) {
            if (f.dialog != 0 && state.isLockedProtected(ACC, f.dialog)) {
                gate.closeLocked(f.node, () -> layout.tryClose(f));
            }
        }
    }

    /** ProtectedChats.relock: the state changes, protectedChatsChanged closes what is locked. */
    protected void manualLock(long dialog) {
        state.relock(ACC, dialog);
        idle();
        closeLockedFragments();
        idle();
    }

    // ------------------------------------------------------------------ helpers

    protected void protect(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.protect(ACC, dialog, state.proofFromPasscode("1234", null)));
    }

    protected void authenticate(long dialog) {
        assertEquals(ProtectedChatsState.Result.OK, state.unlock(ACC, dialog, state.proofFromPasscode("1234", null)));
    }

    protected boolean locked(long dialog) {
        return state.isLockedProtected(ACC, dialog);
    }

    /** No fragment was taken off the stack by the gate and no transition was cut short. */
    protected void assertNothingWasPopped() {
        assertEquals("the gate closed no chat", Collections.emptyList(), layout.removedByGate);
        assertEquals("no transition was cut short", 0, layout.forcedTransitionEnds);
    }

    /** The protected chat A, authenticated and opened over the chat list. */
    protected Frag openSource() {
        protect(SOURCE);
        authenticate(SOURCE);
        final Frag a = new Frag("A", SOURCE, false);
        assertTrue(layout.present(a, false, true));
        settle();
        assertEquals("list, A", layout.names());
        org.junit.Assert.assertFalse(locked(SOURCE));
        return a;
    }
}
