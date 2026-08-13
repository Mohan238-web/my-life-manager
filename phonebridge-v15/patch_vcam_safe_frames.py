from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig')

if '#include <vector>' not in s:
    insert_at=s.find('\n', s.find('#include'))
    # Put the include after the first include; the exact sample include list varies.
    s=s[:insert_at+1]+'#include <vector>\n'+s[insert_at+1:]

def replace_cpp_function(text: str, signature: str, replacement: str) -> str:
    start=text.index(signature)
    brace=text.index('{',start)
    depth=0
    end=None
    for i in range(brace,len(text)):
        ch=text[i]
        if ch=='{': depth+=1
        elif ch=='}':
            depth-=1
            if depth==0:
                end=i+1
                break
    if end is None:
        raise RuntimeError('Could not locate function end: '+signature)
    return text[:start]+replacement+text[end:]

safe_reader=r'''HRESULT SimpleFrameGenerator::_CreateRGB32Frame(
    _Inout_updates_bytes_(len) BYTE* pBuf,
    _In_ DWORD len,
    _In_ LONG pitch,
    _In_ DWORD width,
    _In_ DWORD height,
    _In_ ULONG rgbMask )
{
    if (!pBuf || width == 0 || height == 0) return E_INVALIDARG;
    const LONG dstPitch = pitch >= 0 ? pitch : -pitch;
    if (dstPitch < static_cast<LONG>(width * 4) ||
        len < static_cast<DWORD>(dstPitch) * height)
        return HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);
    (void)rgbMask;

    // PhoneBridgeSafeFrameReader v1.5
    // Always begin from a valid opaque-black RGB32 frame. Every failure in the
    // cross-process bus path therefore degrades to black instead of terminating
    // Windows Camera Frame Server.
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

    // Diagnostic mode used by PhoneBridgeCameraProbe /black. It deliberately
    // bypasses video.bus and proves the Media Foundation source can produce a
    // frame without any cross-process data dependency.
    const std::wstring blackFlag = dir + L"\\camera-black.flag";
    if (GetFileAttributesW(blackFlag.c_str()) != INVALID_FILE_ATTRIBUTES) return S_OK;

    const std::wstring path = dir + L"\\video.bus";
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) return S_OK;

    LARGE_INTEGER fileSize{};
    if (!GetFileSizeEx(file, &fileSize) || fileSize.QuadPart < 28)
    {
        CloseHandle(file);
        return S_OK;
    }

    HANDLE mapping = CreateFileMappingW(file, nullptr, PAGE_READONLY, 0, 0, nullptr);
    if (!mapping)
    {
        CloseHandle(file);
        return S_OK;
    }
    BYTE* view = static_cast<BYTE*>(MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 0));
    if (!view)
    {
        CloseHandle(mapping);
        CloseHandle(file);
        return S_OK;
    }

    bool copied = false;
    do
    {
        DWORD seq1 = 0, srcW = 0, srcH = 0, srcStride = 0, srcBytes = 0;
        memcpy(&seq1, view + 0, 4);
        if (seq1 & 1u) break; // writer is in progress; black is safer than a torn frame.
        memcpy(&srcW, view + 4, 4);
        memcpy(&srcH, view + 8, 4);
        memcpy(&srcStride, view + 12, 4);
        memcpy(&srcBytes, view + 16, 4);

        // Strict validation before any pixel dereference. Limit dimensions and
        // allocation size so corrupt/stale headers cannot crash Frame Server.
        if (!srcW || !srcH || srcW > 4096 || srcH > 4096) break;
        const ULONGLONG minStride = static_cast<ULONGLONG>(srcW) * 4ull;
        const ULONGLONG required = static_cast<ULONGLONG>(srcStride) * srcH;
        if (srcStride < minStride || srcStride > 65536u) break;
        if (srcBytes < required || srcBytes > 256u * 1024u * 1024u) break;
        if (fileSize.QuadPart < static_cast<LONGLONG>(28ull + srcBytes)) break;

        // Copy the complete frame into private memory first. The receiver may
        // update video.bus immediately afterwards, but Frame Server never keeps
        // a raw pointer into that concurrently-written mapping.
        std::vector<BYTE> local(srcBytes);
        memcpy(local.data(), view + 28, srcBytes);
        MemoryBarrier();
        DWORD seq2 = 0;
        memcpy(&seq2, view + 0, 4);
        if (seq1 != seq2 || (seq2 & 1u)) break;

        for (DWORD y = 0; y < height; ++y)
        {
            const DWORD sy = static_cast<DWORD>((static_cast<ULONGLONG>(y) * srcH) / height);
            const BYTE* srcRow = local.data() + static_cast<size_t>(sy) * srcStride;
            BYTE* dstRow = pitch >= 0 ? pBuf + static_cast<size_t>(y) * dstPitch
                                      : pBuf + static_cast<size_t>(height - 1 - y) * dstPitch;
            for (DWORD x = 0; x < width; ++x)
            {
                const DWORD sx = static_cast<DWORD>((static_cast<ULONGLONG>(x) * srcW) / width);
                const BYTE* src = srcRow + static_cast<size_t>(sx) * 4;
                BYTE* dst = dstRow + static_cast<size_t>(x) * 4;
                dst[0] = src[0]; // B
                dst[1] = src[1]; // G
                dst[2] = src[2]; // R
                dst[3] = 255;
            }
        }
        copied = true;
    } while (false);

    UnmapViewOfFile(view);
    CloseHandle(mapping);
    CloseHandle(file);
    (void)copied; // black remains a valid fallback when no safe live frame was copied.
    return S_OK;
}
'''

s=replace_cpp_function(s,'HRESULT SimpleFrameGenerator::_CreateRGB32Frame(',safe_reader)

for marker in ['PhoneBridgeSafeFrameReader v1.5','camera-black.flag','std::vector<BYTE> local','FILE_SHARE_DELETE','srcBytes > 256u * 1024u * 1024u']:
    if marker not in s:
        raise RuntimeError('Safe frame marker missing: '+marker)

p.write_text(s,encoding='utf-8')
print('Applied PhoneBridge v1.5 crash-safe Frame Server bus reader')
