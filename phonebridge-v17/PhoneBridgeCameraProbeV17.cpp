#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <mferror.h>
#include <shlobj.h>
#include <string>
#include <sstream>
#include <fstream>
#include <filesystem>
#pragma comment(lib,"ole32.lib")
#pragma comment(lib,"mf.lib")
#pragma comment(lib,"mfplat.lib")
#pragma comment(lib,"mfreadwrite.lib")
#pragma comment(lib,"mfuuid.lib")
#pragma comment(lib,"shell32.lib")

static std::wstring HrText(HRESULT hr){
    wchar_t* msg=nullptr;
    DWORD n=FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER|FORMAT_MESSAGE_FROM_SYSTEM|FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr,(DWORD)hr,MAKELANGID(LANG_NEUTRAL,SUBLANG_DEFAULT),(LPWSTR)&msg,0,nullptr);
    wchar_t hex[32]{}; swprintf_s(hex,L"0x%08X",(unsigned)hr);
    std::wstring out=hex;
    if(n&&msg){ out+=L" - "; out+=msg; LocalFree(msg); }
    return out;
}
static std::wstring GuidText(REFGUID g){ wchar_t b[64]{}; StringFromGUID2(g,b,64); return b; }
static std::wstring reportPath(){
    wchar_t local[MAX_PATH]{}; DWORD n=GetEnvironmentVariableW(L"LOCALAPPDATA",local,MAX_PATH);
    std::filesystem::path dir=(n&&n<MAX_PATH)?std::filesystem::path(local):std::filesystem::current_path();
    dir/=L"PhoneBridge"; dir/=L"Logs"; std::error_code ec; std::filesystem::create_directories(dir,ec);
    return (dir/L"CameraHealth-v1.7.txt").wstring();
}
static void saveReport(const std::wstring& text){ std::wofstream f(std::filesystem::path(reportPath()),std::ios::trunc); if(f) f<<text; }
static void show(const std::wstring& text,UINT icon=MB_ICONINFORMATION){ MessageBoxW(nullptr,text.c_str(),L"PhoneBridge Camera Health v1.7",MB_OK|icon); }

int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR,int){
    std::wstringstream log; log<<L"PhoneBridge Camera Health Test v1.7\n\n";
    HRESULT coHr=CoInitializeEx(nullptr,COINIT_MULTITHREADED); bool uninit=SUCCEEDED(coHr); log<<L"COM: "<<HrText(coHr)<<L"\n";
    HRESULT hr=MFStartup(MF_VERSION); log<<L"MFStartup: "<<HrText(hr)<<L"\n";
    if(FAILED(hr)){ saveReport(log.str()); show(L"Media Foundation could not start.\n"+HrText(hr),MB_ICONERROR); if(uninit) CoUninitialize(); return 2; }

    IMFAttributes* attrs=nullptr; IMFActivate** devices=nullptr; UINT32 count=0; IMFActivate* chosen=nullptr;
    IMFMediaSource* source=nullptr; IMFSourceReader* reader=nullptr; IMFMediaType* nativeType=nullptr; IMFSample* sample=nullptr;
    std::wstring chosenName; bool ok=false; HRESULT lastHr=S_OK; DWORD lastFlags=0; int ticks=0;

    hr=MFCreateAttributes(&attrs,1);
    if(SUCCEEDED(hr)) hr=attrs->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
    if(SUCCEEDED(hr)) hr=MFEnumDeviceSources(attrs,&devices,&count);
    log<<L"Enumerate cameras: "<<HrText(hr)<<L" count="<<count<<L"\n";
    if(SUCCEEDED(hr)){
        for(UINT32 i=0;i<count;i++){
            WCHAR* name=nullptr; UINT32 chars=0;
            HRESULT nhr=devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME,&name,&chars);
            std::wstring friendly=(SUCCEEDED(nhr)&&name)?name:L"<unnamed>"; if(name) CoTaskMemFree(name);
            log<<L"  ["<<i<<L"] "<<friendly<<L"\n";
            if(!chosen && friendly.find(L"PhoneBridge Camera")!=std::wstring::npos){ chosen=devices[i]; chosen->AddRef(); chosenName=friendly; }
        }
    }

    if(chosen){
        log<<L"\nSelected: "<<chosenName<<L"\n";
        hr=chosen->ActivateObject(IID_PPV_ARGS(&source)); log<<L"ActivateObject: "<<HrText(hr)<<L"\n";
        if(SUCCEEDED(hr)){ hr=MFCreateSourceReaderFromMediaSource(source,nullptr,&reader); log<<L"Create source reader: "<<HrText(hr)<<L"\n"; }
        if(SUCCEEDED(hr)){
            hr=reader->GetNativeMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM,0,&nativeType);
            log<<L"First native video type: "<<HrText(hr)<<L"\n";
            if(SUCCEEDED(hr)&&nativeType){ GUID subtype{}; UINT32 w=0,h=0; nativeType->GetGUID(MF_MT_SUBTYPE,&subtype); MFGetAttributeSize(nativeType,MF_MT_FRAME_SIZE,&w,&h); log<<L"Subtype: "<<GuidText(subtype)<<L"\nFrame size: "<<w<<L"x"<<h<<L"\n"; }
        }
        if(SUCCEEDED(hr)){
            reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM,TRUE);
            // A freshly activated Frame Server camera may legally emit one or more
            // MF_SOURCE_READERF_STREAMTICK (0x100) events before its first sample.
            // v1.4-v1.6 probes incorrectly treated the very first tick as failure.
            for(int attempt=0;attempt<90 && !ok;attempt++){
                DWORD actual=0,flags=0; LONGLONG ts=0; IMFSample* one=nullptr;
                HRESULT rhr=reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM,0,&actual,&flags,&ts,&one);
                lastHr=rhr; lastFlags=flags;
                log<<L"ReadSample["<<attempt<<L"]: "<<HrText(rhr)<<L" flags=0x"<<std::hex<<flags<<std::dec<<L" sample="<<(one?L"yes":L"no")<<L" ts="<<ts<<L"\n";
                if(FAILED(rhr) || (flags&MF_SOURCE_READERF_ERROR)){ if(one) one->Release(); break; }
                if(one){ sample=one; ok=true; break; }
                if(flags&MF_SOURCE_READERF_STREAMTICK){ ++ticks; Sleep(10); continue; }
                if(flags&MF_SOURCE_READERF_ENDOFSTREAM){ if(one) one->Release(); break; }
                Sleep(10);
            }
        }
    } else log<<L"\nPhoneBridge Camera was not found.\n";

    log<<L"\nStream ticks before first frame: "<<ticks<<L"\n";
    log<<L"RESULT: "<<(ok?L"PASS - real camera sample received.":L"FAIL - no real sample received before timeout/error.")<<L"\n";
    log<<L"Last HRESULT: "<<HrText(lastHr)<<L" flags=0x"<<std::hex<<lastFlags<<std::dec<<L"\nReport: "<<reportPath()<<L"\n";
    saveReport(log.str());

    if(sample) sample->Release(); if(nativeType) nativeType->Release(); if(reader) reader->Release();
    if(source){ source->Shutdown(); source->Release(); } if(chosen){ chosen->ShutdownObject(); chosen->Release(); }
    if(devices){ for(UINT32 i=0;i<count;i++) if(devices[i]) devices[i]->Release(); CoTaskMemFree(devices); }
    if(attrs) attrs->Release(); MFShutdown(); if(uninit) CoUninitialize();

    if(ok) show(L"PASS: PhoneBridge Camera delivered a real video frame.\n\nInitial stream ticks are normal during Frame Server startup.\n\nReport: "+reportPath());
    else {
        std::wstringstream msg; msg<<L"FAIL: PhoneBridge Camera did not deliver a real frame.\n\nLast result: "<<HrText(lastHr)<<L"\nflags=0x"<<std::hex<<lastFlags<<std::dec<<L"\nstream ticks="<<ticks<<L"\n\nReport: "<<reportPath();
        show(msg.str(),MB_ICONERROR);
    }
    return ok?0:1;
}
