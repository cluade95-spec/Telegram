# Activity: implementation plan

Product name: **Activity** (Settings → Activity). Baseline `master` `055a0c3b`. Evidence: [activity-research.md](activity-research.md)
(cited as R§n). Paths relative to `TMessagesProj/src/main/java/org/telegram/`. Internal code uses the prefix `Usage` to avoid
confusion with Android activities (`UsageTracker`, `UsageReportActivity`); the user-facing name is Activity.

Independent of the Local History Chat; the only coupling is one surface (§7).

---

## 1. Product scope

A local report answering "where did my time inside Telegram actually go?":

* total **active** time for Today / This week / This month, with the previous period for comparison;
* a non-overlapping breakdown by surface category;
* most-used chats;
* a small set of secondary metrics (§16).

Everything is computed and stored on the device.

## 2. Non-goals

Usage limits, focus mode, blocking, breaks, doomscroll warnings, parental controls, AI analysis, cloud analytics, server
reporting, Telegram API changes, uploads, background services, periodic wakeups, per-message tracking, content capture.

## 3. Existing architecture discovered

* No app-wide last-input timestamp exists; `LaunchActivity.onUserInteraction` is the cheapest central input hook but misses
  separate windows and IME typing (R§1).
* Foreground: `ForegroundDetector` (app-wide refcount). Background boundary: activity stop, not pause (PR #9 finding).
  Screen: `ScreenReceiver` / `NotificationCenter.screenStateChanged` (R§2).
* Surface: no single "visible fragment" notion; main tabs live in `MainTabsActivity` outside the stack; tablets show up to three
  layouts; viewers are separate windows (R§3).
* Calls: `VoIPService` state and `didStartedCall` / `didEndCall` (R§3).
* Messages sent: existing confirmed-send counting points in `SendMessagesHelper` (R§5).
* UI: `UniversalFragment` + `UItem.asChart` + `ui/Charts` work without network (R§6).

## 4. Active-time definition

A second counts as **active** when all of these hold:

1. Telegram is in the foreground (`ForegroundDetector.isForeground()`), **and**
2. the screen is on (`ApplicationLoader.isScreenOn`), **and**
3. either the user interacted within the idle window (§5), or an **engaged passive activity** is running (§6), or a call is
   connected (§6).

Each active second is credited to exactly one **surface key** `(accountUserId, surface, dialogKey)`: the primary surface at
that instant (§8). Calls are exclusive: while a call is connected, its seconds go to Calls and nothing else.

## 5. Idle detection

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

## 6. Passive media and call rules

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

## 7. Surface taxonomy

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

## 8. Surface-classification architecture

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

## 9. Per-dialog accounting

* Key: `(accountUserId, dialogId)` with the dialog id Telegram uses (users > 0, chats < 0, secret chats encrypted id, local
  history reserved id). No names or avatars stored.
* Display resolves names dynamically: `MessagesController.getUser/getChat/getEncryptedChat` (loading from storage if needed);
  the local history id resolves through `ProtectedDialogIdentity` (local-history-chat-plan §28).
* Migrated groups: rows keyed by the old basic-group id are merged into the channel's row at display time when
  `chat.migrated_to` is known, and rewritten at the next rollup.
* Topics: parent dialog only in v1.
* Chat no longer exists / account deleted / secret chat gone: the row stays with "Deleted chat" / "Deleted account" and a
  placeholder avatar; time is never dropped from category totals.
* Protected chats: time is always counted. The Activity UI shows identity and time only (Protected Chats already never hides
  identity); tapping a row opens the chat through `ProtectedChatGate`.

## 10. Multi-account behavior

* Every credit carries the account's `clientUserId` at that moment (`UserConfig.selectedAccount`).
* The dashboard shows **all accounts combined** for totals, categories and charts (the question is about time in the app), and
  Most Used chats from all accounts, with a small account avatar on rows when more than one account is logged in.
* `activeAccountChanged` closes the current segment.
* Account removal (`UserConfig.clearConfig`, next to `ProtectedChats.onAccountRemoved`): per-dialog rows of that user id are
  re-keyed to `dialog_id = 0` (time stays in totals and categories; chat identities are dropped).

## 11. Storage schema

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

## 12. Aggregation and retention

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

## 13. Write / flush strategy

* Accounting is in memory: `UsageAccountant` books credits into a `UsageLedger` (`HashMap<BucketKey, Integer>` seconds).
* Flush (one transaction of upserts on `usageQueue`) when: the app goes to the background (`ForegroundDetector.onBecameBackground`),
  the screen turns off, the dashboard opens, the account changes, or unflushed time reaches **5 minutes** (checked at accounting
  events; no timer).
* Crash/process-death loss is bounded by 5 minutes of foreground time; background flushes make loss after the app is left zero.
* Clock: durations use `SystemClock.elapsedRealtime()`; the local day/hour of a credit uses `System.currentTimeMillis()` and the
  default time zone **at booking time**. A wall-clock jump or time-zone change only moves where future credits are booked; an
  interval is never double counted or negative because durations are monotonic. A credit spanning midnight is split.

## 14. Performance and battery budget

Hard constraints for implementation:

* Per input event: one `long` store and one comparison on the UI thread. No allocation, no locking, no logging.
* Per navigation event: mark dirty; at most one resolver run per UI frame (posted runnable, coalesced).
* No timers, no `Handler` loops, no services, no alarms, no wake locks, no `JobScheduler`.
* Disk writes only at flush boundaries (§13): typically a few per hour of use.
* Dashboard queries on `usageQueue`, each bounded by the period's rows (< 10 ms on the heavy estimate).
* No per-frame or per-scroll work; no reflection on hot paths.

## 15. Dashboard UX

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

## 16. Charts and metrics

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

## 17. Settings integration

* `SettingsActivity.fillItems`: one new `SettingCell.Factory.of(24, ..., R.drawable.<activity icon>, "Activity", "<today total>")`
  placed after Data and Storage; `onClick` case 24 -> `presentSettingFragment(new UsageReportActivity())`.
* Settings search: `ProfileActivity.SearchAdapter.onCreateSearchArray` entry id 1000 ("Activity") with `tg://settings/activity`
  if `LinkManager` routes settings links (Phase A5 checks).
* Legacy own-profile rows in `ProfileActivity` are not extended (the new `SettingsActivity` is the settings home in this build).

## 18. Theme and accessibility

* All colors from `Theme` keys (`windowBackgroundWhite`, `windowBackgroundWhiteBlackText`, `windowBackgroundWhiteGrayText`,
  `statisticChartLine_*`); no hard-coded colors; observe `NotificationCenter.didSetNewTheme` through `UniversalFragment`.
* Time formatting via `LocaleController` (plural-aware strings, `formatPluralString`); 24h/12h from `LocaleController.is24HourFormat`.
* Content descriptions: summary row reads "Today, 3 hours 42 minutes"; category rows read name, time, percent; charts have a
  text summary row for TalkBack.
* RTL: `LayoutHelper` + `LocaleController.isRTL` like other settings screens.

## 19. Lifecycle handling

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

## 20. Process-death handling

In-memory ledger lost; at most 5 minutes of foreground time (§13). On start, no recovery is attempted; there is no "open
segment" on disk to repair. The database is always consistent (transactions).

## 21. Edge cases

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

## 22. Testing architecture

Pure core in `messenger/usage/`: `UsagePolicy`, `UsageSurface`, `UsageClassifier`, `UsageAccountant`, `UsageLedger`,
`UsageClock` (interface: `elapsed()`, `wallMillis()`, `zone()`), `UsageReportMath` (period ranges, comparisons, percentages).
Android adapters (`UsageTracker`, `UsageSurfaceResolver`, `UsageStore`) stay thin. Tests use a `FakeClock` and drive the
accountant with event scripts.

## 23. Unit tests (JVM, `TMessagesProj/src/test/java/org/telegram/messenger/usage/`)

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

## 24. Integration / device tests

* Instrumented (`TMessagesProj_AppTests/src/androidTest`): `UsageStoreTest` against a real sqlite file (upserts, rollup,
  reset, concurrent flush while querying).
* Device QA script: (1) scroll list 2 min, leave phone untouched with screen on 5 min -> Chat List about 3 min; (2) read a
  channel 5 min with occasional scrolls -> Channels; (3) open a photo then video -> Media; (4) stories 2 min -> Stories;
  (5) 3-minute call with screen off at the ear -> Calls 3 min, nothing else in that period; (6) switch account mid-chat ->
  split per account; (7) background for 10 min -> nothing; (8) tablet: list + chat -> chat; (9) reset clears; (10) theme
  switch on the dashboard; (11) protected chat time appears without content; (12) Local History Chat time appears under
  Local History.

## 25. Implementation phases

| Phase | Goal | Depends | Files | APIs | Tests | Gate | Not yet |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A1 Core accounting | pure accountant, policy, clock, ledger, surfaces enum | — | `messenger/usage/UsagePolicy`, `UsageSurface`, `UsageClock`, `UsageAccountant`, `UsageLedger` | `onInput`, `onForeground/Background`, `onScreen`, `onSurface`, `onPassive`, `onCall`, `drain()` | §23 accountant tests | JVM tests green | Android hooks, storage, UI |
| A2 Classification | classifier + Android resolver | A1 | `usage/UsageClassifier`, `UsageSurfaceResolver`, `DialogsActivity` (public `isSearchShown()`), Local History fragment types later | `classify(...)`, `resolve()` | classifier tests | resolver logs surfaces in debug builds on device (counts only) | storage, UI |
| A3 Signals | wire input/foreground/screen/navigation/playback/calls into `UsageTracker` | A2 | `usage/UsageTracker`, `LaunchActivity` (`onUserInteraction`, `dispatchKeyEvent`), `BottomSheet`, `AlertDialog`, `PhotoViewer`, `SecretMediaViewer`, `StoryViewer`, `ArticleViewer`, `ChatActivityEnterView`, `BaseFragment` (next to `ProtectedChatGate` calls), `ActionBarLayout.onFragmentStackChanged`, `MainTabsActivity.onViewPagerScrollEnd`, `ApplicationLoader` (listener registration) | `UsageTracker.onUserInput()`, `onNavigationChanged()` | existing suites unchanged | device check: debug overlay or log of credited seconds per surface over QA steps 1-8 | persistence, UI |
| A4 Persistence | store, flush, rollup, account removal, messages sent/opens/sessions | A3 | `usage/UsageStore`, `UserConfig.clearConfig`, `SendMessagesHelper` (3 sites), `SecretChatHelper` | `flush()`, `query(range)`, `reset()`, `onAccountRemoved(uid)` | rollup/isolation tests; instrumented `UsageStoreTest` | kill the process mid-use: loss <= 5 min; DB size check | UI |
| A5 Dashboard | `UsageReportActivity`, Settings row, search entry, strings | A4 | `ui/UsageReportActivity`, `SettingsActivity`, `ProfileActivity.SearchAdapter`, `res/values/strings.xml`, icon drawable | — | report-math tests | QA 9-11, light/dark, RTL, TalkBack | Local History surface |
| A6 Integration + hardening | Local History surface (once LH4 exists), tablet rules, backup rules, performance review | A5 (+ LH4) | `UsageClassifier`, resolver, manifest rules shared with Local History | — | full suite | full QA §24 incl. 12 | — |

## 26. Files / classes expected to change

New: `messenger/usage/UsagePolicy`, `UsageSurface`, `UsageClock`, `UsageAccountant`, `UsageLedger`, `UsageClassifier`,
`UsageSurfaceResolver`, `UsageTracker`, `UsageStore`, `UsageReportMath`; `ui/UsageReportActivity`.

Existing (small hooks): `LaunchActivity`, `ApplicationLoader`, `BaseFragment`, `ActionBarLayout`, `MainTabsActivity`,
`BottomSheet`, `AlertDialog`, `PhotoViewer`, `SecretMediaViewer`, `Stories/StoryViewer`, `ArticleViewer`,
`Components/ChatActivityEnterView`, `DialogsActivity` (getter), `SendMessagesHelper`, `SecretChatHelper`, `UserConfig`,
`SettingsActivity`, `ProfileActivity` (search array only), `res/values/strings.xml`, one drawable, manifest backup rules (shared).

Not changed: `MessagesController`, `MessagesStorage`, `ConnectionsManager`, `ChatActivity` (classification only reads its
public getters).

## 27. Risks and open questions

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

## 28. Acceptance criteria

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
