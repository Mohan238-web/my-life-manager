from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def rep(old,new,label):
    global s
    if old not in s:
        raise SystemExit(f'v1.3 camera patch anchor missing: {label}')
    s=s.replace(old,new,1)

rep('''static HRESULT OpenCamera(IMFVirtualCamera** out) {
    return MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource,
        MFVirtualCameraLifetime_System, MFVirtualCameraAccess_CurrentUser,
        kFriendly, kClsid, nullptr, 0, out);
}
''','''static HRESULT OpenCameraWithAccess(MFVirtualCameraAccess access, IMFVirtualCamera** out) {
    return MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource,
        MFVirtualCameraLifetime_System, access,
        kFriendly, kClsid, nullptr, 0, out);
}
static HRESULT OpenCamera(IMFVirtualCamera** out) {
    // v1.3 is installed elevated and registers for all users. This avoids the
    // per-user registration boundary that can prevent browsers/recorders from
    // enumerating a camera installed from an elevated setup process.
    return OpenCameraWithAccess(MFVirtualCameraAccess_AllUsers,out);
}
static HRESULT OpenLegacyCurrentUserCamera(IMFVirtualCamera** out) {
    return OpenCameraWithAccess(MFVirtualCameraAccess_CurrentUser,out);
}
''','all-users camera open')

rep('''    HRESULT hr=MFStartup(MF_VERSION); if(FAILED(hr)) return hr;
    IMFVirtualCamera* cam=nullptr;
    hr=OpenCamera(&cam);
''','''    HRESULT hr=MFStartup(MF_VERSION); if(FAILED(hr)) return hr;

    // Migrate away from v1.2's CurrentUser registration first. The all-users
    // camera below is a different Media Foundation registration key even though
    // it uses the same friendly name and media-source CLSID.
    IMFVirtualCamera* legacy=nullptr;
    if(SUCCEEDED(OpenLegacyCurrentUserCamera(&legacy)) && legacy){
        legacy->Remove();
        legacy->Shutdown();
        legacy->Release();
    }

    IMFVirtualCamera* cam=nullptr;
    hr=OpenCamera(&cam);
''','migrate current-user camera')

old='''static HRESULT RemoveCamera() {
    HRESULT hr=MFStartup(MF_VERSION); if(FAILED(hr)) return hr;
    IMFVirtualCamera* cam=nullptr; hr=OpenCamera(&cam);
    if(SUCCEEDED(hr) && cam) { HRESULT r=cam->Remove(); cam->Shutdown(); cam->Release(); if(FAILED(r)) hr=r; }
    MFShutdown();
    UnregisterCom();
    wchar_t pf[MAX_PATH]{}; if(GetEnvironmentVariableW(L"ProgramFiles",pf,MAX_PATH)) {
        std::filesystem::path dir=std::filesystem::path(pf)/L"PhoneBridge";
        CleanupOldMediaSourceDlls(dir);
    }
    return hr;
}
'''
new='''static HRESULT RemoveCamera() {
    HRESULT hr=MFStartup(MF_VERSION); if(FAILED(hr)) return hr;
    HRESULT result=S_OK;
    IMFVirtualCamera* cam=nullptr;
    HRESULT openHr=OpenCamera(&cam);
    if(SUCCEEDED(openHr) && cam){ HRESULT r=cam->Remove(); cam->Shutdown(); cam->Release(); if(FAILED(r)) result=r; }
    cam=nullptr;
    openHr=OpenLegacyCurrentUserCamera(&cam);
    if(SUCCEEDED(openHr) && cam){ HRESULT r=cam->Remove(); cam->Shutdown(); cam->Release(); if(FAILED(r) && SUCCEEDED(result)) result=r; }
    MFShutdown();
    UnregisterCom();
    wchar_t pf[MAX_PATH]{}; if(GetEnvironmentVariableW(L"ProgramFiles",pf,MAX_PATH)) {
        std::filesystem::path dir=std::filesystem::path(pf)/L"PhoneBridge";
        CleanupOldMediaSourceDlls(dir);
    }
    return result;
}
'''
rep(old,new,'remove both camera scopes')

for marker in ['MFVirtualCameraAccess_AllUsers','OpenLegacyCurrentUserCamera','Migrate away from v1.2']:
    if marker not in s: raise SystemExit(f'v1.3 camera marker missing: {marker}')

p.write_text(s,encoding='utf-8',newline='\n')
print('Patched PhoneBridge Camera to AllUsers with v1.2 CurrentUser migration')
