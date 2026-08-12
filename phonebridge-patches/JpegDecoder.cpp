#include "JpegDecoder.h"
#include <shlwapi.h>
#include <cstring>
#pragma comment(lib,"windowscodecs.lib")
#pragma comment(lib,"shlwapi.lib")

JpegDecoder::JpegDecoder() = default;
JpegDecoder::~JpegDecoder(){ if(factory_) factory_->Release(); }

bool JpegDecoder::init(){
    return SUCCEEDED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                      IID_PPV_ARGS(&factory_)));
}

bool JpegDecoder::decodeToBgra(const uint8_t* data, size_t size,
                               std::vector<uint8_t>& out,
                               uint32_t& w, uint32_t& h, uint32_t& stride){
    if(!factory_ || !data || size == 0 || size > UINT_MAX) return false;

    HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, size);
    if(!mem) return false;
    void* p = GlobalLock(mem);
    if(!p){ GlobalFree(mem); return false; }
    memcpy(p, data, size);
    GlobalUnlock(mem);

    IStream* stream = nullptr;
    if(FAILED(CreateStreamOnHGlobal(mem, TRUE, &stream))){
        GlobalFree(mem);
        return false;
    }

    IWICBitmapDecoder* decoder = nullptr;
    HRESULT hr = factory_->CreateDecoderFromStream(stream, nullptr,
                                                    WICDecodeMetadataCacheOnLoad, &decoder);
    stream->Release();
    if(FAILED(hr)) return false;

    IWICBitmapFrameDecode* frame = nullptr;
    hr = decoder->GetFrame(0, &frame);
    decoder->Release();
    if(FAILED(hr)) return false;

    UINT uw = 0, uh = 0;
    hr = frame->GetSize(&uw, &uh);
    if(FAILED(hr) || !uw || !uh){ frame->Release(); return false; }

    // Decode to a no-alpha, byte-defined Windows format first. This avoids any
    // ambiguity around premultiplied alpha/channel interpretation in the preview
    // and in the virtual-camera shared bus.
    IWICFormatConverter* converter = nullptr;
    hr = factory_->CreateFormatConverter(&converter);
    if(SUCCEEDED(hr)){
        hr = converter->Initialize(frame,
                                   GUID_WICPixelFormat24bppBGR,
                                   WICBitmapDitherTypeNone,
                                   nullptr,
                                   0.0,
                                   WICBitmapPaletteTypeCustom);
    }
    frame->Release();
    if(FAILED(hr)){
        if(converter) converter->Release();
        return false;
    }

    const uint64_t bgrStride64 = static_cast<uint64_t>(uw) * 3ull;
    const uint64_t bgrBytes64 = bgrStride64 * uh;
    const uint64_t bgraStride64 = static_cast<uint64_t>(uw) * 4ull;
    const uint64_t bgraBytes64 = bgraStride64 * uh;
    if(bgrStride64 > UINT_MAX || bgrBytes64 > UINT_MAX || bgraBytes64 > SIZE_MAX){
        converter->Release();
        return false;
    }

    const UINT bgrStride = static_cast<UINT>(bgrStride64);
    std::vector<uint8_t> bgr(static_cast<size_t>(bgrBytes64));
    hr = converter->CopyPixels(nullptr, bgrStride, static_cast<UINT>(bgr.size()), bgr.data());
    converter->Release();
    if(FAILED(hr)) return false;

    w = uw;
    h = uh;
    stride = static_cast<uint32_t>(bgraStride64);
    out.resize(static_cast<size_t>(bgraBytes64));

    for(uint32_t y = 0; y < h; ++y){
        const uint8_t* src = bgr.data() + static_cast<size_t>(y) * bgrStride;
        uint8_t* dst = out.data() + static_cast<size_t>(y) * stride;
        for(uint32_t x = 0; x < w; ++x){
            dst[x * 4 + 0] = src[x * 3 + 0]; // B
            dst[x * 4 + 1] = src[x * 3 + 1]; // G
            dst[x * 4 + 2] = src[x * 3 + 2]; // R
            dst[x * 4 + 3] = 255;            // A
        }
    }
    return true;
}
