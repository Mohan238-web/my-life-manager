#pragma once
#include <cstdint>
#include <string>

struct StereoMixStatus {
    bool found = false;
    bool active = false;
    std::wstring name;
};

StereoMixStatus FindStereoMixCapture();

class StereoMixBridge {
public:
    StereoMixBridge();
    ~StereoMixBridge();
    StereoMixBridge(const StereoMixBridge&) = delete;
    StereoMixBridge& operator=(const StereoMixBridge&) = delete;

    bool pushMonoPcm16(const int16_t* samples, uint32_t sampleCount, std::wstring* error = nullptr);
    void reset();

private:
    bool ensure(std::wstring* error);

    struct Impl;
    Impl* impl_ = nullptr;
};
