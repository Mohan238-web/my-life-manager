from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

s=s.replace('PhoneBridge v1.4 • waiting for phone','PhoneBridge v1.5 • waiting for phone')
s=s.replace('PhoneBridge-v1.4-SingleInstance','PhoneBridge-v1.5-SingleInstance')
s=s.replace('PhoneBridge v1.4 starting','PhoneBridge v1.5 starting')
s=s.replace('PhoneBridge v1.4 - Camera & Microphone','PhoneBridge v1.5 - Camera & Microphone')
s=s.replace('No-echo browser mic ON. In the browser select ','Buffered no-echo browser mic ON. In the browser select ')

for marker in ['PhoneBridge-v1.5-SingleInstance','Buffered no-echo browser mic ON','VBCableBridge','D2D1CreateFactory','DXGI_FORMAT_B8G8R8A8_UNORM']:
    if marker not in s:
        raise SystemExit('v1.5 Windows marker missing: '+marker)
if 'Stereo Mix' in s or 'StereoMixBridge' in s:
    raise SystemExit('Regression: Stereo Mix feedback path returned')
if 'StretchDIBits(' in s:
    raise SystemExit('Regression: legacy color renderer returned')

p.write_text(s,encoding='utf-8',newline='\n')
print('Applied PhoneBridge v1.5 Windows release identity and buffered audio messaging')
