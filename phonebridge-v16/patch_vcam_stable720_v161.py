from pathlib import Path
import sys

p = Path(sys.argv[1] if len(sys.argv) > 1 else 'windows-camera/Samples/VirtualCamera/VirtualCameraMediaSource/SimpleMediaStream.cpp')
s = p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

old = '#define NUM_IMAGE_ROWS 480\n#define NUM_IMAGE_COLS 640\n'
new = '''// PhoneBridgeStable720 v1.6.1\n// Reliability hotfix: expose one stable 1280x720 @ 30 fps camera media type.\n// Portrait phone frames are preserved by the v1.6 FIT_CENTER frame generator\n// instead of changing Media Foundation media types while browser camera clients run.\n#define NUM_IMAGE_ROWS 720\n#define NUM_IMAGE_COLS 1280\n'''
if old not in s:
    raise SystemExit('v1.6.1 stable720 anchor missing: Microsoft VGA defines')
s = s.replace(old, new, 1)

for marker in ['PhoneBridgeStable720 v1.6.1', '#define NUM_IMAGE_ROWS 720', '#define NUM_IMAGE_COLS 1280', 'MFSetAttributeRatio(spMediaType.get(), MF_MT_FRAME_RATE, 30, 1)']:
    if marker not in s:
        raise SystemExit('v1.6.1 stable720 marker missing: ' + marker)

if 'PhoneBridgeAdaptiveMedia v1.6' in s or 'const uint32_t NUM_MEDIATYPES = 4' in s:
    raise SystemExit('Regression: dynamic adaptive media negotiation is still present')
if '#define NUM_IMAGE_ROWS 480' in s or '#define NUM_IMAGE_COLS 640' in s:
    raise SystemExit('Regression: VGA-only virtual camera remains')

p.write_text(s, encoding='utf-8', newline='\n')
print('Applied PhoneBridge v1.6.1 stable 1280x720 @ 30 fps virtual-camera media type')
