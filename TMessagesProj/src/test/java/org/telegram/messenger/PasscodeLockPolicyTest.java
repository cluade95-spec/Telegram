package org.telegram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** The four supported combinations of the app-wide lock and protected chats on one credential. */
public class PasscodeLockPolicyTest {

    private static class Cfg implements ProtectedChatsState.Credential {
        boolean credential = true;
        boolean appLockFlag = true;

        public boolean hasCredential() {
            return PasscodeLockPolicy.canProtectChats(credential);
        }

        public ProtectedChatsState.Verification verify(String s) {
            return "1234".equals(s) ? ProtectedChatsState.Verification.OK : ProtectedChatsState.Verification.WRONG;
        }

        public boolean biometricAvailable() {
            return false;
        }

        boolean appLock() {
            return PasscodeLockPolicy.isAppLockEnabled(credential, appLockFlag);
        }
    }

    private static ProtectedChatsState state(Cfg cfg) {
        final Map<String, String> m = new HashMap<>();
        return new ProtectedChatsState(new ProtectedChatsState.Storage() {
            public String get(String k) {
                return m.get(k);
            }

            public void put(String k, String v) {
                m.put(k, v);
            }

            public void remove(String k) {
                m.remove(k);
            }
        }, () -> 1000L, cfg);
    }

    @Test
    public void appLockOnNoProtectedChats() {
        Cfg cfg = new Cfg();
        ProtectedChatsState s = state(cfg);
        assertTrue(cfg.appLock());
        assertEquals(0, s.protectedCount());
    }

    @Test
    public void appLockOnWithProtectedChats() {
        Cfg cfg = new Cfg();
        ProtectedChatsState s = state(cfg);
        assertEquals(ProtectedChatsState.Result.OK, s.protect(1, 10, s.proofFromPasscode("1234", null)));
        assertTrue(cfg.appLock());
        assertTrue(s.isProtected(1, 10));
    }

    @Test
    public void appLockOffProtectedChatsStillUseTheCredential() {
        Cfg cfg = new Cfg();
        ProtectedChatsState s = state(cfg);
        assertEquals(ProtectedChatsState.Result.OK, s.protect(1, 10, s.proofFromPasscode("1234", null)));
        cfg.appLockFlag = false;
        assertFalse(cfg.appLock());
        assertTrue("credential and protection survive turning the app lock off", s.isProtected(1, 10));
        s.relock(1, 10);
        assertEquals(ProtectedChatsState.Result.OK, s.unlock(1, 10, s.proofFromPasscode("1234", null)));
        assertTrue(s.isUnlocked(1, 10));
    }

    @Test
    public void protectedChatsOffAppLockStillOn() {
        Cfg cfg = new Cfg();
        ProtectedChatsState s = state(cfg);
        assertEquals(ProtectedChatsState.Result.OK, s.protect(1, 10, s.proofFromPasscode("1234", null)));
        s.disableFeatureRemovingAllProtection();
        assertTrue(cfg.appLock());
        assertEquals(0, s.protectedCount());
    }

    @Test
    public void noCredentialMeansNeitherConsumer() {
        Cfg cfg = new Cfg();
        cfg.credential = false;
        assertFalse(cfg.appLock());
        assertFalse(PasscodeLockPolicy.canProtectChats(false));
    }
}
