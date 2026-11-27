package com.duophone.app.pocket

import com.duophone.app.BuildConfig

/** Build-time debug selector: no exported intent can enable experimental native code. */
object PocketBackend {
    val requested: Boolean get() = BuildConfig.DEBUG && BuildConfig.POCKET_UI == "top"
    val faultInjection: Boolean get() = requested && BuildConfig.POCKET_FAIL_INIT
    // v0.12.0 is singleton based; dual is rejected by Gradle, never silently simulated.
}

internal object PocketNative {
    fun load() = System.loadLibrary("duophone_pocket")
    external fun create(bundle: ByteArray): Long
    external fun destroy(handle: Long, contextLost: Boolean)
    external fun receive(handle: Long, json: ByteArray): Boolean
    external fun resize(handle: Long, width: Int, height: Int)
    external fun touch(handle: Long, action: Int, id: Int, x: Float, y: Float)
    external fun key(handle: Long, mask: Int, down: Boolean)
    external fun clearInput(handle: Long)
    external fun setBackdrop(handle: Long, rgba: ByteArray, width: Int, height: Int)
    external fun setQuad(
        handle: Long,
        tlX: Float,
        tlY: Float,
        trX: Float,
        trY: Float,
        brX: Float,
        brY: Float,
        blX: Float,
        blY: Float,
        cornerRadiusLogical: Float,
        brightness: Float,
        backdropScale: Float,
    )
    external fun setMotion(
        handle: Long,
        breathingEnabled: Boolean,
        breathingAmplitudePx: Float,
        breathingAmplitudeDp: Float,
        recoilDropPx: Float,
    )
    external fun pulseRecoil(handle: Long)
    external fun frame(handle: Long): Boolean
    external fun pollIntent(handle: Long): String?
}
