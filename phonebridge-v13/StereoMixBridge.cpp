#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include "StereoMixBridge.h"
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <propvarutil.h>
#include <algorithm>
#include <cwctype>
#include <vector>

namespace {
std::wstring lower(std::wstring s){
    std::transform(s.begin(),s.end(),s.begin(),[](wchar_t c){ return (wchar_t)towlower(c); });
    return s;
}

void releaseUnknown(IUnknown*& p){ if(p){ p->Release(); p=nullptr; } }
}

StereoMixStatus FindStereoMixCapture(){
    StereoMixStatus out;
    IMMDeviceEnumerator* en=nullptr;
    if(FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&en)) || !en) return out;
    IMMDeviceCollection* col=nullptr;
    if(SUCCEEDED(en->EnumAudioEndpoints(eCapture,DEVICE_STATEMASK_ALL,&col)) && col){
        UINT count=0; col->GetCount(&count);
        for(UINT i=0;i<count;i++){
            IMMDevice* dev=nullptr; if(FAILED(col->Item(i,&dev)) || !dev) continue;
            IPropertyStore* props=nullptr; std::wstring name;
            if(SUCCEEDED(dev->OpenPropertyStore(STGM_READ,&props)) && props){
                PROPVARIANT pv; PropVariantInit(&pv);
                if(SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName,&pv)) && pv.vt==VT_LPWSTR && pv.pwszVal) name=pv.pwszVal;
                PropVariantClear(&pv); props->Release();
            }
            auto l=lower(name);
            if(l.find(L"stereo mix")!=std::wstring::npos || l.find(L"what u hear")!=std::wstring::npos){
                DWORD state=0; dev->GetState(&state);
                out.found=true; out.active=(state&DEVICE_STATE_ACTIVE)!=0; out.name=name;
                dev->Release(); break;
            }
            dev->Release();
        }
        col->Release();
    }
    en->Release();
    return out;
}

struct StereoMixBridge::Impl {
    IMMDeviceEnumerator* enumerator=nullptr;
    IMMDevice* device=nullptr;
    IAudioClient* client=nullptr;
    IAudioRenderClient* render=nullptr;
    UINT32 bufferFrames=0;
    bool started=false;
};

StereoMixBridge::StereoMixBridge():impl_(new Impl){}
StereoMixBridge::~StereoMixBridge(){ reset(); delete impl_; impl_=nullptr; }

void StereoMixBridge::reset(){
    if(!impl_) return;
    if(impl_->client && impl_->started) impl_->client->Stop();
    impl_->started=false; impl_->bufferFrames=0;
    if(impl_->render){ impl_->render->Release(); impl_->render=nullptr; }
    if(impl_->client){ impl_->client->Release(); impl_->client=nullptr; }
    if(impl_->device){ impl_->device->Release(); impl_->device=nullptr; }
    if(impl_->enumerator){ impl_->enumerator->Release(); impl_->enumerator=nullptr; }
}

bool StereoMixBridge::ensure(std::wstring* error){
    if(!impl_) return false;
    if(impl_->client && impl_->render && impl_->started) return true;
    reset();
    HRESULT hr=CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&impl_->enumerator);
    if(FAILED(hr)){ if(error) *error=L"Windows audio device enumerator failed"; return false; }
    hr=impl_->enumerator->GetDefaultAudioEndpoint(eRender,eConsole,&impl_->device);
    if(FAILED(hr)){ if(error) *error=L"No default Windows playback device is available"; reset(); return false; }
    hr=impl_->device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,(void**)&impl_->client);
    if(FAILED(hr)){ if(error) *error=L"Could not open the default Windows playback device"; reset(); return false; }

    WAVEFORMATEX fmt{};
    fmt.wFormatTag=WAVE_FORMAT_PCM;
    fmt.nChannels=2;
    fmt.nSamplesPerSec=48000;
    fmt.wBitsPerSample=16;
    fmt.nBlockAlign=(WORD)(fmt.nChannels*fmt.wBitsPerSample/8);
    fmt.nAvgBytesPerSec=fmt.nSamplesPerSec*fmt.nBlockAlign;
    fmt.cbSize=0;
    const DWORD flags=AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM|AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
    const REFERENCE_TIME bufferDuration=1000000; // 100 ms shared-mode safety buffer.
    hr=impl_->client->Initialize(AUDCLNT_SHAREMODE_SHARED,flags,bufferDuration,0,&fmt,nullptr);
    if(FAILED(hr)){ if(error) *error=L"The default playback device rejected the 48 kHz compatibility stream"; reset(); return false; }
    hr=impl_->client->GetBufferSize(&impl_->bufferFrames);
    if(FAILED(hr) || impl_->bufferFrames==0){ if(error) *error=L"Could not create the Stereo Mix bridge buffer"; reset(); return false; }
    hr=impl_->client->GetService(__uuidof(IAudioRenderClient),(void**)&impl_->render);
    if(FAILED(hr)){ if(error) *error=L"Could not create the Windows audio render bridge"; reset(); return false; }
    hr=impl_->client->Start();
    if(FAILED(hr)){ if(error) *error=L"Could not start the Stereo Mix compatibility stream"; reset(); return false; }
    impl_->started=true;
    return true;
}

bool StereoMixBridge::pushMonoPcm16(const int16_t* samples,uint32_t sampleCount,std::wstring* error){
    if(!samples || sampleCount==0) return true;
    if(!ensure(error)) return false;
    UINT32 padding=0;
    HRESULT hr=impl_->client->GetCurrentPadding(&padding);
    if(FAILED(hr)){ reset(); if(error) *error=L"Windows playback device was disconnected"; return false; }
    if(padding>=impl_->bufferFrames) return true;
    UINT32 available=impl_->bufferFrames-padding;
    UINT32 frames=std::min<UINT32>(available,sampleCount);
    if(frames==0) return true;
    BYTE* raw=nullptr;
    hr=impl_->render->GetBuffer(frames,&raw);
    if(FAILED(hr)){ reset(); if(error) *error=L"Could not write to the Windows playback mix"; return false; }
    auto* out=(int16_t*)raw;
    for(UINT32 i=0;i<frames;i++){
        const int16_t v=samples[i];
        out[i*2]=v;
        out[i*2+1]=v;
    }
    hr=impl_->render->ReleaseBuffer(frames,0);
    if(FAILED(hr)){ reset(); if(error) *error=L"Windows rejected Stereo Mix bridge audio"; return false; }
    return true;
}
