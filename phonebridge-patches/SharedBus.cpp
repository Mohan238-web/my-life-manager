#include "SharedBus.h"
#include <algorithm>
#include <cstring>
#include <string>

#pragma pack(push,1)
struct VideoHeader { volatile LONG sequence; uint32_t width,height,stride,bytes; uint64_t timestamp100ns; };
struct AudioHeader { volatile LONG sequence; uint32_t sampleRate,channels,writeOffset,capacityBytes; };
#pragma pack(pop)

static HANDLE OpenProgramDataBus(const wchar_t* leaf, size_t capacity){
    wchar_t base[MAX_PATH]{};
    DWORD n=GetEnvironmentVariableW(L"ProgramData",base,MAX_PATH);
    if(!n || n>=MAX_PATH) return INVALID_HANDLE_VALUE;
    std::wstring dir=std::wstring(base)+L"\\PhoneBridge";
    CreateDirectoryW(dir.c_str(),nullptr);
    std::wstring path=dir+L"\\"+leaf;
    HANDLE f=CreateFileW(path.c_str(),GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(f==INVALID_HANDLE_VALUE) return f;
    LARGE_INTEGER wanted{}; wanted.QuadPart=(LONGLONG)capacity;
    LARGE_INTEGER actual{};
    if(!GetFileSizeEx(f,&actual) || actual.QuadPart<(LONGLONG)capacity){
        if(!SetFilePointerEx(f,wanted,nullptr,FILE_BEGIN) || !SetEndOfFile(f)){ CloseHandle(f); return INVALID_HANDLE_VALUE; }
    }
    return f;
}

SharedFrameBus::SharedFrameBus() = default;
SharedFrameBus::~SharedFrameBus(){ if(view_) UnmapViewOfFile(view_); if(map_) CloseHandle(map_); if(file_!=INVALID_HANDLE_VALUE) CloseHandle(file_); }
bool SharedFrameBus::openOrCreate(const wchar_t* name){
    file_=OpenProgramDataBus(L"video.bus",kCapacity);
    if(file_!=INVALID_HANDLE_VALUE){
        map_=CreateFileMappingW(file_,nullptr,PAGE_READWRITE,0,0,nullptr);
        if(map_) view_=(uint8_t*)MapViewOfFile(map_,FILE_MAP_ALL_ACCESS,0,0,kCapacity);
        if(view_) return true;
        if(map_){ CloseHandle(map_); map_=nullptr; }
        CloseHandle(file_); file_=INVALID_HANDLE_VALUE;
    }
    map_=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,(DWORD)(kCapacity>>32),(DWORD)kCapacity,name);
    if(!map_) return false;
    view_=(uint8_t*)MapViewOfFile(map_,FILE_MAP_ALL_ACCESS,0,0,kCapacity);
    return view_!=nullptr;
}
bool SharedFrameBus::writeBgra(const void* pixels,uint32_t w,uint32_t h,uint32_t stride,uint64_t ts){
    if(!view_) return false; size_t bytes=(size_t)stride*h; if(bytes+sizeof(VideoHeader)>kCapacity) return false;
    auto* hdr=(VideoHeader*)view_; InterlockedIncrement(&hdr->sequence); hdr->width=w; hdr->height=h; hdr->stride=stride; hdr->bytes=(uint32_t)bytes; hdr->timestamp100ns=ts;
    memcpy(view_+sizeof(VideoHeader),pixels,bytes); MemoryBarrier(); InterlockedIncrement(&hdr->sequence); return true;
}

SharedAudioBus::SharedAudioBus() = default;
SharedAudioBus::~SharedAudioBus(){ if(view_) UnmapViewOfFile(view_); if(map_) CloseHandle(map_); if(file_!=INVALID_HANDLE_VALUE) CloseHandle(file_); }
bool SharedAudioBus::openOrCreate(const wchar_t* name){
    file_=OpenProgramDataBus(L"audio.bus",kCapacity);
    if(file_!=INVALID_HANDLE_VALUE){
        map_=CreateFileMappingW(file_,nullptr,PAGE_READWRITE,0,0,nullptr);
        if(map_) view_=(uint8_t*)MapViewOfFile(map_,FILE_MAP_ALL_ACCESS,0,0,kCapacity);
    }
    if(!view_){
        if(map_){ CloseHandle(map_); map_=nullptr; }
        if(file_!=INVALID_HANDLE_VALUE){ CloseHandle(file_); file_=INVALID_HANDLE_VALUE; }
        map_=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,(DWORD)kCapacity,name);
        if(!map_) return false;
        view_=(uint8_t*)MapViewOfFile(map_,FILE_MAP_ALL_ACCESS,0,0,kCapacity);
    }
    if(!view_) return false;
    auto* hdr=(AudioHeader*)view_; if(hdr->capacityBytes==0) hdr->capacityBytes=(uint32_t)(kCapacity-sizeof(AudioHeader)); return true;
}
bool SharedAudioBus::writePcm16(const int16_t* samples,uint32_t count,uint32_t rate,uint32_t ch){
    if(!view_||!samples) return false; auto* hdr=(AudioHeader*)view_; uint8_t* ring=view_+sizeof(AudioHeader); uint32_t cap=(uint32_t)(kCapacity-sizeof(AudioHeader));
    uint32_t bytes=count*sizeof(int16_t); hdr->sampleRate=rate; hdr->channels=ch; hdr->capacityBytes=cap; InterlockedIncrement(&hdr->sequence);
    uint32_t off=hdr->writeOffset%cap; const uint8_t* src=(const uint8_t*)samples;
    uint32_t first=std::min(bytes,cap-off); memcpy(ring+off,src,first); if(bytes>first) memcpy(ring,src+first,bytes-first);
    hdr->writeOffset=(off+bytes)%cap; MemoryBarrier(); InterlockedIncrement(&hdr->sequence); return true;
}
