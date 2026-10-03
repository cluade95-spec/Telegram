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
| `MediaDataController` launcher shortcuts | do not list frequent chats while a passcode lock is on | app lock **and** never list protected chats | identity of protected chats is kept out of the launcher; rebuilt whenever protection changes |
| `NotificationsController` pre-API-24 popup reply action | no reply from notifications while locked | app lock; protected dialogs are filtered per dialog (`hasMessagesToReply`, popup list, wear action, receiver) | unrelated chats keep their reply paths |
| `NotificationsController` `passcode` log variable | log only | app lock | no behavior |
| FLAG_SECURE / screenshots: `LaunchActivity`, `BubbleActivity`, `ExternalActionActivity`, `PaymentFormActivity`, `AndroidUtilities.allowScreenCapture` | block screenshots / task-switcher content whenever a passcode exists | **credential** (reverted to the original property) | the "show app content" switch is a credential-level setting; its rows are shown whenever a credential exists |
| `EditWidgetActivity` note | "passcode ignored for widgets" | credential | text is about the passcode in general |
| `PrivacySettingsActivity` row | shows Passcode On/Off | app lock, or credential with protected chats | row reflects whether the passcode is in use for anything |
| `PasscodeActivity`, `LogoutActivity` | create/change/remove the credential, offer setup | credential (unchanged) | credential semantics |

Preview rule: with "hide previews" on, content of a protected chat is hidden on every surface outside the opened
conversation (dialog rows incl. accessibility, Saved Messages sub-lists, search, hashtag search, downloads list,
notifications, widgets, popups, car) regardless of temporary authorization. Identity is never hidden.

## Chat authentication UI and settings (device QA passes)

* Presentation: Telegram's native `BottomSheet` (slide in/out, dim, outside tap, Back, swipe down, insets, keyboard).
  `ProtectedChatAuthSheet` replaces the sheet's container with a holder that rounds the top corners; the content is
  Telegram's own `PasscodeView` in chat lock mode, so keypad, digit animation (`AnimatingTextView`), error shake and
  haptics, wallpaper, retry throttling and the biometric presentation (keypad hidden while the system prompt is up,
  restored on cancel) are the app lock's code.
* Layout: the full-screen `PasscodeView.onMeasure` derives everything from the display size. In chat mode it asks
  `ChatLockLayout` (pure, tested) for metrics from the height the popup is offered: roomy layout with the lock icon
  (406dp), otherwise the icon is dropped and keypad buttons step down (56 -> 36dp). Title and digits share one band.
  Nothing is scaled; the full-screen layout code path is untouched.
* PIN input state is `PasscodeInputBuffer` (pure, tested). No selected digit: delete removes the latest digit.
* Settings: Passcode Lock = Change Passcode, Fingerprint, then `App Lock` and `Protected Chats` rows
  (`NotificationsCheckCell`: switch end toggles, body opens details; `SwitchRowHitTest`). App Lock details: Auto-lock,
  App Content in Task Switcher. Protected Chats details: Hide Message Previews, Auto-lock, Chats (count) ->
  management list (identity only rows, remove needs the passcode). Switches animate themselves; no list rebuilds.
