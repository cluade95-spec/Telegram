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

**Source side** (`ProtectedChatsState.beginForwardHold`): a forward picker covers the screen that started it. With
Immediate Auto-lock the covered protected chat was locked and then closed when the picker returned to it, before
Telegram's success message and tag emojis for a forward to Saved Messages could finish. The hold does not depend on the
screen type: `ActionBarLayout.presentFragment` tells `ProtectedChatGate.onForwardPickerPresented` which fragment a forward
picker is presented over, and any fragment that shows a protected dialog which is authorized and open right now (a chat,
a profile with its shared media, the chat behind the photo viewer) holds it. A screen that is not
gated (`MediaActivity`) shows no protected dialog, so it can neither hold nor manufacture an authorization; the chat and
profile under it were already covered, so under Immediate Auto-lock there is nothing of theirs left to lock.
While the picker is on top the cover is not counted as leaving (the time it happened is kept). When the picker hands its
selection over (`DialogsActivity.notifyDelegate`) the source is marked as returning; right after the delegate returns the
hold is settled: if the selection was not completed the picker goes on, if a destination chat was opened over the source
the countdown the cover would have started is applied from the time of the cover (the old behaviour), otherwise the source
is back on screen with its authorization, for a deposit into Saved Messages (success message and tag emojis), a forward
into the same chat, or a send to several chats. A picker that ends without a selection (Back) settles the same way as an
ordinary cover. In the completion phase Telegram's own bulletin (tags) or undo view hiding ends the hold for a chat's
forward to Saved Messages; for the other sources the phase has no effect while the source is on screen and ends with the
next navigation away from it, the next forward, the app going to the background, a manual lock or the chat being
destroyed. The hold concerns only the chat that was authorized and open when the picker was presented, never authorizes
another chat or Saved Messages, and uses no timer. Destination authentication is unchanged: a selection of several chats
asks for each locked protected destination in turn, remembers what the user unlocked for it while its own restart runs, and
sends once; a cancel sends nothing.
