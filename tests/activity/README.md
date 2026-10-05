# Activity checks

Run from the repository root:

```
python3 tests/activity/run_jvm.py
python3 tests/activity/test_storage_sql.py
python3 tests/activity/check_resources.py
python3 tests/activity/negative_controls.py
```

The JVM runner compiles current pure production sources and every Activity JVM test, plus the
Protected Chats / shared navigation, passcode, forward, profile and layout regression suites.
It uses locally cached JUnit 4.13.2 and Hamcrest; it invokes neither Gradle nor an APK build.
The host SQLite tests execute production SQL on file-backed WAL databases, including restart,
rollback/retry, deduplication, isolation, migration, retention, range reads and reset.
These do not substitute for Android/native SQLite or device QA.

`TMessagesProj_AppTests/src/androidTest/java/org/telegram/messenger/usage/UsageStoreTest.java`
uses isolated no-backup database files and exercises the real queue/native adapter. Android SDK,
Android compilation, instrumentation, process-death QA and dashboard light/dark/RTL/TalkBack
checks are **UNEXECUTED** in the Termux environment without an Android SDK/device runner.
Do not launch an APK build or workflow to run them without separate authorization.

The resource check parses Activity strings/plural variants/drawable XML and checks references.
The mutation runner changes only temporary copies: removing the idle cap and double-booking credits
must fail the existing accountant suite. Report math covers locale week starts, calendar months,
partial-hour Today comparison, exact chart/category sums, migration, isolation, rounded percentages,
secondary metrics and the 50-chat display limit.
