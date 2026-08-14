from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8-sig')
if '#include <vector>' not in s:
    pos=s.find('\n',s.find('#include'))
    s=s[:pos+1]+'#include <vector>\n'+s[pos+1:]

def replace_cpp_function(text,signature,replacement):
    start=text.index(signature)
    brace=text.index('{',start)
    depth=0
    for i in range(brace,len(text)):
        if text[i]=='{': depth+=1
        elif text[i]=='}':
            depth-=1
            if depth==0:
                return text[:start]+replacement+text[i+1:]
    raise RuntimeError('Could not locate function end: '+signature)

frame=r'''HRESULT SimpleFrameGenerator::_CreateRGB32Frame(
    _Inout_updates_bytes_(len) BYTE* pBuf,
    _In_ DWORD len,
    _In_ LONG pitch,
    _In_ DWORD width,
    _In_ DWORD height,
    _In_ ULONG rgbMask )
{
    // PhoneBridgeStableFitReader v1.7
    // Reopen/remap video.bus for each sample like the proven v1.5 path, but keep
    // the last complete frame and render it with FIT_CENTER. This avoids stale
    // persistent mappings, avoids read-modify-write operations on a read-only map,
    // prevents portrait stretching, and avoids black flashes during a torn write.
    if(!pBuf || width==0 || height==0) return E_INVALIDARG;
    const LONG dstPitch=pitch>=0?pitch:-pitch;
    if(dstPitch<static_cast<LONG>(width*4) || len<static_cast<DWORD>(dstPitch)*height)
        return HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);
    (void)rgbMask;

    auto fillBlack=[&](){
        for(DWORD y=0;y<height;++y){
            BYTE* row=pitch>=0?pBuf+static_cast<size_t>(y)*dstPitch
                              :pBuf+static_cast<size_t>(height-1-y)*dstPitch;
            memset(row,0,static_cast<size_t>(width)*4);
            for(DWORD x=0;x<width;++x) row[static_cast<size_t>(x)*4+3]=255;
        }
    };
    fillBlack();

    static thread_local std::vector<BYTE> s_cached;
    static thread_local DWORD s_cachedW=0,s_cachedH=0,s_cachedStride=0;

    wchar_t base[MAX_PATH]{};
    if(GetEnvironmentVariableW(L"ProgramData",base,MAX_PATH)){
        const std::wstring dir=std::wstring(base)+L"\\PhoneBridge";
        const std::wstring blackFlag=dir+L"\\camera-black.flag";
        if(GetFileAttributesW(blackFlag.c_str())==INVALID_FILE_ATTRIBUTES){
            const std::wstring path=dir+L"\\video.bus";
            HANDLE file=CreateFileW(path.c_str(),GENERIC_READ,
                FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,
                nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
            if(file!=INVALID_HANDLE_VALUE){
                LARGE_INTEGER fs{};
                if(GetFileSizeEx(file,&fs) && fs.QuadPart>=28){
                    HANDLE map=CreateFileMappingW(file,nullptr,PAGE_READONLY,0,0,nullptr);
                    if(map){
                        BYTE* view=static_cast<BYTE*>(MapViewOfFile(map,FILE_MAP_READ,0,0,0));
                        if(view){
                            LONG seq1=0,seq2=0;
                            memcpy(&seq1,view,4);
                            MemoryBarrier();
                            if(!(seq1&1)){
                                DWORD srcW=0,srcH=0,srcStride=0,srcBytes=0;
                                memcpy(&srcW,view+4,4);
                                memcpy(&srcH,view+8,4);
                                memcpy(&srcStride,view+12,4);
                                memcpy(&srcBytes,view+16,4);
                                const ULONGLONG required=static_cast<ULONGLONG>(srcStride)*srcH;
                                const bool valid=srcW&&srcH&&srcW<=4096&&srcH<=4096&&
                                    srcStride>=srcW*4u&&srcStride<=65536u&&
                                    srcBytes>=required&&srcBytes<=256u*1024u*1024u&&
                                    static_cast<ULONGLONG>(fs.QuadPart)>=28ull+srcBytes;
                                if(valid){
                                    std::vector<BYTE> candidate(srcBytes);
                                    memcpy(candidate.data(),view+28,srcBytes);
                                    MemoryBarrier();
                                    memcpy(&seq2,view,4);
                                    if(seq1==seq2 && !(seq2&1)){
                                        s_cached.swap(candidate);
                                        s_cachedW=srcW; s_cachedH=srcH; s_cachedStride=srcStride;
                                    }
                                }
                            }
                            UnmapViewOfFile(view);
                        }
                        CloseHandle(map);
                    }
                }
                CloseHandle(file);
            }
        }
    }

    if(s_cached.empty()||!s_cachedW||!s_cachedH||!s_cachedStride) return S_OK;

    if(s_cachedW==width && s_cachedH==height){
        for(DWORD y=0;y<height;++y){
            const BYTE* src=s_cached.data()+static_cast<size_t>(y)*s_cachedStride;
            BYTE* dst=pitch>=0?pBuf+static_cast<size_t>(y)*dstPitch
                              :pBuf+static_cast<size_t>(height-1-y)*dstPitch;
            memcpy(dst,src,static_cast<size_t>(width)*4);
        }
        return S_OK;
    }

    DWORD fitW=width;
    DWORD fitH=static_cast<DWORD>((static_cast<ULONGLONG>(s_cachedH)*width)/s_cachedW);
    if(fitH>height){
        fitH=height;
        fitW=static_cast<DWORD>((static_cast<ULONGLONG>(s_cachedW)*height)/s_cachedH);
    }
    if(!fitW) fitW=1; if(!fitH) fitH=1;
    const DWORD offX=(width-fitW)/2, offY=(height-fitH)/2;

    for(DWORD y=0;y<fitH;++y){
        const DWORD sy=static_cast<DWORD>((static_cast<ULONGLONG>(y)*s_cachedH)/fitH);
        const BYTE* srcRow=s_cached.data()+static_cast<size_t>(sy)*s_cachedStride;
        const DWORD dy=offY+y;
        BYTE* dstRow=pitch>=0?pBuf+static_cast<size_t>(dy)*dstPitch
                            :pBuf+static_cast<size_t>(height-1-dy)*dstPitch;
        for(DWORD x=0;x<fitW;++x){
            const DWORD sx=static_cast<DWORD>((static_cast<ULONGLONG>(x)*s_cachedW)/fitW);
            const BYTE* src=srcRow+static_cast<size_t>(sx)*4;
            BYTE* dst=dstRow+static_cast<size_t>(offX+x)*4;
            dst[0]=src[0]; dst[1]=src[1]; dst[2]=src[2]; dst[3]=255;
        }
    }
    return S_OK;
}
'''

s=replace_cpp_function(s,'HRESULT SimpleFrameGenerator::_CreateRGB32Frame(',frame)
for marker in ['PhoneBridgeStableFitReader v1.7','s_cached.swap(candidate)','FIT_CENTER']:
    if marker not in s: raise SystemExit('Missing v1.7 camera marker: '+marker)
if 'InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(s_view)' in s:
    raise SystemExit('Regression: read-modify-write sequence read returned')
p.write_text(s,encoding='utf-8')
print('Applied PhoneBridge v1.7 stable per-sample mapping, last-good cache and FIT_CENTER rendering')
