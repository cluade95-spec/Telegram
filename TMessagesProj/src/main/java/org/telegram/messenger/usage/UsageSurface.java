package org.telegram.messenger.usage;

public enum UsageSurface {
    CHAT_PRIVATE(1, Category.PRIVATE_CHATS),
    CHAT_SECRET(2, Category.PRIVATE_CHATS),
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

    public enum Category {
        PRIVATE_CHATS, GROUPS, CHANNELS, BOTS, CHAT_LIST, SEARCH,
        STORIES, MEDIA, CALLS, LOCAL_HISTORY, OTHER
    }

    public final int id;
    public final Category category;

    UsageSurface(int id, Category category) {
        this.id = id;
        this.category = category;
    }
}
