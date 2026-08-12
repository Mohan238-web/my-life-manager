#include "JpegDecoder.h"
#include <turbojpeg.h>
#include <cstdint>
#include <cstdio>
#include <vector>

static bool dominant(const std::vector<uint8_t>& bgra, int w, int x, int y, int channel){
    const uint8_t* p = &bgra[(static_cast<size_t>(y) * w + x) * 4];
    int b=p[0], g=p[1], r=p[2];
    if(channel==0) return r>180 && r>g+80 && r>b+80;
    if(channel==1) return g>180 && g>r+80 && g>b+80;
    if(channel==2) return b>180 && b>r+80 && b>g+80;
    return r>200 && g>200 && b>200;
}

int main(){
    constexpr int W=64,H=64;
    std::vector<uint8_t> rgb(W*H*3);
    for(int y=0;y<H;y++) for(int x=0;x<W;x++){
        uint8_t r=255,g=255,b=255;
        if(x<32 && y<32){ r=255; g=0; b=0; }
        else if(x>=32 && y<32){ r=0; g=255; b=0; }
        else if(x<32){ r=0; g=0; b=255; }
        size_t i=(static_cast<size_t>(y)*W+x)*3;
        rgb[i]=r; rgb[i+1]=g; rgb[i+2]=b;
    }

    tjhandle enc=tjInitCompress();
    if(!enc) return 10;
    unsigned char* jpeg=nullptr; unsigned long jpegSize=0;
    int rc=tjCompress2(enc,rgb.data(),W,W*3,H,TJPF_RGB,&jpeg,&jpegSize,TJSAMP_444,100,0);
    tjDestroy(enc);
    if(rc!=0 || !jpeg || !jpegSize) return 11;

    JpegDecoder dec;
    if(!dec.init()){ tjFree(jpeg); return 12; }
    std::vector<uint8_t> bgra; uint32_t w=0,h=0,stride=0;
    bool ok=dec.decodeToBgra(jpeg,jpegSize,bgra,w,h,stride);
    tjFree(jpeg);
    if(!ok || w!=W || h!=H || stride!=W*4) return 13;

    bool colors = dominant(bgra,W,16,16,0) && dominant(bgra,W,48,16,1) &&
                  dominant(bgra,W,16,48,2) && dominant(bgra,W,48,48,3);
    if(!colors){
        std::fprintf(stderr,"BGRA decoder channel self-test failed\n");
        return 14;
    }
    std::puts("PhoneBridge TurboJPEG BGRA self-test passed");
    return 0;
}
