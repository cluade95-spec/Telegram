#!/usr/bin/env python3
"""Compile current pure Activity and shared security/regression sources, without Android or an APK."""
from pathlib import Path
import os, subprocess, tempfile
base=Path(__file__).resolve().parents[2]
jars=list((Path.home()/'.gradle').glob('wrapper/dists/**/lib/junit-4.13.2.jar'))
hams=list((Path.home()/'.gradle').glob('wrapper/dists/**/lib/hamcrest-core-1.3.jar'))
if not jars or not hams: raise SystemExit('UNEXECUTED: cached JUnit 4.13.2 / Hamcrest required')
cp=os.pathsep.join(map(str,[jars[0],hams[0]]))
src=base/'TMessagesProj/src/main/java/org/telegram/messenger'
test=base/'TMessagesProj/src/test/java/org/telegram/messenger'
exclude={'UsageTracker.java','UsageSurfaceResolver.java','UsageStore.java','UsageSendObserver.java'}
sources=[p for p in (src/'usage').glob('*.java') if p.name not in exclude]
activity=list((test/'usage').glob('*Test.java'))
shared=['ProtectedChatsState','ProtectedDialogIds','ProtectedGateLifecycle','ForwardDestinations','PasscodeInputBuffer','PasscodeLockPolicy','ProfileContentPolicy','SwitchRowHitTest','ChatLockLayout']
sources += [src/(n+'.java') for n in shared]
regressions=[p for p in test.glob('*Test.java') if not p.name.startswith(('Lyrics','SyncedLyrics'))]
sources += [test/'GateNavigationTestBase.java']
with tempfile.TemporaryDirectory(prefix='activity-jvm-') as tmp:
 subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',tmp,*map(str,sources+activity+regressions)],check=True)
 for label,tests in [('Activity',activity),('Protected Chats and shared regressions',regressions)]:
  print(label,flush=True)
  names=['org.telegram.messenger.'+('usage.' if p.parent.name=='usage' else '')+p.stem for p in tests]
  subprocess.run(['java','-cp',tmp+os.pathsep+cp,'org.junit.runner.JUnitCore',*names],check=True)
