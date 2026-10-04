# Protected chats: passcodeHash call-site audit

Three separate notions exist now:

* **credential** – `SharedConfig.hasPasscode()` (a hash is stored)
* **app-wide lock** – `SharedConfig.isAppLockEnabled()` (credential + `appLockEnabled`)
* **protected chats** – `ProtectedChats` (per dialog)

| Call site | Original purpose | New condition | Why |
|---|---|---|---|
| `AndroidUtilities.needShowPasscode` | decide whether the full-screen lock must show | app lock | belongs to the app lock |
| `AppStartReceiver` (boot) | re-lock the app after reboot | app lock | app lock only; protected chats are in-memory and always locked after boot |
| `LaunchActivity` onCreate / `onActivityResult` / `onPasscodePause` / `ExternalActionActivity` / `BubbleActivity` lock timers | auto-lock bookkeeping | app lock | app lock only. Protected chats use their own `appPaused/appResumed` calls |
| `DialogsActivity` header lock icon | "lock the app now" | app lock | action locks the app |
| `TelegramMediaSession` | hide car/media metadata while app is locked | app lock | app lock |
| `MediaDataController` launcher shortcuts | do not list frequent chats while a passcode lock is on | app lock (as on master); protected chats are listed like any other | a shortcut holds a chat's name and avatar, never its messages; opening one goes through the protected-chat gate. Hiding it removed a Telegram feature without protecting any message content |
| `NotificationsController` pre-API-24 popup reply action, popup list and `PopupNotificationActivity`, wear / inline reply action (`WearReplyReceiver`), car unread list (`HomeScreen`), bot buttons on notifications | no reply from notifications while locked | app lock; per dialog `ProtectedChats.allowsExternalInteraction` (never for a protected dialog, whatever "Hide Message Previews" says or whether the chat is open for now) | each of these sends into the chat without opening it, so it would bypass the authentication that opening requires; unrelated chats keep their paths |
| `NotificationsController` `passcode` log variable | log only | app lock | no behavior |
| FLAG_SECURE / screenshots: `LaunchActivity`, `BubbleActivity`, `ExternalActionActivity`, `PaymentFormActivity`, `AndroidUtilities.allowScreenCapture` | block screenshots / task-switcher content whenever a passcode exists | **credential** (reverted to the original property) | the "show app content" switch is a credential-level setting; its rows are shown whenever a credential exists |
| `EditWidgetActivity` note | "passcode ignored for widgets" | credential | text is about the passcode in general |
| `PrivacySettingsActivity` row | shows Passcode On/Off | app lock, or credential with protected chats | row reflects whether the passcode is in use for anything |
| `PasscodeActivity`, `LogoutActivity` | create/change/remove the credential, offer setup | credential (unchanged) | credential semantics |

Preview rule: with "hide previews" on, content of a protected chat is hidden on every surface outside the opened
conversation (dialog rows incl. accessibility, Saved Messages sub-lists, search, hashtag search, downloads list,
notifications, widgets, copy-code button) regardless of temporary authorization. Identity is never hidden.

Interaction rule (independent of the preview setting): nothing outside the opened conversation may act on a protected
dialog. The popup (it has a reply box), the notification and Wear reply, the car unread list (it replies), bot buttons on
a notification, and a share into the chat (system share sheet, Direct Share shortcut, share picker: `LaunchActivity.didSelectDialogs`
asks for the unlock first) all follow `ProtectedChats.allowsExternalInteraction`. Bubbles and launcher shortcuts only
lead to the chat, which the gate authenticates.

## Chat authentication UI and settings (device QA passes)

* Presentation: Telegram's native `BottomSheet` (slide in/out, dim, outside tap, Back, swipe down, insets, keyboard).
  `ProtectedChatAuthSheet` replaces the sheet's container with a holder that rounds the top corners; the content is
  Telegram's own `PasscodeView` in chat lock mode, so keypad, digit animation (`AnimatingTextView`), error shake and
  haptics, wallpaper, retry throttling and the biometric presentation (keypad hidden while the system prompt is up,
  restored on cancel) are the app lock's code.
* Layout: the full-screen `PasscodeView.onMeasure` derives everything from the display size. In chat mode it asks
  `ChatLockLayout` (pure, tested) for metrics from the height the popup is offered: roomy layout with the lock icon
  (414dp: 24dp clear under the bottom row), otherwise the icon is dropped and keypad buttons step down (56 -> 36dp).
  Title and digits share one band. The keypad frame and the popup are summed in pixels the way the keys are placed
  (every `dp()` term rounds up on its own, so a frame sized by the dp total cut the bottom off the "0" key at some
  densities). Nothing is scaled; the full-screen layout code path is untouched.
* PIN input state is `PasscodeInputBuffer` (pure, tested). No selected digit: delete removes the latest digit.
* Settings: Passcode Lock = Change Passcode, Fingerprint, then `App Lock` and `Protected Chats` rows
  (`NotificationsCheckCell`: switch end toggles, body opens details; `SwitchRowHitTest`). App Lock details: Auto-lock,
  App Content in Task Switcher. Protected Chats details: Hide Message Previews, Auto-lock, Chats (count) ->
  management list (identity only rows, remove needs the passcode). Switches animate themselves; no list rebuilds.

## Profiles and shared media (device QA regression pass)

Protected Chats protects a protected chat and its message content. It does not remove Telegram features, so a profile
keeps its normal structure for every peer (own profile, users, groups, channels, bots, Saved Messages, secret chats).

* Never touched by protection: Stories, Gifts (profile gifts), Common Groups, Similar Channels/Bots, Members, Storage,
  profile actions, account rows, and every other row of `ProfileActivity`.
* Withheld only while the chat is locked (`ProfileContentPolicy`, applied in `SharedMediaLayout`, the preloader and
  `ProfileActivity`): the tabs derived from the messages of the protected dialog (Media, Files, Music, Voice, Links, GIFs,
  Posts) and their counts. Counts are masked when read, so unlocking needs no reload.
* Saved Messages: only the Saved Messages content (messages and the Saved Messages / Saved Dialogs tabs) is withheld
  while Saved Messages is locked. The own profile is no longer cut down; its Stories and Gifts tabs stay.
* Section: `ProfileContentPolicy.showSharedMediaSection`. The own profile always has its shared-media section (where
  Stories and Gifts appear), except while Saved Messages is locked and nothing else (Stories, Gifts, visible media)
  would fill it. Stories and Gifts are part of what fills it, so they cannot be what is lost. The own profile looks at its
  rows again when its full user info arrives (`rebuildWhenUserInfoArrives`).
* Gate: opening the profile of another protected dialog still goes through `ProtectedChatGate`, because that profile
  hosts the protected shared media. The own profile is never gated as a whole.
* App Lock state, the credential and the locked/unlocked state never decide whether an unrelated profile feature is shown.

## Lifecycle of an open protected chat (device QA, build #82)

Build #82 kicked the user out of an authorized protected chat when Android showed a camera or microphone permission
dialog, when a channel's comments were opened and closed, and in the three forward flows. One mistake sat under all of
them: *"the fragment was paused or covered" was treated as "the user left the chat"*. Telegram and Android put things above
a chat all the time and the chat is where the user comes back to. `ProtectedGateLifecycle` (pure Java, driven by
`ProtectedChatGate`) now models this explicitly. A fragment that shows a protected dialog is a node in one of these states:

| State | Meaning | Left by |
| --- | --- | --- |
| `VISIBLE` | on screen, in use | a child is presented over it, the host pauses, it is closed |
| `HOST_PAUSED` | the activity is paused (a system permission dialog, a split-screen focus change, picture-in-picture) but the chat is still what the user sees; not navigation, not leaving | the activity resumes; if it is stopped instead, the app-background boundary applies |
| `COVERED` | a child fragment (forward picker or destination, channel comments, a profile, a media, search or contact screen) is over it and it is where Back returns to; still in use, still authorized | Back (it becomes `RETURNING`) |
| `RETURNING` | resumed while its transition (Back, swipe, predictive back) is still running; nothing is evaluated and nothing is closed mid-transition | the transition ends (`VISIBLE`), a cancelled swipe (`COVERED`) |
| `LEFT` | genuinely left while its fragment is still in a stack; it is closed as soon as that is safe | - |
| `DESTROYED` | popped or removed; normal Auto-lock applies from then | - |

**System permission dialogs.** `Activity.requestPermissions` shows the permission controller's translucent dialog over the
activity: `LaunchActivity.onPause` runs, `onStop` does not, and the result arrives in `onRequestPermissionsResult` just before
`onResume`. Build #82 had two places that read `onPause` as "the app left": `ActionBarLayout.onPause` forwarded it to the top
chat as an ordinary fragment pause (starting the Immediate countdown for that chat), and `LaunchActivity.onPasscodePause`
called `ProtectedChats.onAppPaused`, marking every open chat as backgrounded. `onResume` then found the chat locked and the
gate closed it. Now `ActionBarLayout.onPause/onResume` run the forwarded host lifecycle inside
`ProtectedChatGate.hostLifecycle(true/false)`, so a fragment paused there is `HOST_PAUSED`, not covered; and the
app-background boundary (`ProtectedChats.onAppPaused`) moved to what really is the background: `LaunchActivity.onStop`,
`BubbleActivity.onStop` and the screen turning off. Allow, Deny and a dismissed dialog are the same lifecycle (the gate never
sees the answer). No timer, no per-permission case: the camera, the microphone, a circle message, the attachment menu and any
future permission are the same pause without a stop. If the activity is stopped (Home pressed over the dialog) the normal
background boundary wins: Immediate Auto-lock applies on return, and a timed interval counts from the stop. A system activity
that stops the app (file picker, the camera app) keeps the existing rule: its result (`onActivityResult`) revives chats that
were open; a permission UI of a device vendor that fully covers the app is, like any app switch, a background.

**Channel comments and every other child.** The comments are a `ChatActivity` of the linked discussion chat pushed over the
channel. In build #82 the channel's pause started the Immediate countdown, it locked, and Back resumed a locked chat, which the
gate closed with `removeSelfFromStack` while `closeLastFragment`'s transition was running (`removeFragmentFromStack` completes a
running transition first): the glitch, then the kick. Now covering is not leaving. `ActionBarLayout.presentFragment` tells the
gate what it presents over (`ProtectedChatGate.onFragmentPresented`); a fragment presented over a protected chat that is in
use is its child, and so is anything opened from a child as long as it shows no other conversation than the ones that belong to
the chat's context (the chat's own dialog, the dialog the child shows, screens that are not a conversation: pickers, media,
search, settings). The chat stays authorized and counted as open however long the child stays, for every Auto-lock interval.
Back, system back, the toolbar arrow, a completed swipe or predictive back, and a cancelled one (the previous fragment is
resumed when the gesture starts and paused again when it is given up) are the same `COVERED` / `RETURNING` transitions.

**Genuine departure** (normal Auto-lock applies from that moment): the chat is closed or removed; the app goes to the
background (activity stopped, screen off); a manual lock; and a child that opens *another* conversation than the ones that
belong to the chat's context (another chat or profile opened from the comments, a link to another chat). A chat left that way
is closed at once if it is not on screen (silently, under its children), whatever the interval: an old instance does not wait
in a stack to be revealed later, and opening it again goes through the gate (and asks nothing while the interval lasts). The
forward picker's own screens (a forum's topics) belong to the forward while the picker is open.

**Never pop what is becoming visible.** The gate closes a fragment that lost its authorization at the moment it loses it (a
manual lock, the app returning from the background, a departure), when it is not on screen, through `ProtectedChatGate.tryClose`:
silent removal for a fragment that is not the top or second of its layout, `finishFragment(false)` for the top, and *not now* for
the top or second fragment of a layout that is in a transition or a swipe, retried when the transition ended
(`BaseFragment.onBecomeFullyVisible/Hidden` -> `transitionSettled`). Nothing is ever closed from inside the call that resumes a
fragment. The Build #82 `removeSelfFromStack` in `onFragmentResumed` is gone; a locked fragment that is revealed anyway (no known
path) is closed after the transition, not during it.

**Security boundaries kept.** The app background, the screen turning off, a manual lock, Auto-lock after a genuine departure and
after the activity was stopped, a locked chat being asked for before it is created (the gate in front of `presentFragment`,
`addFragmentToStack`, sheets and the right-sliding container, which is what notification, deep-link, share and restored-state
entries go through), authorization that does not survive the process, and a protected chat opened as a child (comments, a
forward destination) being gated and authorized on its own account, independently of the chat it was opened from.

**Audit of what takes the top position from a chat** (read from the code; `CA` = `ChatActivity`, `AB` = `ActionBarLayout`,
`LA` = `LaunchActivity`). The gate treats each mechanism, not each screen:

| Mechanism | What it is (examples, evidence) | Chat's `onPause` | Gate |
| --- | --- | --- | --- |
| window, dialog, bottom sheet, in-chat view | attach menu `ChatAttachAlert` and its photo grid, albums, in-app camera view, file, location and contact layouts; circle-message `InstantCameraView` and voice recording (views in the chat); `PhonebookShareAlert`, `ShareAlert`, `BotWebViewSheet`, attach-menu bots, `EmbedBottomSheet`, `ArticleViewer` sheet, alert dialogs, popups; `PhotoViewer` (a window, `WindowManager.addView`); chat search, pinned-bar tap, hashtag search (in place, embedded chats off the stack) | no | nothing happens |
| fragment pushed as a sheet | any `presentFragment` while a `ChatAttachAlert` or `BotWebViewSheet` is visible becomes `showAsSheet` (`AB.shouldOpenFragmentOverlay`); `showAsSheet`; `presentFragmentAsPreview` (until expanded) | no | nothing happens |
| system permission dialog | camera (attach tile, empty-view button, circle message), microphone (voice, circle message, video), media/storage, location, contacts, QR camera and web permissions in a web app: `Activity.requestPermissions`, result before `onResume` | only by the host (`onPause`, no `onStop`) | `HOST_PAUSED`, back to `VISIBLE` on resume |
| external activity | camera apps (`ACTION_IMAGE_CAPTURE`, `ACTION_VIDEO_CAPTURE`), system file and gallery pickers (`ACTION_GET_CONTENT`, `ACTION_PICK`), share chooser, dial, sms, contact insert, settings intents, Custom Tabs and the external browser (`Browser.openUrl`) | by the host (`onPause`, `onStop`) | background boundary; a result (`onActivityResult`, before `onResume`) keeps the chats that were open as it always did; with no result (a link opened in the browser) the normal background rule applies on return |
| child fragment pushed | forward picker `DialogsActivity`; `ProfileActivity` (`ProfileActivity2` is not instantiated); `MediaActivity`; `LocationActivity` (viewing); `HashtagActivity`, `CalendarActivity`; the pinned list (a `ChatActivity` of the same dialog, `MODE_PINNED`); a reply thread (a second `ChatActivity` of the same dialog, `threadMessageId`); **channel comments** (a `ChatActivity` of the linked discussion chat: `chat_id = -discussionDialogId`, `setThreadMessages`, `presentFragment(chatActivity)` at CA:35757-35773, guarded by `isFullyVisible`); another chat opened by a link, a forwarded-from header or a search result; photo-picker search fragments when no alert is showing | at the end of the open transition | `COVERED`; opening another conversation from a child leaves the chat |
| replacement or removal | a bot mentioned in a chat replaces it (`removeLast`); `PhotoViewer` "show in chat" replaces it; `TopicsFragment.prepareToSwitchAnimation` adds the topics screen *under* the chat and finishes the chat; `closeChats` (posted by most intent-driven links and notifications) finishes or removes every chat; `LaunchActivity.handleIntent` account switch and "show dialogs" remove all fragments; a second `ChatActivity` of the same dialog removes the older one (CA:27306) | pause and destroy | destroyed: normal Auto-lock from then (the fragments of one dialog count together, so topics under a chat or a duplicate does not lock it) |
| tablet routing | non-chat fragments presented from the right or main layout go to the layers layout (own stack); the tablet-mode switch destroys and rebuilds the chats synchronously | not for layers pushes | no relation is recorded; a rebuilt chat is created again before the posted leave runs |

Findings from the audit: a permission request and an external activity are different mechanisms (pause only, pause and
stop) and used to be the same thing to the gate; `BubbleActivity` had its own copy of the same mistake
(`onPasscodePause` -> `ProtectedChats.onAppPaused`), fixed the same way; `ChatAttachAlertPhotoLayout.onPause` closes the camera
view when the activity pauses (Telegram's own behaviour, unrelated to the gate).

## Forwarding and sharing

**Destination rule** (`ForwardDestinations`, one rule for every kind of destination: users, bots, groups, supergroups,
channels, topics, secret chats): a destination that is protected and locked asks for authentication before anything is
sent. The only exception is the user's own Saved Messages: a forward into it is write-only, so it neither asks nor unlocks
nor opens Saved Messages, and its history stays behind its own lock. A destination that is already unlocked is not asked
again. In-app forward pickers (`DialogsActivity`, type forward: chat forward, quote and reply pickers, photo viewer,
media, search, music, share contact) and the in-app share sheet (`ShareAlert`) apply it where the selection is handed
over. The system share sheet, Direct Share and the share picker (`LaunchActivity.didSelectDialogs`) apply it too, but
without the exception: a share opens the chat it lands in.

**Continuation**: the pending operation is the original selection handed over again with the arguments it had (messages
in order, destinations with their topics, comment, send options). Nothing is consumed before the destinations are open.
After a successful unlock it runs once; a cancelled or failed unlock drops it and it can never run later.
Lost-forward root cause (build #80): `ChatActivity.didSelectDialogs` cleared the selection and the forwarded message
first, then opened the destination chat; the chat gate blocked that open, the picker was closed, and after the unlock
the gate only reopened the bare destination chat, so the forward panel was never created.

**Source side: the forward is a transaction of the chat that started it** (`ProtectedChatsState.ForwardPhase`,
driven by `ProtectedGateLifecycle`, which `ProtectedChatGate` adapts to fragments). The picker, a destination's
authentication sheet, the destination chat the forward opens and Telegram's success interaction are all part of the
forward; none of them is the user leaving the source chat. Phases, per source dialog:

| Phase | Source chat | Starts | Ends |
| --- | --- | --- | --- |
| `NONE` (source visible, or no forward) | shown, or covered by a child | - | - |
| `PICKER` (`FORWARD_PICKER_OPEN`; a destination's `FORWARD_AUTH_OPEN` sheet is only a layer over it) | covered by the picker, not left | `ActionBarLayout.presentFragment` -> `ProtectedChatGate.onFragmentPresented`, only for a forward picker over a protected dialog that is authorized and visible | picker destroyed (`CANCELLED` when the source was shown again first: Back, system/toolbar/gesture back), source destroyed, app paused, manual lock |
| `HANDING_OVER` (`RETURNING_TO_SOURCE`) | covered | `DialogsActivity.notifyDelegate` before `didSelectDialogs` | the delegate returns (`forwardSettled`, in a `finally`) |
| `DESTINATION` (`DESTINATION_OPENED`) | covered by the destination chat | settle: a chat is on top of the source | destination paused or destroyed, source destroyed, app paused, manual lock |
| `COMPLETING` (`FORWARD_COMPLETION_UI`) | shown again, success/tag UI on it | settle: the picker returned to the source | bulletin/undo view hidden, any navigation away, next forward, app paused, source destroyed |

Why a cancel is not special: the picker and the destination are children of the source, and a covered chat is not left (see
the lifecycle section above); so Back from the picker, Back from the destination and a forward that returns to the source all
find the chat as it was. The forward phases add the transaction's own bookkeeping on top: if the user does leave while a
forward is open (the chat is released), the forward ends with it. Nothing is decided at resume time: build #82 resolved the hold when the source resumed
(`ProtectedChatGate.onFragmentResumed` called `forwardPickerClosed`), which applied the Immediate Auto-lock, and the chat
gate then closed the freshly resumed source with `removeSelfFromStack` while the Back animation was still running
(`ActionBarLayout.removeFragmentFromStack` completes the running transition, then closes the chat); and it abandoned the
hold as soon as a destination opened, so Back from the destination met a locked source.

A destination that opens *another conversation* (a chat or profile) takes the user out of the source's context: the source is
left and normal Auto-lock applies from then (it is closed under the destination, silently). A screen that shows no other
conversation (a profile of the destination's own chat, media, search) does not. The app going to the background, a manual
lock and the source being closed end the forward; a chat that was only covered by its forward is not revived by a return from a
system activity. The swipe-back preview resumes the source when the swipe starts and pauses it again when the swipe is given
up: the phase is untouched until the picker or destination is actually destroyed.

The hold does not depend on the screen type: any fragment that shows a protected dialog which is authorized and visible
right now (a chat, a profile with its shared media, the chat behind the photo viewer) can start it. A screen that is not gated
(`MediaActivity`) shows no protected dialog, so it can neither hold nor manufacture an authorization. Source and destination
are different fragments with different dialogs: the picker points to its source node, the destination points back to the
source dialog, every transition is addressed to one dialog, a destination's own gate and authorization are never touched, and
there is no global "current forward chat". The hold never authorizes another chat or Saved Messages and uses no timer.
Destination authentication is unchanged: a selection of several chats asks for each locked protected destination in turn,
remembers what the user unlocked for it while its own restart runs, and sends once; a cancel sends nothing; Saved Messages
stays write-only and the tag-emoji completion is kept. `ActionBarLayout.presentFragment` is involved only to record who
covers whom and to start the transaction; Back, destination and completion are decided by the fragment lifecycle callbacks
every kind of back navigation goes through (`closeLastFragment`, the swipe-back animation).
