from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig').replace('\r\n','\n')
s=s.replace('PhoneBridge v1.5 • waiting for phone','PhoneBridge v1.7 • waiting for phone')
s=s.replace('PhoneBridge-v1.5-SingleInstance','PhoneBridge-v1.7-SingleInstance')
s=s.replace('PhoneBridge v1.5 starting','PhoneBridge v1.7 starting')
s=s.replace('PhoneBridge v1.5 - Camera & Microphone','PhoneBridge v1.7 - Camera & Microphone')
s=s.replace('Buffered no-echo browser mic ON. In the browser select ','Resilient no-echo browser mic ON. In the browser select ')
for marker in ['PhoneBridge-v1.7-SingleInstance','Resilient no-echo browser mic ON','VBCableBridge']:
    if marker not in s: raise SystemExit('v1.7 receiver marker missing: '+marker)
if 'Stereo Mix' in s or 'StereoMixBridge' in s: raise SystemExit('Regression: Stereo Mix returned')
if 'StretchDIBits(' in s: raise SystemExit('Regression: legacy preview renderer returned')
p.write_text(s,encoding='utf-8',newline='\n')
print('Stamped PhoneBridge v1.7 resilient camera/audio receiver')
