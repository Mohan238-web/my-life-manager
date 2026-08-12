#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <shellapi.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cwctype>
#include <iomanip>
#include <mutex>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include "phonebridge_protocol.h"
#include "JpegDecoder.h"
#include "SharedBus.h"
#pragma comment(lib,"ws2_32.lib")
#pragma comment(lib,"ole32.lib")
#pragma comment(lib,"comctl32.lib")

namespace {
constexpr UINT WM_PB_STATUS = WM_APP + 1;
constexpr UINT WM_PB_FRAME  = WM_APP + 2;
constexpr UINT WM_PB_LEVEL  = WM_APP + 3;
constexpr UINT WM_PB_STATS  = WM_APP + 4;

constexpr int IDC_SHARE      = 1001;
constexpr int IDC_CAMERA     = 1002;
constexpr int IDC_MIC        = 1003;
constexpr int IDC_SWITCH     = 1004;
constexpr int IDC_TORCH      = 1005;
constexpr int IDC_ZOOM       = 1006;
constexpr int IDC_OPEN_SOUND = 1007;
constexpr int IDC_OPEN_CAM   = 1008;

HWND g_hwnd{};
HWND g_status{};
HWND g_pin{};
HWND g_stats{};
HWND g_audioLabel{};
HWND g_audioBar{};
HWND g_share{};
HWND g_camera{};
HWND g_mic{};
HWND g_switch{};
HWND g_torch{};
HWND g_zoom{};

std::mutex g_socketMutex;
SOCKET g_activeSocket = INVALID_SOCKET;
std::atomic<bool> g_paired{false};
std::atomic<bool> g_running{true};
std::atomic<bool> g_videoEnabled{true};
std::atomic<bool> g_audioEnabled{true};

std::mutex g_frameMutex;
std::vector<uint8_t> g_frame;
uint32_t g_frameW=0, g_frameH=0, g_frameStride=0;
std::atomic<int> g_audioLevel{0};
std::wstring g_expectedPin;

uint32_t be32(const uint8_t* p){ return (uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|p[3]; }
bool recvAll(SOCKET s,void* buf,size_t n){ char* p=(char*)buf; while(n){ int r=recv(s,p,(int)n,0); if(r<=0) return false; p+=r; n-=r; } return true; }
std::string extractPin(const std::vector<uint8_t>& v){ std::string s(v.begin(),v.end()); auto p=s.find("\"pin\":\""); if(p==std::string::npos) return {}; p+=7; auto e=s.find('"',p); return e==std::string::npos?std::string{}:s.substr(p,e-p); }
std::string extractDevice(const std::vector<uint8_t>& v){ std::string s(v.begin(),v.end()); auto p=s.find("\"device\":\""); if(p==std::string::npos) return "Phone"; p+=10; auto e=s.find('"',p); return e==std::string::npos?"Phone":s.substr(p,e-p); }
std::wstring widen(const std::string& s){ if(s.empty()) return {}; int n=MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),nullptr,0); std::wstring w(n,L'\0'); if(n) MultiByteToWideChar(CP_UTF8,0,s.data(),(int)s.size(),w.data(),n); return w; }
std::string narrow(const std::wstring& w){ if(w.empty()) return {}; int n=WideCharToMultiByte(CP_UTF8,0,w.data(),(int)w.size(),nullptr,0,nullptr,nullptr); std::string s(n,'\0'); if(n) WideCharToMultiByte(CP_UTF8,0,w.data(),(int)w.size(),s.data(),n,nullptr,nullptr); return s; }
std::wstring randomPin(){ std::random_device rd; std::mt19937 gen(rd()); std::uniform_int_distribution<int> d(0,999999); wchar_t b[7]; swprintf_s(b,L"%06d",d(gen)); return b; }

void postText(UINT msg, const std::wstring& text){ if(!g_hwnd) return; auto* p=new std::wstring(text); if(!PostMessageW(g_hwnd,msg,0,(LPARAM)p)) delete p; }

bool sendRecord(uint8_t type, const void* data, uint32_t len){
    std::lock_guard<std::mutex> lk(g_socketMutex);
    if(g_activeSocket==INVALID_SOCKET || !g_paired.load()) return false;
    uint8_t h[12]={'P','B','R','1',type,0,0,0,(uint8_t)(len>>24),(uint8_t)(len>>16),(uint8_t)(len>>8),(uint8_t)len};
    auto sendAll=[&](const void* p,size_t n){ const char* c=(const char*)p; while(n){ int r=send(g_activeSocket,c,(int)n,0); if(r<=0) return false; c+=r; n-=r; } return true; };
    return sendAll(h,sizeof(h)) && (!len || sendAll(data,len));
}
bool sendControl(const std::string& json){ return sendRecord((uint8_t)pbr::Type::Control,json.data(),(uint32_t)json.size()); }
void setRemoteVideo(bool on){ g_videoEnabled=on; sendControl(std::string("{\"cmd\":\"video\",\"value\":")+(on?"true":"false")+"}"); }
void setRemoteAudio(bool on){ g_audioEnabled=on; sendControl(std::string("{\"cmd\":\"audio\",\"value\":")+(on?"true":"false")+"}"); }

void discoveryLoop(){
    SOCKET u=socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP); if(u==INVALID_SOCKET) return; int yes=1; setsockopt(u,SOL_SOCKET,SO_REUSEADDR,(char*)&yes,sizeof(yes));
    sockaddr_in a{}; a.sin_family=AF_INET; a.sin_port=htons(8990); a.sin_addr.s_addr=INADDR_ANY; if(bind(u,(sockaddr*)&a,sizeof(a))){ closesocket(u); return; }
    while(g_running){ char b[256]{}; sockaddr_in from{}; int fl=sizeof(from); int n=recvfrom(u,b,sizeof(b)-1,0,(sockaddr*)&from,&fl); if(n<=0) continue;
        if(std::string(b,b+n)=="PBR_DISCOVER_V1"){ char host[128]="PC"; gethostname(host,sizeof(host)); std::string r=std::string("PBR_HERE_V1|")+host+"|8989"; sendto(u,r.data(),(int)r.size(),0,(sockaddr*)&from,fl); }
    }
    closesocket(u);
}

std::wstring localIps(){
    char host[256]{}; if(gethostname(host,sizeof(host))!=0) return L""; addrinfo hints{}; hints.ai_family=AF_INET; addrinfo* res=nullptr; if(getaddrinfo(host,nullptr,&hints,&res)!=0) return L"";
    std::wstring out; bool first=true; for(auto* p=res;p;p=p->ai_next){ auto* a=(sockaddr_in*)p->ai_addr; char ip[INET_ADDRSTRLEN]{}; inet_ntop(AF_INET,&a->sin_addr,ip,sizeof(ip)); if(!first) out+=L", "; out+=widen(ip); first=false; } freeaddrinfo(res); return out;
}

int calculateLevel(const int16_t* samples, size_t count){
    if(!samples||!count) return 0; long double sum=0; for(size_t i=0;i<count;i++){ long double x=samples[i]/32768.0L; sum+=x*x; }
    double rms=std::sqrt((double)(sum/count)); if(rms<=0.00001) return 0; double db=20.0*std::log10(rms); double norm=(db+60.0)/60.0; return (int)std::clamp(norm*100.0,0.0,100.0);
}

void receiverLoop(){
    WSADATA wd{}; if(WSAStartup(MAKEWORD(2,2),&wd)!=0){ postText(WM_PB_STATUS,L"Winsock startup failed"); return; }
    CoInitializeEx(nullptr,COINIT_MULTITHREADED);
    std::thread(discoveryLoop).detach();
    SOCKET ls=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP); if(ls==INVALID_SOCKET){ postText(WM_PB_STATUS,L"Could not create listener"); return; }
    int yes=1; setsockopt(ls,SOL_SOCKET,SO_REUSEADDR,(char*)&yes,sizeof(yes));
    sockaddr_in a{}; a.sin_family=AF_INET; a.sin_port=htons(8989); a.sin_addr.s_addr=INADDR_ANY;
    if(bind(ls,(sockaddr*)&a,sizeof(a))||listen(ls,1)){ postText(WM_PB_STATUS,L"Port 8989 is already in use"); closesocket(ls); return; }
    JpegDecoder jpeg; if(!jpeg.init()){ postText(WM_PB_STATUS,L"Windows image decoder failed"); closesocket(ls); return; }
    SharedFrameBus video; SharedAudioBus audio; video.openOrCreate(); audio.openOrCreate();
    postText(WM_PB_STATUS,L"Waiting for phone...  PC IP: "+localIps());

    while(g_running){
        sockaddr_in peer{}; int plen=sizeof(peer); SOCKET s=accept(ls,(sockaddr*)&peer,&plen); if(s==INVALID_SOCKET) continue; BOOL nd=TRUE; setsockopt(s,IPPROTO_TCP,TCP_NODELAY,(char*)&nd,sizeof(nd));
        {
            std::lock_guard<std::mutex> lk(g_socketMutex); g_activeSocket=s;
        }
        g_paired=false; std::string device="Phone"; uint64_t frames=0; uint64_t audioPackets=0; uint64_t totalBytes=0; auto start=std::chrono::steady_clock::now();
        postText(WM_PB_STATUS,L"Phone connected - waiting for PIN...");
        while(g_running){
            uint8_t h[12]; if(!recvAll(s,h,sizeof(h))) break; if(memcmp(h,"PBR1",4)!=0) break; uint8_t type=h[4]; uint32_t len=be32(h+8); if(len>pbr::kMaxPayload) break;
            std::vector<uint8_t> payload(len); if(len&&!recvAll(s,payload.data(),len)) break; totalBytes += len+12;
            if(type==(uint8_t)pbr::Type::Hello){ device=extractDevice(payload); postText(WM_PB_STATUS,L"Connected: "+widen(device)+L" - verifying PIN..."); continue; }
            if(type==(uint8_t)pbr::Type::Pair){ bool ok=(extractPin(payload)==narrow(g_expectedPin)); g_paired=ok; postText(WM_PB_STATUS,ok?L"Paired: "+widen(device)+L" - camera and microphone ready":L"Pairing PIN rejected"); if(!ok) break;
                setRemoteVideo(g_videoEnabled.load()); setRemoteAudio(g_audioEnabled.load()); continue; }
            if(!g_paired) continue;
            if(type==(uint8_t)pbr::Type::VideoJpeg){
                std::vector<uint8_t> bgra; uint32_t w=0,hg=0,stride=0; if(jpeg.decodeToBgra(payload.data(),payload.size(),bgra,w,hg,stride)){
                    auto now=std::chrono::steady_clock::now().time_since_epoch(); uint64_t t100=(uint64_t)(std::chrono::duration_cast<std::chrono::nanoseconds>(now).count()/100);
                    video.writeBgra(bgra.data(),w,hg,stride,t100);
                    { std::lock_guard<std::mutex> lk(g_frameMutex); g_frame.swap(bgra); g_frameW=w; g_frameH=hg; g_frameStride=stride; }
                    frames++; if(g_hwnd) PostMessageW(g_hwnd,WM_PB_FRAME,0,0);
                }
            } else if(type==(uint8_t)pbr::Type::AudioPcm16){
                const int16_t* samples=(const int16_t*)payload.data(); uint32_t count=(uint32_t)(payload.size()/2); audio.writePcm16(samples,count,48000,1); audioPackets++;
                int level=calculateLevel(samples,count); g_audioLevel=level; if(g_hwnd) PostMessageW(g_hwnd,WM_PB_LEVEL,(WPARAM)level,0);
            }
            if((frames+audioPackets)%25==0){ double sec=std::max(0.001,std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()); std::wstringstream ss; ss<<L"Video "<<g_frameW<<L"x"<<g_frameH<<L"  |  "<<std::fixed<<std::setprecision(1)<<(frames/sec)<<L" fps  |  "<<std::setprecision(2)<<((totalBytes*8.0/sec)/1000000.0)<<L" Mbps"; postText(WM_PB_STATS,ss.str()); }
        }
        { std::lock_guard<std::mutex> lk(g_socketMutex); if(g_activeSocket==s) g_activeSocket=INVALID_SOCKET; }
        g_paired=false; closesocket(s); postText(WM_PB_STATUS,L"Phone disconnected - waiting for reconnect...");
    }
    closesocket(ls); WSACleanup(); CoUninitialize();
}

void layout(HWND hwnd){
    RECT r{}; GetClientRect(hwnd,&r); int w=r.right-r.left, h=r.bottom-r.top;
    int side=280; int pad=18; int x=w-side+pad; int bw=side-pad*2;
    MoveWindow(g_status,pad,18,w-side-pad*2,30,TRUE);
    MoveWindow(g_pin,x,18,bw,56,TRUE);
    MoveWindow(g_share,x,88,bw,38,TRUE);
    MoveWindow(g_camera,x,136,bw,32,TRUE);
    MoveWindow(g_mic,x,174,bw,32,TRUE);
    MoveWindow(g_switch,x,222,bw,34,TRUE);
    MoveWindow(g_torch,x,264,bw,30,TRUE);
    MoveWindow(g_zoom,x,306,bw,32,TRUE);
    MoveWindow(g_audioLabel,x,350,bw,24,TRUE);
    MoveWindow(g_audioBar,x,378,bw,22,TRUE);
    MoveWindow(g_stats,pad,h-46,w-side-pad*2,28,TRUE);
}

RECT previewRect(HWND hwnd){ RECT r{}; GetClientRect(hwnd,&r); return RECT{18,58,(LONG)std::max(200,(int)r.right-298),(LONG)std::max(180,(int)r.bottom-58)}; }

void paintPreview(HWND hwnd,HDC dc){
    RECT pr=previewRect(hwnd); HBRUSH bg=CreateSolidBrush(RGB(18,20,24)); FillRect(dc,&pr,bg); DeleteObject(bg);
    std::lock_guard<std::mutex> lk(g_frameMutex);
    if(g_frame.empty()||!g_frameW||!g_frameH){ SetBkMode(dc,TRANSPARENT); SetTextColor(dc,RGB(195,200,210)); DrawTextW(dc,L"Phone camera preview will appear here after pairing",-1,&pr,DT_CENTER|DT_VCENTER|DT_SINGLELINE); return; }
    int rw=pr.right-pr.left, rh=pr.bottom-pr.top; double scale=std::min(rw/(double)g_frameW,rh/(double)g_frameH); int dw=(int)(g_frameW*scale), dh=(int)(g_frameH*scale); int dx=pr.left+(rw-dw)/2, dy=pr.top+(rh-dh)/2;
    BITMAPINFO bmi{}; bmi.bmiHeader.biSize=sizeof(BITMAPINFOHEADER); bmi.bmiHeader.biWidth=(LONG)g_frameW; bmi.bmiHeader.biHeight=-(LONG)g_frameH; bmi.bmiHeader.biPlanes=1; bmi.bmiHeader.biBitCount=32; bmi.bmiHeader.biCompression=BI_RGB;
    StretchDIBits(dc,dx,dy,dw,dh,0,0,g_frameW,g_frameH,g_frame.data(),&bmi,DIB_RGB_COLORS,SRCCOPY);
}

HFONT makeFont(int px, int weight=FW_NORMAL){ return CreateFontW(-px,0,0,0,weight,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH|FF_SWISS,L"Segoe UI"); }

LRESULT CALLBACK WndProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp){
    switch(msg){
    case WM_CREATE:{
        HFONT font=makeFont(16), bold=makeFont(18,FW_SEMIBOLD), pinFont=makeFont(24,FW_BOLD);
        g_status=CreateWindowW(L"STATIC",L"Starting PhoneBridge...",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
        g_pin=CreateWindowW(L"STATIC",(L"PIN  "+g_expectedPin).c_str(),WS_CHILD|WS_VISIBLE|SS_CENTER|SS_CENTERIMAGE|WS_BORDER,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
        g_share=CreateWindowW(L"BUTTON",L"Sharing ON",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_SHARE,nullptr,nullptr);
        g_camera=CreateWindowW(L"BUTTON",L"Share camera",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_CAMERA,nullptr,nullptr);
        g_mic=CreateWindowW(L"BUTTON",L"Share microphone",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_MIC,nullptr,nullptr);
        g_switch=CreateWindowW(L"BUTTON",L"Switch front / rear camera",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,0,0,0,0,hwnd,(HMENU)IDC_SWITCH,nullptr,nullptr);
        g_torch=CreateWindowW(L"BUTTON",L"Torch",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)IDC_TORCH,nullptr,nullptr);
        g_zoom=CreateWindowExW(0,TRACKBAR_CLASSW,L"",WS_CHILD|WS_VISIBLE|TBS_AUTOTICKS,0,0,0,0,hwnd,(HMENU)IDC_ZOOM,nullptr,nullptr);
        g_audioLabel=CreateWindowW(L"STATIC",L"Microphone level",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
        g_audioBar=CreateWindowExW(0,PROGRESS_CLASSW,L"",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
        g_stats=CreateWindowW(L"STATIC",L"Waiting for stream statistics...",WS_CHILD|WS_VISIBLE,0,0,0,0,hwnd,nullptr,nullptr,nullptr);
        for(HWND c:{g_status,g_share,g_camera,g_mic,g_switch,g_torch,g_audioLabel,g_stats}) SendMessageW(c,WM_SETFONT,(WPARAM)font,TRUE);
        SendMessageW(g_status,WM_SETFONT,(WPARAM)bold,TRUE); SendMessageW(g_pin,WM_SETFONT,(WPARAM)pinFont,TRUE);
        Button_SetCheck(g_camera,BST_CHECKED); Button_SetCheck(g_mic,BST_CHECKED); SendMessageW(g_zoom,TBM_SETRANGE,TRUE,MAKELONG(0,100)); SendMessageW(g_zoom,TBM_SETPOS,TRUE,0); SendMessageW(g_audioBar,PBM_SETRANGE,0,MAKELPARAM(0,100));
        std::thread(receiverLoop).detach(); return 0; }
    case WM_SIZE: layout(hwnd); InvalidateRect(hwnd,nullptr,TRUE); return 0;
    case WM_PAINT:{ PAINTSTRUCT ps{}; HDC dc=BeginPaint(hwnd,&ps); paintPreview(hwnd,dc); EndPaint(hwnd,&ps); return 0; }
    case WM_PB_FRAME: InvalidateRect(hwnd,nullptr,FALSE); return 0;
    case WM_PB_LEVEL: SendMessageW(g_audioBar,PBM_SETPOS,wp,0); return 0;
    case WM_PB_STATUS:{ auto* s=(std::wstring*)lp; if(s){ SetWindowTextW(g_status,s->c_str()); delete s; } return 0; }
    case WM_PB_STATS:{ auto* s=(std::wstring*)lp; if(s){ SetWindowTextW(g_stats,s->c_str()); delete s; } return 0; }
    case WM_HSCROLL: if((HWND)lp==g_zoom){ int pos=(int)SendMessageW(g_zoom,TBM_GETPOS,0,0); std::ostringstream ss; ss<<"{\"cmd\":\"zoom\",\"value\":"<<(pos/100.0f)<<"}"; sendControl(ss.str()); } return 0;
    case WM_COMMAND:{
        int id=LOWORD(wp), code=HIWORD(wp); if(code!=BN_CLICKED) break;
        if(id==IDC_CAMERA){ bool on=Button_GetCheck(g_camera)==BST_CHECKED; setRemoteVideo(on); }
        else if(id==IDC_MIC){ bool on=Button_GetCheck(g_mic)==BST_CHECKED; setRemoteAudio(on); }
        else if(id==IDC_SWITCH){ sendControl("{\"cmd\":\"camera\"}"); }
        else if(id==IDC_TORCH){ bool on=Button_GetCheck(g_torch)==BST_CHECKED; sendControl(std::string("{\"cmd\":\"torch\",\"value\":")+(on?"true":"false")+"}"); }
        else if(id==IDC_SHARE){ bool newState=!(g_videoEnabled.load()||g_audioEnabled.load()); Button_SetCheck(g_camera,newState?BST_CHECKED:BST_UNCHECKED); Button_SetCheck(g_mic,newState?BST_CHECKED:BST_UNCHECKED); setRemoteVideo(newState); setRemoteAudio(newState); SetWindowTextW(g_share,newState?L"Sharing ON":L"Sharing OFF"); }
        return 0; }
    case WM_CLOSE: DestroyWindow(hwnd); return 0;
    case WM_DESTROY:{ g_running=false; { std::lock_guard<std::mutex> lk(g_socketMutex); if(g_activeSocket!=INVALID_SOCKET) shutdown(g_activeSocket,SD_BOTH); } PostQuitMessage(0); return 0; }
    }
    return DefWindowProcW(hwnd,msg,wp,lp);
}
}

int WINAPI wWinMain(HINSTANCE hInst,HINSTANCE,LPWSTR cmd,int show){
    INITCOMMONCONTROLSEX ic{sizeof(ic),ICC_BAR_CLASSES|ICC_PROGRESS_CLASS}; InitCommonControlsEx(&ic);
    g_expectedPin=randomPin();
    if(cmd&&*cmd){ std::wstring p=cmd; p.erase(std::remove(p.begin(),p.end(),L'"'),p.end()); if(p.size()==6&&std::all_of(p.begin(),p.end(),[](wchar_t c){ return iswdigit(c)!=0; })) g_expectedPin=p; }
    WNDCLASSEXW wc{sizeof(wc)}; wc.lpfnWndProc=WndProc; wc.hInstance=hInst; wc.hCursor=LoadCursor(nullptr,IDC_ARROW); wc.hIcon=LoadIcon(nullptr,IDI_APPLICATION); wc.hbrBackground=(HBRUSH)(COLOR_WINDOW+1); wc.lpszClassName=L"PhoneBridgeReceiverWindow"; RegisterClassExW(&wc);
    HWND hwnd=CreateWindowExW(0,wc.lpszClassName,L"PhoneBridge - Camera & Microphone",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,CW_USEDEFAULT,CW_USEDEFAULT,1120,700,nullptr,nullptr,hInst,nullptr);
    if(!hwnd) return 2; g_hwnd=hwnd; ShowWindow(hwnd,show); UpdateWindow(hwnd);
    MSG m{}; while(GetMessageW(&m,nullptr,0,0)>0){ TranslateMessage(&m); DispatchMessageW(&m); } return (int)m.wParam;
}
