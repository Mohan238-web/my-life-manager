from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')
if 'static bool gSilent=false;' not in s:
    s=s.replace('static constexpr wchar_t kFriendly[] = L"PhoneBridge Camera";\n', 'static constexpr wchar_t kFriendly[] = L"PhoneBridge Camera";\nstatic bool gSilent=false;\n')
s=s.replace('static void Show(const std::wstring& s, UINT flags=MB_OK|MB_ICONINFORMATION) {\n    MessageBoxW(nullptr,s.c_str(),L"PhoneBridge Virtual Camera",flags);\n}', 'static void Show(const std::wstring& s, UINT flags=MB_OK|MB_ICONINFORMATION) {\n    if(!gSilent) MessageBoxW(nullptr,s.c_str(),L"PhoneBridge Virtual Camera",flags);\n}')
old='''int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR cmd,int) {\n    std::wstring args=cmd?cmd:L""; bool remove=args.find(L"/uninstall")!=std::wstring::npos;'''
new='''int WINAPI wWinMain(HINSTANCE,HINSTANCE,LPWSTR cmd,int) {\n    std::wstring args=cmd?cmd:L""; gSilent=args.find(L"/silent")!=std::wstring::npos; bool remove=args.find(L"/uninstall")!=std::wstring::npos;'''
if old in s: s=s.replace(old,new,1)
s=s.replace('HINSTANCE r=ShellExecuteW(nullptr,L"runas",exe,remove?L"/uninstall":L"/install",nullptr,SW_SHOWNORMAL);', 'std::wstring elevateArgs=remove?L"/uninstall":L"/install"; if(gSilent) elevateArgs+=L" /silent"; HINSTANCE r=ShellExecuteW(nullptr,L"runas",exe,elevateArgs.c_str(),nullptr,SW_SHOWNORMAL);')
p.write_text(s,encoding='utf-8')
print('Enabled /silent mode for PhoneBridge virtual camera setup')
