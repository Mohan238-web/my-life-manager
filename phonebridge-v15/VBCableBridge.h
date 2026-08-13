#pragma once
#include <cstdint>
#include <string>

struct VBCableStatus {
    bool renderFound = false;
    bool renderActive = false;
    std::wstring renderName;
    bool captureFound = false;
    bool captureActive = false;
    std::wstring captureName;
};

VBCableStatus FindVBCable();

class VBCableBridge {
public:
    VBCableBridge();
    ~VBCableBridge();
    VBCableBridge(const VBCableBridge&) = delete;
    VBCableBridge& operator=(const VBCableBridge&) = delete;

    // Network thread: enqueue clean 48 kHz mono PCM. A dedicated event-driven
    // WASAPI thread clocks the data into CABLE Input, so network packet timing
    // never directly controls the Windows audio renderer.
    bool pushMonoPcm16(const int16_t* samples, uint32_t sampleCount, std::wstring* error = nullptr);
    void reset();
    uint32_t bufferedMilliseconds() const;
    uint64_t droppedSamples() const;

private:
    struct Impl;
    Impl* impl_ = nullptr;
};
