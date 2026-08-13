from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def rep(old,new,label):
    global s
    if old not in s:
        raise SystemExit(f'v1.2 Windows anchor missing: {label}')
    s=s.replace(old,new,1)

s=s.replace('PhoneBridge v1.1 RC','PhoneBridge v1.2')
s=s.replace('PhoneBridge-v1.1-SingleInstance','PhoneBridge-v1.2-SingleInstance')

rep('HWND g_startup{};\n', 'HWND g_startup{};\nHWND g_openSound{};\nHWND g_openCam{};\n', 'browser settings fields')

rep('''    MoveWindow(g_startup,x,494,bw,30,TRUE);
    MoveWindow(g_audioLabel,x,536,bw,22,TRUE);
    MoveWindow(g_audioBar,x,562,bw,22,TRUE);
''','''    MoveWindow(g_startup,x,494,bw,30,TRUE);
    MoveWindow(g_openCam,x,532,half,32,TRUE);
    MoveWindow(g_openSound,x+half+gap,532,half,32,TRUE);
    MoveWindow(g_audioLabel,x,574,bw,22,TRUE);
    MoveWindow(g_audioBar,x,600,bw,22,TRUE);
''','layout endpoint buttons')

rep('''        g_startup=CreateWindowW(L"BUTTON",L"Start PhoneBridge with Windows",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_STARTUP,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
''','''        g_startup=CreateWindowW(L"BUTTON",L"Start PhoneBridge with Windows",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_STARTUP,nullptr,nullptr);
        g_openCam=CreateWindowW(L"BUTTON",L"Camera privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_CAM,nullptr,nullptr);
        g_openSound=CreateWindowW(L"BUTTON",L"Mic privacy",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_OPEN_SOUND,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
''','create endpoint buttons')

rep('for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_streamLabel,g_applyCfg,g_snapshot,g_record,g_startup,g_audioLabel,g_stats,g_resolution,g_fps})',
    'for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_streamLabel,g_applyCfg,g_snapshot,g_record,g_startup,g_openCam,g_openSound,g_audioLabel,g_stats,g_resolution,g_fps})',
    'font endpoint buttons')

# Higher-quality daily default while keeping the known-good JPEG transport.
s=s.replace('ComboBox_SetCurSel(g_fps,1);','ComboBox_SetCurSel(g_fps,2);',1)
s=s.replace('SendMessageW(g_quality,TBM_SETPOS,TRUE,88);','SendMessageW(g_quality,TBM_SETPOS,TRUE,92);',1)

# Apply selected quality automatically as soon as pairing succeeds.
rep('''                setRemoteVideo(g_videoEnabled.load()); setRemoteAudio(g_audioEnabled.load()); continue; }
''','''                setRemoteVideo(g_videoEnabled.load()); setRemoteAudio(g_audioEnabled.load()); applyStreamConfig(); continue; }
''','pair quality apply')

rep('''        else if(id==IDC_STARTUP){ bool on=Button_GetCheck(g_startup)==BST_CHECKED; if(!setStartupEnabled(on)){ Button_SetCheck(g_startup,on?BST_UNCHECKED:BST_CHECKED); postText(WM_PB_STATUS,L"Could not change Windows startup setting"); } }
        return 0; }
''','''        else if(id==IDC_STARTUP){ bool on=Button_GetCheck(g_startup)==BST_CHECKED; if(!setStartupEnabled(on)){ Button_SetCheck(g_startup,on?BST_UNCHECKED:BST_CHECKED); postText(WM_PB_STATUS,L"Could not change Windows startup setting"); } }
        else if(id==IDC_OPEN_CAM){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-webcam",nullptr,nullptr,SW_SHOWNORMAL); }
        else if(id==IDC_OPEN_SOUND){ ShellExecuteW(hwnd,L"open",L"ms-settings:privacy-microphone",nullptr,nullptr,SW_SHOWNORMAL); }
        return 0; }
''','endpoint privacy handlers')

s=s.replace('CW_USEDEFAULT,CW_USEDEFAULT,1240,760','CW_USEDEFAULT,CW_USEDEFAULT,1240,820',1)

for marker in ['D2D1CreateFactory','DXGI_FORMAT_B8G8R8A8_UNORM','MFCreateSinkWriterFromURL','PhoneBridge-v1.2-SingleInstance','Camera privacy','Mic privacy']:
    if marker not in s:
        raise SystemExit(f'v1.2 Windows required marker missing: {marker}')
if 'StretchDIBits(' in s:
    raise SystemExit('Regression: legacy color-corrupting renderer returned')

p.write_text(s,encoding='utf-8',newline='\n')
print('Applied PhoneBridge v1.2 Windows quality/browser controls')
