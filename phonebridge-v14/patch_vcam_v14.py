from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def rep(old,new,label):
    global s
    if old not in s:
        raise SystemExit(f'v1.4 camera patch anchor missing: {label}')
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
    // v1.4 intentionally returns to CurrentUser. Browsers run in the same
    // interactive user context, and this avoids the AllUsers registration
    // experiment introduced in v1.3.
    return OpenCameraWithAccess(MFVirtualCameraAccess_CurrentUser,out);
}
static HRESULT OpenAllUsersCamera(IMFVirtualCamera** out) {
    return OpenCameraWithAccess(MFVirtualCameraAccess_AllUsers,out);
}
static void RemoveScope(MFVirtualCameraAccess access) {
    IMFVirtualCamera* cam=nullptr;
    HRESULT hr=OpenCameraWithAccess(access,&cam);
    if(SUCCEEDED(hr) && cam){ cam->Remove(); cam->Shutdown(); cam->Release(); }
}
''','camera access helpers')

rep('''    HRESULT hr=MFStartup(MF_VERSION); if(FAILED(hr)) return hr;
    IMFVirtualCamera* cam=nullptr;
    hr=OpenCamera(&cam);
''','''    HRESULT hr=MFStartup(MF_VERSION); if(FAILED(hr)) return hr;

    // Clean both historical scopes before creating the one v1.4 registration.
    // This removes v1.3 AllUsers duplicates and any stale v1.2 CurrentUser entry.
    RemoveScope(MFVirtualCameraAccess_AllUsers);
    RemoveScope(MFVirtualCameraAccess_CurrentUser);

    IMFVirtualCamera* cam=nullptr;
    hr=OpenCamera(&cam);
''','clean registrations before install')

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
    RemoveScope(MFVirtualCameraAccess_AllUsers);
    RemoveScope(MFVirtualCameraAccess_CurrentUser);
    MFShutdown();
    UnregisterCom();
    wchar_t pf[MAX_PATH]{}; if(GetEnvironmentVariableW(L"ProgramFiles",pf,MAX_PATH)) {
        std::filesystem::path dir=std::filesystem::path(pf)/L"PhoneBridge";
        CleanupOldMediaSourceDlls(dir);
    }
    return S_OK;
}
'''
rep(old,new,'remove both scopes')

oldmain='''int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR cmd,int) {
    std::wstring args=cmd?cmd:L""; bool remove=args.find(L"/uninstall")!=std::wstring::npos;'''
newmain='''int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR cmd,int) {
    std::wstring args=cmd?cmd:L""; bool remove=args.find(L"/uninstall")!=std::wstring::npos; bool repair=args.find(L"/repair")!=std::wstring::npos;'''
if oldmain in s:
    s=s.replace(oldmain,newmain,1)
else:
    # The /silent patch may already have inserted gSilent parsing.
    oldmain2='''int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR cmd,int) {
    std::wstring args=cmd?cmd:L""; gSilent=args.find(L"/silent")!=std::wstring::npos; bool remove=args.find(L"/uninstall")!=std::wstring::npos;'''
    newmain2='''int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR cmd,int) {
    std::wstring args=cmd?cmd:L""; gSilent=args.find(L"/silent")!=std::wstring::npos; bool remove=args.find(L"/uninstall")!=std::wstring::npos; bool repair=args.find(L"/repair")!=std::wstring::npos;'''
    rep(oldmain2,newmain2,'repair argument')

s=s.replace('remove?L"/uninstall":L"/install"','remove?L"/uninstall":(repair?L"/repair":L"/install")')
s=s.replace('remove?RemoveCamera():InstallCamera()','remove?RemoveCamera():InstallCamera()')
s=s.replace('remove?L"PhoneBridge Camera removed.":L"PhoneBridge Camera installed. Restart Zoom, Meet, Teams or OBS so it can refresh the camera list."',
            'remove?L"PhoneBridge Camera removed.":(repair?L"PhoneBridge Camera repaired. Fully close and reopen the browser before testing again.":L"PhoneBridge Camera installed. Fully close and reopen browsers, Camera, Zoom, Meet, Teams or OBS so they refresh the camera list.")')

for marker in ['MFVirtualCameraAccess_CurrentUser','MFVirtualCameraAccess_AllUsers','RemoveScope','Clean both historical scopes','bool repair=']:
    if marker not in s: raise SystemExit(f'v1.4 camera required marker missing: {marker}')

p.write_text(s,encoding='utf-8',newline='\n')
print('Patched PhoneBridge Camera v1.4: clean both scopes, register CurrentUser, add /repair')
