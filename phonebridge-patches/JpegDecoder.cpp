#include "JpegDecoder.h"
#include <turbojpeg.h>
#include <climits>
#include <limits>

JpegDecoder::JpegDecoder() = default;

JpegDecoder::~JpegDecoder(){
    if(handle_){
        tjDestroy(reinterpret_cast<tjhandle>(handle_));
        handle_ = nullptr;
    }
}

bool JpegDecoder::init(){
    if(handle_) return true;
    handle_ = tjInitDecompress();
    return handle_ != nullptr;
}

bool JpegDecoder::decodeToBgra(const uint8_t* data, size_t size,
                               std::vector<uint8_t>& out,
                               uint32_t& w, uint32_t& h, uint32_t& stride){
    if(!handle_ || !data || size == 0 || size > static_cast<size_t>(ULONG_MAX)) return false;

    int iw = 0, ih = 0, subsamp = 0, colorspace = 0;
    if(tjDecompressHeader3(reinterpret_cast<tjhandle>(handle_),
                           data, static_cast<unsigned long>(size),
                           &iw, &ih, &subsamp, &colorspace) != 0) return false;
    if(iw <= 0 || ih <= 0) return false;

    const uint64_t stride64 = static_cast<uint64_t>(iw) * 4ull;
    const uint64_t bytes64 = stride64 * static_cast<uint64_t>(ih);
    if(stride64 > UINT32_MAX || bytes64 > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) return false;

    std::vector<uint8_t> decoded(static_cast<size_t>(bytes64));
    if(tjDecompress2(reinterpret_cast<tjhandle>(handle_),
                     data, static_cast<unsigned long>(size),
                     decoded.data(), iw, static_cast<int>(stride64), ih,
                     TJPF_BGRA, TJFLAG_FASTDCT) != 0) return false;

    // TurboJPEG's TJPF_BGRA is byte-defined B,G,R,A. Force alpha to 255 so
    // both GDI preview and the virtual-camera bus see an opaque top-down frame.
    for(size_t i = 3; i < decoded.size(); i += 4) decoded[i] = 255;

    w = static_cast<uint32_t>(iw);
    h = static_cast<uint32_t>(ih);
    stride = static_cast<uint32_t>(stride64);
    out.swap(decoded);
    return true;
}
