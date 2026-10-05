"""Resource consistency checks; these are not Android compilation or device validation."""
from pathlib import Path
import re,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2]
resources=root/'TMessagesProj/src/main/res'
xml=ET.parse(resources/'values/strings.xml')
names=[n.attrib['name'] for n in xml.getroot() if 'name' in n.attrib]
usage=[n for n in names if n.startswith('Usage')]
assert len(usage)==len(set(usage)), 'Duplicate Activity resource'
for p in [root/'TMessagesProj/src/main/java/org/telegram/ui/UsageReportActivity.java']:
 for name in re.findall(r'R.string.(\w+)',p.read_text()): assert name in names,(p.name,name)
 for unit in ['UsageHours','UsageMinutes','UsageSeconds']:
  for suffix in ['zero','one','two','few','many','other']: assert unit+'_'+suffix in names
ET.parse(resources/'drawable/settings_activity.xml')
print('PASS: Activity resource references, plural variants, and drawable XML')
