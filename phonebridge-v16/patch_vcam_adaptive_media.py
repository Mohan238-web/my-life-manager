from pathlib import Path
import sys

p = Path(sys.argv[1] if len(sys.argv) > 1 else 'windows-camera/Samples/VirtualCamera/VirtualCameraMediaSource/SimpleMediaStream.cpp')
s = p.read_text(encoding='utf-8-sig').replace('\r\n','\n')


def replace_cpp_function(text: str, signature: str, replacement: str) -> str:
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 0
    end = None
    for i in range(brace, len(text)):
        if text[i] == '{': depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        raise RuntimeError('Could not locate function end: ' + signature)
    return text[:start] + replacement + text[end:]

old_defs = '''#define NUM_IMAGE_ROWS 480
#define NUM_IMAGE_COLS 640
#define BYTES_PER_PIXEL 4
#define IMAGE_BUFFER_SIZE_BYTES (NUM_IMAGE_ROWS * NUM_IMAGE_COLS * BYTES_PER_PIXEL)
#define IMAGE_ROW_SIZE_BYTES (NUM_IMAGE_COLS * BYTES_PER_PIXEL)
'''
if old_defs not in s:
    # Allow this patch to run after the previous one-off 720p patch during local testing.
    old_defs = '''// PhoneBridgeHD720 v1.6
// The Microsoft sample advertises only VGA (640x480). PhoneBridge's real sender
// already provides 720p/1080p frames, so expose a proper 16:9 720p virtual camera
// to browsers and recorder applications. Frame rate remains 30 fps.
#define NUM_IMAGE_ROWS 720
#define NUM_IMAGE_COLS 1280
#define BYTES_PER_PIXEL 4
#define IMAGE_BUFFER_SIZE_BYTES (NUM_IMAGE_ROWS * NUM_IMAGE_COLS * BYTES_PER_PIXEL)
#define IMAGE_ROW_SIZE_BYTES (NUM_IMAGE_COLS * BYTES_PER_PIXEL)
'''
if old_defs not in s:
    raise SystemExit('v1.6 adaptive media anchor missing: Microsoft dimension defines')

new_defs = r'''// PhoneBridgeAdaptiveMedia v1.6
// PhoneBridge exposes both landscape 1280x720 and portrait 720x1280 native
// camera formats. The format matching the current phone orientation is placed
// first/default when a browser opens the camera.
#define PB_LANDSCAPE_WIDTH 1280
#define PB_LANDSCAPE_HEIGHT 720
#define PB_PORTRAIT_WIDTH 720
#define PB_PORTRAIT_HEIGHT 1280
#define BYTES_PER_PIXEL 4

static bool PhoneBridgeReadSourceDimensions(UINT32* outW, UINT32* outH)
{
    if (!outW || !outH) return false;
    *outW = 0; *outH = 0;
    wchar_t base[MAX_PATH]{};
    if (!GetEnvironmentVariableW(L"ProgramData", base, MAX_PATH)) return false;
    const std::wstring path = std::wstring(base) + L"\\PhoneBridge\\video.bus";
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) return false;

    BYTE header[20]{};
    DWORD got = 0;
    bool ok = false;
    for (int attempt = 0; attempt < 3 && !ok; ++attempt)
    {
        LARGE_INTEGER zero{};
        SetFilePointerEx(file, zero, nullptr, FILE_BEGIN);
        if (!ReadFile(file, header, sizeof(header), &got, nullptr) || got != sizeof(header)) break;
        LONG seq = 0; DWORD w = 0, h = 0, stride = 0, bytes = 0;
        memcpy(&seq, header + 0, 4);
        memcpy(&w, header + 4, 4);
        memcpy(&h, header + 8, 4);
        memcpy(&stride, header + 12, 4);
        memcpy(&bytes, header + 16, 4);
        if (seq & 1) { SwitchToThread(); continue; }
        if (w && h && w <= 4096 && h <= 4096 && stride >= w * 4u &&
            bytes >= static_cast<ULONGLONG>(stride) * h)
        {
            *outW = w; *outH = h; ok = true;
        }
    }
    CloseHandle(file);
    return ok;
}
'''
s = s.replace(old_defs, new_defs, 1)

initialize = r'''HRESULT SimpleMediaStream::Initialize(
            _In_ SimpleMediaSource* pSource,
            _In_ DWORD dwStreamId,
            _In_ MFSampleAllocatorUsage allocatorUsage
        )
    {
        winrt::slim_lock_guard lock(m_Lock);

        wil::com_ptr_nothrow<IMFMediaTypeHandler> spTypeHandler;
        wil::com_ptr_nothrow<IMFAttributes> attrs;

        RETURN_HR_IF_NULL(E_INVALIDARG, pSource);
        m_parent = pSource;
        m_dwStreamId = dwStreamId;
        m_allocatorUsage = allocatorUsage;

        UINT32 sourceW = 0, sourceH = 0;
        const bool sourceKnown = PhoneBridgeReadSourceDimensions(&sourceW, &sourceH);
        const bool portrait = sourceKnown && sourceH > sourceW;
        const UINT32 preferredW = portrait ? PB_PORTRAIT_WIDTH : PB_LANDSCAPE_WIDTH;
        const UINT32 preferredH = portrait ? PB_PORTRAIT_HEIGHT : PB_LANDSCAPE_HEIGHT;
        const UINT32 alternateW = portrait ? PB_LANDSCAPE_WIDTH : PB_PORTRAIT_WIDTH;
        const UINT32 alternateH = portrait ? PB_LANDSCAPE_HEIGHT : PB_PORTRAIT_HEIGHT;

        // PhoneBridge v1.6 exposes both orientations in both NV12 and RGB32. The
        // current phone orientation is first so typical browser/native selection
        // opens directly at 1280x720 landscape or 720x1280 portrait.
        const uint32_t NUM_MEDIATYPES = 4;
        wil::unique_cotaskmem_array_ptr<wil::com_ptr_nothrow<IMFMediaType>> mediaTypeList =
            wilEx::make_unique_cotaskmem_array<wil::com_ptr_nothrow<IMFMediaType>>(NUM_MEDIATYPES);

        auto makeType = [&](REFGUID subtype, UINT32 w, UINT32 h, bool nv12, uint32_t index) -> HRESULT
        {
            wil::com_ptr_nothrow<IMFMediaType> type;
            RETURN_IF_FAILED(MFCreateMediaType(&type));
            RETURN_IF_FAILED(type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
            RETURN_IF_FAILED(type->SetGUID(MF_MT_SUBTYPE, subtype));
            RETURN_IF_FAILED(type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
            RETURN_IF_FAILED(type->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE));
            RETURN_IF_FAILED(MFSetAttributeSize(type.get(), MF_MT_FRAME_SIZE, w, h));
            RETURN_IF_FAILED(MFSetAttributeRatio(type.get(), MF_MT_FRAME_RATE, 30, 1));
            const uint64_t bytesPerFrame = nv12
                ? (static_cast<uint64_t>(w) * h * 3ull) / 2ull
                : static_cast<uint64_t>(w) * h * 4ull;
            const uint64_t bitRate = bytesPerFrame * 8ull * 30ull;
            RETURN_IF_FAILED(type->SetUINT32(MF_MT_AVG_BITRATE,
                static_cast<UINT32>(bitRate > 0xffffffffull ? 0xffffffffull : bitRate)));
            RETURN_IF_FAILED(MFSetAttributeRatio(type.get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
            mediaTypeList[index] = type.detach();
            return S_OK;
        };

        RETURN_IF_FAILED(makeType(MFVideoFormat_NV12, preferredW, preferredH, true, 0));
        RETURN_IF_FAILED(makeType(MFVideoFormat_NV12, alternateW, alternateH, true, 1));
        RETURN_IF_FAILED(makeType(MFVideoFormat_RGB32, preferredW, preferredH, false, 2));
        RETURN_IF_FAILED(makeType(MFVideoFormat_RGB32, alternateW, alternateH, false, 3));

        RETURN_IF_FAILED(MFCreateAttributes(&m_spAttributes, 10));
        RETURN_IF_FAILED(_SetStreamAttributes(m_spAttributes.get()));
        RETURN_IF_FAILED(MFCreateEventQueue(&m_spEventQueue));
        RETURN_IF_FAILED(MFCreateStreamDescriptor(m_dwStreamId, NUM_MEDIATYPES,
            mediaTypeList.get(), &m_spStreamDesc));
        RETURN_IF_FAILED(m_spStreamDesc->GetMediaTypeHandler(&spTypeHandler));
        RETURN_IF_FAILED(spTypeHandler->SetCurrentMediaType(mediaTypeList[0]));
        RETURN_IF_FAILED(_SetStreamDescriptorAttributes(m_spStreamDesc.get()));
        return S_OK;
    }'''

s = replace_cpp_function(s, 'HRESULT SimpleMediaStream::Initialize(', initialize)

for marker in [
    'PhoneBridgeAdaptiveMedia v1.6',
    'PB_LANDSCAPE_WIDTH 1280',
    'PB_PORTRAIT_HEIGHT 1280',
    'PhoneBridgeReadSourceDimensions',
    'const uint32_t NUM_MEDIATYPES = 4',
    'preferredW, preferredH',
    'alternateW, alternateH'
]:
    if marker not in s:
        raise SystemExit('v1.6 adaptive media marker missing: ' + marker)
if '#define NUM_IMAGE_ROWS 480' in s or '#define NUM_IMAGE_COLS 640' in s:
    raise SystemExit('Regression: VGA-only media type remains')

p.write_text(s, encoding='utf-8', newline='\n')
print('Applied PhoneBridge v1.6 landscape/portrait native media types with phone-orientation preference')
