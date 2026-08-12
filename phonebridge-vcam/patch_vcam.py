from pathlib import Path
import sys

p = Path(sys.argv[1] if len(sys.argv) > 1 else 'windows-camera/Samples/VirtualCamera/VirtualCameraMediaSource/SimpleFrameGenerator.cpp')
s = p.read_text(encoding='utf-8-sig')


def replace_cpp_function(text: str, signature: str, replacement: str) -> str:
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 0
    end = None
    for i in range(brace, len(text)):
        ch = text[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        raise RuntimeError(f'Could not locate end of {signature}')
    return text[:start] + replacement + text[end:]


frame_reader = r'''HRESULT SimpleFrameGenerator::_CreateRGB32Frame(
    _Inout_updates_bytes_(len) BYTE* pBuf,
    _In_ DWORD len,
    _In_ LONG pitch,
    _In_ DWORD width,
    _In_ DWORD height,
    _In_ ULONG rgbMask )
{
    if (!pBuf) return E_INVALIDARG;
    const LONG dstPitch = pitch >= 0 ? pitch : -pitch;
    if (len < static_cast<DWORD>(dstPitch) * height)
        return HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);
    (void)rgbMask;

    // Frame Server loads this media source as Local Service in Session 0.
    // Use a file-backed mapping under ProgramData so both service and user sessions can access it.
    // Header is packed: seq(4), width(4), height(4), stride(4), bytes(4), timestamp100ns(8).
    static HANDLE s_file = INVALID_HANDLE_VALUE;
    static HANDLE s_map = nullptr;
    static BYTE* s_view = nullptr;
    if (!s_view)
    {
        wchar_t base[MAX_PATH]{};
        if (GetEnvironmentVariableW(L"ProgramData", base, MAX_PATH))
        {
            std::wstring path = std::wstring(base) + L"\\PhoneBridge\\video.bus";
            s_file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (s_file != INVALID_HANDLE_VALUE)
            {
                s_map = CreateFileMappingW(s_file, nullptr, PAGE_READONLY, 0, 0, nullptr);
                if (s_map) s_view = static_cast<BYTE*>(MapViewOfFile(s_map, FILE_MAP_READ, 0, 0, 0));
            }
        }
    }

    auto fillBlack = [&]() {
        for (DWORD y = 0; y < height; ++y)
        {
            BYTE* row = pitch >= 0 ? pBuf + y * dstPitch : pBuf + (height - 1 - y) * dstPitch;
            memset(row, 0, static_cast<size_t>(width) * 4);
        }
    };

    if (!s_view)
    {
        fillBlack();
        return S_OK;
    }

    bool copied = false;
    for (int attempt = 0; attempt < 3 && !copied; ++attempt)
    {
        const LONG seq1 = InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(s_view), 0, 0);
        if (seq1 & 1) { SwitchToThread(); continue; }

        DWORD srcW = 0, srcH = 0, srcStride = 0, srcBytes = 0;
        memcpy(&srcW, s_view + 4, 4);
        memcpy(&srcH, s_view + 8, 4);
        memcpy(&srcStride, s_view + 12, 4);
        memcpy(&srcBytes, s_view + 16, 4);
        if (!srcW || !srcH || srcStride < srcW * 4 || srcBytes < srcStride * srcH)
        {
            fillBlack();
            return S_OK;
        }

        const BYTE* pixels = s_view + 28;
        for (DWORD y = 0; y < height; ++y)
        {
            const DWORD sy = static_cast<DWORD>((static_cast<ULONGLONG>(y) * srcH) / height);
            const BYTE* srcRow = pixels + static_cast<size_t>(sy) * srcStride;
            BYTE* dstRow = pitch >= 0 ? pBuf + static_cast<size_t>(y) * dstPitch
                                      : pBuf + static_cast<size_t>(height - 1 - y) * dstPitch;
            for (DWORD x = 0; x < width; ++x)
            {
                const DWORD sx = static_cast<DWORD>((static_cast<ULONGLONG>(x) * srcW) / width);
                const BYTE* src = srcRow + static_cast<size_t>(sx) * 4;
                BYTE* dst = dstRow + static_cast<size_t>(x) * 4;
                // Shared bus is explicit BGRA: B,G,R,A.
                dst[0] = src[0];
                dst[1] = src[1];
                dst[2] = src[2];
                dst[3] = 255;
            }
        }

        MemoryBarrier();
        const LONG seq2 = InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(s_view), 0, 0);
        copied = (seq1 == seq2 && !(seq2 & 1));
    }

    if (!copied) fillBlack();
    return S_OK;
}
'''

s = replace_cpp_function(s, 'HRESULT SimpleFrameGenerator::_CreateRGB32Frame(', frame_reader)

# Replace Microsoft's sample RGB32 -> NV12 routine. The sample is designed as a demo and
# chooses chroma from individual pixels. PhoneBridge uses a deterministic BT.601 limited-range
# conversion with explicit B/G/R order, 2x2 chroma averaging, and clamping.
color_safe_nv12 = r'''HRESULT SimpleFrameGenerator::RGB32ToNV12Frame(
    _Inout_updates_bytes_(len) BYTE* pbBuff,
    ULONG cbBuff,
    long stride,
    UINT width,
    UINT height,
    BYTE* pbBuffOut,
    ULONG cbBuffOut,
    long strideOut)
{
    // PhoneBridgeColorSafeNV12
    if (!pbBuff || !pbBuffOut || width == 0 || height == 0 || stride <= 0 || strideOut <= 0)
        return E_INVALIDARG;

    const size_t srcRequired = static_cast<size_t>(stride) * height;
    const size_t yBytes = static_cast<size_t>(strideOut) * height;
    const size_t uvRows = (height + 1u) / 2u;
    const size_t dstRequired = yBytes + static_cast<size_t>(strideOut) * uvRows;
    if (srcRequired > cbBuff || dstRequired > cbBuffOut)
        return HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);

    auto clampByte = [](int v) -> BYTE {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return static_cast<BYTE>(v);
    };
    auto yFromRgb = [&](int r, int g, int b) -> BYTE {
        return clampByte(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
    };
    auto uFromRgb = [&](int r, int g, int b) -> BYTE {
        return clampByte(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
    };
    auto vFromRgb = [&](int r, int g, int b) -> BYTE {
        return clampByte(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
    };

    // Y plane. RGB32 in this sample is stored bytewise as B,G,R,X on little-endian Windows.
    for (UINT y = 0; y < height; ++y)
    {
        const BYTE* srcRow = pbBuff + static_cast<size_t>(y) * stride;
        BYTE* yRow = pbBuffOut + static_cast<size_t>(y) * strideOut;
        for (UINT x = 0; x < width; ++x)
        {
            const BYTE* px = srcRow + static_cast<size_t>(x) * 4;
            const int b = px[0];
            const int g = px[1];
            const int r = px[2];
            yRow[x] = yFromRgb(r, g, b);
        }
    }

    // Interleaved UV plane. Average the source RGB of each 2x2 block before conversion.
    BYTE* uvBase = pbBuffOut + yBytes;
    for (UINT y = 0; y < height; y += 2)
    {
        BYTE* uvRow = uvBase + static_cast<size_t>(y / 2) * strideOut;
        for (UINT x = 0; x < width; x += 2)
        {
            int sumR = 0, sumG = 0, sumB = 0, count = 0;
            for (UINT dy = 0; dy < 2 && y + dy < height; ++dy)
            {
                const BYTE* srcRow = pbBuff + static_cast<size_t>(y + dy) * stride;
                for (UINT dx = 0; dx < 2 && x + dx < width; ++dx)
                {
                    const BYTE* px = srcRow + static_cast<size_t>(x + dx) * 4;
                    sumB += px[0];
                    sumG += px[1];
                    sumR += px[2];
                    ++count;
                }
            }
            const int r = sumR / count;
            const int g = sumG / count;
            const int b = sumB / count;
            uvRow[x] = uFromRgb(r, g, b);
            if (x + 1 < static_cast<UINT>(strideOut))
                uvRow[x + 1] = vFromRgb(r, g, b);
        }
    }

    return S_OK;
}
'''

s = replace_cpp_function(s, 'HRESULT SimpleFrameGenerator::RGB32ToNV12Frame(', color_safe_nv12)
p.write_text(s, encoding='utf-8')
print(f'Patched frame bus + color-safe NV12 conversion in {p}')

# Give PhoneBridge its own media-source CLSID rather than reusing Microsoft's sample CLSID.
old_guid = '7B89B92E-FE71-42D0-8A41-E137D06EA184'
new_guid = 'A7318E11-4B4C-4BCC-A19F-FA192BA8BA5D'
old_hex = '0x7b89b92e, 0xfe71, 0x42d0, 0x8a, 0x41, 0xe1, 0x37, 0xd0, 0x6e, 0xa1, 0x84'
new_hex = '0xa7318e11, 0x4b4c, 0x4bcc, 0xa1, 0x9f, 0xfa, 0x19, 0x2b, 0xa8, 0xba, 0x5d'

patched_guid_files = []
for candidate in p.parent.iterdir():
    if not candidate.is_file() or candidate.suffix.lower() not in {'.h', '.hpp', '.cpp', '.c', '.idl', '.def', '.props'}:
        continue
    try:
        text = candidate.read_text(encoding='utf-8-sig')
    except UnicodeDecodeError:
        continue
    original = text
    text = text.replace(old_guid, new_guid)
    text = text.replace(old_guid.lower(), new_guid.lower())
    text = text.replace(old_hex, new_hex)
    if text != original:
        candidate.write_text(text, encoding='utf-8')
        patched_guid_files.append(candidate.name)

required = {'VirtualCameraMediaSource.h', 'VirtualCameraMediaSourceActivate.h'}
missing = sorted(required - set(patched_guid_files))
if missing:
    raise RuntimeError(f'PhoneBridge CLSID patch did not update required files: {missing}')

remaining = []
for candidate in p.parent.iterdir():
    if not candidate.is_file() or candidate.suffix.lower() not in {'.h', '.hpp', '.cpp', '.c', '.idl', '.def', '.props'}:
        continue
    try:
        text = candidate.read_text(encoding='utf-8-sig')
    except UnicodeDecodeError:
        continue
    if old_guid.lower() in text.lower():
        remaining.append(candidate.name)
if remaining:
    raise RuntimeError(f'Old Microsoft virtual-camera CLSID still present in: {remaining}')

print('Patched PhoneBridge CLSID consistently in: ' + ', '.join(sorted(patched_guid_files)))
