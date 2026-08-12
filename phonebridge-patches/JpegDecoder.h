#pragma once
#include <objbase.h>
#include <cstddef>
#include <cstdint>
#include <vector>

class JpegDecoder {
public:
    JpegDecoder();
    ~JpegDecoder();
    JpegDecoder(const JpegDecoder&) = delete;
    JpegDecoder& operator=(const JpegDecoder&) = delete;

    bool init();
    bool decodeToBgra(const uint8_t* data, size_t size,
                      std::vector<uint8_t>& out,
                      uint32_t& w, uint32_t& h, uint32_t& stride);
private:
    void* handle_ = nullptr;
};
