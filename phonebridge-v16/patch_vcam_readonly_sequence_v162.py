from pathlib import Path
import sys

p = Path(sys.argv[1] if len(sys.argv) > 1 else 'windows-camera/Samples/VirtualCamera/VirtualCameraMediaSource/SimpleFrameGenerator.cpp')
s = p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

old1 = '''        const LONG seq1 = InterlockedCompareExchange(
            reinterpret_cast<volatile LONG*>(s_view), 0, 0);
'''
new1 = '''        // PhoneBridgeReadOnlySequence v1.6.2
        // video.bus is mapped FILE_MAP_READ/PAGE_READONLY in Camera Frame Server.
        // InterlockedCompareExchange is a read-modify-write instruction and can fault
        // on a read-only mapping even when used only to observe the value. Use a true
        // read-only copy for the writer sequence counter instead.
        LONG seq1 = 0;
        memcpy(&seq1, s_view, sizeof(seq1));
'''
old2 = '''                const LONG seq2 = InterlockedCompareExchange(
                    reinterpret_cast<volatile LONG*>(s_view), 0, 0);
'''
new2 = '''                MemoryBarrier();
                LONG seq2 = 0;
                memcpy(&seq2, s_view, sizeof(seq2));
'''

if old1 not in s:
    raise SystemExit('v1.6.2 anchor missing: first InterlockedCompareExchange sequence read')
if old2 not in s:
    raise SystemExit('v1.6.2 anchor missing: second InterlockedCompareExchange sequence read')

s = s.replace(old1, new1, 1).replace(old2, new2, 1)

for marker in ['PhoneBridgeReadOnlySequence v1.6.2','memcpy(&seq1, s_view, sizeof(seq1))','memcpy(&seq2, s_view, sizeof(seq2))','s_cached.swap(s_candidate)','FIT_CENTER instead of stretch/crop']:
    if marker not in s:
        raise SystemExit('v1.6.2 required marker missing: ' + marker)
if 'InterlockedCompareExchange(' in s:
    raise SystemExit('Regression: write-style interlocked read remains in read-only Frame Server mapping')

p.write_text(s, encoding='utf-8', newline='\n')
print('Applied PhoneBridge v1.6.2 true read-only sequence sampling for Camera Frame Server')
