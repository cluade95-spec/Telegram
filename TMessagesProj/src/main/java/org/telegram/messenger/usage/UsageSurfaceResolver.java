package org.telegram.messenger.usage;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
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

import java.util.List;

/**
 * Android adapter of the classifier (Reference A, A8): gathers plain facts from {@link LaunchActivity} and hands them to
 * {@link UsageClassifier}. The single place to update when upstream adds a navigation container or a fragment type.
 * Call on the UI thread only.
 */
public final class UsageSurfaceResolver {

    private UsageSurfaceResolver() {
    }

    public static UsageClassifier.SurfaceKey resolve(boolean callConnected) {
        UsageClassifier.OverlayFacts overlays = new UsageClassifier.OverlayFacts();
        overlays.callConnected = callConnected;
        overlays.passcode = SharedConfig.appLocked;
        overlays.mediaViewer = (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible())
                || (SecretMediaViewer.hasInstance() && SecretMediaViewer.getInstance().isVisible());
        overlays.instantView = ArticleViewer.hasInstance() && ArticleViewer.getInstance().isVisible();
        for (int i = 0; i < StoryViewer.globalInstances.size(); i++) {
            if (StoryViewer.globalInstances.get(i).isShown()) {
                overlays.stories = true;
                break;
            }
        }

        BaseFragment top = topFragment();
        if (!overlays.stories && top != null) {
            StoryViewer sv = top.getLastStoryViewer();
            overlays.stories = sv != null && sv.isShown();
        }
        return UsageClassifier.classify(facts(top), overlays, inSettingsContext(top));
    }

    /** The fragment the user is looking at, across sheets, tablet panes, bubbles and main tabs. */
    static BaseFragment topFragment() {
        BubbleActivity bubble = BubbleActivity.instance;
        if (bubble != null && bubble.actionBarLayout != null) {
            return unwrap(bubble.actionBarLayout.getLastFragment());
        }
        LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return null;
        }
        if (!activity.sheetFragmentsStack.isEmpty()) {
            return unwrap(activity.sheetFragmentsStack.get(activity.sheetFragmentsStack.size() - 1).getLastFragment());
        }
        if (AndroidUtilities.isTablet()) {
            INavigationLayout layers = activity.getLayersActionBarLayout();
            if (layers != null && !layers.getFragmentStack().isEmpty() && layers.getView() != null && layers.getView().getVisibility() == android.view.View.VISIBLE) {
                return unwrap(layers.getLastFragment());
            }
            INavigationLayout right = activity.getRightActionBarLayout();
            if (right != null && !right.getFragmentStack().isEmpty()) {
                return unwrap(right.getLastFragment());
            }
        }
        return activity.actionBarLayout != null ? unwrap(activity.actionBarLayout.getLastFragment()) : null;
    }

    private static BaseFragment unwrap(BaseFragment fragment) {
        if (fragment instanceof MainTabsActivity) {
            BaseFragment current = ((MainTabsActivity) fragment).getCurrentVisibleFragment();
            return current != null ? current : fragment;
        }
        return fragment;
    }

    /** SettingsActivity is in the main stack below the top fragment. */
    private static boolean inSettingsContext(BaseFragment top) {
        LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.actionBarLayout == null) {
            return false;
        }
        List<BaseFragment> stack = activity.actionBarLayout.getFragmentStack();
        for (int i = 0; i < stack.size(); i++) {
            BaseFragment f = unwrap(stack.get(i));
            if (f != top && f instanceof SettingsActivity) {
                return true;
            }
        }
        return false;
    }

    static UsageClassifier.FragmentFacts facts(BaseFragment f) {
        if (f instanceof ChatActivity) {
            ChatActivity chat = (ChatActivity) f;
            UsageClassifier.FragmentFacts r = UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.CHAT);
            r.dialogId = chat.getDialogId();
            r.chatMode = chat.getChatMode();
            r.isEncrypted = chat.getCurrentEncryptedChat() != null || DialogObject.isEncryptedDialog(r.dialogId);
            TLRPC.User user = chat.getCurrentUser();
            TLRPC.Chat c = chat.getCurrentChat();
            if (user != null) {
                r.isUser = true;
                r.isSelf = UserObject.isUserSelf(user);
                r.isBot = UserObject.isBot(user);
            } else if (c != null) {
                r.isChannel = ChatObject.isChannelAndNotMegaGroup(c) || ChatObject.isMonoForum(c);
            }
            return r;
        }
        if (f instanceof DialogsActivity) {
            DialogsActivity d = (DialogsActivity) f;
            UsageClassifier.FragmentFacts r = UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.DIALOGS);
            r.searchShown = d.isSearchShown();
            r.selectMode = d.isSelectMode();
            return r;
        }
        if (f instanceof TopicsFragment) {
            UsageClassifier.FragmentFacts r = UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.TOPICS);
            r.dialogId = ((TopicsFragment) f).getDialogId();
            return r;
        }
        if (f instanceof org.telegram.messenger.localhistory.LocalHistoryScreen) {
            return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.LOCAL_HISTORY);
        }
        if (f instanceof ProfileActivity || f instanceof ProfileActivity2) {
            return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.PROFILE);
        }
        if (f instanceof SettingsActivity) {
            return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.SETTINGS);
        }
        if (f instanceof CallLogActivity) {
            return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.CALL_LOG);
        }
        if (f instanceof ContactsActivity) {
            return UsageClassifier.FragmentFacts.of(UsageClassifier.Kind.CONTACTS);
        }
        return UsageClassifier.FragmentFacts.of(f == null ? UsageClassifier.Kind.NONE : UsageClassifier.Kind.OTHER);
    }
}
