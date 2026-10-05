# Local History Chat: implementation plan

Planning name: **Local History Chat**. Final product name not chosen.
Baseline: `master` `055a0c3b`. Source evidence: [local-history-chat-research.md](local-history-chat-research.md) (cited as R§n).
Paths relative to `TMessagesProj/src/main/java/org/telegram/`. Code names introduced here are proposals; everything else exists.

This feature is independent of Activity. The only coupling is one surface classification (§30).

---

## 1. Product scope

One local conversation per Telegram account, shown in that account's chat list, that looks like a group. It preserves:

* **edits** made by other people to messages they sent the owner in ordinary 1-to-1 chats (every observed version), and
* **deletions** of such messages (the last locally known version, with its media when it was already on the device).

The original private chat is untouched: no placeholder, no "deleted" marker, no change to Telegram's own edit display.
All data stays on the device.

## 2. Non-goals

* No anti-delete in the original chat, no change to `ChatActivity` or `messages_v2`.
* No groups, supergroups, channels, topics, secret chats, bots, Saved Messages, service accounts, outgoing messages.
* No recovery of media that was never downloaded.
* No notifications for archive events in v1.
* No server objects: no group, no Saved Messages entry, no cloud sync, no backup designed by us.
* No forwarding of preserved content as Telegram forwards (§10).
* No inclusion of archived content in global search.

## 3. Source eligibility

Exactly one pure function decides; everything else calls it.

```java
// messenger/localhistory/LocalHistoryEligibility.java (pure, no Android)
static boolean isEligible(long selfId, long sourceDialogId, TLRPC.Message m, UserFacts user, int nowSec)
```

Returns true only when all hold:

| Rule | Check | Why |
| --- | --- | --- |
| ordinary 1-to-1 | `DialogObject.isUserDialog(sourceDialogId)` (excludes secret, folder, chats, topics, channels) | scope |
| not Saved Messages | `sourceDialogId != selfId` | scope |
| incoming | `!m.out` and (`m.from_id == null` or `from_id` is `TL_peerUser` with `user_id == sourceDialogId`) | outgoing excluded |
| a real message | `m instanceof TL_message` (not `TL_messageService`, not `TL_messageEmpty`) | service events are not "said" |
| sender kind | `user != null`, `!user.bot`, `!user.self`, `!UserObject.isService(id)` (333000, 777000, 42777), `!UserObject.isReplyUser(id)`, `id != UserObject.VERIFY`, `id != UserObject.ANONYMOUS` | bots/service excluded |
| not self-destructing media | `m.media == null \|\| m.media.ttl_seconds == 0` | view-once content must not be kept |
| not an auto-delete expiry (deletions only) | if `m.ttl_period != 0`: eligible only if `nowSec < m.date + m.ttl_period - 60` | expiry is not "the other user deleted it" |

Decisions: deleted user accounts (`user.deleted`) stay eligible (the deletion still happened). Business chats are ordinary
private users and are eligible (a business bot replying on the user's behalf is still the user's message). `UserFacts` is a
tiny value (`bot`, `self`, `deleted`) so the function stays pure; the caller resolves it from `MC.getUser` or
`MS.getUserSync` on the storage thread.

## 4. Edit update flow (actual)

R§1: `processUpdates` -> `processUpdateArray` (stage) collects `editingMessages` -> `MS.putMessages(res, dialogId, -2, 0, false,
0, 0)` (storage) -> old row read and deserialized -> `REPLACE INTO messages_v2` -> old media deleted if replaced -> commit.
The UI gets `replaceMessagesObjects` directly from stage; it is not used.

## 5. Deletion update flow (actual)

R§2: `processUpdateArray` `deletedMessages[0]` (ids only) -> storage runnable -> `MS.markMessagesAsDeleted(0, ids, false, true, 0,
0)` (reads rows by `mid IN (...) AND is_channel = 0`, queues file deletion, deletes rows) -> `updateDialogsWithDeletedMessages`.
Push: `PushListenerController` `MESSAGE_DELETED` -> `MC.deleteMessagesByPush(dialogId, ids, channelId)` -> storage, same
`markMessagesAsDeleted`.

## 6. Remote vs local deletion

* Capture runs only on the two **remote entry points** (update key 0, push with `channelId == 0`), never inside
  `markMessagesAsDeletedInternal`, which local deletions share.
* Every local deletion on this device (`MC.deleteMessages`, `deleteDialog`, `deleteMessagesRange`, TTL tasks, logout, clear
  database, `resetDialogs`) removes the rows before any remote echo can be processed (storage FIFO, R§2.3). Capture reads the
  rows itself; **no row, no archive.** This makes "Delete for me on this device never archives" structural, not heuristic.
* Unavoidable ambiguity: the owner deleting or clearing history **on another device of the same account** is indistinguishable
  (R§2.4). v1 archives it. UI copy says "Removed from the chat" rather than claiming who deleted it, and the info page explains
  that deletions made from your other devices also appear. Product decision requested (§39 Q1).
* Bulk removals (the other side, or the owner elsewhere, clearing a whole chat) arrive as one or more large
  `TL_updateDeleteMessages`. v1 archives them, but a single source dialog losing more than 20 messages in one update is stored
  with a shared `batch_id` and rendered as one collapsed entry ("37 messages removed from the chat with Alice", expandable).

## 7. Capture points

### 7.1 Edits

`MS.putMessages(messages_Messages, ...)` gets one extra boolean, used only by `MC.processUpdateArray` (~L19832):

```java
// MessagesStorage
public void putMessages(TLRPC.messages_Messages messages, long dialogId, int load_type, int max_id,
                        boolean createDialog, int mode, long threadMessageId, boolean fromEditUpdate)
// existing 7-arg overload delegates with fromEditUpdate = false
```

Inside the `load_type == -2` branch, immediately after `oldMessage.readAttachPath(...)` and **before** the
`message.attachPath = oldMessage.attachPath` merge and the `REPLACE`:

```java
if (fromEditUpdate && mode == 0) {
    LocalHistoryCapture.getInstance(currentAccount).onEditStored(oldMessage, message);   // storage thread, synchronous
}
```

`onEditStored` (storage thread):

1. Fast exit when the feature is off or `!DialogObject.isUserDialog(message.dialog_id)` (cost: one boolean and one compare).
2. Eligibility (§3) on the **new** message.
3. `LocalHistoryDiff.isContentChange(old, new)`: text, entities, media identity (photo id / document id / geo / contact /
   media type), caption. Reply markup, web preview fill, reactions, `edit_hide`-only changes are not content changes.
   No change -> return.
4. Builds an immutable `CapturedEdit` (serialized old and new `TLRPC.Message` bytes, ids, dates).
5. If media identity changed and the old media file exists: registers a **media hold** (§10) on the old file paths.
6. Writes synchronously to the feature database (§25) in one transaction, `INSERT OR IGNORE` on the idempotency key (§8).

Why here: the only place both states exist in persisted form; one thread; runs for every arrival path (live, difference,
restart); and the hold is registered before `FL.deleteFiles` for the replaced media is posted (same runnable, later line).

### 7.2 Deletions

In `MC.processUpdateArray`'s deletion block (~L21216) and in `MC.deleteMessagesByPush`, inside the storage runnable, **before**
`markMessagesAsDeleted`:

```java
if (key == 0) {   // TL_updateDeleteMessages only; channels never
    LocalHistoryCapture.getInstance(currentAccount).onRemoteDeleteBeforeStorage(0, arrayList);
}
// deleteMessagesByPush: if (channelId == 0) ...onRemoteDeleteBeforeStorage(dialogId, ids)
```

`onRemoteDeleteBeforeStorage(long dialogIdOrZero, ArrayList<Integer> mids)` (storage thread):

1. Fast exit when the feature is off.
2. Reads candidate rows itself through a new `MS` helper (storage thread only):
   `ArrayList<TLRPC.Message> getMessagesForArchiveSync(long dialogIdOrZero, ArrayList<Integer> mids)` with
   `SELECT uid, data FROM messages_v2 WHERE mid IN(..) AND is_channel = 0 AND uid > 0` (or `uid = dialogId`), deserialized with
   `readAttachPath`. Positive `uid` excludes groups; eligibility excludes the rest.
3. Eligibility (§3) per message; resolves the user from `MC.getUser` or `MS.getUserSync`.
4. Registers media holds for every existing local file of each archived message (`FL.getPathToMessage` +
   `addFilesToDelete`-equivalent path list; thumbnails included).
5. Writes the deletion events in one transaction (`INSERT OR IGNORE`).

Cost when nothing is eligible: one indexed SELECT per update batch (the same rows `markMessagesAsDeletedInternal` reads a moment
later, now in the page cache).

## 8. Idempotency

| Event | Unique key | Effect of a replay |
| --- | --- | --- |
| entry (one per source message) | `(source_dialog_id, source_mid)` | upsert, never a second entry |
| revision | `(entry_id, content_hash, edit_date)` | ignored |
| deletion | `entry.deleted_at IS NOT NULL` | second deletion ignored |

`content_hash` = 64-bit hash (e.g. `Utilities.MD5`-derived, or FNV-1a) of the normalized text, serialized entities and media
identity. The update path and the push path for the same deletion converge on the same key. A crash after our commit but before
Telegram's commit replays the update; the old row is still the old one, so the diff and keys are identical.

## 9. Event / revision model

**Chosen: one feed entry per source message with an expandable revision history** (option B).

* Feed entry: the message as it was last known, with a status footer: `Edited` (with version count) or `Deleted · 8:34 PM`, or
  `Edited · Deleted`.
* The entry's position is its `last_event_at` (the latest edit or the deletion), so new activity appears at the bottom like a
  group conversation. An entry that is edited again or then deleted moves to the bottom (the list is a feed of what happened,
  not of when it was originally sent; the original send time is shown in the history sheet).
* Revisions: `ORIGINAL` (the old state at the first observed edit), `EDIT n`, and for deletions the deleted state is the latest
  revision. If the first observed old state already had `edit_date != 0`, the sheet says "Earlier versions were not seen".
* History sheet (tap the status footer, or long-press -> "Version history"): a `BottomSheet` listing versions oldest first,
  each rendered with a real `ChatMessageCell` (text, entities, captions, web preview), each with its own time.
* Why not one entry per event (A): three edits of one message would show four near-identical bubbles and bury other people.
  Why not only the latest diff: a user wants to see what was originally said, which needs the full chain.
* Replies: the archived message's `reply_to` is kept. If the replied-to message is itself in the archive the reply header
  scrolls to it; otherwise it shows the quote from the stored `reply_to` data when present, and nothing is fetched.
* Link previews: kept as stored (`TL_messageMediaWebPage` with its `webpage`), with photos subject to §10 like any media.
* Captions and entities: part of the serialized message; rendered natively.

## 10. Media preservation

States per archived media: `PRESERVED` (file in feature storage), `PENDING_COPY`, `NOT_DOWNLOADED` (never on device),
`TOO_LARGE`, `OVER_BUDGET`, `LOST` (original vanished before the copy, e.g. cache cleanup), `EVICTED` (removed by the budget or
by the user).

### Hold-then-copy

1. At capture (storage thread), for each local file of the archived message that exists, `LocalHistoryMediaHolds.hold(file)`
   (a concurrent set of absolute paths) and insert a `media` row `PENDING_COPY` with the source path.
2. `FL.deleteFiles` (fileLoader thread) skips held files: one line in its loop,
   `if (LocalHistoryMediaHolds.isHeld(file)) continue;`. The hold is registered before the delete is posted, so the check
   cannot miss it.
3. `LocalHistoryMediaCopier` on the feature queue copies each pending file into
   `getNoBackupFilesDir()/local_history/<userId>/media/<entryId>_<rev>_<n>.<ext>` (stream copy, `fsync`, then rename from a
   `.part` file), marks `PRESERVED`, then performs Telegram's intended deletion of the original (if it was being deleted),
   then releases the hold.
4. Crash recovery: on feature start, `PENDING_COPY` rows are retried while holds are re-registered from them; a source that no
   longer exists becomes `LOST`.

Telegram's cache cleanup (`AutoDeleteMediaTask`, manual clear) can delete an original during the short copy window; that
yields `LOST`, not a crash. Media that was never downloaded is `NOT_DOWNLOADED`: the entry shows the stripped thumbnail (inline
in the stored message) or a type icon, plus "Not saved: it was never downloaded".

Limits (no new settings screen): files larger than **50 MB** are not copied (`TOO_LARGE`); a per-account budget of **1 GB**
(oldest preserved media evicted first, `EVICTED`); both constants in one place. Info page shows "Saved media: 312 MB" with
"Delete saved media". Product confirmation requested (§39 Q3).

### Rendering rule (network safety)

An archived `MessageObject` **never carries a downloadable remote location**. Before building it, `LocalHistoryRenderModel`
rewrites the stored message:

* preserved file: `attachPath` = local file; for documents also `document.localPath` (honored first by `FL.getPathToAttach`);
  `ChatMessageCell` uses `attachPath` when it exists (`localFile == 1`).
* not preserved: media replaced by a placeholder representation (stripped thumbnail + type + size text); no `photo.sizes`
  with remote locations, no `file_reference`.

This keeps `FileLoader`/`FileRefController` from ever requesting a file whose parent is the synthetic dialog.

## 11. Synthetic-dialog architectures considered

| # | Architecture | Collision | Network | ChatActivity/DB assumptions | Verdict |
| --- | --- | --- | --- | --- | --- |
| 1 | Reserved id inserted as a `TLRPC.Dialog` into `MC.allDialogs` | provable (R§3) | leaks: `dialogsForward` (share targets), read tasks, pinned reorder, folder moves, mute, `getInputPeer` | `MS.putDialogs` persists it in `cache4.db`; every `sortDialogs` consumer sees it | rejected |
| 2 | New local dialog type inside Telegram's model (`TL_dialogLocal`) | provable | same consumers, each needs an `instanceof` guard | dozens of loops over `allDialogs` | rejected: guard surface too large |
| 3 | `ChatActivity` with a synthetic or fake peer | needs `putUser` of a fake user | 20+ peer-keyed requests (R§4), bare-id requests touch real messages | `onFragmentCreate` requires a real peer | rejected |
| 4 | Dedicated fragment reusing `ChatMessageCell`, adapter-level chat-list row | id only in our code and Protected Chats | none by construction | none: Telegram's model never sees the id | **chosen** |
| 5 | Local pseudo-group (`TLRPC.Chat` with fake id) | needs a fake chat id, which is a real-looking negative id | `getInputPeer` -> `inputPeerChat`, a real group's id | `getChat` consumers everywhere | rejected: most dangerous |
| 6 | Saved Messages sub-dialog / server group | — | server-side | — | out of product scope |

## 12. Chosen architecture

**Dedicated `LocalHistoryActivity` (a `BaseFragment`) built on the `ChannelAdminLogActivity` pattern, an adapter-level row in
the chat list, a local-only identity, and a reserved id that exists only for Protected Chats and Activity.**

* Rendering reuses `ChatMessageCell`, `ChatActionCell` (date separators), `PhotoViewer`, `MediaController`, `AvatarDrawable`,
  `BackupImageView`, `BottomSheet`, the chat background (`Theme.getCachedWallpaper`) and action bar.
* Telegram's dialog model, `cache4.db` and the network layer never see the reserved id.
* Data lives in a feature-owned SQLite file per account user id.

## 13. Collision and network safety

Identity: `LocalDialogIds.LOCAL_HISTORY = 0x20004C4800000001L` (proof R§3), with `LocalDialogIds.isLocal(long)` (exact
compare). One value for all accounts; account isolation comes from the account key, as in Protected Chats.

Guardrails (defense in depth on top of "never handed to Telegram code"):

| Guard | Where | Behavior |
| --- | --- | --- |
| G1 | `MC.getInputPeer(long)`, `MC.getInputPeer(TLRPC.Peer)`, `MC.getInputUser(long)`, `MC.getInputChannel(long)` | `if (LocalDialogIds.isLocal(id))` -> log + return `TL_inputPeerEmpty` / `TL_inputUserEmpty` / `TL_inputChannelEmpty` (and `BuildVars.DEBUG_VERSION` throws) |
| G2 | `SendMessagesHelper.sendMessage(SendMessageParams)` | refuse `peer` that is local |
| G3 | `MC.markDialogAsRead`, `MC.sendTyping`, `MC.deleteMessages`, `MC.deleteDialog`, `MediaDataController.saveDraft`, `NotificationsController.setOpenedDialogId` | early return for a local id |
| G4 | `MC.sortDialogs` / `MS.putDialogs` | assertion: no dialog in `allDialogs` is local |
| G5 | archived `MessageObject` construction | `out=false`, `unread=false`, `media_unread=false`, flags `HAS_VIEWS`/`replies`/`reactions`/`reply_markup` cleared, `eventId != 0`, no remote file locations (§10) |
| G6 | `LocalHistoryActivity` delegate | no `ChatActivity` opened with the local id; avatar taps open the **real** source user |
| G7 | share/forward pickers | the local row is not created for `DialogsActivity` instances with `onlySelect`, forward, share, or `requestPeerType` |

Dedicated tests in §34 (`LocalDialogIdsTest`, `LocalHistoryNetworkGuardTest`).

## 14. Chat-list integration

* `DialogsAdapter`: new `VIEW_TYPE_LOCAL_HISTORY`. In `updateItemList`, only when `dialogsType == DIALOGS_TYPE_DEFAULT`,
  `folderId == 0`, the "All chats" filter (or no filter), `communityId == 0`, not `onlySelect`, and the feature is on with at
  least one entry: insert one `ItemInternal(VIEW_TYPE_LOCAL_HISTORY, row)` among the `VIEW_TYPE_DIALOG` items at the position
  where its `last_event_at` sorts by date, after pinned dialogs.
* Rendering: `DialogCell` in a new local mode, built on the existing `CustomDialog` path: name, avatar (local photo or
  `AvatarDrawable` with a fixed icon), last entry preview ("Alice: come at 8" / "Alice deleted a message"), time, unread
  count with muted style, lock icon when protected. Preview masking: `ProtectedChats.shouldHideContent(account,
  LOCAL_HISTORY)` -> preview replaced exactly as `DialogCell` already does for protected dialogs.
* `DialogsActivity.onItemClick`: new branch before the generic `TLRPC.Dialog` path; `ProtectedChatGate` decides via
  `presentFragment` as for any chat.
* Long press: no multi-select participation; a small popup: Lock/Unlock (§28), Mark as read, Clear history, Hide from chat list
  (turns the row off; reachable again from Settings).
* Survives `MessagesController` reloads because it is rebuilt from `LocalHistoryRepository`'s in-memory summary (count,
  last entry, unread) on every `updateItemList`; the repository posts `NotificationCenter.localHistoryChanged` (new global id)
  on change, and `DialogsActivity` observes it to call `dialogsAdapter.notifyDataSetChanged` through its normal update path.

## 15. Rendering strategy

`LocalHistoryActivity`:

* `RecyclerListView` + `LinearLayoutManager` (stack from end) + own adapter; view types: message (`ChatMessageCell`),
  date (`ChatActionCell`), collapsed batch (`ChatActionCell` with a button), unsupported.
* Pages entries from the repository on the feature queue (newest first, 50 per page), builds `MessageObject`s on the feature
  queue (`new MessageObject(account, renderModel, true, true)` needs users in `MC`; see §16), delivers on UI.
* Bubble footer: a `ChatActionCell`-style status line under the bubble or the cell's existing edited label replaced by the
  status text (`MessageObject` field set by the render model; `ChatMessageCell` already draws an "edited" string from
  `isEdited()`; v1 sets the flag and overrides the label text through a small hook `ChatMessageCell.setCustomStatusText`).
* No input field. Action bar: name + subtitle ("12 deleted · 30 edited"), avatar, menu (Search, Clear history, Info).

## 16. Group-style sender rendering

* `messageCell.isChat = true`; `eventId` = entry id so `MessageObject.needDrawAvatar()` is true; `peer_id` = `TL_peerUser`
  (source user), `from_id` = `TL_peerUser` (source user), `dialog_id` = `LOCAL_HISTORY`.
* `pinnedTop/pinnedBottom` as in `ChannelAdminLogActivity.ChatActivityAdapter.onBindViewHolder` (same sender, <= 300 s apart).
* Sender identity is the **current** real `TLRPC.User` from `MC.getUser`, loaded from storage with `MS.getUsersInternal`
  on the feature queue and put with `MC.putUsers(users, true)` when missing. No fake users.
* Decision: **current identity**, not captured historical identity. Telegram resolves names/avatars dynamically everywhere; storing
  names and photos would duplicate personal data. Captured fallback: the entry stores only the display name string at capture
  time (`source_name_snapshot`) used when the user cannot be resolved anymore (account deleted, user not in cache).

## 17. Sender profile navigation

Avatar / name tap -> the real `ProfileActivity` (`user_id` args) of the source user, exactly like the admin log. Long press on
the avatar -> `AvatarPreviewer` as in the admin log. "Show in chat" (entry menu) opens the real private chat at the message id
when the message still exists (edits), otherwise the chat without a jump. Both go through `ProtectedChatGate` like any route,
so a protected source chat asks first.

## 18. Editable local name and photo

* Stored in the feature database `meta` table: `title` (default "Local History" placeholder until named), `photo_path`
  (a JPEG in the feature directory).
* Never uses `MessagesController.changeChatTitle`, `ImageUpdater` upload paths, `TL_messages_editChatTitle`,
  `TL_photos_uploadProfilePhoto` or `ChatEditActivity`.
* UI: `LocalHistoryEditActivity` (a `BaseFragment` with an `EditTextBoldCursor` title field and an avatar row), styled like
  `ChatEditActivity`'s header, using `ImageUpdater` **only** for picking/cropping (`ImageUpdater` with a delegate that receives the
  local `TLRPC.PhotoSize` file; its upload step is not invoked when the delegate does not request upload). Phase 6 gate:
  verify `ImageUpdater` makes no upload request when its delegate handles `didUploadPhoto` locally; if it does, use
  `PhotoAlbumPickerActivity` + `PhotoCropActivity` directly.

## 19. Local profile / info page

`LocalHistoryProfileActivity` (`UniversalFragment`): header (avatar, name), rows: Edit (name/photo), Lock Settings (opens
`ChatLockSettingsActivity` with the local id when `ProtectedChats.isLockSettingsAvailable`), Saved media size + Delete saved
media, Clear history, explanation text (what is archived, other-device ambiguity, stays on this device). Not `ProfileActivity`:
that class is built around real peers (full user/chat loads, shared media, stories).

## 20. Search

* In-chat search (action bar search field): `LIKE` over `entry.search_text` (lowercased normalized text of all revisions),
  results scroll to the entry. Respects the chat's lock (the activity is gated).
* Chat-list search: the local chat appears when its **name** matches (a `DialogsSearchAdapter` local hit), masked like protected
  rows when protected. Archived message content is **never** in global message search (privacy; `DialogsSearchAdapter`
  message results come from the server and `cache4.db` anyway).

## 21. Unread / read model

* `meta.last_read_entry_seq`; unread = entries with `seq > last_read_entry_seq`. Opening the chat marks all read locally.
* Badge style is muted; it does **not** count toward `MC.unreadUnmutedDialogs`, folder counters, the app icon badge, or
  "mark all as read" in Telegram.

## 22. Media viewer

`PhotoViewer.getInstance().setParentActivity(this); openPhoto(messageObject, ..., provider)` with a provider over the local
entries (as the admin log does). `eventId != 0` disables shared-media searches (`needSearchImageInArr = false`). Video and voice
play from `attachPath` via `MediaController.playMessage`; `MediaController.isSamePlayingMessage` compares `eventId`. Saving
media to the gallery uses `MediaController.saveFile` on the local path. Gate: PhotoViewer must not show "Show in chat", "Share"
to Telegram chats, or "Delete" for these objects (its menu reads `messageObject` flags and the provider; Phase 5 verifies each
menu item).

## 23. Clear / delete local history

* Per entry: long press -> "Delete from Local History" (also deletes its preserved files).
* All: "Clear history" (confirm dialog) deletes every entry, revision, file, and resets unread. Atomic in one transaction;
  files deleted after commit on the feature queue; holds for in-flight copies are released and their copies discarded.
* Disabling the feature: capture stops immediately; data is kept until the user chooses "Delete all local history" in the
  same screen (offered in the disable confirmation).
* Interplay with capture: deletion and capture are serialized on the database; an event that arrives during "Clear" lands
  after it (new entry), which is correct.

## 24. Multi-account behavior

**One Local History Chat per account** (keyed by `clientUserId`, never by slot):

* database `getNoBackupFilesDir()/local_history/<userId>/history.db`, media in `.../<userId>/media/`.
* `LocalHistoryCapture.getInstance(account)` resolves the user id at capture time; capture is skipped when it is 0.
* The row appears only in that account's `DialogsActivity` (adapter uses `currentAccount`).
* Account removal: `UserConfig.clearConfig()` already calls `ProtectedChats.onAccountRemoved(getClientUserId())`; add
  `LocalHistory.onAccountRemoved(userId)` right next to it: closes the database, deletes the user's directory on the feature
  queue. Default: delete (privacy, matches Telegram wiping `cache4.db` on logout). Logging back in starts empty.

## 25. Storage schema and indexes

Feature-owned SQLite via `org.telegram.SQLite.SQLiteDatabase`, `PRAGMA journal_mode = WAL`, `secure_delete = ON` (as `cache4.db`).

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value BLOB);            -- schema_version, title, photo_path, last_read_seq, row_hidden

CREATE TABLE entry(
  id INTEGER PRIMARY KEY AUTOINCREMENT,      -- seq; also the MessageObject eventId and display id
  source_dialog_id INTEGER NOT NULL,         -- = source user id
  source_mid INTEGER NOT NULL,
  source_date INTEGER NOT NULL,              -- original send time
  first_seen_at INTEGER NOT NULL,
  last_event_at INTEGER NOT NULL,            -- feed order
  edit_count INTEGER NOT NULL DEFAULT 0,
  deleted_at INTEGER,                        -- NULL = not deleted
  batch_id INTEGER,                          -- bulk removal grouping (§6)
  source_name_snapshot TEXT,
  search_text TEXT NOT NULL DEFAULT '',
  UNIQUE(source_dialog_id, source_mid));
CREATE INDEX entry_feed ON entry(last_event_at, id);
CREATE INDEX entry_source ON entry(source_dialog_id, last_event_at);

CREATE TABLE revision(
  entry_id INTEGER NOT NULL,
  idx INTEGER NOT NULL,                      -- 0 = original
  kind INTEGER NOT NULL,                     -- ORIGINAL, EDIT
  edit_date INTEGER NOT NULL,
  observed_at INTEGER NOT NULL,
  content_hash INTEGER NOT NULL,
  data BLOB NOT NULL,                        -- serialized TLRPC.Message (as cache4.db stores it)
  text TEXT,                                 -- plain text fallback if data ever fails to deserialize
  PRIMARY KEY(entry_id, idx),
  UNIQUE(entry_id, content_hash, edit_date)) WITHOUT ROWID;

CREATE TABLE media(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  entry_id INTEGER NOT NULL, revision_idx INTEGER NOT NULL,
  state INTEGER NOT NULL, kind INTEGER NOT NULL,
  source_path TEXT, local_path TEXT, size INTEGER, created_at INTEGER);
CREATE INDEX media_entry ON media(entry_id);
CREATE INDEX media_state ON media(state);
```

Not stored: user names/avatars (except the fallback name), phone numbers, access hashes beyond what the serialized message
already holds, read state of the source chat.

Growth estimate (one entry ~3 KB including two serialized revisions, search text and indexes):

| Usage | Entries/day | 30 days | 1 year | 5 years |
| --- | --- | --- | --- | --- |
| typical | 5 | 0.45 MB | 5.5 MB | 27 MB |
| heavy | 40 | 3.6 MB | 44 MB | 220 MB |

Media dominates and is capped by the
1 GB budget. No automatic text retention limit in v1; the info page shows the size.

## 26. Migration strategy

* `meta.schema_version` with ordered migrations in `LocalHistoryDatabase.migrate()`; never destructive without a copy.
* Telegram TL layer changes: blobs are deserialized with `TLRPC.Message.TLdeserialize`, which keeps old constructors (cache4.db
  depends on the same). If a future upstream merge drops a constructor, the `revision.text` column keeps the entry readable
  as plain text. Test `RevisionBlobRoundTripTest` pins current constructors.
* `cache4.db` migrations (`MS.LAST_DB_VERSION`) do not affect the feature database.

## 27. Android backup and privacy

* Location: `getNoBackupFilesDir()` (excluded from Auto Backup, key/value and device transfer by platform contract).
* Today's manifest: custom key/value `BackupAgent` (only `saved_tokens*` preferences), `allowBackup="true"`, no
  `dataExtractionRules` (R§7). Recommendation: keep the feature in `no_backup` **and** add `android:dataExtractionRules` /
  `android:fullBackupContent` that exclude `no_backup/local_history` explicitly, so a future switch to Auto Backup cannot
  include it.
* Never sent over the network, never put in `cache4.db`, never in logs (`FileLog` lines carry ids and counts only, never text).
* Screenshots: the existing credential-level FLAG_SECURE rules apply; no new rule.

## 28. Protected Chats integration

Reuse, do not fork. Changes:

1. `ProtectedChats.isSupportedDialog(id)`: `id != 0 && (!DialogObject.isFolderDialogId(id) || LocalDialogIds.isLocal(id))`.
   One explicit admission; folders stay unsupported.
2. `ProtectedChatGate.getDialogId(BaseFragment)`: `LocalHistoryActivity`, `LocalHistoryProfileActivity`,
   `LocalHistoryEditActivity` return `LocalDialogIds.LOCAL_HISTORY`. `conversationDialogId` treats `LocalHistoryActivity` as a
   conversation (so opening a real chat from it is a genuine departure, as for any chat).
3. Identity resolver `ProtectedDialogIdentity.resolve(account, dialogId)` -> name + avatar setter; used by
   `ProtectedChatsListActivity` rows and `ChatLockSettingsActivity`'s title, with the local branch reading the local title and
   photo.
4. Chat-list row: Lock/Unlock in the row popup calls the same `ProtectedChatAuthSheet` (`PROTECT`/`UNPROTECT`) and
   `ProtectedChats.protect/unprotect` as `DialogsActivity`'s long-press does today.
5. Profile -> ⋮ -> Lock Settings: `LocalHistoryProfileActivity` menu item opening `ChatLockSettingsActivity` with the local id.
6. Hide Message Previews: `DialogCell` local mode and the chat-list search hit use `ProtectedChats.shouldHideContent`.
7. Auto-lock / lifecycle / gate-before-reveal: free, because the fragments are `BaseFragment`s in `ActionBarLayout` with a
   recognized dialog id (`ProtectedGateLifecycle` states apply unchanged).
8. Not applicable (asserted by tests): notifications, share targets, widgets, launcher shortcuts, bubbles.
9. Account removal: `ProtectedChats.onAccountRemoved` already clears all keys of the user id, local key included.

Invariants kept: one credential, one state machine, keys `(userId, dialogId)`, nothing unlocks without the sheet, folder ids
still unsupported. No new password.

## 29. Lifecycle / navigation integration

* Feature start: `LocalHistory.init(account)` lazily on first capture or first chat-list build after the account is activated
  (`UserConfig.isClientActivated`), opening the database on the feature queue (`DispatchQueue("localHistoryQueue")`).
* Capture writes happen on the storage thread (synchronous, short transactions) through the same `SQLiteDatabase` handle,
  guarded by the database's own lock (`synchronized` wrapper `LocalHistoryDatabase`), so capture never waits for UI work.
  UI reads run on the feature queue.
* Process death: capture is committed before Telegram's own commit (§7); copies resume from `PENDING_COPY` (§10).
* Navigation: `LocalHistoryActivity` is presented through `presentFragment` (gate applies); Back is ordinary.

## 30. Activity integration

* Activity classifies `LocalHistoryActivity` (and its profile/edit screens) as surface `LOCAL_HISTORY`, display category
  "Local History", per-dialog key `LocalDialogIds.LOCAL_HISTORY` (see activity-plan §7-9).
* Not counted as a private chat. The Activity per-chat list shows it with its local name/avatar via the same identity resolver.
* Protection does not stop accounting; Activity shows identity and time only, never content.

## 31. Performance

* Capture fast path when the feature is off: one static boolean read. When on and the dialog is not a user dialog: one compare.
* Eligible edit: diff + one small transaction (< 1 ms typical) on storage.
* Deletion batch: one indexed SELECT on rows Telegram reads anyway, one transaction.
* No timers, no services, no wake locks; copies only after a capture.
* UI: paged loads, `MessageObject` creation off the UI thread, no full-table scans outside search.

## 32. Race conditions

| Case | Handling |
| --- | --- |
| edit then delete immediately | both on storage FIFO in update order: edit capture first (old->new), delete capture reads the edited row; one entry with revisions + `deleted_at` |
| multiple edits in one batch | each `putMessages` row processed in order; revisions get increasing `idx`; identical content deduped |
| batch delete | one transaction; bulk rule (§6) |
| update before message is in memory | irrelevant: capture reads storage, not memory |
| message in DB but not in controller cache | normal case for capture |
| message not in DB | edit: Telegram drops it, we cannot see it (documented). delete: no row, nothing archived |
| reconnect bursts / duplicates | idempotency keys (§8) |
| push delete then update delete | push captures; the update finds no rows (and keys would dedupe anyway) |
| process death during archival | our transaction is atomic and precedes Telegram's; replay dedupes; media rows resume |
| media download racing deletion | a file still downloading is not "existing"; becomes `NOT_DOWNLOADED`; `FL.cancelLoadFiles` proceeds as today |
| user clears local history during capture | serialized on the database lock; capture after the clear creates a new entry |
| logout during write | `onAccountRemoved` closes the database under the lock after pending writes; later captures see user id 0 and skip |
| source user deleted / chat removed | entry keeps working; name falls back to `source_name_snapshot`; "Show in chat" hidden |
| Telegram DB migration / cleanup / clear database | no effect on our database; edits for cleared rows are not visible (documented) |
| our DB migration | versioned migrations; capture disabled until migration finished (captures during startup are queued in memory, bounded to 500, else dropped with a log line) |

## 33. Security invariants

1. The reserved id never appears in `MC.allDialogs`, `dialogs_dict`, `cache4.db`, or any TL request (G1-G4).
2. Archived `MessageObject`s are never outgoing, unread, view-counted, reactable, or downloadable from the network (G5, §10).
3. No server API is used to edit the local name or photo (§18).
4. Archived content leaves the feature database only to the screen, to the clipboard, or to the gallery on explicit user action.
5. Only remote entry points capture (§6); a local deletion on this device can never create an entry.
6. View-once and auto-delete expiries are never archived (§3).
7. Data is per account user id and removed with the account.
8. Protected Chats state for the local id follows the same rules as any dialog.

## 34. Unit-test architecture

Pure Java under `TMessagesProj/src/test/java/org/telegram/messenger/localhistory/` (JUnit 4, as existing tests):

* `LocalHistoryEligibilityTest`: every row of §3, including service ids, bots, Saved Messages, secret ids, folder ids, the local
  id itself, `ttl_seconds`, `ttl_period` near/after expiry, outgoing, service messages, deleted users.
* `LocalHistoryDiffTest`: text/entity/media/caption changes vs markup/web-preview/reaction-only updates.
* `LocalHistoryLedgerTest` (pure in-memory implementation of the repository interface): edit capture, repeated edits, identical
  replay, edit->delete, delete->edit (ignored), batch delete with bulk grouping, push+update convergence, clear during capture.
* `LocalDialogIdsTest`: the reserved id vs `makeFolderDialogId(0, 1, Integer.MAX_VALUE, -1, Integer.MIN_VALUE)`,
  `makeEncryptedDialogId(0..)`, boundary user ids (2^52-1), negative ids; classifier outcomes.
* `LocalHistoryNetworkGuardTest`: G1 behavior through a pure helper `LocalDialogIds.guardInputPeer(id, fallback)`.
* `LocalHistoryMediaStateTest`: hold/copy/lost/evicted transitions and budget eviction order with a fake file system.
* `ProtectedLocalIdentityTest`: `isSupportedDialog` accepts the local id and still rejects other folder ids;
  `ProtectedChatsState` protect/unlock/relock for the local key; account isolation.
* Negative controls (mutation checks run once per phase): make capture also run inside `markMessagesAsDeletedInternal` ->
  "local delete creates no entry" test must fail; remove the content diff -> markup-only edit test must fail; drop the
  `isLocal` admission -> protected local test must fail.

## 35. Integration tests

The repo has an instrumented test module, `TMessagesProj_AppTests` (`src/androidTest/kotlin`, `AndroidJUnitRunner`, used today by
the lyrics rendering tests). Integration tests go there, run on an emulator or device, never on a hosted CI runner unless the
owner asks:

* `LocalHistoryDatabaseTest`: real `org.telegram.SQLite` database in a temp `no_backup` directory: schema, migrations, unique
  keys, paging order, clear, account directory removal.
* `LocalHistoryCaptureIntegrationTest`: real serialized `TLRPC.Message` fixtures (round-tripped through `NativeByteBuffer`) fed
  through `LocalHistoryCapture.onEditStored` / `onRemoteDeleteBeforeStorage` against a scratch database; checks entries,
  revisions, idempotency and media rows.
* `RevisionBlobRoundTripTest`: serialize/deserialize every media type used in fixtures with the current TL layer.
* `LocalHistoryRenderModelTest`: built `MessageObject`s have `eventId != 0`, no views/replies/reactions flags, no remote file
  locations, `dialog_id == LOCAL_HISTORY`.

Pure logic stays in JVM unit tests (§34) so most coverage runs without a device.

## 36. Device QA

Two accounts A (owner) and B (other), plus A on a second device:

1. B edits a text message twice -> one entry, 3 versions; original chat unchanged.
2. B deletes a message (chat open / closed / app background / app killed + reopen / airplane mode then reconnect).
3. A deletes B's message "for me" on this device -> no entry. A deletes on the second device -> entry (documented ambiguity).
4. B clears the whole chat for both -> one collapsed bulk entry.
5. Photo downloaded then deleted -> preserved, opens in viewer, saves to gallery; photo never opened -> "Not saved".
6. 60 MB video downloaded then deleted -> `TOO_LARGE` label.
7. View-once photo deleted -> nothing archived. Auto-delete chat expiry -> nothing archived.
8. Bot, Saved Messages, secret chat, group, channel edits/deletes -> nothing archived.
9. Rename and set a photo -> network log shows no request (proxy or `ConnectionsManager` debug log).
10. Protect the local chat from the row and from the info page; previews hidden; Auto-lock; Back over it asks.
11. Switch accounts -> each sees only its own chat. Log out A -> its directory is gone.
12. Open the chat for 2 minutes -> Activity shows "Local History" time.
13. Open/close/scroll the chat with a network proxy log: zero requests attributable to it.

## 37. Implementation phases

Each phase: build the debug flavor, run `./gradlew :TMessagesProj:testDebugUnitTest` (or the repo's existing unit-test task),
commit, next phase. No phase triggers CI workflows by itself beyond the normal push policy chosen at implementation time.

| Phase | Goal | Depends | Files | APIs | Tests | Gate | Not yet |
| --- | --- | --- | --- | --- | --- | --- | --- |
| LH1 Identity + guards | reserved id, classifier, network guards | — | `messenger/localhistory/LocalDialogIds.java`; `MC.getInputPeer/getInputUser/getInputChannel`; `SendMessagesHelper.sendMessage` | `LocalDialogIds.LOCAL_HISTORY`, `isLocal`, `guardInputPeer` | `LocalDialogIdsTest`, `LocalHistoryNetworkGuardTest` | tests green; no behavior change for real ids | storage, capture, UI |
| LH2 Storage + model | feature DB, schema, repository, eligibility, diff | LH1 | `localhistory/LocalHistoryDatabase.java`, `LocalHistoryRepository.java`, `LocalHistoryEligibility.java`, `LocalHistoryDiff.java`, `LocalHistory.java` (per-account entry point, `onAccountRemoved`), `UserConfig.clearConfig` | repository interface (`recordEdit`, `recordDeletion`, `pageFeed`, `clear`, `deleteEntry`, `summary`) | eligibility, diff, ledger tests | DB created in `no_backup`; account removal deletes it | capture hooks, UI |
| LH3 Capture | edit and deletion capture | LH2 | `MS.putMessages` (+`fromEditUpdate` overload), `MS.getMessagesForArchiveSync`, `MC.processUpdateArray` (edit call site, deletion block), `MC.deleteMessagesByPush`, `localhistory/LocalHistoryCapture.java` | `onEditStored`, `onRemoteDeleteBeforeStorage` | ledger tests through capture entry points with fixtures; negative controls | device QA 1-4, 7-8 via a temporary debug dump (`FileLog` counts only) | media copy, UI |
| LH4 Chat list row + read-only feed | row, `LocalHistoryActivity` with text rendering, date separators, sender grouping, avatars, unread | LH3 | `DialogsAdapter`, `DialogCell` (local mode), `DialogsActivity.onItemClick`, `NotificationCenter.localHistoryChanged`, `ui/LocalHistoryActivity.java`, `localhistory/LocalHistoryRenderModel.java` | render model | render-model tests (flags cleared, ids, no remote locations) | QA 13 (no requests), row positions, theme switch | media, profile, lock |
| LH5 Media | holds, copier, budget, viewer, save to gallery | LH4 | `FL.deleteFiles`, `localhistory/LocalHistoryMediaHolds.java`, `LocalHistoryMediaCopier.java`, viewer provider | `hold/isHeld/release` | media state tests | QA 5-6 | profile, lock |
| LH6 Profile + identity | info page, edit name/photo, clear/delete, revision sheet, in-chat search | LH5 | `ui/LocalHistoryProfileActivity.java`, `ui/LocalHistoryEditActivity.java`, `ui/Components/LocalHistoryRevisionsSheet.java` | — | identity storage test | QA 9 | lock |
| LH7 Protected Chats | §28 items 1-9 | LH6 | `ProtectedChats`, `ProtectedChatGate`, `ProtectedDialogIdentity` (new), `ProtectedChatsListActivity`, `ChatLockSettingsActivity`, `DialogCell`, `DialogsSearchAdapter` | `ProtectedDialogIdentity.resolve` | `ProtectedLocalIdentityTest`, existing Protected Chats suites unchanged and green | QA 10 | — |
| LH8 Settings + hardening | enable/disable switch (Privacy and Security), hide row, backup rules, logging review, bulk entry UI | LH7 | `PrivacySettingsActivity` (or the new settings screen), `AndroidManifest.xml` + `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` | — | full suite | full device QA §36 | — |

## 38. Files / classes expected to change

New (`messenger/localhistory/`): `LocalDialogIds`, `LocalHistory`, `LocalHistoryDatabase`, `LocalHistoryRepository`,
`LocalHistoryEligibility`, `LocalHistoryDiff`, `LocalHistoryCapture`, `LocalHistoryRenderModel`, `LocalHistoryMediaHolds`,
`LocalHistoryMediaCopier`; `messenger/ProtectedDialogIdentity`. UI: `LocalHistoryActivity`, `LocalHistoryProfileActivity`,
`LocalHistoryEditActivity`, `Components/LocalHistoryRevisionsSheet`.

Existing: `MessagesStorage` (putMessages overload + one hook, `getMessagesForArchiveSync`), `MessagesController`
(`processUpdateArray` two call sites, `deleteMessagesByPush`, `getInputPeer*`/`getInputUser`/`getInputChannel` guards, G3
early returns), `SendMessagesHelper` (G2), `FileLoader.deleteFiles` (one line), `UserConfig.clearConfig` (one line),
`NotificationCenter` (one id), `DialogsAdapter`, `DialogCell`, `DialogsActivity`, `DialogsSearchAdapter`, `ProtectedChats`,
`ProtectedChatGate`, `ProtectedChatsListActivity`, `ChatLockSettingsActivity`, `ChatMessageCell` (status text hook),
`PrivacySettingsActivity`, `AndroidManifest.xml`, `res/values/strings.xml`, new `res/xml` rules.

Not changed: `ChatActivity`, `ProfileActivity`, `MessagesStorage` schema, `ConnectionsManager`.

## 39. Risks and open questions

Product decisions needed:

* **Q1** Deletions the owner makes on another device are indistinguishable and will be archived. Accept with explanatory copy
  (recommended), or archive edits only?
* **Q2** Bulk removals (> 20 in one update from one chat): archive as one collapsed entry (recommended) or skip?
* **Q3** Media limits: 50 MB per file and 1 GB per account (recommended), or text only in v1?
* **Q4** Default state: off until enabled in Privacy and Security (recommended, because it keeps content other people removed),
  or on by default?
* **Q5** Logout deletes the account's local history (recommended), or keep it for the next login of the same user?
* **Q6** Final product name and default title/avatar.

Technical risks:

* Edits that reach the client only through a history reload (gaps, `differenceTooLong`, cleared database) are invisible.
* `ImageUpdater` may upload by default; Phase 6 gate verifies (§18).
* `ChatMessageCell` status-text hook is a small upstream-touching change; keep it a single nullable field.
* Upstream TL layer changes could break blob deserialization; plain-text fallback mitigates.
* Copy window vs. keep-media cleanup produces `LOST` in rare cases.

## 40. Acceptance criteria

1. Edits and deletions by ordinary private users of incoming messages appear in exactly one Local History entry per message,
   with the full observed revision chain, in the right account only.
2. No entry is ever created by the owner's own actions on this device, by bots, service accounts, Saved Messages, secret
   chats, groups, channels, topics, view-once media or auto-delete expiry.
3. The original private chat looks and behaves exactly as on `master`.
4. Downloaded media of deleted messages remains viewable within the limits; never-downloaded media is labeled, not fetched.
5. Opening, scrolling, searching, viewing media, editing name/photo and clearing the Local History Chat issue zero network
   requests (verified by guard tests and QA 13).
6. The reserved id passes the collision test and never appears in `allDialogs`, `cache4.db` or any TL request.
7. Protected Chats works for the local chat through the existing sheet, settings, previews, Auto-lock and gate, with all
   existing Protected Chats tests unchanged and green.
8. Feature data lives in `no_backup`, is excluded by explicit rules, and is deleted with the account.
9. Replaying the same updates (reconnect, crash, push + update) never duplicates entries or revisions.
10. All unit tests in §34 pass, including the negative controls failing when the protected behavior is removed.
