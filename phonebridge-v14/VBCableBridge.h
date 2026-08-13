#pragma once
#include <cstdint>
#include <string>

struct VBCableStatus {
    bool renderFound = false;
    bool renderActive = false;
    bool captureFound = false;
    bool captureActive = false;
    std::wstring renderName;
    std::wstring captureName;
};

VBCableStatus FindVBCable();

class VBCableBridge {
public:
    VBCableBridge();
    ~VBCableBridge();
    VBCableBridge(const VBCableBridge&) = delete;
    VBCableBridge& operator=(const VBCableBridge&) = delete;

    bool pushMonoPcm16(const int16_t* samples, uint32_t sampleCount, std::wstring* error = nullptr);
    void reset();

private:
    bool ensure(std::wstring* error);
    struct Impl;
    Impl* impl_ = nullptr;
};
