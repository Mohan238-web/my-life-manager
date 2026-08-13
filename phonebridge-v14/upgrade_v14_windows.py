from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def rep(old,new,label):
    global s
    if old not in s:
        raise SystemExit(f'v1.4 Windows anchor missing: {label}')
    s=s.replace(old,new,1)

rep('#include "SharedBus.h"\n','#include "SharedBus.h"\n#include "VBCableBridge.h"\n','VB-CABLE include')
rep('constexpr UINT WM_PB_RECORD_STATE = WM_APP + 5;\n','constexpr UINT WM_PB_RECORD_STATE = WM_APP + 5;\nconstexpr UINT WM_PB_CABLE_STATE = WM_APP + 6;\n','cable state msg')
rep('constexpr int IDC_APPLY_CFG  = 1015;\n','constexpr int IDC_APPLY_CFG  = 1015;\nconstexpr int IDC_VB_CABLE = 1016;\nconstexpr int IDC_AUDIO_DEVICES = 1017;\nconstexpr int IDC_GET_VB_CABLE = 1018;\nconstexpr int IDC_TEST_CAMERA = 1019;\nconstexpr int IDC_REPAIR_CAMERA = 1020;\n','v1.4 ids')
rep('HWND g_openCam{};\nstd::atomic<bool> g_recording{false};\n','HWND g_openCam{};\nHWND g_vbCable{};\nHWND g_audioDevices{};\nHWND g_getVbCable{};\nHWND g_testCamera{};\nHWND g_repairCamera{};\nstd::atomic<bool> g_recording{false};\nstd::atomic<bool> g_vbCableEnabled{false};\n','v1.4 globals')

helpers=r'''bool launchSibling(HWND owner,const wchar_t* subdir,const wchar_t* file,const wchar_t* args,bool elevate){
    wchar_t exe[MAX_PATH]{};
    if(!GetModuleFileNameW(nullptr,exe,MAX_PATH)) return false;
    std::filesystem::path path=std::filesystem::path(exe).parent_path();
    if(subdir && *subdir) path/=subdir;
    path/=file;
    if(!std::filesystem::exists(path)) return false;
    HINSTANCE r=ShellExecuteW(owner,elevate?L"runas":L"open",path.c_str(),args,nullptr,SW_SHOWNORMAL);
    return (INT_PTR)r>32;
}

'''
rep('int calculateLevel(const int16_t* samples, size_t count){\n',helpers+'int calculateLevel(const int16_t* samples, size_t count){\n','launch helpers')

rep('SharedFrameBus video; SharedAudioBus audio; video.openOrCreate(); audio.openOrCreate();\n','SharedFrameBus video; SharedAudioBus audio; video.openOrCreate(); audio.openOrCreate();\n    VBCableBridge vbCable;\n','VB-CABLE receiver')

old='''            } else if(type==(uint8_t)pbr::Type::AudioPcm16){
                const int16_t* samples=(const int16_t*)payload.data(); uint32_t count=(uint32_t)(payload.size()/2); audio.writePcm16(samples,count,48000,1); audioPackets++;
                int level=calculateLevel(samples,count); g_audioLevel=level; if(g_hwnd) PostMessageW(g_hwnd,WM_PB_LEVEL,(WPARAM)level,0);
'''
new='''            } else if(type==(uint8_t)pbr::Type::AudioPcm16){
                const int16_t* samples=(const int16_t*)payload.data(); uint32_t count=(uint32_t)(payload.size()/2); audio.writePcm16(samples,count,48000,1); audioPackets++;
                if(g_vbCableEnabled.load()){
                    std::wstring cableError;
                    if(!vbCable.pushMonoPcm16(samples,count,&cableError)){
                        g_vbCableEnabled=false;
                        if(g_hwnd) PostMessageW(g_hwnd,WM_PB_CABLE_STATE,0,0);
                        postText(WM_PB_STATUS,L"VB-CABLE browser mic stopped: "+cableError);
                    }
                }
                int level=calculateLevel(samples,count); g_audioLevel=level; if(g_hwnd) PostMessageW(g_hwnd,WM_PB_LEVEL,(WPARAM)level,0);
'''
rep(old,new,'audio cable push')

old_layout='''    MoveWindow(g_startup,x,494,bw,30,TRUE);
    MoveWindow(g_openCam,x,532,half,32,TRUE);
    MoveWindow(g_openSound,x+half+gap,532,half,32,TRUE);
    MoveWindow(g_audioLabel,x,574,bw,22,TRUE);
    MoveWindow(g_audioBar,x,600,bw,22,TRUE);
'''
new_layout='''    MoveWindow(g_startup,x,494,bw,30,TRUE);
    MoveWindow(g_openCam,x,532,half,32,TRUE);
    MoveWindow(g_openSound,x+half+gap,532,half,32,TRUE);
    MoveWindow(g_vbCable,x,574,bw,30,TRUE);
    MoveWindow(g_audioDevices,x,610,half,32,TRUE);
    MoveWindow(g_getVbCable,x+half+gap,610,half,32,TRUE);
    MoveWindow(g_testCamera,x,650,half,32,TRUE);
    MoveWindow(g_repairCamera,x+half+gap,650,half,32,TRUE);
    MoveWindow(g_audioLabel,x,692,bw,22,TRUE);
    MoveWindow(g_audioBar,x,718,bw,22,TRUE);
'''
rep(old_layout,new_layout,'v1.4 layout')

rep('''        g_openCam=CreateWindowW(L"BUTTON",L"Camera privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_CAM,nullptr,nullptr);
        g_openSound=CreateWindowW(L"BUTTON",L"Mic privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_SOUND,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
''','''        g_openCam=CreateWindowW(L"BUTTON",L"Camera privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_CAM,nullptr,nullptr);
        g_openSound=CreateWindowW(L"BUTTON",L"Mic privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_SOUND,nullptr,nullptr);
        g_vbCable=CreateWindowW(L"BUTTON",L"Browser mic via VB-CABLE (no echo)",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_VB_CABLE,nullptr,nullptr);
        g_audioDevices=CreateWindowW(L"BUTTON",L"Audio devices",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_AUDIO_DEVICES,nullptr,nullptr);
        g_getVbCable=CreateWindowW(L"BUTTON",L"Get VB-CABLE",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_GET_VB_CABLE,nullptr,nullptr);
        g_testCamera=CreateWindowW(L"BUTTON",L"Camera health test",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_TEST_CAMERA,nullptr,nullptr);
        g_repairCamera=CreateWindowW(L"BUTTON",L"Repair camera",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_REPAIR_CAMERA,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
''','create v1.4 controls')

rep('for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_streamLabel,g_applyCfg,g_snapshot,g_record,g_startup,g_openCam,g_openSound,g_audioLabel,g_stats,g_resolution,g_fps})',
    'for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_streamLabel,g_applyCfg,g_snapshot,g_record,g_startup,g_openCam,g_openSound,g_vbCable,g_audioDevices,g_getVbCable,g_testCamera,g_repairCamera,g_audioLabel,g_stats,g_resolution,g_fps})',
    'font v1.4 controls')

rep('''    case WM_PB_RECORD_STATE: setRecordButton(wp!=0); return 0;
''','''    case WM_PB_RECORD_STATE: setRecordButton(wp!=0); return 0;
    case WM_PB_CABLE_STATE: Button_SetCheck(g_vbCable,wp?BST_CHECKED:BST_UNCHECKED); return 0;
''','cable state handler')

rep('''        else if(id==IDC_OPEN_CAM){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-webcam",nullptr,nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_OPEN_SOUND){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-microphone",nullptr,nullptr,SW_SHOWNORMAL); }
        return 0; }
''','''        else if(id==IDC_OPEN_CAM){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-webcam",nullptr,nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_OPEN_SOUND){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-microphone",nullptr,nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_VB_CABLE){
            bool on=Button_GetCheck(g_vbCable)==BST_CHECKED;
            if(on){
                VBCableStatus st=FindVBCable();
                if(!st.renderFound || !st.captureFound){
                    Button_SetCheck(g_vbCable,BST_UNCHECKED); g_vbCableEnabled=false;
                    postText(WM_PB_STATUS,L"VB-CABLE is not installed. Click Get VB-CABLE, install the official driver, restart Windows, then enable this option.");
                } else if(!st.renderActive || !st.captureActive){
                    Button_SetCheck(g_vbCable,BST_UNCHECKED); g_vbCableEnabled=false;
                    postText(WM_PB_STATUS,L"VB-CABLE is installed but disabled. Enable CABLE Input and CABLE Output in Windows Sound settings.");
                } else {
                    g_vbCableEnabled=true;
                    postText(WM_PB_STATUS,L"No-echo browser mic ON. In the browser select "+st.captureName+L" as microphone.");
                }
            } else {
                g_vbCableEnabled=false;
                postText(WM_PB_STATUS,L"VB-CABLE browser microphone bridge OFF");
            }
        }
        else if(id==IDC_AUDIO_DEVICES){ ShellExecuteW(hwnd,L"open",L"control.exe",L"mmsys.cpl,,1",nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_GET_VB_CABLE){ ShellExecuteW(hwnd,L"open",L"https://vb-audio.com/Cable/",nullptr,nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_TEST_CAMERA){
            if(launchSibling(hwnd,L"Camera",L"PhoneBridgeCameraProbe.exe",nullptr,false)) postText(WM_PB_STATUS,L"Camera health test started. It will report whether Windows can activate and read PhoneBridge Camera.");
            else postText(WM_PB_STATUS,L"Camera health tester is missing. Reinstall PhoneBridge v1.4.");
        }
        else if(id==IDC_REPAIR_CAMERA){
            if(launchSibling(hwnd,L"Camera",L"PhoneBridgeVirtualCameraSetup.exe",L"/repair",true)) postText(WM_PB_STATUS,L"Camera repair started. Close browsers and camera apps while it runs, then reopen the browser.");
            else postText(WM_PB_STATUS,L"Camera repair utility is missing. Reinstall PhoneBridge v1.4.");
        }
        return 0; }
''','v1.4 handlers')

s=s.replace('PhoneBridge v1.2 • waiting for phone','PhoneBridge v1.4 • waiting for phone')
s=s.replace('PhoneBridge-v1.2-SingleInstance','PhoneBridge-v1.4-SingleInstance')
s=s.replace('PhoneBridge v1.2 starting','PhoneBridge v1.4 starting')
s=s.replace('PhoneBridge v1.2 - Camera & Microphone','PhoneBridge v1.4 - Camera & Microphone')
s=s.replace('CW_USEDEFAULT,CW_USEDEFAULT,1240,820','CW_USEDEFAULT,CW_USEDEFAULT,1240,940',1)

for marker in ['VBCableBridge','Browser mic via VB-CABLE (no echo)','Camera health test','PhoneBridge-v1.4-SingleInstance','D2D1CreateFactory','DXGI_FORMAT_B8G8R8A8_UNORM']:
    if marker not in s: raise SystemExit(f'v1.4 marker missing: {marker}')
if 'Stereo Mix' in s or 'StereoMixBridge' in s: raise SystemExit('Regression: Stereo Mix feedback path returned')
if 'StretchDIBits(' in s: raise SystemExit('Regression: legacy color renderer returned')

p.write_text(s,encoding='utf-8',newline='\n')
print('Applied PhoneBridge v1.4 VB-CABLE and camera-health controls')
