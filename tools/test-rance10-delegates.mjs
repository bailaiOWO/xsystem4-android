// Delegate entries hold weak (object, function, sequence) targets, except
// closure environments, which each entry owns. A stale entry (its object was
// freed while still registered) must not release whatever reused the slot:
// Rance 10's base camp quest choice was lost when a per-frame delegate's
// stale entry kept releasing the closure that RunIScene's postEvent needed,
// so GetResult never ran and the quest was skipped. Runs the real heap.c
// allocator (LIFO slot reuse, sequence numbers), cached page allocation, and
// resume.c JSON save/load with the production cJSON implementation. JSON trees
// stay in memory; the heap is actually destroyed and restored before dispatch.
import assert from 'node:assert/strict';
import fs from 'node:fs';import path from 'node:path';import {spawnSync} from 'node:child_process';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),build=path.join(root,'build/rance10-tests');
fs.mkdirSync(build,{recursive:true});
const read=p=>fs.readFileSync(path.join(root,'xsystem4',p),'utf8').replace(/\r\n/g,'\n');
function fn(src,name){
  const m=new RegExp(`^(?:static )?[^\\n;{}]*\\b${name}\\([^;{}]*\\)\\s*\\{`,'m').exec(src);assert.ok(m,name);
  let end=src.indexOf('{',m.index),d=0;do{d+=(src[end]==='{')-(src[end]==='}');end++;}while(d);return src.slice(m.index,end);
}
const heapc=read('src/heap.c'),pagec=read('src/page.c'),vmc=read('src/vm.c'),ainh=read('subprojects/libsys4/include/system4/ain.h');
const resumec=read('src/resume.c'),jsonc=read('src/cJSON.c'),jsonh=read('include/cJSON.h');
function macro(name){
  const lines=ainh.split('\n'),start=lines.findIndex(l=>l.startsWith(`#define ${name}\t`)||l.startsWith(`#define ${name} `));
  assert.ok(start>=0,name);let end=start;while(lines[end].endsWith('\\'))end++;return lines.slice(start,end+1).join('\n');
}
const source=String.raw`
typedef int int32_t;typedef unsigned int uint32_t;typedef unsigned long size_t;typedef _Bool bool;
#define true 1
#define false 0
#define NULL ((void*)0)
#define unlikely(x) (x)
#define assert(x) do{if(!(x))__builtin_trap();}while(0)
__attribute__((noreturn,import_module("env"),import_name("vm_error"))) void test_vm_error(int line);
#define VM_ERROR(...) test_vm_error(__LINE__)
#define WARNING(...) ((void)0)
#define NOTICE(...) ((void)0)
#define INITIAL_HEAP_SIZE 8
#define HEAP_ALLOC_STEP 8
${ainh.match(/enum ain_data_type\s*\{[\s\S]*?\};/)[0]}
${macro('AIN_ARRAY_TYPE')}
${macro('AIN_REF_TYPE')}
union vm_value{int32_t i;float f;};
enum page_type{GLOBAL_PAGE,LOCAL_PAGE,STRUCT_PAGE,ARRAY_PAGE,DELEGATE_PAGE,CLOSURE_PAGE};
enum vm_pointer_type{VM_PAGE,VM_STRING};
struct page{enum page_type type;union{int index;enum ain_data_type a_type;};
 union{struct{int struct_ptr;int parent_page;uint32_t struct_seq;uint32_t parent_seq;}local;struct{int struct_type;int rank;}array;struct{int live_slot;uint32_t live_seq;}delegate;};
 int nr_vars;union vm_value values[];};
struct string{char *text;};
struct vm_pointer{int ref;uint32_t seq;enum vm_pointer_type type;union{struct string *s;struct page *page;};};
struct vm_pointer *heap;size_t heap_size;uint32_t heap_next_seq;int32_t *heap_free_stack;size_t heap_free_ptr;
static unsigned char arena[1<<21];static size_t arena_top;
void *memset(void *d,int c,size_t n){unsigned char *a=d;for(size_t i=0;i<n;i++)a[i]=c;return d;}
void *memcpy(void *d,const void *s,size_t n){unsigned char *a=d;const unsigned char *b=s;for(size_t i=0;i<n;i++)a[i]=b[i];return d;}
static void *xmalloc(size_t n){unsigned char *p=arena+arena_top;arena_top+=16+((n+15)&~15u);if(arena_top>sizeof(arena))__builtin_trap();*(size_t*)p=n;return p+16;}
static void *xcalloc(size_t a,size_t b){return memset(xmalloc(a*b),0,a*b);}
static void *xrealloc(void *o,size_t n){void *p=xmalloc(n);if(o){size_t m=*(size_t*)((unsigned char*)o-16);memcpy(p,o,m<n?m:n);}return p;}
static void free(void *p){(void)p;}
static size_t strlen(const char *s){size_t n=0;while(s[n])n++;return n;}
static int strcmp(const char *a,const char *b){while(*a&&*a==*b){a++;b++;}return (unsigned char)*a-(unsigned char)*b;}
static int tolower(int c){return c>='A'&&c<='Z'?c+'a'-'A':c;}
/* Unused text-format libc calls are discarded by the linker. The JSON tree
   API below is the entire production implementation, not a JSON test double. */
int sprintf(char *,const char *,...);int sscanf(const char *,const char *,...);
double strtod(const char *,char **);char *strcpy(char *,const char *);int strncmp(const char *,const char *,size_t);
#define INT_MAX 2147483647
#define INT_MIN (-INT_MAX-1)
#define malloc xmalloc
#define realloc xrealloc
${jsonh.replace(/^#include[^\n]*$/gm,'')}
${jsonc.replace(/^#include[^\n]*$/gm,'')}
#undef malloc
#undef realloc
struct ain_type{int data,struc,rank;};
struct ain_variable{struct ain_type type;};
struct ain_function{struct ain_variable *vars;};
struct ain_struct{struct ain_variable *members;int destructor;};
static struct ain_variable one_int[1]={{{AIN_INT,-1,0}}};
static struct ain_function functions[2]={{0},{one_int}};
static struct ain_struct structures[1]={{one_int,0}};
static struct{struct ain_variable *globals;struct ain_function *functions;struct ain_struct *structures;int nr_delegates;}the_ain={one_int,functions,structures,1};
#define ain (&the_ain)
#define NR_CACHES 8
#define CACHE_SIZE 64
${pagec.match(/struct page_cache\s*\{[\s\S]*?\};/)[0]}
struct page_cache page_cache[NR_CACHES];
${['_alloc_page','alloc_page','free_page'].map(n=>fn(pagec,n)).join('\n')}
void delete_struct(int no,int slot){(void)no;(void)slot;}
void free_string(struct string *s){(void)s;__builtin_trap();}
void exit_unref(int slot){(void)slot;__builtin_trap();}
int vm_string_ref(struct string *s){(void)s;__builtin_trap();}
struct string *heap_get_string(int i){(void)i;__builtin_trap();}
int vm_copy_page(struct page *p){(void)p;__builtin_trap();}
void heap_unref(int slot);void heap_ref(int32_t slot);uint32_t heap_get_seq(int slot);bool heap_index_valid(int index);
bool page_index_valid(int index);struct page *heap_get_page(int index);void delete_page(int slot);void heap_grow(size_t new_size);
enum ain_data_type variable_type(struct page *page,int varno,int *struct_type,int *array_rank);
enum ain_data_type array_type(enum ain_data_type type);
${['heap_grow','heap_init','heap_alloc_slot','heap_free_slot','heap_double_free','heap_ref','heap_unref','heap_get_seq','heap_index_valid','page_index_valid','heap_get_page','heap_get_delegate_page','heap_set_page','heap_alloc_page'].map(n=>fn(heapc,n)).join('\n')}
${fn(vmc,'vm_copy')}
${['variable_fini','array_type','delegate_is_closure','variable_type','delete_page_vars','delete_page','copy_page',
   'delegate_new_from_method','delegate_contains','delegate_append','delegate_numof','delegate_entry_current','delegate_entry_live',
   'delegate_remove_entry','delegate_erase','delegate_prune','delegate_clear','delegate_get'].map(n=>fn(pagec,n)).join('\n')}
/* Real resume serialization/deserialization, with only file transport and
   unrelated bytecode/config migration services replaced at their boundaries. */
#define possibly_unused
#define _invalid_save_data(...) VM_ERROR()
#define invalid_save_data(...) VM_ERROR()
#define type_check(type,json) _type_check(__FILE__,__func__,__LINE__,type,json)
${resumec.match(/static const char \* const page_type_strtab\[\]\s*=\s*\{[\s\S]*?\};/)[0]}
static void savedata_repair_legacy_config(struct page *p){(void)p;}
static void repair_legacy_array_types(void){}
static struct string *make_string(const char *s,size_t n){(void)s;(void)n;__builtin_trap();}
struct function_call{int fno;uint32_t call_address,return_address;int page_slot,struct_page;};
static struct function_call call_stack[8];static int call_stack_ptr;
static union vm_value stack[32];static int stack_ptr;static size_t instr_ptr;
static union vm_value vm_int(int i){return (union vm_value){.i=i};}
static void stack_push_value(union vm_value v){stack[stack_ptr++]=v;}
static union vm_value stack_pop(void){return stack[--stack_ptr];}
static int resume_instruction_width(size_t address){(void)address;return 6;}
static cJSON *saved_image;
static cJSON *read_json_image(const char *key,const char *path){(void)key;(void)path;return saved_image;}
${['string_to_page_type','get_number','value_to_json','resume_page_to_json','heap_item_to_json','heap_to_json',
   'funcall_to_json','call_stack_to_json','stack_to_json','vm_image_to_json','json_strtype','_type_check',
   'load_json_page','load_json_string','delete_heap','alloc_heap_slot','load_json_heap',
   'load_json_call_stack','load_json_stack','load_json_image'].map(n=>fn(resumec,n)).join('\n')}
static void save_and_restore(void){
 stack_push_value(vm_int(0));stack_push_value(vm_int(0)); /* ResumeSave arguments */
 saved_image=vm_image_to_json("delegate-test");load_json_image("delegate-test","memory");saved_image=NULL;
}
#define CHECK(c) do{if(!(c))return __LINE__;}while(0)
static int new_object(void){int s=heap_alloc_slot(VM_PAGE);heap_set_page(s,alloc_page(STRUCT_PAGE,0,1));return s;}
/* DG_NEW_FROM_METHOD for a lambda with captures: the entry owns the environment. */
static int new_closure_delegate(int this_obj,int fun,int *env_out){
 struct page *e=alloc_page(CLOSURE_PAGE,1,1);e->local.struct_ptr=this_obj;e->local.struct_seq=heap_get_seq(this_obj);e->local.parent_page=-1;
 int env=heap_alloc_page(e);int dg=heap_alloc_page(delegate_new_from_method(env,fun));heap_unref(env);*env_out=env;return dg;}
/* One v12 DG_CALLBEGIN .. DG_CALL loop; returns how many entries were called. */
static int call_all(int dg,int *obj,int *fun){
 struct page *live=heap_get_delegate_page(dg);delegate_prune(live);
 struct page *snapshot=copy_page(live);if(!snapshot)snapshot=alloc_page(DELEGATE_PAGE,0,0);
 snapshot->index=-1;snapshot->delegate.live_slot=dg;snapshot->delegate.live_seq=heap_get_seq(dg);
 int inv=heap_alloc_page(snapshot),n=0;
 while(delegate_get(heap_get_delegate_page(inv),n,obj,fun))n++;
 heap_unref(inv);return n;}
static int test_resume_snapshot(uint32_t first_seq){
 delete_heap();heap_init();heap_next_seq=first_seq;
 heap[0].ref=1;heap[0].type=VM_PAGE;heap[0].page=alloc_page(GLOBAL_PAGE,0,1);
 int o,f,a=new_object(),b=new_object(),c=new_object();
 int live=heap_alloc_page(delegate_new_from_method(a,41));
 heap_set_page(live,delegate_append(heap_get_delegate_page(live),b,42));
 heap_set_page(live,delegate_append(heap_get_delegate_page(live),c,43));
 struct page *snapshot=copy_page(heap_get_delegate_page(live));
 CHECK(snapshot->delegate.live_slot==-1&&snapshot->delegate.live_seq==0);
 snapshot->index=-1;snapshot->delegate.live_slot=live;snapshot->delegate.live_seq=heap_get_seq(live);
 struct page *copied=copy_page(snapshot);
 CHECK(copied->index==-1&&copied->delegate.live_slot==live&&copied->delegate.live_seq==heap_get_seq(live));
 free_page(copied);
 int invocation=heap_alloc_page(snapshot);
 CHECK(delegate_get(snapshot,0,&o,&f)&&o==a&&f==41);
 delegate_erase(heap_get_delegate_page(live),b,42); /* cancelled before saving */
 uint32_t live_seq=heap_get_seq(live),target_seq=heap_get_seq(c),next_seq=heap_next_seq;
 stack_push_value(vm_int(invocation));stack_push_value(vm_int(1)); /* next callback */
 save_and_restore();
 CHECK(heap_get_seq(live)==live_seq&&heap_get_seq(c)==target_seq&&heap_next_seq==next_seq);
 CHECK(stack_ptr==2&&stack[0].i==invocation&&stack[1].i==1);
 snapshot=heap_get_delegate_page(invocation);
 CHECK(snapshot->index==-1&&snapshot->delegate.live_slot==live&&snapshot->delegate.live_seq==live_seq);
 delegate_erase(heap_get_delegate_page(live),a,41); /* self-erase after restoring */
 CHECK(delegate_get(snapshot,stack[1].i,&o,&f)&&o==c&&f==43);
 CHECK(!delegate_get(snapshot,2,&o,&f));
 heap_unref(invocation);stack_ptr=0;
 /* A cancelled callback must also stay cancelled when it is unregistered
    after restoring, not just when the serialized live list lacked it. */
 snapshot=copy_page(heap_get_delegate_page(live));snapshot->index=-1;
 snapshot->delegate.live_slot=live;snapshot->delegate.live_seq=live_seq;
 invocation=heap_alloc_page(snapshot);save_and_restore();
 delegate_erase(heap_get_delegate_page(live),c,43);
 CHECK(!delegate_get(heap_get_delegate_page(invocation),0,&o,&f));
 heap_unref(invocation);
 /* Arrays share the metadata union with delegates. Reuse that exact cache
    entry for a normal delegate, a snapshot copy, then an old JSON snapshot. */
 struct page *poison=alloc_page(ARRAY_PAGE,AIN_INT,3);
 poison->array.struct_type=live;poison->array.rank=(int)live_seq;free_page(poison);
 struct page *ordinary=delegate_new_from_method(c,43);
 CHECK(ordinary==poison&&ordinary->delegate.live_slot==-1&&ordinary->delegate.live_seq==0);
 copied=copy_page(ordinary);
 CHECK(copied->index==0&&copied->delegate.live_slot==-1&&copied->delegate.live_seq==0);
 free_page(copied);
 cJSON *normal_json=resume_page_to_json(ordinary);
 CHECK(!cJSON_GetObjectItem(normal_json,"live-slot")&&!cJSON_GetObjectItem(normal_json,"live-seq"));
 ordinary->index=-1;ordinary->delegate.live_slot=live;ordinary->delegate.live_seq=live_seq;
 cJSON *old_json=resume_page_to_json(ordinary);
 cJSON_DeleteItemFromObject(old_json,"live-slot");cJSON_DeleteItemFromObject(old_json,"live-seq");
 free_page(ordinary);
 poison=alloc_page(ARRAY_PAGE,AIN_INT,3);CHECK(poison==ordinary);
 poison->array.struct_type=live;poison->array.rank=(int)live_seq;free_page(poison);
 invocation=heap_alloc_slot(VM_PAGE);load_json_page(invocation,old_json);
 snapshot=heap_get_delegate_page(invocation);
 CHECK(snapshot==poison&&snapshot->index==-1&&snapshot->delegate.live_slot==-1&&snapshot->delegate.live_seq==0);
 /* The source is empty, but old snapshots have no reliable source link:
    their still-live callback must run rather than using array cache debris. */
 CHECK(delegate_get(snapshot,0,&o,&f)&&o==c&&f==43);
 heap_unref(invocation);cJSON_Delete(old_json);
 poison=alloc_page(ARRAY_PAGE,AIN_INT,3);
 poison->array.struct_type=live;poison->array.rank=(int)live_seq;free_page(poison);
 invocation=heap_alloc_slot(VM_PAGE);load_json_page(invocation,normal_json);
 ordinary=heap_get_delegate_page(invocation);
 CHECK(ordinary==poison&&ordinary->index==0&&ordinary->delegate.live_slot==-1&&ordinary->delegate.live_seq==0);
 CHECK(delegate_get(ordinary,0,&o,&f)&&o==c&&f==43);
 heap_unref(invocation);cJSON_Delete(normal_json);
 return 0;
}
int run_tests(void){
 heap_init();int o,f;
 /* Base camp: a UI object still registered in a per-frame delegate is freed;
    its slot is reused (LIFO) by the closure RunQuestSelectBaseCamp hands to
    RunIScene as postEvent. Frames keep calling the per-frame delegate. */
 int ui=new_object();int frame=heap_alloc_page(delegate_new_from_method(ui,5));
 heap_unref(ui);
 int env;int post=new_closure_delegate(-1,7,&env);
 CHECK(env==ui);uint32_t env_seq=heap_get_seq(env);
 for(int i=0;i<3;i++)CHECK(call_all(frame,&o,&f)==0);
 CHECK(heap[env].ref==1&&heap_get_seq(env)==env_seq);
 CHECK(heap_get_delegate_page(frame)->nr_vars==0);
 CHECK(call_all(post,&o,&f)==1&&o==env&&f==7);
 /* The scene finishes: releasing postEvent frees its environment once. */
 heap_unref(post);CHECK(heap_get_seq(env)==0);
 /* An entry can also go stale during a call: the first callback frees X and
    the next lambda's environment reuses X's slot before the snapshot gets to
    X. The snapshot must skip X without releasing that environment. */
 int first=new_object(),xo=new_object();
 int ev=heap_alloc_page(delegate_new_from_method(first,5));
 heap_set_page(ev,delegate_append(heap_get_delegate_page(ev),xo,6));
 struct page *evlive=heap_get_delegate_page(ev);delegate_prune(evlive);
 struct page *evsnap=copy_page(evlive);evsnap->index=-1;int inv=heap_alloc_page(evsnap);
 CHECK(delegate_get(heap_get_delegate_page(inv),0,&o,&f)&&o==first&&f==5);
 heap_unref(xo);int env7;int dg7=new_closure_delegate(-1,12,&env7);CHECK(env7==xo);
 CHECK(!delegate_get(heap_get_delegate_page(inv),1,&o,&f));
 heap_unref(inv);
 CHECK(heap[env7].ref==1&&call_all(dg7,&o,&f)==1&&o==env7&&f==12);
 /* A closure whose object died is not called and its owned reference goes. */
 int owner=new_object();int env2;int dg2=new_closure_delegate(owner,9,&env2);
 CHECK(call_all(dg2,&o,&f)==1&&heap[env2].ref==1);
 heap_unref(owner);
 CHECK(call_all(dg2,&o,&f)==0&&heap_get_seq(env2)==0&&heap_get_delegate_page(dg2)->nr_vars==0);
 heap_unref(dg2);
 /* Snapshot copies take and return their own closure references. */
 int env3;int dg3=new_closure_delegate(-1,11,&env3);
 for(int i=0;i<4;i++)CHECK(call_all(dg3,&o,&f)==1&&o==env3);
 CHECK(heap[env3].ref==1);heap_unref(dg3);CHECK(heap_get_seq(env3)==0);
 /* Unregistering: A leaves a stale entry, B reuses A's slot and registers
    the same method. Erasing B must remove B's entry, not A's stale one. */
 int a=new_object();int g=heap_alloc_page(delegate_new_from_method(a,5));heap_unref(a);
 int b=new_object();CHECK(b==a);
 heap_set_page(g,delegate_append(heap_get_delegate_page(g),b,5));CHECK(delegate_contains(heap_get_delegate_page(g),b,5));
 delegate_erase(heap_get_delegate_page(g),b,5);
 CHECK(!delegate_contains(heap_get_delegate_page(g),b,5)&&call_all(g,&o,&f)==0);
 /* Erasing a closure entry releases the environment it owns. */
 int env4;int dg4=new_closure_delegate(-1,13,&env4);
 delegate_erase(heap_get_delegate_page(dg4),env4,13);CHECK(heap_get_seq(env4)==0&&heap_get_delegate_page(dg4)->nr_vars==0);
 /* Pruning keeps the live entries (plain functions, live objects) in order. */
 int c=new_object(),dead=new_object();
 int h=heap_alloc_page(delegate_new_from_method(-1,3));
 heap_set_page(h,delegate_append(heap_get_delegate_page(h),dead,4));
 heap_set_page(h,delegate_append(heap_get_delegate_page(h),c,6));
 heap_unref(dead);
 int env5;int reuse=new_closure_delegate(-1,8,&env5);CHECK(env5==dead);
 delegate_prune(heap_get_delegate_page(h));
 struct page *hp=heap_get_delegate_page(h);
 CHECK(hp->nr_vars==6&&hp->values[0].i==-1&&hp->values[1].i==3&&hp->values[3].i==c&&hp->values[4].i==6);
 CHECK(heap[env5].ref==1&&call_all(reuse,&o,&f)==1&&o==env5);
 /* Clearing a delegate with a stale entry leaves the slot's new owner alone. */
 int x=new_object();int k=heap_alloc_page(delegate_new_from_method(x,5));heap_unref(x);
 int env6;int dg6=new_closure_delegate(-1,10,&env6);CHECK(env6==x);
 heap_set_page(k,delegate_clear(heap_get_delegate_page(k)));CHECK(heap[env6].ref==1);
 heap_unref(k);CHECK(heap[env6].ref==1&&call_all(dg6,&o,&f)==1);
 /* Mutation during dispatch: self-erasing must not skip the next live entry,
    while cancelling that entry prevents it from firing and preserves #2. */
 int m0=new_object(),m1=new_object(),m2=new_object();
 int mdg=heap_alloc_page(delegate_new_from_method(m0,21));
 heap_set_page(mdg,delegate_append(heap_get_delegate_page(mdg),m1,22));
 heap_set_page(mdg,delegate_append(heap_get_delegate_page(mdg),m2,23));
 struct page *msnap=copy_page(heap_get_delegate_page(mdg));
 msnap->index=-1;msnap->delegate.live_slot=mdg;msnap->delegate.live_seq=heap_get_seq(mdg);
 int minv=heap_alloc_page(msnap);
 CHECK(delegate_get(heap_get_delegate_page(minv),0,&o,&f)&&o==m0&&f==21);
 delegate_erase(heap_get_delegate_page(mdg),m0,21); /* self-erase */
 delegate_erase(heap_get_delegate_page(mdg),m1,22); /* cancel sibling callback */
 CHECK(delegate_get(heap_get_delegate_page(minv),1,&o,&f)&&o==m2&&f==23);
 CHECK(!delegate_get(heap_get_delegate_page(minv),2,&o,&f));
 heap_unref(minv);
 /* Delegate.Clear mid-dispatch stops all remaining callbacks and unrefs closures. */
 int cenv;int cdg=new_closure_delegate(-1,31,&cenv);
 heap_set_page(cdg,delegate_append(heap_get_delegate_page(cdg),m2,32));
 struct page *csnap=copy_page(heap_get_delegate_page(cdg));
 csnap->index=-1;csnap->delegate.live_slot=cdg;csnap->delegate.live_seq=heap_get_seq(cdg);
 int cinv=heap_alloc_page(csnap);
 heap_set_page(cdg,delegate_clear(heap_get_delegate_page(cdg)));
 CHECK(!delegate_get(heap_get_delegate_page(cinv),0,&o,&f));
 heap_unref(cinv);CHECK(heap_get_seq(cenv)==0);
 heap_unref(cdg);heap_unref(m0);heap_unref(m1);heap_unref(m2);
 int result=test_resume_snapshot(1);if(result)return result;
 result=test_resume_snapshot(0x80000005u);if(result)return result;
 result=test_resume_snapshot(0xfffffff0u);if(result)return result;
 return 0;}
`;
fs.writeFileSync(path.join(build,'delegates-test.c'),source);
const compiler=process.env.RANCE10_TEST_CLANG||path.resolve(root,'../android-build/sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'),wasm=path.join(build,'delegates-test.wasm');
const r=spawnSync(compiler,['--target=wasm32-unknown-unknown','-x','c','-std=gnu11','-O1','-fno-builtin','-nostdlib','-Werror','-Wno-unused-function','-Wl,--no-entry','-Wl,--export=run_tests','-o',wasm,'-'],{input:source,encoding:'utf8',env:{...process.env,TEMP:build,TMP:build}});
assert.equal(r.status,0,r.stderr||r.stdout);
let vmError=0;
const {instance}=await WebAssembly.instantiate(fs.readFileSync(wasm),{env:{vm_error(line){vmError=line;throw new Error(`VM_ERROR at generated line ${line}`);}}});
const line=instance.exports.run_tests();assert.equal(line,0,`C failure ${line}: ${source.split('\n')[line-1]}`);
console.log('PASS: delegate ownership, stale-slot pruning and mutation during dispatch; production JSON heap save/restore retains snapshot links and uint32 generations, resumes remaining callbacks, skips cancellations, and safely loads old snapshots after array-cache reuse.');
