// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: rgba16_check.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT
 * 一支为libpng官方颜色展开；另一支只从独立原始样本及必要元数据按规范展开。 */
#include <png.h>
#include <zlib.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static void fail(const char *s){fprintf(stderr,"RGBA16_REFERENCE_ERROR %s\n",s);exit(1);}
static FILE *openf(const char *s){FILE *f=fopen(s,"rb");if(!f)fail(s);return f;}
static unsigned sample(const uint8_t *row,unsigned i,unsigned b){
    if(b==16)return ((unsigned)row[i*2]<<8)|row[i*2+1];
    if(b==8)return row[i];
    return (row[i*b/8]>>(8-b-i*b%8))&((1U<<b)-1);
}
static unsigned be16(const uint8_t *v){return ((unsigned)v[0]<<8)|v[1];}
int main(int argc,char **argv){
    if(argc!=10)fail("PNG RAW META W H TYPE DEPTH PLEN TLEN");
    if(strcmp(png_get_libpng_ver(NULL),"1.6.59")||strcmp(zlibVersion(),"1.3.2"))fail("fixed version");
    unsigned w=atoi(argv[4]),h=atoi(argv[5]),t=atoi(argv[6]),b=atoi(argv[7]),pn=atoi(argv[8]),tn=atoi(argv[9]);
    unsigned c=t==2?3:t==4?2:t==6?4:1;
    if(!w||!h||w>8192||h>8192||!b||b>16||pn+tn>1024)fail("arguments");
    FILE *image=openf(argv[1]),*raw=openf(argv[2]),*mf=openf(argv[3]);uint8_t meta[1024];
    if(fread(meta,1,pn+tn,mf)!=pn+tn||fgetc(mf)!=EOF)fail("metadata length");
    fclose(mf);
    png_structp p=png_create_read_struct(PNG_LIBPNG_VER_STRING,NULL,NULL,NULL);
    png_infop info=png_create_info_struct(p);if(!p||!info||setjmp(png_jmpbuf(p)))fail("libpng");
    png_init_io(p,image);png_set_crc_action(p,PNG_CRC_ERROR_QUIT,PNG_CRC_ERROR_QUIT);png_read_info(p,info);
    if(png_get_image_width(p,info)!=w||png_get_image_height(p,info)!=h||png_get_color_type(p,info)!=t||png_get_bit_depth(p,info)!=b)fail("original format");
    int has_trns=png_get_valid(p,info,PNG_INFO_tRNS)!=0;
    if(t==3)png_set_palette_to_rgb(p);
    if(t==0&&b<8)png_set_expand_gray_1_2_4_to_8(p);
    if(has_trns)png_set_tRNS_to_alpha(p);
    if(t==0||t==4)png_set_gray_to_rgb(p);
    png_set_expand_16(p);
    if(!(t&PNG_COLOR_MASK_ALPHA)&&!has_trns)png_set_add_alpha(p,65535,PNG_FILLER_AFTER);
    png_read_update_info(p,info);
    if(png_get_bit_depth(p,info)!=16||png_get_channels(p,info)!=4||png_get_rowbytes(p,info)!=(size_t)w*8)fail("RGBA16 output format");
    size_t n=((size_t)w*c*b+7)/8;uint8_t *input=malloc(n),*rgba=malloc((size_t)w*8);if(!input||!rgba)fail("row memory");
    unsigned scale=65535/((1U<<b)-1);
    for(unsigned y=0;y<h;y++){
        png_read_row(p,rgba,NULL);if(fread(input,1,n,raw)!=n)fail("raw length");
        for(unsigned x=0;x<w;x++){
            unsigned s[4]={0,0,0,(1U<<b)-1},gold[4];
            for(unsigned k=0;k<c;k++)s[k]=sample(input,x*c+k,b);
            if(t==3){
                unsigned index=s[0];if(3*index+2>=pn)fail("palette index");
                for(unsigned k=0;k<3;k++)gold[k]=meta[3*index+k]*257U;
                gold[3]=(index<tn?meta[pn+index]:255)*257U;
            }else{
                if(t==0||t==4){gold[0]=gold[1]=gold[2]=s[0]*scale;gold[3]=t==4?s[1]*scale:65535;}
                else{for(unsigned k=0;k<3;k++)gold[k]=s[k]*scale;gold[3]=t==6?s[3]*scale:65535;}
                if(t==0&&tn&&s[0]==be16(meta+pn))gold[3]=0;
                if(t==2&&tn&&s[0]==be16(meta+pn)&&s[1]==be16(meta+pn+2)&&s[2]==be16(meta+pn+4))gold[3]=0;
            }
            for(unsigned k=0;k<4;k++)if(be16(rgba+(size_t)x*8+2*k)!=gold[k]){
                fprintf(stderr,"RGBA16_REFERENCE_ERROR semantic mismatch x=%u y=%u channel=%u\n",x,y,k);return 1;
            }
        }
    }
    if(fgetc(raw)!=EOF)fail("raw trailing data");
    png_read_end(p,info);png_destroy_read_struct(&p,&info,NULL);
    free(input);free(rgba);fclose(image);fclose(raw);
    printf("RGBA16_REFERENCE_PASS width=%u height=%u type=%u depth=%u pixels=%u\n",w,h,t,b,w*h);return 0;
}
