#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfvirtualcamera.h>
#include <sddl.h>
#include <aclapi.h>
#include <filesystem>
#include <string>
#include <sstream>
#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "mfsensorgroup.lib")
#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "ole32.lib")

static constexpr wchar_t kClsid[] = L"{A7318E11-4B4C-4BCC-A19F-FA192BA8BA5D}";
static constexpr wchar_t kFriendly[] = L"PhoneBridge Camera";
// VCAM_KIND = {C7F7C57B-DF30-41D0-AFFC-15201CDF920D}
static const GUID VCAM_KIND_PB = {0xc7f7c57b,0xdf30,0x41d0,{0xaf,0xfc,0x15,0x20,0x1c,0xdf,0x92,0x0d}};

static bool IsElevated() {
    HANDLE token{}; TOKEN_ELEVATION e{}; DWORD cb{};
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return false;
    bool ok = GetTokenInformation(token, TokenElevation, &e, sizeof(e), &cb) && e.TokenIsElevated;
    CloseHandle(token); return ok;
}
static std::wstring HrText(HRESULT hr) {
    wchar_t b[64]; swprintf_s(b,L"0x%08X",(unsigned)hr); return b;
}
static void Show(const std::wstring& s, UINT flags=MB_OK|MB_ICONINFORMATION) {
    MessageBoxW(nullptr,s.c_str(),L"PhoneBridge Virtual Camera",flags);
}
static bool RegisterCom(const std::wstring& dll) {
    std::wstring key = std::wstring(L"Software\\Classes\\CLSID\\") + kClsid + L"\\InProcServer32";
    HKEY h{}; DWORD disp{};
    LONG r=RegCreateKeyExW(HKEY_LOCAL_MACHINE,key.c_str(),0,nullptr,0,KEY_WRITE|KEY_WOW64_64KEY,nullptr,&h,&disp);
    if(r!=ERROR_SUCCESS) return false;
    r=RegSetValueExW(h,nullptr,0,REG_SZ,(const BYTE*)dll.c_str(),(DWORD)((dll.size()+1)*sizeof(wchar_t)));
    const wchar_t both[]=L"Both";
    if(r==ERROR_SUCCESS) r=RegSetValueExW(h,L"ThreadingModel",0,REG_SZ,(const BYTE*)both,sizeof(both));
    RegCloseKey(h); return r==ERROR_SUCCESS;
}
static void UnregisterCom() {
    std::wstring key = std::wstring(L"Software\\Classes\\CLSID\\") + kClsid;
    RegDeleteTreeW(HKEY_LOCAL_MACHINE,key.c_str());
}
static HRESULT OpenCamera(IMFVirtualCamera** out) {
    return MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource,
        MFVirtualCameraLifetime_System, MFVirtualCameraAccess_CurrentUser,
        kFriendly, kClsid, nullptr, 0, out);
}

static HRESULT PrepareSharedBusDirectory() {
    wchar_t base[MAX_PATH]{};
    if(!GetEnvironmentVariableW(L"ProgramData",base,MAX_PATH)) return HRESULT_FROM_WIN32(GetLastError());
    std::filesystem::path dir=std::filesystem::path(base)/L"PhoneBridge";
    std::error_code ec; std::filesystem::create_directories(dir,ec);

    PSECURITY_DESCRIPTOR sd=nullptr;
    // SYSTEM/Admins: full control; Users: read/write; Local Service (Frame Server): read.
    if(!ConvertStringSecurityDescriptorToSecurityDescriptorW(
        L"D:P(A;OICI;GA;;;SY)(A;OICI;GA;;;BA)(A;OICI;GRGW;;;BU)(A;OICI;GR;;;LS)",
        SDDL_REVISION_1,&sd,nullptr)) return HRESULT_FROM_WIN32(GetLastError());
    PACL dacl=nullptr; BOOL present=FALSE, defaulted=FALSE;
    if(!GetSecurityDescriptorDacl(sd,&present,&dacl,&defaulted) || !present){ LocalFree(sd); return E_FAIL; }

    auto applySecurity=[&](const std::filesystem::path& path)->HRESULT {
        std::wstring text=path.wstring();
        DWORD er=SetNamedSecurityInfoW(text.data(),SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION|PROTECTED_DACL_SECURITY_INFORMATION,
            nullptr,nullptr,dacl,nullptr);
        return er==ERROR_SUCCESS?S_OK:HRESULT_FROM_WIN32(er);
    };
    HRESULT hr=applySecurity(dir); if(FAILED(hr)){ LocalFree(sd); return hr; }

    auto makeFile=[&](const wchar_t* name, ULONGLONG bytes)->HRESULT {
        std::filesystem::path path=dir/name;
        HANDLE f=CreateFileW(path.c_str(),GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
        if(f==INVALID_HANDLE_VALUE) return HRESULT_FROM_WIN32(GetLastError());
        LARGE_INTEGER actual{}; GetFileSizeEx(f,&actual);
        BOOL ok=TRUE;
        if(actual.QuadPart < (LONGLONG)bytes){
            LARGE_INTEGER size{}; size.QuadPart=(LONGLONG)bytes;
            ok=SetFilePointerEx(f,size,nullptr,FILE_BEGIN) && SetEndOfFile(f);
        }
        DWORD last=ok?ERROR_SUCCESS:GetLastError();
        CloseHandle(f);
        if(!ok) return HRESULT_FROM_WIN32(last);
        return applySecurity(path); // Repair ACL even if receiver created this file earlier.
    };
    hr=makeFile(L"video.bus",3840ull*2160ull*4ull+4096ull); if(FAILED(hr)){ LocalFree(sd); return hr; }
    hr=makeFile(L"audio.bus",2ull*1024ull*1024ull);
    LocalFree(sd);
    return hr;
}

static HRESULT InstallCamera() {
    HRESULT prep=PrepareSharedBusDirectory(); if(FAILED(prep)) return prep;
    wchar_t exePath[MAX_PATH]{}; GetModuleFileNameW(nullptr,exePath,MAX_PATH);
    std::filesystem::path source = std::filesystem::path(exePath).parent_path()/L"VirtualCameraMediaSource.dll";
    if(!std::filesystem::exists(source)) { Show(L"VirtualCameraMediaSource.dll must be beside this setup file.",MB_OK|MB_ICONERROR); return HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND); }
    wchar_t pf[MAX_PATH]{}; if(!GetEnvironmentVariableW(L"ProgramFiles",pf,MAX_PATH)) return HRESULT_FROM_WIN32(GetLastError());
    std::filesystem::path dir=std::filesystem::path(pf)/L"PhoneBridge"; std::error_code ec; std::filesystem::create_directories(dir,ec);
    std::filesystem::path dest=dir/L"VirtualCameraMediaSource.dll";
    if(!CopyFileW(source.c_str(),dest.c_str(),FALSE)) return HRESULT_FROM_WIN32(GetLastError());
    if(!RegisterCom(dest.wstring())) return HRESULT_FROM_WIN32(GetLastError());

    HRESULT hr=MFStartup(MF_VERSION); if(FAILED(hr)) return hr;
    IMFVirtualCamera* cam=nullptr;
    hr=OpenCamera(&cam);
    if(SUCCEEDED(hr) && cam) {
        hr=cam->SetUINT32(VCAM_KIND_PB,0); // Synthetic media source -> patched PhoneBridge frames.
        if(SUCCEEDED(hr)) hr=cam->Start(nullptr);
        cam->Shutdown(); cam->Release();
    }
    MFShutdown(); return hr;
}
static HRESULT RemoveCamera() {
    HRESULT hr=MFStartup(MF_VERSION); if(FAILED(hr)) return hr;
    IMFVirtualCamera* cam=nullptr; hr=OpenCamera(&cam);
    if(SUCCEEDED(hr) && cam) { HRESULT r=cam->Remove(); cam->Shutdown(); cam->Release(); if(FAILED(r)) hr=r; }
    MFShutdown();
    UnregisterCom();
    wchar_t pf[MAX_PATH]{}; if(GetEnvironmentVariableW(L"ProgramFiles",pf,MAX_PATH)) {
        std::filesystem::path dll=std::filesystem::path(pf)/L"PhoneBridge"/L"VirtualCameraMediaSource.dll";
        if(!DeleteFileW(dll.c_str())) MoveFileExW(dll.c_str(),nullptr,MOVEFILE_DELAY_UNTIL_REBOOT);
    }
    return hr;
}
int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR cmd,int) {
    std::wstring args=cmd?cmd:L""; bool remove=args.find(L"/uninstall")!=std::wstring::npos;
    if(!IsElevated()) {
        wchar_t exe[MAX_PATH]{}; GetModuleFileNameW(nullptr,exe,MAX_PATH);
        HINSTANCE r=ShellExecuteW(nullptr,L"runas",exe,remove?L"/uninstall":L"/install",nullptr,SW_SHOWNORMAL);
        return ((INT_PTR)r>32)?0:3;
    }
    HRESULT hr=CoInitializeEx(nullptr,COINIT_MULTITHREADED); bool co=SUCCEEDED(hr)||hr==RPC_E_CHANGED_MODE;
    hr=remove?RemoveCamera():InstallCamera();
    if(co && hr!=RPC_E_CHANGED_MODE) CoUninitialize();
    if(SUCCEEDED(hr)) Show(remove?L"PhoneBridge Camera removed.":L"PhoneBridge Camera installed. Restart Zoom, Meet, Teams or OBS so it can refresh the camera list.");
    else Show(std::wstring(remove?L"Removal failed: ":L"Installation failed: ")+HrText(hr),MB_OK|MB_ICONERROR);
    return FAILED(hr)?1:0;
}
