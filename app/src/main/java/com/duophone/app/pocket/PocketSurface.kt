package com.duophone.app.pocket

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.duophone.app.diagnostics.DuoLogger
import com.duophone.app.diagnostics.LogCategory
import com.duophone.app.stage.PhoneOverlayConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/** One GL thread owns all QuickJS/core calls. State is conflated, input ordered, events bounded. */
@SuppressLint("ViewConstructor")
internal class PocketSurface(context: Context, private val bundle: ByteArray,
    private val onIntent: (String) -> Unit, private val onFailure: (String) -> Unit,
    private val onReady: () -> Unit,
) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private class BackdropPixels(val rgba: ByteArray, val width: Int, val height: Int)

    @Volatile var presentedFrames = 0L
        private set
    private var handle = 0L // GL thread only
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var initialized = false
    private var readinessReported = false
    private var wantsRunning = true
    private var resumed = false // main thread only
    private var resumeBaselineFrames = 0L // main thread only
    private val mainHandler = Handler(Looper.getMainLooper())
    private val firstFrameTimeout = Runnable {
        if (!stopped && !closed && presentedFrames <= resumeBaselineFrames) fail("surface_first_frame_timeout")
    }
    private val state = AtomicReference<String?>(null)
    private val events = java.util.concurrent.ArrayBlockingQueue<String>(16)
    @Volatile private var overlayConfig = PhoneOverlayConfig()
    @Volatile private var pendingBackdrop: BackdropPixels? = null
    @Volatile private var backdropDirty = false
    @Volatile private var quadDirty = true
    @Volatile private var motionDirty = true
    @Volatile private var hasHandle = false
    @Volatile private var failed = false
    @Volatile private var stopped = false
    @Volatile private var closed = false
    @Volatile private var latestSnapshot: String? = null // main thread only

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusableInTouchMode = true
        setOnKeyListener { _, key, event -> forwardKey(key, event) }
    }

    fun snapshot(json: String) { latestSnapshot = json; state.set(json) }
    fun event(json: String) { if (!events.offer(json)) Log.w("PocketBridge", "event_queue_full") }

    fun setBackdrop(rgba: ByteArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || rgba.size != width * height * 4) return
        pendingBackdrop = BackdropPixels(rgba, width, height)
        backdropDirty = true
    }

    fun setOverlayConfig(config: PhoneOverlayConfig) {
        if (overlayConfig != config) {
            overlayConfig = config
            quadDirty = true
            motionDirty = true
        }
    }

    fun pulseRecoil() {
        queueEvent { if (handle != 0L && !stopped) PocketNative.pulseRecoil(handle) }
    }

    private fun fail(code: String) {
        if (failed || closed) return
        failed = true
        Log.e("PocketHost", "fallback=$code role=TOP")
        DuoLogger.e(LogCategory.DISPLAY, "pocket_fallback", "code=$code frames=$presentedFrames")
        mainHandler.post { if (!closed) onFailure(code) }
    }

    private fun destroyOnGlThread(contextLost: Boolean) {
        if (handle != 0L) {
            PocketNative.destroy(handle, contextLost)
            handle = 0L
        }
        hasHandle = false
        initialized = false
        events.clear()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        destroyOnGlThread(true)
        state.compareAndSet(null, latestSnapshot)
        Log.i("PocketHost", "surface_created role=TOP")
        DuoLogger.i(LogCategory.DISPLAY, "pocket_surface_created", "w=$surfaceWidth h=$surfaceHeight hadHandle=$hasHandle stopped=$stopped")
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width; surfaceHeight = height
        quadDirty = true
        if (handle != 0L) PocketNative.resize(handle, width, height)
        DuoLogger.i(LogCategory.DISPLAY, "pocket_surface_changed", "w=$width h=$height handle=${handle != 0L}")
    }
    override fun onDrawFrame(gl: GL10?) {
        if (failed || stopped || closed) return
        try {
            if (handle == 0L) {
                val start = SystemClock.elapsedRealtime()
                handle = PocketNative.create(bundle)
                if (handle == 0L) { fail("runtime_or_surface_init"); return }
                hasHandle = true
                backdropDirty = pendingBackdrop != null
                quadDirty = true
                motionDirty = true
                PocketNative.resize(handle, surfaceWidth, surfaceHeight)
                val initMs = SystemClock.elapsedRealtime() - start
                Log.i("PocketHost", "runtime_init_ms=$initMs role=TOP")
                DuoLogger.i(LogCategory.DISPLAY, "pocket_runtime_init", "ms=$initMs handle=$handle w=$surfaceWidth h=$surfaceHeight")
            }
            if (backdropDirty) {
                pendingBackdrop?.let { PocketNative.setBackdrop(handle, it.rgba, it.width, it.height) }
                backdropDirty = false
            }
            if (quadDirty) {
                val cfg = overlayConfig
                val pts = PocketQuadGeometry.computeScreenQuad(cfg, surfaceWidth.toFloat(), surfaceHeight.toFloat(), 1.03f)
                val radius = PocketQuadGeometry.cornerRadiusLogical(cfg)
                PocketNative.setQuad(
                    handle,
                    pts[0], pts[1], pts[2], pts[3], pts[4], pts[5], pts[6], pts[7],
                    radius, cfg.screenBrightness, 1.03f,
                )
                quadDirty = false
                DuoLogger.i(
                    LogCategory.DISPLAY,
                    "pocket_quad_applied",
                    "tl=(${pts[0].roundToInt()},${pts[1].roundToInt()}) tr=(${pts[2].roundToInt()},${pts[3].roundToInt()}) br=(${pts[4].roundToInt()},${pts[5].roundToInt()}) bl=(${pts[6].roundToInt()},${pts[7].roundToInt()}) stage=${surfaceWidth}x${surfaceHeight}",
                )
            }
            if (motionDirty) {
                val cfg = overlayConfig
                val density = resources.displayMetrics.density
                PocketNative.setMotion(
                    handle,
                    cfg.breathingEnabled,
                    cfg.breathingAmplitudeDp * density,
                    cfg.breathingAmplitudeDp,
                    1.8f * density,
                )
                motionDirty = false
            }
            val update = state.getAndSet(null)
            if (update != null) {
                // Only envelope kind changes; snapshot contents never become source code.
                val payload = if (initialized) update else org.json.JSONObject(update).put("kind", "initialize").toString()
                if (!PocketNative.receive(handle, payload.toByteArray(Charsets.UTF_8))) {
                    destroyOnGlThread(false); fail("state_delivery"); return
                }
                initialized = true
                Log.d("PocketBridge", "state_push role=TOP")
                DuoLogger.i(LogCategory.DISPLAY, "pocket_state_applied", "init=$initialized frames=$presentedFrames")
            }
            repeat(16) {
                val message = events.poll()
                if (message != null && !PocketNative.receive(handle, message.toByteArray(Charsets.UTF_8))) {
                    destroyOnGlThread(false); fail("event_delivery"); return
                }
            }
            if (!PocketNative.frame(handle)) { destroyOnGlThread(false); fail("native_frame"); return }
            presentedFrames++
            if (presentedFrames == 1L || presentedFrames == resumeBaselineFrames + 1L) {
                DuoLogger.i(LogCategory.DISPLAY, "pocket_frame_ok", "frames=$presentedFrames init=$initialized")
            }
            if (initialized) {
                mainHandler.post { mainHandler.removeCallbacks(firstFrameTimeout) }
                if (!readinessReported) {
                    readinessReported = true
                    mainHandler.post {
                        DuoLogger.i(LogCategory.DISPLAY, "pocket_onready", "closed=$closed failed=$failed")
                        if (!closed && !failed) onReady()
                    }
                }
            }
            for (index in 0 until 16) {
                val message = PocketNative.pollIntent(handle) ?: break
                mainHandler.post { if (!closed && !stopped && !failed) onIntent(message) }
            }
        } catch (_: RuntimeException) { destroyOnGlThread(false); fail("runtime_exception")
        } catch (_: LinkageError) { destroyOnGlThread(false); fail("native_linkage") }
    }

    fun start() {
        wantsRunning = true
        resumeSurface()
    }

    private fun resumeSurface() {
        if (closed || resumed) {
            DuoLogger.i(LogCategory.DISPLAY, "pocket_lifecycle", "op=resume_skip closed=$closed resumed=$resumed")
            return
        }
        resumed = true
        stopped = false
        latestSnapshot?.let { state.set(it) }
        resumeBaselineFrames = presentedFrames
        DuoLogger.i(LogCategory.DISPLAY, "pocket_lifecycle", "op=resume stopped=$stopped resumed=$resumed hasHandle=$hasHandle frames=$presentedFrames")
        onResume()
        mainHandler.removeCallbacks(firstFrameTimeout)
        mainHandler.postDelayed(firstFrameTimeout, 5000)
    }

    /** Queue teardown BEFORE GLSurfaceView pauses/detaches and loses its EGL thread. */
    fun stop(reason: String = "stop") {
        wantsRunning = false
        pauseSurface(reason)
    }

    private fun pauseSurface(reason: String) {
        if (stopped) return
        stopped = true
        resumed = false
        mainHandler.removeCallbacks(firstFrameTimeout)
        DuoLogger.i(LogCategory.DISPLAY, "pocket_lifecycle", "op=pause reason=$reason stopped=$stopped resumed=$resumed hasHandle=$hasHandle frames=$presentedFrames")
        if (hasHandle) {
            val done = CountDownLatch(1)
            queueEvent {
                try { destroyOnGlThread(false) } finally { done.countDown() }
            }
            if (!done.await(1500, TimeUnit.MILLISECONDS)) fail("teardown_timeout")
        }
        onPause()
    }
    fun close(reason: String = "close") {
        stop(reason)
        closed = true
        DuoLogger.i(LogCategory.DISPLAY, "pocket_lifecycle", "op=close reason=$reason closed=$closed frames=$presentedFrames")
    }

    fun clearInput() {
        queueEvent { if (handle != 0L && !stopped) PocketNative.clearInput(handle) }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            clearInput()
        } else if (!closed && !stopped && !failed) {
            requestFocus()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        DuoLogger.i(LogCategory.DISPLAY, "pocket_lifecycle", "op=surfaceDestroyed stopped=$stopped resumed=$resumed hasHandle=$hasHandle frames=$presentedFrames")
        pauseSurface("surfaceDestroyed")
        super.surfaceDestroyed(holder)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        DuoLogger.i(LogCategory.DISPLAY, "pocket_lifecycle", "op=surfaceCreated stopped=$stopped resumed=$resumed hasHandle=$hasHandle frames=$presentedFrames")
        if (wantsRunning && !resumed) resumeSurface()
    }

    override fun onDetachedFromWindow() {
        close("onDetachedFromWindow")
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closed || stopped || failed) return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            DuoLogger.d(LogCategory.INPUT, "pocket_touch", "action=$action w=$width h=$height hasHandle=$hasHandle stopped=$stopped")
        }
        val indices = if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL)
            0 until event.pointerCount else event.actionIndex..event.actionIndex
        for (index in indices) {
            val id = event.getPointerId(index); val x = event.getX(index); val y = event.getY(index)
            queueEvent { if (handle != 0L && !stopped) PocketNative.touch(handle, action, id, x, y) }
        }
        return true
    }

    private fun forwardKey(key: Int, event: KeyEvent): Boolean {
        val mask = when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> 0x10
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x20
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x40
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x80
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> 0x2000
            KeyEvent.KEYCODE_BUTTON_B -> 0x4000
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        queueEvent { if (handle != 0L && !stopped) PocketNative.key(handle, mask, down) }
        return true
    }
}
