package com.delukz.tabplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sound-quality regression tests for the v1.1 synth rewrite. The v1.0 guitar
 * used raw white-noise Karplus-Strong excitation, a near-unity loop gain and
 * an always-on tanh in the player, which made even the "clean" variants
 * harsh and distorted. These tests pin the measurable properties of the fix:
 * bounded peaks, a smooth (low high-frequency-energy) clean attack, a
 * compressed distorted variant, and pitch glides that actually land on pitch.
 */
class SynthTest {

    private fun peak(buf: FloatArray): Float = buf.maxOf { abs(it) }

    /** Share of energy in the sample-to-sample difference (fizz/harshness proxy). */
    private fun hfRatio(buf: FloatArray, ms: Double): Double {
        val n = minOf((Synth.SAMPLE_RATE * ms / 1000).toInt(), buf.size)
        var eTot = 0.0
        var eDiff = 0.0
        for (i in 0 until n) {
            eTot += buf[i] * buf[i]
            if (i > 0) {
                val d = (buf[i] - buf[i - 1]).toDouble()
                eDiff += d * d
            }
        }
        return if (eTot > 0) eDiff / eTot else 0.0
    }

    private fun crest(buf: FloatArray, aSec: Double, bSec: Double): Double {
        val from = (aSec * Synth.SAMPLE_RATE).toInt()
        val to = minOf((bSec * Synth.SAMPLE_RATE).toInt(), buf.size)
        var pk = 0.0
        var s = 0.0
        for (i in from until to) {
            pk = maxOf(pk, abs(buf[i]).toDouble())
            s += buf[i] * buf[i]
        }
        val rms = sqrt(s / (to - from))
        return if (rms > 0) pk / rms else 0.0
    }

    private fun rms(buf: FloatArray, aSec: Double, bSec: Double): Double {
        val from = (aSec * Synth.SAMPLE_RATE).toInt()
        val to = minOf((bSec * Synth.SAMPLE_RATE).toInt(), buf.size)
        if (to <= from) return 0.0
        var s = 0.0
        for (i in from until to) s += buf[i] * buf[i]
        return sqrt(s / (to - from))
    }

    /** Dominant frequency in a window via a Goertzel scan. */
    private fun dominantFreq(buf: FloatArray, aSec: Double, bSec: Double, lo: Double, hi: Double): Double {
        val from = (aSec * Synth.SAMPLE_RATE).toInt()
        val to = minOf((bSec * Synth.SAMPLE_RATE).toInt(), buf.size)
        var bestF = lo
        var bestP = -1.0
        var f = lo
        while (f <= hi) {
            val w = 2 * PI * f / Synth.SAMPLE_RATE
            var re = 0.0
            var im = 0.0
            for (i in from until to) {
                re += buf[i] * cos(w * (i - from))
                im -= buf[i] * sin(w * (i - from))
            }
            val p = re * re + im * im
            if (p > bestP) { bestP = p; bestF = f }
            f += 1.0
        }
        return bestF
    }

    @Test
    fun guitarVariantsStayCleanAndBounded() {
        for (variant in 0..4) {
            for (pitch in listOf(40, 45, 52, 64)) {
                val buf = Synth.renderNote(SoundFamily.GUITAR, variant, pitch, 110, 0.8)
                assertTrue("empty render v$variant p$pitch", buf.isNotEmpty())
                assertFalse("NaN v$variant p$pitch", buf.any { it.isNaN() })
                val pk = peak(buf)
                assertTrue("peak $pk too hot v$variant p$pitch", pk <= 0.85f)
                assertTrue("peak $pk too quiet v$variant p$pitch", pk > 0.1f)
            }
        }
    }

    @Test
    fun cleanElectricAttackIsSmooth() {
        // The v1.0 clean electric measured ~1.03 on this metric (all fizz).
        val buf = Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 0.8)
        val hf = hfRatio(buf, 80.0)
        assertTrue("clean attack too harsh: hf=$hf", hf < 0.35)
    }

    @Test
    fun distortedVariantIsCompressed() {
        val clean = Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 0.8)
        val dist = Synth.renderNote(SoundFamily.GUITAR, 3, 45, 100, 0.8)
        // Waveshaping compresses dynamics: crest factor drops hard vs clean.
        assertTrue(
            "distorted should be compressed: clean=${crest(clean, 0.1, 0.5)} dist=${crest(dist, 0.1, 0.5)}",
            crest(dist, 0.1, 0.5) < crest(clean, 0.1, 0.5) * 0.7,
        )
    }

    @Test
    fun mutedPluckDiesFastCleanRings() {
        val muted = Synth.renderNote(SoundFamily.GUITAR, 4, 45, 100, 2.0)
        val clean = Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 2.0)
        assertTrue("muted should be silent by 0.5s", rms(muted, 0.5, 0.8) < 0.01)
        assertTrue("clean should still ring at 0.5s", rms(clean, 0.5, 0.8) > 0.02)
    }

    @Test
    fun bendGlidesToTargetPitch() {
        val fx = Synth.FxSpec(bendSemitones = 2f)
        val buf = Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 1.2, fx = fx) // A2 110 Hz
        val start = dominantFreq(buf, 0.03, 0.13, 80.0, 160.0)
        val end = dominantFreq(buf, 0.85, 1.1, 80.0, 160.0)
        assertEquals(110.0, start, 7.0)
        assertEquals(123.5, end, 7.0) // +2 semitones = B2
    }

    @Test
    fun techniqueRendersAreSane() {
        val fxs = listOf(
            Synth.FxSpec(palmMute = true),
            Synth.FxSpec(harmonic = true),
            Synth.FxSpec(vibrato = true),
            Synth.FxSpec(slideFromSemitones = -2f),
            Synth.FxSpec(slideToSemitones = 3f),
            Synth.FxSpec(bendSemitones = 1f, prebend = true, bendRelease = true),
            Synth.FxSpec(staccato = true),
        )
        for (fx in fxs) {
            val buf = Synth.renderNote(SoundFamily.GUITAR, 2, 50, 100, 0.7, fx = fx)
            assertFalse("NaN for $fx", buf.any { it.isNaN() })
            assertTrue("peak too hot for $fx", peak(buf) <= 0.9f)
        }
    }

    @Test
    fun playerTranslatesTechniquesToFx() {
        fun note(tick: Long, string: Int, pitch: Int, fx: NoteEffects) =
            NoteEvent(tick, 240, pitch, string, 5, 100, effects = fx)
        val plain = NoteEffects()
        val notes = listOf(
            note(0, 1, 45, NoteEffects(slideType = 1)), // slides into the next note (+2 st)
            note(240, 1, 47, NoteEffects(hammerPull = true)),
            note(480, 2, 50, NoteEffects(slideType = -1)), // slide into from below (-2 st)
            note(720, 3, 52, NoteEffects(bendSemitones = 1f, bendType = 5)), // prebend + release
            note(960, 4, 40, plain),
        )
        val engine = PlayerEngine()
        val fx = engine.buildFx(notes)
        assertEquals(2f, fx[0].slideToSemitones, 0.01f)
        assertTrue(fx[1].softAttack)
        assertEquals(-2f, fx[2].slideFromSemitones, 0.01f)
        assertTrue(fx[3].prebend)
        assertTrue(fx[3].bendRelease)
    }
}
