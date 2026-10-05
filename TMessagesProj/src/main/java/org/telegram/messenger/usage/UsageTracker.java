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

/** One application-lifetime observer; accounting and Telegram state reads stay on the UI thread. */
public final class UsageTracker implements NotificationCenter.NotificationCenterDelegate, ForegroundDetector.Listener,
        Application.ActivityLifecycleCallbacks {
    private static UsageTracker instance;
    private final EventClock clock = new EventClock();
    private final UsageAccountant accountant = new UsageAccountant(clock);
    private final UsageInputBuffer inputs = new UsageInputBuffer(this::deliverInput);
    private WeakReference<Activity> host = new WeakReference<>(null);
    private boolean resolvePosted;
    private boolean configurationTransition;
    private VoIPService callService;
    private long connectedCallAccount;
    private long lastEvent;
    private SurfaceKey resolved;
    private final Runnable resolveRunnable = () -> { resolvePosted = false; resolveNow(); };
    private final VoIPService.StateListener callListener = new VoIPService.StateListener() {
        @Override public void onStateChanged(int state) {
            if (callService == null) return;
            long account = state == VoIPService.STATE_ESTABLISHED
                    ? UserConfig.getInstance(callService.getAccount()).getClientUserId() : 0;
            if (connectedCallAccount == account) return;
            prepareEvent();
            connectedCallAccount = account;
            accountant.onCall(account == 0 ? null : new SurfaceKey(account, UsageSurface.CALL, 0));
            resolveNow();
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
        accountant.onPassive(passive());
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
        connectedCallAccount = 0;
        accountant.onCall(null);
        if (service != null) service.registerStateListener(callListener);
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
        } else if (id == NotificationCenter.didStartedCall || id == NotificationCenter.didEndCall || id == NotificationCenter.voipServiceCreated) {
            observeCallService();
        } else if (id == NotificationCenter.activeAccountChanged) {
            prepareEvent();
            accountant.onSurface(null);
            resolved = null;
        }
        onNavigationChanged();
    }

    @Override public void onBecameForeground() {
        configurationTransition = false;
        prepareEvent();
        accountant.onForeground(true);
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
    }
    @Override public void onActivityResumed(Activity activity) { host = new WeakReference<>(activity); onNavigationChanged(); }
    @Override public void onActivityDestroyed(Activity activity) { if (host.get() == activity) host.clear(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
