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
        if (dialogId == 0 || fragment.protectedGateDialogId != 0 || !ProtectedChats.isProtected(fragment.getCurrentAccount(), dialogId)) {
            return;
        }
        fragment.protectedGateDialogId = dialogId;
        fragment.protectedGateAccount = fragment.getCurrentAccount();
        fragment.protectedGateVisible = true;
        ProtectedChats.enter(fragment.protectedGateAccount, dialogId);
    }

    /**
     * A covered fragment (another chat, a sheet, the app in the background) is not "being used":
     * the re-lock countdown of its dialog starts once no fragment of it is visible.
     */
    public static void onFragmentPaused(BaseFragment fragment) {
        if (fragment.protectedGateDialogId != 0 && fragment.protectedGateVisible) {
            fragment.protectedGateVisible = false;
            ProtectedChats.leave(fragment.protectedGateAccount, fragment.protectedGateDialogId);
        }
    }

    /** Back on screen: if the authorization ended while it was covered, it must not show again. */
    public static void onFragmentResumed(BaseFragment fragment) {
        if (fragment.protectedGateDialogId == 0 || fragment.protectedGateVisible) {
            return;
        }
        final int account = fragment.protectedGateAccount;
        final long dialogId = fragment.protectedGateDialogId;
        // Back on screen: the forward picker it opened over itself is gone. If it did not deposit
        // into Saved Messages the cover counts as leaving, exactly as without the picker.
        ProtectedChats.forwardPickerClosed(account, dialogId);
        if (ProtectedChats.isLockedProtected(account, dialogId)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!fragment.isFinished && ProtectedChats.isLockedProtected(account, dialogId)) {
                    fragment.removeSelfFromStack();
                }
            });
            return;
        }
        fragment.protectedGateVisible = true;
        ProtectedChats.enter(account, dialogId);
    }

    public static void onFragmentDestroyed(BaseFragment fragment) {
        if (fragment.protectedGateDialogId != 0) {
            long dialogId = fragment.protectedGateDialogId;
            // A chat that is gone holds nothing.
            ProtectedChats.forwardPickerClosed(fragment.protectedGateAccount, dialogId);
            ProtectedChats.forwardCompletionEnded(fragment.protectedGateAccount, dialogId);
            fragment.protectedGateDialogId = 0;
            if (fragment.protectedGateVisible) {
                fragment.protectedGateVisible = false;
                ProtectedChats.leave(fragment.protectedGateAccount, dialogId);
            }
        }
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

    // ------------------------------------------------------------------ forward hold

    /**
     * A forward picker is being presented over {@code below}. Whatever screen that is (a chat, a
     * profile or its shared media, the chat behind a viewer), if it shows a protected dialog that is
     * authorized and open right now, that authorization is held while the picker is on top (see
     * ProtectedChatsState.beginForwardHold). The picker remembers the source it belongs to.
     */
    public static void onForwardPickerPresented(BaseFragment picker, BaseFragment below) {
        if (!(picker instanceof DialogsActivity) || !((DialogsActivity) picker).isForwardPicker() || below == null) {
            return;
        }
        if (ProtectedChats.beginForwardHoldOver(below.protectedGateAccount, below.protectedGateDialogId, below.protectedGateVisible)) {
            ((DialogsActivity) picker).setProtectedForwardSource(below);
        }
    }

    /** The picker is gone (or never opened) without a deposit into Saved Messages. */
    public static void forwardPickerClosed(BaseFragment source) {
        if (source != null && source.protectedGateDialogId != 0) {
            ProtectedChats.forwardPickerClosed(source.protectedGateAccount, source.protectedGateDialogId);
        }
    }

    /** The picker hands its selection over; Telegram may return to the source and show its success interaction. */
    public static void forwardReturnsToSource(BaseFragment source) {
        if (source != null && source.protectedGateDialogId != 0) {
            ProtectedChats.forwardReturnsToSource(source.protectedGateAccount, source.protectedGateDialogId);
        }
    }

    /** The delegate has handled the selection (or not): see ProtectedChatsState.forwardSettled. */
    public static void forwardSettled(BaseFragment source, boolean handled) {
        if (source != null && source.protectedGateDialogId != 0) {
            ProtectedChats.forwardSettled(source.protectedGateAccount, source.protectedGateDialogId, handled, source.protectedGateVisible);
        }
    }

    /** Telegram's success and tag interaction ended. */
    public static void forwardCompletionEnded(BaseFragment source) {
        if (source != null && source.protectedGateDialogId != 0) {
            ProtectedChats.forwardCompletionEnded(source.protectedGateAccount, source.protectedGateDialogId);
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
     * Pops every fragment of the given layouts that shows a protected dialog which is no longer
     * authorized (authorization expired, manual re-lock, app returned from background).
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
            for (int i = stack.size() - 1; i >= 0; i--) {
                BaseFragment fragment = stack.get(i);
                if (isLocked(fragment)) {
                    if (i == stack.size() - 1) {
                        fragment.finishFragment(false);
                    } else {
                        fragment.removeSelfFromStack();
                    }
                }
            }
        }
    }
}
