package com.delukz.tabplayer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

enum class SoundFamily { GUITAR, BASS, DRUMS, KEYS }

val SOUND_FAMILY_LABELS = mapOf(
    SoundFamily.GUITAR to "Guitar",
    SoundFamily.BASS to "Bass",
    SoundFamily.DRUMS to "Drums",
    SoundFamily.KEYS to "Keyboard",
)

/** Five sound variants per instrument family. */
val SOUND_VARIANTS: Map<SoundFamily, List<String>> = mapOf(
    SoundFamily.GUITAR to listOf("Acoustic Steel", "Nylon Classic", "Clean Electric", "Distorted", "Muted Pluck"),
    SoundFamily.BASS to listOf("Fingered", "Picked", "Slap", "Fretless", "Synth Bass"),
    SoundFamily.DRUMS to listOf("Rock Kit", "Pop Kit", "Electronic Kit", "Jazz Kit", "Lo-Fi Kit"),
    SoundFamily.KEYS to listOf("Grand Piano", "Electric Piano", "Organ", "Music Box", "Synth Lead"),
)

fun defaultFamilyFor(kind: TrackKind): SoundFamily = when (kind) {
    TrackKind.GUITAR -> SoundFamily.GUITAR
    TrackKind.BASS -> SoundFamily.BASS
    TrackKind.DRUMS -> SoundFamily.DRUMS
    TrackKind.KEYS, TrackKind.OTHER -> SoundFamily.KEYS
}

fun midiToFreq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

/**
 * Pure synthesis: renders one note (or one drum hit) to a mono FloatArray.
 * No Android dependencies, so it is unit-testable.
 *
 * v1.2 audio rework — "use the same sound engine as Fruity McLoops".
 * Fruity is a Web Audio app: every sound is a recipe of oscillators,
 * biquad filters and gain envelopes summed into a master
 * DynamicsCompressor. v1.0/v1.1 used a hand-rolled Karplus-Strong synth
 * that Delukz still heard as harsh and distorted, so v1.2 replaces the
 * synth outright with ports of Fruity's actual recipes running on the
 * offline Web Audio primitives in Engine.kt:
 *
 *   Guitar  Acoustic Steel  <- Fruity "guitarpluck" (Keys)
 *           Nylon Classic   <- guitarpluck, darker/quieter layers (documented tweak)
 *           Clean Electric  <- Fruity "pluck" (Lead Pluck)
 *           Distorted       <- Fruity "superpluck" stack through Fruity's distortion curve
 *           Muted Pluck     <- guitarpluck with decays x0.16 and filters x0.55 (documented tweak)
 *   Bass    Fingered        <- Fruity "pluckbass"
 *           Picked          <- pluckbass with a brighter sweep + pick noise (documented tweak)
 *           Slap            <- pluckbass (hotter sweep) + a sine pitch-drop thump
 *           Fretless        <- Fruity "vintagebass" + a short pitch glide-in (documented tweak)
 *           Synth Bass      <- Fruity "acidbass" (Acid Bass 303)
 *   Keys    Grand Piano     <- Fruity "epiano" FM pair, index lowered, longer decay (documented tweak)
 *           Electric Piano  <- Fruity "epiano"
 *           Organ           <- Fruity "organ" (Drawbar Organ)
 *           Music Box       <- Fruity "musicbox"
 *           Synth Lead      <- Fruity "squarelead"
 *   Drums   kits assembled from Fruity's kick / snare / clap / hat /
 *           openhat / tom / crash and the v2-expansion deepkick / kick2 /
 *           lofikick / snare2 / noisesnare / brushsnare / clapstack /
 *           electrotom / cowbell / stick / shaker / pedalhat recipes.
 *
 * Fruity is a step sequencer whose pluck recipes have fixed decays, so
 * where a recipe's decay is fixed the port scales it toward the note's
 * written duration (long notes ring longer); peaks, filter frequencies,
 * Q values, envelope shapes and the distortion curve are Fruity's own.
 * Per-note peak normalization (target per family) is kept as a backstop,
 * and the player mixer now runs Fruity's master compressor settings.
 */
object Synth {
    const val SAMPLE_RATE = 44100

    /** Technique shaping for one note, derived from NoteEffects by the player. */
    data class FxSpec(
        val palmMute: Boolean = false,
        val harmonic: Boolean = false,
        val bendSemitones: Float = 0f,
        val bendRelease: Boolean = false,
        val prebend: Boolean = false,
        val slideFromSemitones: Float = 0f, // start offset, glides to 0 (slide into the note)
        val slideToSemitones: Float = 0f,   // glides toward this offset at the end (slide to next note)
        val vibrato: Boolean = false,
        val staccato: Boolean = false,
        val softAttack: Boolean = false,   // hammer-on / legato target: gentler attack
    )

    /** Recipe-level shaping derived from FxSpec / dead-note state. */
    private data class Shape(
        val decayScale: Double = 1.0,  // <1 shortens all decays (palm mute, dead note)
        val filterScale: Double = 1.0, // <1 darkens filters (palm mute, dead note)
        val noiseScale: Double = 1.0,  // <1 quiets pick/attack noise (legato targets)
        val attackSec: Double = 0.0,   // extra fade-in (legato targets)
    )

    fun renderNote(family: SoundFamily, variant: Int, pitch: Int, velocity: Int,
                   durationSec: Double, dead: Boolean = false, fx: FxSpec = FxSpec()): FloatArray {
        val vel = velocity.coerceIn(1, 127) / 127f
        if (family == SoundFamily.DRUMS) return renderDrum(variant, pitch, vel * 0.9f)
        val effPitch = if (fx.harmonic) pitch + 12 else pitch
        var dur = durationSec
        if (fx.staccato) dur = minOf(dur, maxOf(0.09, durationSec * 0.45))
        if (fx.palmMute && family == SoundFamily.KEYS) dur = minOf(dur, 0.35)
        val shape = Shape(
            decayScale = when {
                dead -> 0.12
                fx.palmMute -> 0.30
                else -> 1.0
            },
            filterScale = if (fx.palmMute || dead) 0.55 else 1.0,
            noiseScale = if (fx.softAttack) 0.3 else 1.0,
            attackSec = if (fx.softAttack) 0.02 else 0.0,
        )
        val needsGlide = fx.bendSemitones > 0.01f || fx.prebend ||
            abs(fx.slideFromSemitones) > 0.01f || abs(fx.slideToSemitones) > 0.01f || fx.vibrato
        // Render a little extra material when a pitch contour will consume it faster.
        val renderDur = if (needsGlide) dur * 1.3 + 0.05 else dur
        val f = midiToFreq(effPitch)
        val voice = when (family) {
            SoundFamily.GUITAR -> guitarVoice(variant, f, renderDur, shape)
            SoundFamily.BASS -> bassVoice(variant, f, renderDur, shape)
            SoundFamily.KEYS -> keysVoice(variant, f, renderDur, shape)
            SoundFamily.DRUMS -> error("handled above")
        }
        val n = (voice.seconds * SAMPLE_RATE).toInt().coerceIn(256, SAMPLE_RATE * 8)
        var buf = voice.node.render(n, SAMPLE_RATE)
        if (fx.harmonic) buf = scale(buf, 0.6f)
        if (needsGlide) buf = applyPitchContour(buf, fx, dur)
        microFades(buf, shape.attackSec)
        val target = when (family) {
            SoundFamily.GUITAR -> 0.80f
            SoundFamily.BASS -> 0.85f
            else -> 0.78f
        } * (0.30f + 0.70f * vel)
        return normalize(buf, target)
    }

    // ---------- Voice assembly ----------

    private class Voice(val node: AudioNode, val seconds: Double, val offset: Double = 0.0) {
        fun delayed(at: Double): Voice = Voice(node, seconds, at)
    }

    private fun sumVoices(vararg voices: Voice): Voice {
        val total = voices.maxOf { it.offset + it.seconds }
        val comp = object : AudioNode() {
            override fun compute(n: Int, sr: Int): FloatArray {
                val out = FloatArray(n)
                for (v in voices) {
                    val len = (v.seconds * sr).toInt()
                    val b = v.node.render(len, sr)
                    val off = (v.offset * sr).toInt()
                    for (i in b.indices) {
                        val j = off + i
                        if (j in 0 until n) out[j] += b[i]
                    }
                }
                return out
            }
        }
        return Voice(comp, total)
    }

    /** Fruity's envGain(): gain starts at peak and exponential-ramps to 0.001 over decay. */
    private fun envGainVoice(input: AudioNode, peak: Double, decay: Double): Voice {
        val g = Param(1.0)
        g.setValueAtTime(peak, 0.0)
        g.exponentialRampToValueAtTime(0.001, decay)
        return Voice(GainNode(input, g), decay + 0.1)
    }

    /** Gain envelope shared by the _tone/_noise ports (attack ramp) and envGain-style hits (instant). */
    private fun gainEnv(peak: Double, dur: Double, attack: Double, instant: Boolean): Param {
        val g = Param(1.0)
        if (instant) {
            g.setValueAtTime(peak, 0.0)
            g.exponentialRampToValueAtTime(0.001, dur)
        } else {
            g.setValueAtTime(0.0001, 0.0)
            g.linearRampToValueAtTime(peak, attack)
            g.exponentialRampToValueAtTime(0.001, dur)
        }
        return g
    }

    /** Port of Fruity's _tone() helper. */
    private fun tone(
        type: Wave, f0: Double, f1: Double? = null, ft: Double? = null,
        dur: Double, peak: Double, a: Double = 0.005,
        lp: Double? = null, q: Double? = null,
        instant: Boolean = false, glideFrom: Double? = null,
    ): Voice {
        val freq = Param(f0 * (glideFrom ?: 1.0))
        if (glideFrom != null && glideFrom != 1.0) freq.exponentialRampToValueAtTime(f0, 0.07)
        if (f1 != null) freq.exponentialRampToValueAtTime(f1, ft ?: dur)
        var node: AudioNode = OscNode(type, freq)
        if (lp != null) node = BiquadNode(node, BiquadType.LOWPASS, Param(lp), Param(q ?: 1.0))
        return Voice(GainNode(node, gainEnv(peak, dur, a, instant)), dur + 0.05)
    }

    /** Port of Fruity's _noise() helper (bandpass by default). */
    private fun noiseHit(
        type: BiquadType = BiquadType.BANDPASS, freq: Double, f1: Double? = null, q: Double = 1.0,
        dur: Double, peak: Double, a: Double = 0.004, instant: Boolean = false, seedSalt: Int = 0,
    ): Voice {
        val fp = Param(freq)
        if (f1 != null) fp.exponentialRampToValueAtTime(f1, dur)
        val seed = (freq * 7 + dur * 1000).toInt() * 31 + type.ordinal + seedSalt * 1013
        val flt = BiquadNode(NoiseNode(seed), type, fp, Param(q))
        return Voice(GainNode(flt, gainEnv(peak, dur, a, instant)), if (instant) dur + 0.1 else dur + 0.05)
    }

    /** Port of Fruity's _fm() helper (sine carrier, sine modulator, decaying index). */
    private fun fm(freq: Double, ratio: Double, index: Double, dur: Double, peak: Double, a: Double = 0.005): Voice {
        val mod = OscNode(Wave.SINE, Param(freq * ratio))
        val mg = Param(freq * index)
        mg.setValueAtTime(freq * index, 0.0)
        mg.exponentialRampToValueAtTime(1.0, dur)
        val carFreq = Param(freq)
        carFreq.inputs = listOf(GainNode(mod, mg))
        val car = OscNode(Wave.SINE, carFreq)
        return Voice(GainNode(car, gainEnv(peak, dur, a, instant = false)), dur + 0.05)
    }

    /**
     * Pluck decay for tab playback: Fruity's fixed recipe decay, stretched
     * toward the written note duration (long notes ring longer), then
     * scaled. [capShortened] (palm mute / dead note shaping) hard-caps the
     * result at 0.35 s; recipe-variant tweaks under 1.0 do not cap.
     */
    private fun ringDur(recipeBase: Double, dur: Double, decayScale: Double, capShortened: Boolean = decayScale < 1.0): Double {
        var d = maxOf(recipeBase, minOf(dur * 0.95, recipeBase * 5))
        d *= decayScale
        if (capShortened) d = minOf(d, 0.35)
        return maxOf(d, 0.03)
    }

    /** Note duration for the duration-driven bass/keys recipes, with palm/dead shaping applied. */
    private fun shapedDur(dur: Double, shape: Shape, floor: Double = 0.30): Double {
        var d = maxOf(dur, floor)
        if (shape.decayScale < 1.0) d = minOf(d * shape.decayScale, 0.35)
        return maxOf(d, 0.05)
    }

    // ---------- Guitar (Fruity pluck family) ----------

    private fun guitarVoice(variant: Int, f: Double, dur: Double, shape: Shape): Voice = when (variant) {
        0 -> guitarPluckVoice(f, dur, shape, triLp = 2400.0, sawLp = 1800.0, sawPeak = 0.16, noisePeak = 0.18, decayScale = 1.0)
        1 -> guitarPluckVoice(f, dur, shape, triLp = 1350.0, sawLp = 1050.0, sawPeak = 0.10, noisePeak = 0.08, decayScale = 0.9)
        2 -> cleanElectricVoice(f, dur, shape)
        3 -> distortedVoice(f, dur, shape)
        else -> guitarPluckVoice(f, dur, shape, triLp = 2400.0, sawLp = 1800.0, sawPeak = 0.16, noisePeak = 0.22, decayScale = 0.16)
    }

    /** Fruity "guitarpluck": triangle body + short saw attack layer + pick noise. */
    private fun guitarPluckVoice(
        f: Double, dur: Double, shape: Shape,
        triLp: Double, sawLp: Double, sawPeak: Double, noisePeak: Double, decayScale: Double,
    ): Voice {
        val ds = decayScale * shape.decayScale
        val fs = shape.filterScale
        val ring = ringDur(0.52, dur, ds, capShortened = shape.decayScale < 1.0)
        return sumVoices(
            tone(Wave.TRIANGLE, f, dur = ring, peak = 0.55, lp = triLp * fs),
            tone(Wave.SAWTOOTH, f, dur = 0.14 * maxOf(ds, 0.16), peak = sawPeak, lp = sawLp * fs),
            noiseHit(freq = 1800.0, dur = 0.025, peak = noisePeak * shape.noiseScale),
        )
    }

    /** Fruity "pluck" (Lead Pluck): triangle through a closing lowpass, Q 4. */
    private fun cleanElectricVoice(f: Double, dur: Double, shape: Shape): Voice {
        val fs = shape.filterScale
        val fltFreq = Param(3400.0 * fs)
        fltFreq.exponentialRampToValueAtTime(420.0 * fs, 0.22)
        val flt = BiquadNode(OscNode(Wave.TRIANGLE, Param(f)), BiquadType.LOWPASS, fltFreq, Param(4.0))
        return envGainVoice(flt, 0.55, ringDur(0.26, dur, shape.decayScale))
    }

    /** Fruity "superpluck" detuned saw stack, driven through Fruity's distortion curve (as in "growl"/the tone chain). */
    private fun distortedVoice(f: Double, dur: Double, shape: Shape): Voice {
        val fs = shape.filterScale
        val stack = SumNode(listOf(-9.0, 0.0, 9.0).map { OscNode(Wave.SAWTOOTH, Param(f), it) })
        val shaper = ShaperNode(stack, distortionCurve(45.0))
        val fltFreq = Param(3800.0 * fs)
        fltFreq.exponentialRampToValueAtTime(500.0 * fs, 0.25)
        val flt = BiquadNode(shaper, BiquadType.LOWPASS, fltFreq, Param(3.0))
        return envGainVoice(flt, 0.5, ringDur(0.28, dur, shape.decayScale))
    }

    // ---------- Bass (Fruity bass recipes) ----------

    private fun bassVoice(variant: Int, f: Double, dur: Double, shape: Shape): Voice = when (variant) {
        0 -> pluckBassVoice(f, dur, shape, startFreq = 1900.0, endFreq = 190.0)
        1 -> sumVoices(
            pluckBassVoice(f, dur, shape, startFreq = 3200.0, endFreq = 300.0),
            noiseHit(freq = 1800.0, dur = 0.02, peak = 0.12 * shape.noiseScale),
        )
        2 -> sumVoices(
            pluckBassVoice(f, dur, shape, startFreq = 2800.0, endFreq = 220.0, durMul = 0.75),
            tone(Wave.SINE, f * 0.5, f1 = f * 0.25, ft = 0.07, dur = 0.14, peak = 0.5),
        )
        3 -> {
            val d = shapedDur(dur, shape)
            sumVoices(
                tone(Wave.TRIANGLE, f, dur = d, peak = 0.55, lp = 1200.0 * shape.filterScale, glideFrom = 0.94),
                tone(Wave.SINE, f / 2, dur = d, peak = 0.4, glideFrom = 0.94),
            )
        }
        else -> acidBassVoice(f, dur, shape)
    }

    /** Fruity "pluckbass": triangle through a lowpass sweeping 1900 -> 190 over the note, Q 5. */
    private fun pluckBassVoice(
        f: Double, dur: Double, shape: Shape,
        startFreq: Double, endFreq: Double, durMul: Double = 1.0,
    ): Voice {
        val fs = shape.filterScale
        val d = shapedDur(dur, shape) * durMul
        val fltFreq = Param(startFreq * fs)
        fltFreq.exponentialRampToValueAtTime(endFreq * fs, d)
        val flt = BiquadNode(OscNode(Wave.TRIANGLE, Param(f)), BiquadType.LOWPASS, fltFreq, Param(5.0))
        return envGainVoice(flt, 0.7, d)
    }

    /** Fruity "acidbass": sawtooth through a resonant lowpass sweeping 2900 -> 240, Q 13. */
    private fun acidBassVoice(f: Double, dur: Double, shape: Shape): Voice {
        val fs = shape.filterScale
        val d = shapedDur(dur, shape)
        val fltFreq = Param(2900.0 * fs)
        fltFreq.exponentialRampToValueAtTime(240.0 * fs, d * 0.8)
        val flt = BiquadNode(OscNode(Wave.SAWTOOTH, Param(f)), BiquadType.LOWPASS, fltFreq, Param(13.0))
        val g = Param(1.0)
        g.setValueAtTime(0.0001, 0.0)
        g.linearRampToValueAtTime(0.6, 0.01)
        g.exponentialRampToValueAtTime(0.001, d)
        return Voice(GainNode(flt, g), d + 0.05)
    }

    // ---------- Keyboard (Fruity keys recipes) ----------

    private fun keysVoice(variant: Int, f: Double, dur: Double, shape: Shape): Voice = when (variant) {
        // Fruity "epiano" FM pair; Grand Piano lowers the FM index and stretches the decay.
        0 -> sumVoices(
            fm(f, 1.0, 2.2, shapedDur(maxOf(dur * 1.2, 0.6), shape, floor = 0.6), 0.42),
            fm(f, 14.07, 0.7, 0.18, 0.10),
        )
        1 -> sumVoices(
            fm(f, 1.0, 3.2, shapedDur(dur, shape, floor = 0.6), 0.42),
            fm(f, 14.07, 0.7, 0.18, 0.10),
        )
        2 -> organVoice(f, dur, shape)
        3 -> sumVoices(
            fm(f, 3.98, 1.1, maxOf(0.95, minOf(dur, 2.5)), 0.28),
            tone(Wave.SINE, f * 2, dur = 0.3, peak = 0.06),
        )
        else -> synthLeadVoice(f, dur, shape)
    }

    /** Fruity "organ" (Drawbar Organ): four sine partials (1x/2x/3x/4x) under a hold-and-release gain. */
    private fun organVoice(f: Double, dur: Double, shape: Shape): Voice {
        val d = shapedDur(dur, shape)
        val g = Param(1.0)
        g.setValueAtTime(0.0001, 0.0)
        g.linearRampToValueAtTime(0.3, 0.015)
        g.setValueAtTime(0.3, maxOf(0.02, d - 0.08))
        g.exponentialRampToValueAtTime(0.001, d)
        val partials = listOf(1.0 to 0.5, 2.0 to 0.32, 3.0 to 0.2, 4.0 to 0.13)
        val sum = SumNode(partials.map { (m, a) -> GainNode(OscNode(Wave.SINE, Param(f * m)), Param(a)) })
        return Voice(GainNode(sum, g), d + 0.05)
    }

    /** Fruity "squarelead": square wave with a 5.2 Hz vibrato LFO through a 2900 Hz lowpass. */
    private fun synthLeadVoice(f: Double, dur: Double, shape: Shape): Voice {
        val d = shapedDur(dur, shape)
        val lfoAmt = GainNode(OscNode(Wave.SINE, Param(5.2)), Param(7.0))
        val freqP = Param(f)
        freqP.inputs = listOf(lfoAmt)
        val osc = OscNode(Wave.SQUARE, freqP)
        val flt = BiquadNode(osc, BiquadType.LOWPASS, Param(2900.0 * shape.filterScale), Param(1.0))
        val g = Param(1.0)
        g.setValueAtTime(0.0001, 0.0)
        g.linearRampToValueAtTime(0.32, 0.02)
        g.exponentialRampToValueAtTime(0.001, d)
        return Voice(GainNode(flt, g), d + 0.05)
    }

    // ---------- Drums (Fruity drum recipes, GM-mapped) ----------

    private enum class DrumPiece { KICK, SNARE, CLAP, HAT_CLOSED, HAT_OPEN, TOM, CRASH, RIDE, COWBELL, SHAKER, STICK, UNKNOWN }

    private fun pieceFor(pitch: Int): DrumPiece = when (pitch) {
        35, 36 -> DrumPiece.KICK
        38, 40 -> DrumPiece.SNARE
        37 -> DrumPiece.STICK
        39 -> DrumPiece.CLAP
        42, 44 -> DrumPiece.HAT_CLOSED
        46 -> DrumPiece.HAT_OPEN
        41, 43, 45, 47, 48, 50 -> DrumPiece.TOM
        49, 52, 57, 55 -> DrumPiece.CRASH
        51, 53, 59 -> DrumPiece.RIDE
        56 -> DrumPiece.COWBELL
        70, 69, 82 -> DrumPiece.SHAKER
        else -> if (pitch % 2 == 0) DrumPiece.SNARE else DrumPiece.TOM
    }

    fun renderDrum(kit: Int, pitch: Int, gain: Float): FloatArray {
        val voice = drumVoice(kit, pieceFor(pitch), pitch)
        val n = (voice.seconds * SAMPLE_RATE).toInt().coerceAtLeast(256)
        val buf = voice.node.render(n, SAMPLE_RATE)
        if (kit == 4) shapeBuffer(buf, crushCurve(5)) // Lo-Fi kit runs through Fruity's crush curve
        microFades(buf, 0.0)
        // Keep every hit at a known ceiling so kit pieces can't stack into clipping.
        return normalize(buf, 0.85f * (gain / 0.9f).coerceIn(0.15f, 1f))
    }

    private fun drumVoice(kit: Int, piece: DrumPiece, pitch: Int): Voice = when (piece) {
        DrumPiece.KICK -> when (kit) {
            2 -> sumVoices( // Fruity "kick2" (Electro Kick)
                tone(Wave.SINE, 162.0, f1 = 58.0, ft = 0.09, dur = 0.3, peak = 0.95),
                tone(Wave.TRIANGLE, 820.0, dur = 0.018, peak = 0.35),
            )
            3 -> sumVoices( // Fruity "deepkick"
                tone(Wave.SINE, 118.0, f1 = 36.0, ft = 0.14, dur = 0.42, peak = 1.0),
                noiseHit(BiquadType.HIGHPASS, 3000.0, dur = 0.02, peak = 0.25),
            )
            4 -> sumVoices( // Fruity "lofikick"
                tone(Wave.SINE, 120.0, f1 = 45.0, ft = 0.1, dur = 0.3, peak = 0.9, lp = 900.0),
                noiseHit(BiquadType.LOWPASS, 2400.0, dur = 0.05, peak = 0.12),
            )
            else -> { // Fruity "kick"
                val freq = Param(155.0)
                freq.exponentialRampToValueAtTime(42.0, 0.11)
                envGainVoice(OscNode(Wave.SINE, freq), 1.0, 0.27)
            }
        }
        DrumPiece.SNARE -> when (kit) {
            1, 4 -> sumVoices( // Fruity "snare2" (tight)
                noiseHit(BiquadType.BANDPASS, 2400.0, q = 0.7, dur = 0.11, peak = 0.65),
                tone(Wave.TRIANGLE, 242.0, f1 = 170.0, ft = 0.05, dur = 0.07, peak = 0.4),
            )
            2 -> sumVoices( // Fruity "noisesnare"
                noiseHit(BiquadType.BANDPASS, 1000.0, f1 = 3200.0, q = 0.6, dur = 0.3, peak = 0.6),
                noiseHit(BiquadType.HIGHPASS, 6000.0, dur = 0.18, peak = 0.3),
            )
            3 -> sumVoices( // Fruity "brushsnare"
                noiseHit(BiquadType.BANDPASS, 3200.0, f1 = 1400.0, q = 0.6, dur = 0.34, peak = 0.5, a = 0.05),
                tone(Wave.TRIANGLE, 210.0, dur = 0.06, peak = 0.2),
            )
            else -> sumVoices( // Fruity "snare"
                noiseHit(BiquadType.BANDPASS, 1800.0, q = 0.8, dur = 0.16, peak = 0.7, instant = true),
                tone(Wave.TRIANGLE, 192.0, dur = 0.09, peak = 0.45, instant = true),
            )
        }
        DrumPiece.CLAP -> when (kit) {
            1, 4 -> sumVoices( // Fruity "clapstack": four bandpassed noise bursts
                noiseHit(BiquadType.BANDPASS, 1300.0, q = 1.8, dur = 0.07, peak = 0.5),
                noiseHit(BiquadType.BANDPASS, 1300.0, q = 1.8, dur = 0.07, peak = 0.5, seedSalt = 1).delayed(0.03),
                noiseHit(BiquadType.BANDPASS, 1300.0, q = 1.8, dur = 0.07, peak = 0.5, seedSalt = 2).delayed(0.07),
                noiseHit(BiquadType.BANDPASS, 1300.0, q = 1.8, dur = 0.3, peak = 0.5, seedSalt = 3).delayed(0.12),
            )
            else -> clapVoice() // Fruity "clap": three pulses + tail through a 1200 Hz bandpass
        }
        DrumPiece.HAT_CLOSED -> when (kit) {
            2 -> sumVoices( // Fruity "pedalhat"
                noiseHit(BiquadType.HIGHPASS, 9200.0, dur = 0.035, peak = 0.3),
                tone(Wave.SQUARE, 10400.0, dur = 0.02, peak = 0.08),
            )
            3 -> hatVoice(open = false, hpFreq = 8200.0, peakScale = 0.7)
            4 -> hatVoice(open = false, hpFreq = 6000.0, peakScale = 1.0)
            else -> hatVoice(open = false, hpFreq = 8200.0, peakScale = 1.0) // Fruity "hat"
        }
        DrumPiece.HAT_OPEN -> when (kit) {
            3 -> hatVoice(open = true, hpFreq = 7600.0, peakScale = 0.8)
            4 -> hatVoice(open = true, hpFreq = 6000.0, peakScale = 1.0)
            else -> hatVoice(open = true, hpFreq = 7600.0, peakScale = 1.0) // Fruity "openhat"
        }
        DrumPiece.TOM -> {
            val mul = when (pitch) {
                41 -> 0.55; 43 -> 0.62; 45 -> 0.72; 47 -> 0.82; 48 -> 0.90; 50 -> 1.0
                else -> 0.8
            }
            if (kit == 2) { // Fruity "electrotom", scaled by tom pitch
                sumVoices(
                    tone(Wave.SQUARE, 302.0 * mul, f1 = 88.0 * mul, ft = 0.22, dur = 0.32, peak = 0.4, lp = 1600.0),
                    tone(Wave.SINE, 302.0 * mul, f1 = 88.0 * mul, ft = 0.22, dur = 0.32, peak = 0.4),
                )
            } else { // Fruity "tom"
                tone(Wave.SINE, 196.0 * mul, f1 = 88.0 * mul, ft = 0.18, dur = 0.26, peak = 0.8, instant = true)
            }
        }
        DrumPiece.CRASH -> sumVoices( // Fruity "crash": two highpassed noise layers
            noiseHit(BiquadType.HIGHPASS, 5800.0, dur = 1.25, peak = 0.5, instant = true),
            noiseHit(BiquadType.HIGHPASS, 9500.0, dur = 0.7, peak = 0.22, instant = true),
        )
        DrumPiece.RIDE -> sumVoices(
            // Fruity has no ride recipe; approximated from its "tambourine"
            // metallic partials over a highpassed noise wash (documented).
            noiseHit(BiquadType.HIGHPASS, 7600.0, dur = 0.5, peak = 0.28),
            tone(Wave.SQUARE, 5230.0, dur = 0.3, peak = 0.04),
            tone(Wave.SQUARE, 6640.0, dur = 0.25, peak = 0.03),
        )
        DrumPiece.COWBELL -> { // Fruity "cowbell": two squares through a 720 Hz bandpass
            val bp = BiquadNode(
                SumNode(listOf(OscNode(Wave.SQUARE, Param(540.0)), OscNode(Wave.SQUARE, Param(845.0)))),
                BiquadType.BANDPASS, Param(720.0), Param(1.1),
            )
            envGainVoice(bp, 0.5, 0.26)
        }
        DrumPiece.SHAKER -> if (pitch == 69) {
            noiseHit(BiquadType.HIGHPASS, 6200.0, dur = 0.07, peak = 0.42)
        } else { // Fruity "shaker": two hits
            sumVoices(
                noiseHit(BiquadType.HIGHPASS, 6200.0, dur = 0.07, peak = 0.42),
                noiseHit(BiquadType.HIGHPASS, 6200.0, dur = 0.07, peak = 0.3, seedSalt = 1).delayed(0.085),
            )
        }
        DrumPiece.STICK -> sumVoices( // Fruity "stick" (Cross Stick)
            tone(Wave.SQUARE, 905.0, dur = 0.032, peak = 0.42),
            noiseHit(BiquadType.HIGHPASS, 5000.0, dur = 0.02, peak = 0.2),
        )
        DrumPiece.UNKNOWN -> noiseHit(BiquadType.HIGHPASS, 4000.0, dur = 0.1, peak = 0.4)
    }

    /** Fruity "hat"/"openhat": highpassed noise under an envGain decay. */
    private fun hatVoice(open: Boolean, hpFreq: Double, peakScale: Double): Voice =
        noiseHit(
            BiquadType.HIGHPASS, hpFreq,
            dur = if (open) 0.42 else 0.05,
            peak = (if (open) 0.4 else 0.42) * peakScale,
            instant = true,
        )

    /** Fruity "clap": bandpassed noise with three attack pulses and a tail, via raw gain automation. */
    private fun clapVoice(): Voice {
        val bp = BiquadNode(NoiseNode(1237), BiquadType.BANDPASS, Param(1200.0), Param(1.6))
        val g = Param(1.0)
        g.setValueAtTime(0.0, 0.0)
        for (off in listOf(0.0, 0.021, 0.042)) {
            g.setValueAtTime(0.85, off)
            g.exponentialRampToValueAtTime(0.25, off + 0.018)
        }
        g.setValueAtTime(0.85, 0.063)
        g.exponentialRampToValueAtTime(0.001, 0.32)
        return Voice(GainNode(bp, g), 0.36)
    }

    // ---------- Shared helpers ----------

    /** Short fade at both ends of a rendered note so note boundaries never click. */
    private fun microFades(buf: FloatArray, attackSec: Double) {
        val fadeIn = maxOf(24, (attackSec * SAMPLE_RATE).toInt()).coerceAtMost(buf.size / 2)
        for (i in 0 until fadeIn) buf[i] *= i.toFloat() / fadeIn
        val fadeOut = minOf(256, buf.size / 6)
        for (i in 0 until fadeOut) {
            val idx = buf.size - 1 - i
            buf[idx] *= i.toFloat() / fadeOut
        }
    }

    private fun shapeBuffer(buf: FloatArray, curve: FloatArray) {
        val m = curve.size - 1
        for (i in buf.indices) {
            val x = buf[i].coerceIn(-1f, 1f)
            val pos = (x + 1f) * 0.5f * m
            val i0 = pos.toInt().coerceIn(0, m - 1)
            val frac = pos - i0
            buf[i] = curve[i0] + (curve[i0 + 1] - curve[i0]) * frac
        }
    }

    /** Resample [buf] with a time-varying rate to impose bend/slide/vibrato pitch contours. */
    private fun applyPitchContour(buf: FloatArray, fx: FxSpec, nominalDurSec: Double): FloatArray {
        val sr = SAMPLE_RATE
        // Keep up to 0.6 s of ring-out past the written duration; contour timing
        // is still measured against the written duration.
        val n = minOf(buf.size, ((nominalDurSec + 0.6) * sr).toInt()).coerceAtLeast(256)
        val out = FloatArray(n)
        val totalT = maxOf(nominalDurSec, 0.05)
        var pos = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val rate = 2.0.pow(pitchOffsetAt(fx, t, totalT) / 12.0)
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = if (i0 in buf.indices) buf[i0] else 0f
            val b = if (i0 + 1 in buf.indices) buf[i0 + 1] else 0f
            out[i] = a + (b - a) * frac
            pos += rate
        }
        return out
    }

    /** Pitch offset in semitones at time [t] of a note lasting [totalT] seconds. */
    private fun pitchOffsetAt(fx: FxSpec, t: Double, totalT: Double): Double {
        var off = 0.0
        if (abs(fx.slideFromSemitones) > 0.01f) {
            val glideLen = minOf(0.18, totalT * 0.3)
            off += fx.slideFromSemitones * (1.0 - smoothstep(0.0, glideLen, t))
        }
        if (fx.bendSemitones > 0.01f) {
            val b = fx.bendSemitones.toDouble()
            off += if (fx.prebend) {
                if (fx.bendRelease) b * (1.0 - smoothstep(totalT * 0.25, totalT * 0.8, t)) else b
            } else {
                var v = b * smoothstep(totalT * 0.12, totalT * 0.45, t)
                if (fx.bendRelease) v *= 1.0 - smoothstep(totalT * 0.7, totalT * 0.95, t)
                v
            }
        }
        if (abs(fx.slideToSemitones) > 0.01f) {
            off += fx.slideToSemitones * smoothstep(totalT * 0.62, totalT * 0.97, t)
        }
        if (fx.vibrato && t > 0.12) {
            val depth = 0.13 * minOf(1.0, (t - 0.12) * 3.0)
            off += depth * sin(2 * PI * 5.2 * (t - 0.12))
        }
        return off
    }

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        if (b <= a) return if (x >= b) 1.0 else 0.0
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun scale(buf: FloatArray, k: Float): FloatArray {
        for (i in buf.indices) buf[i] *= k
        return buf
    }

    /** Scale so the peak absolute value equals [targetPeak] (no-op if silent). */
    private fun normalize(buf: FloatArray, targetPeak: Float): FloatArray {
        var peak = 0f
        for (s in buf) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        if (peak > 1e-6f) {
            val k = targetPeak / peak
            for (i in buf.indices) buf[i] *= k
        }
        return buf
    }
}
