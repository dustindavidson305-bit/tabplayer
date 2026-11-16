package com.delukz.tabplayer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * v1.2 audio engine: a small offline port of the Web Audio primitives that
 * Fruity McLoops' sound library is built from. Delukz asked for "the same
 * sound engine as Fruity McLoops" — Fruity is a Web Audio app whose sounds
 * are recipe functions (oscillators + biquad filters + gain envelopes +
 * waveshapers) summed into a master DynamicsCompressor. Tab Player is
 * native, so the recipes are re-implemented here on equivalent primitives:
 * AudioParam-style automation (setValueAtTime / linear / exponential
 * ramps), RBJ-cookbook biquads (the formulas Web Audio uses), Fruity's
 * exact waveshaper curves, and a spec-level DynamicsCompressor with
 * Fruity's settings in the player mixer.
 *
 * Everything renders offline to FloatArrays at 44.1 kHz, one note at a
 * time, with fresh node state per note — no Android dependencies, so the
 * whole engine is unit-testable.
 */

// ---------------------------------------------------------------- Params

/** An AudioParam: a base automation curve plus summed a-rate inputs. */
class Param(private val initial: Double) {
    private class Ev(val time: Double, val value: Double, val kind: Int) // 0=set, 1=linear, 2=exp

    private val events = ArrayList<Ev>()

    /** Nodes whose output is added to this parameter (FM, LFO). */
    var inputs: List<AudioNode> = emptyList()

    fun setValueAtTime(v: Double, t: Double) { events.add(Ev(t, v, 0)) }
    fun linearRampToValueAtTime(v: Double, t: Double) { events.add(Ev(t, v, 1)) }
    fun exponentialRampToValueAtTime(v: Double, t: Double) { events.add(Ev(t, v, 2)) }

    /** Base (automation-only) value at [time] seconds. Events must be added in time order. */
    fun valueAt(time: Double): Double {
        var prevT = 0.0
        var prevV = initial
        var held = initial
        for (ev in events) {
            if (time < ev.time) {
                val span = ev.time - prevT
                if (span <= 0.0) return ev.value
                val k = (time - prevT) / span
                return when (ev.kind) {
                    1 -> prevV + (ev.value - prevV) * k
                    2 -> if (prevV == 0.0 || ev.value == 0.0 || (prevV < 0) != (ev.value < 0)) prevV
                         else prevV * (ev.value / prevV).pow(k)
                    else -> prevV
                }
            }
            held = ev.value; prevT = ev.time; prevV = ev.value
        }
        return held
    }

    /** Fill [out] with the parameter's curve (automation + summed inputs). */
    fun renderInto(n: Int, sr: Int, out: FloatArray) {
        for (i in 0 until n) out[i] = valueAt(i.toDouble() / sr).toFloat()
        for (inp in inputs) {
            val b = inp.render(n, sr)
            for (i in 0 until n) out[i] += b[i]
        }
    }
}

// ---------------------------------------------------------------- Nodes

abstract class AudioNode {
    private var cache: FloatArray? = null
    fun render(n: Int, sr: Int): FloatArray = cache ?: compute(n, sr).also { cache = it }
    protected abstract fun compute(n: Int, sr: Int): FloatArray
}

enum class Wave { SINE, SQUARE, SAWTOOTH, TRIANGLE }

fun waveSample(w: Wave, phase: Double): Double {
    val frac = phase - floor(phase)
    return when (w) {
        Wave.SINE -> sin(2 * PI * phase)
        // Phase-shifted so the wave starts at 0 rising (no start click).
        Wave.SAWTOOTH -> 2.0 * ((frac + 0.5) % 1.0) - 1.0
        Wave.TRIANGLE -> (2.0 / PI) * kotlin.math.asin(sin(2 * PI * phase))
        Wave.SQUARE -> if (frac < 0.5) 1.0 else -1.0
    }
}

class OscNode(val wave: Wave, val freq: Param, val detuneCents: Double = 0.0) : AudioNode() {
    override fun compute(n: Int, sr: Int): FloatArray {
        val out = FloatArray(n)
        val fc = FloatArray(n)
        freq.renderInto(n, sr, fc)
        val dm = 2.0.pow(detuneCents / 1200.0)
        var phase = 0.0
        for (i in 0 until n) {
            out[i] = waveSample(wave, phase).toFloat()
            phase += fc[i] * dm / sr
        }
        return out
    }
}

class NoiseNode(private val seed: Int) : AudioNode() {
    override fun compute(n: Int, sr: Int): FloatArray {
        val rnd = Random(seed)
        return FloatArray(n) { rnd.nextFloat() * 2f - 1f }
    }
}

class GainNode(val input: AudioNode, val gain: Param) : AudioNode() {
    override fun compute(n: Int, sr: Int): FloatArray {
        val src = input.render(n, sr)
        val gc = FloatArray(n)
        gain.renderInto(n, sr, gc)
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = src[i] * gc[i]
        return out
    }
}

enum class BiquadType { LOWPASS, HIGHPASS, BANDPASS, PEAKING, LOWSHELF, HIGHSHELF, ALLPASS }

/** RBJ Audio EQ Cookbook biquad (the design Web Audio's BiquadFilterNode uses), DF2T. */
class BiquadNode(
    val input: AudioNode,
    val type: BiquadType,
    val freq: Param,
    val q: Param = Param(1.0),
    val gainDb: Param = Param(0.0),
) : AudioNode() {
    override fun compute(n: Int, sr: Int): FloatArray {
        val src = input.render(n, sr)
        val out = FloatArray(n)
        val fc = FloatArray(n); freq.renderInto(n, sr, fc)
        val qc = FloatArray(n); q.renderInto(n, sr, qc)
        val gc = FloatArray(n); gainDb.renderInto(n, sr, gc)
        var z1 = 0.0
        var z2 = 0.0
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        var lastF = Double.NaN; var lastQ = Double.NaN; var lastG = Double.NaN
        for (i in 0 until n) {
            val f = fc[i].toDouble().coerceIn(1.0, sr * 0.49)
            val qq = qc[i].toDouble().coerceIn(0.01, 100.0)
            val g = gc[i].toDouble()
            if (abs(f - lastF) > 0.01 || abs(qq - lastQ) > 0.0001 || abs(g - lastG) > 0.001) {
                lastF = f; lastQ = qq; lastG = g
                val w0 = 2 * PI * f / sr
                val cw = cos(w0)
                val alpha = sin(w0) / (2 * qq)
                val a0inv: Double
                when (type) {
                    BiquadType.LOWPASS -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2; a0inv = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                    BiquadType.HIGHPASS -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2; a0inv = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                    BiquadType.BANDPASS -> { b0 = alpha; b1 = 0.0; b2 = -alpha; a0inv = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                    BiquadType.ALLPASS -> { b0 = 1 - alpha; b1 = -2 * cw; b2 = 1 + alpha; a0inv = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha }
                    BiquadType.PEAKING -> {
                        val a = 10.0.pow(g / 40.0)
                        b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a; a0inv = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
                    }
                    BiquadType.LOWSHELF -> {
                        val a = 10.0.pow(g / 40.0)
                        val s = 2 * kotlin.math.sqrt(a) * alpha
                        b0 = a * ((a + 1) - (a - 1) * cw + s); b1 = 2 * a * ((a - 1) - (a + 1) * cw); b2 = a * ((a + 1) - (a - 1) * cw - s)
                        a0inv = (a + 1) + (a - 1) * cw + s; a1 = -2 * ((a - 1) + (a + 1) * cw); a2 = (a + 1) + (a - 1) * cw - s
                    }
                    BiquadType.HIGHSHELF -> {
                        val a = 10.0.pow(g / 40.0)
                        val s = 2 * kotlin.math.sqrt(a) * alpha
                        b0 = a * ((a + 1) + (a - 1) * cw + s); b1 = -2 * a * ((a - 1) + (a + 1) * cw); b2 = a * ((a + 1) + (a - 1) * cw - s)
                        a0inv = (a + 1) - (a - 1) * cw + s; a1 = 2 * ((a - 1) - (a + 1) * cw); a2 = (a + 1) - (a - 1) * cw - s
                    }
                }
                b0 /= a0inv; b1 /= a0inv; b2 /= a0inv; a1 /= a0inv; a2 /= a0inv
            }
            val x = src[i].toDouble()
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            out[i] = y.toFloat()
        }
        return out
    }
}

/** WaveShaperNode: input clamped to [-1, 1] and mapped through the curve with linear interpolation. */
class ShaperNode(val input: AudioNode, val curve: FloatArray) : AudioNode() {
    override fun compute(n: Int, sr: Int): FloatArray {
        val src = input.render(n, sr)
        val out = FloatArray(n)
        val m = curve.size - 1
        for (i in 0 until n) {
            val x = src[i].coerceIn(-1f, 1f)
            val pos = (x + 1f) * 0.5f * m
            val i0 = pos.toInt().coerceIn(0, m - 1)
            val frac = pos - i0
            out[i] = curve[i0] + (curve[i0 + 1] - curve[i0]) * frac
        }
        return out
    }
}

class SumNode(val inputs: List<AudioNode>) : AudioNode() {
    override fun compute(n: Int, sr: Int): FloatArray {
        val out = FloatArray(n)
        for (inp in inputs) {
            val b = inp.render(n, sr)
            for (i in 0 until n) out[i] += b[i]
        }
        return out
    }
}

// ---------------------------------------------------------------- Curves (Fruity ports)

/** Fruity McLoops' makeDistortionCurve(amount), verbatim formula, 256 points. */
fun distortionCurve(amount: Double): FloatArray {
    val n = 256
    val curve = FloatArray(n)
    val k = amount * 2
    for (i in 0 until n) {
        val x = i * 2.0 / n - 1
        curve[i] = if (amount <= 0) x.toFloat()
            else (((3 + k) * x * 20 * (PI / 180)) / (PI + k * abs(x))).toFloat()
    }
    return curve
}

/** Fruity McLoops' makeCrushCurve(bits), verbatim formula, 256 points. */
fun crushCurve(bits: Int): FloatArray {
    val n = 256
    val curve = FloatArray(n)
    val levels = 2.0.pow(bits)
    for (i in 0 until n) {
        val x = i * 2.0 / n - 1
        curve[i] = (round(x * levels) / levels).toFloat()
    }
    return curve
}

// ---------------------------------------------------------------- Master compressor

/**
 * DynamicsCompressor with Fruity McLoops' master-chain settings
 * (threshold -14 dB, knee 22 dB, ratio 5, attack 0.004 s, release 0.18 s —
 * ensureCtx() in Fruity's index.html), implemented at the Web Audio spec
 * level: static knee curve on the detected level, then the gain reduction
 * is smoothed with the attack/release time constants. Streaming: keep one
 * instance per playback session.
 */
class MasterCompressor(
    val thresholdDb: Double = -14.0,
    val kneeDb: Double = 22.0,
    val ratio: Double = 5.0,
    val attackSec: Double = 0.004,
    val releaseSec: Double = 0.18,
    val sr: Int = 44100,
) {
    private var smoothedGrDb = 0.0

    fun reset() { smoothedGrDb = 0.0 }

    /** The smoothed gain reduction (dB, <= 0) currently applied — exposed for tests. */
    val currentGainReductionDb: Double get() = smoothedGrDb

    fun process(x: Float): Float {
        val ax = abs(x)
        val levelDb = if (ax > 1e-6f) 20.0 * log10(ax.toDouble()) else -120.0
        val t = thresholdDb
        val k = kneeDb
        val outDb = when {
            levelDb <= t - k / 2 -> levelDb
            levelDb >= t + k / 2 -> t + (levelDb - t) / ratio
            else -> {
                val d = levelDb - t + k / 2
                levelDb + (1.0 / ratio - 1.0) * d * d / (2 * k)
            }
        }
        val targetGr = outDb - levelDb
        val tau = if (targetGr < smoothedGrDb) attackSec else releaseSec
        val c = exp(-1.0 / (sr * tau))
        smoothedGrDb = c * smoothedGrDb + (1 - c) * targetGr
        val g = 10.0.pow(smoothedGrDb / 20.0)
        return (x * g).toFloat()
    }

    companion object {
        /** Run a fresh compressor over a whole buffer (tests, offline checks). */
        fun processBuffer(buf: FloatArray, sr: Int = 44100): FloatArray {
            val comp = MasterCompressor(sr = sr)
            val out = FloatArray(buf.size)
            for (i in buf.indices) out[i] = comp.process(buf[i])
            return out
        }
    }
}

/**
 * Final safety ceiling after the master compressor, in the player only:
 * identity below 0.95, then a tanh knee asymptoting to 1.0. A compressor
 * with a 4 ms attack cannot catch the first milliseconds of a sudden
 * six-note onset; this keeps those rare transients from hard-clipping
 * without touching anything at normal levels (unlike v1.0/v1.1, where a
 * saturator was the only thing between the mix and the output).
 */
fun softCeiling(x: Float): Float {
    val ax = abs(x)
    if (ax <= 0.95f) return x
    val y = 0.95f + 0.049f * tanh(((ax - 0.95f) / 0.05f).toDouble()).toFloat()
    return if (x < 0) -y else y
}
