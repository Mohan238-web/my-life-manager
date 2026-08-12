from pathlib import Path
import re, sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')

if '#include <d2d1.h>' not in s:
    s=s.replace('#include <shellapi.h>\n', '#include <shellapi.h>\n#include <d2d1.h>\n')
if '#pragma comment(lib,"d2d1.lib")' not in s:
    s=s.replace('#pragma comment(lib,"comctl32.lib")\n', '#pragma comment(lib,"comctl32.lib")\n#pragma comment(lib,"d2d1.lib")\n')

anchor='HWND g_zoom{};\n'
insert='''HWND g_zoom{};\n\nID2D1Factory* g_d2dFactory=nullptr;\nID2D1HwndRenderTarget* g_d2dTarget=nullptr;\nID2D1Bitmap* g_d2dBitmap=nullptr;\nuint32_t g_d2dBitmapW=0, g_d2dBitmapH=0;\nstd::atomic<bool> g_decodedBmpSaved{false};\n'''
if 'g_d2dFactory' not in s:
    if anchor not in s: raise SystemExit('global anchor not found')
    s=s.replace(anchor,insert,1)

helper_anchor='std::wstring randomPin(){ std::random_device rd; std::mt19937 gen(rd()); std::uniform_int_distribution<int> d(0,999999); wchar_t b[7]; swprintf_s(b,L"%06d",d(gen)); return b; }\n\n'
helpers=r'''void releaseD2DTarget(){
    if(g_d2dBitmap){ g_d2dBitmap->Release(); g_d2dBitmap=nullptr; }
    g_d2dBitmapW=g_d2dBitmapH=0;
    if(g_d2dTarget){ g_d2dTarget->Release(); g_d2dTarget=nullptr; }
}

bool ensureD2DTarget(HWND hwnd){
    if(!g_d2dFactory){
        if(FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED,&g_d2dFactory))) return false;
    }
    if(g_d2dTarget) return true;
    RECT rc{}; GetClientRect(hwnd,&rc);
    D2D1_SIZE_U size=D2D1::SizeU((UINT32)std::max<LONG>(1,rc.right-rc.left),(UINT32)std::max<LONG>(1,rc.bottom-rc.top));
    return SUCCEEDED(g_d2dFactory->CreateHwndRenderTarget(
        D2D1::RenderTargetProperties(),D2D1::HwndRenderTargetProperties(hwnd,size),&g_d2dTarget));
}

void saveDecodedBmpOnce(const uint8_t* bgra,uint32_t w,uint32_t h,uint32_t stride){
    if(!bgra||!w||!h||stride<w*4||g_decodedBmpSaved.exchange(true)) return;
    wchar_t exe[MAX_PATH]{}; std::wstring path;
    if(GetModuleFileNameW(nullptr,exe,MAX_PATH)){
        path=exe; size_t slash=path.find_last_of(L"\\/");
        if(slash!=std::wstring::npos) path.resize(slash+1); else path.clear();
        path+=L"PhoneBridge-Decoded-Frame.bmp";
    }
    auto writeBmp=[&](const std::wstring& candidate)->bool{
        HANDLE f=CreateFileW(candidate.c_str(),GENERIC_WRITE,FILE_SHARE_READ,nullptr,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
        if(f==INVALID_HANDLE_VALUE) return false;
        BITMAPFILEHEADER bfh{}; BITMAPINFOHEADER bih{};
        bih.biSize=sizeof(bih); bih.biWidth=(LONG)w; bih.biHeight=-(LONG)h; bih.biPlanes=1; bih.biBitCount=32; bih.biCompression=BI_RGB;
        bih.biSizeImage=w*h*4;
        bfh.bfType=0x4D42; bfh.bfOffBits=sizeof(bfh)+sizeof(bih); bfh.bfSize=bfh.bfOffBits+bih.biSizeImage;
        DWORD n=0; bool ok=WriteFile(f,&bfh,sizeof(bfh),&n,nullptr)&&n==sizeof(bfh);
        ok=ok&&WriteFile(f,&bih,sizeof(bih),&n,nullptr)&&n==sizeof(bih);
        for(uint32_t y=0; ok&&y<h; ++y){
            const uint8_t* row=bgra+(size_t)y*stride;
            ok=WriteFile(f,row,w*4,&n,nullptr)&&n==w*4;
        }
        CloseHandle(f); return ok;
    };
    if(path.empty()||!writeBmp(path)){
        wchar_t temp[MAX_PATH]{}; DWORD n=GetTempPathW(MAX_PATH,temp);
        if(n&&n<MAX_PATH){ path=std::wstring(temp)+L"PhoneBridge-Decoded-Frame.bmp"; if(!writeBmp(path)) path.clear(); }
        else path.clear();
    }
}

'''
if 'void saveDecodedBmpOnce' not in s:
    if helper_anchor not in s: raise SystemExit('helper anchor not found')
    s=s.replace(helper_anchor,helper_anchor+helpers,1)

# Save exact decoder output before shared-bus/render processing.
needle='''                std::vector<uint8_t> bgra; uint32_t w=0,hg=0,stride=0; if(jpeg.decodeToBgra(payload.data(),payload.size(),bgra,w,hg,stride)){\n                    auto now='''
repl='''                std::vector<uint8_t> bgra; uint32_t w=0,hg=0,stride=0; if(jpeg.decodeToBgra(payload.data(),payload.size(),bgra,w,hg,stride)){\n                    saveDecodedBmpOnce(bgra.data(),w,hg,stride);\n                    auto now='''
if 'saveDecodedBmpOnce(bgra.data(),w,hg,stride);' not in s:
    if needle not in s: raise SystemExit('decoded frame insertion point not found')
    s=s.replace(needle,repl,1)

# Reset diagnostic for each new connection.
s=s.replace('g_paired=false; std::string device="Phone";', 'g_paired=false; g_decodedBmpSaved=false; std::string device="Phone";')

new_paint=r'''void paintPreview(HWND hwnd,HDC){
    if(!ensureD2DTarget(hwnd)) return;
    RECT pr=previewRect(hwnd);

    uint32_t fw=0,fh=0,fs=0;
    {
        std::lock_guard<std::mutex> lk(g_frameMutex);
        fw=g_frameW; fh=g_frameH; fs=g_frameStride;
        if(!g_frame.empty()&&fw&&fh&&fs>=fw*4){
            if(!g_d2dBitmap||g_d2dBitmapW!=fw||g_d2dBitmapH!=fh){
                if(g_d2dBitmap){ g_d2dBitmap->Release(); g_d2dBitmap=nullptr; }
                D2D1_BITMAP_PROPERTIES props=D2D1::BitmapProperties(
                    D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM,D2D1_ALPHA_MODE_IGNORE));
                if(SUCCEEDED(g_d2dTarget->CreateBitmap(D2D1::SizeU(fw,fh),g_frame.data(),fs,props,&g_d2dBitmap))){
                    g_d2dBitmapW=fw; g_d2dBitmapH=fh;
                }
            } else {
                g_d2dBitmap->CopyFromMemory(nullptr,g_frame.data(),fs);
            }
        }
    }

    g_d2dTarget->BeginDraw();
    ID2D1SolidColorBrush* brush=nullptr;
    g_d2dTarget->CreateSolidColorBrush(D2D1::ColorF(18.0f/255.0f,20.0f/255.0f,24.0f/255.0f),&brush);
    D2D1_RECT_F preview=D2D1::RectF((FLOAT)pr.left,(FLOAT)pr.top,(FLOAT)pr.right,(FLOAT)pr.bottom);
    if(brush) g_d2dTarget->FillRectangle(preview,brush);

    if(g_d2dBitmap&&fw&&fh){
        const float rw=(float)(pr.right-pr.left), rh=(float)(pr.bottom-pr.top);
        const float scale=std::min(rw/(float)fw,rh/(float)fh);
        const float dw=fw*scale, dh=fh*scale;
        const float dx=pr.left+(rw-dw)*0.5f, dy=pr.top+(rh-dh)*0.5f;
        D2D1_RECT_F dst=D2D1::RectF(dx,dy,dx+dw,dy+dh);
        g_d2dTarget->DrawBitmap(g_d2dBitmap,dst,1.0f,D2D1_BITMAP_INTERPOLATION_MODE_LINEAR,nullptr);
    }
    if(brush) brush->Release();
    HRESULT hr=g_d2dTarget->EndDraw();
    if(hr==D2DERR_RECREATE_TARGET) releaseD2DTarget();
}
'''
pat=r'void paintPreview\(HWND hwnd,HDC dc\)\{.*?\n\}\n\nHFONT makeFont'
m=re.search(pat,s,re.S)
if not m: raise SystemExit('paintPreview block not found')
s=s[:m.start()]+new_paint+'\nHFONT makeFont'+s[m.end():]

# Resize D2D target with the window.
s=s.replace('case WM_SIZE: layout(hwnd); InvalidateRect(hwnd,nullptr,TRUE); return 0;',
'''case WM_SIZE: if(g_d2dTarget){ g_d2dTarget->Resize(D2D1::SizeU((UINT32)std::max(1,LOWORD(lp)),(UINT32)std::max(1,HIWORD(lp)))); } layout(hwnd); InvalidateRect(hwnd,nullptr,TRUE); return 0;''')

# Release D2D resources at shutdown.
s=s.replace('case WM_DESTROY:{ g_running=false;',
'''case WM_DESTROY:{ releaseD2DTarget(); if(g_d2dFactory){ g_d2dFactory->Release(); g_d2dFactory=nullptr; } g_running=false;''')

p.write_text(s,encoding='utf-8')
print('Replaced GDI preview with Direct2D BGRA8 renderer and decoded-frame diagnostic')
