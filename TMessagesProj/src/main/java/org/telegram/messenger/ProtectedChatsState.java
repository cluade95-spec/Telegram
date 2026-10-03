package org.telegram.messenger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure (Android-free) state model for individual chat protection.
 *
 * There is exactly one passcode credential in the app; it is owned by {@link SharedConfig} and
 * reached through {@link Credential}. This class never stores or copies it. It only stores
 * <i>which</i> dialogs are protected (persisted through {@link Storage}, keyed by the stable
 * account user id, never by the account slot) plus three feature preferences.
 *
 * Temporary authorization ("unlocked" state) lives in memory only. It is therefore always lost on
 * process death, and every uncertain transition (clock going backwards, unknown record) resolves
 * to "locked".
 *
 * All public methods are synchronized: notifications are built off the UI thread.
 *
 * Timing semantics: a chat that was authenticated stays unlocked while it is open in the
 * foreground. The re-lock interval starts when the user leaves the chat or the app goes to the
 * background, measured on a monotonic clock that keeps counting during device sleep. Interval 0
 * means "lock as soon as the chat is left".
 */
public final class ProtectedChatsState {

    public static final int RELOCK_IMMEDIATELY = 0;
    public static final int RELOCK_1_MINUTE = 60;
    public static final int RELOCK_5_MINUTES = 5 * 60;
    public static final int RELOCK_1_HOUR = 60 * 60;
    public static final int RELOCK_5_HOURS = 5 * 60 * 60;
    public static final int[] RELOCK_CHOICES = {RELOCK_IMMEDIATELY, RELOCK_1_MINUTE, RELOCK_5_MINUTES, RELOCK_1_HOUR, RELOCK_5_HOURS};
    public static final int DEFAULT_RELOCK_SECONDS = RELOCK_1_MINUTE;
    /** Time between a successful authentication and the chat actually opening. */
    public static final long GRACE_MS = 5000;

    public interface Storage {
        String get(String key);

        void put(String key, String value);

        void remove(String key);
    }

    public interface Clock {
        /** Monotonic milliseconds that keep counting while the device sleeps. */
        long elapsedMs();
    }

    public enum Verification {
        OK, WRONG, THROTTLED
    }

    /** Adapter over the single, existing passcode credential. */
    public interface Credential {
        boolean hasCredential();

        /** Checks the secret and applies the shared bad-try accounting of the existing lock. */
        Verification verify(String secret);

        boolean biometricAvailable();
    }

    /** Result of a successful authentication; can only be produced by this class. */
    public static final class AuthProof {
        private boolean used;

        private AuthProof() {
        }

        private boolean consume() {
            if (used) {
                return false;
            }
            used = true;
            return true;
        }
    }

    public enum Result {
        OK, FEATURE_DISABLED, NO_CREDENTIAL, UNSUPPORTED, NOT_AUTHENTICATED, NOT_PROTECTED
    }

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HIDE_PREVIEW = "hidePreview";
    private static final String KEY_RELOCK = "relockSeconds";
    private static final String KEY_ACCOUNTS = "accounts";
    private static final String KEY_CHATS_PREFIX = "chats_";

    private static final class Auth {
        /** True while the chat is open in the foreground. */
        boolean active;
        /** Elapsed time at which the chat was left / app was backgrounded. */
        long leftAt;
        /** The chat was open when the app went to the background. */
        boolean pausedWhileOpen;
        /** Short window after authentication in which the chat must be opened (see GRACE_MS). */
        long graceUntil;
    }

    private final Storage storage;
    private final Clock clock;
    private final Credential credential;

    private final Map<Long, Set<Long>> protectedByAccount = new HashMap<>();
    private final Map<String, Auth> authorized = new HashMap<>();
    private boolean featureEnabled;
    private boolean hidePreview;
    private int relockSeconds;

    public ProtectedChatsState(Storage storage, Clock clock, Credential credential) {
        this.storage = storage;
        this.clock = clock;
        this.credential = credential;
        load();
    }

    // ---------------------------------------------------------------- persistence

    private void load() {
        // On by default: nothing is protected until the user protects a chat.
        featureEnabled = !"0".equals(storage.get(KEY_ENABLED));
        hidePreview = !"0".equals(storage.get(KEY_HIDE_PREVIEW));
        relockSeconds = normalizeRelock(parseInt(storage.get(KEY_RELOCK), DEFAULT_RELOCK_SECONDS));
        protectedByAccount.clear();
        String accounts = storage.get(KEY_ACCOUNTS);
        if (accounts != null && !accounts.isEmpty()) {
            for (String a : accounts.split(",")) {
                try {
                    long accountKey = Long.parseLong(a);
                    Set<Long> ids = parseIds(storage.get(KEY_CHATS_PREFIX + accountKey));
                    if (!ids.isEmpty()) {
                        protectedByAccount.put(accountKey, ids);
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        // Without the feature or without the credential nothing can be unlocked; drop stale rows
        // instead of leaving chats that nobody could ever open.
        if ((!featureEnabled || !credential.hasCredential()) && !protectedByAccount.isEmpty()) {
            protectedByAccount.clear();
            persistAll();
        }
    }

    private void persistAll() {
        StringBuilder accounts = new StringBuilder();
        Set<Long> keep = new HashSet<>();
        for (Map.Entry<Long, Set<Long>> e : protectedByAccount.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            keep.add(e.getKey());
            if (accounts.length() > 0) {
                accounts.append(',');
            }
            accounts.append(e.getKey());
            storage.put(KEY_CHATS_PREFIX + e.getKey(), joinIds(e.getValue()));
        }
        String old = storage.get(KEY_ACCOUNTS);
        if (old != null && !old.isEmpty()) {
            for (String a : old.split(",")) {
                try {
                    long accountKey = Long.parseLong(a);
                    if (!keep.contains(accountKey)) {
                        storage.remove(KEY_CHATS_PREFIX + accountKey);
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        storage.put(KEY_ACCOUNTS, accounts.toString());
    }

    private void persistSettings() {
        storage.put(KEY_ENABLED, featureEnabled ? "1" : "0");
        storage.put(KEY_HIDE_PREVIEW, hidePreview ? "1" : "0");
        storage.put(KEY_RELOCK, Integer.toString(relockSeconds));
    }

    private static Set<Long> parseIds(String s) {
        Set<Long> ids = new LinkedHashSet<>();
        if (s == null || s.isEmpty()) {
            return ids;
        }
        for (String p : s.split(",")) {
            try {
                long id = Long.parseLong(p);
                if (id != 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return ids;
    }

    private static String joinIds(Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int normalizeRelock(int seconds) {
        for (int c : RELOCK_CHOICES) {
            if (c == seconds) {
                return seconds;
            }
        }
        return DEFAULT_RELOCK_SECONDS;
    }

    private static String key(long accountKey, long dialogId) {
        return accountKey + ":" + dialogId;
    }

    // ---------------------------------------------------------------- feature settings

    public synchronized boolean isFeatureEnabled() {
        return featureEnabled;
    }

    /**
     * Turns the feature on. Needs the credential to exist: this class never creates one.
     */
    public synchronized Result enableFeature() {
        if (!credential.hasCredential()) {
            return Result.NO_CREDENTIAL;
        }
        featureEnabled = true;
        persistSettings();
        return Result.OK;
    }

    /**
     * Turns the feature off. This is a deliberate transition: every protection is removed in the
     * same step, so no chat can end up protected-but-unenforced or inaccessible. The UI must ask
     * for confirmation using {@link #protectedCount()} before calling this.
     */
    public synchronized void disableFeatureRemovingAllProtection() {
        featureEnabled = false;
        protectedByAccount.clear();
        authorized.clear();
        persistSettings();
        persistAll();
    }

    public synchronized boolean isHidePreviewWhenLocked() {
        return hidePreview;
    }

    public synchronized void setHidePreviewWhenLocked(boolean hide) {
        hidePreview = hide;
        persistSettings();
    }

    public synchronized int getRelockSeconds() {
        return relockSeconds;
    }

    public synchronized boolean setRelockSeconds(int seconds) {
        for (int c : RELOCK_CHOICES) {
            if (c == seconds) {
                relockSeconds = seconds;
                persistSettings();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- authentication

    /** Verifies the existing passcode; returns a single-use proof on success, otherwise null. */
    public synchronized AuthProof proofFromPasscode(String secret, Verification[] outResult) {
        Verification v = credential.hasCredential() ? credential.verify(secret) : Verification.WRONG;
        if (outResult != null && outResult.length > 0) {
            outResult[0] = v;
        }
        return v == Verification.OK ? new AuthProof() : null;
    }

    /** To be called by the UI after the platform biometric prompt succeeded. */
    public synchronized AuthProof proofFromBiometric() {
        return credential.hasCredential() && credential.biometricAvailable() ? new AuthProof() : null;
    }

    // ---------------------------------------------------------------- protection

    public synchronized boolean isProtected(long accountKey, long dialogId) {
        if (!featureEnabled || !credential.hasCredential()) {
            return false;
        }
        Set<Long> ids = protectedByAccount.get(accountKey);
        return ids != null && ids.contains(dialogId);
    }

    public synchronized int protectedCount() {
        int count = 0;
        for (Set<Long> ids : protectedByAccount.values()) {
            count += ids.size();
        }
        return count;
    }

    public synchronized int protectedCount(long accountKey) {
        Set<Long> ids = protectedByAccount.get(accountKey);
        return ids == null ? 0 : ids.size();
    }

    /**
     * Protects a chat. Requires authentication proof, an enabled feature and an existing
     * credential. The chat is considered authorized right after being protected (the user just
     * proved the passcode) and becomes locked once left.
     */
    public synchronized Result protect(long accountKey, long dialogId, AuthProof proof) {
        java.util.List<Long> ids = new ArrayList<>(1);
        ids.add(dialogId);
        return protectMany(accountKey, ids, proof);
    }

    /** One authentication covers a whole multi-selection. */
    public synchronized Result protectMany(long accountKey, java.util.List<Long> dialogIds, AuthProof proof) {
        if (dialogIds.isEmpty()) {
            return Result.UNSUPPORTED;
        }
        for (long id : dialogIds) {
            if (id == 0) {
                return Result.UNSUPPORTED;
            }
        }
        if (!credential.hasCredential()) {
            return Result.NO_CREDENTIAL;
        }
        if (!featureEnabled) {
            return Result.FEATURE_DISABLED;
        }
        if (proof == null || !proof.consume()) {
            return Result.NOT_AUTHENTICATED;
        }
        Set<Long> ids = protectedByAccount.get(accountKey);
        if (ids == null) {
            ids = new LinkedHashSet<>();
            protectedByAccount.put(accountKey, ids);
        }
        long now = clock.elapsedMs();
        for (long dialogId : dialogIds) {
            ids.add(dialogId);
            Auth auth = new Auth();
            auth.active = false;
            auth.leftAt = now;
            authorized.put(key(accountKey, dialogId), auth);
        }
        persistAll();
        return Result.OK;
    }

    /** Permanently removes protection from the chat; needs proof of the passcode. */
    public synchronized Result unprotect(long accountKey, long dialogId, AuthProof proof) {
        java.util.List<Long> ids = new ArrayList<>(1);
        ids.add(dialogId);
        return unprotectMany(accountKey, ids, proof);
    }

    public synchronized Result unprotectMany(long accountKey, java.util.List<Long> dialogIds, AuthProof proof) {
        for (long id : dialogIds) {
            if (!isProtected(accountKey, id)) {
                return Result.NOT_PROTECTED;
            }
        }
        if (dialogIds.isEmpty()) {
            return Result.NOT_PROTECTED;
        }
        if (proof == null || !proof.consume()) {
            return Result.NOT_AUTHENTICATED;
        }
        for (long id : dialogIds) {
            removeInternal(accountKey, id);
        }
        return Result.OK;
    }

    private void removeInternal(long accountKey, long dialogId) {
        Set<Long> ids = protectedByAccount.get(accountKey);
        if (ids != null) {
            ids.remove(dialogId);
            if (ids.isEmpty()) {
                protectedByAccount.remove(accountKey);
            }
        }
        authorized.remove(key(accountKey, dialogId));
        persistAll();
    }

    // ---------------------------------------------------------------- authorization

    /** Grants temporary authorization after a successful authentication of this chat only. */
    public synchronized Result unlock(long accountKey, long dialogId, AuthProof proof) {
        if (!isProtected(accountKey, dialogId)) {
            return Result.NOT_PROTECTED;
        }
        if (proof == null || !proof.consume()) {
            return Result.NOT_AUTHENTICATED;
        }
        Auth auth = new Auth();
        long now = clock.elapsedMs();
        auth.active = false;
        auth.leftAt = now;
        auth.graceUntil = now + GRACE_MS;
        authorized.put(key(accountKey, dialogId), auth);
        return Result.OK;
    }

    /** True if the chat is not protected or is currently authorized. */
    public synchronized boolean isUnlocked(long accountKey, long dialogId) {
        if (!isProtected(accountKey, dialogId)) {
            return true;
        }
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth == null) {
            return false;
        }
        if (auth.active) {
            return true;
        }
        long now = clock.elapsedMs();
        if (now >= auth.leftAt && now < auth.graceUntil) {
            return true;
        }
        if (relockSeconds <= 0 || now < auth.leftAt || now - auth.leftAt >= relockSeconds * 1000L) {
            authorized.remove(key(accountKey, dialogId));
            return false;
        }
        return true;
    }

    public synchronized boolean isLockedProtected(long accountKey, long dialogId) {
        return isProtected(accountKey, dialogId) && !isUnlocked(accountKey, dialogId);
    }

    /** The lock quick action is offered only for protected chats that are currently authorized. */
    public synchronized boolean canManuallyRelock(long accountKey, long dialogId) {
        return isProtected(accountKey, dialogId) && isUnlocked(accountKey, dialogId);
    }

    /** Immediately invalidates this chat's authorization. Needs no authentication. */
    public synchronized boolean relock(long accountKey, long dialogId) {
        return authorized.remove(key(accountKey, dialogId)) != null;
    }

    /** The chat became visible after having passed the gate. */
    public synchronized void chatEntered(long accountKey, long dialogId) {
        if (!isProtected(accountKey, dialogId)) {
            return;
        }
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth != null && isUnlocked(accountKey, dialogId)) {
            auth.active = true;
            auth.graceUntil = 0;
        }
    }

    public synchronized void chatLeft(long accountKey, long dialogId) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth != null && auth.active) {
            auth.active = false;
            auth.leftAt = clock.elapsedMs();
        }
    }

    /** App moved to the background: open chats start their re-lock countdown. */
    public synchronized void appPaused() {
        long now = clock.elapsedMs();
        for (Auth auth : authorized.values()) {
            if (auth.active) {
                auth.active = false;
                auth.leftAt = now;
                auth.pausedWhileOpen = true;
            }
        }
    }

    /**
     * App returned to the foreground: chats that were open when it was backgrounded and whose
     * interval ran out are revoked. Fresh ones stay in their countdown window; a fragment that
     * becomes visible again re-activates them through {@link #chatEntered}.
     */
    public synchronized void appResumed() {
        for (Iterator<Map.Entry<String, Auth>> it = authorized.entrySet().iterator(); it.hasNext(); ) {
            Auth auth = it.next().getValue();
            if (!auth.pausedWhileOpen) {
                continue;
            }
            auth.pausedWhileOpen = false;
            long now = clock.elapsedMs();
            if (relockSeconds <= 0 || now < auth.leftAt || now - auth.leftAt >= relockSeconds * 1000L) {
                it.remove();
            }
        }
    }

    /**
     * The app came back from a system activity the user launched from an open chat (file picker,
     * camera, ...). Like the app lock does for activity results, chats that were open stay open.
     */
    public synchronized void appResumedFromActivityResult() {
        for (Auth auth : authorized.values()) {
            if (auth.pausedWhileOpen) {
                auth.pausedWhileOpen = false;
                auth.active = true;
            }
        }
    }

    // ---------------------------------------------------------------- previews

    /**
     * Message content must be withheld for this dialog on every surface outside the opened,
     * authenticated conversation (dialog rows, search, notifications, widgets, popups). Identity
     * (title, avatar) is never hidden. Independent of the temporary authorization: that only
     * opens the conversation itself.
     */
    public synchronized boolean shouldHideContent(long accountKey, long dialogId) {
        return hidePreview && isProtected(accountKey, dialogId);
    }

    // ---------------------------------------------------------------- lifecycle of data

    public synchronized void removeDialog(long accountKey, long dialogId) {
        if (protectedByAccount.containsKey(accountKey) || authorized.containsKey(key(accountKey, dialogId))) {
            removeInternal(accountKey, dialogId);
        }
    }

    /** Group upgraded to supergroup, etc. Keeps the protection on the new dialog id. */
    public synchronized void migrateDialog(long accountKey, long oldDialogId, long newDialogId) {
        Set<Long> ids = protectedByAccount.get(accountKey);
        if (ids == null || !ids.contains(oldDialogId) || newDialogId == 0) {
            return;
        }
        ids.remove(oldDialogId);
        ids.add(newDialogId);
        Auth auth = authorized.remove(key(accountKey, oldDialogId));
        if (auth != null) {
            authorized.put(key(accountKey, newDialogId), auth);
        }
        persistAll();
    }

    /** Account removed / logged out. */
    public synchronized void clearAccount(long accountKey) {
        if (protectedByAccount.remove(accountKey) != null) {
            persistAll();
        }
        String prefix = accountKey + ":";
        for (Iterator<String> it = authorized.keySet().iterator(); it.hasNext(); ) {
            if (it.next().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    /**
     * The one explicit credential-removal transition. Nothing may still depend on the credential
     * afterwards, so every protection and the feature flag are cleared together. Call after the
     * user confirmed.
     */
    public synchronized void onCredentialRemoved() {
        protectedByAccount.clear();
        authorized.clear();
        // Back to the default so that a passcode created later offers the feature again.
        featureEnabled = true;
        persistSettings();
        persistAll();
    }

    public synchronized ArrayList<Long> protectedDialogs(long accountKey) {
        Set<Long> ids = protectedByAccount.get(accountKey);
        return ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }
}
