package org.telegram.ui;

import android.app.Activity;
import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
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
            // The own profile page is account settings, not the Saved Messages conversation.
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
        ProtectedChats.enter(fragment.protectedGateAccount, dialogId);
    }

    public static void onFragmentDestroyed(BaseFragment fragment) {
        if (fragment.protectedGateDialogId != 0) {
            long dialogId = fragment.protectedGateDialogId;
            fragment.protectedGateDialogId = 0;
            ProtectedChats.leave(fragment.protectedGateAccount, dialogId);
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

    /** Shows the floating prompt for a dialog and runs {@code onSuccess} after the matching state transition. */
    public static void authenticate(Activity activity, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider, int account, long dialogId, ProtectedChatAuthSheet.Mode mode, Runnable onSuccess) {
        ProtectedChatAuthSheet.show(activity, resourcesProvider, mode, getTitle(account, dialogId), new ProtectedChatAuthSheet.Callback() {
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
                if (result == ProtectedChatsState.Result.OK && onSuccess != null) {
                    onSuccess.run();
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
