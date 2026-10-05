# Local History Chat: source research

Baseline: `master` at `055a0c3b44357ae0cfe7bea592d46d54d016cbe1` (merge of PR #9, head `de813684`). No commits landed after it.
Paths are relative to `TMessagesProj/src/main/java/org/telegram/`. Abbreviations: `MC` = `messenger/MessagesController.java`,
`MS` = `messenger/MessagesStorage.java`, `FL` = `messenger/FileLoader.java`, `CA` = `ui/ChatActivity.java`.
Line numbers are from this baseline and are given to find the code, not as a contract; method names are the contract.

Threads:

| Name | What it is | Used for |
| --- | --- | --- |
| stage | `Utilities.stageQueue` | `MC.processUpdates`, `MC.processUpdateArray`, getDifference processing |
| storage | `MS.storageQueue` (`getStorageQueue()`), one serial queue per account | every `cache4.db` read/write |
| fileLoader | `FileLoader.fileLoaderQueue` | `FL.deleteFiles` |
| UI | `AndroidUtilities.runOnUIThread` | `NotificationCenter` posts, fragments |

---

## 1. Incoming edits (private chats)

### 1.1 Update types and entry points

* `TL_update.TL_updateEditMessage` (private chats and basic groups, common pts) and `TL_update.TL_updateEditChannelMessage`
  (channels, channel pts). There is no short edit variant.
* Live: `ConnectionsManager.onUnparsedMessageReceived` -> stage -> `MC.processUpdates(updates, false)` -> pts check
  (`getLastPtsValue() + pts_count == pts`) -> `MC.processUpdateArray(updates, users, chats, false, date)`. A batch whose pts was
  already applied is dropped; a gap goes to `updatesQueuePts` or `getDifference`.
* Reconnect: `MC.getDifference` -> `processUpdateArray(res.other_updates, res.users, res.chats, true, 0)`. Messages that were
  sent **and** edited while offline arrive in `res.new_messages` already in their edited form: no edit event exists for them.
* Our own `messages.editMessage` result also returns `TL_updateEditMessage` (with `message.out == true`).

### 1.2 Path inside `processUpdateArray` (stage thread)

`MC.processUpdateArray` (~L18400) collects edits in `LongSparseArray<ArrayList<MessageObject>> editingMessages` keyed by
`message.dialog_id` (branch at ~L19418-19516): fixes `out` (from_id == self), sets `unread` from read max, `ImageLoader.saveMessageThumbs`,
posts `SendMessagesHelper.onMessageEdited`, builds a `MessageObject`.

After the loop (~L19825-19839), still on stage:

```java
getMessagesStorage().putMessages(messagesRes, editingMessages.keyAt(b), -2, 0, false, 0, 0);   // posts to storage
getMessagesStorage().getStorageQueue().postRunnable(() -> AndroidUtilities.runOnUIThread(() -> {
    getNotificationsController().processEditedMessages(editingMessagesFinal);
    getTopicsController().processEditedMessages(editingMessagesFinal); }));
```

The UI part (`NotificationCenter.replaceMessagesObjects`, ~L20901) is posted **directly** to the UI thread, so the UI can show the
new version before storage has written it. `ChatActivity.replaceMessageObjects` only has the old `MessageObject` if that chat is
loaded; `MC.dialogMessage` only has it for the dialog's top message. **The UI is not a reliable source of the old version.**

### 1.3 Where old and new both exist: `MS.putMessages(messages_Messages, dialogId, load_type = -2, ...)`

`MS.putMessages(TLRPC.messages_Messages, long, int, int, boolean, int, long)` (~L16078) runs on storage. Inside the default
(non scheduled / quick reply / welcome) branch, for `load_type == -2` (~L16273):

```java
cursor = database.queryFinalized("SELECT mid, data, ttl, mention, read_state, send_state, custom_params FROM messages_v2 WHERE mid = %d AND uid = %d", ...);
if (exist = cursor.next()) {
    TLRPC.Message oldMessage = TLRPC.Message.TLdeserialize(data, ...);   // OLD state
    oldMessage.readAttachPath(data, clientUserId);
    ... if (!sameMedia) addFilesToDelete(oldMessage, filesToDelete, ...);   // old media file will be DELETED
}
if (!exist) continue;                                                       // edit of an unknown message is dropped
```

then the `REPLACE INTO messages_v2` write, then `getFileLoader().deleteFiles(filesToDelete, 0)` (~L16629), then `commitTransaction`.

Facts that drive the design:

* This is the **only** point where the persisted old message and the new message coexist, on one thread, before the old row is
  overwritten. It runs for chat open/closed, foreground/background, after restart (getDifference replays through the same path).
* `load_type == -2` is **not edit-specific**: it is "replace if the row exists" and is also used for link-preview fills
  (`MC` ~L11866), `ArticleViewer` cached pages (~L5628/5665) and grouped-media finalization (`SendMessagesHelper` ~L1654/9902).
  A capture hook must therefore be enabled **only for the call from `processUpdateArray`** (an explicit flag), and must also
  compare content, because `TL_updateEditMessage` is also sent for non-user changes (reply markup, web preview, `edit_hide`).
* An edit that replaces the media deletes the old media file from disk (`addFilesToDelete(oldMessage)` -> `FL.deleteFiles`).
* When the old row is absent (never loaded, local database cleared, `resetDialogs` after `differenceTooLong`) Telegram itself
  drops the edit. We cannot know the old content there.
* Rows are also overwritten **without** this path: history loads (`putMessages` load types 0..4 do `REPLACE` without reading),
  `MS.replaceMessageIfExists` (file-reference refresh, location updates). An edit that reaches us only as a reloaded message is
  invisible. This is an accepted limitation, not something to work around.

`TLRPC.Message.edit_date` / `edit_hide` exist; `MessageObject.isEdited()` = edited flag && `edit_date != 0` && `!edit_hide`.

### 1.4 Duplicates

pts guarantees each update is applied once in the normal case, but a crash between the storage write and `setLastPtsValue`
can replay an update, and the push path below can race the update path. Idempotency must be our own:
key `(source_dialog_id, mid, edit_date, content_hash)`.

---

## 2. Incoming deletions (private chats)

### 2.1 Update types

* `TL_update.TL_updateDeleteMessages`: **ids only** (`messages`, `pts`, `pts_count`). No peer, no actor. Private chats and basic
  groups share one per-account message id space, so ids alone are unique among non-channel messages.
* `TL_updateDeleteChannelMessages` (channel id + ids), `TL_updateDeleteScheduledMessages`, `TL_updateDeleteQuickReplyMessages`,
  `TL_updateChannelAvailableMessages` are not relevant to private chats.

### 2.2 Path

`processUpdateArray` puts `TL_updateDeleteMessages.messages` into `deletedMessages.get(0)` (key **0**: peer unknown, ~L18821).
At the end (~L21216-21225, stage):

```java
getMessagesStorage().getStorageQueue().postRunnable(() -> {
    ArrayList<Long> dialogIds = getMessagesStorage().markMessagesAsDeleted(key, arrayList, false, true, 0, 0);  // useQueue=false, deleteFiles=true
    getMessagesStorage().updateDialogsWithDeletedMessages(key, -key, arrayList, dialogIds);
});
```

Before that, the UI notification (`messagesDeleted`, notification removal) was posted via storage -> UI (~L21014). Both are FIFO on
storage, but the UI runnable may run concurrently with the delete.

Second remote entry point: FCM push `MESSAGE_DELETED` -> `PushListenerController` (~L405-416) -> `MC.deleteMessagesByPush(dialogId,
ids, channelId)` (~L17604) -> storage: `deletePushMessages`, `markMessagesAsDeleted(dialogId, ids, false, true, 0, 0)`. Here the
dialog id is known. The later pts update then finds no rows.

`MS.markMessagesAsDeletedInternal(long dialogId, ArrayList<Integer> messages, boolean deleteFiles, int mode, int threadMessageId)`
(~L14516), default mode:

1. `SELECT uid, data, read_state, out, mention, mid FROM messages_v2 WHERE mid IN(..) AND is_channel = 0` (dialogId 0) or
   `... AND uid = dialogId`. With `deleteFiles == true` every row is deserialized and `addFilesToDelete` collects its files.
   **This SELECT is the last moment the old message exists.**
2. `getFileLoader().deleteFiles(filesToDelete, 0)` (~L14764): posted to fileLoader, deletes media from disk asynchronously.
3. unread counters, `DELETE FROM messages_v2 ...`, topics, polls, media tables.

### 2.3 Local deletions by the owner (must never be archived)

| Owner action | Path | Ordering relative to any later server echo |
| --- | --- | --- |
| Delete for me / for everyone | `MC.deleteMessages(...)` (~L9322): `markMessagesAsDeleted(dialogId, messages, true /*queue*/, forAll, ...)` is **posted to storage before** the request is sent | rows are gone before any update about them can be processed (storage is FIFO) |
| Clear history / delete chat | `MC.deleteDialog(...)` -> `MS.deleteDialog(did, onlyHistory)` (local), then `messages.deleteHistory` | same |
| Delete by date range | `MC.deleteMessagesRange` -> response -> `markMessagesAsDeleted` | not an update; local caller |
| Auto-delete timer (`ttl_period`) | `MC.checkDeletingTask` -> `deleteMessages(..., cacheOnly)` | local caller |
| Self-destructing media (`ttl_seconds`) | `MS.emptyMessagesMedia` | local caller |
| Secret chats | `SecretChatHelper` -> `markMessagesAsDeletedByRandoms` / `deleteDialog` | not in scope (secret chats excluded) |
| Logout / clear database / `differenceTooLong` | `MS.cleanup`, `clearLocalDatabase`, `resetDialogs` | no deletion event at all |

The owner's request returns `TL_messages_affectedMessages`, which only advances pts (`MC.processNewDifferenceParams`); a server
echo, if any, finds no rows.

### 2.4 Can remote and local deletion be told apart?

* **Deletions made on this device: yes, deterministically**, by capturing only on the two remote entry points (update path with
  key 0, push path) and never inside `markMessagesAsDeletedInternal` itself (which all paths share). Local deletions remove the
  rows first, so a remote echo finds nothing.
* **Deletions made by the owner on another device of the same account: no.** `TL_updateDeleteMessages` carries no actor.
  "Delete for me" or "clear history" done on the owner's laptop arrives exactly like the other user's revoke. This is an
  unavoidable protocol ambiguity and must be stated in the UI copy (see plan, open questions).
* Auto-delete expiry may arrive as an update before the local timer runs; the message's `ttl_period` and `date` tell us.

### 2.5 Media on disk

* `MS.addFilesToDelete` + `FL.deleteFiles` delete media files for server-driven deletes, "delete for everyone", TTL expiry, and
  media-replacing edits. "Delete for me" (`forAll == false`) does not delete files.
* File locations: `FL.getPathToAttach` -> `document.localPath` if set, else `FilePathDatabase` override, else
  `FileLoader.getDirectory(MEDIA_DIR_IMAGE/AUDIO/VIDEO/DOCUMENT/CACHE)` + `getAttachFileName` (`<dc>_<docId>.<ext>`,
  `<volume>_<local>.jpg`). Media directories come from `ImageLoader.createMediaPaths()`: external app-specific storage
  (`getExternalFilesDir(null)/Telegram/Telegram Images|Video|Audio|Documents`) and `AndroidUtilities.getCacheDir()`
  (external cache), so they are usually **not** on the same filesystem as internal storage: `File.renameTo` into internal
  storage fails, a copy is required.
* `AutoDeleteMediaTask` (keep-media, at most daily, `Utilities.cacheClearQueue`) and manual cache clearing delete cached files by
  age/size regardless of messages. A file the archive needs must be in feature-owned storage.
* A file deletion is posted to the fileLoader queue **after** our capture point (same storage runnable), so a hold registered
  synchronously at capture time is visible to `FL.deleteFiles` before it deletes (happens-before through the queue post).
* Never-downloaded media cannot be recovered after deletion: the message's file reference dies with it, and fetching it would
  need a request with the deleted message as parent (`FileRefController`). The serialized message still carries the inline
  stripped thumbnail (`TL_photoStrippedSize`) and metadata (type, size, duration, file name).

---

## 3. Dialog ids (`messenger/DialogObject.java`)

| Kind | Encoding | Classifier |
| --- | --- | --- |
| user | `user_id` (> 0, server ids fit in 52 bits) | `isUserDialog`: > 0, not encrypted, not folder |
| basic group / supergroup / channel / community | `-chat_id` (< 0) | `isChatDialog`: < 0 |
| secret chat | `0x4000000000000000L \| (chatId & 0xffffffffL)` | `isEncryptedDialog`: bit 62 set, bit 63 clear |
| folder row | `0x2000000000000000L \| folderId` (`makeFolderDialogId(int)`) | `isFolderDialogId`: bit 61 set, bit 63 clear |
| forum topic | not a dialog id: `(dialogId, topicId)` pair | — |

**Every non-zero `long` is classified as something**: negatives are "chat", positives are user/secret/folder. There is no free
namespace from `DialogObject`'s point of view; any synthetic id that leaks into Telegram code will be interpreted.
`MC.getInputPeer(long)` never returns `TL_inputPeerEmpty`: an unknown positive id becomes `TL_inputPeerUser{id, access_hash 0}`, an
unknown negative id becomes `TL_inputPeerChat{-id}` (a real group the user may be in). Calls that take bare message ids
(`messages.readMessageContents`, `messages.getMessagesViews` with `increment`) can act on the account's **real** messages.

### Provably collision-free value

`LOCAL_HISTORY_DIALOG_ID = 0x20004C4800000001L` (bit 61 set, bits 62-63 clear, bits 32-47 = `0x4C48`):

* not a user id: users are positive and below 2^52; this is >= 2^61.
* not a chat/channel/community id: positive.
* not a secret chat id: bit 62 clear.
* not a folder id: `makeFolderDialogId(int f)` for `f >= 0` has bits 32-60 all zero (high word exactly `0x20000000`); ours has
  `0x4C48` there. For `f < 0` sign extension makes the result negative.
* no code in the repo constructs other values with bit 61 (`0x2000000000000000L` appears only in `DialogObject`).
* If it leaks into a `DialogObject` classifier it reads as a **folder**, which no Telegram code turns into a network peer
  (folders use `folder_id` ints). This is the least dangerous interpretation available; it is defense in depth, not the
  safety mechanism. The safety mechanism is that the id never enters `MessagesController`, `MessagesStorage` or
  `ConnectionsManager` (plan §13).

---

## 4. ChatActivity cannot host a local conversation safely

`ChatActivity.onFragmentCreate` (~L2669-3186) returns `false` unless `user_id`/`chat_id`/`enc_id` resolves to a real
`TLRPC.User`/`Chat`/`EncryptedChat` (only `MODE_EDIT_BUSINESS_LINK` and `MODE_SEARCH` open without one). Opening, scrolling,
pausing and closing a normal chat issues, keyed on `getInputPeer(dialog_id)`: `getPeerSettings`, `getFullUser`/`getFullChat`,
`getHistory` (`loadMessagesInternal`, also on empty cache), `readHistory` (fires even with nothing unread), `getPollResults`,
`getMessagesReactions`, `getExtendedMedia`, stories, `getMessagesViews` (from the cell), pinned/reply loads, sponsored,
`readMentions`/`readReactions`, `readMessageContents` on voice playback, `setTyping`, `getWebPagePreview`, `saveDraft` on pause,
translations, group call checks. Neutralizing all of these with a synthetic id would mean scattering guards over a 47k-line
class and every future upstream merge could add a new leak.

**Precedent that works: `ui/ChannelAdminLogActivity.java`.** A plain `BaseFragment` with its own `RecyclerListView` adapter
that renders `ChatMessageCell` / `ChatActionCell` for messages that are not part of any loaded history:

* builds `MessageObject`s itself (synthetic local ids from a counter, `eventId != 0`, date separators via `createDateArray`),
* sets `messageCell.isChat = true` and computes `pinnedTop`/`pinnedBottom` by sender and a 300 s gap (group-style bubbles),
* implements its own `ChatMessageCellDelegate` (every method is `default`): avatar tap -> real `ProfileActivity`, media ->
  `PhotoViewer` (`eventId != 0` disables shared-media lookups), playback via `MediaController`,
* shows edits ("Original message" box built in the `MessageObject` admin-log constructor, `EventLogOriginalMessages`) and
  deleted messages.
* its only network calls are its own explicit requests.

`ChatMessageCell` draws the sender name/avatar when `isChat` and `MessageObject.needDrawAvatar()` (true when `eventId != 0`)
and resolves the sender with `MessagesController.getUser(getFromChatId())`: the **real** source user must be present in
`MessagesController` (load it from storage if not in memory; never fabricate a user). The cell's own network side effect is
`addToViewsQueue`, guarded by `MESSAGE_FLAG_HAS_VIEWS` / `replies`: archived messages must have both cleared.

---

## 5. Chat list

* `MC.sortDialogs` iterates `allDialogs` and derives `dialogsServerOnly`, `dialogsForward` (share/forward targets),
  `dialogsUsersOnly`, filter lists, unread counts and folders; `dialogs_dict` / `allDialogs` are persisted through
  `MS.putDialogs` and drive read tasks, pinned reorder (`messages.reorderPinnedDialogs`), folder moves, mute
  (`account.updateNotifySettings`). A `TLRPC.Dialog` with a synthetic id in that model would leak into all of them.
* `promoDialog` is a real server dialog; folder rows (`TL_dialogFolder`) are created by `ensureFolderDialogExists` and
  persisted. Neither is a safe template.
* Safe precedents for non-server rows: `DialogsAdapter.updateItemList` already mixes many `ItemInternal` view types
  (`VIEW_TYPE_HEADER`, `VIEW_TYPE_STORIES`, `VIEW_TYPE_FORWARD_TO_STORIES_CELL`...), and `DialogCell.CustomDialog` +
  `DialogCell.setDialog(CustomDialog)` renders a row from plain fields without `dialogs_dict`, `dialogMessage` or `getUser`.
* Tap dispatch is `DialogsActivity.onItemClick` (~L7895): folders branch before the generic `TLRPC.Dialog` path; a new branch
  for the local row goes there.

---

## 6. Protected Chats (PR #9)

* Keys: `ProtectedChatsState` keys every record by `accountKey + ":" + dialogId` where `accountKey =
  UserConfig.getClientUserId()` (not the slot). Persisted in `SharedPreferences("protected_chats")`.
* `ProtectedChats.isSupportedDialog(dialogId)` = `dialogId != 0 && !DialogObject.isFolderDialogId(dialogId)`; the chosen local id
  is in the folder namespace, so it is **rejected today** and needs exactly one explicit admission.
* Fragment identity: `ProtectedChatGate.getDialogId(BaseFragment)` (ChatActivity, ChatLockSettingsActivity, TopicsFragment,
  ProfileActivity, ProfileActivity2) via `ProtectedDialogIds.fromArgs`. Every gate decision (present, reveal, lifecycle states
  in `ProtectedGateLifecycle`) flows from that id, so a new fragment type joins by being recognized there.
* Lifecycle hooks are already in `BaseFragment.onResume/onPause/onBecomeFully*/onFragmentDestroy` and `ActionBarLayout`.
* Identity rendering of a protected dialog id (management list `ProtectedChatsListActivity`, `ChatLockSettingsActivity` title)
  assumes `getUser`/`getChat`; it needs a resolver for the local id.
* Account removal: `UserConfig.clearConfig()` calls `ProtectedChats.onAccountRemoved(clientUserId)` before clearing; the natural
  place for our own cleanup too.

---

## 7. Storage and backup

* `cache4.db` lives in `getFilesDirFixed()` (account 0) or `getFilesDirFixed()/account<N>/`. It is a cache: `MS.cleanup` deletes
  the file on logout and login, `clearLocalDatabase` (Storage settings, `SuggestClearDatabaseBottomSheet`, `DialogsActivity`)
  wipes message tables, `checkSQLException` recovery can reset it. **Feature data must not live in `cache4.db`.**
* Account slots are reused after logout; key by user id (as Protected Chats does).
* Manifest: `android:allowBackup="true"`, `android:backupAgent=".BackupAgent"` (a `BackupAgentHelper` with only
  `SharedPreferencesBackupHelper("saved_tokens", "saved_tokens_login")`), no `fullBackupOnly`, no `fullBackupContent`, no
  `dataExtractionRules`. Because a custom agent is declared without `fullBackupOnly`, cloud backup is key/value and only
  covers those two preference files. Device-to-device transfer on Android 12+ is governed by `dataExtractionRules`, which the
  app does not declare; its behavior for this configuration could not be verified from source. `getNoBackupFilesDir()` is
  excluded from every backup and transfer mechanism by platform contract, so it is the recommended location.
* SQLite: Telegram's own `org.telegram.SQLite.SQLiteDatabase` (bundled native sqlite, default threadsafe build, WAL used by
  `cache4.db`) can open additional database files.

---

## 8. Answers to the plan's source questions (Local History part)

1. **Central edit path:** `MC.processUpdateArray` (stage) -> `MS.putMessages(..., -2, ...)` (storage) old-row read at the
   `load_type == -2` branch.
2. **Central delete path:** `MC.processUpdateArray` `deletedMessages[0]` -> storage runnable -> `MS.markMessagesAsDeleted(0, ids,
   false, true, 0, 0)`; plus `MC.deleteMessagesByPush`.
3. **Old message recoverable:** edits: inside the `-2` branch after `oldMessage` is deserialized, before `REPLACE`. Deletes: in
   the storage runnable before `markMessagesAsDeleted` (we read the rows ourselves).
4. **Media files:** deleted from disk (asynchronously on fileLoader) for remote deletes and media-replacing edits; also removed
   by keep-media cleanup independent of messages.
5. **Local vs remote:** reliable for this device (entry point), impossible for the owner's other devices (no actor field).
6. **Dialog id encoding:** §3.
7. **Safe namespace:** `0x20004C4800000001L` is provably distinct from every encodable id; safety still depends on the id
   never entering Telegram's dialog/network layers.
8. **ChatActivity assumptions:** deep; requires a real peer and issues 20+ peer-keyed requests (§4).
9. **MessageObject/ChatMessageCell:** yes, as `ChannelAdminLogActivity` does, with flags cleared and real sender users.
10. **Network calls if ChatActivity were reused:** §4 list.
11. **Chat list:** `DialogsActivity.getDialogsArray` -> `MC.getDialogs(folderId)` -> `DialogsAdapter.updateItemList`;
    preview from `MC.dialogMessage` in `DialogCell.update`.
12. **Survive reloads:** a row injected at the adapter level is rebuilt on every `updateItemList`, independent of
    `MessagesController` reloads (`dialogsNeedReload`, `sortDialogs`).
13. **Search:** local chat name only in the chat-list search; archived content only inside the Local History Chat.
14. **Protected Chats keying:** `(clientUserId, dialogId)`.
15. **Local identity in Protected Chats:** yes, with one explicit admission in `isSupportedDialog`, fragment recognition in
    `ProtectedChatGate.getDialogId`, and an identity resolver. No invariant needs weakening.
20. **Account switches / process recreation:** our data is keyed by user id and read from our own database; the row is rebuilt
    from it on every list update; protection authorization does not survive the process (existing PR #9 rule).
