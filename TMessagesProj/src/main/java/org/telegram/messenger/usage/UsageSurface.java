package org.telegram.messenger.usage;

/**
 * Where active time is spent. Ids are stored in the database and must never change.
 */
public enum UsageSurface {
    CHAT_PRIVATE(1, Category.PRIVATE),
    CHAT_SECRET(2, Category.PRIVATE),
    CHAT_BOT(3, Category.BOTS),
    CHAT_SAVED(4, Category.OTHER),
    CHAT_GROUP(5, Category.GROUPS),
    CHAT_CHANNEL(6, Category.CHANNELS),
    CHAT_LIST(7, Category.CHAT_LIST),
    SEARCH(8, Category.SEARCH),
    STORIES(9, Category.STORIES),
    MEDIA_VIEWER(10, Category.MEDIA),
    PROFILE(11, Category.OTHER),
    SETTINGS(12, Category.OTHER),
    CALL(13, Category.CALLS),
    LOCAL_HISTORY(14, Category.LOCAL_HISTORY),
    OTHER(15, Category.OTHER);

    /** Display categories; the report never shows overlapping categories. */
    public enum Category {
        PRIVATE, BOTS, GROUPS, CHANNELS, CHAT_LIST, SEARCH, STORIES, MEDIA, CALLS, LOCAL_HISTORY, OTHER
    }

    public final int id;
    public final Category category;

    UsageSurface(int id, Category category) {
        this.id = id;
        this.category = category;
    }

    /** True for surfaces that carry a dialog id. */
    public boolean isChat() {
        return this == CHAT_PRIVATE || this == CHAT_SECRET || this == CHAT_BOT || this == CHAT_SAVED || this == CHAT_GROUP || this == CHAT_CHANNEL || this == LOCAL_HISTORY;
    }

    public static UsageSurface fromId(int id) {
        for (UsageSurface s : values()) {
            if (s.id == id) {
                return s;
            }
        }
        return OTHER;
    }
}
