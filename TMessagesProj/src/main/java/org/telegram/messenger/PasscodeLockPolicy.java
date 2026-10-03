package org.telegram.messenger;

/**
 * One credential, two independent consumers: the app-wide lock and protected chats.
 * The credential exists as long as a passcode hash is stored; the app-wide lock additionally needs
 * its own flag, so it can be switched off while protected chats keep using the credential.
 */
public final class PasscodeLockPolicy {

    private PasscodeLockPolicy() {
    }

    public static boolean isAppLockEnabled(boolean hasCredential, boolean appLockFlag) {
        return hasCredential && appLockFlag;
    }

    /** Protected chats only need the credential, never the app-wide lock. */
    public static boolean canProtectChats(boolean hasCredential) {
        return hasCredential;
    }
}
