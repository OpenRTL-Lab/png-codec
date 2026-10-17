// OpenRTL file declaration
// Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
// Organization introduction: Open hardware, from algorithms to RTL.
// Repository: png-codec
// Repository introduction: Standalone lossless PNG encoder and decoder.
// File: zlib_reference.c
// Website: https://openrtl.top/
// SPDX-License-Identifier: MIT
/* SPDX-License-Identifier: MIT */
#include <zlib.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void fail(const char *s) {fprintf(stderr,"%s\n",s);exit(1);}
static FILE *file(const char *p,const char *m){FILE *f=fopen(p,m);if(!f)fail("REFERENCE_FILE_ERROR");return f;}
static uint8_t *read_all(const char *name,size_t *size){
    FILE *f=file(name,"rb");if(fseek(f,0,SEEK_END))fail("REFERENCE_SEEK");
    long n=ftell(f);if(n<0 || n>10000000)fail("REFERENCE_SIZE");rewind(f);
    uint8_t *p=malloc(n?n:1);if(!p)fail("REFERENCE_MEMORY");
    if(fread(p,1,n,f)!=(size_t)n)fail("REFERENCE_READ");
    fclose(f);*size=n;return p;
}
int main(int argc,char **argv){
    if(strcmp(zlibVersion(),"1.3.2"))fail("REFERENCE_VERSION");
    if(argc==5 && !strcmp(argv[1],"compress")){
        size_t n;uint8_t *raw=read_all(argv[2],&n);int mode=atoi(argv[4]);
        z_stream s;memset(&s,0,sizeof s);
        if(deflateInit2(&s,mode==0?0:6,Z_DEFLATED,15,8,mode==1?Z_FIXED:Z_DEFAULT_STRATEGY)!=Z_OK)fail("REFERENCE_INIT");
        size_t bound=deflateBound(&s,n);uint8_t *out=malloc(bound);if(!out)fail("REFERENCE_MEMORY");
        s.next_in=raw;s.avail_in=n;s.next_out=out;s.avail_out=bound;
        if(deflate(&s,Z_FINISH)!=Z_STREAM_END || s.avail_in)fail("REFERENCE_COMPRESS");
        FILE *f=file(argv[3],"wb");if(fwrite(out,1,s.total_out,f)!=s.total_out)fail("REFERENCE_WRITE");
        fclose(f);printf("ZLIB_COMPRESS_PASS bytes=%lu\n",s.total_out);deflateEnd(&s);free(out);free(raw);return 0;
    }
    if(argc==4 && !strcmp(argv[1],"check")){
        size_t zn,n;uint8_t *z=read_all(argv[2],&zn),*raw=read_all(argv[3],&n);
        uint8_t *actual=malloc(n+1);if(!actual)fail("REFERENCE_MEMORY");
        uLongf actual_n=n+1;int status=uncompress(actual,&actual_n,z,zn);
        if(status!=Z_OK)fail("REFERENCE_ZLIB_ERROR");
        if(actual_n!=n || memcmp(actual,raw,n))fail("REFERENCE_DATA_MISMATCH");
        printf("ZLIB_REFERENCE_PASS bytes=%zu\n",n);free(z);free(raw);free(actual);return 0;
    }
    if(argc==3 && !strcmp(argv[1],"generate")){
        const unsigned sizes[]={1,2,3,4,259,32767,32768,32769,65536};
        char path[1024];snprintf(path,sizeof path,"%s/cases.txt",argv[2]);FILE *cases=file(path,"w");
        snprintf(path,sizeof path,"%s/raw.hex",argv[2]);FILE *hex=file(path,"w");unsigned offset=0;
        for(unsigned c=0;c<10;c++){
            unsigned n=c<9?sizes[c]:33664; // 所有长度3..258，各多一个前置literal
            snprintf(path,sizeof path,"%s/raw-%02u.bin",argv[2],c);FILE *raw=file(path,"wb");
            unsigned written=0;
            if(c==9){for(unsigned len=3;len<=258;len++)for(unsigned j=0;j<len+1;j++){
                uint8_t v=(len*13)&255;fputc(v,raw);fprintf(hex,"%02x\n",v);written++;
            }}else for(unsigned i=0;i<n;i++){
                uint8_t v=c<=4?0xa5:c==5?(i&255):c==6?0:c==7?((i*37+(i>>7)*19)^0x5a):i%3;
                fputc(v,raw);fprintf(hex,"%02x\n",v);written++;
            }
            if(c==9)n=written;
            fprintf(cases,"%u %u\n",n,offset);offset+=n;fclose(raw);
        }
        fclose(hex);fclose(cases);printf("ZLIB_VECTORS_PASS cases=10 bytes=%u\n",offset);return 0;
    }
    fail("generate DIR / check ZLIB RAW");return 1;
}
