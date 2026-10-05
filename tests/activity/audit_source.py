"""Source contract audit, complementary to JVM/SQLite tests and unexecuted Android/device QA."""
from pathlib import Path
import re,subprocess
root=Path(__file__).resolve().parents[2]
base=root/'TMessagesProj/src/main/java/org/telegram'
baseline='37036a582'
for name in ['ui/ActionBar/BaseFragment.java','ui/ActionBar/ActionBarLayout.java','ui/LaunchActivity.java']:
 p=base/name
 before=subprocess.check_output(['git','show',baseline+':'+str(p.relative_to(root))],cwd=root).decode()
 guard=lambda s:[line.strip() for line in s.splitlines() if 'ProtectedChatGate.' in line or 'ProtectedChats.onAppPaused' in line]
 assert guard(before)==guard(p.read_text()),name
 print('PASS: existing Protected Chats gate calls/order retained: '+name)
tracker=(base/'messenger/usage/UsageTracker.java').read_text()
assert 'UsagePolicy.FLUSH_MS' in tracker and 'STATE_ESTABLISHED' in tracker
assert '? callServiceAccount : 0' in tracker and 'instance.callServiceAccount=0' in tracker
assert 'SystemClock.elapsedRealtime()' in tracker
assert 'if (!activeAccount)' in tracker and 'instance.flushNow(uid)' in tracker
store=(base/'messenger/usage/UsageStore.java').read_text()
assert 'getNoBackupFilesDir()' in store and 'usageQueue' in store and 'BEGIN IMMEDIATE' in store and 'ROLLBACK' in store
ui=(base/'ui/UsageReportActivity.java').read_text()
assert 'VIEW_TYPE_STACKBAR,-1,chart' in ui
assert 'chat.setCurrentAccount(slot)' in ui and 'presentFragment(chat)' in ui
assert 'ImageLocation' not in ui and 'ImageReceiver' not in ui
for p in list((base/'messenger/usage').glob('*.java'))+[base/'ui/UsageReportActivity.java']:
 assert not re.search(r'\b(?:sendRequest|WorkManager|AlarmManager|JobScheduler|postDelayed|scheduleAtFixedRate|TimerTask)\b',p.read_text()),p
send=(base/'messenger/SendMessagesHelper.java').read_text()
assert send.count('UsageSendObserver.reply(usageAccountUserId,')==3
assert 'TL_updateShortSentMessage && !scheduling' in (base/'messenger/usage/UsageSendObserver.java').read_text()
secret=(base/'messenger/SecretChatHelper.java').read_text()
assert 'UsageTracker.onMessageSent(usageAccountUserId,' in secret
changed=subprocess.check_output(['git','diff','--name-only',baseline],cwd=root).decode().splitlines()
for p in changed:
 assert not p.startswith('.github/workflows/') and not p.endswith('AndroidManifest.xml')
 assert not ('keystore' in p or 'signing' in p.lower() or 'LocalHistory' in p)
print('PASS: monotonic/event-checked storage, account capture, local charts/avatars and scope contracts')
