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
#include <vector>
#pragma comment(lib,"ole32.lib")
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
    wchar_t local[MAX_PATH]{};
    DWORD n=GetEnvironmentVariableW(L"LOCALAPPDATA",local,MAX_PATH);
    std::wstring base=(n&&n<MAX_PATH)?local:L".";
    std::wstring dir=base+L"\\PhoneBridge\\Logs";
    CreateDirectoryW((base+L"\\PhoneBridge").c_str(),nullptr);
    CreateDirectoryW(dir.c_str(),nullptr);
    return dir+L"\\CameraHealth.txt";
}

static void saveReport(const std::wstring& text){
    std::wofstream f(reportPath(),std::ios::trunc);
    if(f) f<<text;
}

static void show(const std::wstring& text,UINT icon=MB_ICONINFORMATION){
    MessageBoxW(nullptr,text.c_str(),L"PhoneBridge Camera Health",MB_OK|icon);
}

int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR,int){
    std::wstringstream log;
    log<<L"PhoneBridge Camera Health Test\n\n";
    HRESULT hr=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
    bool co=SUCCEEDED(hr)||hr==RPC_E_CHANGED_MODE;
    log<<L"COM: "<<HrText(hr)<<L"\n";

    hr=MFStartup(MF_VERSION);
    log<<L"MFStartup: "<<HrText(hr)<<L"\n";
    if(FAILED(hr)){ saveReport(log.str()); show(L"Media Foundation could not start.\n\n"+HrText(hr)+L"\n\nReport: "+reportPath(),MB_ICONERROR); if(co&&hr!=RPC_E_CHANGED_MODE) CoUninitialize(); return 2; }

    IMFAttributes* attrs=nullptr;
    IMFActivate** devices=nullptr;
    UINT32 count=0;
    IMFActivate* chosen=nullptr;
    IMFMediaSource* source=nullptr;
    IMFSourceReader* reader=nullptr;
    IMFMediaType* nativeType=nullptr;
    IMFSample* sample=nullptr;
    bool ok=false;
    std::wstring chosenName;

    hr=MFCreateAttributes(&attrs,1);
    if(SUCCEEDED(hr)) hr=attrs->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
    if(SUCCEEDED(hr)) hr=MFEnumDeviceSources(attrs,&devices,&count);
    log<<L"Enumerate cameras: "<<HrText(hr)<<L"  count="<<count<<L"\n";

    if(SUCCEEDED(hr)){
        for(UINT32 i=0;i<count;i++){
            WCHAR* name=nullptr; UINT32 chars=0;
            HRESULT nhr=devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME,&name,&chars);
            std::wstring friendly=(SUCCEEDED(nhr)&&name)?name:L"<unnamed>";
            if(name) CoTaskMemFree(name);
            log<<L"  ["<<i<<L"] "<<friendly<<L"\n";
            if(!chosen && friendly.find(L"PhoneBridge Camera")!=std::wstring::npos){ chosen=devices[i]; chosen->AddRef(); chosenName=friendly; }
        }
    }

    if(!chosen){
        log<<L"\nRESULT: PhoneBridge Camera is not registered/enumerable for this Windows user.\n";
    } else {
        log<<L"\nSelected: "<<chosenName<<L"\n";
        hr=chosen->ActivateObject(IID_PPV_ARGS(&source));
        log<<L"ActivateObject(IMFMediaSource): "<<HrText(hr)<<L"\n";
        if(SUCCEEDED(hr)){
            hr=MFCreateSourceReaderFromMediaSource(source,nullptr,&reader);
            log<<L"Create source reader: "<<HrText(hr)<<L"\n";
        }
        if(SUCCEEDED(hr)){
            hr=reader->GetNativeMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM,0,&nativeType);
            log<<L"First native video type: "<<HrText(hr)<<L"\n";
            if(SUCCEEDED(hr)&&nativeType){
                GUID subtype{}; UINT32 w=0,h=0;
                if(SUCCEEDED(nativeType->GetGUID(MF_MT_SUBTYPE,&subtype))) log<<L"Subtype: "<<GuidText(subtype)<<L"\n";
                if(SUCCEEDED(MFGetAttributeSize(nativeType,MF_MT_FRAME_SIZE,&w,&h))) log<<L"Frame size: "<<w<<L"x"<<h<<L"\n";
            }
        }
        if(SUCCEEDED(hr)){
            reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM,TRUE);
            DWORD actual=0,flags=0; LONGLONG ts=0;
            hr=reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM,0,&actual,&flags,&ts,&sample);
            log<<L"ReadSample: "<<HrText(hr)<<L" flags=0x"<<std::hex<<flags<<std::dec<<L" sample="<<(sample?L"yes":L"no")<<L"\n";
            ok=SUCCEEDED(hr)&&sample!=nullptr;
        }
    }

    log<<L"\nRESULT: "<<(ok?L"PASS - Windows can activate and read PhoneBridge Camera.":L"FAIL - Windows could not complete PhoneBridge Camera activation/read.")<<L"\n";
    log<<L"Report: "<<reportPath()<<L"\n";
    saveReport(log.str());

    if(sample) sample->Release();
    if(nativeType) nativeType->Release();
    if(reader) reader->Release();
    if(source){ source->Shutdown(); source->Release(); }
    if(chosen){ chosen->ShutdownObject(); chosen->Release(); }
    if(devices){ for(UINT32 i=0;i<count;i++) if(devices[i]) devices[i]->Release(); CoTaskMemFree(devices); }
    if(attrs) attrs->Release();
    MFShutdown();
    if(co) CoUninitialize();

    if(ok) show(L"PASS: Windows successfully opened PhoneBridge Camera and read a video frame.\n\nIf a browser still says busy/blocked, fully close that browser and any Camera/Zoom/OBS app, then reopen it and select PhoneBridge Camera.\n\nReport: "+reportPath());
    else show(L"FAIL: Windows could not fully activate/read PhoneBridge Camera.\n\nThe exact HRESULT has been saved here:\n"+reportPath()+L"\n\nUse Repair camera, then run this test again.",MB_ICONERROR);
    return ok?0:1;
}
