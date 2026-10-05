# Activity A1–A5 implementation and source audit

Completed on `codex/activity-implementation`, 2026-10-05. A1–A3 were retained without amendment.
The interrupted archive was inspected, its tracked patch checked before applying, and only the intended A4
files restored. The committed plan corrections were preserved. A4 and A5 were reviewed, tested and
committed separately. The final audit commit contains the small fixes and this report.

## Commits

| SHA | Change |
| --- | --- |
| `fdd2d407dd804eae722391004cd0ad87ff809be3` | Activity A1: add accounting core |
| `0a5e817df06b9af35b37988746ef32c8634f55a6` | Activity plan: correct settings and call integration assumptions |
| `7b393c416128b670970f965b8da37ba180c69530` | Activity A2: add surface classification |
| `5dc203a3c0ae0821eec6d01f1d9280015925ff2a` | Activity A3: wire activity signals |
| `fcc0e783c91309d2283d6c11d96e96cb9c6d58b9` | Activity plan: accept event-checked persistence loss |
| `aabd6402fe3b266c8ce5401ffaf95f08b17f3871` | Activity plan: count verified deliveries and deduplicate sends |
| `526876d31ab77884405b22c3a6b7d6b4d806d922` | Activity A4: persist earned usage and verified send metrics |
| `8b26039525352b1259475cf932fb9f12a5fce722` | Activity A5: add local Settings dashboard and report math |

The final audit commit's SHA is reported with delivery and can be read from `git log`; it cannot be
embedded in its own committed content. No prior commit was amended.

## Architecture and exact accounting

`UsageTracker` serializes state/accounting on the UI thread; the pure accountant, classifier, ledger,
metrics and report math are independently testable. Duration uses `elapsedRealtime`, never wall-clock
differences. At each event the old owner is settled before state changes, and a cursor prevents replay
or double credit. Each millisecond has at most one owner `(account user ID, surface, dialog)`.

Normal time requires foreground plus screen-on and input within 60 seconds, or engaged passive playback.
Entering foreground grants the initial idle window; navigation and screen-on alone do not refresh input.
A late event credits only the earned prefix of the idle window. Passive video/viewers, stories and voice/
round messages extend activity while foreground and screen-on. Music, muted in-chat autoplay and GIFs do
not extend idle. PiP excludes normal activity. Permission pauses do not themselves mean app background;
configuration changes preserve continuity. Connected calls use `STATE_ESTABLISHED`, exclusively own
Calls, and continue in background and with the screen off. Dialing/ringing/connecting do not count as Calls.

Input hooks cover the main window, bubble, sheets/dialogs, viewer windows and IME InputConnection edits.
They capture timestamps, never typed text. Input buffering is event-driven; navigation resolution is
coalesced. The resolver honors overlays, tablet pane priorities, chat modes, explicit Settings origins,
profiles/search and account IDs. Topics credit their parent group. Stable reserved Local History IDs do
not implement Local History and were not expanded in this work.

Calendar bucket labels derive from the wall/zone snapshot at a segment boundary; elapsed time remains
monotonic across wall changes. Hour/day splitting conserves credited duration, including DST; repeated
local hours share their hour bin. Bucket milliseconds become whole seconds only on persistence.

## Persistence, flush, rollup and reset

The feature owns `getNoBackupFilesDir()/usage/usage.db`, its WAL and the `usageQueue` DispatchQueue.
It does not use `cache4.db`. Meta, bucket, daily and opaque sent-token tables store aggregates, stable
account/dialog IDs and salted SHA-256 confirmation digests; no message content or raw per-message IDs
are stored. Bucket/daily keys use WITHOUT ROWID and account/dialog/day indexing where needed.

Flushes settle earned time at background, screen-off, account switches/logout, call/passive transitions,
dashboard reads and verified send boundaries. The five-minute credited-time threshold is checked only
when events arrive. There are no timers, polling, delayed periodic Handler callbacks, WorkManager,
AlarmManager, JobScheduler or Activity background services. Album/forward confirmations coalesce their
flush into an event-posted UI runnable. Touch/scroll hooks perform no database I/O.

A transaction atomically writes earned seconds, secondary metrics, dedup tokens, anonymization and
rollup. BEGIN IMMEDIATE/COMMIT/ROLLBACK uses the native wrapper's SQL statements because its transaction
helper has no rollback API. Errors retain the unsaved batch and retry at the next real event, with no
scheduled retry. Subsecond remainders are retained in queue memory. There is no persisted open interval,
future deadline, or recovery that claims unearned time.

Rollup runs at most once per local calendar day on a real flush/query. Rows strictly older than 90 days
merge to daily hour=-1 buckets. Dialog/day totals strictly below 60 seconds and older than two calendar
years fold into dialog=0 while preserving accounts/categories/totals. Known cached group-to-supergroup
migrations merge keys without duplication, opportunistically once daily; report math also aliases known
migrations. Old category/day totals remain available.

Logout settles the original user ID before UserConfig is cleared, anonymizes that account's dialog keys,
retains aggregate time/metrics and leaves other accounts untouched. Account slots are not persistent
identities. Send/difference requests and call attachment retain the original user ID. A late successful
send after logout still belongs to that original account and re-applies anonymization rather than
restoring dialog identities. Removing another account does not end the active account's session.

Reset settles/discards pre-reset in-memory credits, clears buckets, daily metrics, tokens and metadata
transactionally, and creates a fresh salt/creation marker. New earned activity continues from the reset
boundary. Failure is surfaced and can retry at the next real event/query. Reset starts a new measurement
generation; prior idempotency records are intentionally removed along with the measurements.

## Secondary metrics and Messages Sent

Opens count real foreground transitions (not configuration changes). Sessions start with earned activity;
account changes, background or an earned-activity gap exceeding five minutes split a session. Longest
session stores active duration, excludes idle gaps, splits daily totals at midnight and survives flushes
without restarting the session. Period metrics sum opens/messages and take the maximum daily session
value; Calls is the exact Calls category total.

Ordinary successful send replies, actual outgoing new-message/new-channel-message confirmations and
visible successful secret sends count once per logical confirmed identity. Forwards/albums count each
message. Failed requests, incoming messages, service messages, invalid/local IDs and quick-reply template
updates do not count. Secret sends use nonzero random IDs, including signed values.

Scheduling acceptance and TL_updateNewScheduledMessage never count. A short response to a scheduling
request is excluded because it does not verify delivery. Scheduled messages count on actual outgoing
from_scheduled live delivery updates or verified new-message difference catch-up. History loads and
channel-too-long snapshots are not delivery confirmations. Persistent salted identity tokens make
ordinary/repeated scheduled confirmations idempotent across retries and restarts while data is retained.
The token and daily increment commit together. Metrics use the local date when a verified confirmation
is observed, including catch-up; server dates are not compared with device wall time. Device clock
changes therefore cannot suppress a new successful confirmation. No Telegram protocol, request,
existing StatsController metric or network behavior is changed.

## Settings dashboard

Settings → Activity shows today's duration on the Settings row, and opens a native UniversalFragment.
The Settings search entry and `tg://settings/activity` route use the same Settings navigation context,
including tablet behavior. The dashboard is itself a Settings surface.

Today/locale-first-day Week/calendar Month tabs show total duration, previous-period comparison,
stacked local charts, nonzero categories with duration/percent bars, most-used chats and secondary metrics.
Today comparison uses yesterday's complete prior hours plus a prorated current hour, explicitly labeled
an estimate. Week/month compare with the complete previous calendar week/month. Categories partition
exactly the total; largest-remainder integer percentages sum to 100. Charts use seconds and locale 12/24
hour labels. Week/month daily chart positions are evenly spaced across DST with correct day labels.

The top ten chats can expand to fifty. Keys include account ID; multiple accounts receive an account
badge; Saved Messages has its own icon. Anonymous/deleted/unavailable identities have safe labels and
are not opened when unavailable. Identity resolution uses Telegram memory caches and bounded local
MessagesStorage reads on a worker queue; generated avatars avoid remote image loading. Cached identity
labels are refreshed on reload. Chat taps set the correct account and use the existing central Protected
Chats presentation/reveal gate. The dashboard does not bypass unlock or fetch message content.

The UI includes local-only/privacy text, an empty state, reset confirmation, failure/retry, accessibility
descriptions, theme keys and RTL handling. Request generations reject stale callbacks after tab changes,
logout, reset or destruction. Device visual/accessibility behavior remains unverified below.

## Tests and review results

Each phase's diff was reviewed and executable failures were fixed before its separate commit.

| Gate | Result |
| --- | --- |
| A4 pure Activity JVM tests | PASS, 26 |
| A4 Protected Chats/shared regressions | PASS, 317 |
| A4 production SQL on host file-backed SQLite WAL | PASS, 10 |
| A5 pure Activity JVM tests | PASS, 32 |
| A5 Protected Chats/shared regressions | PASS, 317 |
| A5 production SQL on host SQLite | PASS, 10 |
| Final audit pure Activity JVM tests | PASS, 33 |
| Final audit Protected Chats/shared regressions | PASS, 317 |
| Final audit host production-SQL tests | PASS, 12 |
| Resource references/plural variants/drawable XML | PASS |
| Negative controls: remove idle cap; double-book credits | PASS, both mutations rejected |
| Protected Chats call ordering and local-only/scope source contracts | PASS |
| JDK parse of 53 changed Java files | PASS, syntax only, no Android type resolution |
| `git diff --check` | PASS |
| Android compilation/type checking/native instrumentation | **UNEXECUTED**, no SDK |
| Android process-death, native database/storage latency QA | **UNEXECUTED** |
| Device/tablet/PiP/media/calls and light/dark/RTL/TalkBack QA | **UNEXECUTED** |

The current-source JVM runner covers all Activity JVM tests and 317 existing Protected Chats/shared
navigation, passcode, forward, profile and layout regressions. SQLite tests execute exact production SQL
and cover reopen, atomic failure/retry, replay deduplication, backwards wall clocks, account separation,
logout plus late delivery, migration, rollup, folding, bounded queries, daily MAX, reset and WAL visibility.
These are host tests, not proof of the Android adapter or Android compilation. Added native UsageStoreTest
and actual-TL-type UsageSendObserverTest are retained for Android execution and are unexecuted here.
The source audit verifies the existing ProtectedChatGate call text/order in BaseFragment,
ActionBarLayout and LaunchActivity against pre-A1; shared security logic was not weakened.

Reproduce the executable gate with the commands in [tests/activity/README.md](../tests/activity/README.md).
The locally cached JUnit/Hamcrest runner and JDK syntax parser do not invoke Gradle or build APKs.

## Privacy, performance, deviations and limitations

Source audit: Activity has no telemetry/upload/network request, remote avatar loader, content capture,
periodic task or export path. Reads/writes are local and the database is excluded from Android backup by
its no-backup location. Stored salted digests are opaque; stable account IDs and retained dialog IDs are
app-private metadata rather than content. Logout anonymization and correct account selection were
reviewed across storage, send callbacks, call attachment and dashboard opens. Protected Chats hooks,
lock/reveal behavior and protected navigation were retained; host regressions pass, device QA is pending.

Input work is constant-time timestamp buffering; accounting/state changes run on UI, database work on
the dedicated queue and local identity lookup on a worker queue. Dashboard queries cover only the
selected/current and previous periods, and identity/display work is capped at fifty chats. No new
per-frame work or Activity-controlled wakeup exists. Real Android latency/battery measurements are
unexecuted, so host figures below are not device performance guarantees.

Meaningful small adaptations: InputConnection hooks replace planned text watchers; explicit Settings
origins replace stack inference; actual STATE_ESTABLISHED call transitions replace notification-based
connection inference; rollback uses SQL; verified scheduled-delivery observers plus durable digests
replace existing scheduling-inclusive stats hooks; confirmation dates use local observation; generated
avatars and a small optional chart-label adapter preserve local-only/locale behavior. Final audit fixes
capture request/call user IDs, anonymize late logout callbacks, preserve another account's session,
coalesce send writes, remove clock-skew suppression and refresh identity labels. No material product or
architecture change was required.

Accepted persistence limitation: an abrupt death before a later event can lose more than five minutes
of an event-free connected call or passive playback. Queued writes must finish to survive death.
Subsecond queue remainders can also be lost (less than one second per bucket); no future/unearned time
is ever persisted to compensate. Rollup/migration runs only when the app next has a meaningful event.
A dashboard opened and then left untouched does not refresh on a timer; another event/read refreshes it.
Catch-up send metrics are booked on the local confirmation date, not reconstructed historical send dates.
Reset clears dedup history and begins a fresh measurement generation.

Host five-year heavy-use sample: 300 hourly rows/day for the newest roughly 90 days, forty daily dialog
rows thereafter, produced 6,692,864 bytes of retained bucket data. Adding 100 opaque confirmations/day
for five years produced 21,626,880 bytes total. A 1,500-row current-month query took 4.24 ms in this host
sample; this is illustrative, not Android QA. Durable dedup tokens grow with confirmed sends until
reset, so the original roughly 7 MB estimate applies to buckets, not guaranteed total database size.

No APK was built; no GitHub Actions/workflow was triggered; no merge, release, deployment or push
was performed. Master, workflows, manifests, signing and Protected Chats security implementation were
not modified. A6 and Local History Chat were not implemented.

## Complete changed-file inventory (pre-A1 through this audit)

Paths are relative to the repository root. Includes the committed plan/research corrections; the research
document needed no further modification for the flush or scheduled-delivery corrections.

- `TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/SecretChatHelper.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/SurfaceKey.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageAccountant.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageClassifier.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageClock.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageInputBuffer.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageLedger.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageMetrics.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsagePolicy.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageReportMath.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageRollup.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageSendIdentity.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageSendObserver.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageStorageSql.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageStore.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageSurface.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageSurfaceResolver.java`
- `TMessagesProj/src/main/java/org/telegram/messenger/usage/UsageTracker.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarLayout.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ActionBar/AlertDialog.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BaseFragment.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ActionBar/BottomSheet.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ArticleViewer.java`
- `TMessagesProj/src/main/java/org/telegram/ui/BubbleActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/Charts/view_data/LegendSignatureView.java`
- `TMessagesProj/src/main/java/org/telegram/ui/Components/EditTextBoldCursor.java`
- `TMessagesProj/src/main/java/org/telegram/ui/Components/PasscodeViewDialog.java`
- `TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/LinkManager.java`
- `TMessagesProj/src/main/java/org/telegram/ui/MainTabsActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/PhotoViewer.java`
- `TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/SecretMediaViewer.java`
- `TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/StatisticActivity.java`
- `TMessagesProj/src/main/java/org/telegram/ui/Stories/StoryViewer.java`
- `TMessagesProj/src/main/java/org/telegram/ui/UsageReportActivity.java`
- `TMessagesProj/src/main/res/drawable/settings_activity.xml`
- `TMessagesProj/src/main/res/values/strings.xml`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageAccountIsolationTest.java`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageAccountantTest.java`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageClassifierTest.java`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageInputBufferTest.java`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageMetricsTest.java`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageReportMathTest.java`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageRollupTest.java`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageSendIdentityTest.java`
- `TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageSignalBoundaryTest.java`
- `TMessagesProj_AppTests/src/androidTest/java/org/telegram/messenger/usage/UsageSendObserverTest.java`
- `TMessagesProj_AppTests/src/androidTest/java/org/telegram/messenger/usage/UsageStoreTest.java`
- `docs/activity-implementation-report.md`
- `docs/activity-plan.md`
- `docs/activity-research.md`
- `tests/activity/README.md`
- `tests/activity/audit_source.py`
- `tests/activity/benchmark_storage.py`
- `tests/activity/check_java_syntax.py`
- `tests/activity/check_resources.py`
- `tests/activity/negative_controls.py`
- `tests/activity/run_jvm.py`
- `tests/activity/test_storage_sql.py`
