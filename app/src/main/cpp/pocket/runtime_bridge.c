/* DuoPhone adapter, MIT. Included upstream source stays in the pinned build cache.
 * Including the TU gives this small adapter access to the real QuickJS context;
 * no guessed upstream exports or arbitrary JavaScript evaluation channel.
 * All calls are confined to ONE owner render thread. */
#include "pocket_runtime.c"
#include <stdlib.h>

#define DUO_QUEUE_CAPACITY 16
#define DUO_MESSAGE_LIMIT 4096
static char *duo_intents[DUO_QUEUE_CAPACITY];
static unsigned duo_head, duo_count;

static void duo_clear(void) {
  for (unsigned i = 0; i < DUO_QUEUE_CAPACITY; ++i) {
    free(duo_intents[i]); duo_intents[i] = NULL;
  }
  duo_head = duo_count = 0;
}

static JSValue duo_dispatch(JSContext *ctx, JSValueConst self, int argc, JSValueConst *argv) {
  (void)self;
  if (argc != 1 || !JS_IsString(argv[0])) return JS_ThrowTypeError(ctx, "intent must be JSON string");
  size_t length = 0;
  const char *bytes = JS_ToCStringLen(ctx, &length, argv[0]);
  if (!bytes) return JS_EXCEPTION;
  if (length > DUO_MESSAGE_LIMIT || duo_count == DUO_QUEUE_CAPACITY) {
    JS_FreeCString(ctx, bytes);
    return JS_ThrowRangeError(ctx, "intent queue/size limit");
  }
  for (size_t i = 0; i < length; ++i) {
    unsigned char ch = (unsigned char)bytes[i];
    if (ch < 0x20 || ch > 0x7E) {
      JS_FreeCString(ctx, bytes);
      return JS_ThrowRangeError(ctx, "intent must be printable ASCII");
    }
  }
  char *copy = malloc(length + 1);
  if (!copy) { JS_FreeCString(ctx, bytes); return JS_ThrowOutOfMemory(ctx); }
  memcpy(copy, bytes, length); copy[length] = 0;
  JS_FreeCString(ctx, bytes);
  duo_intents[(duo_head + duo_count++) % DUO_QUEUE_CAPACITY] = copy;
  return JS_UNDEFINED;
}

static int duo_install(void) {
  duo_clear();
  JS_SetMemoryLimit(runtime, 32 * 1024 * 1024);
  return JS_SetPropertyStr(context, global, "__duoDispatch",
      JS_NewCFunction(context, duo_dispatch, "__duoDispatch", 1)) >= 0;
}

/* Fixed entry point, JSON is data passed as a JS string, never source code. */
static int duo_receive(const char *json, size_t length) {
  if (!context || length > 16384) return 0;
  JSValue fn = JS_GetPropertyStr(context, global, "duoReceive");
  if (!JS_IsFunction(context, fn)) { JS_FreeValue(context, fn); return 0; }
  JSValue arg = JS_NewStringLen(context, json, length);
  JSValue result = JS_Call(context, fn, global, 1, &arg);
  JS_FreeValue(context, arg); JS_FreeValue(context, fn);
  if (JS_IsException(result)) { take_exception(context); return 0; }
  JS_FreeValue(context, result);
  return drain_jobs();
}

static char *duo_pop(void) {
  if (!duo_count) return NULL;
  char *value = duo_intents[duo_head]; duo_intents[duo_head] = NULL;
  duo_head = (duo_head + 1) % DUO_QUEUE_CAPACITY; --duo_count;
  return value;
}
