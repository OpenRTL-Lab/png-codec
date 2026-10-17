// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: large_reference.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * 完整图像以单行有界内存生成/检查，逐位零样本不以摘要替代像素。 */
#include <png.h>
#include <zlib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void fail(const char *s){fprintf(stderr,"LARGE_REFERENCE_ERROR %s\n",s);exit(1);}
int main(int argc,char **argv){
    if(argc!=5)fail("generate/check PNG W H");
    if(strcmp(png_get_libpng_ver(NULL),"1.6.59")||strcmp(zlibVersion(),"1.3.2"))fail("fixed version");
    unsigned w=atoi(argv[3]),h=atoi(argv[4]);if(!w||!h||w>8192||h>8192)fail("dimensions");
    size_t n=(w+7)/8;unsigned char *row=calloc(n,1);if(!row)fail("row allocation");
    int writing=!strcmp(argv[1],"generate");if(!writing&&strcmp(argv[1],"check"))fail("command");
    FILE *f=fopen(argv[2],writing?"wb":"rb");if(!f)fail("file");
    png_structp p=writing?png_create_write_struct(PNG_LIBPNG_VER_STRING,NULL,NULL,NULL):png_create_read_struct(PNG_LIBPNG_VER_STRING,NULL,NULL,NULL);
    png_infop info=png_create_info_struct(p);if(!p||!info||setjmp(png_jmpbuf(p)))fail("libpng");
    png_init_io(p,f);
    if(writing){
        png_set_IHDR(p,info,w,h,1,PNG_COLOR_TYPE_GRAY,0,0,0);png_set_filter(p,0,PNG_FILTER_NONE);png_set_compression_level(p,9);
        png_write_info(p,info);for(unsigned y=0;y<h;y++)png_write_row(p,row);png_write_end(p,info);png_destroy_write_struct(&p,&info);
    }else{
        png_set_crc_action(p,PNG_CRC_ERROR_QUIT,PNG_CRC_ERROR_QUIT);png_read_info(p,info);
        if(png_get_image_width(p,info)!=w||png_get_image_height(p,info)!=h||png_get_color_type(p,info)!=0||png_get_bit_depth(p,info)!=1||png_get_rowbytes(p,info)!=n)fail("format");
        for(unsigned y=0;y<h;y++){
            png_read_row(p,row,NULL);
            for(unsigned x=0;x<w;x++)if((row[x/8]>>(7-x%8))&1){fprintf(stderr,"LARGE_REFERENCE_ERROR pixel mismatch x=%u y=%u\n",x,y);return 1;}
        }
        png_read_end(p,info);png_destroy_read_struct(&p,&info,NULL);
    }
    free(row);fclose(f);printf("LARGE_REFERENCE_PASS width=%u height=%u pixels=%u row_bytes=%zu\n",w,h,w*h,n);return 0;
}
