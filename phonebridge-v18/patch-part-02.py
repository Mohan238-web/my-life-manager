        MoveWindow(g_switch,x,440,bw,30,TRUE);
        MoveWindow(g_torch,x,474,bw,26,TRUE);
        MoveWindow(g_zoom,x,504,bw,26,TRUE);
        MoveWindow(g_streamLabel,x,534,bw,20,TRUE);
        MoveWindow(g_resolution,x,558,half,120,TRUE);
        MoveWindow(g_fps,x+half+gap,558,half,120,TRUE);
        MoveWindow(g_quality,x,592,bw,24,TRUE);
        MoveWindow(g_applyCfg,x,620,bw,28,TRUE);
        MoveWindow(g_snapshot,x,652,half,30,TRUE);
        MoveWindow(g_record,x+half+gap,652,half,30,TRUE);
        MoveWindow(g_startup,x,686,bw,26,TRUE);
        MoveWindow(g_vbCable,x,716,bw,26,TRUE);
        MoveWindow(g_audioDevices,x,746,half,28,TRUE);
        MoveWindow(g_getVbCable,x+half+gap,746,half,28,TRUE);
        MoveWindow(g_openCam,x,778,half,28,TRUE);
        MoveWindow(g_openSound,x+half+gap,778,half,28,TRUE);
        MoveWindow(g_testCamera,x,810,half,28,TRUE);
        MoveWindow(g_repairCamera,x+half+gap,810,half,28,TRUE);
        MoveWindow(g_audioLabel,x,844,bw,20,TRUE);
        MoveWindow(g_audioBar,x,868,bw,20,TRUE);
        MoveWindow(g_stats,x,892,bw,std::max(20,h-906),TRUE);
    }
    applySettingsVisibility();
}
'''
s=s[:ls]+new_layout+s[le:]
must_replace('RECT previewRect(HWND hwnd){ RECT r{}; GetClientRect(hwnd,&r); return RECT{18,58,(LONG)std::max(200,(int)r.right-358),(LONG)std::max(180,(int)r.bottom-58)}; }',
             'RECT previewRect(HWND hwnd){ RECT r{}; GetClientRect(hwnd,&r); int side=controlSideWidth(); return RECT{18,18,(LONG)std::max(200,(int)r.right-side-8),(LONG)std::max(180,(int)r.bottom-18)}; }','preview')

# WM_CREATE UI additions
must_replace('g_status=CreateWindowW(L"STATIC",L"PhoneBridge v1.7 • waiting for phone",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);',
             'g_status=CreateWindowW(L"STATIC",L"PhoneBridge v1.8 • waiting for phone",WS_CHILD|WS_VISIBLE|SS_LEFT,0,0,0,0,hwnd,nullptr,nullptr,nullptr);','status')
must_replace('g_pin=CreateWindowW(L"STATIC",(L"PIN  "+g_expectedPin).c_str(),WS_CHILD|WS_VISIBLE|SS_CENTER|SS_CENTERIMAGE|WS_BORDER,0,0,0,0,hwnd,nullptr,nullptr,nullptr);',
             'g_pin=CreateWindowW(L"STATIC",(L"Current PIN  "+g_expectedPin).c_str(),WS_CHILD|WS_VISIBLE|SS_CENTER|SS_CENTERIMAGE|WS_BORDER,0,0,0,0,hwnd,nullptr,nullptr,nullptr);','pin')
must_replace('''        g_repairCamera=CreateWindowW(L"BUTTON",L"Repair camera",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_REPAIR_CAMERA,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
''','''        g_repairCamera=CreateWindowW(L"BUTTON",L"Repair camera",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_REPAIR_CAMERA,nullptr,nullptr);
        g_settingsButton=CreateWindowW(L"BUTTON",L"Settings",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_SETTINGS,nullptr,nullptr);
        g_rememberButtons=CreateWindowW(L"BUTTON",L"Remember buttons",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_REMEMBER_BUTTONS,nullptr,nullptr);
        g_runWith=CreateWindowW(WC_COMBOBOXW,L"",WS_CHILD|WS_VISIBLE|CBS_DROPDOWNLIST|WS_VSCROLL,0,0,0,0,hwnd,(HMENU)IDC_RUN_WITH,nullptr,nullptr);
        g_pinEdit=CreateWindowExW(WS_EX_CLIENTEDGE,L"EDIT",g_expectedPin.c_str(),WS_CHILD|WS_VISIBLE|ES_NUMBER|ES_CENTER|ES_AUTOHSCROLL,0,0,0,0,hwnd,(HMENU)IDC_PIN_EDIT,nullptr,nullptr);
        SendMessageW(g_pinEdit,EM_SETLIMITTEXT,6,0);
        g_savePin=CreateWindowW(L"BUTTON",L"Save PIN",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_SAVE_PIN,nullptr,nullptr);
        g_addressLabel=CreateWindowW(L"STATIC",L"PC address",WS_CHILD|WS_VISIBLE|SS_LEFT,0,0,0,0,hwnd,(HMENU)IDC_ADDRESS_LABEL,nullptr,nullptr);
        g_copyAddress=CreateWindowW(L"BUTTON",L"Copy PC address",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_COPY_ADDRESS,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
''','controls')
must_replace('for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_streamLabel,g_applyCfg,g_snapshot,g_record,g_startup,g_openCam,g_openSound,g_vbCable,g_audioDevices,g_getVbCable,g_testCamera,g_repairCamera,g_audioLabel,g_stats,g_resolution,g_fps})',
             'for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_streamLabel,g_applyCfg,g_snapshot,g_record,g_startup,g_openCam,g_openSound,g_vbCable,g_audioDevices,g_getVbCable,g_testCamera,g_repairCamera,g_audioLabel,g_stats,g_resolution,g_fps,g_settingsButton,g_rememberButtons,g_runWith,g_pinEdit,g_savePin,g_addressLabel,g_copyAddress})','font list')
old_init='''        Button_SetCheck(g_camera,BST_CHECKED); Button_SetCheck(g_mic,BST_CHECKED); SendMessageW(g_zoom,TBM_SETRANGE,TRUE,MAKELONG(0,100)); SendMessageW(g_zoom,TBM_SETPOS,TRUE,0); SendMessageW(g_audioBar,PBM_SETRANGE,0,MAKELPARAM(0,100));
        ComboBox_AddString(g_resolution,L"720p"); ComboBox_AddString(g_resolution,L"1080p"); ComboBox_AddString(g_resolution,L"360p low bandwidth"); ComboBox_SetCurSel(g_resolution,0);
        ComboBox_AddString(g_fps,L"15 fps"); ComboBox_AddString(g_fps,L"20 fps"); ComboBox_AddString(g_fps,L"30 fps"); ComboBox_SetCurSel(g_fps,2);
        SendMessageW(g_quality,TBM_SETRANGE,TRUE,MAKELONG(55,95)); SendMessageW(g_quality,TBM_SETPOS,TRUE,92);
        Button_SetCheck(g_startup,startupEnabled()?BST_CHECKED:BST_UNCHECKED);
        std::thread(receiverLoop).detach(); return 0; }
'''
new_init='''        Button_SetCheck(g_camera,g_videoEnabled.load()?BST_CHECKED:BST_UNCHECKED); Button_SetCheck(g_mic,g_audioEnabled.load()?BST_CHECKED:BST_UNCHECKED);
        SendMessageW(g_zoom,TBM_SETRANGE,TRUE,MAKELONG(0,100)); SendMessageW(g_zoom,TBM_SETPOS,TRUE,g_uiPrefs.rememberButtons?g_uiPrefs.zoom:0); SendMessageW(g_audioBar,PBM_SETRANGE,0,MAKELPARAM(0,100));
        ComboBox_AddString(g_resolution,L"720p"); ComboBox_AddString(g_resolution,L"1080p"); ComboBox_AddString(g_resolution,L"360p low bandwidth"); ComboBox_SetCurSel(g_resolution,g_uiPrefs.rememberButtons?g_uiPrefs.resolution:0);
        ComboBox_AddString(g_fps,L"15 fps"); ComboBox_AddString(g_fps,L"20 fps"); ComboBox_AddString(g_fps,L"30 fps"); ComboBox_SetCurSel(g_fps,g_uiPrefs.rememberButtons?g_uiPrefs.fps:2);
        SendMessageW(g_quality,TBM_SETRANGE,TRUE,MAKELONG(55,95)); SendMessageW(g_quality,TBM_SETPOS,TRUE,g_uiPrefs.rememberButtons?g_uiPrefs.quality:92);
        Button_SetCheck(g_torch,(g_uiPrefs.rememberButtons&&g_uiPrefs.torch)?BST_CHECKED:BST_UNCHECKED);
        Button_SetCheck(g_startup,startupEnabled()?BST_CHECKED:BST_UNCHECKED);
        Button_SetCheck(g_rememberButtons,g_uiPrefs.rememberButtons?BST_CHECKED:BST_UNCHECKED);
        ComboBox_AddString(g_runWith,L"Run with: Last used buttons");
        ComboBox_AddString(g_runWith,L"Run with: Camera + microphone");
        ComboBox_AddString(g_runWith,L"Run with: Camera only");
        ComboBox_AddString(g_runWith,L"Run with: Microphone only");
        ComboBox_AddString(g_runWith,L"Run with: Connected only");
        ComboBox_SetCurSel(g_runWith,g_uiPrefs.runWith);
        if(g_vbCableEnabled.load()) applyVbCableState(true,false); else Button_SetCheck(g_vbCable,BST_UNCHECKED);
        applySettingsVisibility();
        std::thread(receiverLoop).detach(); return 0; }
'''
must_replace(old_init,new_init,'init controls')

# WndProc messages
must_replace('case WM_PB_CABLE_STATE: Button_SetCheck(g_vbCable,wp?BST_CHECKED:BST_UNCHECKED); return 0;',
             'case WM_PB_CABLE_STATE: Button_SetCheck(g_vbCable,wp?BST_CHECKED:BST_UNCHECKED); g_vbCableEnabled=(wp!=0); rememberCurrentButtons(); return 0;','cable msg')
must_replace('case WM_HSCROLL: if((HWND)lp==g_zoom){ int pos=(int)SendMessageW(g_zoom,TBM_GETPOS,0,0); std::ostringstream ss; ss<<"{\\\"cmd\\\":\\\"zoom\\\",\\\"value\\\":"<<(pos/100.0f)<<"}"; sendControl(ss.str()); } return 0;',
             'case WM_HSCROLL: if((HWND)lp==g_zoom){ int pos=(int)SendMessageW(g_zoom,TBM_GETPOS,0,0); std::ostringstream ss; ss<<"{\\\"cmd\\\":\\\"zoom\\\",\\\"value\\\":"<<(pos/100.0f)<<"}"; sendControl(ss.str()); rememberCurrentButtons(); } return 0;','scroll')
old_cmd='''    case WM_COMMAND:{
        int id=LOWORD(wp), code=HIWORD(wp); if(code!=BN_CLICKED) break;
        if(id==IDC_CAMERA){ bool on=Button_GetCheck(g_camera)==BST_CHECKED; setRemoteVideo(on); }
'''
new_cmd='''    case WM_COMMAND:{
        int id=LOWORD(wp), code=HIWORD(wp);
        if(id==IDC_RUN_WITH && code==CBN_SELCHANGE){ g_uiPrefs.runWith=std::max(0,ComboBox_GetCurSel(g_runWith)); saveUiPrefs(); return 0; }
        if(code!=BN_CLICKED) break;
        if(id==IDC_SETTINGS){ g_settingsOpen=!g_settingsOpen.load(); layout(hwnd); InvalidateRect(hwnd,nullptr,TRUE); return 0; }
        if(id==IDC_REMEMBER_BUTTONS){ g_uiPrefs.rememberButtons=Button_GetCheck(g_rememberButtons)==BST_CHECKED; if(g_uiPrefs.rememberButtons) rememberCurrentButtons(); else saveUiPrefs(); return 0; }
        if(id==IDC_SAVE_PIN){ wchar_t b[16]{}; GetWindowTextW(g_pinEdit,b,16); std::wstring pin=b; if(savePinValue(pin)){ SetWindowTextW(g_pin,(L"Current PIN  "+pin).c_str()); postText(WM_PB_STATUS,L"PIN saved. Use this PIN on the phone from now on."); } else postText(WM_PB_STATUS,L"PIN must contain exactly 6 digits."); return 0; }
        if(id==IDC_COPY_ADDRESS){ std::wstring ip=localIps(); if(copyText(hwnd,ip)) postText(WM_PB_STATUS,L"PC address copied: "+ip); else postText(WM_PB_STATUS,L"Could not copy PC address"); return 0; }
        if(id==IDC_CAMERA){ bool on=Button_GetCheck(g_camera)==BST_CHECKED; setRemoteVideo(on); rememberCurrentButtons(); }
'''
must_replace(old_cmd,new_cmd,'command start')
must_replace('else if(id==IDC_MIC){ bool on=Button_GetCheck(g_mic)==BST_CHECKED; setRemoteAudio(on); }',
             'else if(id==IDC_MIC){ bool on=Button_GetCheck(g_mic)==BST_CHECKED; setRemoteAudio(on); rememberCurrentButtons(); }','mic')
must_replace('else if(id==IDC_TORCH){ bool on=Button_GetCheck(g_torch)==BST_CHECKED; sendControl(std::string("{\\\"cmd\\\":\\\"torch\\\",\\\"value\\\":")+(on?"true":"false")+"}"); }',
             'else if(id==IDC_TORCH){ bool on=Button_GetCheck(g_torch)==BST_CHECKED; sendControl(std::string("{\\\"cmd\\\":\\\"torch\\\",\\\"value\\\":")+(on?"true":"false")+"}"); rememberCurrentButtons(); }','torch')
must_replace('else if(id==IDC_SHARE){ bool newState=!(g_videoEnabled.load()||g_audioEnabled.load()); Button_SetCheck(g_camera,newState?BST_CHECKED:BST_UNCHECKED); Button_SetCheck(g_mic,newState?BST_CHECKED:BST_UNCHECKED); setRemoteVideo(newState); setRemoteAudio(newState); SetWindowTextW(g_share,newState?L"Sharing ON":L"Sharing OFF"); }',
             'else if(id==IDC_SHARE){ bool newState=!(g_videoEnabled.load()||g_audioEnabled.load()); Button_SetCheck(g_camera,newState?BST_CHECKED:BST_UNCHECKED); Button_SetCheck(g_mic,newState?BST_CHECKED:BST_UNCHECKED); setRemoteVideo(newState); setRemoteAudio(newState); SetWindowTextW(g_share,newState?L"Sharing ON":L"Sharing OFF"); rememberCurrentButtons(); }','share')
must_replace('if(applyStreamConfig()) postText(WM_PB_STATUS,L"Stream quality command sent to phone"); else postText(WM_PB_STATUS,L"Connect the phone before changing stream quality");',
             'if(applyStreamConfig()) postText(WM_PB_STATUS,L"Stream quality command sent to phone"); else postText(WM_PB_STATUS,L"Connect the phone before changing stream quality"); rememberCurrentButtons();','config')
old_vb='''        else if(id==IDC_VB_CABLE){
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
                    postText(WM_PB_STATUS,L"Resilient no-echo browser mic ON. In the browser select "+st.captureName+L" as microphone.");
                }
            } else {
                g_vbCableEnabled=false;
                postText(WM_PB_STATUS,L"VB-CABLE browser microphone bridge OFF");
            }
        }
'''
new_vb='''        else if(id==IDC_VB_CABLE){
            bool on=Button_GetCheck(g_vbCable)==BST_CHECKED;
            applyVbCableState(on,true);
            rememberCurrentButtons();
        }
'''
must_replace(old_vb,new_vb,'vb')

# Identity and load
