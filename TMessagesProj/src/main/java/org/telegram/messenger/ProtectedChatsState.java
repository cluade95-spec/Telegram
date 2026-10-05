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
 * Per-chat settings: besides the set of protected dialogs, a dialog can carry its own "hide message
 * previews" and "Auto-lock" values. They are stored per account and dialog, independently of the
 * protection itself, and are only ever <i>read</i> for a protected dialog: protection status stays
 * the authority, so a dialog with saved settings that is not protected behaves like any other chat,
 * and turning protection back on restores what the chat was configured with. A dialog that has no
 * value of its own for a setting uses the value of the Protected Chats page; that is how every chat
 * protected before per-chat settings existed keeps behaving exactly as it did. Everything that
 * needs an answer asks this class ({@link #shouldHideContent}, {@link #getRelockSeconds(long, long)},
 * {@link #allowsExternalInteraction}); nothing else interprets the stored values.
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
    /** Accounts that have per-chat settings, and one record list per account: {@code id:hidePreview:relockSeconds;...}. */
    private static final String KEY_SETTINGS_ACCOUNTS = "chatSettingsAccounts";
    private static final String KEY_SETTINGS_PREFIX = "chatsettings_";

    private static final class Auth {
        final long accountKey;
        long dialogId;
        /** True while the chat is open in the foreground. */
        boolean active;
        /** Elapsed time at which the chat was left / app was backgrounded. */
        long leftAt;
        /** The chat was open when the app went to the background. */
        boolean pausedWhileOpen;
        /** Short window after authentication in which the chat must be opened (see GRACE_MS). */
        long graceUntil;
        /** Forward transaction (see {@link ForwardPhase}): the chat started a forward on its own authorization. */
        ForwardPhase hold = ForwardPhase.NONE;
        /** The chat was covered while its forward transaction was open; the countdown starts if it ends with the chat still covered. */
        boolean leaveDeferred;
        long deferredLeftAt;

        Auth(long accountKey, long dialogId) {
            this.accountKey = accountKey;
            this.dialogId = dialogId;
        }
    }

    /** What the user configured for one dialog; a field that was never set follows the Protected Chats page. */
    private static final class ChatSettings {
        /** null: not set for this chat. */
        Boolean hidePreview;
        /** -1: not set for this chat. */
        int relockSeconds = -1;

        boolean isEmpty() {
            return hidePreview == null && relockSeconds < 0;
        }
    }

    /**
     * Where the forward the chat started is, from the chat's point of view. The picker, the
     * authentication sheet of a destination, the destination chat that the forward opened and
     * Telegram's success interaction are all part of the forward: none of them is the user leaving the
     * chat. The transaction ends when the chat is shown again (Back from the picker, Back from the
     * destination, the forward came back to it), or when the user navigates to something that is not
     * part of the forward, or when the app goes to the background.
     */
    public enum ForwardPhase {
        /** No forward in progress. A chat that is shown and authorized is simply "source visible". */
        NONE,
        /** The forward picker is on top of the chat (a destination's authentication sheet is only a layer over it). */
        PICKER,
        /** The picker has handed its selection to the delegate and the delegate is running. */
        HANDING_OVER,
        /** The forward opened a destination chat over this chat. Back from it returns to this chat. */
        DESTINATION,
        /** The forward came back to this chat and Telegram's success / tag interaction is on it. */
        COMPLETING
    }

    /** What the picker's delegate did with the selection. */
    public enum ForwardOutcome {
        /** It did not complete (a confirmation is pending, an error): the picker goes on. */
        PICKER_REMAINS,
        /** It finished by returning to the source chat (Saved Messages deposit, same chat, several chats). */
        SOURCE_RETURNED,
        /** It opened a destination chat over the source. */
        DESTINATION_OPENED,
        /** The picker is gone and nothing that belongs to the forward is on top of the source. */
        ABANDONED
    }

    private final Storage storage;
    private final Clock clock;
    private final Credential credential;

    private final Map<Long, Set<Long>> protectedByAccount = new HashMap<>();
    private final Map<String, Auth> authorized = new HashMap<>();
    private final Map<Long, Map<Long, ChatSettings>> chatSettings = new HashMap<>();
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
        loadChatSettings();
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

    private void loadChatSettings() {
        chatSettings.clear();
        String accounts = storage.get(KEY_SETTINGS_ACCOUNTS);
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        for (String a : accounts.split(",")) {
            try {
                long accountKey = Long.parseLong(a);
                Map<Long, ChatSettings> map = parseChatSettings(storage.get(KEY_SETTINGS_PREFIX + accountKey));
                if (!map.isEmpty()) {
                    chatSettings.put(accountKey, map);
                }
            } catch (NumberFormatException ignore) {
            }
        }
    }

    private static Map<Long, ChatSettings> parseChatSettings(String s) {
        Map<Long, ChatSettings> map = new HashMap<>();
        if (s == null || s.isEmpty()) {
            return map;
        }
        for (String record : s.split(";")) {
            String[] f = record.split(":", -1);
            if (f.length != 3) {
                continue;
            }
            try {
                long id = Long.parseLong(f[0]);
                if (id == 0) {
                    continue;
                }
                ChatSettings cs = new ChatSettings();
                if ("1".equals(f[1])) {
                    cs.hidePreview = Boolean.TRUE;
                } else if ("0".equals(f[1])) {
                    cs.hidePreview = Boolean.FALSE;
                }
                if (!f[2].isEmpty()) {
                    int seconds = Integer.parseInt(f[2]);
                    if (isRelockChoice(seconds)) {
                        cs.relockSeconds = seconds;
                    }
                }
                if (!cs.isEmpty()) {
                    map.put(id, cs);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return map;
    }

    private void persistChatSettings() {
        StringBuilder accounts = new StringBuilder();
        Set<Long> keep = new HashSet<>();
        for (Map.Entry<Long, Map<Long, ChatSettings>> e : chatSettings.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            keep.add(e.getKey());
            if (accounts.length() > 0) {
                accounts.append(',');
            }
            accounts.append(e.getKey());
            StringBuilder records = new StringBuilder();
            for (Map.Entry<Long, ChatSettings> c : e.getValue().entrySet()) {
                if (records.length() > 0) {
                    records.append(';');
                }
                records.append(c.getKey()).append(':');
                if (c.getValue().hidePreview != null) {
                    records.append(c.getValue().hidePreview ? '1' : '0');
                }
                records.append(':');
                if (c.getValue().relockSeconds >= 0) {
                    records.append(c.getValue().relockSeconds);
                }
            }
            storage.put(KEY_SETTINGS_PREFIX + e.getKey(), records.toString());
        }
        String old = storage.get(KEY_SETTINGS_ACCOUNTS);
        if (old != null && !old.isEmpty()) {
            for (String a : old.split(",")) {
                try {
                    long accountKey = Long.parseLong(a);
                    if (!keep.contains(accountKey)) {
                        storage.remove(KEY_SETTINGS_PREFIX + accountKey);
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        storage.put(KEY_SETTINGS_ACCOUNTS, accounts.toString());
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

    private static boolean isRelockChoice(int seconds) {
        for (int c : RELOCK_CHOICES) {
            if (c == seconds) {
                return true;
            }
        }
        return false;
    }

    private static int normalizeRelock(int seconds) {
        return isRelockChoice(seconds) ? seconds : DEFAULT_RELOCK_SECONDS;
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
        chatSettings.clear();
        persistSettings();
        persistAll();
        persistChatSettings();
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
            Auth auth = new Auth(accountKey, dialogId);
            auth.active = false;
            auth.leftAt = now;
            authorized.put(key(accountKey, dialogId), auth);
        }
        persistAll();
        return Result.OK;
    }

    /**
     * Protects a chat the user has open right now (its fragments are in the navigation stack): the
     * user just proved the passcode, so the chat is in use and stays authorized until it is left,
     * and only then does its own Auto-lock count. Whoever calls this must register the open
     * fragments of the chat as in use ({@link ProtectedGateLifecycle#protectOpen} does both), otherwise
     * nothing would ever end the use; the package-private access keeps it that way.
     */
    synchronized Result protectOpen(long accountKey, long dialogId, AuthProof proof) {
        Result result = protect(accountKey, dialogId, proof);
        if (result == Result.OK) {
            Auth auth = authorized.get(key(accountKey, dialogId));
            auth.active = true;
            auth.graceUntil = 0;
        }
        return result;
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
        Auth auth = new Auth(accountKey, dialogId);
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
        final int relock = relockSecondsOf(accountKey, dialogId);
        if (relock <= 0 || now < auth.leftAt || now - auth.leftAt >= relock * 1000L) {
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
            auth.leaveDeferred = false;
        }
    }

    public synchronized void chatLeft(long accountKey, long dialogId) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth != null && auth.active) {
            if (auth.hold == ForwardPhase.PICKER || auth.hold == ForwardPhase.HANDING_OVER || auth.hold == ForwardPhase.DESTINATION) {
                // The forward the chat itself started covers it (its picker, then the destination it
                // opened). That is not the user leaving: remember when it happened. If the chat is
                // shown again the cover never counts (chatEntered); if the forward ends with the chat
                // still covered it counts from now (releaseHold).
                if (!auth.leaveDeferred) {
                    auth.leaveDeferred = true;
                    auth.deferredLeftAt = clock.elapsedMs();
                }
                return;
            }
            // Anything else that covers the chat, also while Telegram completes a forward, is
            // unrelated navigation: the hold ends and the normal countdown starts.
            auth.hold = ForwardPhase.NONE;
            auth.active = false;
            auth.leftAt = clock.elapsedMs();
        }
    }

    // ------------------------------------------------------------- forward hold

    /**
     * The open, authorized chat starts a forward: its forward picker is about to cover it. For as
     * long as the forward (picker, destination it opens, completion) is the only thing on top of
     * the chat, the chat is not counted as left, so Immediate Auto-lock does not close it while the
     * forward runs, and Back from the picker or from the destination finds it as it was. Only a chat
     * that is protected, authorized and open right now can hold; nothing else is authorized and
     * nothing is extended: the hold ends when the chat is shown again, with unrelated navigation,
     * and with the app going to the background.
     *
     * @return true if the hold started
     */
    public synchronized boolean beginForwardHold(long accountKey, long dialogId) {
        if (!isProtected(accountKey, dialogId)) {
            return false;
        }
        Auth auth = authorized.get(key(accountKey, dialogId));
        // One forward at a time. A new forward supersedes the completion or the destination of an earlier one.
        if (auth == null || !auth.active || auth.hold == ForwardPhase.PICKER || auth.hold == ForwardPhase.HANDING_OVER) {
            return false;
        }
        auth.hold = ForwardPhase.PICKER;
        auth.leaveDeferred = false;
        return true;
    }

    /**
     * The forward picker is presented over a fragment. The fragment says which protected dialog it
     * shows (0 when it is not about one) and whether it is on screen. Which kind of screen it is
     * (chat, profile, shared media, a viewer's chat) does not matter: only a protected dialog that
     * is authorized and open right now can hold, and the hold only keeps that authorization.
     */
    public synchronized boolean beginForwardHoldOver(long accountKey, long shownProtectedDialogId, boolean onScreen) {
        if (shownProtectedDialogId == 0 || !onScreen) {
            return false;
        }
        return beginForwardHold(accountKey, shownProtectedDialogId);
    }

    /**
     * The picker was destroyed. While it was still the picker (Back, the system or gesture back, the
     * toolbar back, any other way of cancelling) this ends the forward: if the source is shown again
     * (chatEntered cleared the deferral) nothing else happens, and the source keeps its authorization
     * exactly as it was. If the source is still covered by something else the cover counts as
     * leaving, from when it began. Once the picker handed its selection over, its destruction is part
     * of that hand-over and changes nothing.
     */
    public synchronized void forwardPickerClosed(long accountKey, long dialogId) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth == null || auth.hold != ForwardPhase.PICKER) {
            return;
        }
        releaseHold(auth);
    }

    /** The picker is handing its selection to the delegate, which may finish the picker or open a chat. */
    public synchronized void forwardHandOver(long accountKey, long dialogId) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth != null && auth.hold == ForwardPhase.PICKER) {
            auth.hold = ForwardPhase.HANDING_OVER;
        }
    }

    /** The delegate returned: the transaction continues in the phase that matches what it did. */
    public synchronized void forwardSettled(long accountKey, long dialogId, ForwardOutcome outcome) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth == null || auth.hold != ForwardPhase.HANDING_OVER) {
            return;
        }
        switch (outcome) {
            case PICKER_REMAINS:
                auth.hold = ForwardPhase.PICKER;
                break;
            case SOURCE_RETURNED:
                auth.hold = ForwardPhase.COMPLETING;
                break;
            case DESTINATION_OPENED:
                auth.hold = ForwardPhase.DESTINATION;
                break;
            default:
                releaseHold(auth);
                break;
        }
    }

    /**
     * The destination chat the forward opened over the source was left or destroyed. Back from it
     * shows the source again first (the source is entered before the destination pauses), so nothing
     * changes for the source. Anything else that took the user away from the destination while the
     * source is still covered ends the forward, and the source's cover counts from when it began.
     */
    public synchronized void forwardDestinationLeft(long accountKey, long dialogId) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth == null || auth.hold != ForwardPhase.DESTINATION) {
            return;
        }
        releaseHold(auth);
    }

    /** Telegram's success / tag interaction ended (dismissed, timed out, tag chosen): the hold is over. */
    public synchronized void forwardCompletionEnded(long accountKey, long dialogId) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth == null || auth.hold != ForwardPhase.COMPLETING) {
            return;
        }
        releaseHold(auth);
    }

    /** The source chat itself is gone: whatever it held ends with it. */
    public synchronized void forwardSourceGone(long accountKey, long dialogId) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        if (auth != null) {
            releaseHold(auth);
        }
    }

    /** Ends the forward; a cover that was only deferred counts as a departure from when it began. */
    private static void releaseHold(Auth auth) {
        auth.hold = ForwardPhase.NONE;
        if (auth.leaveDeferred) {
            auth.leaveDeferred = false;
            auth.active = false;
            auth.leftAt = auth.deferredLeftAt;
        }
    }

    /** Whether a forward hold exists for the chat. */
    public synchronized boolean isForwardHoldActive(long accountKey, long dialogId) {
        return getForwardPhase(accountKey, dialogId) != ForwardPhase.NONE;
    }

    public synchronized ForwardPhase getForwardPhase(long accountKey, long dialogId) {
        Auth auth = authorized.get(key(accountKey, dialogId));
        return auth == null ? ForwardPhase.NONE : auth.hold;
    }

    /** App moved to the background: open chats start their re-lock countdown. */
    public synchronized void appPaused() {
        long now = clock.elapsedMs();
        for (Auth auth : authorized.values()) {
            // A forward hold never survives the app going to the background. A chat that was only
            // covered by its own forward counts as left from when it was covered, so it is not
            // revived by a return from a system activity.
            releaseHold(auth);
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
            final int relock = relockSecondsOf(auth.accountKey, auth.dialogId);
            if (relock <= 0 || now < auth.leftAt || now - auth.leftAt >= relock * 1000L) {
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
        return isProtected(accountKey, dialogId) && hidePreviewOf(accountKey, dialogId);
    }

    /**
     * Whether something outside the opened conversation may act on the dialog: a reply typed into a
     * notification, the popup, the Wear and car replies, a bot button on a notification. Such an
     * action sends into the chat without opening it, so it would bypass the authentication that
     * opening requires. It is never allowed for a protected dialog, whatever "Hide Message
     * Previews" says (that only decides what is shown) and whether the chat is open for now (that
     * only opens the conversation itself).
     */
    public synchronized boolean allowsExternalInteraction(long accountKey, long dialogId) {
        return !isProtected(accountKey, dialogId);
    }

    // ---------------------------------------------------------------- per-chat settings

    private ChatSettings chatSettingsOf(long accountKey, long dialogId) {
        Map<Long, ChatSettings> map = chatSettings.get(accountKey);
        return map == null ? null : map.get(dialogId);
    }

    private boolean hidePreviewOf(long accountKey, long dialogId) {
        ChatSettings cs = chatSettingsOf(accountKey, dialogId);
        return cs != null && cs.hidePreview != null ? cs.hidePreview : hidePreview;
    }

    private int relockSecondsOf(long accountKey, long dialogId) {
        ChatSettings cs = chatSettingsOf(accountKey, dialogId);
        return cs != null && cs.relockSeconds >= 0 ? cs.relockSeconds : relockSeconds;
    }

    /**
     * Whether message content is hidden for this dialog, as the settings screen shows it. This is the
     * value, not the policy: it says nothing about whether the dialog is protected (see
     * {@link #shouldHideContent} for that).
     */
    public synchronized boolean getHidePreview(long accountKey, long dialogId) {
        return hidePreviewOf(accountKey, dialogId);
    }

    /** The Auto-lock of this dialog in seconds, as the settings screen shows it (see {@link #getHidePreview}). */
    public synchronized int getRelockSeconds(long accountKey, long dialogId) {
        return relockSecondsOf(accountKey, dialogId);
    }

    /**
     * Sets this dialog's own "hide message previews". Stored whether or not the dialog is protected
     * (it only acts while it is) and never touches another dialog or another account.
     */
    public synchronized Result setChatHidePreview(long accountKey, long dialogId, boolean hide) {
        if (accountKey == 0 || dialogId == 0) {
            return Result.UNSUPPORTED;
        }
        mutableChatSettings(accountKey, dialogId).hidePreview = hide;
        persistChatSettings();
        return Result.OK;
    }

    /** Sets this dialog's own Auto-lock; only one of {@link #RELOCK_CHOICES} is accepted. */
    public synchronized Result setChatRelockSeconds(long accountKey, long dialogId, int seconds) {
        if (accountKey == 0 || dialogId == 0 || !isRelockChoice(seconds)) {
            return Result.UNSUPPORTED;
        }
        mutableChatSettings(accountKey, dialogId).relockSeconds = seconds;
        persistChatSettings();
        return Result.OK;
    }

    private ChatSettings mutableChatSettings(long accountKey, long dialogId) {
        Map<Long, ChatSettings> map = chatSettings.get(accountKey);
        if (map == null) {
            map = new HashMap<>();
            chatSettings.put(accountKey, map);
        }
        ChatSettings cs = map.get(dialogId);
        if (cs == null) {
            cs = new ChatSettings();
            map.put(dialogId, cs);
        }
        return cs;
    }

    /**
     * Whether a chat's own Lock Settings are offered (the profile's menu item): for a dialog that can
     * be protected, while the feature can be used. That is the condition the chat list uses for
     * "Protect": with a passcode but the feature switched off there is nothing to configure, and
     * without a passcode the screen is offered because turning Chat Lock on leads to the passcode setup.
     */
    public static boolean isLockSettingsOffered(boolean supportedDialog, boolean featureEnabled, boolean hasCredential) {
        return supportedDialog && (featureEnabled || !hasCredential);
    }

    /** Whether the user gave this dialog a value of its own for either setting (kept while it is not protected). */
    public synchronized boolean hasChatSettings(long accountKey, long dialogId) {
        return chatSettingsOf(accountKey, dialogId) != null;
    }

    // ---------------------------------------------------------------- lifecycle of data

    public synchronized void removeDialog(long accountKey, long dialogId) {
        if (protectedByAccount.containsKey(accountKey) || authorized.containsKey(key(accountKey, dialogId))) {
            removeInternal(accountKey, dialogId);
        }
        Map<Long, ChatSettings> map = chatSettings.get(accountKey);
        if (map != null && map.remove(dialogId) != null) {
            if (map.isEmpty()) {
                chatSettings.remove(accountKey);
            }
            persistChatSettings();
        }
    }

    /** Group upgraded to supergroup, etc. Keeps the protection on the new dialog id. */
    public synchronized void migrateDialog(long accountKey, long oldDialogId, long newDialogId) {
        if (newDialogId == 0 || newDialogId == oldDialogId) {
            return;
        }
        Set<Long> ids = protectedByAccount.get(accountKey);
        if (ids != null && ids.contains(oldDialogId)) {
            ids.remove(oldDialogId);
            ids.add(newDialogId);
            Auth auth = authorized.remove(key(accountKey, oldDialogId));
            if (auth != null) {
                auth.dialogId = newDialogId;
                authorized.put(key(accountKey, newDialogId), auth);
            }
            persistAll();
        }
        // the chat's own settings belong to the dialog, protected or not
        Map<Long, ChatSettings> map = chatSettings.get(accountKey);
        if (map != null && map.containsKey(oldDialogId)) {
            map.put(newDialogId, map.remove(oldDialogId));
            persistChatSettings();
        }
    }

    /** Account removed / logged out. */
    public synchronized void clearAccount(long accountKey) {
        if (protectedByAccount.remove(accountKey) != null) {
            persistAll();
        }
        if (chatSettings.remove(accountKey) != null) {
            persistChatSettings();
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
        chatSettings.clear();
        // Back to the default so that a passcode created later offers the feature again.
        featureEnabled = true;
        persistSettings();
        persistAll();
        persistChatSettings();
    }

    public synchronized ArrayList<Long> protectedDialogs(long accountKey) {
        Set<Long> ids = protectedByAccount.get(accountKey);
        return ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }
}
