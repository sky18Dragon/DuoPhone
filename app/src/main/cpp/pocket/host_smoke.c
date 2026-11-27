/* Real QuickJS + Rust/software raster test. NOT an Android/GLES/device test. */
#include "runtime_bridge.c"
#include <assert.h>
#include <stdio.h>

static const char *snapshot = "{\"version\":1,\"kind\":\"initialize\",\"role\":\"TOP\",\"connection\":\"READY\",\"signalBars\":3,\"sim\":\"READY\",\"call\":\"IDLE\",\"page\":\"PHONE\",\"screen\":\"PhoneDialing\",\"dialDisplay\":\"**78\",\"unreadCount\":2,\"draftLength\":0}";
static uint64_t checksum(const uint8_t *pixels, size_t length) {
  uint64_t hash=1469598103934665603ULL;
  for(size_t i=0;i<length;i++) { hash ^= pixels[i]; hash *= 1099511628211ULL; }
  return hash;
}
int main(int argc, char **argv) {
  assert(argc == 3);
  FILE *file=fopen(argv[1],"rb"); assert(file);
  fseek(file,0,SEEK_END); long size=ftell(file); rewind(file);
  char *bundle=calloc((size_t)size+1,1); assert(bundle);
  assert(fread(bundle,1,(size_t)size,file)==(size_t)size); fclose(file);
  const uint8_t pack[1]={0};
  for(int cycle=0;cycle<20;cycle++) {
    if (!pocket_runtime_boot(bundle,size,pack,0,360,640)) {
      fprintf(stderr,"boot: %s\n",pocket_runtime_error()); return 1;
    }
    assert(duo_install()); assert(duo_receive(snapshot,strlen(snapshot)));
    PocketRuntimeInput input={0}; assert(pocket_runtime_tick(&input));
    const uint8_t *pixels=pocket_runtime_render(); assert(pixels);
    assert(pocket_runtime_width()==360 && pocket_runtime_height()==640);
    if(cycle==0) {
      FILE *out=fopen(argv[2],"wb"); assert(out);
      fprintf(out,"P6\n%u %u\n255\n",pocket_runtime_width(),pocket_runtime_height());
      for(unsigned y=0;y<pocket_runtime_height();y++) for(unsigned x=0;x<pocket_runtime_width();x++) {
        const uint8_t *p=pixels+y*pocket_runtime_stride()+x*4;
        const uint8_t rgb[3]={p[2],p[1],p[0]}; fwrite(rgb,1,3,out);
      }
      fclose(out);
    }
    uint64_t before=checksum(pixels,pocket_runtime_length());
    char updated[1024]; snprintf(updated,sizeof updated,"%s",snapshot);
    char *digits=strstr(updated,"**78"); assert(digits); digits[2]='4'; digits[3]='2';
    assert(duo_receive(updated,strlen(updated)));
    assert(pocket_runtime_tick(&input));
    pixels=pocket_runtime_render(); assert(pixels);
    assert(checksum(pixels,pocket_runtime_length()) != before);
    /* Actual core hit test -> actual QuickJS frame -> native intent queue. */
    input.touch_down=1; input.touch_x=84; input.touch_y=226;
    input.touch_hit=pocket_runtime_hit_test_bounds(84,226); assert(input.touch_hit>1);
    assert(pocket_runtime_tick(&input));
    input.touch_down=0; assert(pocket_runtime_tick(&input));
    char *intent=duo_pop(); assert(intent && strstr(intent,"\"digit\":\"1\"")); free(intent);
    assert(!duo_pop());
    /* Confirm edge, safe call request is data, no business capability linked. */
    input.buttons=POCKET_BTN_CROSS; assert(pocket_runtime_tick(&input));
    intent=duo_pop(); assert(intent && strstr(intent,"\"back\"")); free(intent);
    input.buttons=0; assert(pocket_runtime_tick(&input));
    PocketRuntimeContactsInput multi={0};
    multi.contact_count=2;
    multi.contacts[0]=(PocketRuntimeContact){7,84,226,pocket_runtime_hit_test_bounds(84,226)};
    multi.contacts[1]=(PocketRuntimeContact){8,180,226,pocket_runtime_hit_test_bounds(180,226)};
    assert(pocket_runtime_tick_contacts(&multi));
    multi.contact_count=1; multi.contacts[0]=multi.contacts[1];
    multi.cancelled_count=1; multi.cancelled[0]=7;
    assert(pocket_runtime_tick_contacts(&multi)); assert(!duo_pop());
    multi.contact_count=0; multi.cancelled_count=0;
    assert(pocket_runtime_tick_contacts(&multi));
    intent=duo_pop(); assert(intent && strstr(intent,"\"digit\":\"2\"")); free(intent);
    assert(!duo_pop());
    const char *event="{\"version\":1,\"kind\":\"event\",\"code\":\"SAFE_CALL_BLOCKED\"}";
    assert(duo_receive(event,strlen(event)));
    assert(pocket_runtime_tick(&input));
    /* Drag-off cancellation: press on digit '1' then drag outside before release. */
    multi.contact_count=1; multi.cancelled_count=0;
    multi.contacts[0]=(PocketRuntimeContact){9,84,226,pocket_runtime_hit_test_bounds(84,226)};
    assert(pocket_runtime_tick_contacts(&multi));
    multi.contacts[0]=(PocketRuntimeContact){9,350,10,multi.contacts[0].hit};
    assert(pocket_runtime_tick_contacts(&multi));
    multi.contact_count=0;
    assert(pocket_runtime_tick_contacts(&multi));
    assert(!duo_pop());
    /* Wide-word touch (y > 511) on SMS page hits EDIT SMS. */
    const char *sms_page="{\"version\":1,\"kind\":\"state\",\"role\":\"TOP\",\"connection\":\"READY\",\"signalBars\":3,\"sim\":\"READY\",\"call\":\"IDLE\",\"page\":\"SMS\",\"screen\":\"SmsChat\",\"dialDisplay\":\"**42\",\"unreadCount\":2,\"draftLength\":0}";
    assert(duo_receive(sms_page,strlen(sms_page)));
    assert(pocket_runtime_tick_contacts(&multi));
    multi.contact_count=1;
    multi.contacts[0]=(PocketRuntimeContact){10,104,538,pocket_runtime_hit_test_bounds(104,538)};
    assert(pocket_runtime_tick_contacts(&multi));
    multi.contact_count=0;
    assert(pocket_runtime_tick_contacts(&multi));
    intent=duo_pop(); assert(intent && strstr(intent,"\"editDraft\"")); free(intent);
    assert(!duo_pop());
    /* Active call state on PHONE flips CALL button to emit hangup. */
    const char *active_call="{\"version\":1,\"kind\":\"state\",\"role\":\"TOP\",\"connection\":\"READY\",\"signalBars\":3,\"sim\":\"READY\",\"call\":\"ACTIVE\",\"page\":\"PHONE\",\"screen\":\"InCall\",\"dialDisplay\":\"**42\",\"unreadCount\":2,\"draftLength\":0}";
    assert(duo_receive(active_call,strlen(active_call)));
    assert(pocket_runtime_tick_contacts(&multi));
    multi.contact_count=1;
    multi.contacts[0]=(PocketRuntimeContact){11,180,538,pocket_runtime_hit_test_bounds(180,538)};
    assert(pocket_runtime_tick_contacts(&multi));
    multi.contact_count=0;
    assert(pocket_runtime_tick_contacts(&multi));
    intent=duo_pop(); assert(intent && strstr(intent,"\"hangup\"")); free(intent);
    assert(!duo_pop());
    /* Non-ASCII intent string must be rejected before reaching JNI NewStringUTF. */
    JSValue bad=JS_Eval(context,"__duoDispatch('{\"type\":\"\\u00e9\"}')",strlen("__duoDispatch('{\"type\":\"\\u00e9\"}')"),"test.js",JS_EVAL_TYPE_GLOBAL);
    assert(JS_IsException(bad)); JS_FreeValue(context,JS_GetException(context));
    assert(!duo_pop());
    duo_clear(); pocket_runtime_shutdown();
    assert(runtime==NULL && context==NULL && duo_count==0);
  }
  free(bundle); puts("PASS: 20 real QuickJS/core boot-render-state-touch-intent-event-shutdown cycles"); return 0;
}
