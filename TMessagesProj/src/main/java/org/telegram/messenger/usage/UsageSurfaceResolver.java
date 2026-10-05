package org.telegram.messenger.usage;

import android.app.Activity;
import android.view.View;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ArticleViewer;
import org.telegram.ui.BubbleActivity;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.ProfileActivity2;
import org.telegram.ui.SecretMediaViewer;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Stories.StoryViewer;
import org.telegram.ui.TopicsFragment;
import java.util.WeakHashMap;

/** UI-thread adapter. Origins belong to fragment instances, never to an account or a global last tab. */
public final class UsageSurfaceResolver {
    private static final WeakHashMap<BaseFragment, Boolean> settingsOrigins = new WeakHashMap<>();

    private UsageSurfaceResolver() { }

    public static void inheritSettingsOrigin(BaseFragment destination, BaseFragment source) {
        if (destination == null || settingsOrigins.containsKey(destination)) return;
        source = unwrap(source);
        boolean settings = source instanceof SettingsActivity
                || source != null && facts(source).kind == UsageClassifier.Kind.OTHER && settingsOrigins.containsKey(source);
        if (settings) settingsOrigins.put(destination, Boolean.TRUE);
    }

    public static void forget(BaseFragment fragment) {
        settingsOrigins.remove(fragment);
    }

    public static SurfaceKey resolve(Activity host, long connectedCallAccount) {
        BaseFragment top = topFragment(host);
        int slot = top == null ? UserConfig.selectedAccount : top.getCurrentAccount();
        long account = UserConfig.getInstance(slot).getClientUserId();
        boolean media = PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()
                || SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isVisible();
        StoryViewer activeStory = top == null ? null : top.getLastStoryViewer();
        boolean stories = activeStory != null && activeStory.isShown();
        if (!stories) {
            for (StoryViewer viewer : StoryViewer.globalInstances) {
                if (viewer.isShown()) { stories = true; activeStory = viewer; break; }
            }
        }
        boolean article = ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible()
                || top != null && top.getLastSheet() instanceof ArticleViewer.Sheet && top.getLastSheet().isShown();
        if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) slot = PhotoViewer.getInstance().getUsageAccount();
        else if (SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isVisible()) slot = SecretMediaViewer.getInstance().getUsageAccount();
        else if (stories) slot = activeStory.currentAccount;
        else if (ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible()) slot = ArticleViewer.getInstance().getUsageAccount();
        account = UserConfig.getInstance(slot).getClientUserId();
        boolean locked = host instanceof LaunchActivity && ((LaunchActivity) host).isUsagePasscodeVisible();
        return UsageClassifier.classify(account, facts(top),
                new UsageClassifier.OverlayFacts(connectedCallAccount, media, stories, article, locked),
                top != null && settingsOrigins.containsKey(top));
    }

    private static BaseFragment topFragment(Activity host) {
        if (host instanceof BubbleActivity) {
            INavigationLayout layout = ((BubbleActivity) host).actionBarLayout;
            return unwrap(layout == null ? null : layout.getLastFragment());
        }
        if (!(host instanceof LaunchActivity)) return null;
        LaunchActivity activity = (LaunchActivity) host;
        if (!activity.sheetFragmentsStack.isEmpty()) {
            return unwrap(activity.sheetFragmentsStack.get(activity.sheetFragmentsStack.size() - 1).getLastFragment());
        }
        BaseFragment top = visibleTop(activity.getLayersActionBarLayout());
        if (top != null) return unwrap(top);
        top = visibleTop(activity.getRightActionBarLayout());
        if (top != null && !activity.isUsageTabletFullSize()) return unwrap(top);
        INavigationLayout main = activity.getActionBarLayout();
        return unwrap(main == null ? null : main.getLastFragment());
    }

    private static BaseFragment visibleTop(INavigationLayout layout) {
        return layout == null || layout.getView().getVisibility() != View.VISIBLE ? null : layout.getLastFragment();
    }

    private static BaseFragment unwrap(BaseFragment fragment) {
        return fragment instanceof MainTabsActivity ? ((MainTabsActivity) fragment).getCurrentVisibleFragment() : fragment;
    }

    private static UsageClassifier.FragmentFacts facts(BaseFragment fragment) {
        if (fragment instanceof ChatActivity) {
            ChatActivity chat = (ChatActivity) fragment;
            return new UsageClassifier.FragmentFacts(UsageClassifier.Kind.CHAT, chat.getDialogId(), chat.getChatMode(),
                    chat.getCurrentEncryptedChat() != null || DialogObject.isEncryptedDialog(chat.getDialogId()), UserObject.isUserSelf(chat.getCurrentUser()), UserObject.isBot(chat.getCurrentUser()),
                    ChatObject.isChannelAndNotMegaGroup(chat.getCurrentChat()), ChatObject.isMonoForum(chat.getCurrentChat()), false, false);
        }
        if (fragment instanceof DialogsActivity) {
            DialogsActivity dialogs = (DialogsActivity) fragment;
            return new UsageClassifier.FragmentFacts(UsageClassifier.Kind.LIST, 0, 0, false, false, false, false, false,
                    dialogs.isUsageSearchShown(), dialogs.isUsageSelectionOnly());
        }
        if (fragment instanceof org.telegram.ui.Components.HashtagActivity) return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.SEARCH);
        if (fragment instanceof TopicsFragment) return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.TOPICS);
        if (fragment instanceof ProfileActivity || fragment instanceof ProfileActivity2) return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.PROFILE);
        if (fragment instanceof SettingsActivity) return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.SETTINGS);
        if (fragment instanceof ContactsActivity || fragment instanceof CallLogActivity) return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.EXPLICIT_OTHER);
        return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.OTHER);
    }
}
