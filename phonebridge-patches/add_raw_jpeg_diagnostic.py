from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

s = s.replace(
    'std::wstring g_expectedPin;\n',
    'std::wstring g_expectedPin;\nstd::atomic<bool> g_rawJpegSaved{false};\nvoid postText(UINT msg, const std::wstring& text);\n')

anchor = 'std::wstring randomPin(){ std::random_device rd; std::mt19937 gen(rd()); std::uniform_int_distribution<int> d(0,999999); wchar_t b[7]; swprintf_s(b,L"%06d",d(gen)); return b; }\n\n'
helper = r'''void saveRawJpegOnce(const std::vector<uint8_t>& payload){
    if(payload.empty() || g_rawJpegSaved.exchange(true)) return;

    wchar_t exe[MAX_PATH]{};
    std::wstring path;
    if(GetModuleFileNameW(nullptr,exe,MAX_PATH)){
        path=exe;
        size_t slash=path.find_last_of(L"\\/");
        if(slash!=std::wstring::npos) path.resize(slash+1); else path.clear();
        path+=L"PhoneBridge-Raw-Frame.jpg";
    }

    auto writeFile=[&](const std::wstring& candidate)->bool{
        HANDLE f=CreateFileW(candidate.c_str(),GENERIC_WRITE,FILE_SHARE_READ,nullptr,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
        if(f==INVALID_HANDLE_VALUE) return false;
        DWORD written=0;
        BOOL ok=WriteFile(f,payload.data(),(DWORD)payload.size(),&written,nullptr);
        CloseHandle(f);
        return ok && written==payload.size();
    };

    if(path.empty() || !writeFile(path)){
        wchar_t temp[MAX_PATH]{};
        DWORD n=GetTempPathW(MAX_PATH,temp);
        if(n && n<MAX_PATH){
            path=std::wstring(temp)+L"PhoneBridge-Raw-Frame.jpg";
            if(!writeFile(path)) path.clear();
        } else path.clear();
    }

    if(!path.empty()) postText(WM_PB_STATUS,L"Paired - untouched phone JPEG saved: "+path);
}

'''
if anchor not in s:
    raise SystemExit('randomPin anchor not found')
s = s.replace(anchor, anchor + helper, 1)

s = s.replace(
    'g_paired=false; std::string device="Phone"; uint64_t frames=0;',
    'g_paired=false; g_rawJpegSaved=false; std::string device="Phone"; uint64_t frames=0;')

needle = '            if(type==(uint8_t)pbr::Type::VideoJpeg){\n                std::vector<uint8_t> bgra;'
replacement = '            if(type==(uint8_t)pbr::Type::VideoJpeg){\n                saveRawJpegOnce(payload);\n                std::vector<uint8_t> bgra;'
if needle not in s:
    raise SystemExit('VideoJpeg block not found')
s = s.replace(needle, replacement, 1)

p.write_text(s, encoding='utf-8')
print(f'Added untouched JPEG diagnostic: {p}')
