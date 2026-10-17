// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: png_reference.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * 独立软件参考：仅调用固定libpng/zlib，不参与DUT运行。 */
#include <png.h>
#include <zlib.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static void fail(const char *s) { fprintf(stderr, "参考错误：%s\n", s); exit(1); }
static FILE *open_file(const char *s, const char *m) {
    FILE *f = fopen(s, m); if (!f) fail(s); return f;
}
static void put32(FILE *f, uint32_t v) {
    for (int i = 3; i >= 0; --i) fputc((v >> (8*i)) & 255, f);
}
static void chunk(FILE *f, const char *name, const uint8_t *p, size_t n) {
    put32(f, (uint32_t)n); fwrite(name, 1, 4, f); if (n) fwrite(p,1,n,f);
    uLong crc = crc32(0L, Z_NULL, 0); crc = crc32(crc, (const Bytef *)name, 4);
    if (n) crc = crc32(crc, p, (uInt)n);
    put32(f, (uint32_t)crc);
}
static void write_png(const char *name, const uint8_t *raw, unsigned w, unsigned h) {
    FILE *f = open_file(name, "wb");
    png_structp p = png_create_write_struct(PNG_LIBPNG_VER_STRING,NULL,NULL,NULL);
    png_infop info = png_create_info_struct(p);
    if (!p || !info || setjmp(png_jmpbuf(p))) fail("libpng写入");
    png_init_io(p,f); png_set_IHDR(p,info,w,h,8,PNG_COLOR_TYPE_GRAY,0,0,0);
    png_set_filter(p,0,PNG_FILTER_NONE); png_set_compression_level(p,0);
    png_write_info(p,info);
    for (unsigned y=0;y<h;y++) png_write_row(p,(png_const_bytep)(raw+y*w));
    png_write_end(p,info); png_destroy_write_struct(&p,&info); fclose(f);
}
static void check_png(const char *name, const char *raw_name, unsigned w, unsigned h) {
    FILE *f=open_file(name,"rb"), *r=open_file(raw_name,"rb");
    png_structp p=png_create_read_struct(PNG_LIBPNG_VER_STRING,NULL,NULL,NULL);
    png_infop info=png_create_info_struct(p);
    if (!p || !info || setjmp(png_jmpbuf(p))) fail("libpng读取");
    png_init_io(p,f); png_set_crc_action(p,PNG_CRC_ERROR_QUIT,PNG_CRC_ERROR_QUIT);
    png_read_info(p,info);
    if (png_get_image_width(p,info)!=w || png_get_image_height(p,info)!=h ||
        png_get_bit_depth(p,info)!=8 || png_get_color_type(p,info)!=PNG_COLOR_TYPE_GRAY)
        fail("Gray8样本元数据");
    uint8_t *row=malloc(w); if (!row) fail("行内存");
    for(unsigned y=0;y<h;y++) {
        png_read_row(p,row,NULL);
        for(unsigned x=0;x<w;x++) if(fgetc(r)!=row[x]) fail("逐像素差异");
    }
    if(fgetc(r)!=EOF) fail("原始像素尾部");
    png_read_end(p,info); png_destroy_read_struct(&p,&info,NULL);
    free(row); fclose(f); fclose(r);
    printf("REFERENCE_PNG_PASS width=%u height=%u pixels=%u\n",w,h,w*h);
}
static void hex_file(const char *input,const char *out) {
    FILE *f=open_file(input,"rb"),*o=open_file(out,"w"); int c;
    while((c=fgetc(f))!=EOF) fprintf(o,"%02x\n",c);
    fclose(f); fclose(o);
}
static void generate(const char *dir,unsigned w,unsigned h) {
    char raw_path[1024], png_path[1024], gold_path[1024], hex_path[1024];
    snprintf(raw_path,sizeof raw_path,"%s/raw.bin",dir);
    snprintf(png_path,sizeof png_path,"%s/software.png",dir);
    snprintf(gold_path,sizeof gold_path,"%s/canonical.png",dir);
    size_t n=(size_t)w*h, filtered_n=(size_t)(w+1)*h;
    if (!w || !h || filtered_n>32757) fail("首样本尺寸");
    uint8_t *raw=malloc(n),*filtered=malloc(filtered_n); if(!raw||!filtered)fail("内存");
    for(unsigned y=0;y<h;y++) {
        filtered[(size_t)y*(w+1)]=0;
        for(unsigned x=0;x<w;x++) {
            uint8_t v=(uint8_t)((x*37+y*19+(x*y))^0x5a);
            raw[(size_t)y*w+x]=v; filtered[(size_t)y*(w+1)+x+1]=v;
        }
    }
    FILE *f=open_file(raw_path,"wb"); fwrite(raw,1,n,f); fclose(f);
    write_png(png_path,raw,w,h);
    /* 独立规范封装器：匹配硬件此阶段的固定STORED策略，校验来自zlib。 */
    uint8_t *z=malloc(filtered_n+11); if(!z)fail("zlib内存");
    z[0]=0x78;z[1]=0x01;z[2]=1; z[3]=filtered_n&255;z[4]=(filtered_n>>8)&255;
    z[5]=(~filtered_n)&255;z[6]=((~filtered_n)>>8)&255;
    memcpy(z+7,filtered,filtered_n);
    uLong ad=adler32(1L,filtered,(uInt)filtered_n);
    for(unsigned i=0;i<4;i++)z[7+filtered_n+i]=(ad>>(24-8*i))&255;
    f=open_file(gold_path,"wb"); fwrite("\x89PNG\r\n\x1a\n",1,8,f);
    uint8_t ihdr[13]={0};
    for(unsigned i=0;i<4;i++){ihdr[i]=(w>>(24-8*i))&255;ihdr[i+4]=(h>>(24-8*i))&255;}
    ihdr[8]=8;chunk(f,"IHDR",ihdr,13);chunk(f,"IDAT",z,filtered_n+11);chunk(f,"IEND",NULL,0);fclose(f);
    snprintf(hex_path,sizeof hex_path,"%s/input.hex",dir);hex_file(raw_path,hex_path);
    snprintf(hex_path,sizeof hex_path,"%s/golden.hex",dir);hex_file(gold_path,hex_path);
    check_png(png_path,raw_path,w,h);check_png(gold_path,raw_path,w,h);
    free(z);free(filtered);free(raw);
}
int main(int argc,char **argv) {
    if(strcmp(png_get_libpng_ver(NULL),"1.6.59") || strcmp(zlibVersion(),"1.3.2"))fail("固定版本不一致");
    if(argc==2 && !strcmp(argv[1],"version")) {printf("libpng=%s zlib=%s\n",PNG_LIBPNG_VER_STRING,zlibVersion());return 0;}
    if(argc==5 && !strcmp(argv[1],"generate")){generate(argv[2],strtoul(argv[3],NULL,10),strtoul(argv[4],NULL,10));return 0;}
    if(argc==6 && !strcmp(argv[1],"check")){check_png(argv[2],argv[3],strtoul(argv[4],NULL,10),strtoul(argv[5],NULL,10));return 0;}
    fail("用法 version / generate DIR W H / check PNG RAW W H");return 1;
}
