must_replace('g_singleInstanceMutex=CreateMutexW(nullptr,FALSE,L"Local\\PhoneBridge-v1.7-SingleInstance");','g_singleInstanceMutex=CreateMutexW(nullptr,FALSE,L"Local\\PhoneBridge-v1.8-SingleInstance");','mutex')
must_replace('phoneBridgeLog(L"PhoneBridge v1.7 starting");','phoneBridgeLog(L"PhoneBridge v1.8 starting");','log')
must_replace('''    g_expectedPin=loadOrCreatePin();
    if(cmd&&*cmd){ std::wstring p=cmd; p.erase(std::remove(p.begin(),p.end(),L'"'),p.end()); if(p.size()==6&&std::all_of(p.begin(),p.end(),[](wchar_t c){ return iswdigit(c)!=0; })) g_expectedPin=p; }
''','''    g_expectedPin=loadOrCreatePin();
    loadUiPrefs();
    applyRunWithDefaults();
    if(cmd&&*cmd){ std::wstring p=cmd; p.erase(std::remove(p.begin(),p.end(),L'"'),p.end()); if(p.size()==6&&std::all_of(p.begin(),p.end(),[](wchar_t c){ return iswdigit(c)!=0; })) g_expectedPin=p; }
''','startup')
must_replace('L"PhoneBridge v1.7 - Camera & Microphone"','L"PhoneBridge v1.8 - Camera & Microphone"','title')

# Restore remote torch/zoom when a remembered phone reconnects.
s=s.replace('setRemoteVideo(g_videoEnabled.load()); setRemoteAudio(g_audioEnabled.load()); applyStreamConfig(); continue; }', '''setRemoteVideo(g_videoEnabled.load()); setRemoteAudio(g_audioEnabled.load()); applyStreamConfig();
                if(g_uiPrefs.rememberButtons){
                    sendControl(std::string("{\\\"cmd\\\":\\\"torch\\\",\\\"value\\\":")+(g_uiPrefs.torch?"true":"false")+"}");
                    std::ostringstream zs; zs<<"{\\\"cmd\\\":\\\"zoom\\\",\\\"value\\\":"<<(g_uiPrefs.zoom/100.0f)<<"}"; sendControl(zs.str());
                }
                continue; }''',1)
s=s.replace('std::max(0,ComboBox_GetCurSel(g_resolution))','std::max(0,(int)ComboBox_GetCurSel(g_resolution))')
s=s.replace('std::max(0,ComboBox_GetCurSel(g_fps))','std::max(0,(int)ComboBox_GetCurSel(g_fps))')
s=s.replace('g_uiPrefs.runWith=std::max(0,ComboBox_GetCurSel(g_runWith));','g_uiPrefs.runWith=std::max(0,(int)ComboBox_GetCurSel(g_runWith));')

# Guards
for marker in ['D2D1CreateFactory','DXGI_FORMAT_B8G8R8A8_UNORM','Resilient no-echo browser mic ON','Remember buttons','Run with: Last used buttons','ui-settings.ini','applyVbCableState']:
    if marker not in s: raise RuntimeError('guard missing '+marker)
if 'Stereo Mix' in s or 'StereoMixBridge' in s: raise RuntimeError('Stereo Mix regression')
if 'StretchDIBits(' in s: raise RuntimeError('GDI regression')
p.write_text(s,encoding='utf-8',newline='\n')
print('Applied PhoneBridge v1.8 Windows UI and persistent settings')
