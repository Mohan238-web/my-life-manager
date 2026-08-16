from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def must_replace(old,new,label,count=1):
    global s
    if old not in s:
        raise RuntimeError(f'missing {label}')
    s=s.replace(old,new,count)

def insert_before(marker,text,label):
    global s
    i=s.find(marker)
    if i<0: raise RuntimeError(f'missing {label}')
    s=s[:i]+text+s[i:]

# IDs and globals
must_replace('constexpr int IDC_REPAIR_CAMERA = 1020;\n', '''constexpr int IDC_REPAIR_CAMERA = 1020;
constexpr int IDC_SETTINGS = 1021;
constexpr int IDC_REMEMBER_BUTTONS = 1022;
constexpr int IDC_RUN_WITH = 1023;
constexpr int IDC_PIN_EDIT = 1024;
constexpr int IDC_SAVE_PIN = 1025;
constexpr int IDC_ADDRESS_LABEL = 1026;
constexpr int IDC_COPY_ADDRESS = 1027;
''','ids')
must_replace('HWND g_repairCamera{};\nstd::atomic<bool> g_recording{false};\n', '''HWND g_repairCamera{};
HWND g_settingsButton{};
HWND g_rememberButtons{};
HWND g_runWith{};
HWND g_pinEdit{};
HWND g_savePin{};
HWND g_addressLabel{};
HWND g_copyAddress{};
std::atomic<bool> g_recording{false};
std::atomic<bool> g_settingsOpen{false};
''','hwnd')
must_replace('std::wstring g_expectedPin;\nstd::atomic<bool> g_rawJpegSaved{false};\n', '''std::wstring g_expectedPin;
struct UiPrefs {
    bool rememberButtons = true;
    int runWith = 0;
    bool camera = true;
    bool mic = true;
    bool vbCable = false;
    bool torch = false;
    int zoom = 0;
    int resolution = 0;
    int fps = 2;
    int quality = 92;
};
UiPrefs g_uiPrefs;
std::atomic<bool> g_rawJpegSaved{false};
''','prefs')

# Settings functions before startupEnabled
settings = r'''
std::wstring uiSettingsFile(){
    std::wstring dir=localDataDir();
    return dir.empty()?L"PhoneBridge.ui.ini":dir+L"\\ui-settings.ini";
}

bool parseBool(const std::wstring& v,bool fallback){
    if(v==L"1" || v==L"true" || v==L"on") return true;
    if(v==L"0" || v==L"false" || v==L"off") return false;
    return fallback;
}

int parseInt(const std::wstring& v,int fallback,int lo,int hi){
    try{ int n=std::stoi(v); return std::clamp(n,lo,hi); }catch(...){ return fallback; }
}

void loadUiPrefs(){
    UiPrefs p{};
    std::wifstream in(uiSettingsFile());
    std::wstring line;
    while(std::getline(in,line)){
        auto eq=line.find(L'='); if(eq==std::wstring::npos) continue;
        auto k=line.substr(0,eq), v=line.substr(eq+1);
        if(k==L"remember") p.rememberButtons=parseBool(v,p.rememberButtons);
        else if(k==L"run_with") p.runWith=parseInt(v,p.runWith,0,4);
        else if(k==L"camera") p.camera=parseBool(v,p.camera);
        else if(k==L"mic") p.mic=parseBool(v,p.mic);
        else if(k==L"vb_cable") p.vbCable=parseBool(v,p.vbCable);
        else if(k==L"torch") p.torch=parseBool(v,p.torch);
        else if(k==L"zoom") p.zoom=parseInt(v,p.zoom,0,100);
        else if(k==L"resolution") p.resolution=parseInt(v,p.resolution,0,2);
        else if(k==L"fps") p.fps=parseInt(v,p.fps,0,2);
        else if(k==L"quality") p.quality=parseInt(v,p.quality,55,95);
    }
    g_uiPrefs=p;
}

void saveUiPrefs(){
    std::wofstream out(uiSettingsFile(),std::ios::trunc); if(!out) return;
    out<<L"remember="<<(g_uiPrefs.rememberButtons?1:0)<<L"\n";
    out<<L"run_with="<<g_uiPrefs.runWith<<L"\n";
    out<<L"camera="<<(g_uiPrefs.camera?1:0)<<L"\n";
    out<<L"mic="<<(g_uiPrefs.mic?1:0)<<L"\n";
    out<<L"vb_cable="<<(g_uiPrefs.vbCable?1:0)<<L"\n";
    out<<L"torch="<<(g_uiPrefs.torch?1:0)<<L"\n";
    out<<L"zoom="<<g_uiPrefs.zoom<<L"\n";
    out<<L"resolution="<<g_uiPrefs.resolution<<L"\n";
    out<<L"fps="<<g_uiPrefs.fps<<L"\n";
    out<<L"quality="<<g_uiPrefs.quality<<L"\n";
}

bool savePinValue(const std::wstring& pin){
    if(!validPin(pin)) return false;
    std::wstring dir=localDataDir();
