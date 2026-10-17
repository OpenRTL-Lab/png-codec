// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: row_reference.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * 离线行答案：独立C预测器构造PNG，再由固定libpng逆滤波逐字节核对。
 * 参考仅用于测试，不接入产品或仿真运行期。 */
#include <png.h>
#include <zlib.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void fail(const char *s) { fprintf(stderr,"ROW_REFERENCE_ERROR %s\n",s); exit(1); }
static FILE *file(const char *p,const char *mode) {
    FILE *f=fopen(p,mode); if(!f)fail(p); return f;
}
static void be32(FILE *f,uint32_t n) {
    for(int i=3;i>=0;i--) fputc((n>>(i*8))&255,f);
}
static void chunk(FILE *f,const char *type,const uint8_t *data,size_t n) {
    be32(f,(uint32_t)n); fwrite(type,1,4,f);
    if(n)fwrite(data,1,n,f);
    uLong c=crc32(0L,Z_NULL,0); c=crc32(c,(const Bytef*)type,4);
    if(n)c=crc32(c,data,(uInt)n);
    be32(f,(uint32_t)c);
}
static int paeth(int a,int b,int c) {
    int p=a+b-c,pa=abs(p-a),pb=abs(p-b),pc=abs(p-c);
    return pa<=pb && pa<=pc ? a : pb<=pc ? b : c;
}
static unsigned components(unsigned t) {
    switch(t){case 0:case 3:return 1;case 2:return 3;case 4:return 2;case 6:return 4;}
    fail("color type");return 0;
}
static int legal(unsigned t,unsigned b) {
    return ((t==0 || t==3) && (b==1 || b==2 || b==4 || b==8)) ||
           ((t==0 || t==2 || t==4 || t==6) && (b==8 || b==16));
}
static void row_pattern(uint8_t *row,unsigned w,unsigned y,unsigned c,unsigned b) {
    size_t bytes=((size_t)w*c*b+7)/8; memset(row,0,bytes);
    for(unsigned x=0;x<w;x++)for(unsigned k=0;k<c;k++) {
        unsigned index=x*c+k;
        unsigned v=(x*313U+y*157U+k*97U+x*y*19U+0x5a37U)&((1U<<b)-1);
        if(b==16){ row[2*index]=v>>8; row[2*index+1]=v&255; }
        else if(b==8)row[index]=v;
        else row[(index*b)/8]|=v<<(8-b-((index*b)%8));
    }
}
static void check_libpng(const char *name,unsigned w,unsigned h,unsigned t,unsigned b) {
    FILE *f=file(name,"rb");
    png_structp p=png_create_read_struct(PNG_LIBPNG_VER_STRING,NULL,NULL,NULL);
    png_infop info=png_create_info_struct(p);
    if(!p || !info || setjmp(png_jmpbuf(p)))fail("libpng decode");
    png_init_io(p,f); png_set_crc_action(p,PNG_CRC_ERROR_QUIT,PNG_CRC_ERROR_QUIT);
    png_read_info(p,info);
    if(png_get_image_width(p,info)!=w || png_get_image_height(p,info)!=h ||
       png_get_color_type(p,info)!=t || png_get_bit_depth(p,info)!=b)fail("format");
    size_t n=((size_t)w*components(t)*b+7)/8;
    uint8_t *row=malloc(n),*expected=malloc(n);if(!row||!expected)fail("memory");
    for(unsigned y=0;y<h;y++) {
        png_read_row(p,row,NULL);row_pattern(expected,w,y,components(t),b);
        if(memcmp(row,expected,n))fail("independent libpng row mismatch");
    }
    png_read_end(p,info);png_destroy_read_struct(&p,&info,NULL);
    free(row);free(expected);fclose(f);
}
int main(int argc,char **argv) {
    if(argc!=9)fail("generate DIR TYPE DEPTH WIDTH HEIGHT FILTER MIXED");
    if(strcmp(argv[1],"generate"))fail("command");
    if(strcmp(png_get_libpng_ver(NULL),"1.6.59") || strcmp(zlibVersion(),"1.3.2"))fail("version");
    unsigned t=strtoul(argv[3],NULL,10),b=strtoul(argv[4],NULL,10);
    unsigned w=strtoul(argv[5],NULL,10),h=strtoul(argv[6],NULL,10);
    unsigned filter=strtoul(argv[7],NULL,10),mixed=strtoul(argv[8],NULL,10);
    if(!legal(t,b)||!w||w>8192||!h||h>8192||filter>4||mixed>1)fail("arguments");
    unsigned c=components(t),bpp=(c*b+7)/8;
    size_t rowbytes=((size_t)w*c*b+7)/8,filtered_n=(rowbytes+1)*h;
    /* 本参考用于有界行矩阵；大图另用逐行流式构造器，不分配整图历史。 */
    if(filtered_n>4*1024*1024)fail("bounded matrix reference limit");
    uint8_t *row=calloc(rowbytes,1),*prev=calloc(rowbytes,1),*all=malloc(filtered_n);
    if(!row||!prev||!all)fail("memory");
    char path[1024];
    snprintf(path,sizeof path,"%s/raw.hex",argv[2]);FILE *raw=file(path,"w");
    snprintf(path,sizeof path,"%s/filtered.hex",argv[2]);FILE *filtered=file(path,"w");
    snprintf(path,sizeof path,"%s/filters.hex",argv[2]);FILE *filters=file(path,"w");
    for(unsigned y=0;y<h;y++) {
        row_pattern(row,w,y,c,b);unsigned f=mixed?(y+filter)%5:filter;
        all[y*(rowbytes+1)]=f;
        for(size_t x=0;x<rowbytes;x++) {
            int a=x>=bpp?row[x-bpp]:0,up=prev[x],ul=x>=bpp?prev[x-bpp]:0;
            int predictor=f==0?0:f==1?a:f==2?up:f==3?(a+up)/2:paeth(a,up,ul);
            uint8_t v=(row[x]-predictor)&255;
            fprintf(raw,"%02x\n",row[x]);fprintf(filtered,"%02x\n",v);fprintf(filters,"%02x\n",f);
            all[y*(rowbytes+1)+x+1]=v;
        }
        memcpy(prev,row,rowbytes);
    }
    fclose(raw);fclose(filtered);fclose(filters);
    uLongf zlen=compressBound(filtered_n);uint8_t *z=malloc(zlen);if(!z)fail("zlib memory");
    if(compress2(z,&zlen,all,filtered_n,6)!=Z_OK)fail("compress");
    snprintf(path,sizeof path,"%s/software.png",argv[2]);FILE *png=file(path,"wb");
    fwrite("\x89PNG\r\n\x1a\n",1,8,png);uint8_t ihdr[13]={0};
    for(unsigned i=0;i<4;i++){ihdr[i]=(w>>(24-i*8))&255;ihdr[i+4]=(h>>(24-i*8))&255;}
    ihdr[8]=b;ihdr[9]=t;chunk(png,"IHDR",ihdr,13);
    if(t==3){uint8_t palette[768];unsigned count=1U<<b;
        for(unsigned i=0;i<count;i++){palette[3*i]=i;palette[3*i+1]=255-i;palette[3*i+2]=(i*37)&255;}
        chunk(png,"PLTE",palette,3*count);
    }
    chunk(png,"IDAT",z,zlen);chunk(png,"IEND",NULL,0);fclose(png);
    check_libpng(path,w,h,t,b);
    snprintf(path,sizeof path,"%s/config.json",argv[2]);FILE *cfg=file(path,"w");
    fprintf(cfg,"{\"width\":%u,\"height\":%u,\"color_type\":%u,\"bit_depth\":%u,\"row_bytes\":%zu,\"bpp\":%u,\"filter\":%u,\"mixed\":%u}\n",w,h,t,b,rowbytes,bpp,filter,mixed);
    fclose(cfg);free(z);free(all);free(row);free(prev);
    printf("ROW_REFERENCE_PASS type=%u depth=%u filter=%u mixed=%u bytes=%zu\n",t,b,filter,mixed,rowbytes*h);
    return 0;
}
