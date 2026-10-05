package org.telegram.ui;

import android.app.Activity;
import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.ForwardDestinations;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.ProtectedChats;
import org.telegram.messenger.ProtectedDialogIds;
import org.telegram.messenger.ProtectedChatsState;
import org.telegram.messenger.ProtectedGateLifecycle;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.ProtectedChatAuthSheet;

import java.util.ArrayList;

/**
 * Single enforcement point for protected chats.
 *
 * Every navigation layout funnels fragment creation through
 * {@link org.telegram.ui.ActionBar.ActionBarLayout#presentFragment} /
 * {@code addFragmentToStack} (chat list rows, search, notifications, deep links, message and
 * pinned-message links, forwards/replies, profile/media, tablet panes, bubbles, restored state),
 * and sheets go through {@link BaseFragment#showAsSheet}. All of them ask {@link #block} before the
 * fragment is created, so a locked protected chat can not become visible without passing the
 * chat-specific authentication sheet. {@link #closeLockedFragments} complements this for chats
 * that were open when their authorization expired or was revoked.
 */
public final class ProtectedChatGate {

    private ProtectedChatGate() {
    }

    /** Dialog whose content the fragment shows, or 0 when the fragment is not about one dialog. */
    public static long getDialogId(BaseFragment fragment) {
        if (fragment == null) {
            return 0;
        }
        if (fragment instanceof ChatActivity) {
            return dialogIdFromArgs(fragment.getArguments(), false);
        }
        if (fragment instanceof ChatLockSettingsActivity) {
            // the chat's own lock settings are as much part of the chat as its profile
            return ((ChatLockSettingsActivity) fragment).getLockDialogId();
        }
        if (fragment instanceof TopicsFragment || fragment instanceof ProfileActivity || fragment instanceof ProfileActivity2) {
            long dialogId = dialogIdFromArgs(fragment.getArguments(), true);
            // The own profile is an account page (also hosted as a main tab that never goes through a
            // navigation layout). It is not gated; ProfileActivity itself hides the Saved Messages
            // media section while Saved Messages is locked.
            if (dialogId != 0 && fragment instanceof ProfileActivity && dialogId == UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId()) {
                return 0;
            }
            return dialogId;
        }
        return 0;
    }

    public static long dialogIdFromArgs(Bundle args, boolean allowDialogIdKey) {
        if (args == null) {
            return 0;
        }
        return ProtectedDialogIds.fromArgs(args.getLong("user_id", 0), args.getLong("chat_id", 0), args.getInt("enc_id", 0), args.getLong("dialog_id", 0), allowDialogIdKey);
    }

    /** The fragment passed the gate and is now part of a navigation stack. */
    public static void onFragmentCreated(BaseFragment fragment) {
        long dialogId = getDialogId(fragment);
        if (dialogId == 0 || fragment.protectedGate.isRegistered() || !ProtectedChats.isProtected(fragment.getCurrentAccount(), dialogId)) {
            return;
        }
        ProtectedChats.lifecycle().created(fragment.protectedGate, ProtectedChats.accountKey(fragment.getCurrentAccount()), dialogId, () -> tryClose(fragment));
    }

    /**
     * Covered by a child fragment, or paused together with the activity (a system permission dialog is
     * not navigation): neither is the user leaving the chat. See {@link ProtectedGateLifecycle}.
     */
    public static void onFragmentPaused(BaseFragment fragment) {
        if (!fragment.protectedGate.isIdle()) {
            ProtectedChats.lifecycle().paused(fragment.protectedGate);
        }
    }

    /** Back on screen. Nothing is evaluated or closed from inside the call that resumes a fragment. */
    public static void onFragmentResumed(BaseFragment fragment) {
        if (fragment.protectedGate.isRegistered()) {
            ProtectedChats.lifecycle().resumed(fragment.protectedGate);
        }
    }

    /** The layout finished the transition this fragment took part in (fully visible or fully hidden). */
    public static void onFragmentSettled(BaseFragment fragment) {
        if (!fragment.protectedGate.isIdle()) {
            ProtectedChats.lifecycle().transitionSettled(fragment.protectedGate);
        }
    }

    public static void onFragmentDestroyed(BaseFragment fragment) {
        if (!fragment.protectedGate.isIdle()) {
            ProtectedChats.lifecycle().destroyed(fragment.protectedGate);
        }
    }

    /**
     * The navigation layout is forwarding the host activity's own pause or resume to its last
     * fragment between {@code hostLifecycle(true)} and {@code hostLifecycle(false)}.
     */
    public static void hostLifecycle(boolean active) {
        ProtectedChats.lifecycle().hostLifecycle(active);
    }

    /**
     * A fragment is being presented over {@code below} (called by the navigation layout for every
     * presented fragment; it does nothing unless {@code below} belongs to a protected chat's
     * context). The new fragment becomes a child of that chat: the chat is covered, not left,
     * unless the child opens another conversation than the ones that belong to the chat's own
     * context. The forward picker additionally starts the forward transaction. This hook only
     * records who covers whom; every decision is made by the fragment lifecycle callbacks.
     */
    public static void onFragmentPresented(BaseFragment fragment, BaseFragment below, boolean removeLast) {
        if (below == null || !below.protectedGate.isInContext()) {
            return;
        }
        final boolean forwardPicker = fragment instanceof DialogsActivity && ((DialogsActivity) fragment).isForwardPicker();
        ProtectedChats.lifecycle().presented(fragment.protectedGate, conversationDialogId(fragment), below.protectedGate, removeLast, forwardPicker);
    }

    /**
     * Protects a chat from its own lock settings, which are on top of the chat's profile and chat: the
     * user just proved the passcode, so the chat stays authorized as long as it is open and its
     * Auto-lock counts from when it is left (see {@link ProtectedGateLifecycle#protectOpen}).
     */
    public static ProtectedChatsState.Result protectWhileOpen(BaseFragment from, long dialogId, ProtectedChatsState.AuthProof proof) {
        final ArrayList<ProtectedGateLifecycle.StackEntry> entries = new ArrayList<>();
        final INavigationLayout layout = from.getParentLayout();
        if (layout != null && layout.getFragmentStack() != null) {
            for (BaseFragment fragment : new ArrayList<>(layout.getFragmentStack())) {
                entries.add(new ProtectedGateLifecycle.StackEntry(fragment.protectedGate, getDialogId(fragment), () -> tryClose(fragment)));
            }
        }
        return ProtectedChats.protectOpen(from.getCurrentAccount(), dialogId, proof, entries);
    }

    /** The dialog a fragment shows as a conversation (a chat or a profile), or 0 for any other screen. */
    private static long conversationDialogId(BaseFragment fragment) {
        if (fragment instanceof ChatActivity || fragment instanceof ProfileActivity || fragment instanceof ProfileActivity2) {
            return getDialogId(fragment);
        }
        return 0;
    }

    /**
     * Closes a fragment whose authorization ended, without ever cutting a transition short:
     * {@code ActionBarLayout.removeFragmentFromStack} completes a running transition first and
     * {@code closeLastFragment} refuses to run during one. Returns false when the fragment is the top
     * or second fragment of a layout that is in a transition (or being swiped); the gate then retries
     * when that transition ended.
     */
    private static boolean tryClose(BaseFragment fragment) {
        if (fragment.isFinished) {
            return true;
        }
        final INavigationLayout layout = fragment.getParentLayout();
        if (layout == null || layout.getFragmentStack() == null) {
            return true;
        }
        final java.util.List<BaseFragment> stack = layout.getFragmentStack();
        final int index = stack.indexOf(fragment);
        if (index < 0) {
            return true;
        }
        if (index >= stack.size() - 2 && (layout.isTransitionAnimationInProgress() || layout.isSwipeInProgress())) {
            return false;
        }
        if (index == stack.size() - 1 && stack.size() > 1) {
            // Closing the top fragment reveals the one under it; a locked one there would need its own
            // authentication (see blockReveal) and the top fragment could not be closed meanwhile, so
            // those go first. They are not on screen: removing them is silent.
            for (int below = index - 1; below >= 0 && isLocked(stack.get(below)); below--) {
                stack.get(below).removeSelfFromStack();
            }
            fragment.finishFragment(false);
        } else {
            fragment.removeSelfFromStack();
        }
        return true;
    }

    public static boolean isLocked(BaseFragment fragment) {
        long dialogId = getDialogId(fragment);
        return dialogId != 0 && ProtectedChats.isLockedProtected(fragment.getCurrentAccount(), dialogId);
    }

    /**
     * @return true if the fragment must not be created now. The authentication sheet is shown and
     * {@code onUnlocked} runs after a successful authentication (never on dismissal).
     */
    public static boolean block(BaseFragment fragment, Activity activity, Runnable onUnlocked) {
        final long dialogId = getDialogId(fragment);
        if (dialogId == 0) {
            return false;
        }
        final int account = fragment.getCurrentAccount();
        if (!ProtectedChats.isLockedProtected(account, dialogId)) {
            return false;
        }
        if (activity == null) {
            // No way to ask: stay closed (secure failure).
            return true;
        }
        if (ProtectedChatAuthSheet.isShowing()) {
            return true;
        }
        AndroidUtilities.runOnUIThread(() -> authenticate(activity, null, account, dialogId, ProtectedChatAuthSheet.Mode.UNLOCK, () -> {
            if (onUnlocked != null) {
                onUnlocked.run();
            }
        }));
        return true;
    }

    /**
     * Destination rule of a forward or a share (see {@link ForwardDestinations}). If a destination
     * is protected and locked the authentication sheet is shown and true is returned: the caller
     * stops, and {@code resume} (the original operation with its original arguments) runs once
     * after a successful unlock, never after a cancel. False: nothing is locked and the caller
     * continues inline.
     *
     * @param ownSavedMessagesExempt a forward never asks to deposit into the user's own Saved
     *                               Messages (it neither unlocks nor opens it); a share does, since
     *                               it opens the chat it lands in.
     */
    public static boolean holdForDestinations(Activity activity, int account, java.util.List<MessagesStorage.TopicKey> dids, boolean ownSavedMessagesExempt, Runnable resume) {
        final java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < dids.size(); i++) {
            ids.add(dids.get(i).dialogId);
        }
        final long own = UserConfig.getInstance(account).getClientUserId();
        final ForwardDestinations.Locks locks = dialogId -> ProtectedChats.isLockedProtected(account, dialogId);
        if (activity == null) {
            // No way to ask: a locked destination stays closed (secure failure).
            return ForwardDestinations.firstLocked(ids, own, ownSavedMessagesExempt, locks) != 0;
        }
        return ForwardDestinations.holdUntilUnlocked(ids, own, ownSavedMessagesExempt, locks, (dialogId, onUnlocked, onCancelled) ->
                AndroidUtilities.runOnUIThread(() -> authenticate(activity, null, account, dialogId, ProtectedChatAuthSheet.Mode.UNLOCK, onUnlocked, onCancelled)), resume);
    }

    // ------------------------------------------------------------------ forward transaction

    /** What the picker remembers between handing its selection over and the delegate returning. */
    public static final class Handover {
        private final ProtectedGateLifecycle.Node source;
        private final INavigationLayout layout;

        private Handover(ProtectedGateLifecycle.Node source, INavigationLayout layout) {
            this.source = source;
            this.layout = layout;
        }
    }

    /** The picker hands its selection to the delegate. Null when the picker tracks no protected source. */
    public static Handover forwardHandOver(BaseFragment picker) {
        if (!picker.protectedGate.hasForwardSource()) {
            return null;
        }
        final ProtectedGateLifecycle.Node source = ProtectedChats.lifecycle().handOver(picker.protectedGate);
        return source == null ? null : new Handover(source, picker.getParentLayout());
    }

    /** The delegate returned (handled or not): the forward continues in the phase that matches. */
    public static void forwardSettled(BaseFragment picker, Handover handover, boolean handled) {
        if (handover == null) {
            return;
        }
        final BaseFragment top = handover.layout != null ? handover.layout.getLastFragment() : null;
        ProtectedChats.lifecycle().settled(picker.protectedGate, handover.source, handled, top != null ? top.protectedGate : null);
    }

    /** Telegram's success and tag interaction ended. */
    public static void forwardCompletionEnded(BaseFragment source) {
        if (source.protectedGate.isRegistered()) {
            ProtectedChats.lifecycle().completionEnded(source.protectedGate);
        }
    }

    /** Shows the floating prompt for a dialog and runs {@code onSuccess} after the matching state transition. */
    public static void authenticate(Activity activity, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider, int account, long dialogId, ProtectedChatAuthSheet.Mode mode, Runnable onSuccess) {
        authenticate(activity, resourcesProvider, account, dialogId, mode, onSuccess, null);
    }

    /** Like above; {@code onCancelled} runs when the prompt was dismissed without authenticating. */
    public static void authenticate(Activity activity, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider, int account, long dialogId, ProtectedChatAuthSheet.Mode mode, Runnable onSuccess, Runnable onCancelled) {
        ProtectedChatAuthSheet.show(activity, resourcesProvider, mode, getTitle(account, dialogId), new ProtectedChatAuthSheet.Callback() {
            @Override
            public void onCancelled() {
                if (onCancelled != null) {
                    onCancelled.run();
                }
            }

            @Override
            public void onAuthenticated(ProtectedChatsState.AuthProof proof) {
                ProtectedChatsState.Result result;
                switch (mode) {
                    case PROTECT:
                        result = ProtectedChats.protect(account, dialogId, proof);
                        break;
                    case UNPROTECT:
                        result = ProtectedChats.unprotect(account, dialogId, proof);
                        break;
                    default:
                        result = ProtectedChats.unlock(account, dialogId, proof);
                        break;
                }
                if (result == ProtectedChatsState.Result.OK) {
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                } else if (onCancelled != null) {
                    onCancelled.run();
                }
            }
        });
    }

    public static CharSequence getTitle(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat encryptedChat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            TLRPC.User user = encryptedChat != null ? controller.getUser(encryptedChat.user_id) : null;
            return user != null ? UserObject.getUserName(user) : LocaleController.getString(R.string.AppName);
        } else if (dialogId > 0) {
            if (dialogId == UserConfig.getInstance(account).getClientUserId()) {
                return LocaleController.getString(R.string.SavedMessages);
            }
            TLRPC.User user = controller.getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : "";
        } else {
            TLRPC.Chat chat = controller.getChat(-dialogId);
            return chat != null ? chat.title : "";
        }
    }

    /**
     * Gate before reveal. Back ({@code ActionBarLayout.closeLastFragment}: system back, toolbar back,
     * {@code finishFragment}), a swipe back and the start of a predictive back are about to reveal
     * {@code previous}, the fragment under {@code current}. If that is a protected chat that is locked
     * right now, the operation does not start: the existing authentication sheet is shown instead and
     * {@code proceed} (the original operation) runs once after a successful authentication, if the two
     * fragments are still on top of the stack. A cancel leaves the user on {@code current}; nothing is
     * closed and nothing of the protected chat has been shown.
     *
     * @return true when the caller must not start the operation
     */
    public static boolean blockReveal(INavigationLayout layout, BaseFragment current, BaseFragment previous, Activity activity, Runnable proceed) {
        final long dialogId = getDialogId(previous);
        if (dialogId == 0) {
            return false;
        }
        final int account = previous.getCurrentAccount();
        final ProtectedGateLifecycle.Authenticator auth = activity == null ? null : new ProtectedGateLifecycle.Authenticator() {
            @Override
            public boolean isShowing() {
                return ProtectedChatAuthSheet.isShowing();
            }

            @Override
            public void ask(long accountKey, long dialog, Runnable onUnlocked, Runnable onCancelled) {
                authenticate(activity, null, account, dialog, ProtectedChatAuthSheet.Mode.UNLOCK, onUnlocked, onCancelled);
            }
        };
        final boolean blocked = ProtectedChats.lifecycle().blockReveal(ProtectedChats.accountKey(account), dialogId, auth,
                () -> isStillOnTop(layout, current, previous), proceed);
        if (blocked) {
            // BaseFragment.finishFragment marked the fragment as finishing before asking the layout to close it
            current.setFinishing(false);
        }
        return blocked;
    }

    private static boolean isStillOnTop(INavigationLayout layout, BaseFragment current, BaseFragment previous) {
        final java.util.List<BaseFragment> stack = layout.getFragmentStack();
        final int size = stack == null ? 0 : stack.size();
        return size >= 2 && stack.get(size - 1) == current && stack.get(size - 2) == previous && !current.isFinished && !previous.isFinished;
    }

    /**
     * Whether Back would reveal a protected chat that is locked right now (the predictive back
     * preview asks this before it shows anything of the fragment under the top one).
     */
    public static boolean revealsLockedChat(BaseFragment previous) {
        final long dialogId = getDialogId(previous);
        return dialogId != 0 && ProtectedChats.lifecycle().revealsLockedChat(ProtectedChats.accountKey(previous.getCurrentAccount()), dialogId);
    }

    /**
     * Closes what lost its authorization and is on screen or in the way, after the app came back from
     * the background or a manual lock: the top fragment of each layout when it shows a locked protected
     * dialog, together with the locked fragments directly under it. A locked fragment that is covered
     * by something that is not locked stays where it is; it is not visible, and whatever reveals it
     * is gated (see {@link #blockReveal}): the user is asked to authenticate instead of losing the
     * chat. The top fragment, or one involved in a running transition, is closed once that
     * transition ended (never by cutting it short).
     */
    public static void closeLockedFragments(java.util.List<INavigationLayout> layouts) {
        for (INavigationLayout layout : layouts) {
            if (layout == null) {
                continue;
            }
            if (layout.getFragmentStack() == null) {
                continue;
            }
            ArrayList<BaseFragment> stack = new ArrayList<>(layout.getFragmentStack());
            int bottomOfRun = stack.size();
            while (bottomOfRun > 0 && isLocked(stack.get(bottomOfRun - 1))) {
                bottomOfRun--;
            }
            // from the bottom of the locked run up, so that closing the top never reveals a locked one
            for (int i = bottomOfRun; i < stack.size(); i++) {
                final BaseFragment fragment = stack.get(i);
                ProtectedChats.lifecycle().closeLocked(fragment.protectedGate, () -> tryClose(fragment));
            }
        }
    }
}
