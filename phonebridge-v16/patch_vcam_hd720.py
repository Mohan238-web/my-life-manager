from pathlib import Path
import sys

p = Path(sys.argv[1] if len(sys.argv) > 1 else 'windows-camera/Samples/VirtualCamera/VirtualCameraMediaSource/SimpleMediaStream.cpp')
s = p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

old = '#define NUM_IMAGE_ROWS 480\n#define NUM_IMAGE_COLS 640\n'
new = '''// PhoneBridgeHD720 v1.6\n// The Microsoft sample advertises only VGA (640x480). PhoneBridge's real sender\n// already provides 720p/1080p frames, so expose a proper 16:9 720p virtual camera\n// to browsers and recorder applications. Frame rate remains 30 fps.\n#define NUM_IMAGE_ROWS 720\n#define NUM_IMAGE_COLS 1280\n'''
if old not in s:
    raise SystemExit('PhoneBridge v1.6 HD patch anchor missing: VGA dimensions')
s = s.replace(old, new, 1)

# The sample computes all NV12/RGB32 buffer sizes, bitrates and sample allocator
# dimensions from these constants, so changing the advertised dimensions here keeps
# the allocator, media type and frame generator consistent.
for marker in ['PhoneBridgeHD720 v1.6', '#define NUM_IMAGE_ROWS 720', '#define NUM_IMAGE_COLS 1280', 'MFSetAttributeRatio(spMediaType.get(), MF_MT_FRAME_RATE, 30, 1)']:
    if marker not in s:
        raise SystemExit(f'PhoneBridge v1.6 HD marker missing: {marker}')
if '#define NUM_IMAGE_ROWS 480' in s or '#define NUM_IMAGE_COLS 640' in s:
    raise SystemExit('Regression: VGA virtual-camera media type still present')

p.write_text(s, encoding='utf-8', newline='\n')
print(f'Patched PhoneBridge virtual camera native output to 1280x720 @ 30 fps: {p}')
