# Activity: source research

Baseline: `master` `055a0c3b`. Paths relative to `TMessagesProj/src/main/java/org/telegram/`. Line numbers locate code; method
names are the contract.

## 1. Input signals

| Signal | Where | Coverage | Gaps |
| --- | --- | --- | --- |
| `LaunchActivity.onUserInteraction()` (~L6734, today only resets `voipLaunchedInBackground`) | Activity window | every touch/key dispatched to the main window: chats, lists, settings, `ActionBarLayout` swipes, in-window viewers | not called for other windows |
| `LaunchActivity.dispatchKeyEvent` (~L8459) | Activity window | hardware/volume keys | — |
| `BottomSheet.dispatchTouchEvent` (`ui/ActionBar/BottomSheet.java` ~L1946) | every `BottomSheet` window, including `showAsSheet` fragments and `ProtectedChatAuthSheet` | sheets | — |
| `AlertDialog` (`ui/ActionBar/AlertDialog.java`) | Dialog window | no override today | needs one |
| Window-based viewers | `PhotoViewer` (`WindowManager.addView(windowView)`), `SecretMediaViewer`, `StoryViewer` when not attached to a fragment (`globalInstances`), `ArticleViewer` window mode | their own window views | need a hook in each root view's `dispatchTouchEvent` |
| Soft keyboard typing | IME window: **no touch reaches the app** | `ChatActivityEnterView` text watcher (the place that triggers `needSendTyping`) and search fields | needs explicit hooks |
| Precedent | `ui/Components/chat/ChatActivityMessageMetricsView.java` keeps `lastUserActivityTime` with a 15 s `USER_ACTIVE_TIME`, fed from `ChatActivity`'s content `dispatchTouchEvent` | per-message read metrics sent to the server; reuse the idea, not the class | — |

There is no existing app-wide "last user activity" timestamp.

## 2. Foreground, background, screen

* `ui/Components/ForegroundDetector.java`: `ActivityLifecycleCallbacks` with a started-activity refcount, `isForeground()`,
  `Listener.onBecameForeground/onBecameBackground` (created in `ApplicationLoader`). Covers `LaunchActivity`, `BubbleActivity`,
  `ExternalActionActivity`, `PopupNotificationActivity`.
* `LaunchActivity.onPause` sets `ApplicationLoader.mainInterfacePaused`; `onStop` sets `mainInterfaceStopped` and calls
  `ProtectedChats.onAppPaused()`. PR #9 established (docs/protected-chats-audit.md) that pause without stop is a system
  permission dialog or split-screen focus loss, not background.
* `messenger/ScreenReceiver.java` sets `ApplicationLoader.isScreenOn` and posts global `NotificationCenter.screenStateChanged`.
* `ConnectionsManager.setAppPaused` / `SharedConfig.lastPauseTime` are network/app-lock specific; not suitable.

## 3. Visible surface

* `INavigationLayout` / `ActionBarLayout`: `presentFragment`, `closeLastFragment`, `addFragmentToStack`,
  `removeFragmentFromStack` all end in the private `ActionBarLayout.onFragmentStackChanged(String)` (~L2358), which runs one
  `Runnable` set with `setFragmentStackChangedListener` (installed only on the main layout, `LaunchActivity` ~L544, for system
  bar colors and `StoryViewer.updatePlayingMode`).
* `BaseFragment.onResume/onPause/onBecomeFullyVisible/onBecomeFullyHidden/onFragmentDestroy` already call
  `ProtectedChatGate` hooks (PR #9); the same call sites can notify a tracker.
* `LaunchActivity.getLastFragment()` / `getSafeLastFragment()` check `BubbleActivity`, the top of `sheetFragmentsStack`, then
  `getActionBarLayout()`; `actionBarLayout.getLastFragmentIncludeMainTabs()` unwraps `MainTabsActivity`.
* **`MainTabsActivity`** (`ViewPagerActivity`) hosts Chats / Contacts / Calls-or-Settings / own Profile as tabs that are **not**
  in an `ActionBarLayout` stack; `ViewPagerActivity.getCurrentVisibleFragment()` and `onViewPagerScrollEnd()` give the settled
  tab. During a swipe two tabs are resumed.
* Tablet: `actionBarLayout` (left), `rightActionBarLayout` (right pane), `layersActionBarLayout` (modal layer) can be on screen
  together; there is no helper for "the visible top" across them.
* `RightSlidingDialogContainer` (topics side panel in `DialogsActivity`) resumes its fragment in preview mode.
* `DialogsActivity`: `folderId`, `isArchive()`, `communityId`, `onlySelect`, `initialDialogsType`; search state in the private
  `searchIsShowed` (no getter).
* Overlays: `PhotoViewer.getInstance().isVisible()` (and `PipVideoOverlay.isVisible()`), `SecretMediaViewer.isVisible()`,
  `ArticleViewer.isVisible()`, `StoryViewer.isShown()` (fragment-attached via `BaseFragment.getLastStoryViewer()`, or
  `StoryViewer.globalInstances`).
* Calls: `VoIPService.getSharedInstance()`, `getCallState()` (`STATE_ESTABLISHED`), `getCallDuration()`, `groupCall`;
  `NotificationCenter.didStartedCall` / `didEndCall` (global).
* Playback: `MediaController.getPlayingMessageObject()`, `isMessagePaused()`, `messagePlayingDidStart` /
  `messagePlayingPlayStateChanged` / `messagePlayingDidReset`.

## 4. ChatActivity identity

`ChatActivity` fields/getters: `getDialogId()`, `getCurrentUser()`, `getCurrentChat()`, `getCurrentEncryptedChat()`,
`getChatMode()` (`MODE_DEFAULT 0, SCHEDULED 1, PINNED 2, SAVED 3, QUICK_REPLIES 5, EDIT_BUSINESS_LINK 6, SEARCH 7,
SUGGESTIONS 8, WELCOME_MESSAGES 9`), `getTopicId()`, `isTopic`, `isComments`, `getThreadMessage()`. Helpers:
`UserObject.isUserSelf`, `isBot`, `isService`, `ChatObject.isChannel`, `isMegagroup`, `isChannelAndNotMegaGroup`, `isForum`,
`isMonoForum`, `DialogObject.isEncryptedDialog`.

## 5. Messages sent

`SendMessagesHelper` already counts confirmed sends for data-usage statistics:
`getStatsController().incrementSentItemsCount(network, StatsController.TYPE_MESSAGES, n)` at ~L2741 (forward batch), ~L7772
(album item), ~L8303 (single message), each next to `NotificationCenter.messageReceivedByServer`. Secret chat sends
(`SecretChatHelper` ~L765) do not increment it. Counting next to these calls gives server-confirmed, non-duplicated counts.

## 6. Reusable UI

* Charts: `ui/Charts/*` (`StackBarChartView`, `BarChartView`, `LinearChartView`, `PieChartView`), data from JSON via
  `StatisticActivity.createChartData(JSONObject, graphType, false)` and `StatisticActivity.ChartViewData` (public constructor),
  inserted into a `UniversalAdapter` list with `UItem.asChart(StatisticActivity.VIEW_TYPE_STACKBAR, id, viewData)`. Local
  precedent: `ui/Components/poll/sheets/PollStatisticsBottomSheet`. `UniversalChartCell.loadData` returns early when
  `stats_dc < 0`, so a locally built chart triggers no request. Colors come from `statisticChartLine_*` theme keys.
* `CacheChart` (donut) prints file sizes in its center; not reusable for durations without a hook.
* Settings lists: `UniversalFragment` (`getTitle`, `fillItems`, `onClick`), `UItem` factories, classic cells.
* Settings entry: the new `ui/SettingsActivity.java` (`fillItems` ~L617 with `SettingCell.Factory.of(id, colorTop,
  colorBottom, icon, title, subtitle)`, ids 1-23 used, `onClick` ~L772 switch, `presentSettingFragment` handles tablets).
  Settings search lives in `ProfileActivity.SearchAdapter.onCreateSearchArray` (ids 1000+ free).
* Storage precedent: `messenger/StatsController.java` (per account `stats2.dat`, `RandomAccessFile`, throttled saves on its own
  `DispatchQueue`).

## 7. Accounts and storage

`UserConfig.MAX_ACCOUNT_COUNT = 4`, `selectedAccount`, `LaunchActivity.switchToAccount` posts global
`NotificationCenter.activeAccountChanged`. Logout: `MessagesController.performLogout` -> `UserConfig.clearConfig()` (which already
calls `ProtectedChats.onAccountRemoved(clientUserId)`) -> `appDidLogout` -> `MS.cleanup(false)`. Slots are reused, so key by
`clientUserId`. Backup: see local-history-chat-research.md §7 (custom key/value agent; `no_backup` recommended).

## 8. Answers to the plan's source questions (Activity part)

16. **Best central input source:** `LaunchActivity.onUserInteraction` plus a handful of window hooks (BottomSheet, AlertDialog,
    PhotoViewer, SecretMediaViewer, StoryViewer, ArticleViewer) and text-change hooks for typing; there is no single existing one.
17. **Best central surface source:** pull, not push: on any navigation-ish event, resolve the surface from
    `LaunchActivity` state (overlays, sheet stack, layers/right/main layouts, `MainTabsActivity` current tab) in one resolver.
    Events come from `BaseFragment.onResume/onPause`, `ActionBarLayout.onFragmentStackChanged`, `onViewPagerScrollEnd`, and viewer
    open/close.
18. **Sheets/dialogs/viewers:** `BottomSheet`/dialogs leave the underlying surface in place (user is still in that chat);
    `showAsSheet` fragments are classified as fragments; window viewers take priority over fragments while visible.
19. **Charts:** `ui/Charts` via `StatisticActivity.ChartViewData` + `UItem.asChart`.
20. **Account switches / process recreation:** `activeAccountChanged` ends the current segment; in-memory accounting is lost on
    process death after the last flush (bounded, A13 of docs/implementation-plan.md).
