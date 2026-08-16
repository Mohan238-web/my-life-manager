#pragma once
#include <atomic>
#include <cstdint>
#include <deque>
#include <vector>

class NoiseCleaner {
public:
    enum Mode { Off=0, Natural=1, Strong=2 };
    enum Activity { Disabled=0, Learning=1, Voice=2, NoiseReady=3 };

    NoiseCleaner();
    void setMode(int mode);
    int mode() const { return desiredMode_.load(); }
    void process(const int16_t* input, uint32_t count, std::vector<int16_t>& output);
    Activity activity() const { return (Activity)activity_.load(); }
    float noiseDb() const { return noiseDb_.load(); }
    float inputDb() const { return inputDb_.load(); }

private:
    static constexpr int N=512;
    static constexpr int H=N/2;
    std::atomic<int> desiredMode_{Off};
    int activeMode_=Off;
    std::atomic<int> activity_{Disabled};
    std::atomic<float> noiseDb_{-70.0f};
    std::atomic<float> inputDb_{-70.0f};
    std::vector<float> inputFifo_;
    std::deque<float> outputFifo_;
    std::vector<float> overlap_;
    std::vector<float> noisePower_;
    std::vector<float> prevGain_;
    float noiseRms_=0.0025f;
    int quietFrames_=0;
    int voiceHang_=0;

    void resetState();
    void processFrame(const float* frame);
};
