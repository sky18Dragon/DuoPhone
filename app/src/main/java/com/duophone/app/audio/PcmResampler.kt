package com.duophone.app.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Stateful linear PCM 16-bit mono resampler with cross-frame phase and sample continuity.
 */
class StatefulLinearResampler(
    val inputRateHz: Int,
    val outputRateHz: Int,
) {
    init {
        require(inputRateHz > 0) { "inputRateHz must be positive: $inputRateHz" }
        require(outputRateHz > 0) { "outputRateHz must be positive: $outputRateHz" }
    }

    private val step: Double = inputRateHz.toDouble() / outputRateHz.toDouble()
    private var fractionalPos: Double = 0.0
    private var prevSample: Short = 0
    private var hasPrev: Boolean = false

    fun reset() {
        fractionalPos = 0.0
        prevSample = 0
        hasPrev = false
    }

    fun process(input: ShortArray, length: Int = input.size): ShortArray {
        require(length in 0..input.size) { "Invalid length $length for buffer size ${input.size}" }
        if (length == 0) return ShortArray(0)
        if (inputRateHz == outputRateHz) {
            hasPrev = true
            prevSample = input[length - 1]
            return input.copyOf(length)
        }

        val estimatedOut = ((length.toDouble() - fractionalPos) / step).toInt() + 4
        val out = ShortArray(estimatedOut.coerceAtLeast(1))
        var outCount = 0

        // Index -1 refers to the trailing sample of the previous chunk when available.
        var pos = fractionalPos
        while (pos < (length - 1).toDouble()) {
            val baseIdx = kotlin.math.floor(pos).toInt()
            val frac = pos - baseIdx.toDouble()
            val s0: Double
            val s1: Double
            if (baseIdx < 0) {
                s0 = (if (hasPrev) prevSample else input[0]).toDouble()
                s1 = input[0].toDouble()
            } else {
                s0 = input[baseIdx].toDouble()
                s1 = input[baseIdx + 1].toDouble()
            }
            val interpolated = (s0 + (s1 - s0) * frac)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            if (outCount == out.size) {
                // Rare growth guard
                return processFallbackGrow(out, outCount, pos, input, length)
            }
            out[outCount++] = interpolated
            pos += step
        }

        // Preserve fractional offset into the next chunk using prevSample = input[length - 1]
        fractionalPos = pos - length.toDouble()
        prevSample = input[length - 1]
        hasPrev = true
        return out.copyOf(outCount)
    }

    private fun processFallbackGrow(
        initial: ShortArray,
        initialCount: Int,
        startPos: Double,
        input: ShortArray,
        length: Int,
    ): ShortArray {
        val list = ArrayList<Short>(initialCount + 16)
        for (i in 0 until initialCount) list.add(initial[i])
        var pos = startPos
        while (pos < (length - 1).toDouble()) {
            val baseIdx = kotlin.math.floor(pos).toInt()
            val frac = pos - baseIdx.toDouble()
            val s0 = if (baseIdx < 0) (if (hasPrev) prevSample else input[0]).toDouble() else input[baseIdx].toDouble()
            val s1 = if (baseIdx < 0) input[0].toDouble() else input[baseIdx + 1].toDouble()
            val interpolated = (s0 + (s1 - s0) * frac)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            list.add(interpolated)
            pos += step
        }
        fractionalPos = pos - length.toDouble()
        prevSample = input[length - 1]
        hasPrev = true
        return list.toShortArray()
    }
}

data class PcmLevelStats(
    val sampleCount: Int,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val allZero: Boolean,
)

object PcmLevelMeter {
    private const val MIN_DBFS = -96.0
    private const val FULL_SCALE = 32768.0

    fun measure(samples: ShortArray, length: Int = samples.size): PcmLevelStats {
        if (length <= 0) {
            return PcmLevelStats(sampleCount = 0, rmsDbfs = MIN_DBFS, peakDbfs = MIN_DBFS, allZero = true)
        }
        var sumSq = 0.0
        var maxAbs = 0
        for (i in 0 until length) {
            val v = samples[i].toInt()
            val abs = if (v == Short.MIN_VALUE.toInt()) 32768 else kotlin.math.abs(v)
            if (abs > maxAbs) maxAbs = abs
            sumSq += v.toDouble() * v.toDouble()
        }
        if (maxAbs == 0) {
            return PcmLevelStats(sampleCount = length, rmsDbfs = MIN_DBFS, peakDbfs = MIN_DBFS, allZero = true)
        }
        val rmsNorm = sqrt(sumSq / length.toDouble()) / FULL_SCALE
        val peakNorm = maxAbs.toDouble() / FULL_SCALE
        val rmsDb = max(MIN_DBFS, 20.0 * log10(rmsNorm.coerceAtLeast(1e-6)))
        val peakDb = max(MIN_DBFS, 20.0 * log10(peakNorm.coerceAtLeast(1e-6)))
        return PcmLevelStats(
            sampleCount = length,
            rmsDbfs = rmsDb,
            peakDbfs = peakDb,
            allZero = false,
        )
    }

    fun pcm16LeBytesToShorts(bytes: ByteArray, byteLength: Int = bytes.size): ShortArray {
        val count = byteLength / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    fun shortsToPcm16LeBytes(samples: ShortArray, length: Int = samples.size): ByteArray {
        val out = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = samples[i].toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}
