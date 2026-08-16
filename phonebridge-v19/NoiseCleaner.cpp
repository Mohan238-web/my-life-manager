#include "NoiseCleaner.h"
#include <algorithm>
#include <cmath>
#include <complex>

namespace {
constexpr float kPi=3.14159265358979323846f;
static void fft(std::vector<std::complex<float>>& a,bool inverse){
    const int n=(int)a.size();
    for(int i=1,j=0;i<n;i++){
        int bit=n>>1; for(;j&bit;bit>>=1) j^=bit; j^=bit;
        if(i<j) std::swap(a[i],a[j]);
    }
    for(int len=2;len<=n;len<<=1){
        float ang=2.0f*kPi/len*(inverse?1.0f:-1.0f);
        std::complex<float> wlen(std::cos(ang),std::sin(ang));
        for(int i=0;i<n;i+=len){
            std::complex<float> w(1.0f,0.0f);
            for(int j=0;j<len/2;j++){
                auto u=a[i+j],v=a[i+j+len/2]*w;
                a[i+j]=u+v; a[i+j+len/2]=u-v; w*=wlen;
            }
        }
    }
    if(inverse) for(auto& x:a) x/=(float)n;
}
static float dbfs(float rms){ return 20.0f*std::log10(std::max(rms,1.0e-7f)); }
}

NoiseCleaner::NoiseCleaner(){ resetState(); }
void NoiseCleaner::setMode(int mode){ desiredMode_=std::clamp(mode,0,2); }
void NoiseCleaner::resetState(){
    inputFifo_.clear(); outputFifo_.clear(); overlap_.assign(N,0.0f);
    noisePower_.assign(N/2+1,1.0e-6f); prevGain_.assign(N/2+1,1.0f);
    noiseRms_=0.015f; quietFrames_=0; voiceHang_=0;
    activity_=(activeMode_==Off?Disabled:Learning); noiseDb_=-36.5f; inputDb_=-70.0f;
}

void NoiseCleaner::process(const int16_t* input,uint32_t count,std::vector<int16_t>& output){
    int wanted=desiredMode_.load();
    if(wanted!=activeMode_){ activeMode_=wanted; resetState(); }
    output.resize(count);
    if(activeMode_==Off){
        std::copy(input,input+count,output.begin()); activity_=Disabled;
        if(count){ long double s=0; for(uint32_t i=0;i<count;i++){ float x=input[i]/32768.0f; s+=x*x; } inputDb_=dbfs(std::sqrt((float)(s/count))); }
        return;
    }
    inputFifo_.reserve(inputFifo_.size()+count);
    for(uint32_t i=0;i<count;i++) inputFifo_.push_back(input[i]/32768.0f);
    while((int)inputFifo_.size()>=N){
        processFrame(inputFifo_.data());
        inputFifo_.erase(inputFifo_.begin(),inputFifo_.begin()+H);
    }
    for(uint32_t i=0;i<count;i++){
        float y=0.0f;
        if(!outputFifo_.empty()){ y=outputFifo_.front(); outputFifo_.pop_front(); }
        y=std::clamp(y,-1.0f,1.0f);
        output[i]=(int16_t)std::clamp((int)std::lround(y*32767.0f),-32768,32767);
    }
}

void NoiseCleaner::processFrame(const float* frame){
    std::vector<std::complex<float>> spec(N);
    long double rmsSum=0.0;
    for(int i=0;i<N;i++){
        float x=frame[i]; rmsSum+=(long double)x*x;
        float w=std::sqrt(std::max(0.0f,0.5f-0.5f*std::cos(2.0f*kPi*i/(N-1))));
        spec[i]=std::complex<float>(x*w,0.0f);
    }
    float rms=std::sqrt((float)(rmsSum/N)); inputDb_=dbfs(rms);
    fft(spec,false);

    float threshold=std::max(noiseRms_*2.6f,0.0040f);
    bool voice=rms>threshold;
    // The first short quiet gap is calibration. This matches the intended PhoneBridge
    // workflow: leave a brief silence before speaking so steady fan/AC noise is learned.
    if(quietFrames_<8 && rms<0.05f) voice=false;
    if(voice) voiceHang_=8; else if(voiceHang_>0){ --voiceHang_; voice=true; }
    bool learn=!voice;
    if(learn){
        float rate=quietFrames_<24?0.16f:0.035f;
        noiseRms_=(1.0f-rate)*noiseRms_+rate*rms;
        ++quietFrames_;
    }
    noiseDb_=dbfs(noiseRms_);

    const float floorVoice=(activeMode_==Natural?0.38f:0.16f);
    const float floorQuiet=(activeMode_==Natural?0.16f:0.045f);
    const float exponent=(activeMode_==Natural?0.72f:0.92f);
    for(int k=0;k<=N/2;k++){
        float p=std::norm(spec[k])+1.0e-10f;
        if(learn){
            float a=quietFrames_<24?0.18f:0.04f;
            noisePower_[k]=(1.0f-a)*noisePower_[k]+a*p;
        }
        float snr=std::max(p-noisePower_[k],0.0f)/(noisePower_[k]+1.0e-10f);
        float wiener=snr/(snr+1.0f);
        float floor=voice?floorVoice:floorQuiet;
        float g=floor+(1.0f-floor)*std::pow(std::clamp(wiener,0.0f,1.0f),exponent);
        float attack=voice?0.34f:0.20f;
        g=attack*g+(1.0f-attack)*prevGain_[k];
        prevGain_[k]=g;
        spec[k]*=g;
        if(k>0 && k<N/2) spec[N-k]=std::conj(spec[k]);
    }
    fft(spec,true);
    for(int i=0;i<N;i++){
        float w=std::sqrt(std::max(0.0f,0.5f-0.5f*std::cos(2.0f*kPi*i/(N-1))));
        overlap_[i]+=spec[i].real()*w;
    }
    for(int i=0;i<H;i++) outputFifo_.push_back(overlap_[i]);
    std::move(overlap_.begin()+H,overlap_.end(),overlap_.begin());
    std::fill(overlap_.end()-H,overlap_.end(),0.0f);
    if(quietFrames_<8) activity_=Learning;
    else activity_=voice?Voice:NoiseReady;
}
