#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include "VBCableBridge.h"
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <propvarutil.h>
#include <algorithm>
#include <cwctype>

namespace {
std::wstring lower(std::wstring s){
    std::transform(s.begin(),s.end(),s.begin(),[](wchar_t c){ return (wchar_t)towlower(c); });
    return s;
}

std::wstring friendlyName(IMMDevice* dev){
    if(!dev) return {};
    IPropertyStore* props=nullptr;
    std::wstring name;
    if(SUCCEEDED(dev->OpenPropertyStore(STGM_READ,&props)) && props){
        PROPVARIANT pv; PropVariantInit(&pv);
        if(SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName,&pv)) && pv.vt==VT_LPWSTR && pv.pwszVal) name=pv.pwszVal;
        PropVariantClear(&pv);
        props->Release();
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
    UINT count=0; col->GetCount(&count);
    IMMDevice* result=nullptr;
    for(UINT i=0;i<count;i++){
        IMMDevice* dev=nullptr;
        if(FAILED(col->Item(i,&dev)) || !dev) continue;
        std::wstring name=friendlyName(dev);
        bool match=wantInput?looksLikeCableInput(name):looksLikeCableOutput(name);
        if(match){
            DWORD state=0; dev->GetState(&state);
            if(nameOut) *nameOut=name;
            if(stateOut) *stateOut=state;
            result=dev;
            break;
        }
        dev->Release();
    }
    col->Release();
    return result;
}
}

VBCableStatus FindVBCable(){
    VBCableStatus out;
    IMMDeviceEnumerator* en=nullptr;
    HRESULT hr=CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&en);
    if(FAILED(hr)||!en) return out;

    DWORD state=0;
    IMMDevice* render=findEndpoint(en,eRender,true,&out.renderName,&state);
    if(render){ out.renderFound=true; out.renderActive=(state&DEVICE_STATE_ACTIVE)!=0; render->Release(); }
    state=0;
    IMMDevice* capture=findEndpoint(en,eCapture,false,&out.captureName,&state);
    if(capture){ out.captureFound=true; out.captureActive=(state&DEVICE_STATE_ACTIVE)!=0; capture->Release(); }
    en->Release();
    return out;
}

struct VBCableBridge::Impl {
    IMMDeviceEnumerator* enumerator=nullptr;
    IMMDevice* device=nullptr;
    IAudioClient* client=nullptr;
    IAudioRenderClient* render=nullptr;
    UINT32 bufferFrames=0;
    bool started=false;
};

VBCableBridge::VBCableBridge():impl_(new Impl){}
VBCableBridge::~VBCableBridge(){ reset(); delete impl_; impl_=nullptr; }

void VBCableBridge::reset(){
    if(!impl_) return;
    if(impl_->client && impl_->started) impl_->client->Stop();
    impl_->started=false; impl_->bufferFrames=0;
    if(impl_->render){ impl_->render->Release(); impl_->render=nullptr; }
    if(impl_->client){ impl_->client->Release(); impl_->client=nullptr; }
    if(impl_->device){ impl_->device->Release(); impl_->device=nullptr; }
    if(impl_->enumerator){ impl_->enumerator->Release(); impl_->enumerator=nullptr; }
}

bool VBCableBridge::ensure(std::wstring* error){
    if(!impl_) return false;
    if(impl_->client && impl_->render && impl_->started) return true;
    reset();

    HRESULT hr=CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&impl_->enumerator);
    if(FAILED(hr)){ if(error) *error=L"Windows audio device enumerator failed"; return false; }

    std::wstring renderName; DWORD state=0;
    impl_->device=findEndpoint(impl_->enumerator,eRender,true,&renderName,&state);
    if(!impl_->device){ if(error) *error=L"VB-CABLE playback endpoint (CABLE Input) was not found"; reset(); return false; }
    if((state&DEVICE_STATE_ACTIVE)==0){ if(error) *error=L"CABLE Input exists but is disabled"; reset(); return false; }

    hr=impl_->device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,(void**)&impl_->client);
    if(FAILED(hr)){ if(error) *error=L"Could not open CABLE Input"; reset(); return false; }

    WAVEFORMATEX fmt{};
    fmt.wFormatTag=WAVE_FORMAT_PCM;
    fmt.nChannels=2;
    fmt.nSamplesPerSec=48000;
    fmt.wBitsPerSample=16;
    fmt.nBlockAlign=(WORD)(fmt.nChannels*fmt.wBitsPerSample/8);
    fmt.nAvgBytesPerSec=fmt.nSamplesPerSec*fmt.nBlockAlign;
    fmt.cbSize=0;

    const DWORD flags=AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM|AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
    const REFERENCE_TIME bufferDuration=500000; // 50 ms shared-mode buffer.
    hr=impl_->client->Initialize(AUDCLNT_SHAREMODE_SHARED,flags,bufferDuration,0,&fmt,nullptr);
    if(FAILED(hr)){ if(error) *error=L"CABLE Input rejected the 48 kHz PhoneBridge stream"; reset(); return false; }
    hr=impl_->client->GetBufferSize(&impl_->bufferFrames);
    if(FAILED(hr)||!impl_->bufferFrames){ if(error) *error=L"Could not create the VB-CABLE buffer"; reset(); return false; }
    hr=impl_->client->GetService(__uuidof(IAudioRenderClient),(void**)&impl_->render);
    if(FAILED(hr)){ if(error) *error=L"Could not create the VB-CABLE render client"; reset(); return false; }
    hr=impl_->client->Start();
    if(FAILED(hr)){ if(error) *error=L"Could not start CABLE Input"; reset(); return false; }
    impl_->started=true;
    return true;
}

bool VBCableBridge::pushMonoPcm16(const int16_t* samples,uint32_t sampleCount,std::wstring* error){
    if(!samples||!sampleCount) return true;
    if(!ensure(error)) return false;
    UINT32 padding=0;
    HRESULT hr=impl_->client->GetCurrentPadding(&padding);
    if(FAILED(hr)){ reset(); if(error) *error=L"VB-CABLE was disconnected"; return false; }
    if(padding>=impl_->bufferFrames) return true;
    UINT32 frames=std::min<UINT32>(impl_->bufferFrames-padding,sampleCount);
    if(!frames) return true;
    BYTE* raw=nullptr;
    hr=impl_->render->GetBuffer(frames,&raw);
    if(FAILED(hr)){ reset(); if(error) *error=L"Could not write to CABLE Input"; return false; }
    int16_t* out=(int16_t*)raw;
    for(UINT32 i=0;i<frames;i++){
        int16_t v=samples[i];
        out[i*2]=v;
        out[i*2+1]=v;
    }
    hr=impl_->render->ReleaseBuffer(frames,0);
    if(FAILED(hr)){ reset(); if(error) *error=L"VB-CABLE rejected PhoneBridge audio"; return false; }
    return true;
}
