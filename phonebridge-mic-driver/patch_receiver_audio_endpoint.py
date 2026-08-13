from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def rep(old,new,label):
    global s
    if old not in s: raise SystemExit(f'PhoneBridge receiver mic bridge anchor missing: {label}')
    s=s.replace(old,new,1)

rep('std::atomic<int> g_audioLevel{0};\n', '''std::atomic<int> g_audioLevel{0};
HANDLE g_phoneBridgeMicDevice=INVALID_HANDLE_VALUE;
constexpr DWORD IOCTL_PHONEBRIDGE_AUDIO_PUSH = CTL_CODE(FILE_DEVICE_UNKNOWN, 0x801, METHOD_BUFFERED, FILE_WRITE_DATA);

void closePhoneBridgeMicDevice(){
    if(g_phoneBridgeMicDevice!=INVALID_HANDLE_VALUE){ CloseHandle(g_phoneBridgeMicDevice); g_phoneBridgeMicDevice=INVALID_HANDLE_VALUE; }
}

bool pushPhoneBridgeMic(const int16_t* samples, uint32_t count){
    if(!samples || !count) return false;
    if(g_phoneBridgeMicDevice==INVALID_HANDLE_VALUE){
        g_phoneBridgeMicDevice=CreateFileW(L"\\\\.\\PhoneBridgeAudio",GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
        if(g_phoneBridgeMicDevice==INVALID_HANDLE_VALUE) return false;
        phoneBridgeLog(L"PhoneBridge Microphone driver connected");
    }
    // Microsoft sample endpoint is 48 kHz, 32-bit stereo. Convert incoming mono PCM16.
    std::vector<int32_t> converted((size_t)count*2);
    for(uint32_t n=0;n<count;n++){
        int32_t v=((int32_t)samples[n])<<16;
        converted[(size_t)n*2]=v;
        converted[(size_t)n*2+1]=v;
    }
    DWORD returned=0;
    BOOL ok=DeviceIoControl(g_phoneBridgeMicDevice,IOCTL_PHONEBRIDGE_AUDIO_PUSH,converted.data(),(DWORD)(converted.size()*sizeof(int32_t)),nullptr,0,&returned,nullptr);
    if(!ok){ closePhoneBridgeMicDevice(); return false; }
    return true;
}
''','mic driver globals')
rep('''                const int16_t* samples=(const int16_t*)payload.data(); uint32_t count=(uint32_t)(payload.size()/2); audio.writePcm16(samples,count,48000,1); audioPackets++;
''','''                const int16_t* samples=(const int16_t*)payload.data(); uint32_t count=(uint32_t)(payload.size()/2); audio.writePcm16(samples,count,48000,1); pushPhoneBridgeMic(samples,count); audioPackets++;
''','push incoming PCM')
# Add driver state to stats without making the driver mandatory.
rep('''ss<<L"  |  Q"<<g_cfgQuality; if(g_recorder.active) ss<<L"  |  REC"; postText(WM_PB_STATS,ss.str());''',
    '''ss<<L"  |  Q"<<g_cfgQuality; if(g_phoneBridgeMicDevice!=INVALID_HANDLE_VALUE) ss<<L"  |  PhoneBridge Mic"; if(g_recorder.active) ss<<L"  |  REC"; postText(WM_PB_STATS,ss.str());''','stats driver state')
# Close device on orderly receiver shutdown.
if 'closePhoneBridgeMicDevice();' not in s[s.find('case WM_DESTROY'):]:
    rep('case WM_DESTROY:{ phoneBridgeLog(L"PhoneBridge shutting down");', 'case WM_DESTROY:{ closePhoneBridgeMicDevice(); phoneBridgeLog(L"PhoneBridge shutting down");','mic driver cleanup')

for marker in ['PhoneBridgeAudio','IOCTL_PHONEBRIDGE_AUDIO_PUSH','pushPhoneBridgeMic(samples,count)','D2D1CreateFactory','DXGI_FORMAT_B8G8R8A8_UNORM']:
    if marker not in s: raise SystemExit(f'PhoneBridge receiver endpoint marker missing: {marker}')
if 'StretchDIBits(' in s: raise SystemExit('Regression: legacy preview returned')
p.write_text(s,encoding='utf-8',newline='\n')
print('Added optional PhoneBridge virtual microphone driver bridge to receiver')
