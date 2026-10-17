// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: encoder_check.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * 固定libpng离线读实际RTL文件，原格式逐字节及PLTE/tRNS检查。 */
#include <png.h>
#include <zlib.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void fail(const char *s){fprintf(stderr,"ENCODER_REFERENCE_ERROR %s\n",s);exit(1);}
static FILE *openf(const char *p){FILE *f=fopen(p,"rb");if(!f)fail(p);return f;}
int main(int argc,char **argv){
    if(argc!=10)fail("PNG RAW META W H TYPE DEPTH PLEN TLEN");
    if(strcmp(png_get_libpng_ver(NULL),"1.6.59")||strcmp(zlibVersion(),"1.3.2"))fail("version");
    unsigned w=strtoul(argv[4],0,10),h=strtoul(argv[5],0,10),t=strtoul(argv[6],0,10);
    unsigned b=strtoul(argv[7],0,10),plen=strtoul(argv[8],0,10),tlen=strtoul(argv[9],0,10);
    FILE *f=openf(argv[1]),*raw=openf(argv[2]),*mf=openf(argv[3]);uint8_t meta[1024];
    if(plen+tlen>sizeof meta || fread(meta,1,plen+tlen,mf)!=plen+tlen || fgetc(mf)!=EOF)fail("meta file");
    fclose(mf);
    png_structp p=png_create_read_struct(PNG_LIBPNG_VER_STRING,0,0,0);
    png_infop info=png_create_info_struct(p);if(!p||!info||setjmp(png_jmpbuf(p)))fail("libpng");
    png_init_io(p,f);png_set_crc_action(p,PNG_CRC_ERROR_QUIT,PNG_CRC_ERROR_QUIT);png_read_info(p,info);
    if(png_get_image_width(p,info)!=w||png_get_image_height(p,info)!=h||
       png_get_color_type(p,info)!=t||png_get_bit_depth(p,info)!=b)fail("format");
    png_colorp palette=0;int entries=0;
    if(plen){
        if(!png_get_PLTE(p,info,&palette,&entries)||(unsigned)entries*3!=plen)fail("palette length");
        for(int i=0;i<entries;i++)if(palette[i].red!=meta[3*i]||palette[i].green!=meta[3*i+1]||palette[i].blue!=meta[3*i+2])fail("palette value");
    }else if(png_get_valid(p,info,PNG_INFO_PLTE))fail("unexpected palette");
    png_bytep alpha=0;int alen=0;png_color_16p key=0;
    if(tlen){
        if(!png_get_tRNS(p,info,&alpha,&alen,&key))fail("missing tRNS");
        const uint8_t *v=meta+plen;
        if(t==3){if((unsigned)alen!=tlen||memcmp(alpha,v,tlen))fail("alpha table");}
        else if(t==0){if(!key||key->gray!=((unsigned)v[0]<<8|v[1]))fail("gray key");}
        else if(t==2){if(!key||key->red!=((unsigned)v[0]<<8|v[1])||key->green!=((unsigned)v[2]<<8|v[3])||key->blue!=((unsigned)v[4]<<8|v[5]))fail("RGB key");}
    }else if(png_get_valid(p,info,PNG_INFO_tRNS))fail("unexpected tRNS");
    size_t n=png_get_rowbytes(p,info);uint8_t *row=malloc(n),*expected=malloc(n);if(!row||!expected)fail("memory");
    for(unsigned y=0;y<h;y++){
        png_read_row(p,row,0);if(fread(expected,1,n,raw)!=n||memcmp(row,expected,n))fail("sample mismatch");
    }
    if(fgetc(raw)!=EOF)fail("raw end");
    png_read_end(p,info);png_destroy_read_struct(&p,&info,0);
    fclose(raw);fclose(f);free(row);free(expected);
    printf("ENCODER_REFERENCE_PASS width=%u height=%u type=%u depth=%u metadata=%u\n",w,h,t,b,plen+tlen);return 0;
}
