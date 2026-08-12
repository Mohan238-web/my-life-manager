from pathlib import Path
import re, sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')

# Headers / libraries.
if '#include <filesystem>' not in s:
    s=s.replace('#include <iomanip>\n', '#include <iomanip>\n#include <filesystem>\n#include <fstream>\n')
if '#pragma comment(lib,"advapi32.lib")' not in s:
    s=s.replace('#pragma comment(lib,"comctl32.lib")\n', '#pragma comment(lib,"comctl32.lib")\n#pragma comment(lib,"advapi32.lib")\n')

# Extra app message and controls.
s=s.replace('constexpr UINT WM_PB_STATS  = WM_APP + 4;\n', 'constexpr UINT WM_PB_STATS  = WM_APP + 4;\nconstexpr UINT WM_PB_TRUSTED = WM_APP + 5;\n')
s=s.replace('constexpr int IDC_OPEN_CAM   = 1008;\n', '''constexpr int IDC_OPEN_CAM   = 1008;\nconstexpr int IDC_QUALITY    = 1010;\nconstexpr int IDC_FPS        = 1011;\nconstexpr int IDC_SNAPSHOT   = 1012;\nconstexpr int IDC_RECORD     = 1013;\nconstexpr int IDC_STARTUP    = 1014;\n''')

anchor='HWND g_zoom{};\n'
if 'HWND g_quality{};' not in s:
    insert='''HWND g_zoom{};\nHWND g_trusted{};\nHWND g_qualityLabel{};\nHWND g_quality{};\nHWND g_fpsLabel{};\nHWND g_fps{};\nHWND g_snapshot{};\nHWND g_record{};\nHWND g_startup{};\n'''
    if anchor not in s: raise SystemExit('g_zoom anchor missing')
    s=s.replace(anchor,insert,1)

# Persistent / recording state.
state_anchor='std::wstring g_expectedPin;\n'
if 'g_lastJpeg' not in s:
    state='''std::wstring g_expectedPin;\nstd::vector<uint8_t> g_lastJpeg;\nstd::mutex g_recordMutex;\nstd::ofstream g_recordVideo;\nstd::ofstream g_recordAudio;\nstd::filesystem::path g_recordDir;\nuint32_t g_recordAudioBytes=0;\nstd::atomic<bool> g_recording{false};\nint g_qualityValue=78;\nint g_fpsValue=20;\n'''
    if state_anchor not in s: raise SystemExit('settings state anchor missing')
    s=s.replace(state_anchor,state,1)

# Helpers for registry, startup, snapshot and recording.
helper_anchor='std::wstring randomPin(){ std::random_device rd; std::mt19937 gen(rd()); std::uniform_int_distribution<int> d(0,999999); wchar_t b[7]; swprintf_s(b,L"%06d",d(gen)); return b; }\n\n'
if 'loadOrCreatePin()' not in s:
    helpers=r'''std::wstring regString(const wchar_t* name){
    HKEY h{}; if(RegOpenKeyExW(HKEY_CURRENT_USER,L"Software\\PhoneBridge",0,KEY_READ,&h)!=ERROR_SUCCESS) return {};
    wchar_t value[512]{}; DWORD type=REG_SZ, bytes=sizeof(value);
    LONG r=RegQueryValueExW(h,name,nullptr,&type,(BYTE*)value,&bytes); RegCloseKey(h);
    return r==ERROR_SUCCESS&&type==REG_SZ?std::wstring(value):std::wstring{};
}
void setRegString(const wchar_t* name,const std::wstring& value){
    HKEY h{}; DWORD d{}; if(RegCreateKeyExW(HKEY_CURRENT_USER,L"Software\\PhoneBridge",0,nullptr,0,KEY_WRITE,nullptr,&h,&d)!=ERROR_SUCCESS) return;
    RegSetValueExW(h,name,0,REG_SZ,(const BYTE*)value.c_str(),(DWORD)((value.size()+1)*sizeof(wchar_t))); RegCloseKey(h);
}
DWORD regDword(const wchar_t* name,DWORD fallback){
    HKEY h{}; if(RegOpenKeyExW(HKEY_CURRENT_USER,L"Software\\PhoneBridge",0,KEY_READ,&h)!=ERROR_SUCCESS) return fallback;
    DWORD v=fallback,type=REG_DWORD,bytes=sizeof(v); LONG r=RegQueryValueExW(h,name,nullptr,&type,(BYTE*)&v,&bytes); RegCloseKey(h);
    return r==ERROR_SUCCESS&&type==REG_DWORD?v:fallback;
}
void setRegDword(const wchar_t* name,DWORD value){
    HKEY h{}; DWORD d{}; if(RegCreateKeyExW(HKEY_CURRENT_USER,L"Software\\PhoneBridge",0,nullptr,0,KEY_WRITE,nullptr,&h,&d)!=ERROR_SUCCESS) return;
    RegSetValueExW(h,name,0,REG_DWORD,(const BYTE*)&value,sizeof(value)); RegCloseKey(h);
}
std::wstring loadOrCreatePin(){
    std::wstring p=regString(L"PairingPin");
    bool valid=p.size()==6&&std::all_of(p.begin(),p.end(),[](wchar_t c){ return iswdigit(c)!=0; });
    if(!valid){ p=randomPin(); setRegString(L"PairingPin",p); }
    return p;
}
std::wstring currentExe(){ wchar_t b[MAX_PATH]{}; return GetModuleFileNameW(nullptr,b,MAX_PATH)?std::wstring(b):std::wstring{}; }
bool startupEnabled(){
    HKEY h{}; if(RegOpenKeyExW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",0,KEY_READ,&h)!=ERROR_SUCCESS) return false;
    wchar_t b[1024]{}; DWORD type=REG_SZ,bytes=sizeof(b); LONG r=RegQueryValueExW(h,L"PhoneBridge",nullptr,&type,(BYTE*)b,&bytes); RegCloseKey(h); return r==ERROR_SUCCESS;
}
void setStartup(bool on){
    HKEY h{}; DWORD d{}; if(RegCreateKeyExW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",0,nullptr,0,KEY_WRITE,nullptr,&h,&d)!=ERROR_SUCCESS) return;
    if(on){ std::wstring exe=L"\""+currentExe()+L"\""; RegSetValueExW(h,L"PhoneBridge",0,REG_SZ,(const BYTE*)exe.c_str(),(DWORD)((exe.size()+1)*sizeof(wchar_t))); }
    else RegDeleteValueW(h,L"PhoneBridge");
    RegCloseKey(h); setRegDword(L"StartWithWindows",on?1:0);
}
std::wstring stamp(){ SYSTEMTIME t{}; GetLocalTime(&t); wchar_t b[64]{}; swprintf_s(b,L"%04u-%02u-%02u_%02u-%02u-%02u",t.wYear,t.wMonth,t.wDay,t.wHour,t.wMinute,t.wSecond); return b; }
std::filesystem::path userFolder(const wchar_t* leaf){
    wchar_t u[MAX_PATH]{}; DWORD n=GetEnvironmentVariableW(L"USERPROFILE",u,MAX_PATH); std::filesystem::path p=n?std::filesystem::path(u):std::filesystem::current_path();
    p/=leaf; p/=L"PhoneBridge"; std::error_code ec; std::filesystem::create_directories(p,ec); return p;
}
void writeU16(std::ofstream& f,uint16_t v){ char b[2]={(char)(v&255),(char)((v>>8)&255)}; f.write(b,2); }
void writeU32(std::ofstream& f,uint32_t v){ char b[4]={(char)(v&255),(char)((v>>8)&255),(char)((v>>16)&255),(char)((v>>24)&255)}; f.write(b,4); }
void writeWavHeader(std::ofstream& f,uint32_t dataBytes){
    f.seekp(0); f.write("RIFF",4); writeU32(f,36+dataBytes); f.write("WAVEfmt ",8); writeU32(f,16); writeU16(f,1); writeU16(f,1); writeU32(f,48000); writeU32(f,96000); writeU16(f,2); writeU16(f,16); f.write("data",4); writeU32(f,dataBytes);
}
bool startRecordingSession(){
    std::lock_guard<std::mutex> lk(g_recordMutex); if(g_recording) return true;
    g_recordDir=userFolder(L"Videos")/stamp(); std::error_code ec; std::filesystem::create_directories(g_recordDir,ec); if(ec) return false;
    g_recordVideo.open(g_recordDir/L"video.mjpeg",std::ios::binary|std::ios::trunc); g_recordAudio.open(g_recordDir/L"audio.wav",std::ios::binary|std::ios::trunc);
    if(!g_recordVideo||!g_recordAudio){ g_recordVideo.close(); g_recordAudio.close(); return false; }
    g_recordAudioBytes=0; writeWavHeader(g_recordAudio,0); g_recordAudio.seekp(44);
    std::wofstream info(g_recordDir/L"README.txt"); if(info) info<<L"PhoneBridge v1 recording\nvideo.mjpeg = concatenated JPEG video at approximately "<<g_fpsValue<<L" fps\naudio.wav = 48 kHz mono PCM\n";
    g_recording=true; return true;
}
void stopRecordingSession(){
    std::lock_guard<std::mutex> lk(g_recordMutex); if(!g_recording.exchange(false)) return;
    if(g_recordAudio){ writeWavHeader(g_recordAudio,g_recordAudioBytes); g_recordAudio.flush(); g_recordAudio.close(); }
    if(g_recordVideo){ g_recordVideo.flush(); g_recordVideo.close(); }
}
void recordVideo(const std::vector<uint8_t>& jpeg){ std::lock_guard<std::mutex> lk(g_recordMutex); if(g_recording&&g_recordVideo) g_recordVideo.write((const char*)jpeg.data(),(std::streamsize)jpeg.size()); }
void recordAudio(const std::vector<uint8_t>& pcm){ std::lock_guard<std::mutex> lk(g_recordMutex); if(g_recording&&g_recordAudio){ g_recordAudio.write((const char*)pcm.data(),(std::streamsize)pcm.size()); g_recordAudioBytes+=(uint32_t)pcm.size(); } }
bool saveSnapshot(std::filesystem::path& out){
    std::vector<uint8_t> jpg; { std::lock_guard<std::mutex> lk(g_frameMutex); jpg=g_lastJpeg; }
    if(jpg.empty()) return false; out=userFolder(L"Pictures")/(L"PhoneBridge-"+stamp()+L".jpg"); std::ofstream f(out,std::ios::binary|std::ios::trunc); if(!f) return false; f.write((const char*)jpg.data(),(std::streamsize)jpg.size()); return (bool)f;
}
void sendPerfControls(){
    sendControl(std::string("{\"cmd\":\"quality\",\"value\":")+std::to_string(g_qualityValue)+"}");
    sendControl(std::string("{\"cmd\":\"fps\",\"value\":")+std::to_string(g_fpsValue)+"}");
}

'''
    if helper_anchor not in s: raise SystemExit('randomPin helper anchor missing')
    s=s.replace(helper_anchor,helper_anchor+helpers,1)

# Pair acceptance: persist device and restore current performance controls.
old='''if(type==(uint8_t)pbr::Type::Pair){ bool ok=(extractPin(payload)==narrow(g_expectedPin)); g_paired=ok; postText(WM_PB_STATUS,ok?L"Paired: "+widen(device)+L" - camera and microphone ready":L"Pairing PIN rejected"); if(!ok) break;\n                setRemoteVideo(g_videoEnabled.load()); setRemoteAudio(g_audioEnabled.load()); continue; }'''
new='''if(type==(uint8_t)pbr::Type::Pair){ bool ok=(extractPin(payload)==narrow(g_expectedPin)); g_paired=ok; postText(WM_PB_STATUS,ok?L"Paired: "+widen(device)+L" - camera and microphone ready":L"Pairing PIN rejected"); if(!ok) break;\n                setRegString(L"TrustedDevice",widen(device)); postText(WM_PB_TRUSTED,L"Trusted phone: "+widen(device));\n                setRemoteVideo(g_videoEnabled.load()); setRemoteAudio(g_audioEnabled.load()); sendPerfControls(); continue; }'''
if 'setRegString(L"TrustedDevice"' not in s:
    if old not in s: raise SystemExit('pair block not found')
    s=s.replace(old,new,1)

# Save latest raw JPEG and record streams before decode.
needle='''            if(type==(uint8_t)pbr::Type::VideoJpeg){\n                std::vector<uint8_t> bgra;'''
repl='''            if(type==(uint8_t)pbr::Type::VideoJpeg){\n                { std::lock_guard<std::mutex> lk(g_frameMutex); g_lastJpeg=payload; }\n                recordVideo(payload);\n                std::vector<uint8_t> bgra;'''
if 'recordVideo(payload);' not in s:
    if needle not in s: raise SystemExit('video packet anchor missing')
    s=s.replace(needle,repl,1)
needle2='''            } else if(type==(uint8_t)pbr::Type::AudioPcm16){\n                const int16_t* samples='''
repl2='''            } else if(type==(uint8_t)pbr::Type::AudioPcm16){\n                recordAudio(payload);\n                const int16_t* samples='''
if 'recordAudio(payload);' not in s:
    if needle2 not in s: raise SystemExit('audio packet anchor missing')
    s=s.replace(needle2,repl2,1)

# Stop any open recording on disconnect.
disc='''        g_paired=false; closesocket(s); postText(WM_PB_STATUS,L"Phone disconnected - waiting for reconnect...");'''
if 'stopRecordingSession(); closesocket(s);' not in s:
    if disc not in s: raise SystemExit('disconnect anchor missing')
    s=s.replace(disc,'''        g_paired=false; stopRecordingSession(); if(g_record) SetWindowTextW(g_record,L"Start recording"); closesocket(s); postText(WM_PB_STATUS,L"Phone disconnected - waiting for reconnect...");''',1)

# Replace side-panel layout with v1 layout.
layout=r'''void layout(HWND hwnd){
    RECT r{}; GetClientRect(hwnd,&r); int w=r.right-r.left, h=r.bottom-r.top;
    int side=320; int pad=18; int x=w-side+pad; int bw=side-pad*2;
    MoveWindow(g_status,pad,18,w-side-pad*2,30,TRUE);
    MoveWindow(g_pin,x,18,bw,52,TRUE);
    MoveWindow(g_trusted,x,76,bw,26,TRUE);
    MoveWindow(g_share,x,108,bw,36,TRUE);
    MoveWindow(g_camera,x,150,bw,30,TRUE);
    MoveWindow(g_mic,x,184,bw,30,TRUE);
    MoveWindow(g_switch,x,222,bw,34,TRUE);
    MoveWindow(g_torch,x,262,bw,28,TRUE);
    MoveWindow(g_zoom,x,296,bw,30,TRUE);
    MoveWindow(g_audioLabel,x,334,bw,22,TRUE);
    MoveWindow(g_audioBar,x,358,bw,20,TRUE);
    MoveWindow(g_qualityLabel,x,390,bw,20,TRUE);
    MoveWindow(g_quality,x,412,bw,120,TRUE);
    MoveWindow(g_fpsLabel,x,450,bw,20,TRUE);
    MoveWindow(g_fps,x,472,bw,120,TRUE);
    MoveWindow(g_snapshot,x,516,bw,34,TRUE);
    MoveWindow(g_record,x,556,bw,34,TRUE);
    MoveWindow(g_startup,x,600,bw,30,TRUE);
    MoveWindow(g_stats,pad,h-42,w-side-pad*2,26,TRUE);
}
'''
pat=r'void layout\(HWND hwnd\)\{.*?\n\}\n\nRECT previewRect'
m=re.search(pat,s,re.S)
if not m: raise SystemExit('layout block not found')
s=s[:m.start()]+layout+'\nRECT previewRect'+s[m.end():]
s=s.replace('return RECT{18,58,(LONG)std::max(200,(int)r.right-298)', 'return RECT{18,58,(LONG)std::max(200,(int)r.right-338)')

# Create v1 controls.
create_anchor='''        g_audioBar=CreateWindowExW(0,PROGRESS_CLASSW,L"",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);\n        g_stats=CreateWindowW(L"STATIC",L"Waiting for stream statistics...",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);'''
create_repl='''        g_audioBar=CreateWindowExW(0,PROGRESS_CLASSW,L"",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);\n        g_trusted=CreateWindowW(L"STATIC",(L"Trusted phone: "+regString(L"TrustedDevice")).c_str(),WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);\n        g_qualityLabel=CreateWindowW(L"STATIC",L"Video quality",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);\n        g_quality=CreateWindowW(L"COMBOBOX",L"",WS_CHILD|WS_VISIBLE|CBS_DROPDOWNLIST|WS_VSCROLL,0,0,0,0,hwnd,(HMENU)IDC_QUALITY,nullptr,nullptr);\n        ComboBox_AddString(g_quality,L"Data saver (Q65)"); ComboBox_AddString(g_quality,L"Balanced (Q78)"); ComboBox_AddString(g_quality,L"High quality (Q90)");\n        ComboBox_SetCurSel(g_quality,g_qualityValue>=88?2:(g_qualityValue<=68?0:1));\n        g_fpsLabel=CreateWindowW(L"STATIC",L"Frame rate",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);\n        g_fps=CreateWindowW(L"COMBOBOX",L"",WS_CHILD|WS_VISIBLE|CBS_DROPDOWNLIST|WS_VSCROLL,0,0,0,0,hwnd,(HMENU)IDC_FPS,nullptr,nullptr);\n        ComboBox_AddString(g_fps,L"15 fps"); ComboBox_AddString(g_fps,L"20 fps"); ComboBox_AddString(g_fps,L"30 fps");\n        ComboBox_SetCurSel(g_fps,g_fpsValue>=30?2:(g_fpsValue<=15?0:1));\n        g_snapshot=CreateWindowW(L"BUTTON",L"Save snapshot",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_SNAPSHOT,nullptr,nullptr);\n        g_record=CreateWindowW(L"BUTTON",L"Start recording",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_RECORD,nullptr,nullptr);\n        g_startup=CreateWindowW(L"BUTTON",L"Start PhoneBridge with Windows",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_STARTUP,nullptr,nullptr);\n        Button_SetCheck(g_startup,startupEnabled()?BST_CHECKED:BST_UNCHECKED);\n        g_stats=CreateWindowW(L"STATIC",L"Waiting for stream statistics...",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);'''
if 'g_snapshot=CreateWindowW' not in s:
    if create_anchor not in s: raise SystemExit('WM_CREATE control anchor missing')
    s=s.replace(create_anchor,create_repl,1)

old_fonts='''        for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_audioLabel,g_stats}) SendMessageW(c,WM_SETFONT,(WPARAM)font,TRUE);'''
new_fonts='''        for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_audioLabel,g_trusted,g_qualityLabel,g_quality,g_fpsLabel,g_fps,g_snapshot,g_record,g_startup,g_stats}) SendMessageW(c,WM_SETFONT,(WPARAM)font,TRUE);'''
s=s.replace(old_fonts,new_fonts)

# Trusted label message handler.
trusted_handler='''    case WM_PB_TRUSTED:{ auto* s=(std::wstring*)lp; if(s){ SetWindowTextW(g_trusted,s->c_str()); delete s; } return 0; }\n'''
if 'case WM_PB_TRUSTED:' not in s:
    target='''    case WM_PB_STATS:{ auto* s=(std::wstring*)lp; if(s){ SetWindowTextW(g_stats,s->c_str()); delete s; } return 0; }\n'''
    if target not in s: raise SystemExit('stats handler missing')
    s=s.replace(target,target+trusted_handler,1)

# Combo selection handling before BN_CLICKED filter.
cmd='''    case WM_COMMAND:{\n        int id=LOWORD(wp), code=HIWORD(wp); if(code!=BN_CLICKED) break;'''
cmd_new='''    case WM_COMMAND:{\n        int id=LOWORD(wp), code=HIWORD(wp);\n        if(id==IDC_QUALITY && code==CBN_SELCHANGE){ int sel=ComboBox_GetCurSel(g_quality); g_qualityValue=sel==0?65:(sel==2?90:78); setRegDword(L"Quality",g_qualityValue); if(g_paired) sendPerfControls(); return 0; }\n        if(id==IDC_FPS && code==CBN_SELCHANGE){ int sel=ComboBox_GetCurSel(g_fps); g_fpsValue=sel==0?15:(sel==2?30:20); setRegDword(L"Fps",g_fpsValue); if(g_paired) sendPerfControls(); return 0; }\n        if(code!=BN_CLICKED) break;'''
if 'id==IDC_QUALITY && code==CBN_SELCHANGE' not in s:
    if cmd not in s: raise SystemExit('WM_COMMAND anchor missing')
    s=s.replace(cmd,cmd_new,1)

# Extra button actions before return.
action_anchor='''        else if(id==IDC_SHARE){ bool newState=!(g_videoEnabled.load()||g_audioEnabled.load()); Button_SetCheck(g_camera,newState?BST_CHECKED:BST_UNCHECKED); Button_SetCheck(g_mic,newState?BST_CHECKED:BST_UNCHECKED); setRemoteVideo(newState); setRemoteAudio(newState); SetWindowTextW(g_share,newState?L"Sharing ON":L"Sharing OFF"); }\n        return 0; }'''
action_repl='''        else if(id==IDC_SHARE){ bool newState=!(g_videoEnabled.load()||g_audioEnabled.load()); Button_SetCheck(g_camera,newState?BST_CHECKED:BST_UNCHECKED); Button_SetCheck(g_mic,newState?BST_CHECKED:BST_UNCHECKED); setRemoteVideo(newState); setRemoteAudio(newState); SetWindowTextW(g_share,newState?L"Sharing ON":L"Sharing OFF"); }\n        else if(id==IDC_SNAPSHOT){ std::filesystem::path saved; postText(WM_PB_STATUS,saveSnapshot(saved)?L"Snapshot saved: "+saved.wstring():L"No camera frame available for snapshot"); }\n        else if(id==IDC_RECORD){ if(!g_recording){ if(startRecordingSession()){ SetWindowTextW(g_record,L"Stop recording"); postText(WM_PB_STATUS,L"Recording to: "+g_recordDir.wstring()); } else postText(WM_PB_STATUS,L"Could not start recording"); } else { auto dir=g_recordDir; stopRecordingSession(); SetWindowTextW(g_record,L"Start recording"); postText(WM_PB_STATUS,L"Recording saved: "+dir.wstring()); } }\n        else if(id==IDC_STARTUP){ bool on=Button_GetCheck(g_startup)==BST_CHECKED; setStartup(on); postText(WM_PB_STATUS,on?L"PhoneBridge will start with Windows":L"Start with Windows disabled"); }\n        return 0; }'''
if 'id==IDC_SNAPSHOT' not in s:
    if action_anchor not in s: raise SystemExit('command action anchor missing')
    s=s.replace(action_anchor,action_repl,1)

# Ensure recording closes at app shutdown. Direct2D patch may have expanded this line.
if 'stopRecordingSession();' not in s[s.find('case WM_DESTROY'):s.find('case WM_DESTROY')+400]:
    s=s.replace('case WM_DESTROY:{', 'case WM_DESTROY:{ stopRecordingSession();',1)

# Persist pairing pin and profile; larger v1 window/title.
s=s.replace('    g_expectedPin=randomPin();\n', '    g_expectedPin=loadOrCreatePin();\n    g_qualityValue=(int)regDword(L"Quality",78); if(g_qualityValue!=65&&g_qualityValue!=78&&g_qualityValue!=90) g_qualityValue=78;\n    g_fpsValue=(int)regDword(L"Fps",20); if(g_fpsValue!=15&&g_fpsValue!=20&&g_fpsValue!=30) g_fpsValue=20;\n',1)
s=s.replace('CreateWindowExW(0,wc.lpszClassName,L"PhoneBridge - Camera & Microphone",', 'CreateWindowExW(0,wc.lpszClassName,L"PhoneBridge v1.0 - Camera & Microphone",')
s=s.replace(',1120,700,nullptr,nullptr,hInst,nullptr);', ',1180,760,nullptr,nullptr,hInst,nullptr);')

p.write_text(s,encoding='utf-8')
print('Applied PhoneBridge v1 Windows persistence, snapshots, recording and quality/FPS controls')
