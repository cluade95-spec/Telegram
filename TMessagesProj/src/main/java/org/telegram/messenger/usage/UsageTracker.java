package org.telegram.messenger.usage;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.SecretMediaViewer;
import org.telegram.ui.Stories.StoryViewer;
import org.telegram.ui.Components.ForegroundDetector;
import java.lang.ref.WeakReference;
import java.time.ZoneId;
import java.util.Map;
import java.util.ArrayList;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

/** One application-lifetime observer; accounting and Telegram state reads stay on the UI thread. */
public final class UsageTracker implements NotificationCenter.NotificationCenterDelegate, ForegroundDetector.Listener,
        Application.ActivityLifecycleCallbacks {
    private static UsageTracker instance;
    private final EventClock clock = new EventClock();
    private final UsageMetrics metrics = new UsageMetrics();
    private final UsageAccountant accountant = new UsageAccountant(clock, metrics);
    private final UsageInputBuffer inputs = new UsageInputBuffer(this::deliverInput);
    private WeakReference<Activity> host = new WeakReference<>(null);
    private boolean resolvePosted;
    private boolean configurationTransition;
    private VoIPService callService;
    private long callServiceAccount;
    private long connectedCallAccount;
    private long lastEvent;
    private SurfaceKey resolved;
    private boolean wasForeground;
    private boolean wasPassive;
    private int lastMigrationDay;
    private boolean sendFlushPosted;
    private final Runnable sendFlush=()->{ sendFlushPosted=false; flushNow(); };
    private final Runnable resolveRunnable = () -> { resolvePosted = false; resolveNow(); };
    private final VoIPService.StateListener callListener = new VoIPService.StateListener() {
        @Override public void onStateChanged(int state) {
            if (callService == null) return;
            long account = state == VoIPService.STATE_ESTABLISHED
                    ? callServiceAccount : 0;
            if (connectedCallAccount == account) return;
            prepareEvent();
            connectedCallAccount = account;
            accountant.onCall(account == 0 ? null : new SurfaceKey(account, UsageSurface.CALL, 0));
            resolveNow();
            flushNow();
        }
    };

    private static final class EventClock implements UsageClock {
        long event = -1;
        @Override public long elapsed() { return event >= 0 ? event : SystemClock.elapsedRealtime(); }
        @Override public long wallMillis() {
            return System.currentTimeMillis() - (event >= 0 ? Math.max(0, SystemClock.elapsedRealtime() - event) : 0);
        }
        @Override public ZoneId zone() { return ZoneId.systemDefault(); }
    }

    private UsageTracker() {
        host = new WeakReference<>(LaunchActivity.instance);
        ForegroundDetector.getInstance().addListener(this);
        ((Application) ApplicationLoader.applicationContext).registerActivityLifecycleCallbacks(this);
        NotificationCenter global = NotificationCenter.getGlobalInstance();
        for (int id : new int[]{NotificationCenter.screenStateChanged, NotificationCenter.activeAccountChanged,
                NotificationCenter.didStartedCall, NotificationCenter.didEndCall}) global.addObserver(this, id);
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            NotificationCenter center = NotificationCenter.getInstance(slot);
            for (int id : new int[]{NotificationCenter.messagePlayingDidStart, NotificationCenter.messagePlayingPlayStateChanged,
                    NotificationCenter.messagePlayingDidReset, NotificationCenter.voipServiceCreated}) center.addObserver(this, id);
        }
        accountant.onScreen(ApplicationLoader.isScreenOn);
        accountant.onForeground(ForegroundDetector.getInstance().isForeground());
        wasForeground = ForegroundDetector.getInstance().isForeground();
        if (wasForeground) metrics.opened(selectedUser(), clock.wallMillis(), clock.zone());
        observeCallService();
    }

    public static void init() {
        if (instance == null) {
            instance = new UsageTracker();
            onNavigationChanged();
        }
    }

    public static void onUserInput() {
        UsageTracker tracker = instance;
        if (tracker != null) tracker.inputs.record(SystemClock.elapsedRealtime());
    }

    public static void onNavigationChanged() {
        UsageTracker tracker = instance;
        if (tracker != null && !tracker.resolvePosted) {
            tracker.resolvePosted = true;
            AndroidUtilities.runOnUIThread(tracker.resolveRunnable);
        }
    }

    private void deliverInput(long elapsed) {
        if (elapsed < lastEvent) return;
        clock.event = elapsed;
        accountant.onInput();
        lastEvent = elapsed;
        clock.event = -1;
        if (accountant.pendingMillis() >= UsagePolicy.FLUSH_MS) onNavigationChanged();
    }

    private void prepareEvent() {
        inputs.deliver();
        clock.event = -1;
        lastEvent = SystemClock.elapsedRealtime();
    }

    private boolean foreground() {
        Activity activity = host.get();
        if (!ForegroundDetector.getInstance().isForeground() && !configurationTransition) return false;
        return activity == null || Build.VERSION.SDK_INT < 24 || !activity.isInPictureInPictureMode();
    }

    private void resolveNow() {
        prepareEvent();
        accountant.onForeground(foreground());
        SurfaceKey key = UsageSurfaceResolver.resolve(host.get(), 0);
        if (!key.equals(resolved)) {
            resolved = key;
            accountant.onSurface(key.accountUserId == 0 ? null : key);
        }
        boolean engaged = passive();
        accountant.onPassive(engaged);
        boolean changed = wasPassive != engaged;
        wasPassive = engaged;
        if (changed || accountant.pendingMillis() >= UsagePolicy.FLUSH_MS) flushNow();
    }

    private boolean passive() {
        if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isUsageVideoPlaying()) return true;
        if (SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isUsageVideoPlaying()) return true;
        BaseFragment top = LaunchActivity.getLastFragment();
        if (top != null && top.getLastStoryViewer() != null && top.getLastStoryViewer().isUsagePlaying()) return true;
        for (StoryViewer story : StoryViewer.globalInstances) if (story.isUsagePlaying()) return true;
        MediaController media = MediaController.getInstance();
        MessageObject message = media.getPlayingMessageObject();
        return message != null && !media.isMessagePaused() && (message.isVoice() || message.isRoundVideo());
    }

    private void observeCallService() {
        VoIPService service = VoIPService.getSharedInstance();
        if (service == callService) return;
        prepareEvent();
        if (callService != null) callService.unregisterStateListener(callListener);
        callService = service;
        callServiceAccount = service == null ? 0 : UserConfig.getInstance(service.getAccount()).getClientUserId();
        connectedCallAccount = 0;
        accountant.onCall(null);
        if (service != null) service.registerStateListener(callListener);
        flushNow();
    }

    private static long selectedUser() { return UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId(); }

    private void flushNow() { flushNow(0); }

    private void flushNow(long removedAccount) {
        prepareEvent();
        ArrayList<UsageStore.Migration> migrations = new ArrayList<>();
        int migrationDay=UsageMetrics.day(java.time.LocalDate.now());
        if (migrationDay!=lastMigrationDay) for (int slot=0; slot<UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            long uid=UserConfig.getInstance(slot).getClientUserId();
            if (uid==0) continue;
            for (TLRPC.Chat chat:MessagesController.getInstance(slot).getChats().values()) {
                if (chat.migrated_to!=null) migrations.add(new UsageStore.Migration(uid,-chat.id,-chat.migrated_to.channel_id));
            }
        }
        lastMigrationDay=migrationDay;
        UsageStore.getInstance().flush(accountant.drain(), metrics.drain(), migrations, removedAccount);
    }

    public static void flush() {
        if (instance == null) init();
        instance.resolveNow();
        instance.flushNow();
    }

    public static void reset(java.util.function.Consumer<Boolean> done) {
        if (instance == null) init();
        instance.prepareEvent();
        instance.accountant.drain();
        instance.metrics.reset();
        UsageStore.getInstance().reset(done);
    }

    public static void onAccountRemoved(long uid) {
        AndroidUtilities.runOnUIThread(() -> {
            if (instance != null) {
                instance.prepareEvent();
                if (instance.callServiceAccount==uid) instance.callServiceAccount=0;
                if (instance.connectedCallAccount==uid) {
                    instance.accountant.onCall(null);
                    instance.connectedCallAccount=0;
                }
                if (instance.resolved!=null && instance.resolved.accountUserId==uid) {
                    instance.accountant.onSurface(null);
                    instance.resolved=null;
                }
                instance.flushNow(uid);
                instance.metrics.onAccountRemoved(uid);
            } else UsageStore.getInstance().onAccountRemoved(uid);
            onNavigationChanged();
        });
    }

    /** Captured identity is independent of account-slot reuse; storage deduplicates atomically. */
    public static void onMessageSent(long uid, long dialog, long message, boolean secret) {
        if (!UsageSendIdentity.eligible(uid,message,true,true,false,secret)) return;
        long observedWall=System.currentTimeMillis();
        ZoneId zone=ZoneId.systemDefault();
        AndroidUtilities.runOnUIThread(() -> {
            if (instance==null) init();
            instance.prepareEvent();
            instance.accountant.checkpoint();
            UsageStore.getInstance().confirmed(uid,dialog,message,secret,observedWall,zone);
            boolean activeAccount=false;
            for (int slot=0; slot<UserConfig.MAX_ACCOUNT_COUNT; slot++)
                if (UserConfig.getInstance(slot).getClientUserId()==uid) { activeAccount=true; break; }
            if (!activeAccount) {
                // Late success after logout retains aggregate attribution, never restores dialog identities.
                instance.flushNow(uid);
                return;
            }
            if (!instance.sendFlushPosted) {
                instance.sendFlushPosted=true;
                AndroidUtilities.runOnUIThread(instance.sendFlush);
            }
        });
    }

    public static Map<UsageLedger.BucketKey, Long> drain() {
        if (instance == null) init();
        instance.resolveNow();
        return instance.accountant.drain();
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.screenStateChanged) {
            prepareEvent();
            accountant.onScreen(ApplicationLoader.isScreenOn);
            if (!ApplicationLoader.isScreenOn) flushNow();
        } else if (id == NotificationCenter.didStartedCall || id == NotificationCenter.didEndCall || id == NotificationCenter.voipServiceCreated) {
            observeCallService();
        } else if (id == NotificationCenter.activeAccountChanged) {
            prepareEvent();
            accountant.onSurface(null);
            flushNow();
            if (connectedCallAccount==0) metrics.endSession();
            resolved = null;
        }
        onNavigationChanged();
    }

    @Override public void onBecameForeground() {
        configurationTransition = false;
        prepareEvent();
        accountant.onForeground(true);
        if (!wasForeground) metrics.opened(selectedUser(), clock.wallMillis(), clock.zone());
        wasForeground=true;
        onNavigationChanged();
    }
    @Override public void onBecameBackground() {
        Activity activity = host.get();
        if (activity != null && activity.isChangingConfigurations()) {
            configurationTransition = true;
            return;
        }
        prepareEvent();
        accountant.onForeground(false);
        wasForeground=false;
        metrics.endSession();
        flushNow();
    }
    @Override public void onActivityResumed(Activity activity) { host = new WeakReference<>(activity); onNavigationChanged(); }
    @Override public void onActivityDestroyed(Activity activity) { if (host.get() == activity) host.clear(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
