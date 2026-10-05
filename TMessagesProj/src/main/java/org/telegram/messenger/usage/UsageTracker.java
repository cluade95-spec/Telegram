package org.telegram.messenger.usage;

import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.ForegroundDetector;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TimeZone;

/**
 * Android glue of Activity (Reference A): turns app signals into {@link UsageAccountant} events. No timers, services,
 * wake locks or network: everything is driven by events that already exist (touches, lifecycle, navigation, playback,
 * calls). All entry points are UI-thread; the few that can arrive elsewhere hop to the UI thread themselves.
 */
public final class UsageTracker implements NotificationCenter.NotificationCenterDelegate, ForegroundDetector.Listener {

    private static final UsageClock CLOCK = new UsageClock() {
        @Override
        public long elapsed() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public long wallMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public TimeZone zone() {
            return TimeZone.getDefault();
        }
    };

    private static volatile UsageTracker instance;

    private final UsageAccountant accountant = new UsageAccountant(CLOCK);
    private final Set<Object> videoPlayers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> storyPlayers = Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean voicePlaying;
    private boolean passive;
    private boolean callConnected;
    private boolean resolvePending;
    private long account;
    private UsageClassifier.SurfaceKey surface;

    private final Runnable resolveRunnable = () -> {
        resolvePending = false;
        resolveNow();
    };

    private UsageTracker() {
    }

    /** Called once from ApplicationLoader after the ForegroundDetector exists. */
    public static void init() {
        if (instance != null) {
            return;
        }
        UsageTracker t = new UsageTracker();
        instance = t;
        ForegroundDetector fd = ForegroundDetector.getInstance();
        t.syncAccount();
        t.accountant.onScreen(ApplicationLoader.isScreenOn);
        if (fd != null) {
            fd.addListener(t);
            if (fd.isForeground()) {
                t.accountant.onForeground();
            }
        }
        NotificationCenter gc = NotificationCenter.getGlobalInstance();
        gc.addObserver(t, NotificationCenter.screenStateChanged);
        gc.addObserver(t, NotificationCenter.activeAccountChanged);
        gc.addObserver(t, NotificationCenter.didEndCall);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) { // playback events are per account
            NotificationCenter nc = NotificationCenter.getInstance(a);
            nc.addObserver(t, NotificationCenter.messagePlayingDidStart);
            nc.addObserver(t, NotificationCenter.messagePlayingPlayStateChanged);
            nc.addObserver(t, NotificationCenter.messagePlayingDidReset);
        }
        t.markDirty();
    }

    // static entry points used by the hooks (all no-ops until init)

    /** Hot path: every touch and key. A long store and a comparison, nothing else. */
    public static void onUserInput() {
        UsageTracker t = instance;
        if (t != null) {
            t.accountant.onInput();
            if (t.accountant.flushDue()) {
                t.flush();
            }
        }
    }

    /** Typing in an IME window produces no touch in our windows, so text fields report here. */
    public static void onTextInput() {
        onUserInput();
    }

    /** The visible surface may have changed (fragment resume/pause, stack change, viewer, search, tab). */
    public static void onNavigationChanged() {
        UsageTracker t = instance;
        if (t != null) {
            t.markDirty();
        }
    }

    /** A video in a viewer started or stopped playing. Owner is any stable object (the viewer). */
    public static void onVideoPlaying(Object owner, boolean playing) {
        UsageTracker t = instance;
        if (t != null) {
            t.setPlaying(t.videoPlayers, owner, playing);
        }
    }

    /** A story viewer is playing (not paused) or not. */
    public static void onStoriesPlaying(Object owner, boolean playing) {
        UsageTracker t = instance;
        if (t != null) {
            t.setPlaying(t.storyPlayers, owner, playing);
        }
    }

    /** Call connected / not connected; may arrive on a service thread. */
    public static void onCallConnected(boolean connected) {
        UsageTracker t = instance;
        if (t != null) {
            t.onUi(() -> {
                if (t.callConnected != connected) {
                    t.callConnected = connected;
                    t.accountant.onCall(connected);
                    t.afterEvent();
                }
            });
        }
    }

    /** Messages were confirmed sent by the server for the account with this clientUserId (any thread). */
    public static void onMessagesSent(long accountUserId, int count) {
        UsageTracker t = instance;
        if (t != null && count > 0 && accountUserId != 0) {
            t.onUi(() -> t.accountant.onMessagesSent(accountUserId, count));
        }
    }

    /** Logout: flush what is in memory first, then anonymize the account's per-dialog rows (order kept on the UI thread). */
    public static void onAccountRemoved(long accountUserId) {
        UsageTracker t = instance;
        if (t != null) {
            t.onUi(() -> {
                t.flush();
                UsageStore.getInstance().onAccountRemoved(accountUserId);
            });
        }
    }

    /** Flushes in-memory time to the store, then reports (dashboard open, reset). */
    public static void flushNow() {
        UsageTracker t = instance;
        if (t != null) {
            t.onUi(t::flush);
        }
    }

    // internals

    private void onUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            AndroidUtilities.runOnUIThread(r);
        }
    }

    private void setPlaying(Set<Object> set, Object owner, boolean playing) {
        if (playing ? set.add(owner) : set.remove(owner)) {
            updatePassive();
        }
    }

    private void updatePassive() {
        boolean now = voicePlaying || !videoPlayers.isEmpty() || !storyPlayers.isEmpty();
        if (now != passive) {
            passive = now;
            accountant.onPassive(now);
            afterEvent();
        }
    }

    private void markDirty() {
        if (!resolvePending) {
            resolvePending = true;
            AndroidUtilities.runOnUIThread(resolveRunnable);
        }
    }

    private void syncAccount() {
        long id = 0;
        try {
            id = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
        } catch (Throwable ignore) {
        }
        if (id != account) {
            account = id;
            accountant.onAccount(id);
        }
    }

    private void resolveNow() {
        syncAccount();
        UsageClassifier.SurfaceKey key;
        try {
            key = UsageSurfaceResolver.resolve(callConnected);
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        if (!key.equals(surface)) {
            surface = key;
            accountant.onSurface(key.surface, key.dialog);
            if (BuildVars.DEBUG_VERSION) {
                FileLog.d("usage: surface " + key.surface + (key.dialog != 0 ? " (dialog)" : ""));
            }
            afterEvent();
        }
    }

    private void afterEvent() {
        if (accountant.flushDue()) {
            flush();
        }
    }

    private void flush() {
        UsageLedger.Snapshot snapshot = accountant.drain();
        if (snapshot.isEmpty()) {
            return;
        }
        if (BuildVars.DEBUG_VERSION) {
            long[] bySurface = new long[UsageSurface.values().length + 1];
            for (UsageLedger.Row r : snapshot.rows) {
                bySurface[UsageSurface.fromId(r.key.surface).ordinal()] += r.seconds;
            }
            StringBuilder sb = new StringBuilder("usage: flush");
            for (UsageSurface s : UsageSurface.values()) {
                if (bySurface[s.ordinal()] > 0) {
                    sb.append(' ').append(s).append('=').append(bySurface[s.ordinal()]).append('s');
                }
            }
            FileLog.d(sb.toString());
        }
        UsageStore.getInstance().write(snapshot);
    }

    private void recomputeVoice() {
        boolean voice = false;
        MediaController mc = MediaController.getInstance();
        MessageObject m = mc.getPlayingMessageObject();
        if (m != null && (m.isVoice() || m.isRoundVideo()) && !mc.isMessagePaused()) {
            voice = true;
        }
        voicePlaying = voice;
        updatePassive();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.screenStateChanged) {
            onUi(() -> {
                boolean on = ApplicationLoader.isScreenOn;
                accountant.onScreen(on);
                if (!on) {
                    flush();
                }
                markDirty();
            });
        } else if (id == NotificationCenter.activeAccountChanged) {
            onUi(() -> {
                accountant.tick();
                flush();
                syncAccount();
                markDirty();
            });
        } else if (id == NotificationCenter.didEndCall) {
            onCallConnected(false);
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            onUi(() -> {
                voicePlaying = false;
                updatePassive();
            });
        } else if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged) {
            onUi(this::recomputeVoice);
        }
    }

    @Override
    public void onBecameForeground() {
        onUi(() -> {
            accountant.onForeground();
            markDirty();
        });
    }

    @Override
    public void onBecameBackground() {
        onUi(() -> {
            accountant.onBackground();
            flush();
        });
    }
}
