import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const build=path.join(root,'build/rance10-tests');
const libs=fs.readFileSync(path.join(root,'xsystem4/src/hll/NewerLibs.c'),'utf8');
const split=libs.slice(libs.indexOf('static struct page *String_Split('),libs.indexOf('HLL_LIBRARY(String,'));
const utf=fs.readFileSync(path.join(root,'xsystem4/subprojects/libsys4/include/system4/utfsjis.h'),'utf8');
const leadMatch=utf.match(/#define SJIS_2BYTE\(b\)[\s\S]*?\n\n/);
assert.ok(leadMatch,'Production character-boundary macro missing');
const lead=leadMatch[0];
const skip=utf.match(/static inline char \*sjis_skip_char\([^)]*\)\s*\{[^}]*\}/)[0];
const source=String.raw`
typedef unsigned size_t;
typedef unsigned char uint8_t;
typedef _Bool bool;
#define true 1
#define false 0
#define NULL ((void*)0)
#define AIN_ARRAY_STRING 17
#define CHECK(c) do{if(!(c))return __LINE__;}while(0)
struct string {int size;char text[1024];};
union vm_value {int i;float f;};
struct page {int nr_vars;union vm_value values[128];};
struct string pool[512];int used;
struct page outputs[128];int pages_used;
bool sys4_text_is_sjis;
size_t strlen(const char *s){size_t n=0;while(s[n])n++;return n;}
void *memset(void *p,int c,size_t n){char *s=p;while(n--)*s++=c;return p;}
void *memcpy(void *p,const void *q,size_t n){char *d=p;const char *s=q;while(n--)*d++=*s++;return p;}
int memcmp(const void *a,const void *b,size_t n){const unsigned char *x=a,*y=b;while(n--){if(*x!=*y)return *x-*y;x++;y++;}return 0;}
int strcmp(const char *a,const char *b){while(*a&&*a==*b){a++;b++;}return (unsigned char)*a-(unsigned char)*b;}
`+lead+skip+String.raw`
struct string *make_string(const char *s,int n){if(used>=512||n<0||n>=1024)__builtin_trap();struct string *p=&pool[used++];p->size=n;for(int i=0;i<n;i++)p->text[i]=s[i];p->text[n]=0;return p;}
int heap_alloc_string(struct string *s){return s-pool;}
struct page *array_pushback(struct page *p,union vm_value value,int type,int structure){if(!p){if(pages_used>=128)__builtin_trap();p=&outputs[pages_used++];p->nr_vars=0;}if(p->nr_vars>=128)__builtin_trap();p->values[p->nr_vars++]=value;return p;}
`+split+String.raw`
void reset(void){used=pages_used=0;sys4_text_is_sjis=false;}
struct page *split_value(const char *s,const char *delimiters,bool keep){struct string *input=make_string(s,strlen(s)),*separators=make_string(delimiters,strlen(delimiters));return String_Split(&input,separators,keep);}
const char *at(struct page *p,int i){return pool[p->values[i].i].text;}
int count(struct page *p){return p?p->nr_vars:0;}
bool equal_tokens(struct page *p,const char *const expected[],int n){
 if(count(p)!=n)return false;
 for(int i=0;i<n;i++)if(strcmp(at(p,i),expected[i]))return false;
 return true;
}
#define MATCH(p,...) equal_tokens(p,(const char*[]){__VA_ARGS__},sizeof((const char*[]){__VA_ARGS__})/sizeof(const char*))
int run_tests(void){
 reset();
 /* Closed commands must have exactly the operation and its argument. */
 struct page *p=split_value("switch<variant>","<>",false);
 CHECK(MATCH(p,"switch","variant"));
 reset();p=split_value("switch\xa3\xbc" "variant\xa3\xbe","\xa3\xbc\xa3\xbe",false);
 CHECK(MATCH(p,"switch","variant"));
 reset();sys4_text_is_sjis=true;
 p=split_value("switch\x81\x83" "variant\x81\x84","\x81\x83\x81\x84",false);
 CHECK(MATCH(p,"switch","variant"));

 /* Empty fields are absent at either boundary and between separators. */
 reset();p=split_value("||alpha||||beta||","|",false);
 CHECK(MATCH(p,"alpha","beta"));
 reset();CHECK(count(split_value("",",",false))==0);
 reset();CHECK(count(split_value("",",",true))==0);
 reset();CHECK(count(split_value(",,,",",",false))==0);
 reset();p=split_value(",,,",",",true);CHECK(MATCH(p,",",",",","));
 reset();p=split_value("  literal  ","<>",false);CHECK(MATCH(p,"  literal  "));
 reset();p=split_value("a,b","",false);CHECK(MATCH(p,"a,b"));

 /* Nested condition parsing keeps the outer result alive and both arguments. */
 reset();struct page *outer=split_value("status<poison>||round:4","&|",false);
 CHECK(MATCH(outer,"status<poison>","round:4"));
 p=split_value(at(outer,0),"<>:",false);CHECK(MATCH(p,"status","poison"));
 p=split_value(at(outer,1),"<>:",false);CHECK(MATCH(p,"round","4"));
 CHECK(MATCH(outer,"status<poison>","round:4"));

 /* Retaining delimiters must not insert empty fields into ColorText tokens. */
 reset();p=split_value("{255,120,0}Attack{}: {0,255,80}100{} damage","{},",true);
 CHECK(MATCH(p,"{","255",",","120",",","0","}","Attack","{","}",": ","{","0",",","255",",","80","}","100","{","}"," damage"));

 /* Delimiter bytes inside a character are not delimiter characters. */
 reset();p=split_value("pre\x81\x7b" "post{arg}","{}",false);
 CHECK(MATCH(p,"pre\x81\x7b" "post","arg"));
 reset();sys4_text_is_sjis=true;p=split_value("\xa6:done:",":",false);
 CHECK(MATCH(p,"\xa6","done"));
 return 0;
}
`;
fs.mkdirSync(build,{recursive:true});
fs.writeFileSync(path.join(build,'string-test.c'),source);
const compiler=process.env.RANCE10_TEST_CLANG||path.resolve(root,'../android-build/sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe');
const wasm=path.join(build,'string-tests.wasm');
const r=spawnSync(compiler,['--target=wasm32-unknown-unknown','-x','c','-std=c11','-O1','-fno-builtin','-nostdlib','-Werror','-Wl,--no-entry','-Wl,--export=run_tests','-o',wasm,'-'],{input:source,encoding:'utf8',env:{...process.env,TEMP:build,TMP:build}});
if(r.error)throw r.error;
assert.equal(r.status,0,r.stderr||r.stdout);
const {instance}=await WebAssembly.instantiate(fs.readFileSync(wasm));
const line=instance.exports.run_tests();
assert.equal(line,0,`C failure ${line}: ${source.split('\n')[line-1]}`);
console.log('PASS: production String.Split closed commands, empty and repeated separators, nested conditions, delimiter retention, whitespace, GBK and Shift-JIS boundaries.');
