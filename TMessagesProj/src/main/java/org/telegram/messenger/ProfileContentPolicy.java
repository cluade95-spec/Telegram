package org.telegram.messenger;

/**
 * What a profile may show of a protected dialog's messages. Protection withholds MESSAGE CONTENT
 * only: the counts of a chat's photos, files, links, voice messages, music, GIFs and polls, and the
 * Saved Messages lists (the tab of Saved Messages kept from one peer, and the saved dialogs).
 * Everything else a profile has - its rows and sections, Stories, Gifts, group members, common
 * groups, recommendations, bot previews - never depends on protection, on whether a chat is locked
 * or unlocked, or on the app lock.
 *
 * <p>The decisions take only the lock state of the dialogs the content belongs to, so the answer is
 * the same wherever the profile is opened from, and a profile whose content is withheld has exactly
 * the structure of a profile that simply has no such messages.
 */
public final class ProfileContentPolicy {

    private ProfileContentPolicy() {
    }

    // SharedMediaLayout's tab ids. They must match SharedMediaLayout.TAB_*.
    public static final int TAB_PHOTOVIDEO = 0;
    public static final int TAB_FILES = 1;
    public static final int TAB_VOICE = 2;
    public static final int TAB_LINKS = 3;
    public static final int TAB_AUDIO = 4;
    public static final int TAB_GIF = 5;
    public static final int TAB_COMMON_GROUPS = 6;
    public static final int TAB_GROUPUSERS = 7;
    public static final int TAB_STORIES = 8;
    public static final int TAB_ARCHIVED_STORIES = 9;
    public static final int TAB_RECOMMENDED_CHANNELS = 10;
    public static final int TAB_SAVED_DIALOGS = 11;
    public static final int TAB_SAVED_MESSAGES = 12;
    public static final int TAB_BOT_PREVIEWS = 13;
    public static final int TAB_GIFTS = 14;
    public static final int TAB_POLL = 15;

    /** Index of the common groups count in SharedMediaLayout's own media counts (the only one that is not messages). */
    public static final int LAYOUT_COMMON_GROUPS_INDEX = 6;

    /** True when the tab lists messages (of the chat, or of Saved Messages). */
    public static boolean isMessageDerivedTab(int tab) {
        switch (tab) {
            case TAB_PHOTOVIDEO:
            case TAB_FILES:
            case TAB_VOICE:
            case TAB_LINKS:
            case TAB_AUDIO:
            case TAB_GIF:
            case TAB_POLL:
            case TAB_SAVED_DIALOGS:
            case TAB_SAVED_MESSAGES:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether a profile tab may be offered. Only a tab of messages is ever withheld: the chat's own
     * kinds of media while that chat is locked, the Saved Messages lists while Saved Messages is
     * locked. Stories, Gifts, members, common groups, recommendations and bot previews: always.
     */
    public static boolean tabAvailable(int tab, boolean dialogLocked, boolean savedMessagesLocked) {
        switch (tab) {
            case TAB_PHOTOVIDEO:
            case TAB_FILES:
            case TAB_VOICE:
            case TAB_LINKS:
            case TAB_AUDIO:
            case TAB_GIF:
            case TAB_POLL:
                return !dialogLocked;
            case TAB_SAVED_DIALOGS:
            case TAB_SAVED_MESSAGES:
                return !savedMessagesLocked;
            default:
                return true;
        }
    }

    /**
     * The media counts of a dialog as the preloader reports them (every entry counts messages of one
     * kind). While the dialog is locked there are none; the raw counts are untouched, so unlocking
     * needs no reload. Returns {@code counts} itself when nothing is withheld.
     */
    public static int[] visiblePreloaderCounts(int[] counts, boolean dialogLocked) {
        return dialogLocked ? new int[counts.length] : counts;
    }

    /**
     * The same for SharedMediaLayout's own array, whose entry {@link #LAYOUT_COMMON_GROUPS_INDEX} is
     * the number of common groups: that one is not a message count and is always kept.
     */
    public static int[] visibleLayoutCounts(int[] counts, boolean dialogLocked) {
        if (!dialogLocked) {
            return counts;
        }
        final int[] visible = new int[counts.length];
        if (LAYOUT_COMMON_GROUPS_INDEX < counts.length) {
            visible[LAYOUT_COMMON_GROUPS_INDEX] = counts[LAYOUT_COMMON_GROUPS_INDEX];
        }
        return visible;
    }

    /** The Saved Messages tab of a peer's profile (the messages kept from that peer). */
    public static boolean showSavedMessagesTab(boolean hasSavedMessages, boolean savedMessagesLocked) {
        return hasSavedMessages && !savedMessagesLocked;
    }

    /** The saved dialogs list of the own profile (Saved Messages grouped by peer). */
    public static boolean showSavedDialogsTab(boolean hasSavedDialogs, boolean savedMessagesLocked) {
        return hasSavedDialogs && !savedMessagesLocked;
    }

    /** Whether any message content of this profile is withheld (so that a change of lock state needs a refresh). */
    public static boolean isContentWithheld(boolean dialogLocked, boolean savedMessagesLocked) {
        return dialogLocked || savedMessagesLocked;
    }
}
