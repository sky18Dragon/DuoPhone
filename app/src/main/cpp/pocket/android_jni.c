#include "runtime_bridge.c"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "contact_latch.h"

#define POCKET_LOGICAL_W 360
#define POCKET_LOGICAL_H 640
#define POCKET_FBO_W 720
#define POCKET_FBO_H 1280
#define POCKET_TWO_PI 6.28318530717958647692f
#define POCKET_DEG_TO_RAD 0.01745329251994329577f

static atomic_long owner = 0;
static long generation = 0;
static pthread_t render_thread;
static PocketContactLatch contacts;
static uint32_t keys, key_pulses;
static int surface_w = POCKET_LOGICAL_W, surface_h = POCKET_LOGICAL_H;
static int gl_ready = 0;
static const uint8_t empty_pack[1] = {0};

/* Scheme 1 GLES2 FBO + perspective quad compositor state (render thread only). */
static int compositor_ready = 0;
static GLuint fbo = 0;
static GLuint fbo_tex = 0;
static GLuint backdrop_tex = 0;
static int backdrop_w = 0, backdrop_h = 0;
static GLuint bg_prog = 0;
static GLint bg_a_pos = -1, bg_a_uv = -1, bg_u_tex = -1;
static GLuint quad_prog = 0;
static GLint quad_a_pos = -1, quad_u_stage = -1, quad_u_tex = -1;
static GLint quad_u_inv_h = -1, quad_u_radius = -1, quad_u_bright = -1;

static int quad_valid = 0;
static float quad_base_pts[8] = {0};
static float quad_anim_pts[8] = {0};
static float quad_anim_inv_h[9] = {0};
static float quad_corner_radius = 27.0f;
static float quad_brightness = 0.96f;
static float quad_backdrop_scale = 1.03f;

/* Handheld breathing & tactile recoil motion state (render thread). */
static int motion_breathing_enabled = 1;
static float motion_amp_px = 12.0f;
static float motion_amp_dp = 4.0f;
static float motion_recoil_drop_px = 5.4f;
static double motion_start_sec = 0.0;
static double recoil_start_sec = -10.0;

#define JNI(name) Java_com_duophone_app_pocket_PocketNative_##name
#define LOG(message) __android_log_print(ANDROID_LOG_INFO, "PocketHost", "%s", message)

static double monotonic_sec(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

static int valid(jlong handle) {
  return handle > 0 && atomic_load(&owner) == handle && pthread_equal(pthread_self(), render_thread);
}

static GLuint compile_shader(GLenum type, const char *src) {
  GLuint shader = glCreateShader(type);
  if (!shader) return 0;
  glShaderSource(shader, 1, &src, NULL);
  glCompileShader(shader);
  GLint ok = 0;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

static GLuint link_program(const char *vs_src, const char *fs_src) {
  GLuint vs = compile_shader(GL_VERTEX_SHADER, vs_src);
  if (!vs) return 0;
  GLuint fs = compile_shader(GL_FRAGMENT_SHADER, fs_src);
  if (!fs) {
    glDeleteShader(vs);
    return 0;
  }
  GLuint prog = glCreateProgram();
  if (!prog) {
    glDeleteShader(vs);
    glDeleteShader(fs);
    return 0;
  }
  glAttachShader(prog, vs);
  glAttachShader(prog, fs);
  glLinkProgram(prog);
  glDeleteShader(vs);
  glDeleteShader(fs);
  GLint ok = 0;
  glGetProgramiv(prog, GL_LINK_STATUS, &ok);
  if (!ok) {
    glDeleteProgram(prog);
    return 0;
  }
  return prog;
}

static void compositor_shutdown(void) {
  if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
    if (fbo) glDeleteFramebuffers(1, &fbo);
    if (fbo_tex) glDeleteTextures(1, &fbo_tex);
    if (backdrop_tex) glDeleteTextures(1, &backdrop_tex);
    if (bg_prog) glDeleteProgram(bg_prog);
    if (quad_prog) glDeleteProgram(quad_prog);
  }
  fbo = 0;
  fbo_tex = 0;
  backdrop_tex = 0;
  backdrop_w = 0;
  backdrop_h = 0;
  bg_prog = 0;
  quad_prog = 0;
  bg_a_pos = bg_a_uv = bg_u_tex = -1;
  quad_a_pos = quad_u_stage = quad_u_tex = quad_u_inv_h = quad_u_radius = quad_u_bright = -1;
  compositor_ready = 0;
  quad_valid = 0;
}

static int compositor_init(void) {
  static const char *bg_vs =
      "attribute vec2 aPos;\n"
      "attribute vec2 aUv;\n"
      "varying vec2 vUv;\n"
      "void main() {\n"
      "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
      "  vUv = aUv;\n"
      "}\n";
  static const char *bg_fs =
      "precision mediump float;\n"
      "uniform sampler2D uTex;\n"
      "varying vec2 vUv;\n"
      "void main() {\n"
      "  gl_FragColor = texture2D(uTex, vUv);\n"
      "}\n";
  static const char *quad_vs =
      "attribute vec2 aPos;\n"
      "uniform vec2 uStageSize;\n"
      "varying vec2 vScreenPos;\n"
      "void main() {\n"
      "  float nx = (aPos.x / uStageSize.x) * 2.0 - 1.0;\n"
      "  float ny = 1.0 - (aPos.y / uStageSize.y) * 2.0;\n"
      "  gl_Position = vec4(nx, ny, 0.0, 1.0);\n"
      "  vScreenPos = aPos;\n"
      "}\n";
  static const char *quad_fs =
      "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
      "precision highp float;\n"
      "#else\n"
      "precision mediump float;\n"
      "#endif\n"
      "uniform sampler2D uTex;\n"
      "uniform mat3 uInvH;\n"
      "uniform float uCornerRadius;\n"
      "uniform float uBrightness;\n"
      "varying vec2 vScreenPos;\n"
      "void main() {\n"
      "  vec3 q = uInvH * vec3(vScreenPos, 1.0);\n"
      "  if (abs(q.z) < 0.0001) discard;\n"
      "  vec2 uv = q.xy / q.z;\n"
      "  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) discard;\n"
      "  vec2 p = vec2(uv.x * 360.0, uv.y * 640.0);\n"
      "  vec2 halfSize = vec2(180.0, 320.0);\n"
      "  vec2 d = abs(p - halfSize) - (halfSize - vec2(uCornerRadius));\n"
      "  float sdf = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - uCornerRadius;\n"
      "  float alpha = 1.0 - smoothstep(-1.2, 1.2, sdf);\n"
      "  if (alpha <= 0.01) discard;\n"
      "  vec4 col = texture2D(uTex, vec2(uv.x, 1.0 - uv.y));\n"
      "  gl_FragColor = vec4(col.rgb * uBrightness, col.a * alpha);\n"
      "}\n";

  compositor_shutdown();

  bg_prog = link_program(bg_vs, bg_fs);
  quad_prog = link_program(quad_vs, quad_fs);
  if (!bg_prog || !quad_prog) {
    compositor_shutdown();
    return 0;
  }
  bg_a_pos = glGetAttribLocation(bg_prog, "aPos");
  bg_a_uv = glGetAttribLocation(bg_prog, "aUv");
  bg_u_tex = glGetUniformLocation(bg_prog, "uTex");

  quad_a_pos = glGetAttribLocation(quad_prog, "aPos");
  quad_u_stage = glGetUniformLocation(quad_prog, "uStageSize");
  quad_u_tex = glGetUniformLocation(quad_prog, "uTex");
  quad_u_inv_h = glGetUniformLocation(quad_prog, "uInvH");
  quad_u_radius = glGetUniformLocation(quad_prog, "uCornerRadius");
  quad_u_bright = glGetUniformLocation(quad_prog, "uBrightness");
  if (bg_a_pos < 0 || bg_a_uv < 0 || bg_u_tex < 0 || quad_a_pos < 0 || quad_u_stage < 0 ||
      quad_u_tex < 0 || quad_u_inv_h < 0 || quad_u_radius < 0 || quad_u_bright < 0) {
    compositor_shutdown();
    return 0;
  }

  glGenTextures(1, &fbo_tex);
  if (!fbo_tex) {
    compositor_shutdown();
    return 0;
  }
  glBindTexture(GL_TEXTURE_2D, fbo_tex);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, POCKET_FBO_W, POCKET_FBO_H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);

  glGenFramebuffers(1, &fbo);
  if (!fbo) {
    compositor_shutdown();
    return 0;
  }
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fbo_tex, 0);
  GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  glBindTexture(GL_TEXTURE_2D, 0);
  if (status != GL_FRAMEBUFFER_COMPLETE) {
    compositor_shutdown();
    return 0;
  }
  compositor_ready = 1;
  return 1;
}

static int compute_homography_inv(const float pts[8], float out_inv[9]) {
  float x0 = pts[0], y0 = pts[1];
  float x1 = pts[2], y1 = pts[3];
  float x2 = pts[4], y2 = pts[5];
  float x3 = pts[6], y3 = pts[7];
  float dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
  float dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
  float det = dx1 * dy2 - dx2 * dy1;
  if (fabsf(det) < 1e-4f) return 0;
  float g = (dx3 * dy2 - dx2 * dy3) / det;
  float h = (dx1 * dy3 - dx3 * dy1) / det;
  float a = x1 - x0 + g * x1;
  float b = x3 - x0 + h * x3;
  float c = x0;
  float d = y1 - y0 + g * y1;
  float e = y3 - y0 + h * y3;
  float f = y0;

  float A = e - f * h;
  float B = c * h - b;
  float C = b * f - c * e;
  float D = f * g - d;
  float E = a - c * g;
  float F = c * d - a * f;
  float G = d * h - e * g;
  float Hc = b * g - a * h;
  float I = a * e - b * d;
  float det_h = a * A + b * D + c * G;
  if (fabsf(det_h) < 1e-4f) return 0;
  float inv_det = 1.0f / det_h;
  out_inv[0] = A * inv_det;
  out_inv[1] = B * inv_det;
  out_inv[2] = C * inv_det;
  out_inv[3] = D * inv_det;
  out_inv[4] = E * inv_det;
  out_inv[5] = F * inv_det;
  out_inv[6] = G * inv_det;
  out_inv[7] = Hc * inv_det;
  out_inv[8] = I * inv_det;
  return 1;
}

static void compute_motion(double now, float *out_tx, float *out_ty, float *out_rad, float *out_scale) {
  float bx = 0.0f, by = 0.0f, brot_deg = 0.0f;
  if (motion_breathing_enabled && motion_amp_px > 0.0f) {
    float phase = (float)fmod((now - motion_start_sec) / 4.8, 1.0);
    if (phase < 0.0f) phase += 1.0f;
    float angle = phase * POCKET_TWO_PI;
    bx = sinf(angle) * motion_amp_px * 0.50f;
    by = cosf(angle * 2.0f) * motion_amp_px * 0.85f;
    brot_deg = sinf(angle) * 0.35f * (motion_amp_dp / 5.0f) * 0.45f;
  }
  float recoil_y = 0.0f, r_scale = 1.0f;
  double r_elapsed = now - recoil_start_sec;
  if (r_elapsed >= 0.0 && r_elapsed < 0.22) {
    float env = 1.0f - (float)(r_elapsed / 0.22);
    env *= env;
    recoil_y = env * motion_recoil_drop_px;
    r_scale = 1.0f - 0.008f * env;
  }
  *out_tx = bx;
  *out_ty = by + recoil_y;
  *out_rad = brot_deg * POCKET_DEG_TO_RAD;
  *out_scale = r_scale;
}

static void draw_backdrop(float tx, float ty, float rad, float r_scale) {
  if (!backdrop_tex || backdrop_w <= 0 || backdrop_h <= 0) return;
  float safeW = surface_w > 0 ? (float)surface_w : 1.0f;
  float safeH = surface_h > 0 ? (float)surface_h : 1.0f;
  float imgAspect = (float)backdrop_w / (float)backdrop_h;
  float viewAspect = safeW / safeH;
  float cropW = (viewAspect >= imgAspect) ? safeW : (safeH * imgAspect);
  float cropH = (viewAspect >= imgAspect) ? (safeW / imgAspect) : safeH;
  float s = (quad_backdrop_scale > 0.1f ? quad_backdrop_scale : 1.03f) * r_scale;
  float hw = cropW * s * 0.5f;
  float hh = cropH * s * 0.5f;
  float cx = safeW * 0.5f;
  float cy = safeH * 0.5f;
  float cosA = cosf(rad), sinA = sinf(rad);

  const float offsets[4][4] = {
      {-hw, -hh, 0.0f, 0.0f},
      { hw, -hh, 1.0f, 0.0f},
      { hw,  hh, 1.0f, 1.0f},
      {-hw,  hh, 0.0f, 1.0f},
  };
  float ndc[4][4];
  for (int i = 0; i < 4; i++) {
    float dx = offsets[i][0], dy = offsets[i][1];
    float sx = cx + (dx * cosA - dy * sinA) + tx;
    float sy = cy + (dx * sinA + dy * cosA) + ty;
    ndc[i][0] = (sx / safeW) * 2.0f - 1.0f;
    ndc[i][1] = 1.0f - (sy / safeH) * 2.0f;
    ndc[i][2] = offsets[i][2];
    ndc[i][3] = offsets[i][3];
  }
  const GLfloat verts[24] = {
      ndc[0][0], ndc[0][1], ndc[0][2], ndc[0][3],
      ndc[3][0], ndc[3][1], ndc[3][2], ndc[3][3],
      ndc[2][0], ndc[2][1], ndc[2][2], ndc[2][3],
      ndc[0][0], ndc[0][1], ndc[0][2], ndc[0][3],
      ndc[2][0], ndc[2][1], ndc[2][2], ndc[2][3],
      ndc[1][0], ndc[1][1], ndc[1][2], ndc[1][3],
  };
  glUseProgram(bg_prog);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, backdrop_tex);
  glUniform1i(bg_u_tex, 0);
  glBindBuffer(GL_ARRAY_BUFFER, 0);
  glEnableVertexAttribArray((GLuint)bg_a_pos);
  glEnableVertexAttribArray((GLuint)bg_a_uv);
  glVertexAttribPointer((GLuint)bg_a_pos, 2, GL_FLOAT, GL_FALSE, 4 * (GLsizei)sizeof(GLfloat), verts);
  glVertexAttribPointer((GLuint)bg_a_uv, 2, GL_FLOAT, GL_FALSE, 4 * (GLsizei)sizeof(GLfloat), verts + 2);
  glDrawArrays(GL_TRIANGLES, 0, 6);
  glDisableVertexAttribArray((GLuint)bg_a_pos);
  glDisableVertexAttribArray((GLuint)bg_a_uv);
}

static void draw_perspective_quad(void) {
  if (!quad_valid || !fbo_tex) return;
  const GLfloat verts[12] = {
      quad_anim_pts[0], quad_anim_pts[1],
      quad_anim_pts[6], quad_anim_pts[7],
      quad_anim_pts[4], quad_anim_pts[5],
      quad_anim_pts[0], quad_anim_pts[1],
      quad_anim_pts[4], quad_anim_pts[5],
      quad_anim_pts[2], quad_anim_pts[3],
  };
  const GLfloat col_major[9] = {
      quad_anim_inv_h[0], quad_anim_inv_h[3], quad_anim_inv_h[6],
      quad_anim_inv_h[1], quad_anim_inv_h[4], quad_anim_inv_h[7],
      quad_anim_inv_h[2], quad_anim_inv_h[5], quad_anim_inv_h[8],
  };
  glEnable(GL_BLEND);
  glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
  glUseProgram(quad_prog);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, fbo_tex);
  glUniform1i(quad_u_tex, 0);
  glUniform2f(quad_u_stage, surface_w > 0 ? (float)surface_w : 1.0f, surface_h > 0 ? (float)surface_h : 1.0f);
  glUniformMatrix3fv(quad_u_inv_h, 1, GL_FALSE, col_major);
  glUniform1f(quad_u_radius, quad_corner_radius);
  glUniform1f(quad_u_bright, quad_brightness);
  glBindBuffer(GL_ARRAY_BUFFER, 0);
  glEnableVertexAttribArray((GLuint)quad_a_pos);
  glVertexAttribPointer((GLuint)quad_a_pos, 2, GL_FLOAT, GL_FALSE, 2 * (GLsizei)sizeof(GLfloat), verts);
  glDrawArrays(GL_TRIANGLES, 0, 6);
  glDisableVertexAttribArray((GLuint)quad_a_pos);
  glDisable(GL_BLEND);
}

JNIEXPORT jlong JNICALL JNI(create)(JNIEnv *env, jobject self, jbyteArray bundle) {
  (void)self;
  long expected = 0;
  if (!atomic_compare_exchange_strong(&owner, &expected, -1)) return 0;
  render_thread = pthread_self();
  if (!bundle) { atomic_store(&owner, 0); return 0; }
  jsize length = (*env)->GetArrayLength(env, bundle);
  if (length <= 0) { atomic_store(&owner, 0); return 0; }
  char *source = calloc((size_t)length + 1, 1);
  if (!source) { atomic_store(&owner, 0); return 0; }
  (*env)->GetByteArrayRegion(env, bundle, 0, length, (jbyte *)source);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    free(source);
    atomic_store(&owner, 0);
    return 0;
  }
  gl_ready = 0;
  int ok = pocket_runtime_boot(source, (size_t)length, empty_pack, 0, POCKET_LOGICAL_W, POCKET_LOGICAL_H);
  free(source);
  if (ok) ok = duo_install();
  if (ok) {
    gl_ready = pocket_runtime_gl_initialize() ? 1 : 0;
    ok = gl_ready;
  }
  if (!ok) {
    /* Do not log guest exception strings: they may contain UI data. */
    LOG("runtime_init_failed");
    compositor_shutdown();
    if (gl_ready && eglGetCurrentContext() != EGL_NO_CONTEXT) pocket_runtime_gl_shutdown();
    gl_ready = 0;
    pocket_runtime_shutdown(); duo_clear();
    atomic_store(&owner, 0); return 0;
  }
  if (eglGetCurrentContext() != EGL_NO_CONTEXT) {
    if (!compositor_init()) LOG("compositor_fbo_fallback");
  }
  motion_start_sec = monotonic_sec();
  recoil_start_sec = -10.0;
  memset(&contacts, 0, sizeof contacts); keys = key_pulses = 0;
  long token = ++generation; atomic_store(&owner, token);
  LOG("runtime_create bundle_load surface_attach renderer=gles2 role=TOP");
  return token;
}

JNIEXPORT void JNICALL JNI(destroy)(JNIEnv *env, jobject self, jlong handle, jboolean lost) {
  (void)env; (void)self; (void)lost;
  if (!valid(handle)) return;
  compositor_shutdown();
  if (gl_ready && eglGetCurrentContext() != EGL_NO_CONTEXT) pocket_runtime_gl_shutdown();
  gl_ready = 0; keys = key_pulses = 0; memset(&contacts, 0, sizeof contacts);
  pocket_runtime_shutdown(); duo_clear();
  atomic_store(&owner, 0); LOG("surface_detach runtime_destroy role=TOP");
}

JNIEXPORT jboolean JNICALL JNI(receive)(JNIEnv *env, jobject self, jlong handle, jbyteArray json) {
  (void)self;
  if (!valid(handle) || !json) return JNI_FALSE;
  jsize len = (*env)->GetArrayLength(env, json);
  if (len <= 0 || len > 16384) return JNI_FALSE;
  jbyte *bytes = (*env)->GetByteArrayElements(env, json, NULL);
  if (!bytes) return JNI_FALSE;
  int ok = duo_receive((const char *)bytes, (size_t)len);
  (*env)->ReleaseByteArrayElements(env, json, bytes, JNI_ABORT);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI(resize)(JNIEnv *env, jobject self, jlong handle, jint w, jint h) {
  (void)env; (void)self;
  if (!valid(handle)) return;
  surface_w = w > 0 ? w : 1; surface_h = h > 0 ? h : 1;
  LOG("resize logical=360x640 role=TOP");
}

JNIEXPORT void JNICALL JNI(setBackdrop)(JNIEnv *env, jobject self, jlong handle, jbyteArray rgba, jint width, jint height) {
  (void)self;
  if (!valid(handle) || !rgba || width <= 0 || height <= 0) return;
  if (!compositor_ready || eglGetCurrentContext() == EGL_NO_CONTEXT) return;
  jsize len = (*env)->GetArrayLength(env, rgba);
  if ((long long)len != (long long)width * (long long)height * 4LL) return;
  jbyte *bytes = (*env)->GetByteArrayElements(env, rgba, NULL);
  if (!bytes) return;
  if (!backdrop_tex) glGenTextures(1, &backdrop_tex);
  if (backdrop_tex) {
    glBindTexture(GL_TEXTURE_2D, backdrop_tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    glBindTexture(GL_TEXTURE_2D, 0);
    backdrop_w = width;
    backdrop_h = height;
  }
  (*env)->ReleaseByteArrayElements(env, rgba, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL JNI(setQuad)(JNIEnv *env, jobject self, jlong handle,
    jfloat tlX, jfloat tlY, jfloat trX, jfloat trY, jfloat brX, jfloat brY, jfloat blX, jfloat blY,
    jfloat cornerRadiusLogical, jfloat brightness, jfloat backdropScale) {
  (void)env; (void)self;
  if (!valid(handle)) return;
  float pts[8] = {tlX, tlY, trX, trY, brX, brY, blX, blY};
  for (int i = 0; i < 8; i++) {
    if (!isfinite(pts[i])) return;
  }
  float inv[9];
  if (!compute_homography_inv(pts, inv)) {
    quad_valid = 0;
    return;
  }
  memcpy(quad_base_pts, pts, sizeof quad_base_pts);
  memcpy(quad_anim_pts, pts, sizeof quad_anim_pts);
  memcpy(quad_anim_inv_h, inv, sizeof quad_anim_inv_h);
  quad_corner_radius = isfinite(cornerRadiusLogical) ? fmaxf(0.0f, fminf(cornerRadiusLogical, 80.0f)) : 27.0f;
  quad_brightness = isfinite(brightness) ? fmaxf(0.2f, fminf(brightness, 1.0f)) : 0.96f;
  quad_backdrop_scale = isfinite(backdropScale) ? fmaxf(0.5f, fminf(backdropScale, 2.0f)) : 1.03f;
  quad_valid = 1;
}

JNIEXPORT void JNICALL JNI(setMotion)(JNIEnv *env, jobject self, jlong handle,
    jboolean breathingEnabled, jfloat breathingAmplitudePx, jfloat breathingAmplitudeDp, jfloat recoilDropPx) {
  (void)env; (void)self;
  if (!valid(handle)) return;
  motion_breathing_enabled = breathingEnabled ? 1 : 0;
  motion_amp_px = isfinite(breathingAmplitudePx) ? fmaxf(0.0f, fminf(breathingAmplitudePx, 48.0f)) : 0.0f;
  motion_amp_dp = isfinite(breathingAmplitudeDp) ? fmaxf(0.0f, fminf(breathingAmplitudeDp, 16.0f)) : 0.0f;
  motion_recoil_drop_px = isfinite(recoilDropPx) ? fmaxf(0.0f, fminf(recoilDropPx, 24.0f)) : 0.0f;
}

JNIEXPORT void JNICALL JNI(pulseRecoil)(JNIEnv *env, jobject self, jlong handle) {
  (void)env; (void)self;
  if (!valid(handle)) return;
  recoil_start_sec = monotonic_sec();
}

JNIEXPORT void JNICALL JNI(touch)(JNIEnv *env, jobject self, jlong handle, jint action, jint id, jfloat x, jfloat y) {
  (void)env; (void)self;
  if (!valid(handle)) return;
  PocketTouchPhase phase;
  if (action == 0 || action == 5) phase = POCKET_TOUCH_DOWN;
  else if (action == 1 || action == 6) phase = POCKET_TOUCH_UP;
  else if (action == 2) phase = POCKET_TOUCH_MOVE;
  else if (action == 3) phase = POCKET_TOUCH_CANCEL;
  else return;
  if (quad_valid) {
    if (phase == POCKET_TOUCH_CANCEL) {
      pocket_contact_event(&contacts, phase, id, 0.0f, 0.0f, POCKET_LOGICAL_W, POCKET_LOGICAL_H);
      return;
    }
    if (!isfinite(x) || !isfinite(y)) return;
    float qx = quad_anim_inv_h[0] * x + quad_anim_inv_h[1] * y + quad_anim_inv_h[2];
    float qy = quad_anim_inv_h[3] * x + quad_anim_inv_h[4] * y + quad_anim_inv_h[5];
    float qz = quad_anim_inv_h[6] * x + quad_anim_inv_h[7] * y + quad_anim_inv_h[8];
    if (fabsf(qz) < 1e-4f) return;
    float u = qx / qz;
    float v = qy / qz;
    if (!isfinite(u) || !isfinite(v)) return;
    if (phase == POCKET_TOUCH_DOWN && (u < 0.0f || u >= 1.0f || v < 0.0f || v >= 1.0f)) {
      return;
    }
    float lx = u * (float)POCKET_LOGICAL_W;
    float ly = v * (float)POCKET_LOGICAL_H;
    pocket_contact_event(&contacts, phase, id, lx, ly, POCKET_LOGICAL_W, POCKET_LOGICAL_H);
    return;
  }
  pocket_contact_event(&contacts, phase, id, x, y, surface_w, surface_h);
}

JNIEXPORT void JNICALL JNI(key)(JNIEnv *env, jobject self, jlong handle, jint mask, jboolean down) {
  (void)env; (void)self;
  if (!valid(handle)) return;
  if (down) { keys |= (uint32_t)mask; key_pulses |= (uint32_t)mask; } else keys &= ~(uint32_t)mask;
}

JNIEXPORT void JNICALL JNI(clearInput)(JNIEnv *env, jobject self, jlong handle) {
  (void)env; (void)self;
  if (!valid(handle)) return;
  keys = 0; key_pulses = 0;
  pocket_contacts_cancel(&contacts);
}

JNIEXPORT jboolean JNICALL JNI(frame)(JNIEnv *env, jobject self, jlong handle) {
  (void)env; (void)self;
  if (!valid(handle)) return JNI_FALSE;
  PocketRuntimeContactsInput input = {0}; input.buttons = keys | key_pulses; key_pulses = 0;
  if (quad_valid) {
    pocket_contacts_sample(&contacts, &input, POCKET_LOGICAL_W, POCKET_LOGICAL_H,
        POCKET_LOGICAL_W, POCKET_LOGICAL_H, pocket_runtime_hit_test_bounds);
  } else {
    pocket_contacts_sample(&contacts, &input, surface_w, surface_h,
        POCKET_LOGICAL_W, POCKET_LOGICAL_H, pocket_runtime_hit_test_bounds);
  }
  if (!pocket_runtime_tick_contacts(&input)) return JNI_FALSE;
  if (compositor_ready && fbo) {
    float tx = 0.0f, ty = 0.0f, rad = 0.0f, r_scale = 1.0f;
    compute_motion(monotonic_sec(), &tx, &ty, &rad, &r_scale);
    if (quad_valid) {
      float cx = (surface_w > 0 ? (float)surface_w : 1.0f) * 0.5f;
      float cy = (surface_h > 0 ? (float)surface_h : 1.0f) * 0.5f;
      float cosA = cosf(rad), sinA = sinf(rad);
      float transformed[8];
      for (int i = 0; i < 4; i++) {
        float dx = (quad_base_pts[i * 2] - cx) * r_scale;
        float dy = (quad_base_pts[i * 2 + 1] - cy) * r_scale;
        transformed[i * 2] = cx + (dx * cosA - dy * sinA) + tx;
        transformed[i * 2 + 1] = cy + (dx * sinA + dy * cosA) + ty;
      }
      float inv[9];
      if (compute_homography_inv(transformed, inv)) {
        memcpy(quad_anim_pts, transformed, sizeof quad_anim_pts);
        memcpy(quad_anim_inv_h, inv, sizeof quad_anim_inv_h);
      }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    int rendered = pocket_runtime_gl_render(POCKET_FBO_W, POCKET_FBO_H);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!rendered) return JNI_FALSE;
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glViewport(0, 0, surface_w, surface_h);
    glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    draw_backdrop(tx, ty, rad, r_scale);
    draw_perspective_quad();
    glUseProgram(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return glGetError() == GL_NO_ERROR ? JNI_TRUE : JNI_FALSE;
  }
  return pocket_runtime_gl_render(surface_w, surface_h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JNI(pollIntent)(JNIEnv *env, jobject self, jlong handle) {
  (void)self;
  if (!valid(handle)) return NULL;
  char *message = duo_pop();
  if (!message) return NULL;
  /* Intent vocabulary is ASCII only; UTF-8 user text never crosses NewStringUTF. */
  jstring result = (*env)->NewStringUTF(env, message); free(message); return result;
}
