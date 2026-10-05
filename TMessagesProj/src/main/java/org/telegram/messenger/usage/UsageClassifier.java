package org.telegram.messenger.usage;

/**
 * Pure mapping from facts about what is on screen to a surface key (Reference A, A7-A8). The Android adapter
 * ({@link UsageSurfaceResolver}) only gathers the facts.
 *
 * Priority: call connected > passcode > media viewer > stories > Instant View > top fragment.
 */
public final class UsageClassifier {

    private UsageClassifier() {
    }

    // ChatActivity.MODE_* values (kept here so the classifier stays free of Android classes)
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_SCHEDULED = 1;
    public static final int MODE_PINNED = 2;
    public static final int MODE_SAVED = 3;
    public static final int MODE_QUICK_REPLIES = 5;
    public static final int MODE_EDIT_BUSINESS_LINK = 6;
    public static final int MODE_SEARCH = 7;
    public static final int MODE_SUGGESTIONS = 8;
    public static final int MODE_WELCOME_MESSAGES = 9;

    public enum Kind {
        NONE, CHAT, DIALOGS, TOPICS, PROFILE, SETTINGS, CALL_LOG, CONTACTS, LOCAL_HISTORY, OTHER
    }

    /** Plain facts about the top fragment. */
    public static final class FragmentFacts {
        public Kind kind = Kind.NONE;
        public long dialogId;
        public int chatMode;
        public boolean isUser, isSelf, isBot, isChannel /* broadcast or mono forum */, isEncrypted;
        public boolean searchShown, selectMode;

        public static FragmentFacts of(Kind kind) {
            FragmentFacts f = new FragmentFacts();
            f.kind = kind;
            return f;
        }
    }

    public static final class OverlayFacts {
        public boolean callConnected, passcode, mediaViewer, stories, instantView;
    }

    public static final class SurfaceKey {
        public final UsageSurface surface;
        public final long dialog;

        public SurfaceKey(UsageSurface surface, long dialog) {
            this.surface = surface;
            this.dialog = surface.isChat() ? dialog : 0;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SurfaceKey && ((SurfaceKey) o).surface == surface && ((SurfaceKey) o).dialog == dialog;
        }

        @Override
        public int hashCode() {
            return surface.id * 31 + Long.hashCode(dialog);
        }

        @Override
        public String toString() {
            return surface + (dialog != 0 ? "#" + dialog : "");
        }
    }

    public static SurfaceKey classify(FragmentFacts top, OverlayFacts overlays, boolean inSettingsContext) {
        if (overlays != null) {
            if (overlays.callConnected) {
                return new SurfaceKey(UsageSurface.CALL, 0);
            }
            if (overlays.passcode) {
                return new SurfaceKey(UsageSurface.OTHER, 0);
            }
            if (overlays.mediaViewer) {
                return new SurfaceKey(UsageSurface.MEDIA_VIEWER, 0);
            }
            if (overlays.stories) {
                return new SurfaceKey(UsageSurface.STORIES, 0);
            }
            if (overlays.instantView) {
                return new SurfaceKey(UsageSurface.MEDIA_VIEWER, 0);
            }
        }
        if (top == null) {
            return new SurfaceKey(UsageSurface.OTHER, 0);
        }
        switch (top.kind) {
            case CHAT:
                return classifyChat(top);
            case DIALOGS:
                if (top.selectMode) {
                    return new SurfaceKey(UsageSurface.OTHER, 0);
                }
                return new SurfaceKey(top.searchShown ? UsageSurface.SEARCH : UsageSurface.CHAT_LIST, 0);
            case TOPICS:
                return new SurfaceKey(UsageSurface.CHAT_GROUP, top.dialogId);
            case PROFILE:
                return new SurfaceKey(UsageSurface.PROFILE, 0);
            case LOCAL_HISTORY:
                // the feed, its info page and its edit screen; no dialog id, the reserved one is not a real peer
                return new SurfaceKey(UsageSurface.LOCAL_HISTORY, 0);
            case SETTINGS:
                return new SurfaceKey(UsageSurface.SETTINGS, 0);
            case CALL_LOG:
            case CONTACTS:
                return new SurfaceKey(UsageSurface.OTHER, 0);
            default:
                return new SurfaceKey(inSettingsContext ? UsageSurface.SETTINGS : UsageSurface.OTHER, 0);
        }
    }

    private static SurfaceKey classifyChat(FragmentFacts f) {
        if (f.isEncrypted) {
            return new SurfaceKey(UsageSurface.CHAT_SECRET, f.dialogId);
        }
        switch (f.chatMode) {
            case MODE_SEARCH:
                return new SurfaceKey(UsageSurface.SEARCH, 0);
            case MODE_QUICK_REPLIES:
            case MODE_EDIT_BUSINESS_LINK:
            case MODE_WELCOME_MESSAGES:
                return new SurfaceKey(UsageSurface.SETTINGS, 0);
            default:
                break;
        }
        if (f.isUser) {
            if (f.isSelf || f.chatMode == MODE_SAVED) {
                return new SurfaceKey(UsageSurface.CHAT_SAVED, f.dialogId);
            }
            return new SurfaceKey(f.isBot ? UsageSurface.CHAT_BOT : UsageSurface.CHAT_PRIVATE, f.dialogId);
        }
        if (f.chatMode == MODE_SAVED) {
            return new SurfaceKey(UsageSurface.CHAT_SAVED, f.dialogId);
        }
        return new SurfaceKey(f.isChannel ? UsageSurface.CHAT_CHANNEL : UsageSurface.CHAT_GROUP, f.dialogId);
    }
}
