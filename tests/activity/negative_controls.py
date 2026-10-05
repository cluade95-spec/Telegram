"""Mutation checks: existing accounting tests must reject idle-cap removal and duplicate booking."""
from pathlib import Path
import os,subprocess,tempfile
base=Path(__file__).resolve().parents[2]
src=base/'TMessagesProj/src/main/java/org/telegram/messenger/usage'
test=base/'TMessagesProj/src/test/java/org/telegram/messenger/usage/UsageAccountantTest.java'
jars=list((Path.home()/'.gradle').glob('wrapper/dists/**/lib/junit-4.13.2.jar'))
hams=list((Path.home()/'.gradle').glob('wrapper/dists/**/lib/hamcrest-core-1.3.jar'))
if not jars or not hams: raise SystemExit('UNEXECUTED: cached JUnit / Hamcrest required')
cp=os.pathsep.join(map(str,[jars[0],hams[0]]))
original=(src/'UsageAccountant.java').read_text()
mutations={
 'remove_idle_cap':original.replace('credit = Math.min(duration, Math.max(0, lastInput + UsagePolicy.IDLE_MS - cursorElapsed));','credit = duration;'),
 'duplicate_credit':original.replace('ledger.add(owner, cursorWall, credit, cursorZone);','ledger.add(owner, cursorWall, credit, cursorZone); ledger.add(owner, cursorWall, credit, cursorZone);')}
for name,source in mutations.items():
 assert source!=original
 with tempfile.TemporaryDirectory(prefix='activity-negative-') as temp:
  temp=Path(temp); changed=temp/'UsageAccountant.java';changed.write_text(source)
  files=[src/(n+'.java') for n in ['UsageClock','UsagePolicy','UsageSurface','SurfaceKey','UsageLedger']]
  subprocess.run(['javac','-cp',cp,'-d',str(temp),*map(str,files),str(changed),str(test)],check=True)
  result=subprocess.run(['java','-cp',str(temp)+os.pathsep+cp,'org.junit.runner.JUnitCore','org.telegram.messenger.usage.UsageAccountantTest'],capture_output=True,text=True)
  assert result.returncode!=0 and 'FAILURES!!!' in result.stdout,(name,result.stdout,result.stderr)
  print('PASS: '+name+' rejected by existing accounting tests')
