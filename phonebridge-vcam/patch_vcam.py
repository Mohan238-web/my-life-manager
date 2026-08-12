from pathlib import Path
import sys

p = Path(sys.argv[1] if len(sys.argv) > 1 else 'windows-camera/Samples/VirtualCamera/VirtualCameraMediaSource/SimpleFrameGenerator.cpp')
s = p.read_text(encoding='utf-8-sig')
start = s.index('HRESULT SimpleFrameGenerator::_CreateRGB32Frame(')
marker = '//////////////////////////////////////////////////\n// pixelFormatConverter'
end = s.index(marker, start)
replacement = r'''HRESULT SimpleFrameGenerator::_CreateRGB32Frame(
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
                memcpy(dstRow + static_cast<size_t>(x) * 4, srcRow + static_cast<size_t>(sx) * 4, 4);
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
s = s[:start] + replacement + s[end:]
p.write_text(s, encoding='utf-8')
print(f'Patched {p}')

# Give PhoneBridge its own media-source CLSID rather than reusing Microsoft's sample CLSID.
header = p.parent / 'VirtualCameraMediaSource.h'
hs = header.read_text(encoding='utf-8-sig')
hs = hs.replace('0x7b89b92e, 0xfe71, 0x42d0, 0x8a, 0x41, 0xe1, 0x37, 0xd0, 0x6e, 0xa1, 0x84',
                '0xa7318e11, 0x4b4c, 0x4bcc, 0xa1, 0x9f, 0xfa, 0x19, 0x2b, 0xa8, 0xba, 0x5d')
hs = hs.replace('{7B89B92E-FE71-42D0-8A41-E137D06EA184}', '{A7318E11-4B4C-4BCC-A19F-FA192BA8BA5D}')
header.write_text(hs, encoding='utf-8')
print(f'Patched PhoneBridge CLSID in {header}')
