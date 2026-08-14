from pathlib import Path
import sys

p = Path(sys.argv[1] if len(sys.argv) > 1 else 'windows-camera/Samples/VirtualCamera/VirtualCameraMediaSource/SimpleFrameGenerator.cpp')
s = p.read_text(encoding='utf-8-sig')

if '#include <vector>' not in s:
    pos = s.find('\n', s.find('#include'))
    s = s[:pos+1] + '#include <vector>\n' + s[pos+1:]


def replace_cpp_function(text: str, signature: str, replacement: str) -> str:
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 0
    end = None
    for i in range(brace, len(text)):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        raise RuntimeError('Could not locate function end: ' + signature)
    return text[:start] + replacement + text[end:]

frame_reader = r'''HRESULT SimpleFrameGenerator::_CreateRGB32Frame(
    _Inout_updates_bytes_(len) BYTE* pBuf,
    _In_ DWORD len,
    _In_ LONG pitch,
    _In_ DWORD width,
    _In_ DWORD height,
    _In_ ULONG rgbMask )
{
    // PhoneBridgeFitLatestFrame v1.6
    // Goals:
    // 1) show the same full field of view as the Android FIT_CENTER preview,
    // 2) never stretch portrait frames into a wide landscape face,
    // 3) repeat the last complete frame instead of flashing black while the writer
    //    is in the middle of a new frame, and
    // 4) keep the video.bus mapping open so each browser sample request receives the
    //    newest frame with less file/mapping overhead.
    if (!pBuf || width == 0 || height == 0) return E_INVALIDARG;
    const LONG dstPitch = pitch >= 0 ? pitch : -pitch;
    if (dstPitch < static_cast<LONG>(width * 4) ||
        len < static_cast<DWORD>(dstPitch) * height)
        return HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);
    (void)rgbMask;

    auto fillBlack = [&]() {
        for (DWORD y = 0; y < height; ++y)
        {
            BYTE* row = pitch >= 0 ? pBuf + static_cast<size_t>(y) * dstPitch
                                   : pBuf + static_cast<size_t>(height - 1 - y) * dstPitch;
            for (DWORD x = 0; x < width; ++x)
            {
                BYTE* d = row + static_cast<size_t>(x) * 4;
                d[0] = 0; d[1] = 0; d[2] = 0; d[3] = 255;
            }
        }
    };
    fillBlack();

    wchar_t base[MAX_PATH]{};
    if (!GetEnvironmentVariableW(L"ProgramData", base, MAX_PATH)) return S_OK;
    const std::wstring dir = std::wstring(base) + L"\\PhoneBridge";
    const std::wstring blackFlag = dir + L"\\camera-black.flag";
    if (GetFileAttributesW(blackFlag.c_str()) != INVALID_FILE_ATTRIBUTES) return S_OK;

    // Frame Server can request samples on more than one worker thread. Give each
    // worker its own persistent mapping/cache so there is no cross-thread cache race.
    static thread_local HANDLE s_file = INVALID_HANDLE_VALUE;
    static thread_local HANDLE s_map = nullptr;
    static thread_local BYTE* s_view = nullptr;
    static thread_local ULONGLONG s_viewBytes = 0;
    static thread_local std::vector<BYTE> s_candidate;
    static thread_local std::vector<BYTE> s_cached;
    static thread_local DWORD s_cachedW = 0;
    static thread_local DWORD s_cachedH = 0;
    static thread_local DWORD s_cachedStride = 0;

    auto ensureMapping = [&]() -> bool {
        if (s_view) return true;
        const std::wstring path = dir + L"\\video.bus";
        s_file = CreateFileW(path.c_str(), GENERIC_READ,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (s_file == INVALID_HANDLE_VALUE) return false;
        LARGE_INTEGER fileSize{};
        if (!GetFileSizeEx(s_file, &fileSize) || fileSize.QuadPart < 28)
        {
            CloseHandle(s_file); s_file = INVALID_HANDLE_VALUE; return false;
        }
        s_map = CreateFileMappingW(s_file, nullptr, PAGE_READONLY, 0, 0, nullptr);
        if (!s_map)
        {
            CloseHandle(s_file); s_file = INVALID_HANDLE_VALUE; return false;
        }
        s_view = static_cast<BYTE*>(MapViewOfFile(s_map, FILE_MAP_READ, 0, 0, 0));
        if (!s_view)
        {
            CloseHandle(s_map); s_map = nullptr;
            CloseHandle(s_file); s_file = INVALID_HANDLE_VALUE;
            return false;
        }
        s_viewBytes = static_cast<ULONGLONG>(fileSize.QuadPart);
        return true;
    };

    if (ensureMapping())
    {
        // Do not wait for an in-progress writer. The cached frame is newer and much
        // smoother than inserting a black frame or blocking the browser sample thread.
        const LONG seq1 = InterlockedCompareExchange(
            reinterpret_cast<volatile LONG*>(s_view), 0, 0);
        if (!(seq1 & 1))
        {
            DWORD srcW = 0, srcH = 0, srcStride = 0, srcBytes = 0;
            memcpy(&srcW, s_view + 4, 4);
            memcpy(&srcH, s_view + 8, 4);
            memcpy(&srcStride, s_view + 12, 4);
            memcpy(&srcBytes, s_view + 16, 4);

            const ULONGLONG minStride = static_cast<ULONGLONG>(srcW) * 4ull;
            const ULONGLONG required = static_cast<ULONGLONG>(srcStride) * srcH;
            const bool valid = srcW && srcH && srcW <= 4096 && srcH <= 4096 &&
                srcStride >= minStride && srcStride <= 65536u &&
                srcBytes >= required && srcBytes <= 256u * 1024u * 1024u &&
                s_viewBytes >= 28ull + srcBytes;

            if (valid)
            {
                s_candidate.resize(srcBytes);
                memcpy(s_candidate.data(), s_view + 28, srcBytes);
                MemoryBarrier();
                const LONG seq2 = InterlockedCompareExchange(
                    reinterpret_cast<volatile LONG*>(s_view), 0, 0);
                if (seq1 == seq2 && !(seq2 & 1))
                {
                    s_cached.swap(s_candidate);
                    s_cachedW = srcW;
                    s_cachedH = srcH;
                    s_cachedStride = srcStride;
                }
            }
        }
    }

    if (s_cached.empty() || !s_cachedW || !s_cachedH || !s_cachedStride) return S_OK;

    // Exact-size fast path: this is the normal 1280x720 landscape or 720x1280
    // portrait case. No scaling is performed, reducing virtual-camera latency.
    if (s_cachedW == width && s_cachedH == height)
    {
        for (DWORD y = 0; y < height; ++y)
        {
            const BYTE* srcRow = s_cached.data() + static_cast<size_t>(y) * s_cachedStride;
            BYTE* dstRow = pitch >= 0 ? pBuf + static_cast<size_t>(y) * dstPitch
                                      : pBuf + static_cast<size_t>(height - 1 - y) * dstPitch;
            memcpy(dstRow, srcRow, static_cast<size_t>(width) * 4);
        }
        return S_OK;
    }

    // FIT_CENTER instead of stretch/crop. If the phone rotates while a browser is
    // holding the old negotiated media type, proportions remain correct immediately;
    // black bars are used until the consuming app reopens/re-negotiates the camera.
    DWORD fitW = width;
    DWORD fitH = static_cast<DWORD>((static_cast<ULONGLONG>(s_cachedH) * width) / s_cachedW);
    if (fitH > height)
    {
        fitH = height;
        fitW = static_cast<DWORD>((static_cast<ULONGLONG>(s_cachedW) * height) / s_cachedH);
    }
    fitW = (fitW == 0) ? 1 : fitW;
    fitH = (fitH == 0) ? 1 : fitH;
    const DWORD offX = (width - fitW) / 2;
    const DWORD offY = (height - fitH) / 2;

    for (DWORD y = 0; y < fitH; ++y)
    {
        const DWORD sy = static_cast<DWORD>((static_cast<ULONGLONG>(y) * s_cachedH) / fitH);
        const BYTE* srcRow = s_cached.data() + static_cast<size_t>(sy) * s_cachedStride;
        const DWORD dy = offY + y;
        BYTE* dstRow = pitch >= 0 ? pBuf + static_cast<size_t>(dy) * dstPitch
                                  : pBuf + static_cast<size_t>(height - 1 - dy) * dstPitch;
        for (DWORD x = 0; x < fitW; ++x)
        {
            const DWORD sx = static_cast<DWORD>((static_cast<ULONGLONG>(x) * s_cachedW) / fitW);
            const BYTE* src = srcRow + static_cast<size_t>(sx) * 4;
            BYTE* dst = dstRow + static_cast<size_t>(offX + x) * 4;
            dst[0] = src[0];
            dst[1] = src[1];
            dst[2] = src[2];
            dst[3] = 255;
        }
    }
    return S_OK;
}
'''

s = replace_cpp_function(s, 'HRESULT SimpleFrameGenerator::_CreateRGB32Frame(', frame_reader)

for marker in [
    'PhoneBridgeFitLatestFrame v1.6',
    'static thread_local BYTE* s_view',
    's_cached.swap(s_candidate)',
    'Exact-size fast path',
    'FIT_CENTER instead of stretch/crop'
]:
    if marker not in s:
        raise RuntimeError('v1.6 frame marker missing: ' + marker)
if 'if (!copied) fillBlack();' in s:
    raise RuntimeError('Regression: v1.5 black-flicker fallback still active')

p.write_text(s, encoding='utf-8')
print('Applied PhoneBridge v1.6 latest-frame cache, FIT_CENTER framing and persistent low-latency bus mapping')
