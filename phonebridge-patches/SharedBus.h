#pragma once
#include <windows.h>
#include <cstdint>
#include <string>

class SharedFrameBus {
public:
    SharedFrameBus();
    ~SharedFrameBus();
    bool openOrCreate(const wchar_t* name=L"Local\\PhoneBridgeVideo");
    bool writeBgra(const void* pixels, uint32_t width, uint32_t height, uint32_t stride, uint64_t timestamp100ns);
private:
    HANDLE file_=INVALID_HANDLE_VALUE;
    HANDLE map_=nullptr;
    uint8_t* view_=nullptr;
    static constexpr size_t kCapacity = 3840ull*2160ull*4ull + 4096;
};

class SharedAudioBus {
public:
    SharedAudioBus();
    ~SharedAudioBus();
    bool openOrCreate(const wchar_t* name=L"Local\\PhoneBridgeAudio");
    bool writePcm16(const int16_t* samples, uint32_t sampleCount, uint32_t sampleRate, uint32_t channels);
private:
    HANDLE file_=INVALID_HANDLE_VALUE;
    HANDLE map_=nullptr;
    uint8_t* view_=nullptr;
    static constexpr size_t kCapacity = 2ull*1024ull*1024ull;
};
