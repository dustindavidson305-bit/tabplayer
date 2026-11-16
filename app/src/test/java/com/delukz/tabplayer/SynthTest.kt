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
 * Sound-quality regression tests for the v1.2 synth: ports of Fruity
 * McLoops' Web Audio recipes on the offline engine in Engine.kt, mixed
 * through Fruity's master compressor settings. These tests pin the
 * measurable properties Delukz's "still all distorted" complaint demands:
 * bounded peaks even in dense chords (through the real playback chain),
 * a clean guitar whose energy lives on the harmonic series (no fizz),
 * grit only in the Distorted variant, and click-free note boundaries.
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

    /** Goertzel magnitude at one frequency over a window (not normalized by window length ratios matter). */
    private fun goertzel(buf: FloatArray, aSec: Double, bSec: Double, freq: Double): Double {
        val from = (aSec * Synth.SAMPLE_RATE).toInt()
        val to = minOf((bSec * Synth.SAMPLE_RATE).toInt(), buf.size)
        val w = 2 * PI * freq / Synth.SAMPLE_RATE
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            re += buf[i] * cos(w * (i - from))
            im -= buf[i] * sin(w * (i - from))
        }
        return sqrt(re * re + im * im) / (to - from)
    }

    /** Dominant frequency in a window via a Goertzel scan. */
    private fun dominantFreq(buf: FloatArray, aSec: Double, bSec: Double, lo: Double, hi: Double): Double {
        var bestF = lo
        var bestP = -1.0
        var f = lo
        while (f <= hi) {
            val p = goertzel(buf, aSec, bSec, f)
            if (p > bestP) { bestP = p; bestF = f }
            f += 1.0
        }
        return bestF
    }

    /** Harmonic distortion ratio: energy in harmonics 2..6 over the fundamental, sustain window. */
    private fun thd(buf: FloatArray, f0: Double): Double {
        val fund = goertzel(buf, 0.15, 0.45, f0)
        var e = 0.0
        for (h in 2..6) {
            val p = goertzel(buf, 0.15, 0.45, f0 * h)
            e += p * p
        }
        return sqrt(e) / fund
    }

    @Test
    fun allFamiliesVariantsBoundedAndAudible() {
        for (family in SoundFamily.values()) {
            for (variant in 0..4) {
                for (pitch in listOf(40, 45, 52, 64)) {
                    val buf = Synth.renderNote(family, variant, pitch, 110, 0.8)
                    assertTrue("empty render $family/$variant p$pitch", buf.isNotEmpty())
                    assertFalse("NaN $family/$variant p$pitch", buf.any { it.isNaN() })
                    val pk = peak(buf)
                    assertTrue("peak $pk too hot $family/$variant p$pitch", pk <= 0.86f)
                    assertTrue("peak $pk too quiet $family/$variant p$pitch", pk > 0.1f)
                }
            }
        }
    }

    @Test
    fun cleanElectricAttackIsSmooth() {
        // The v1.0 KS guitar measured ~1.03 on this metric (all fizz).
        val buf = Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 0.8)
        val hf = hfRatio(buf, 80.0)
        assertTrue("clean attack too harsh: hf=$hf", hf < 0.35)
    }

    @Test
    fun cleanGuitarEnergyIsHarmonicNotFizzy() {
        // A clean pluck's sustain energy should sit on the harmonic series;
        // distortion/fizz would spray energy between the partials.
        val buf = Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 0.8) // A2 = 110 Hz
        val f0 = 110.0
        var harmE = 0.0
        var inhE = 0.0
        for (h in 1..6) {
            val p = goertzel(buf, 0.15, 0.45, f0 * h)
            harmE += p * p
        }
        for (m in listOf(1.5, 2.5, 3.5, 4.5, 5.5)) {
            val p = goertzel(buf, 0.15, 0.45, f0 * m)
            inhE += p * p
        }
        val ratio = inhE / (harmE + inhE)
        assertTrue("inharmonic energy too high: $ratio", ratio < 0.02)
        // And the fundamental is the strongest partial.
        val fund = goertzel(buf, 0.15, 0.45, f0)
        for (h in 2..6) {
            assertTrue("harmonic $h louder than fundamental", goertzel(buf, 0.15, 0.45, f0 * h) < fund)
        }
    }

    @Test
    fun distortedVariantHasTheGritCleanDoesNot() {
        val clean = Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 0.8)
        val dist = Synth.renderNote(SoundFamily.GUITAR, 3, 45, 100, 0.8)
        // Waveshaping adds strong harmonic content and compresses dynamics.
        assertTrue(
            "distorted should be much more harmonic-rich: clean=${thd(clean, 110.0)} dist=${thd(dist, 110.0)}",
            thd(dist, 110.0) > thd(clean, 110.0) * 2,
        )
        assertTrue(
            "distorted should be more compressed: clean=${crest(clean, 0.1, 0.5)} dist=${crest(dist, 0.1, 0.5)}",
            crest(dist, 0.1, 0.5) < crest(clean, 0.1, 0.5) * 0.75,
        )
    }

    @Test
    fun mutedPluckDiesFastCleanRings() {
        val muted = Synth.renderNote(SoundFamily.GUITAR, 4, 45, 100, 2.0)
        val clean = Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 2.0)
        assertTrue("muted should be silent by 0.5s", rms(muted, 0.5, 0.8) < 0.005)
        assertTrue("clean should still ring at 0.5s", rms(clean, 0.5, 0.8) > 0.01)
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
        // Palm mute audibly shortens a bass note too (recipes honour the shaping).
        val plain = Synth.renderNote(SoundFamily.BASS, 0, 33, 100, 2.0)
        val muted = Synth.renderNote(SoundFamily.BASS, 0, 33, 100, 2.0, fx = Synth.FxSpec(palmMute = true))
        assertTrue("palm-muted bass should be quieter late", rms(muted, 0.6, 1.0) < rms(plain, 0.6, 1.0) * 0.5)
    }

    /** Mix notes the way the player does: sum at 0.92 bus gain, master compressor, soft ceiling. */
    private fun playerPath(notes: List<FloatArray>, offsets: List<Int>): FloatArray {
        val len = notes.indices.maxOf { offsets[it] + notes[it].size }
        val mix = FloatArray(len)
        for ((k, nb) in notes.withIndex()) {
            for (i in nb.indices) mix[offsets[k] + i] += nb[i] * 0.92f
        }
        val comp = MasterCompressor(sr = Synth.SAMPLE_RATE)
        val out = FloatArray(len)
        for (i in 0 until len) out[i] = softCeiling(comp.process(mix[i]))
        return out
    }

    @Test
    fun denseChordsStayUnderCeilingThroughMasterChain() {
        val chordPitches = mapOf(
            SoundFamily.GUITAR to listOf(40, 45, 50, 55, 59, 64),
            SoundFamily.BASS to listOf(28, 33, 38, 43, 47, 52),
            SoundFamily.KEYS to listOf(48, 52, 55, 59, 62, 64),
            SoundFamily.DRUMS to listOf(36, 38, 42, 45, 49, 51),
        )
        for (family in SoundFamily.values()) {
            for (variant in 0..4) {
                val notes = chordPitches.getValue(family).map {
                    Synth.renderNote(family, variant, it, 110, 0.7)
                }
                val out = playerPath(notes, List(notes.size) { 0 })
                val pk = peak(out)
                assertTrue("chord $family/$variant exceeds ceiling: $pk", pk <= 1.0f)
                assertTrue("chord $family/$variant suspiciously quiet: $pk", pk > 0.2f)
            }
        }
    }

    @Test
    fun fastRunStaysUnderCeilingThroughMasterChain() {
        val step = (0.06 * Synth.SAMPLE_RATE).toInt()
        for (variant in listOf(0, 2, 3)) {
            val notes = (0 until 8).map { Synth.renderNote(SoundFamily.GUITAR, variant, 45 + it, 110, 0.5) }
            val out = playerPath(notes, (0 until 8).map { it * step })
            assertTrue("run variant $variant exceeds ceiling: ${peak(out)}", peak(out) <= 1.0f)
        }
    }

    @Test
    fun masterCompressorUsesFruitySettingsAndBehaves() {
        val c = MasterCompressor()
        assertEquals(-14.0, c.thresholdDb, 0.001)
        assertEquals(22.0, c.kneeDb, 0.001)
        assertEquals(5.0, c.ratio, 0.001)
        assertEquals(0.004, c.attackSec, 0.0001)
        assertEquals(0.18, c.releaseSec, 0.0001)
        // A hot sustained signal (0.9, about -0.9 dB) is pulled down to the
        // static-curve value: roughly -10.5 dB of gain reduction.
        val hot = FloatArray(Synth.SAMPLE_RATE / 2) { 0.9f }
        val outHot = MasterCompressor.processBuffer(hot)
        val settled = outHot[outHot.size - 1]
        assertTrue("hot signal should settle near 0.27, was $settled", settled in 0.24f..0.30f)
        // A quiet signal below the knee passes essentially untouched.
        val quiet = FloatArray(Synth.SAMPLE_RATE / 2) { 0.05f }
        val outQuiet = MasterCompressor.processBuffer(quiet)
        assertTrue("quiet signal should pass, was ${outQuiet[outQuiet.size - 1]}", outQuiet[outQuiet.size - 1] > 0.045f)
        // The compressor alone (before the ceiling) tames a dense mix's sustain.
        val notes = listOf(40, 45, 50, 55, 59, 64).map { Synth.renderNote(SoundFamily.GUITAR, 3, it, 110, 0.7) }
        val len = notes.maxOf { it.size }
        val mix = FloatArray(len)
        for (nb in notes) for (i in nb.indices) mix[i] += nb[i] * 0.92f
        val comp = MasterCompressor.processBuffer(mix)
        assertTrue("compressor should reduce the raw peak", peak(comp) < peak(mix))
        val from = (0.015 * Synth.SAMPLE_RATE).toInt()
        var latePeak = 0f
        for (i in from until comp.size) latePeak = maxOf(latePeak, abs(comp[i]))
        assertTrue("sustained compressed mix should be under 1.0, was $latePeak", latePeak <= 1.0f)
    }

    @Test
    fun softCeilingOnlyTouchesExtremeTransients() {
        assertEquals(0.5f, softCeiling(0.5f), 0.0001f)
        assertEquals(-0.5f, softCeiling(-0.5f), 0.0001f)
        assertTrue(softCeiling(3.0f) < 1.0f && softCeiling(3.0f) > 0.99f)
        assertTrue(softCeiling(-3.0f) > -1.0f && softCeiling(-3.0f) < -0.99f)
    }

    @Test
    fun noClicksAtNoteBoundaries() {
        val cases = listOf(
            Synth.renderNote(SoundFamily.GUITAR, 2, 45, 100, 0.8), // clean electric
            Synth.renderNote(SoundFamily.GUITAR, 0, 52, 100, 0.8), // acoustic steel
            Synth.renderNote(SoundFamily.KEYS, 0, 60, 100, 0.8),    // grand piano
        )
        for ((idx, buf) in cases.withIndex()) {
            assertTrue("case $idx starts hot: ${buf[0]}", abs(buf[0]) < 0.01f)
            assertTrue("case $idx ends hot: ${buf[buf.size - 1]}", abs(buf[buf.size - 1]) < 0.01f)
            // No discontinuities once the attack transient has passed.
            val from = (0.2 * Synth.SAMPLE_RATE).toInt()
            var md = 0f
            for (i in from + 1 until buf.size) md = maxOf(md, abs(buf[i] - buf[i - 1]))
            assertTrue("case $idx has a click in sustain: maxDelta=$md", md < 0.05f)
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
