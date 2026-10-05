package org.telegram.messenger.localhistory;

/**
 * Marks the fragments that belong to the Local History chat. The Protected Chats gate treats them as the chat with the
 * reserved id, so locking the chat covers all of them (plan B28 items 2, 5 and 7).
 */
public interface LocalHistoryScreen {

    /** True for the feed and the info page, which count as the conversation itself; false for sub-screens like editing. */
    boolean isLocalHistoryConversation();
}
