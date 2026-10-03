package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Android binding of {@link ProtectedChatsState}: persistence in a dedicated preferences file,
 * account slot -> user id mapping, the shared passcode credential from {@link SharedConfig} and
 * change notifications.
 *
 * Architecture summary:
 *  - credential: {@link SharedConfig} (passcodeHash/passcodeSalt/passcodeType, bad-try throttling)
 *  - app-wide lock: {@link SharedConfig#isAppLockEnabled()} (credential + appLockEnabled flag)
 *  - per-chat protection: this class (set of protected dialogs per account user id)
 *
 * Supported dialogs: private chats (including bots and Saved Messages), basic groups,
 * supergroups, channels and secret chats. Folder pseudo-dialogs (archive, community rows) can not
 * be protected.
 */
public class ProtectedChats {

    private static final String PREFS_NAME = "protected_chats";
    private static ProtectedChatsState state;

    private static final ProtectedChatsState.Credential CREDENTIAL = new ProtectedChatsState.Credential() {
        @Override
        public boolean hasCredential() {
            return SharedConfig.hasPasscode();
        }

        @Override
        public ProtectedChatsState.Verification verify(String secret) {
            refreshRetryTimer();
            if (SharedConfig.passcodeRetryInMs > 0) {
                return ProtectedChatsState.Verification.THROTTLED;
            }
            if (secret != null && secret.length() > 0 && SharedConfig.checkPasscode(secret)) {
                SharedConfig.badPasscodeTries = 0;
                SharedConfig.saveConfig();
                return ProtectedChatsState.Verification.OK;
            }
            SharedConfig.increaseBadPasscodeTries();
            return ProtectedChatsState.Verification.WRONG;
        }

        @Override
        public boolean biometricAvailable() {
            return SharedConfig.useFingerprintLock;
        }
    };

    private static synchronized ProtectedChatsState state() {
        if (state == null) {
            final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            state = new ProtectedChatsState(new ProtectedChatsState.Storage() {
                @Override
                public String get(String key) {
                    return prefs.getString(key, null);
                }

                @Override
                public void put(String key, String value) {
                    prefs.edit().putString(key, value).apply();
                }

                @Override
                public void remove(String key) {
                    prefs.edit().remove(key).apply();
                }
            }, SystemClock::elapsedRealtime, CREDENTIAL);
        }
        return state;
    }

    /** Counts the shared retry delay down the same way the app lock screen does. */
    public static void refreshRetryTimer() {
        long currentTime = SystemClock.elapsedRealtime();
        if (SharedConfig.passcodeRetryInMs > 0 && currentTime > SharedConfig.lastUptimeMillis) {
            SharedConfig.passcodeRetryInMs -= (currentTime - SharedConfig.lastUptimeMillis);
            if (SharedConfig.passcodeRetryInMs < 0) {
                SharedConfig.passcodeRetryInMs = 0;
            }
        }
        SharedConfig.lastUptimeMillis = currentTime;
        SharedConfig.saveConfig();
    }

    public static ProtectedChatsState getState() {
        return state();
    }

    private static long accountKey(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.protectedChatsChanged));
    }

    public static boolean isSupportedDialog(long dialogId) {
        return dialogId != 0 && !DialogObject.isFolderDialogId(dialogId);
    }

    // ------------------------------------------------------------------ queries

    public static boolean isFeatureEnabled() {
        return state().isFeatureEnabled();
    }

    public static boolean isProtected(int account, long dialogId) {
        long key = accountKey(account);
        return key != 0 && state().isProtected(key, dialogId);
    }

    /** Cheap check for hot paths (dialog rows): false immediately when nothing is protected. */
    public static boolean shouldHideContent(int account, long dialogId) {
        ProtectedChatsState s = state();
        if (!s.isFeatureEnabled() || s.protectedCount() == 0) {
            return false;
        }
        long key = accountKey(account);
        return key != 0 && s.shouldHideContent(key, dialogId);
    }

    /** See {@link ProtectedChatsState#allowsExternalInteraction}: replies and buttons outside the opened chat. */
    public static boolean allowsExternalInteraction(int account, long dialogId) {
        ProtectedChatsState s = state();
        if (s.protectedCount() == 0) {
            return true;
        }
        long key = accountKey(account);
        return key == 0 || s.allowsExternalInteraction(key, dialogId);
    }

    public static boolean isLockedProtected(int account, long dialogId) {
        ProtectedChatsState s = state();
        if (!s.isFeatureEnabled() || s.protectedCount() == 0) {
            return false;
        }
        long key = accountKey(account);
        return key != 0 && s.isLockedProtected(key, dialogId);
    }

    public static boolean canManuallyRelock(int account, long dialogId) {
        long key = accountKey(account);
        return key != 0 && state().canManuallyRelock(key, dialogId);
    }

    /** Protected dialogs of the account (empty while the feature is off), for the management screen. */
    public static java.util.ArrayList<Long> getProtectedDialogs(int account) {
        long key = accountKey(account);
        if (key == 0 || !state().isFeatureEnabled()) {
            return new java.util.ArrayList<>();
        }
        return state().protectedDialogs(key);
    }

    public static boolean hasAnyProtectedChat() {
        return state().protectedCount() > 0;
    }

    // ------------------------------------------------------------------ transitions

    public static ProtectedChatsState.Result protect(int account, long dialogId, ProtectedChatsState.AuthProof proof) {
        if (!isSupportedDialog(dialogId)) {
            return ProtectedChatsState.Result.UNSUPPORTED;
        }
        long key = accountKey(account);
        if (key == 0) {
            return ProtectedChatsState.Result.UNSUPPORTED;
        }
        ProtectedChatsState.Result result = state().protect(key, dialogId, proof);
        if (result == ProtectedChatsState.Result.OK) {
            notifyChanged();
            refreshNotifications();
        }
        return result;
    }

    public static ProtectedChatsState.Result protectMany(int account, java.util.List<Long> dialogIds, ProtectedChatsState.AuthProof proof) {
        for (long id : dialogIds) {
            if (!isSupportedDialog(id)) {
                return ProtectedChatsState.Result.UNSUPPORTED;
            }
        }
        long key = accountKey(account);
        if (key == 0) {
            return ProtectedChatsState.Result.UNSUPPORTED;
        }
        ProtectedChatsState.Result result = state().protectMany(key, dialogIds, proof);
        if (result == ProtectedChatsState.Result.OK) {
            notifyChanged();
            refreshNotifications();
        }
        return result;
    }

    public static ProtectedChatsState.Result unprotectMany(int account, java.util.List<Long> dialogIds, ProtectedChatsState.AuthProof proof) {
        ProtectedChatsState.Result result = state().unprotectMany(accountKey(account), dialogIds, proof);
        if (result == ProtectedChatsState.Result.OK) {
            notifyChanged();
            refreshNotifications();
        }
        return result;
    }

    public static ProtectedChatsState.Result unprotect(int account, long dialogId, ProtectedChatsState.AuthProof proof) {
        ProtectedChatsState.Result result = state().unprotect(accountKey(account), dialogId, proof);
        if (result == ProtectedChatsState.Result.OK) {
            notifyChanged();
            refreshNotifications();
        }
        return result;
    }

    public static ProtectedChatsState.Result unlock(int account, long dialogId, ProtectedChatsState.AuthProof proof) {
        ProtectedChatsState.Result result = state().unlock(accountKey(account), dialogId, proof);
        if (result == ProtectedChatsState.Result.OK) {
            notifyChanged();
        }
        return result;
    }

    public static void relock(int account, long dialogId) {
        if (state().relock(accountKey(account), dialogId)) {
            notifyChanged();
        }
    }

    private static final java.util.HashMap<String, Integer> openCounts = new java.util.HashMap<>();

    /** A fragment showing this dialog (chat, profile, topics) was opened; reference counted. */
    public static void enter(int account, long dialogId) {
        String k = accountKey(account) + ":" + dialogId;
        Integer c = openCounts.get(k);
        openCounts.put(k, c == null ? 1 : c + 1);
        state().chatEntered(accountKey(account), dialogId);
    }

    /** The last fragment showing the dialog was closed: the re-lock countdown starts. */
    public static void leave(int account, long dialogId) {
        String k = accountKey(account) + ":" + dialogId;
        Integer c = openCounts.get(k);
        if (c == null || c <= 1) {
            openCounts.remove(k);
            // Fragment transitions pause one fragment and resume/create the next in the same pass;
            // decide after they settled so moving between fragments of one chat never re-locks it.
            final long key = accountKey(account);
            AndroidUtilities.runOnUIThread(() -> {
                if (!openCounts.containsKey(key + ":" + dialogId)) {
                    state().chatLeft(key, dialogId);
                }
            });
        } else {
            openCounts.put(k, c - 1);
        }
    }

    // ------------------------------------------------------------- forward hold (see ProtectedChatsState)

    public static boolean beginForwardHold(int account, long dialogId) {
        return state().beginForwardHold(accountKey(account), dialogId);
    }

    public static void forwardPickerClosed(int account, long dialogId) {
        state().forwardPickerClosed(accountKey(account), dialogId);
    }

    public static void forwardToSavedMessagesCompleting(int account, long dialogId) {
        state().forwardToSavedMessagesCompleting(accountKey(account), dialogId);
    }

    public static void forwardCompletionEnded(int account, long dialogId) {
        state().forwardCompletionEnded(accountKey(account), dialogId);
    }

    public static void onAppPaused() {
        if (state().protectedCount() > 0) {
            state().appPaused();
        }
    }

    public static void onAppResumed() {
        if (state().protectedCount() > 0) {
            state().appResumed();
            notifyChanged();
        }
    }

    /** Must run before {@link #onAppResumed()} for the same resume (onActivityResult precedes onResume). */
    public static void onReturnedFromActivityResult() {
        if (state().protectedCount() > 0) {
            state().appResumedFromActivityResult();
        }
    }

    public static ProtectedChatsState.Result enableFeature() {
        ProtectedChatsState.Result result = state().enableFeature();
        notifyChanged();
        return result;
    }

    public static void disableFeatureRemovingAllProtection() {
        state().disableFeatureRemovingAllProtection();
        notifyChanged();
    }

    public static void setHidePreviewWhenLocked(boolean hide) {
        state().setHidePreviewWhenLocked(hide);
        notifyChanged();
        refreshNotifications();
    }

    private static void refreshNotifications() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    NotificationsController.getInstance(a).showNotifications();
                    MediaDataController.getInstance(a).buildShortcuts();
                }
            }
        });
    }

    // ------------------------------------------------------------------ data lifecycle

    public static void onDialogDeleted(int account, long dialogId) {
        long key = accountKey(account);
        if (key != 0) {
            state().removeDialog(key, dialogId);
            notifyChanged();
        }
    }

    public static void onDialogMigrated(int account, long oldDialogId, long newDialogId) {
        long key = accountKey(account);
        if (key != 0) {
            state().migrateDialog(key, oldDialogId, newDialogId);
        }
    }

    /** Must be called with the user id as it was before the account configuration was cleared. */
    public static void onAccountRemoved(long userId) {
        if (userId != 0) {
            state().clearAccount(userId);
            notifyChanged();
        }
    }

    /** The one explicit credential removal transition (see {@link ProtectedChatsState#onCredentialRemoved()}). */
    public static void onCredentialRemoved() {
        state().onCredentialRemoved();
        notifyChanged();
    }
}
