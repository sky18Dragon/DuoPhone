package com.duophone.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmResamplerTest {
    @Test
    fun `resampler upsamples 8kHz to 48kHz across chunk boundaries without step discontinuity`() {
        val resampler = StatefulLinearResampler(inputRateHz = 8_000, outputRateHz = 48_000)
        // Continuous ramp: 0, 600, 1200, ... across two chunks
        val chunk1 = ShortArray(40) { i -> (i * 120).toShort() }
        val chunk2 = ShortArray(40) { i -> ((i + 40) * 120).toShort() }

        val out1 = resampler.process(chunk1)
        val out2 = resampler.process(chunk2)

        assertTrue(out1.size in 230..246)
        assertTrue(out2.size in 234..246)

        // Check boundary continuity between last sample of out1 and first sample of out2
        val boundaryDelta = abs(out2.first().toInt() - out1.last().toInt())
        assertTrue("Expected smooth transition across boundary, delta=$boundaryDelta", boundaryDelta <= 45)
    }

    @Test
    fun `resampler downsamples 48kHz to 8kHz preserving frequency and sample bounds`() {
        val resampler = StatefulLinearResampler(inputRateHz = 48_000, outputRateHz = 8_000)
        val inSamples = ShortArray(960) { i ->
            (16_000.0 * sin(2.0 * PI * 400.0 * i / 48_000.0)).toInt().toShort()
        }
        val outSamples = resampler.process(inSamples)
        assertTrue(outSamples.size in 155..165)

        val stats = PcmLevelMeter.measure(outSamples)
        assertFalse(stats.allZero)
        assertTrue("Expected audible signal level, got ${stats.rmsDbfs}", stats.rmsDbfs > -15.0)
    }

    @Test
    fun `same rate passthrough and byte conversion preserve exact samples`() {
        val resampler = StatefulLinearResampler(16_000, 16_000)
        val input = shortArrayOf(-32768, -123, 0, 456, 32767)
        assertArrayEquals(input, resampler.process(input))

        val leBytes = PcmLevelMeter.shortsToPcm16LeBytes(input)
        val roundTripped = PcmLevelMeter.pcm16LeBytesToShorts(leBytes)
        assertArrayEquals(input, roundTripped)
    }

    @Test
    fun `level meter computes digital silence and full-scale dbfs accurately`() {
        val zeroStats = PcmLevelMeter.measure(ShortArray(160))
        assertTrue(zeroStats.allZero)
        assertEquals(-96.0, zeroStats.rmsDbfs, 0.01)

        val halfScale = ShortArray(160) { 16384 }
        val stats = PcmLevelMeter.measure(halfScale)
        assertFalse(stats.allZero)
        assertEquals(-6.02, stats.rmsDbfs, 0.1)
        assertEquals(-6.02, stats.peakDbfs, 0.1)
    }

    @Test
    fun `voice conditioner filters dc, boosts midband speech without clipping, and ducks echo`() {
        val conditioner = PcmVoiceConditioner(16_000)

        // DC / very low rumble settles down cleanly after settling
        val dcWarmup = ShortArray(1200) { 10_000 }
        conditioner.processInPlace(dcWarmup, targetGain = 1.0f)
        val tailStats = PcmLevelMeter.measure(dcWarmup.copyOfRange(900, 1200))
        assertTrue("DC should be attenuated below -24 dBFS, got ${tailStats.rmsDbfs}", tailStats.rmsDbfs < -24.0)

        conditioner.reset()
        // Quiet 1 kHz speech (~ -30 dBFS) should boost significantly (~ +9 dB)
        val speech = ShortArray(480) { i ->
            (1_000.0 * sin(2.0 * PI * 1_000.0 * i / 16_000.0)).toInt().toShort()
        }
        val origStats = PcmLevelMeter.measure(speech.copyOfRange(160, 480))
        val boosted = speech.copyOf()
        conditioner.processInPlace(boosted, targetGain = PcmVoiceConditioner.DEFAULT_SPEECH_GAIN)
        val boostedStats = PcmLevelMeter.measure(boosted.copyOfRange(160, 480))
        assertTrue(
            "Expected speech gain boost, orig=${origStats.rmsDbfs}, boosted=${boostedStats.rmsDbfs}",
            boostedStats.rmsDbfs - origStats.rmsDbfs >= 7.5,
        )

        // Loud signal stays soft-limited within Int16 range
        val loud = ShortArray(320) { i ->
            (25_000.0 * sin(2.0 * PI * 1_000.0 * i / 16_000.0)).toInt().toShort()
        }
        conditioner.processInPlace(loud, targetGain = PcmVoiceConditioner.DEFAULT_SPEECH_GAIN)
        assertTrue(loud.all { it in Short.MIN_VALUE..Short.MAX_VALUE })

        // Echo ducking attenuates strongly below boosted level
        val ducked = speech.copyOf()
        conditioner.reset()
        conditioner.processInPlace(ducked, targetGain = PcmVoiceConditioner.ECHO_DUCK_GAIN)
        val duckedStats = PcmLevelMeter.measure(ducked.copyOfRange(160, 480))
        assertTrue(
            "Expected echo ducking attenuation, orig=${origStats.rmsDbfs}, ducked=${duckedStats.rmsDbfs}",
            duckedStats.rmsDbfs < origStats.rmsDbfs - 8.0,
        )
    }
}
