    std::wstring file=dir.empty()?L"PhoneBridge.settings":dir+L"\\settings.ini";
    std::wofstream out(file,std::ios::trunc); if(!out) return false;
    out<<pin<<L"\n"; g_expectedPin=pin; return true;
}

void applyRunWithDefaults(){
    bool cam=true,mic=true,vb=false;
    if(g_uiPrefs.runWith==0){
        if(g_uiPrefs.rememberButtons){ cam=g_uiPrefs.camera; mic=g_uiPrefs.mic; vb=g_uiPrefs.vbCable && mic; }
    } else if(g_uiPrefs.runWith==1){ cam=true; mic=true; vb=g_uiPrefs.rememberButtons && g_uiPrefs.vbCable; }
    else if(g_uiPrefs.runWith==2){ cam=true; mic=false; vb=false; }
    else if(g_uiPrefs.runWith==3){ cam=false; mic=true; vb=g_uiPrefs.rememberButtons && g_uiPrefs.vbCable; }
    else { cam=false; mic=false; vb=false; }
    g_videoEnabled=cam; g_audioEnabled=mic; g_vbCableEnabled=vb;
}

void rememberCurrentButtons(){
    if(!g_uiPrefs.rememberButtons) return;
    g_uiPrefs.camera=g_videoEnabled.load();
    g_uiPrefs.mic=g_audioEnabled.load();
    g_uiPrefs.vbCable=g_vbCableEnabled.load();
    if(g_torch) g_uiPrefs.torch=Button_GetCheck(g_torch)==BST_CHECKED;
    if(g_zoom) g_uiPrefs.zoom=(int)SendMessageW(g_zoom,TBM_GETPOS,0,0);
    if(g_resolution) g_uiPrefs.resolution=std::max(0,ComboBox_GetCurSel(g_resolution));
    if(g_fps) g_uiPrefs.fps=std::max(0,ComboBox_GetCurSel(g_fps));
    if(g_quality) g_uiPrefs.quality=(int)SendMessageW(g_quality,TBM_GETPOS,0,0);
    saveUiPrefs();
}

'''
insert_before('bool startupEnabled(){', settings, 'startup marker')

# Helpers before launchSibling
helpers=r'''
bool copyText(HWND owner,const std::wstring& text){
    if(!OpenClipboard(owner)) return false;
    EmptyClipboard();
    SIZE_T bytes=(text.size()+1)*sizeof(wchar_t);
    HGLOBAL mem=GlobalAlloc(GMEM_MOVEABLE,bytes);
    if(!mem){ CloseClipboard(); return false; }
    void* dst=GlobalLock(mem); if(!dst){ GlobalFree(mem); CloseClipboard(); return false; }
    memcpy(dst,text.c_str(),bytes); GlobalUnlock(mem);
    if(!SetClipboardData(CF_UNICODETEXT,mem)){ GlobalFree(mem); CloseClipboard(); return false; }
    CloseClipboard(); return true;
}

bool applyVbCableState(bool on,bool announce){
    if(!on){
        g_vbCableEnabled=false;
        if(g_vbCable) Button_SetCheck(g_vbCable,BST_UNCHECKED);
        if(announce) postText(WM_PB_STATUS,L"VB-CABLE browser microphone bridge OFF");
        return true;
    }
    VBCableStatus st=FindVBCable();
    if(!st.renderFound || !st.captureFound){
        g_vbCableEnabled=false; if(g_vbCable) Button_SetCheck(g_vbCable,BST_UNCHECKED);
        if(announce) postText(WM_PB_STATUS,L"VB-CABLE is not installed. Click Get VB-CABLE, install the official driver, restart Windows, then enable this option.");
        return false;
    }
    if(!st.renderActive || !st.captureActive){
        g_vbCableEnabled=false; if(g_vbCable) Button_SetCheck(g_vbCable,BST_UNCHECKED);
        if(announce) postText(WM_PB_STATUS,L"VB-CABLE is installed but disabled. Enable CABLE Input and CABLE Output in Windows Sound settings.");
        return false;
    }
    g_vbCableEnabled=true; if(g_vbCable) Button_SetCheck(g_vbCable,BST_CHECKED);
    if(announce) postText(WM_PB_STATUS,L"Resilient no-echo browser mic ON. In the browser select "+st.captureName+L" as microphone.");
    return true;
}

int controlSideWidth(){ return g_settingsOpen.load()?380:270; }
void showCtrl(HWND h,bool show){ if(h) ShowWindow(h,show?SW_SHOW:SW_HIDE); }

void applySettingsVisibility(){
    const bool open=g_settingsOpen.load();
    for(HWND h:{g_pin,g_share,g_switch,g_torch,g_zoom,g_streamLabel,g_resolution,g_fps,g_quality,g_applyCfg,g_startup,g_openSound,g_openCam,g_vbCable,g_audioDevices,g_getVbCable,g_testCamera,g_repairCamera,g_stats,g_rememberButtons,g_runWith,g_pinEdit,g_savePin,g_addressLabel,g_copyAddress}) showCtrl(h,open);
    for(HWND h:{g_status,g_settingsButton,g_camera,g_mic,g_snapshot,g_record,g_audioLabel,g_audioBar}) showCtrl(h,true);
    if(g_settingsButton) SetWindowTextW(g_settingsButton,open?L"Back to camera":L"Settings");
}

'''
insert_before('bool launchSibling(HWND owner',helpers,'launchSibling')

# Layout replacement
ls=s.index('void layout(HWND hwnd){')
le=s.index('\nRECT previewRect(HWND hwnd)',ls)
new_layout=r'''void layout(HWND hwnd){
    RECT r{}; GetClientRect(hwnd,&r); int w=r.right-r.left, h=r.bottom-r.top;
    int side=controlSideWidth(), pad=18, gap=8; int x=w-side+pad, bw=side-pad*2;
    int half=(bw-gap)/2;
    MoveWindow(g_settingsButton,x,16,bw,34,TRUE);
    MoveWindow(g_status,x,58,bw,58,TRUE);
    if(!g_settingsOpen.load()){
        MoveWindow(g_camera,x,132,bw,30,TRUE);
        MoveWindow(g_mic,x,166,bw,30,TRUE);
        MoveWindow(g_snapshot,x,212,half,34,TRUE);
        MoveWindow(g_record,x+half+gap,212,half,34,TRUE);
        MoveWindow(g_audioLabel,x,268,bw,22,TRUE);
        MoveWindow(g_audioBar,x,294,bw,22,TRUE);
    } else {
        SetWindowTextW(g_addressLabel,(L"PC address: "+localIps()).c_str());
        MoveWindow(g_addressLabel,x,122,bw,38,TRUE);
        MoveWindow(g_copyAddress,x,164,bw,28,TRUE);
        MoveWindow(g_pin,x,198,bw,34,TRUE);
        MoveWindow(g_pinEdit,x,236,half,30,TRUE);
        MoveWindow(g_savePin,x+half+gap,236,half,30,TRUE);
        MoveWindow(g_rememberButtons,x,272,bw,28,TRUE);
        MoveWindow(g_runWith,x,304,bw,160,TRUE);
        MoveWindow(g_share,x,342,bw,32,TRUE);
        MoveWindow(g_camera,x,378,bw,28,TRUE);
        MoveWindow(g_mic,x,408,bw,28,TRUE);
