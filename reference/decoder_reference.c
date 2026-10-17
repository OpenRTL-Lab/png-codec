// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: decoder_reference.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * 固定libpng完整文件编码输入，不借用硬件封装器。 */
#include <png.h>
#include <zlib.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void fail(const char *s){fprintf(stderr,"DECODER_REFERENCE_ERROR %s\n",s);exit(1);}
static FILE *file(const char *p,const char *m){FILE *f=fopen(p,m);if(!f)fail(p);return f;}
int main(int argc,char **argv){
    if(argc!=9&&argc!=10)fail("generate DIR TYPE DEPTH WIDTH HEIGHT FILTER MODE [alpha]");
    const char *profile=argc==10?argv[9]:"";
    unsigned all_alpha=!strcmp(profile,"alpha"),min_palette=!strcmp(profile,"min-palette");
    unsigned short_alpha=!strcmp(profile,"short-alpha"),no_metadata=!strcmp(profile,"no-metadata");
    if(*profile&&!all_alpha&&!min_palette&&!short_alpha&&!no_metadata)fail("pattern");
    if(strcmp(argv[1],"generate") || strcmp(png_get_libpng_ver(NULL),"1.6.59") || strcmp(zlibVersion(),"1.3.2"))fail("version/command");
    unsigned t=atoi(argv[3]),b=atoi(argv[4]),w=atoi(argv[5]),h=atoi(argv[6]),filter=atoi(argv[7]),mode=atoi(argv[8]);
    unsigned c=t==2?3:t==4?2:t==6?4:1;
    if(!w||w>8192||!h||h>8192||filter>5||mode>2)fail("arguments");
    size_t rowbytes=((size_t)w*c*b+7)/8;if(rowbytes*h>4*1024*1024)fail("bounded matrix");
    uint8_t *row=calloc(rowbytes,1);if(!row)fail("memory");char path[1024];
    snprintf(path,sizeof path,"%s/raw.bin",argv[2]);FILE *raw=file(path,"wb");
    snprintf(path,sizeof path,"%s/meta.bin",argv[2]);FILE *meta=file(path,"wb");
    png_color palette[256];unsigned plen=t==3?1U<<b:(t==2||t==6?2:0);uint8_t alpha[256];
    unsigned tn=t==3?(plen>1?plen-1:1):t==0?2:t==2?6:0;
    if(min_palette){if(t!=3)fail("min-palette type");plen=1;tn=1;}
    if(short_alpha){if(t!=3)fail("short-alpha type");tn=1;}
    if(no_metadata){plen=0;tn=0;}
    for(unsigned i=0;i<plen;i++){
        palette[i].red=i;palette[i].green=255-i;palette[i].blue=(i*37)&255;
        fputc(palette[i].red,meta);fputc(palette[i].green,meta);fputc(palette[i].blue,meta);
    }
    png_color_16 key;memset(&key,0,sizeof key);unsigned mask=(1U<<b)-1;
    if(t==3){for(unsigned i=0;i<tn;i++){alpha[i]=(i*29)&255;fputc(alpha[i],meta);}}
    if(t==0&&tn){key.gray=0x5a37&mask;fputc(key.gray>>8,meta);fputc(key.gray&255,meta);}
    if(t==2&&tn){key.red=0x5a37&mask;key.green=0x5a98&mask;key.blue=0x5af9&mask;
        for(unsigned i=0;i<3;i++){unsigned v=i==0?key.red:i==1?key.green:key.blue;fputc(v>>8,meta);fputc(v&255,meta);}}
    fclose(meta);
    snprintf(path,sizeof path,"%s/software.png",argv[2]);FILE *f=file(path,"wb");
    png_structp p=png_create_write_struct(PNG_LIBPNG_VER_STRING,NULL,NULL,NULL);png_infop info=png_create_info_struct(p);
    if(!p||!info||setjmp(png_jmpbuf(p)))fail("libpng writer");
    png_init_io(p,f);png_set_IHDR(p,info,w,h,b,t,PNG_INTERLACE_NONE,PNG_COMPRESSION_TYPE_BASE,PNG_FILTER_TYPE_BASE);
    if(plen)png_set_PLTE(p,info,palette,plen);
    if(tn)png_set_tRNS(p,info,t==3?alpha:NULL,t==3?tn:1,t==3?NULL:&key);
    png_set_filter(p,PNG_FILTER_TYPE_BASE,filter==5?PNG_ALL_FILTERS:PNG_FILTER_NONE<<filter);
    png_set_compression_level(p,mode==0?0:6);png_set_compression_strategy(p,mode==1?Z_FIXED:Z_DEFAULT_STRATEGY);
    png_write_info(p,info);
    for(unsigned y=0;y<h;y++){
        memset(row,0,rowbytes);
        for(unsigned x=0;x<w;x++)for(unsigned k=0;k<c;k++){
            unsigned i=x*c+k,v=(x*313U+y*157U+k*97U+x*y*19U+0x5a37U)&mask;
            if(min_palette)v=0;
            if(all_alpha&&(t==4||t==6)&&k==c-1)v=(y*w+x)&mask;
            if(b==16){row[2*i]=v>>8;row[2*i+1]=v&255;}else if(b==8)row[i]=v;
            else row[(i*b)/8]|=v<<(8-b-(i*b)%8);
        }
        if(fwrite(row,1,rowbytes,raw)!=rowbytes)fail("raw write");
        png_write_row(p,row);
    }
    png_write_end(p,info);png_destroy_write_struct(&p,&info);fclose(f);fclose(raw);free(row);
    printf("DECODER_REFERENCE_PASS palette=%u transparency=%u\n",plen*3,tn);return 0;
}
