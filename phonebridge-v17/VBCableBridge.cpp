#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include "VBCableBridge.h"
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <propvarutil.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cwctype>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

namespace {
constexpr uint32_t kRate = 48000;
constexpr size_t kPrefillSamples = kRate * 180 / 1000;   // 180 ms initial safety fill.
constexpr size_t kLowWaterSamples = kRate * 90 / 1000;
constexpr size_t kHighWaterSamples = kRate * 300 / 1000;
constexpr size_t kMaxQueueSamples = kRate;                // 1 second hard safety cap.
constexpr uint32_t kConcealFrames = kRate * 12 / 1000;   // 12 ms click-free fade on a short gap.
constexpr uint32_t kRebufferFrames = kRate * 80 / 1000;  // only rebuffer after a real outage.

std::wstring lower(std::wstring s){
    std::transform(s.begin(),s.end(),s.begin(),[](wchar_t c){ return (wchar_t)towlower(c); });
    return s;
}
std::wstring friendlyName(IMMDevice* dev){
    if(!dev) return {};
    IPropertyStore* props=nullptr; std::wstring name;
    if(SUCCEEDED(dev->OpenPropertyStore(STGM_READ,&props)) && props){
        PROPVARIANT pv; PropVariantInit(&pv);
        if(SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName,&pv)) && pv.vt==VT_LPWSTR && pv.pwszVal) name=pv.pwszVal;
        PropVariantClear(&pv); props->Release();
    }
    return name;
}
bool looksLikeCableInput(const std::wstring& name){
    auto l=lower(name);
    return l.find(L"cable input")!=std::wstring::npos ||
           (l.find(L"vb-audio")!=std::wstring::npos && l.find(L"input")!=std::wstring::npos);
}
bool looksLikeCableOutput(const std::wstring& name){
    auto l=lower(name);
    return l.find(L"cable output")!=std::wstring::npos ||
           (l.find(L"vb-audio")!=std::wstring::npos && l.find(L"output")!=std::wstring::npos);
}
IMMDevice* findEndpoint(IMMDeviceEnumerator* en,EDataFlow flow,bool wantInput,std::wstring* nameOut,DWORD* stateOut){
    if(!en) return nullptr;
    IMMDeviceCollection* col=nullptr;
    if(FAILED(en->EnumAudioEndpoints(flow,DEVICE_STATEMASK_ALL,&col)) || !col) return nullptr;
    UINT count=0; col->GetCount(&count); IMMDevice* result=nullptr;
    for(UINT i=0;i<count;i++){
        IMMDevice* dev=nullptr; if(FAILED(col->Item(i,&dev)) || !dev) continue;
        std::wstring name=friendlyName(dev);
        bool match=wantInput?looksLikeCableInput(name):looksLikeCableOutput(name);
        if(match){
            DWORD state=0; dev->GetState(&state);
            if(nameOut) *nameOut=name; if(stateOut) *stateOut=state;
            result=dev; break;
        }
        dev->Release();
    }
    col->Release(); return result;
}

struct VoiceProcessor {
    float hpPrevX=0.0f, hpPrevY=0.0f, presenceLp=0.0f;
    int16_t process(int16_t raw){
        float x=(float)raw/32768.0f;
        constexpr float hpAlpha=0.989636f; // ~80 Hz high-pass at 48 kHz.
        float hp=hpAlpha*(hpPrevY + x - hpPrevX);
        hpPrevX=x; hpPrevY=hp;

        constexpr float lpK=0.25f;
        presenceLp += lpK*(hp-presenceLp);
        // v1.7: +12 dB nominal voice gain. The following compressor/limiter keeps
        // peaks safe while giving normal conversation substantially more level.
        float y=(hp + 0.12f*(hp-presenceLp))*3.981072f;

        // Gentler 2:1 compression above about -13 dBFS; preserve natural modulation.
        float a=std::fabs(y); constexpr float threshold=0.22f;
        if(a>threshold){
            float compressed=threshold+(a-threshold)/2.0f;
            y=std::copysign(compressed,y);
        }
        y=std::clamp(y,-0.891251f,0.891251f); // -1 dBFS limiter.
        int v=(int)std::lround(y*32767.0f);
        return (int16_t)std::clamp(v,-32768,32767);
    }
};
}

VBCableStatus FindVBCable(){
    VBCableStatus out; IMMDeviceEnumerator* en=nullptr;
    HRESULT hr=CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&en);
    if(FAILED(hr)||!en) return out;
    DWORD state=0; IMMDevice* render=findEndpoint(en,eRender,true,&out.renderName,&state);
    if(render){ out.renderFound=true; out.renderActive=(state&DEVICE_STATE_ACTIVE)!=0; render->Release(); }
    state=0; IMMDevice* capture=findEndpoint(en,eCapture,false,&out.captureName,&state);
    if(capture){ out.captureFound=true; out.captureActive=(state&DEVICE_STATE_ACTIVE)!=0; capture->Release(); }
    en->Release(); return out;
}

struct VBCableBridge::Impl {
    mutable std::mutex mutex;
    std::condition_variable cv;
    std::deque<int16_t> queue;
    std::thread worker;
    std::atomic<bool> stop{false};
    std::atomic<bool> resetRequested{false};
    std::atomic<bool> fatal{false};
    std::atomic<uint64_t> dropped{0};
    std::wstring lastError;

    IMMDeviceEnumerator* enumerator=nullptr;
    IMMDevice* device=nullptr;
    IAudioClient* client=nullptr;
    IAudioRenderClient* render=nullptr;
    HANDLE eventHandle=nullptr;
    UINT32 bufferFrames=0;
    bool audioStarted=false;
    bool playoutStarted=false;
    VoiceProcessor processor;
    int16_t lastSample=0;
    uint32_t missingFrames=0;
    uint32_t driftCounter=0;

    void setError(const std::wstring& s){
        std::lock_guard<std::mutex> lk(mutex); lastError=s; fatal=true;
    }
    void closeAudio(){
        if(client && audioStarted) client->Stop();
        audioStarted=false; playoutStarted=false; bufferFrames=0; missingFrames=0; driftCounter=0;
        if(render){ render->Release(); render=nullptr; }
        if(client){ client->Release(); client=nullptr; }
        if(device){ device->Release(); device=nullptr; }
        if(enumerator){ enumerator->Release(); enumerator=nullptr; }
        if(eventHandle){ CloseHandle(eventHandle); eventHandle=nullptr; }
    }
    bool ensureAudio(){
        if(client && render && eventHandle && audioStarted) return true;
        closeAudio(); fatal=false;
        HRESULT hr=CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&enumerator);
        if(FAILED(hr)||!enumerator){ setError(L"Windows audio device enumerator failed"); return false; }
        std::wstring name; DWORD state=0;
        device=findEndpoint(enumerator,eRender,true,&name,&state);
        if(!device){ setError(L"VB-CABLE playback endpoint (CABLE Input) was not found"); closeAudio(); return false; }
        if((state&DEVICE_STATE_ACTIVE)==0){ setError(L"CABLE Input exists but is disabled"); closeAudio(); return false; }
        hr=device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,(void**)&client);
        if(FAILED(hr)||!client){ setError(L"Could not open CABLE Input"); closeAudio(); return false; }

        WAVEFORMATEX fmt{};
        fmt.wFormatTag=WAVE_FORMAT_PCM; fmt.nChannels=2; fmt.nSamplesPerSec=kRate; fmt.wBitsPerSample=16;
        fmt.nBlockAlign=(WORD)(fmt.nChannels*fmt.wBitsPerSample/8);
        fmt.nAvgBytesPerSec=fmt.nSamplesPerSec*fmt.nBlockAlign;
        DWORD flags=AUDCLNT_STREAMFLAGS_EVENTCALLBACK|AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM|AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
        hr=client->Initialize(AUDCLNT_SHAREMODE_SHARED,flags,0,0,&fmt,nullptr);
        if(FAILED(hr)){ setError(L"CABLE Input rejected the event-driven 48 kHz PhoneBridge stream"); closeAudio(); return false; }
        hr=client->GetBufferSize(&bufferFrames);
        if(FAILED(hr)||!bufferFrames){ setError(L"Could not create the VB-CABLE render buffer"); closeAudio(); return false; }
        eventHandle=CreateEventW(nullptr,FALSE,FALSE,nullptr);
        if(!eventHandle){ setError(L"Could not create the VB-CABLE render event"); closeAudio(); return false; }
        hr=client->SetEventHandle(eventHandle);
        if(FAILED(hr)){ setError(L"Windows rejected the VB-CABLE render event"); closeAudio(); return false; }
        hr=client->GetService(__uuidof(IAudioRenderClient),(void**)&render);
        if(FAILED(hr)||!render){ setError(L"Could not create the VB-CABLE render client"); closeAudio(); return false; }
        hr=client->Start();
        if(FAILED(hr)){ setError(L"Could not start CABLE Input"); closeAudio(); return false; }
        audioStarted=true; return true;
    }

    void fillMonoBlock(std::vector<int16_t>& mono, UINT32 frames){
        mono.assign(frames,0);
        std::lock_guard<std::mutex> lk(mutex); // v1.7: ONE lock per WASAPI block, not 48,000 locks/sec.

        if(!playoutStarted){
            if(queue.size()<kPrefillSamples) return;
            playoutStarted=true;
            missingFrames=0;
        }

        const bool low = queue.size()<kLowWaterSamples;
        const bool high = queue.size()>kHighWaterSamples;

        for(UINT32 i=0;i<frames;i++){
            if(!queue.empty()){
                // Gentle clock correction: at most one sample every 192 output frames
                // (~0.52%). At low water duplicate one source sample; at high water
                // consume one extra source sample. This is far smoother than packet drops.
                bool duplicate = low && (++driftCounter % 192u)==0u;
                lastSample=queue.front();
                if(!duplicate) queue.pop_front();
                if(high && (driftCounter % 192u)==0u && !queue.empty()) queue.pop_front();
                mono[i]=lastSample;
                missingFrames=0;
            } else {
                ++missingFrames;
                // Short Wi-Fi scheduling gaps must not trigger a 180 ms restart.
                // Fade the previous sample to zero for 12 ms, then hold digital silence.
                if(missingFrames<=kConcealFrames){
                    float g=1.0f-(float)missingFrames/(float)kConcealFrames;
                    mono[i]=(int16_t)std::lround((float)lastSample*g);
                } else mono[i]=0;

                // Only a genuine >80 ms outage returns to prefill mode.
                if(missingFrames>kRebufferFrames){
                    playoutStarted=false;
                    lastSample=0;
                }
            }
        }
    }

    void renderOnce(){
        if(!ensureAudio()) return;
        UINT32 padding=0; HRESULT hr=client->GetCurrentPadding(&padding);
        if(FAILED(hr)){ setError(L"VB-CABLE playback endpoint was disconnected"); closeAudio(); return; }
        if(padding>=bufferFrames) return;
        UINT32 frames=bufferFrames-padding; if(!frames) return;
        BYTE* raw=nullptr; hr=render->GetBuffer(frames,&raw);
        if(FAILED(hr)){ setError(L"Could not obtain the VB-CABLE render buffer"); closeAudio(); return; }

        std::vector<int16_t> mono;
        fillMonoBlock(mono,frames);
        int16_t* out=(int16_t*)raw;
        for(UINT32 i=0;i<frames;i++){
            int16_t processed=processor.process(mono[i]);
            out[i*2]=processed; out[i*2+1]=processed;
        }
        hr=render->ReleaseBuffer(frames,0);
        if(FAILED(hr)){ setError(L"VB-CABLE rejected rendered PhoneBridge audio"); closeAudio(); }
    }

    void run(){
        HRESULT co=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
        auto lastData=GetTickCount64();
        while(!stop){
            if(resetRequested.exchange(false)){
                closeAudio();
                std::lock_guard<std::mutex> lk(mutex);
                queue.clear(); lastError.clear(); fatal=false; processor=VoiceProcessor{}; lastSample=0; missingFrames=0; driftCounter=0;
            }
            bool hasData=false;
            {
                std::unique_lock<std::mutex> lk(mutex);
                if(queue.empty() && !audioStarted) cv.wait_for(lk,std::chrono::milliseconds(50));
                hasData=!queue.empty();
            }
            if(hasData){ lastData=GetTickCount64(); if(!audioStarted) ensureAudio(); }
            if(audioStarted){
                DWORD wait=WaitForSingleObject(eventHandle,50);
                if(wait==WAIT_OBJECT_0) renderOnce();
                else if(wait==WAIT_FAILED){ setError(L"VB-CABLE render event failed"); closeAudio(); }
                if(GetTickCount64()-lastData>1500){ closeAudio(); }
            }
        }
        closeAudio();
        if(SUCCEEDED(co)) CoUninitialize();
    }
};

VBCableBridge::VBCableBridge():impl_(new Impl){ impl_->worker=std::thread([this]{ impl_->run(); }); }
VBCableBridge::~VBCableBridge(){
    if(!impl_) return;
    impl_->stop=true; impl_->cv.notify_all();
    if(impl_->worker.joinable()) impl_->worker.join();
    delete impl_; impl_=nullptr;
}

bool VBCableBridge::pushMonoPcm16(const int16_t* samples,uint32_t sampleCount,std::wstring* error){
    if(!impl_||!samples||!sampleCount) return true;
    if(impl_->fatal.load()){
        if(error){ std::lock_guard<std::mutex> lk(impl_->mutex); *error=impl_->lastError; }
        return false;
    }
    {
        std::lock_guard<std::mutex> lk(impl_->mutex);
        size_t needed=impl_->queue.size()+sampleCount;
        if(needed>kMaxQueueSamples){
            size_t drop=needed-kMaxQueueSamples;
            drop=std::min(drop,impl_->queue.size());
            for(size_t i=0;i<drop;i++) impl_->queue.pop_front();
            impl_->dropped += drop;
        }
        impl_->queue.insert(impl_->queue.end(),samples,samples+sampleCount);
    }
    impl_->cv.notify_one(); return true;
}
void VBCableBridge::reset(){ if(impl_){ impl_->resetRequested=true; impl_->cv.notify_all(); } }
uint32_t VBCableBridge::bufferedMilliseconds() const{
    if(!impl_) return 0; std::lock_guard<std::mutex> lk(impl_->mutex);
    return (uint32_t)(impl_->queue.size()*1000ull/kRate);
}
uint64_t VBCableBridge::droppedSamples() const{ return impl_?impl_->dropped.load():0; }
