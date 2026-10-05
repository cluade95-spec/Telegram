# Activity and Local History Chat: implementation plan

Single-file plan for two independent features, cut into **3 implementation parts**. Baseline: `master` `055a0c3b` (PR #9,
Protected Chats) plus the planning docs of PR #11. Source evidence: [activity-research.md](activity-research.md) and
[local-history-chat-research.md](local-history-chat-research.md) (cited as R§n inside the references).
Paths are relative to `TMessagesProj/src/main/java/org/telegram/`.

## How this file is organized

1. **The 3 parts** (below): what to build, in which order, with entry conditions, deliverables and gates. Implement one part at a
   time; inside a part, one phase at a time (build, test, commit, next).
2. **Reference A: Activity specification** (sections `A1`-`A28`) and **Reference B: Local History Chat specification**
   (sections `B1`-`B40`): the full design. A `§n` written inside Reference A means A`n`; inside Reference B it means B`n`.
   Phase ids (`A1`...`A6`, `LH1`...`LH8`) are the rows of the phase tables in A25 and B37.

Rules for every part:

* Read the part, the referenced sections and the matching research sections, then re-verify the cited methods in the
  current source (line numbers are approximate; method names are the contract). If the source contradicts the plan, stop that
  phase and report the conflict instead of working around it.
* Implement only the files listed for the phase. Add the listed tests (including negative controls). Run the JVM unit tests under
  `TMessagesProj/src/test` and the existing Protected Chats suites; they must stay green.
* One commit per phase (phase id in the message), one pull request per part. Do not trigger the manual build workflow unless the
  owner asks.
* Defaults for the open product questions are the recommendations in A27 and B39 until the owner answers otherwise.

## The 3 parts at a glance

| Part | Name | Phases | Result you can test on a device | Depends on |
| --- | --- | --- | --- | --- |
| 1 | Activity | A1, A2, A3, A4, A5 | Settings → Activity shows real data | nothing |
| 2 | Local History core | LH1, LH2, LH3, LH4, LH5 | other people's edits and deletions are captured and readable in a read-only chat, with media | nothing (independent of Part 1) |
| 3 | Local History finish + Activity integration | LH6, LH7, LH8, A6 | editable name/photo, version history, search, Protected Chats, settings, backup rules, "Local History" time in Activity | Parts 1 and 2 |

Parts 1 and 2 do not depend on each other and can be built in either order or in parallel by different people; Part 3 needs both.
Recommended order: 1, 2, 3 (Activity first: no message pipeline, pure core, one open decision).

---

## Part 1: Activity (phases A1-A5)

**Goal:** Settings → Activity works end to end for all accounts: active-time accounting, surface classification, local storage,
dashboard. The Local History surface does not exist yet, so its screens (none yet) need no mapping.

**Build (see A25 for the per-phase table):**

| Phase | One-line scope | Main files |
| --- | --- | --- |
| A1 | pure accounting core with injectable clock | `messenger/usage/UsagePolicy`, `UsageSurface`, `UsageClock`, `UsageAccountant`, `UsageLedger` |
| A2 | classifier (pure) + Android surface resolver | `usage/UsageClassifier`, `UsageSurfaceResolver`, `DialogsActivity.isSearchShown()` |
| A3 | wire input, foreground, screen, navigation, playback, call signals | `usage/UsageTracker`, `LaunchActivity`, `BottomSheet`, `AlertDialog`, `PhotoViewer`, `SecretMediaViewer`, `StoryViewer`, `ArticleViewer`, `ChatActivityEnterView`, `BaseFragment`, `ActionBarLayout`, `MainTabsActivity`, `ApplicationLoader` |
| A4 | SQLite store, flush, rollup, account removal, messages sent / opens / sessions | `usage/UsageStore`, `UserConfig.clearConfig`, `SendMessagesHelper`, `SecretChatHelper` |
| A5 | dashboard, Settings row, settings search entry, strings | `ui/UsageReportActivity`, `SettingsActivity`, `ProfileActivity.SearchAdapter`, `strings.xml` |

**Entry conditions:** none. Idle window 60 s and the other Activity defaults in A27 unless changed.

**Exit gate (all must hold):**

* A23 unit tests green, negative controls fail when the protected behavior is removed (idle cap, double booking).
* Device QA A24 steps 1-11 pass (step 12, Local History time, waits for Part 3).
* No timer, service, wake lock or network request added (A14); data lives in `no_backup/usage`.
* Acceptance criteria A28 items 1-4 and 6-9 (item 5 partly: protected chats count time with identity only; Local History part in Part 3).

**Explicitly not in this part:** the Local History surface and its mapping (A6), anything under `messenger/localhistory`.

---

## Part 2: Local History core (phases LH1-LH5)

**Goal:** the archive works. Edits and remote deletions of ordinary private users are captured exactly once, the chat appears in
the chat list and shows entries in a group-style, read-only feed, and downloaded media survives deletion.

**Build (see B37 for the per-phase table):**

| Phase | One-line scope | Main files |
| --- | --- | --- |
| LH1 | reserved id, classifier, network guards G1-G4 | `messenger/localhistory/LocalDialogIds`, `MessagesController` (`getInputPeer*`, `getInputUser`, `getInputChannel`), `SendMessagesHelper` |
| LH2 | feature database (`no_backup`), schema, repository, eligibility, diff, account removal | `localhistory/LocalHistoryDatabase`, `LocalHistoryRepository`, `LocalHistoryEligibility`, `LocalHistoryDiff`, `LocalHistory`, `UserConfig.clearConfig` |
| LH3 | edit and deletion capture hooks | `MessagesStorage.putMessages` (+ `fromEditUpdate`), `getMessagesForArchiveSync`, `MessagesController.processUpdateArray`, `deleteMessagesByPush`, `LocalHistoryCapture` |
| LH4 | chat-list row, `LocalHistoryActivity` read-only feed, unread, **plus the enable switch and "Delete all local history"** (moved here from LH8) | `DialogsAdapter`, `DialogCell`, `DialogsActivity`, `NotificationCenter`, `ui/LocalHistoryActivity`, `LocalHistoryRenderModel`, `PrivacySettingsActivity` |
| LH5 | media holds, copier, budget, viewer, save to gallery | `FileLoader.deleteFiles`, `LocalHistoryMediaHolds`, `LocalHistoryMediaCopier`, viewer provider |

**Entry conditions:** none beyond the defaults in B39 (off by default, delete on logout, 50 MB / 1 GB media limits, bulk grouping above 20).

**Exit gate (all must hold):**

* B34 tests for eligibility, diff, ledger, ids, network guards and media states green; the three mutation checks fail as designed.
* Device QA B36 steps 1-8 and 11 and 13 pass (steps 9-10 and 12 belong to Part 3).
* Opening, scrolling and closing the chat issues zero network requests (guard tests + proxy log).
* Existing Protected Chats suites unchanged and green; `ChatActivity`, `ProfileActivity` and `cache4.db` schema untouched.

**Known limitation until Part 3:** the Local History Chat cannot yet be locked with Protected Chats and has no edit/profile/search
screens. The feature stays off by default, and the enable screen says so; do not enable it for daily use before Part 3.

**Explicitly not in this part:** name/photo editing, version-history sheet, in-chat search, Protected Chats, backup rules, the Activity surface.

---

## Part 3: Local History finish and Activity integration (phases LH6, LH7, LH8, A6)

**Goal:** complete the Local History Chat and connect it to Activity.

**Build:**

| Phase | One-line scope | Main files |
| --- | --- | --- |
| LH6 | info page, local name/photo (no server APIs), version-history sheet, in-chat search, per-entry delete, clear history | `ui/LocalHistoryProfileActivity`, `ui/LocalHistoryEditActivity`, `Components/LocalHistoryRevisionsSheet` |
| LH7 | Protected Chats integration (B28 items 1-9) | `ProtectedChats`, `ProtectedChatGate`, `ProtectedDialogIdentity`, `ProtectedChatsListActivity`, `ChatLockSettingsActivity`, `DialogCell`, `DialogsSearchAdapter` |
| LH8 | hardening: explicit backup / data-extraction rules, logging review, bulk-entry UI polish, full QA | `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` |
| A6 | Activity classifies the Local History screens as "Local History", tablet rules, backup rules (shared with LH8), performance review | `UsageClassifier`, `UsageSurfaceResolver` |

**Entry conditions:** Part 1 and Part 2 merged. Verify the `ImageUpdater` no-upload gate at the start of LH6 (B18); use the fallback
picker if it uploads.

**Exit gate (all must hold):**

* `ProtectedLocalIdentityTest` and all earlier suites green; existing Protected Chats tests unchanged.
* Full device QA B36 (1-13) and A24 (1-12) pass, including: no request when renaming or setting the photo; lock from the row and from the info page; time spent in the chat shows as "Local History" in Activity.
* Acceptance criteria B40 (all 10) and A28 (all 9).

**Explicitly not in this part:** notifications for archive events, forwarding preserved content, archived content in global search, Android Auto Backup of feature data.

---

## Reference A: Activity specification

Product name: **Activity** (Settings → Activity). Evidence: [activity-research.md](activity-research.md). Internal code uses the prefix
`Usage` to avoid confusion with Android activities (`UsageTracker`, `UsageReportActivity`); the user-facing name is Activity.
Independent of the Local History Chat; the only coupling is one surface (A7). Phase **A6** is delivered in Part 3, A1-A5 in Part 1.

### A1. Product scope

A local report answering "where did my time inside Telegram actually go?":

* total **active** time for Today / This week / This month, with the previous period for comparison;
* a non-overlapping breakdown by surface category;
* most-used chats;
* a small set of secondary metrics (§16).

Everything is computed and stored on the device.

### A2. Non-goals

Usage limits, focus mode, blocking, breaks, doomscroll warnings, parental controls, AI analysis, cloud analytics, server
reporting, Telegram API changes, uploads, background services, periodic wakeups, per-message tracking, content capture.

### A3. Existing architecture discovered

* No app-wide last-input timestamp exists; `LaunchActivity.onUserInteraction` is the cheapest central input hook but misses
  separate windows and IME typing (R§1).
* Foreground: `ForegroundDetector` (app-wide refcount). Background boundary: activity stop, not pause (PR #9 finding).
  Screen: `ScreenReceiver` / `NotificationCenter.screenStateChanged` (R§2).
* Surface: no single "visible fragment" notion; main tabs live in `MainTabsActivity` outside the stack; tablets show up to three
  layouts; viewers are separate windows (R§3).
* Calls: `VoIPService` state and `didStartedCall` / `didEndCall` (R§3).
* Messages sent: existing confirmed-send counting points in `SendMessagesHelper` (R§5).
* UI: `UniversalFragment` + `UItem.asChart` + `ui/Charts` work without network (R§6).

### A4. Active-time definition

A second counts as **active** when all of these hold:

1. Telegram is in the foreground (`ForegroundDetector.isForeground()`), **and**
2. the screen is on (`ApplicationLoader.isScreenOn`), **and**
3. either the user interacted within the idle window (§5), or an **engaged passive activity** is running (§6), or a call is
   connected (§6).

Each active second is credited to exactly one **surface key** `(accountUserId, surface, dialogKey)`: the primary surface at
that instant (§8). Calls are exclusive: while a call is connected, its seconds go to Calls and nothing else.

### A5. Idle detection

**Model: event-driven, no timers.** The accountant remembers `lastInputAt` (monotonic `SystemClock.elapsedRealtime`). When any
event arrives at time `t`, the open segment `[segStart, t]` is credited only up to `activeUntil = max(lastInputAt + IDLE,
passiveUntil)`. Nothing runs while the user is idle; the credit is computed lazily at the next event (input, navigation,
background, screen off, dashboard open).

**`IDLE = 60 s`.** Reasoning:

* The screen turning off ends accounting immediately. Common Android screen timeouts are 30 s to 2 min, so in the common case
  the screen timeout, not our window, ends an idle period. The window matters when the screen stays on (long timeouts,
  "screen attention" that keeps it on while the user looks, docks).
* Reading without touching is real use. A long channel post (about 300 words) takes 60-75 s to read; a window shorter than
  that (the 15 s Telegram uses for per-message read metrics) would undercount reading.
* Overcount is bounded: putting the phone down with the screen on adds at most 60 s per abandonment. The example in the brief
  (2 min scrolling, 20 min untouched) reports 3 min, not 22.
* Monotonic and explainable: a gap of `g` seconds without input credits `min(g, 60)`.

The constant lives in `UsagePolicy.IDLE_MS`; tests pin the behavior.

**What counts as input:** touches and keys in the main window (`LaunchActivity.onUserInteraction`, `dispatchKeyEvent`); touches
in `BottomSheet`, `AlertDialog`, `PhotoViewer`, `SecretMediaViewer`, `StoryViewer` (own window), `ArticleViewer` (own window);
text changes in `ChatActivityEnterView` and search fields (IME typing produces no touch in our windows). Scrolling is touch;
fling continuation after the finger lifts is covered by the window. Navigation itself is not input (a programmatic navigation
must not reset idle).

### A6. Passive media and call rules

| Activity | Counts? | Rule | Why |
| --- | --- | --- | --- |
| Video in `PhotoViewer` (playing, viewer visible) | yes | extends `passiveUntil` while playing | the user chose to watch |
| Stories playing (`StoryViewer` shown, not paused) | yes | while playing | auto-advance is watching |
| Voice / round message playback (`MediaController`, app foreground, screen on) | yes | while playing | listening to a message |
| Music playback with Telegram in front | no extension | normal idle window applies | a playlist can play for an hour while the phone sits; the user is not using Telegram |
| Any playback with Telegram in background or screen off | no | — | not "inside Telegram" by our definition |
| Autoplaying GIFs / muted in-chat videos | no | — | not chosen by the user |
| Picture-in-picture over other apps | no | Telegram is not foreground | consistent rule; listed as an open question |
| 1-to-1 call (`VoIPService`, `STATE_ESTABLISHED`) | yes, as **Calls**, even with the screen off (proximity sensor) | exclusive while connected | a call is unambiguous Telegram use |
| Group call / video chat / live stream joined and connected | yes, as **Calls** | exclusive while connected | same |
| System permission dialog over the app | normal idle window | activity paused, not stopped; no input reaches us | brief by nature |
| Split-screen / multi-window, Telegram not focused | normal idle window | input stops arriving | — |

### A7. Surface taxonomy

Internal surfaces (stored as small ints, stable):

| Id | Surface | Display category | Per-dialog key |
| --- | --- | --- | --- |
| 1 | CHAT_PRIVATE | Private Chats | user id |
| 2 | CHAT_SECRET | Private Chats | encrypted dialog id |
| 3 | CHAT_BOT | Bots | bot user id |
| 4 | CHAT_SAVED | Other (listed as "Saved Messages") | self id |
| 5 | CHAT_GROUP (basic, supergroup, forum topic, comments thread) | Groups | chat dialog id (parent for topics) |
| 6 | CHAT_CHANNEL (broadcast, channel direct messages) | Channels | channel dialog id |
| 7 | CHAT_LIST (main list, archive, folders, community lists, topics side panel) | Chat List | 0 |
| 8 | SEARCH (chat-list search, hashtag search mode) | Search | 0 |
| 9 | STORIES | Stories | 0 |
| 10 | MEDIA_VIEWER (`PhotoViewer`, `SecretMediaViewer`, Instant View) | Media | 0 |
| 11 | PROFILE (any profile, including own) | Other ("Profiles") | 0 |
| 12 | SETTINGS | Other ("Settings") | 0 |
| 13 | CALL | Calls | 0 |
| 14 | LOCAL_HISTORY | Local History | `LocalDialogIds.LOCAL_HISTORY` |
| 15 | OTHER | Other | 0 |

Decisions: bots are their own category (bot use is neither a conversation with a person nor a channel); Saved Messages is
personal storage, counted under Other but visible in Most Used; secret chats are private chats; topics roll up to their group;
channel comments count as Groups (the discussion group is where the time goes); the Local History Chat is its own category and
never Private Chats. Display categories with zero time are hidden.

### A8. Surface-classification architecture

Three pieces, all central:

* `UsageSurface` (pure enum + display mapping).
* `UsageClassifier` (pure, unit-tested): `SurfaceKey classify(FragmentFacts top, OverlayFacts overlays, boolean inSettingsContext)`
  where `FragmentFacts` is a plain value (kind, dialog id, chat mode, isBot, isSelf, isChannel, isMegagroup, isForum, isEncrypted,
  searchShown, folderId) produced by the Android adapter. Priority: call connected > media viewer > stories > Instant View >
  top fragment.
* `UsageSurfaceResolver` (Android): builds the facts from `LaunchActivity`:
  1. overlays: `VoIPService`/group call state, `PhotoViewer.hasInstance() && isVisible()`, `SecretMediaViewer`, `StoryViewer`
     (`globalInstances` or the top fragment's `getLastStoryViewer()`), `ArticleViewer`;
  2. top fragment: top of `sheetFragmentsStack`, else on tablets the layers layout if visible, else the right layout's last
     fragment if non-empty and not `tabletFullSize`, else `actionBarLayout.getLastFragmentIncludeMainTabs()`; `BubbleActivity`
     when it is the foreground activity;
  3. settings context: `SettingsActivity` is in the stack below the top fragment and the top is not a chat, profile, chat list,
     story or viewer (no list of settings classes needed).

Fragment kinds recognized by type: `ChatActivity`, `DialogsActivity`, `TopicsFragment`, `ProfileActivity`/`ProfileActivity2`,
`SettingsActivity`, `LocalHistoryActivity` (and its profile/edit screens), `CallLogActivity` (OTHER), `ContactsActivity` (OTHER).
Everything else is OTHER unless in settings context. Adding a type is one line in the resolver.

`ChatActivity` mapping: `getCurrentEncryptedChat() != null` -> SECRET; user: self or `MODE_SAVED` -> SAVED, `isBot` -> BOT,
else PRIVATE; chat: `isChannelAndNotMegaGroup` or `isMonoForum` -> CHANNEL, else GROUP (topic key = parent dialog);
`MODE_SEARCH` -> SEARCH; `MODE_QUICK_REPLIES`, `MODE_EDIT_BUSINESS_LINK`, `MODE_WELCOME_MESSAGES` -> SETTINGS; other modes keep
the dialog's surface.

Resolution triggers (each just marks the resolver dirty; one coalesced UI-thread runnable resolves): `BaseFragment.onResume` /
`onPause` (next to the existing `ProtectedChatGate` calls), `ActionBarLayout.onFragmentStackChanged`, `MainTabsActivity.onViewPagerScrollEnd`,
viewer open/close, `StoryViewer` open/close, `DialogsActivity.showSearch`, call start/end, `activeAccountChanged`.

### A9. Per-dialog accounting

* Key: `(accountUserId, dialogId)` with the dialog id Telegram uses (users > 0, chats < 0, secret chats encrypted id, local
  history reserved id). No names or avatars stored.
* Display resolves names dynamically: `MessagesController.getUser/getChat/getEncryptedChat` (loading from storage if needed);
  the local history id resolves through `ProtectedDialogIdentity` (Reference B §28).
* Migrated groups: rows keyed by the old basic-group id are merged into the channel's row at display time when
  `chat.migrated_to` is known, and rewritten at the next rollup.
* Topics: parent dialog only in v1.
* Chat no longer exists / account deleted / secret chat gone: the row stays with "Deleted chat" / "Deleted account" and a
  placeholder avatar; time is never dropped from category totals.
* Protected chats: time is always counted. The Activity UI shows identity and time only (Protected Chats already never hides
  identity); tapping a row opens the chat through `ProtectedChatGate`.

### A10. Multi-account behavior

* Every credit carries the account's `clientUserId` at that moment (`UserConfig.selectedAccount`).
* The dashboard shows **all accounts combined** for totals, categories and charts (the question is about time in the app), and
  Most Used chats from all accounts, with a small account avatar on rows when more than one account is logged in.
* `activeAccountChanged` closes the current segment.
* Account removal (`UserConfig.clearConfig`, next to `ProtectedChats.onAccountRemoved`): per-dialog rows of that user id are
  re-keyed to `dialog_id = 0` (time stays in totals and categories; chat identities are dropped).

### A11. Storage schema

Feature-owned SQLite file `getNoBackupFilesDir()/usage/usage.db` via `org.telegram.SQLite.SQLiteDatabase` (bundled sqlite
3.39.3 supports `ON CONFLICT DO UPDATE`), WAL, own `DispatchQueue("usageQueue")`. Not `SharedPreferences` (needs range
queries and upserts), not `cache4.db` (wiped on logout and "clear database"), not a `StatsController`-style flat file (needs
dialog-level rows).

```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value BLOB);     -- schema_version, created_at, last_rollup_day
CREATE TABLE bucket(
  day INTEGER NOT NULL,        -- local date yyyymmdd at credit time
  hour INTEGER NOT NULL,       -- 0..23, or -1 after rollup
  account INTEGER NOT NULL,    -- clientUserId
  surface INTEGER NOT NULL,
  dialog INTEGER NOT NULL,     -- 0 when not per-dialog
  seconds INTEGER NOT NULL,
  PRIMARY KEY(day, hour, account, surface, dialog)) WITHOUT ROWID;
CREATE INDEX bucket_dialog ON bucket(account, dialog, day);
CREATE TABLE daily(
  day INTEGER NOT NULL, account INTEGER NOT NULL,
  opens INTEGER NOT NULL DEFAULT 0, sessions INTEGER NOT NULL DEFAULT 0,
  longest_session INTEGER NOT NULL DEFAULT 0,   -- seconds
  messages_sent INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(day, account)) WITHOUT ROWID;
```

### A12. Aggregation and retention

* Credits are split at local hour boundaries when they are booked (§13), so `bucket` always holds whole hours.
* **Rollup** (run at most once per day, at the first flush after midnight, on `usageQueue`): rows older than **90 days** are
  summed into `hour = -1` rows per `(day, account, surface, dialog)`; the hour-of-day chart only needs recent data.
* Rows older than **2 years** keep categories but fold dialogs with less than 60 s on a day into `dialog = 0`.
* Growth (bytes per row about 40 + index):

| Usage | rows/day (hourly) | 30 days | 1 year | 5 years |
| --- | --- | --- | --- | --- |
| typical (2 h, ~10 chats) | ~60 | 0.1 MB | ~0.6 MB | ~2 MB |
| heavy (8 h, ~40 chats, every hour) | ~300 | 0.55 MB | ~2.5 MB | ~7 MB |

  Assumes ~60 bytes per row including the primary key. Without rollup heavy use would reach about 6.6 MB a year and 33 MB in
  five years; rollup keeps it a few MB.

### A13. Write / flush strategy

* Accounting is in memory: `UsageAccountant` books credits into a `UsageLedger` (`HashMap<BucketKey, Integer>` seconds).
* Flush (one transaction of upserts on `usageQueue`) when: the app goes to the background (`ForegroundDetector.onBecameBackground`),
  the screen turns off, the dashboard opens, the account changes, or unflushed time reaches **5 minutes** (checked at accounting
  events; no timer).
* Crash/process-death loss is bounded by 5 minutes of foreground time; background flushes make loss after the app is left zero.
* Clock: durations use `SystemClock.elapsedRealtime()`; the local day/hour of a credit uses `System.currentTimeMillis()` and the
  default time zone **at booking time**. A wall-clock jump or time-zone change only moves where future credits are booked; an
  interval is never double counted or negative because durations are monotonic. A credit spanning midnight is split.

### A14. Performance and battery budget

Hard constraints for implementation:

* Per input event: one `long` store and one comparison on the UI thread. No allocation, no locking, no logging.
* Per navigation event: mark dirty; at most one resolver run per UI frame (posted runnable, coalesced).
* No timers, no `Handler` loops, no services, no alarms, no wake locks, no `JobScheduler`.
* Disk writes only at flush boundaries (§13): typically a few per hour of use.
* Dashboard queries on `usageQueue`, each bounded by the period's rows (< 10 ms on the heavy estimate).
* No per-frame or per-scroll work; no reflection on hot paths.

### A15. Dashboard UX

`UsageReportActivity` (`UniversalFragment`, title "Activity"):

1. **Segmented control** Today / Week / Month (Telegram's existing tab-style control used in statistics screens).
2. **Summary**: total active time (large), comparison line "32 min less than yesterday" / "than last week" / "than last month",
   using the same elapsed fraction of the previous period for Today ("by this time yesterday") to avoid unfair comparisons.
3. **Chart**: Today: hours 0-23 stacked by category; Week: 7 days stacked; Month: days stacked.
4. **Categories**: rows with category color dot, name, time, percent bar (non-overlapping, sums to the total).
5. **Most Used**: top 10 chats (avatar, name, time; tap opens the chat). "Show more" up to 50.
6. **More**: messages sent, app opens, longest session, calls total.
7. Footer: "Activity is measured only on this device and never leaves it." and **Reset Activity** (confirm dialog: deletes
   `usage.db` content).

Empty state (first day, nothing yet): illustration-free text "Activity will appear here as you use Telegram." with the footer.

### A16. Charts and metrics

| Metric | v1 | Reason |
| --- | --- | --- |
| total active time + previous period | yes | core answer |
| category breakdown | yes | core answer |
| per-chat top list | yes | core answer |
| time by hour (Today chart) | yes | cheap, from hourly buckets |
| daily totals (Week/Month chart) | yes | cheap |
| messages sent | yes | factual "communication" signal (see below) |
| app opens (foreground transitions) | yes | cheap, understood |
| longest session (continuous active time without a gap > 5 min or background) | yes | one number per day |
| call duration | yes, as the Calls category | already measured |
| time by day of week | no (later) | needs months of data to mean anything |
| monthly totals across months | no (later) | Month view covers v1 |

**Communication vs consumption:** a robust split by intent is not possible from the source: time in a group is reading and
writing mixed, time in a private chat can be reading a long forward, and Telegram exposes no reliable signal of composing
versus reading beyond messages sent. Showing "Communication 62%" would be fake precision. v1 shows the factual pieces instead:
categories (Private Chats / Groups / Channels are understandable on their own) plus **messages sent** per period. A later
version can add "time in chats where you wrote that day" (derivable from per-dialog sent counts) if wanted.

Messages sent: counted at the existing `StatsController.incrementSentItemsCount(..., TYPE_MESSAGES, n)` call sites in
`SendMessagesHelper` (server confirmed; forwards counted per message), plus the `SecretChatHelper` confirmation. Scheduled
messages count when sent by the server.

Charts: `StatisticActivity.ChartViewData` + `StatisticActivity.createChartData(json, VIEW_TYPE_STACKBAR, false)` +
`UItem.asChart(...)`; series colors from existing `statisticChartLine_*` theme keys mapped per category.

### A17. Settings integration

* `SettingsActivity.fillItems`: one new `SettingCell.Factory.of(24, ..., R.drawable.<activity icon>, "Activity", "<today total>")`
  placed after Data and Storage; `onClick` case 24 -> `presentSettingFragment(new UsageReportActivity())`.
* Settings search: `ProfileActivity.SearchAdapter.onCreateSearchArray` entry id 1000 ("Activity") with `tg://settings/activity`
  if `LinkManager` routes settings links (Phase A5 checks).
* Legacy own-profile rows in `ProfileActivity` are not extended (the new `SettingsActivity` is the settings home in this build).

### A18. Theme and accessibility

* All colors from `Theme` keys (`windowBackgroundWhite`, `windowBackgroundWhiteBlackText`, `windowBackgroundWhiteGrayText`,
  `statisticChartLine_*`); no hard-coded colors; observe `NotificationCenter.didSetNewTheme` through `UniversalFragment`.
* Time formatting via `LocaleController` (plural-aware strings, `formatPluralString`); 24h/12h from `LocaleController.is24HourFormat`.
* Content descriptions: summary row reads "Today, 3 hours 42 minutes"; category rows read name, time, percent; charts have a
  text summary row for TalkBack.
* RTL: `LayoutHelper` + `LocaleController.isRTL` like other settings screens.

### A19. Lifecycle handling

| Event | Effect |
| --- | --- |
| foreground (`ForegroundDetector.onBecameForeground`) | open session, count an app open, start segment on resolved surface |
| background (`onBecameBackground`) | close segment, close session, flush |
| screen off / on (`screenStateChanged`) | close / reopen segment |
| activity pause without stop (permission dialog) | nothing; idle window applies |
| navigation, tab settle, sheet, viewer, search, call start/end | close segment, resolve, open new segment |
| account switch | close segment, flush, open with new account |
| Protected Chats gate sheet over a chat | the chat stays the surface (sheet is a `BottomSheet`); time while authenticating is short and real |
| configuration change / activity recreation | `ForegroundDetector` refcount keeps foreground; segment continues; resolver re-runs |

### A20. Process-death handling

In-memory ledger lost; at most 5 minutes of foreground time (§13). On start, no recovery is attempted; there is no "open
segment" on disk to repair. The database is always consistent (transactions).

### A21. Edge cases

* Bubble chats (`BubbleActivity`): treated as the chat they show.
* `ExternalActionActivity` / passcode screen: OTHER while the app lock (`PasscodeView`) covers content.
* Split screen with Telegram visible but another app focused: no input -> idle after 60 s.
* Tablet with chat list and chat both visible: the right pane chat wins (where the user reads and types); the left list gets time
  only when the right pane is empty.
* Main-tabs swipe (two tabs resumed): resolved after `onViewPagerScrollEnd`.
* `RightSlidingDialogContainer` topics panel: Chat List.
* Forward picker / share picker (`DialogsActivity` with `onlySelect`): OTHER (selection, not reading the list).
* Day boundary while active: split at midnight.
* Device reboot: `elapsedRealtime` resets; no open segment survives, so no artifact.
* User changes clock backwards: new credits book under the new date; nothing negative.
* Very long session without input but with video playing: counts (engaged passive).

### A22. Testing architecture

Pure core in `messenger/usage/`: `UsagePolicy`, `UsageSurface`, `UsageClassifier`, `UsageAccountant`, `UsageLedger`,
`UsageClock` (interface: `elapsed()`, `wallMillis()`, `zone()`), `UsageReportMath` (period ranges, comparisons, percentages).
Android adapters (`UsageTracker`, `UsageSurfaceResolver`, `UsageStore`) stay thin. Tests use a `FakeClock` and drive the
accountant with event scripts.

### A23. Unit tests (JVM, `TMessagesProj/src/test/java/org/telegram/messenger/usage/`)

* `UsageAccountantIdleTest`: 2 min of inputs then 20 min silence -> 3 min; gap 59 s fully counted; gap 61 s counts 60 s.
* `UsageAccountantForegroundTest`: background/screen-off stops crediting immediately; pause-without-stop does not.
* `UsageAccountantSurfaceTest`: transitions credit the old surface up to the switch; no second credited twice (sum of
  credits == wall active time for random scripts, property-style with a fixed seed).
* `UsageAccountantPassiveTest`: video/story/voice extend; music does not; playback in background does not.
* `UsageAccountantCallTest`: call exclusivity, screen off during call still counts, call end resumes surface.
* `UsageAccountantClockTest`: midnight and hour splits, DST change, wall-clock jump forward/backward, time-zone change.
* `UsageClassifierTest`: every row of §7 and the `ChatActivity` mode mapping; settings context; overlays priority.
* `UsageLedgerTest` / `UsageRollupTest`: aggregation, rollup to `hour = -1`, dialog folding after 2 years.
* `UsageAccountIsolationTest`: credits keyed per account user id; account removal re-keys to dialog 0.
* `UsageReportMathTest`: Today vs "by this time yesterday", week/month ranges per locale first day of week, percentages
  summing to 100 with rounding.
* Negative controls: removing the idle cap must fail the 20-minute test; double-booking on surface change must fail the sum
  property.

### A24. Integration / device tests

* Instrumented (`TMessagesProj_AppTests/src/androidTest`): `UsageStoreTest` against a real sqlite file (upserts, rollup,
  reset, concurrent flush while querying).
* Device QA script: (1) scroll list 2 min, leave phone untouched with screen on 5 min -> Chat List about 3 min; (2) read a
  channel 5 min with occasional scrolls -> Channels; (3) open a photo then video -> Media; (4) stories 2 min -> Stories;
  (5) 3-minute call with screen off at the ear -> Calls 3 min, nothing else in that period; (6) switch account mid-chat ->
  split per account; (7) background for 10 min -> nothing; (8) tablet: list + chat -> chat; (9) reset clears; (10) theme
  switch on the dashboard; (11) protected chat time appears without content; (12) Local History Chat time appears under
  Local History.

### A25. Implementation phases

| Phase | Goal | Depends | Files | APIs | Tests | Gate | Not yet |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A1 Core accounting | pure accountant, policy, clock, ledger, surfaces enum | — | `messenger/usage/UsagePolicy`, `UsageSurface`, `UsageClock`, `UsageAccountant`, `UsageLedger` | `onInput`, `onForeground/Background`, `onScreen`, `onSurface`, `onPassive`, `onCall`, `drain()` | §23 accountant tests | JVM tests green | Android hooks, storage, UI |
| A2 Classification | classifier + Android resolver | A1 | `usage/UsageClassifier`, `UsageSurfaceResolver`, `DialogsActivity` (public `isSearchShown()`), Local History fragment types later | `classify(...)`, `resolve()` | classifier tests | resolver logs surfaces in debug builds on device (counts only) | storage, UI |
| A3 Signals | wire input/foreground/screen/navigation/playback/calls into `UsageTracker` | A2 | `usage/UsageTracker`, `LaunchActivity` (`onUserInteraction`, `dispatchKeyEvent`), `BottomSheet`, `AlertDialog`, `PhotoViewer`, `SecretMediaViewer`, `StoryViewer`, `ArticleViewer`, `ChatActivityEnterView`, `BaseFragment` (next to `ProtectedChatGate` calls), `ActionBarLayout.onFragmentStackChanged`, `MainTabsActivity.onViewPagerScrollEnd`, `ApplicationLoader` (listener registration) | `UsageTracker.onUserInput()`, `onNavigationChanged()` | existing suites unchanged | device check: debug overlay or log of credited seconds per surface over QA steps 1-8 | persistence, UI |
| A4 Persistence | store, flush, rollup, account removal, messages sent/opens/sessions | A3 | `usage/UsageStore`, `UserConfig.clearConfig`, `SendMessagesHelper` (3 sites), `SecretChatHelper` | `flush()`, `query(range)`, `reset()`, `onAccountRemoved(uid)` | rollup/isolation tests; instrumented `UsageStoreTest` | kill the process mid-use: loss <= 5 min; DB size check | UI |
| A5 Dashboard | `UsageReportActivity`, Settings row, search entry, strings | A4 | `ui/UsageReportActivity`, `SettingsActivity`, `ProfileActivity.SearchAdapter`, `res/values/strings.xml`, icon drawable | — | report-math tests | QA 9-11, light/dark, RTL, TalkBack | Local History surface |
| A6 Integration + hardening (Part 3) | Local History surface (needs LH4 from Part 2), tablet rules, backup rules, performance review | A5, LH4 | `UsageClassifier`, resolver, manifest rules shared with Local History | — | full suite | full QA §24 incl. 12 | — |

### A26. Files / classes expected to change

New: `messenger/usage/UsagePolicy`, `UsageSurface`, `UsageClock`, `UsageAccountant`, `UsageLedger`, `UsageClassifier`,
`UsageSurfaceResolver`, `UsageTracker`, `UsageStore`, `UsageReportMath`; `ui/UsageReportActivity`.

Existing (small hooks): `LaunchActivity`, `ApplicationLoader`, `BaseFragment`, `ActionBarLayout`, `MainTabsActivity`,
`BottomSheet`, `AlertDialog`, `PhotoViewer`, `SecretMediaViewer`, `Stories/StoryViewer`, `ArticleViewer`,
`Components/ChatActivityEnterView`, `DialogsActivity` (getter), `SendMessagesHelper`, `SecretChatHelper`, `UserConfig`,
`SettingsActivity`, `ProfileActivity` (search array only), `res/values/strings.xml`, one drawable, manifest backup rules (shared).

Not changed: `MessagesController`, `MessagesStorage`, `ConnectionsManager`, `ChatActivity` (classification only reads its
public getters).

### A27. Risks and open questions

Product decisions needed:

* **Q1** Idle window 60 s (recommended) or a different value?
* **Q2** Calls count even with the screen off, as their own category (recommended)? Should long muted participation in group
  calls / live streams count the same way?
* **Q3** Music with Telegram in front: no extension beyond the idle window (recommended)?
* **Q4** Dashboard totals across all accounts with per-chat rows tagged by account (recommended) vs per-account dashboards?
* **Q5** Picture-in-picture video over other apps: not counted (recommended)?
* **Q6** Should Saved Messages and Bots be top-level categories or folded into Other?

Technical risks:

* Input from windows without a hook is missed; mitigated by the hook list in §5 and the idle window, verified in QA.
* Tablet multi-pane attribution is a heuristic (§21).
* `MainTabsActivity` and upstream navigation changes can add new containers; the resolver is the single place to update.
* Chart cell internals (`UniversalChartCell`) are upstream code; v1 relies on its "no token, no load" behavior (verified in R§6).

### A28. Acceptance criteria

1. Settings → Activity opens a native-looking screen in light and dark themes with Today / Week / Month, total, comparison,
   chart, categories, Most Used, secondary metrics, empty state and reset.
2. Category times never overlap and sum to the total; each active second is credited once (property test).
3. The 2-minute-scroll / 20-minute-idle scenario reports about 3 minutes.
4. Background, screen off and app switch stop accounting immediately; a connected call counts as Calls.
5. Protected chats count time and show identity only; Local History time appears under Local History.
6. No network request, no background service, no timer, no wake lock is added (code review + QA).
7. Data lives in `no_backup`, survives restart and process death (bounded loss of 5 min), is reset by the user, and is
   anonymized per account on logout.
8. Storage stays within the §12 estimates under the heavy profile.
9. All §23 tests pass, negative controls fail when the protected behavior is removed, existing test suites stay green.


---

## Reference B: Local History Chat specification

Planning name: **Local History Chat** (final product name not chosen). Evidence: [local-history-chat-research.md](local-history-chat-research.md).
Independent of Activity; the only coupling is one surface classification (B30). Phases LH1-LH5 are delivered in Part 2, LH6-LH8 in Part 3.
Part 2 moves the enable switch and "Delete all local history" from LH8 into LH4 (see Part 2).

### B1. Product scope

One local conversation per Telegram account, shown in that account's chat list, that looks like a group. It preserves:

* **edits** made by other people to messages they sent the owner in ordinary 1-to-1 chats (every observed version), and
* **deletions** of such messages (the last locally known version, with its media when it was already on the device).

The original private chat is untouched: no placeholder, no "deleted" marker, no change to Telegram's own edit display.
All data stays on the device.

### B2. Non-goals

* No anti-delete in the original chat, no change to `ChatActivity` or `messages_v2`.
* No groups, supergroups, channels, topics, secret chats, bots, Saved Messages, service accounts, outgoing messages.
* No recovery of media that was never downloaded.
* No notifications for archive events in v1.
* No server objects: no group, no Saved Messages entry, no cloud sync, no backup designed by us.
* No forwarding of preserved content as Telegram forwards (§10).
* No inclusion of archived content in global search.

### B3. Source eligibility

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

### B4. Edit update flow (actual)

R§1: `processUpdates` -> `processUpdateArray` (stage) collects `editingMessages` -> `MS.putMessages(res, dialogId, -2, 0, false,
0, 0)` (storage) -> old row read and deserialized -> `REPLACE INTO messages_v2` -> old media deleted if replaced -> commit.
The UI gets `replaceMessagesObjects` directly from stage; it is not used.

### B5. Deletion update flow (actual)

R§2: `processUpdateArray` `deletedMessages[0]` (ids only) -> storage runnable -> `MS.markMessagesAsDeleted(0, ids, false, true, 0,
0)` (reads rows by `mid IN (...) AND is_channel = 0`, queues file deletion, deletes rows) -> `updateDialogsWithDeletedMessages`.
Push: `PushListenerController` `MESSAGE_DELETED` -> `MC.deleteMessagesByPush(dialogId, ids, channelId)` -> storage, same
`markMessagesAsDeleted`.

### B6. Remote vs local deletion

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

### B7. Capture points

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

### B8. Idempotency

| Event | Unique key | Effect of a replay |
| --- | --- | --- |
| entry (one per source message) | `(source_dialog_id, source_mid)` | upsert, never a second entry |
| revision | `(entry_id, content_hash, edit_date)` | ignored |
| deletion | `entry.deleted_at IS NOT NULL` | second deletion ignored |

`content_hash` = 64-bit hash (e.g. `Utilities.MD5`-derived, or FNV-1a) of the normalized text, serialized entities and media
identity. The update path and the push path for the same deletion converge on the same key. A crash after our commit but before
Telegram's commit replays the update; the old row is still the old one, so the diff and keys are identical.

### B9. Event / revision model

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

### B10. Media preservation

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

### B11. Synthetic-dialog architectures considered

| # | Architecture | Collision | Network | ChatActivity/DB assumptions | Verdict |
| --- | --- | --- | --- | --- | --- |
| 1 | Reserved id inserted as a `TLRPC.Dialog` into `MC.allDialogs` | provable (R§3) | leaks: `dialogsForward` (share targets), read tasks, pinned reorder, folder moves, mute, `getInputPeer` | `MS.putDialogs` persists it in `cache4.db`; every `sortDialogs` consumer sees it | rejected |
| 2 | New local dialog type inside Telegram's model (`TL_dialogLocal`) | provable | same consumers, each needs an `instanceof` guard | dozens of loops over `allDialogs` | rejected: guard surface too large |
| 3 | `ChatActivity` with a synthetic or fake peer | needs `putUser` of a fake user | 20+ peer-keyed requests (R§4), bare-id requests touch real messages | `onFragmentCreate` requires a real peer | rejected |
| 4 | Dedicated fragment reusing `ChatMessageCell`, adapter-level chat-list row | id only in our code and Protected Chats | none by construction | none: Telegram's model never sees the id | **chosen** |
| 5 | Local pseudo-group (`TLRPC.Chat` with fake id) | needs a fake chat id, which is a real-looking negative id | `getInputPeer` -> `inputPeerChat`, a real group's id | `getChat` consumers everywhere | rejected: most dangerous |
| 6 | Saved Messages sub-dialog / server group | — | server-side | — | out of product scope |

### B12. Chosen architecture

**Dedicated `LocalHistoryActivity` (a `BaseFragment`) built on the `ChannelAdminLogActivity` pattern, an adapter-level row in
the chat list, a local-only identity, and a reserved id that exists only for Protected Chats and Activity.**

* Rendering reuses `ChatMessageCell`, `ChatActionCell` (date separators), `PhotoViewer`, `MediaController`, `AvatarDrawable`,
  `BackupImageView`, `BottomSheet`, the chat background (`Theme.getCachedWallpaper`) and action bar.
* Telegram's dialog model, `cache4.db` and the network layer never see the reserved id.
* Data lives in a feature-owned SQLite file per account user id.

### B13. Collision and network safety

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

### B14. Chat-list integration

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

### B15. Rendering strategy

`LocalHistoryActivity`:

* `RecyclerListView` + `LinearLayoutManager` (stack from end) + own adapter; view types: message (`ChatMessageCell`),
  date (`ChatActionCell`), collapsed batch (`ChatActionCell` with a button), unsupported.
* Pages entries from the repository on the feature queue (newest first, 50 per page), builds `MessageObject`s on the feature
  queue (`new MessageObject(account, renderModel, true, true)` needs users in `MC`; see §16), delivers on UI.
* Bubble footer: a `ChatActionCell`-style status line under the bubble or the cell's existing edited label replaced by the
  status text (`MessageObject` field set by the render model; `ChatMessageCell` already draws an "edited" string from
  `isEdited()`; v1 sets the flag and overrides the label text through a small hook `ChatMessageCell.setCustomStatusText`).
* No input field. Action bar: name + subtitle ("12 deleted · 30 edited"), avatar, menu (Search, Clear history, Info).

### B16. Group-style sender rendering

* `messageCell.isChat = true`; `eventId` = entry id so `MessageObject.needDrawAvatar()` is true; `peer_id` = `TL_peerUser`
  (source user), `from_id` = `TL_peerUser` (source user), `dialog_id` = `LOCAL_HISTORY`.
* `pinnedTop/pinnedBottom` as in `ChannelAdminLogActivity.ChatActivityAdapter.onBindViewHolder` (same sender, <= 300 s apart).
* Sender identity is the **current** real `TLRPC.User` from `MC.getUser`, loaded from storage with `MS.getUsersInternal`
  on the feature queue and put with `MC.putUsers(users, true)` when missing. No fake users.
* Decision: **current identity**, not captured historical identity. Telegram resolves names/avatars dynamically everywhere; storing
  names and photos would duplicate personal data. Captured fallback: the entry stores only the display name string at capture
  time (`source_name_snapshot`) used when the user cannot be resolved anymore (account deleted, user not in cache).

### B17. Sender profile navigation

Avatar / name tap -> the real `ProfileActivity` (`user_id` args) of the source user, exactly like the admin log. Long press on
the avatar -> `AvatarPreviewer` as in the admin log. "Show in chat" (entry menu) opens the real private chat at the message id
when the message still exists (edits), otherwise the chat without a jump. Both go through `ProtectedChatGate` like any route,
so a protected source chat asks first.

### B18. Editable local name and photo

* Stored in the feature database `meta` table: `title` (default "Local History" placeholder until named), `photo_path`
  (a JPEG in the feature directory).
* Never uses `MessagesController.changeChatTitle`, `ImageUpdater` upload paths, `TL_messages_editChatTitle`,
  `TL_photos_uploadProfilePhoto` or `ChatEditActivity`.
* UI: `LocalHistoryEditActivity` (a `BaseFragment` with an `EditTextBoldCursor` title field and an avatar row), styled like
  `ChatEditActivity`'s header, using `ImageUpdater` **only** for picking/cropping (`ImageUpdater` with a delegate that receives the
  local `TLRPC.PhotoSize` file; its upload step is not invoked when the delegate does not request upload). Phase 6 gate:
  verify `ImageUpdater` makes no upload request when its delegate handles `didUploadPhoto` locally; if it does, use
  `PhotoAlbumPickerActivity` + `PhotoCropActivity` directly.

### B19. Local profile / info page

`LocalHistoryProfileActivity` (`UniversalFragment`): header (avatar, name), rows: Edit (name/photo), Lock Settings (opens
`ChatLockSettingsActivity` with the local id when `ProtectedChats.isLockSettingsAvailable`), Saved media size + Delete saved
media, Clear history, explanation text (what is archived, other-device ambiguity, stays on this device). Not `ProfileActivity`:
that class is built around real peers (full user/chat loads, shared media, stories).

### B20. Search

* In-chat search (action bar search field): `LIKE` over `entry.search_text` (lowercased normalized text of all revisions),
  results scroll to the entry. Respects the chat's lock (the activity is gated).
* Chat-list search: the local chat appears when its **name** matches (a `DialogsSearchAdapter` local hit), masked like protected
  rows when protected. Archived message content is **never** in global message search (privacy; `DialogsSearchAdapter`
  message results come from the server and `cache4.db` anyway).

### B21. Unread / read model

* `meta.last_read_entry_seq`; unread = entries with `seq > last_read_entry_seq`. Opening the chat marks all read locally.
* Badge style is muted; it does **not** count toward `MC.unreadUnmutedDialogs`, folder counters, the app icon badge, or
  "mark all as read" in Telegram.

### B22. Media viewer

`PhotoViewer.getInstance().setParentActivity(this); openPhoto(messageObject, ..., provider)` with a provider over the local
entries (as the admin log does). `eventId != 0` disables shared-media searches (`needSearchImageInArr = false`). Video and voice
play from `attachPath` via `MediaController.playMessage`; `MediaController.isSamePlayingMessage` compares `eventId`. Saving
media to the gallery uses `MediaController.saveFile` on the local path. Gate: PhotoViewer must not show "Show in chat", "Share"
to Telegram chats, or "Delete" for these objects (its menu reads `messageObject` flags and the provider; Phase 5 verifies each
menu item).

### B23. Clear / delete local history

* Per entry: long press -> "Delete from Local History" (also deletes its preserved files).
* All: "Clear history" (confirm dialog) deletes every entry, revision, file, and resets unread. Atomic in one transaction;
  files deleted after commit on the feature queue; holds for in-flight copies are released and their copies discarded.
* Disabling the feature: capture stops immediately; data is kept until the user chooses "Delete all local history" in the
  same screen (offered in the disable confirmation).
* Interplay with capture: deletion and capture are serialized on the database; an event that arrives during "Clear" lands
  after it (new entry), which is correct.

### B24. Multi-account behavior

**One Local History Chat per account** (keyed by `clientUserId`, never by slot):

* database `getNoBackupFilesDir()/local_history/<userId>/history.db`, media in `.../<userId>/media/`.
* `LocalHistoryCapture.getInstance(account)` resolves the user id at capture time; capture is skipped when it is 0.
* The row appears only in that account's `DialogsActivity` (adapter uses `currentAccount`).
* Account removal: `UserConfig.clearConfig()` already calls `ProtectedChats.onAccountRemoved(getClientUserId())`; add
  `LocalHistory.onAccountRemoved(userId)` right next to it: closes the database, deletes the user's directory on the feature
  queue. Default: delete (privacy, matches Telegram wiping `cache4.db` on logout). Logging back in starts empty.

### B25. Storage schema and indexes

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

### B26. Migration strategy

* `meta.schema_version` with ordered migrations in `LocalHistoryDatabase.migrate()`; never destructive without a copy.
* Telegram TL layer changes: blobs are deserialized with `TLRPC.Message.TLdeserialize`, which keeps old constructors (cache4.db
  depends on the same). If a future upstream merge drops a constructor, the `revision.text` column keeps the entry readable
  as plain text. Test `RevisionBlobRoundTripTest` pins current constructors.
* `cache4.db` migrations (`MS.LAST_DB_VERSION`) do not affect the feature database.

### B27. Android backup and privacy

* Location: `getNoBackupFilesDir()` (excluded from Auto Backup, key/value and device transfer by platform contract).
* Today's manifest: custom key/value `BackupAgent` (only `saved_tokens*` preferences), `allowBackup="true"`, no
  `dataExtractionRules` (R§7). Recommendation: keep the feature in `no_backup` **and** add `android:dataExtractionRules` /
  `android:fullBackupContent` that exclude `no_backup/local_history` explicitly, so a future switch to Auto Backup cannot
  include it.
* Never sent over the network, never put in `cache4.db`, never in logs (`FileLog` lines carry ids and counts only, never text).
* Screenshots: the existing credential-level FLAG_SECURE rules apply; no new rule.

### B28. Protected Chats integration

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

### B29. Lifecycle / navigation integration

* Feature start: `LocalHistory.init(account)` lazily on first capture or first chat-list build after the account is activated
  (`UserConfig.isClientActivated`), opening the database on the feature queue (`DispatchQueue("localHistoryQueue")`).
* Capture writes happen on the storage thread (synchronous, short transactions) through the same `SQLiteDatabase` handle,
  guarded by the database's own lock (`synchronized` wrapper `LocalHistoryDatabase`), so capture never waits for UI work.
  UI reads run on the feature queue.
* Process death: capture is committed before Telegram's own commit (§7); copies resume from `PENDING_COPY` (§10).
* Navigation: `LocalHistoryActivity` is presented through `presentFragment` (gate applies); Back is ordinary.

### B30. Activity integration

* Activity classifies `LocalHistoryActivity` (and its profile/edit screens) as surface `LOCAL_HISTORY`, display category
  "Local History", per-dialog key `LocalDialogIds.LOCAL_HISTORY` (see Reference A §7-9).
* Not counted as a private chat. The Activity per-chat list shows it with its local name/avatar via the same identity resolver.
* Protection does not stop accounting; Activity shows identity and time only, never content.

### B31. Performance

* Capture fast path when the feature is off: one static boolean read. When on and the dialog is not a user dialog: one compare.
* Eligible edit: diff + one small transaction (< 1 ms typical) on storage.
* Deletion batch: one indexed SELECT on rows Telegram reads anyway, one transaction.
* No timers, no services, no wake locks; copies only after a capture.
* UI: paged loads, `MessageObject` creation off the UI thread, no full-table scans outside search.

### B32. Race conditions

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

### B33. Security invariants

1. The reserved id never appears in `MC.allDialogs`, `dialogs_dict`, `cache4.db`, or any TL request (G1-G4).
2. Archived `MessageObject`s are never outgoing, unread, view-counted, reactable, or downloadable from the network (G5, §10).
3. No server API is used to edit the local name or photo (§18).
4. Archived content leaves the feature database only to the screen, to the clipboard, or to the gallery on explicit user action.
5. Only remote entry points capture (§6); a local deletion on this device can never create an entry.
6. View-once and auto-delete expiries are never archived (§3).
7. Data is per account user id and removed with the account.
8. Protected Chats state for the local id follows the same rules as any dialog.

### B34. Unit-test architecture

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

### B35. Integration tests

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

### B36. Device QA

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

### B37. Implementation phases

Each phase: build the debug flavor, run `./gradlew :TMessagesProj:testDebugUnitTest` (or the repo's existing unit-test task),
commit, next phase. No phase triggers CI workflows by itself beyond the normal push policy chosen at implementation time.

| Phase | Goal | Depends | Files | APIs | Tests | Gate | Not yet |
| --- | --- | --- | --- | --- | --- | --- | --- |
| LH1 Identity + guards | reserved id, classifier, network guards | — | `messenger/localhistory/LocalDialogIds.java`; `MC.getInputPeer/getInputUser/getInputChannel`; `SendMessagesHelper.sendMessage` | `LocalDialogIds.LOCAL_HISTORY`, `isLocal`, `guardInputPeer` | `LocalDialogIdsTest`, `LocalHistoryNetworkGuardTest` | tests green; no behavior change for real ids | storage, capture, UI |
| LH2 Storage + model | feature DB, schema, repository, eligibility, diff | LH1 | `localhistory/LocalHistoryDatabase.java`, `LocalHistoryRepository.java`, `LocalHistoryEligibility.java`, `LocalHistoryDiff.java`, `LocalHistory.java` (per-account entry point, `onAccountRemoved`), `UserConfig.clearConfig` | repository interface (`recordEdit`, `recordDeletion`, `pageFeed`, `clear`, `deleteEntry`, `summary`) | eligibility, diff, ledger tests | DB created in `no_backup`; account removal deletes it | capture hooks, UI |
| LH3 Capture | edit and deletion capture | LH2 | `MS.putMessages` (+`fromEditUpdate` overload), `MS.getMessagesForArchiveSync`, `MC.processUpdateArray` (edit call site, deletion block), `MC.deleteMessagesByPush`, `localhistory/LocalHistoryCapture.java` | `onEditStored`, `onRemoteDeleteBeforeStorage` | ledger tests through capture entry points with fixtures; negative controls | device QA 1-4, 7-8 via a temporary debug dump (`FileLog` counts only) | media copy, UI |
| LH4 Chat list row + read-only feed | row, `LocalHistoryActivity` with text rendering, date separators, sender grouping, avatars, unread | LH3 | `DialogsAdapter`, `DialogCell` (local mode), `DialogsActivity.onItemClick`, `NotificationCenter.localHistoryChanged`, `ui/LocalHistoryActivity.java`, `localhistory/LocalHistoryRenderModel.java`, `PrivacySettingsActivity` (enable switch + Delete all local history, moved here from LH8 so Part 2 is safe to test) | render model | render-model tests (flags cleared, ids, no remote locations) | QA 13 (no requests), row positions, theme switch | media, profile, lock |
| LH5 Media | holds, copier, budget, viewer, save to gallery | LH4 | `FL.deleteFiles`, `localhistory/LocalHistoryMediaHolds.java`, `LocalHistoryMediaCopier.java`, viewer provider | `hold/isHeld/release` | media state tests | QA 5-6 | profile, lock |
| LH6 Profile + identity | info page, edit name/photo, clear/delete, revision sheet, in-chat search | LH5 | `ui/LocalHistoryProfileActivity.java`, `ui/LocalHistoryEditActivity.java`, `ui/Components/LocalHistoryRevisionsSheet.java` | — | identity storage test | QA 9 | lock |
| LH7 Protected Chats | §28 items 1-9 | LH6 | `ProtectedChats`, `ProtectedChatGate`, `ProtectedDialogIdentity` (new), `ProtectedChatsListActivity`, `ChatLockSettingsActivity`, `DialogCell`, `DialogsSearchAdapter` | `ProtectedDialogIdentity.resolve` | `ProtectedLocalIdentityTest`, existing Protected Chats suites unchanged and green | QA 10 | — |
| LH8 Hardening | hide-row option, backup rules, logging review, bulk entry UI (the enable switch is already in LH4) | LH7 | `AndroidManifest.xml` + `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` | — | full suite | full device QA §36 | — |

### B38. Files / classes expected to change

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

### B39. Risks and open questions

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

### B40. Acceptance criteria

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
