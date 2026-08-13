from pathlib import Path
import re, sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

# v1.1 hardening intentionally wraps the proven v1.0 media pipeline.
# Do not change CameraX NV21, TurboJPEG BGRA or Direct2D rendering here.
for inc in ['#include <filesystem>\n', '#include <fstream>\n']:
    if inc not in s:
        s = s.replace('#include <iomanip>\n', '#include <iomanip>\n' + inc, 1)

# Single-instance state.
anchor = 'HWND g_zoom{};\n'
if 'g_singleInstanceMutex' not in s:
    if anchor not in s:
        raise SystemExit('v1.1 global anchor not found')
    s = s.replace(anchor, anchor + 'HANDLE g_singleInstanceMutex{};\n', 1)

# Logging + crash diagnostics. Keep logs local to the PC; no cloud upload.
post_anchor = 'void postText(UINT msg, const std::wstring& text)'
if 'std::filesystem::path phoneBridgeLogDir()' not in s:
    pos = s.find(post_anchor)
    if pos < 0:
        raise SystemExit('postText anchor not found')
    helpers = r'''std::filesystem::path phoneBridgeLogDir(){
    wchar_t local[MAX_PATH]{};
    DWORD n=GetEnvironmentVariableW(L"LOCALAPPDATA",local,MAX_PATH);
    std::filesystem::path dir=(n&&n<MAX_PATH)?std::filesystem::path(local):std::filesystem::temp_directory_path();
    dir/=L"PhoneBridge"; dir/=L"Logs";
    std::error_code ec; std::filesystem::create_directories(dir,ec);
    return dir;
}

void rotatePhoneBridgeLog(){
    std::error_code ec;
    auto log=phoneBridgeLogDir()/L"PhoneBridge.log";
    if(!std::filesystem::exists(log,ec) || ec) return;
    auto size=std::filesystem::file_size(log,ec);
    if(ec || size < 2ull*1024ull*1024ull) return;
    auto old=phoneBridgeLogDir()/L"PhoneBridge.previous.log";
    std::filesystem::remove(old,ec); ec.clear();
    std::filesystem::rename(log,old,ec);
}

void phoneBridgeLog(const std::wstring& text){
    static std::mutex logMutex;
    std::lock_guard<std::mutex> lk(logMutex);
    rotatePhoneBridgeLog();
    SYSTEMTIME st{}; GetLocalTime(&st);
    auto path=phoneBridgeLogDir()/L"PhoneBridge.log";
    std::wofstream out(path,std::ios::app);
    if(!out) return;
    out<<L"["<<std::setfill(L'0')<<std::setw(4)<<st.wYear<<L"-"<<std::setw(2)<<st.wMonth<<L"-"<<std::setw(2)<<st.wDay
       <<L" "<<std::setw(2)<<st.wHour<<L":"<<std::setw(2)<<st.wMinute<<L":"<<std::setw(2)<<st.wSecond<<L"] "<<text<<L"\n";
}

LONG WINAPI phoneBridgeCrashFilter(EXCEPTION_POINTERS* ep){
    std::wstringstream ss; ss<<L"Unhandled Windows exception";
    if(ep&&ep->ExceptionRecord) ss<<L" code=0x"<<std::hex<<(unsigned long)ep->ExceptionRecord->ExceptionCode;
    phoneBridgeLog(ss.str());
    return EXCEPTION_EXECUTE_HANDLER;
}

'''
    s = s[:pos] + helpers + s[pos:]

# Every visible status change is also written to the local diagnostic log.
pattern = r'void postText\(UINT msg, const std::wstring& text\)\{'
if 'if(msg==WM_PB_STATUS) phoneBridgeLog(text);' not in s:
    s, count = re.subn(pattern,
        'void postText(UINT msg, const std::wstring& text){ if(msg==WM_PB_STATUS) phoneBridgeLog(text); ',
        s, count=1)
    if count != 1:
        raise SystemExit('postText patch failed')

# Make the release identity visible without changing controls/media behavior.
s = s.replace('PhoneBridge v1.0', 'PhoneBridge v1.1 RC')
s = s.replace('PhoneBridge - Camera & Microphone', 'PhoneBridge v1.1 RC - Camera & Microphone')

# One receiver instance prevents the confusing "port 8989 already in use" state caused by opening twice.
main_re = re.compile(r'int WINAPI wWinMain\(HINSTANCE hInst,HINSTANCE,LPWSTR cmd,int show\)\{')
if 'Local\\PhoneBridge-v1.1-SingleInstance' not in s:
    replacement = r'''int WINAPI wWinMain(HINSTANCE hInst,HINSTANCE,LPWSTR cmd,int show){
    SetUnhandledExceptionFilter(phoneBridgeCrashFilter);
    g_singleInstanceMutex=CreateMutexW(nullptr,FALSE,L"Local\\PhoneBridge-v1.1-SingleInstance");
    if(g_singleInstanceMutex && GetLastError()==ERROR_ALREADY_EXISTS){
        HWND existing=FindWindowW(L"PhoneBridgeReceiverWindow",nullptr);
        if(existing){ ShowWindow(existing,SW_RESTORE); SetForegroundWindow(existing); }
        CloseHandle(g_singleInstanceMutex); g_singleInstanceMutex=nullptr;
        return 0;
    }
    phoneBridgeLog(L"PhoneBridge v1.1 RC starting");'''
    s, count = main_re.subn(replacement, s, count=1)
    if count != 1:
        raise SystemExit('wWinMain anchor not found')

# Log orderly shutdown and release the process mutex.
if 'phoneBridgeLog(L"PhoneBridge shutting down")' not in s:
    old = 'case WM_DESTROY:{ releaseD2DTarget();'
    if old in s:
        s = s.replace(old,
            'case WM_DESTROY:{ phoneBridgeLog(L"PhoneBridge shutting down"); if(g_singleInstanceMutex){ CloseHandle(g_singleInstanceMutex); g_singleInstanceMutex=nullptr; } releaseD2DTarget();',1)
    else:
        old2='case WM_DESTROY:{ g_running=false;'
        if old2 not in s:
            raise SystemExit('WM_DESTROY anchor not found')
        s=s.replace(old2,
            'case WM_DESTROY:{ phoneBridgeLog(L"PhoneBridge shutting down"); if(g_singleInstanceMutex){ CloseHandle(g_singleInstanceMutex); g_singleInstanceMutex=nullptr; } g_running=false;',1)

# CI-visible safety markers.
required = [
    'D2D1CreateFactory',
    'DXGI_FORMAT_B8G8R8A8_UNORM',
    'tjDecompress2',
    'MFCreateSinkWriterFromURL',
    'loadOrCreatePin',
    'phoneBridgeLogDir',
    'PhoneBridge-v1.1-SingleInstance',
]
for marker in required:
    if marker not in s:
        raise SystemExit(f'v1.1 safety marker missing: {marker}')
if 'StretchDIBits(' in s:
    raise SystemExit('Regression: legacy color-corrupting renderer returned')

p.write_text(s, encoding='utf-8')
print('Applied PhoneBridge v1.1 Windows production-hardening wrapper')
