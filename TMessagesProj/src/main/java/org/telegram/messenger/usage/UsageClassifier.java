package org.telegram.messenger.usage;

/** Pure rules; Android types and navigation state are inspected only by the resolver. */
public final class UsageClassifier {
    public enum Kind { CHAT, LIST, TOPICS, PROFILE, SETTINGS, SEARCH, OTHER, EXPLICIT_OTHER }

    public static final class FragmentFacts {
        public final Kind kind;
        public final long dialog;
        public final int mode;
        public final boolean encrypted, self, bot, channel, monoForum, search, selection;

        public FragmentFacts(Kind kind, long dialog, int mode, boolean encrypted, boolean self,
                boolean bot, boolean channel, boolean monoForum, boolean search, boolean selection) {
            this.kind = kind;
            this.dialog = dialog;
            this.mode = mode;
            this.encrypted = encrypted;
            this.self = self;
            this.bot = bot;
            this.channel = channel;
            this.monoForum = monoForum;
            this.search = search;
            this.selection = selection;
        }

        public static FragmentFacts of(Kind kind) {
            return new FragmentFacts(kind, 0, 0, false, false, false, false, false, false, false);
        }
    }

    public static final class OverlayFacts {
        public final long callAccount;
        public final boolean media, stories, article, locked;
        public OverlayFacts(long callAccount, boolean media, boolean stories, boolean article, boolean locked) {
            this.callAccount = callAccount;
            this.media = media;
            this.stories = stories;
            this.article = article;
            this.locked = locked;
        }
    }

    private UsageClassifier() { }

    public static SurfaceKey classify(long account, FragmentFacts facts, OverlayFacts overlays, boolean settingsOrigin) {
        if (overlays != null) {
            if (overlays.callAccount != 0) return new SurfaceKey(overlays.callAccount, UsageSurface.CALL, 0);
            if (overlays.locked) return new SurfaceKey(account, UsageSurface.OTHER, 0);
            if (overlays.media) return new SurfaceKey(account, UsageSurface.MEDIA_VIEWER, 0);
            if (overlays.stories) return new SurfaceKey(account, UsageSurface.STORIES, 0);
            if (overlays.article) return new SurfaceKey(account, UsageSurface.MEDIA_VIEWER, 0);
        }
        if (facts == null) return new SurfaceKey(account, UsageSurface.OTHER, 0);
        UsageSurface surface;
        long dialog = 0;
        switch (facts.kind) {
            case CHAT:
                if (facts.mode == 7) surface = UsageSurface.SEARCH;
                else if (facts.mode == 5 || facts.mode == 6 || facts.mode == 9) surface = UsageSurface.SETTINGS;
                else {
                    dialog = facts.dialog;
                    if (facts.encrypted) surface = UsageSurface.CHAT_SECRET;
                    else if (facts.self || facts.mode == 3) surface = UsageSurface.CHAT_SAVED;
                    else if (facts.bot) surface = UsageSurface.CHAT_BOT;
                    else if (facts.channel || facts.monoForum) surface = UsageSurface.CHAT_CHANNEL;
                    else if (dialog < 0) surface = UsageSurface.CHAT_GROUP;
                    else if (dialog > 0) surface = UsageSurface.CHAT_PRIVATE;
                    else surface = UsageSurface.OTHER;
                }
                break;
            case LIST: surface = facts.selection ? UsageSurface.OTHER : facts.search ? UsageSurface.SEARCH : UsageSurface.CHAT_LIST; break;
            case TOPICS: surface = UsageSurface.CHAT_LIST; break;
            case SEARCH: surface = UsageSurface.SEARCH; break;
            case PROFILE: surface = UsageSurface.PROFILE; break;
            case SETTINGS: surface = UsageSurface.SETTINGS; break;
            case OTHER: surface = settingsOrigin ? UsageSurface.SETTINGS : UsageSurface.OTHER; break;
            default: surface = UsageSurface.OTHER;
        }
        return new SurfaceKey(account, surface, dialog);
    }
}
