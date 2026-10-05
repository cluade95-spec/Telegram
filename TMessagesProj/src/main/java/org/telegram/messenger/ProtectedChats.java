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
 * be protected. A forum topic is protected through its parent dialog.
 *
 * Every dialog also has its own "hide message previews" and Auto-lock ({@link #getHidePreview},
 * {@link #getRelockSeconds}); {@link ProtectedChatsState} resolves them, callers only ask questions
 * ({@link #shouldHideContent}, {@link #isProtected}, {@link #allowsExternalInteraction}).
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

    public static long accountKey(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.protectedChatsChanged));
    }

    public static boolean isSupportedDialog(long dialogId) {
        return dialogId != 0 && !DialogObject.isFolderDialogId(dialogId);
    }

    /**
     * Whether the chat's own Lock Settings are offered for this dialog: a supported dialog while the
     * feature can be used, which is the same condition the chat list uses to offer "Protect".
     */
    public static boolean isLockSettingsAvailable(int account, long dialogId) {
        return accountKey(account) != 0 && ProtectedChatsState.isLockSettingsOffered(isSupportedDialog(dialogId), state().isFeatureEnabled(), SharedConfig.hasPasscode());
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

    /** The dialog's own "hide message previews" as the settings screen shows it (not a policy: see {@link #shouldHideContent}). */
    public static boolean getHidePreview(int account, long dialogId) {
        long key = accountKey(account);
        return key != 0 ? state().getHidePreview(key, dialogId) : state().isHidePreviewWhenLocked();
    }

    /** The dialog's own Auto-lock in seconds as the settings screen shows it. */
    public static int getRelockSeconds(int account, long dialogId) {
        long key = accountKey(account);
        return key != 0 ? state().getRelockSeconds(key, dialogId) : state().getRelockSeconds();
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

    /**
     * Protects a chat that is open (its fragments are in the navigation stack): it stays authorized
     * until the user leaves it. See {@link ProtectedGateLifecycle#protectOpen}.
     */
    public static ProtectedChatsState.Result protectOpen(int account, long dialogId, ProtectedChatsState.AuthProof proof, java.util.List<ProtectedGateLifecycle.StackEntry> stack) {
        if (!isSupportedDialog(dialogId)) {
            return ProtectedChatsState.Result.UNSUPPORTED;
        }
        long key = accountKey(account);
        if (key == 0) {
            return ProtectedChatsState.Result.UNSUPPORTED;
        }
        ProtectedChatsState.Result result = lifecycle().protectOpen(key, dialogId, proof, stack);
        if (result == ProtectedChatsState.Result.OK) {
            notifyChanged();
            refreshNotifications();
        }
        return result;
    }

    /** The dialog's own "hide message previews". Kept whether or not the dialog is protected; it only acts while it is. */
    public static ProtectedChatsState.Result setChatHidePreview(int account, long dialogId, boolean hide) {
        if (!isSupportedDialog(dialogId)) {
            return ProtectedChatsState.Result.UNSUPPORTED;
        }
        ProtectedChatsState.Result result = state().setChatHidePreview(accountKey(account), dialogId, hide);
        if (result == ProtectedChatsState.Result.OK && state().isProtected(accountKey(account), dialogId)) {
            notifyChanged();
            refreshNotifications();
        }
        return result;
    }

    /** The dialog's own Auto-lock, one of {@link ProtectedChatsState#RELOCK_CHOICES}. */
    public static ProtectedChatsState.Result setChatRelockSeconds(int account, long dialogId, int seconds) {
        if (!isSupportedDialog(dialogId)) {
            return ProtectedChatsState.Result.UNSUPPORTED;
        }
        return state().setChatRelockSeconds(accountKey(account), dialogId, seconds);
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

    private static ProtectedGateLifecycle lifecycle;

    /**
     * What a fragment pause, resume or destroy means for a protected dialog, including the forward
     * the open chat started (see {@link ProtectedGateLifecycle}); {@code ProtectedChatGate} is its
     * Android adapter.
     */
    public static synchronized ProtectedGateLifecycle lifecycle() {
        if (lifecycle == null) {
            lifecycle = new ProtectedGateLifecycle(state(), AndroidUtilities::runOnUIThread);
        }
        return lifecycle;
    }

    /**
     * The app went to the background (the activity stopped, the screen turned off): the one point where
     * every open chat stops being in use. An activity that is only paused (a system permission dialog
     * over it) is not the background; see {@link ProtectedGateLifecycle#hostLifecycle}.
     */
    public static void onAppPaused() {
        if (state().protectedCount() > 0) {
            lifecycle().appBackgrounded();
        }
    }

    public static void onAppResumed() {
        if (state().protectedCount() > 0) {
            lifecycle().appForegrounded();
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
