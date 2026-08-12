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

    // The PhoneBridge receiver owns this session-local shared memory mapping.
    // Header is packed: seq(4), width(4), height(4), stride(4), bytes(4), timestamp100ns(8).
    static HANDLE s_map = nullptr;
    static BYTE* s_view = nullptr;
    if (!s_view)
    {
        s_map = OpenFileMappingW(FILE_MAP_READ, FALSE, L"Local\\PhoneBridgeVideo");
        if (s_map) s_view = static_cast<BYTE*>(MapViewOfFile(s_map, FILE_MAP_READ, 0, 0, 0));
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
