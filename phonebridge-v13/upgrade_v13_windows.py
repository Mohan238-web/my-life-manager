from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def rep(old,new,label):
    global s
    if old not in s:
        raise SystemExit(f'v1.3 Windows anchor missing: {label}')
    s=s.replace(old,new,1)

rep('#include "SharedBus.h"\n', '#include "SharedBus.h"\n#include "StereoMixBridge.h"\n', 'StereoMix include')
rep('constexpr UINT WM_PB_RECORD_STATE = WM_APP + 5;\n', 'constexpr UINT WM_PB_RECORD_STATE = WM_APP + 5;\nconstexpr UINT WM_PB_STEREO_STATE = WM_APP + 6;\n', 'stereo state message')
rep('constexpr int IDC_APPLY_CFG  = 1015;\n', 'constexpr int IDC_APPLY_CFG  = 1015;\nconstexpr int IDC_STEREO_BRIDGE = 1016;\nconstexpr int IDC_RECORD_DEVICES = 1017;\nconstexpr int IDC_REPAIR_CAMERA = 1018;\n', 'v1.3 control ids')
rep('HWND g_openCam{};\nstd::atomic<bool> g_recording{false};\n', 'HWND g_openCam{};\nHWND g_stereoBridge{};\nHWND g_recordDevices{};\nHWND g_repairCamera{};\nstd::atomic<bool> g_recording{false};\nstd::atomic<bool> g_stereoMixBridgeEnabled{false};\n', 'v1.3 globals')

# Camera repair invokes the installed elevated setup utility. The utility is patched in v1.3
# to migrate the old CurrentUser registration to an AllUsers Media Foundation virtual camera.
insert='''bool runCameraRepair(HWND owner){
    wchar_t exe[MAX_PATH]{};
    if(!GetModuleFileNameW(nullptr,exe,MAX_PATH)) return false;
    std::filesystem::path setup=std::filesystem::path(exe).parent_path()/L"Camera"/L"PhoneBridgeVirtualCameraSetup.exe";
    if(!std::filesystem::exists(setup)) return false;
    HINSTANCE r=ShellExecuteW(owner,L"runas",setup.c_str(),L"/install",nullptr,SW_SHOWNORMAL);
    return (INT_PTR)r>32;
}

'''
rep('int calculateLevel(const int16_t* samples, size_t count){\n', insert+'int calculateLevel(const int16_t* samples, size_t count){\n', 'camera repair helper')

rep('SharedFrameBus video; SharedAudioBus audio; video.openOrCreate(); audio.openOrCreate();\n', 'SharedFrameBus video; SharedAudioBus audio; video.openOrCreate(); audio.openOrCreate();\n    StereoMixBridge stereoBridge;\n', 'Stereo Mix receiver bridge')

old='''            } else if(type==(uint8_t)pbr::Type::AudioPcm16){
                const int16_t* samples=(const int16_t*)payload.data(); uint32_t count=(uint32_t)(payload.size()/2); audio.writePcm16(samples,count,48000,1); audioPackets++;
                int level=calculateLevel(samples,count); g_audioLevel=level; if(g_hwnd) PostMessageW(g_hwnd,WM_PB_LEVEL,(WPARAM)level,0);
'''
new='''            } else if(type==(uint8_t)pbr::Type::AudioPcm16){
                const int16_t* samples=(const int16_t*)payload.data(); uint32_t count=(uint32_t)(payload.size()/2); audio.writePcm16(samples,count,48000,1); audioPackets++;
                if(g_stereoMixBridgeEnabled.load()){
                    std::wstring bridgeError;
                    if(!stereoBridge.pushMonoPcm16(samples,count,&bridgeError)){
                        g_stereoMixBridgeEnabled=false;
                        if(g_hwnd) PostMessageW(g_hwnd,WM_PB_STEREO_STATE,0,0);
                        postText(WM_PB_STATUS,L"Stereo Mix bridge stopped: "+bridgeError);
                    }
                }
                int level=calculateLevel(samples,count); g_audioLevel=level; if(g_hwnd) PostMessageW(g_hwnd,WM_PB_LEVEL,(WPARAM)level,0);
'''
rep(old,new,'audio bridge push')

old_layout='''    MoveWindow(g_startup,x,494,bw,30,TRUE);
    MoveWindow(g_openCam,x,532,half,32,TRUE);
    MoveWindow(g_openSound,x+half+gap,532,half,32,TRUE);
    MoveWindow(g_audioLabel,x,574,bw,22,TRUE);
    MoveWindow(g_audioBar,x,600,bw,22,TRUE);
'''
new_layout='''    MoveWindow(g_startup,x,494,bw,30,TRUE);
    MoveWindow(g_openCam,x,532,half,32,TRUE);
    MoveWindow(g_openSound,x+half+gap,532,half,32,TRUE);
    MoveWindow(g_stereoBridge,x,574,bw,30,TRUE);
    MoveWindow(g_recordDevices,x,610,half,32,TRUE);
    MoveWindow(g_repairCamera,x+half+gap,610,half,32,TRUE);
    MoveWindow(g_audioLabel,x,652,bw,22,TRUE);
    MoveWindow(g_audioBar,x,678,bw,22,TRUE);
'''
rep(old_layout,new_layout,'v1.3 layout')

rep('''        g_openCam=CreateWindowW(L"BUTTON",L"Camera privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_CAM,nullptr,nullptr);
        g_openSound=CreateWindowW(L"BUTTON",L"Mic privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_SOUND,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
''','''        g_openCam=CreateWindowW(L"BUTTON",L"Camera privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_CAM,nullptr,nullptr);
        g_openSound=CreateWindowW(L"BUTTON",L"Mic privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_SOUND,nullptr,nullptr);
        g_stereoBridge=CreateWindowW(L"BUTTON",L"Browser mic via Stereo Mix",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_STEREO_BRIDGE,nullptr,nullptr);
        g_recordDevices=CreateWindowW(L"BUTTON",L"Recording devices",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_RECORD_DEVICES,nullptr,nullptr);
        g_repairCamera=CreateWindowW(L"BUTTON",L"Repair browser camera",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_REPAIR_CAMERA,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
''','create v1.3 controls')

rep('for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_streamLabel,g_applyCfg,g_snapshot,g_record,g_startup,g_openCam,g_openSound,g_audioLabel,g_stats,g_resolution,g_fps})',
    'for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_streamLabel,g_applyCfg,g_snapshot,g_record,g_startup,g_openCam,g_openSound,g_stereoBridge,g_recordDevices,g_repairCamera,g_audioLabel,g_stats,g_resolution,g_fps})',
    'font v1.3 controls')

rep('''    case WM_PB_RECORD_STATE: setRecordButton(wp!=0); return 0;
''','''    case WM_PB_RECORD_STATE: setRecordButton(wp!=0); return 0;
    case WM_PB_STEREO_STATE: Button_SetCheck(g_stereoBridge,wp?BST_CHECKED:BST_UNCHECKED); return 0;
''','stereo UI state')

rep('''        else if(id==IDC_OPEN_CAM){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-webcam",nullptr,nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_OPEN_SOUND){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-microphone",nullptr,nullptr,SW_SHOWNORMAL); }
        return 0; }
''','''        else if(id==IDC_OPEN_CAM){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-webcam",nullptr,nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_OPEN_SOUND){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-microphone",nullptr,nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_STEREO_BRIDGE){
            bool on=Button_GetCheck(g_stereoBridge)==BST_CHECKED;
            if(on){
                StereoMixStatus sm=FindStereoMixCapture();
                if(!sm.found){
                    Button_SetCheck(g_stereoBridge,BST_UNCHECKED); g_stereoMixBridgeEnabled=false;
                    postText(WM_PB_STATUS,L"Stereo Mix was not found. Open Recording devices and enable Stereo Mix (Realtek) if your PC provides it.");
                } else if(!sm.active){
                    Button_SetCheck(g_stereoBridge,BST_UNCHECKED); g_stereoMixBridgeEnabled=false;
                    postText(WM_PB_STATUS,L"Stereo Mix is present but disabled. Enable "+sm.name+L" in Recording devices first.");
                } else {
                    g_stereoMixBridgeEnabled=true;
                    postText(WM_PB_STATUS,L"Browser microphone bridge ON. Select "+sm.name+L" in the browser or Sound Recorder. Headphones are recommended to prevent speaker feedback.");
                }
            } else {
                g_stereoMixBridgeEnabled=false;
                postText(WM_PB_STATUS,L"Stereo Mix browser microphone bridge OFF");
            }
        }
        else if(id==IDC_RECORD_DEVICES){ ShellExecuteW(hwnd,L"open",L"control.exe",L"mmsys.cpl,,1",nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_REPAIR_CAMERA){
            if(runCameraRepair(hwnd)) postText(WM_PB_STATUS,L"Camera repair started. Approve Administrator permission, then fully restart the browser after installation completes.");
            else postText(WM_PB_STATUS,L"Could not start PhoneBridge Camera repair. Reinstall PhoneBridge v1.3 if the Camera folder is missing.");
        }
        return 0; }
''','v1.3 handlers')

s=s.replace('PhoneBridge v1.2 • waiting for phone','PhoneBridge v1.3 • waiting for phone')
s=s.replace('PhoneBridge-v1.2-SingleInstance','PhoneBridge-v1.3-SingleInstance')
s=s.replace('PhoneBridge v1.2 starting','PhoneBridge v1.3 starting')
s=s.replace('PhoneBridge v1.2 - Camera & Microphone','PhoneBridge v1.3 - Camera & Microphone')
s=s.replace('CW_USEDEFAULT,CW_USEDEFAULT,1240,820','CW_USEDEFAULT,CW_USEDEFAULT,1240,900',1)

for marker in ['StereoMixBridge','Browser mic via Stereo Mix','Repair browser camera','PhoneBridge-v1.3-SingleInstance','D2D1CreateFactory','DXGI_FORMAT_B8G8R8A8_UNORM']:
    if marker not in s: raise SystemExit(f'v1.3 marker missing: {marker}')
if 'StretchDIBits(' in s: raise SystemExit('Regression: legacy color renderer returned')

p.write_text(s,encoding='utf-8',newline='\n')
print('Applied PhoneBridge v1.3 Windows browser compatibility controls')
