package com.delukz.tabplayer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

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
 * v1.1 audio rework: the v1.0 guitar sound was harsh because (a) raw white
 * noise KS excitation put a burst of high-frequency fizz on every note,
 * (b) loop brightness ~0.999 made high partials ring almost undamped,
 * (c) no note was ever peak-normalized, so overlapping ringing voices drove
 * the mixer's tanh into constant saturation. Now the excitation is
 * lowpassed per variant, the KS loop gain is derived from a target decay
 * time, and every rendered note is peak-normalized to a known level so the
 * mixer stays in its linear range.
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

    fun renderNote(family: SoundFamily, variant: Int, pitch: Int, velocity: Int,
                   durationSec: Double, dead: Boolean = false, fx: FxSpec = FxSpec()): FloatArray {
        val vel = velocity.coerceIn(1, 127) / 127f
        if (family == SoundFamily.DRUMS) return renderDrum(variant, pitch, vel * 0.9f)
        val effPitch = if (fx.harmonic) pitch + 12 else pitch
        var dur = durationSec
        if (fx.staccato) dur = minOf(dur, maxOf(0.09, durationSec * 0.45))
        if (fx.palmMute && family == SoundFamily.KEYS) dur = minOf(dur, 0.35)
        val needsGlide = fx.bendSemitones > 0.01f || fx.prebend ||
            abs(fx.slideFromSemitones) > 0.01f || abs(fx.slideToSemitones) > 0.01f || fx.vibrato
        // Render a little extra material when a pitch contour will consume it faster.
        val renderDur = if (needsGlide) dur * 1.3 + 0.05 else dur
        var buf = when (family) {
            SoundFamily.GUITAR -> renderPluck(effPitch, vel, renderDur, guitarParams(variant), dead, fx)
            SoundFamily.BASS -> renderBass(variant, effPitch, vel, renderDur, dead, fx)
            SoundFamily.KEYS -> renderKeys(variant, effPitch, vel, renderDur)
            SoundFamily.DRUMS -> error("handled above")
        }
        if (fx.harmonic) buf = scale(buf, 0.6f)
        if (needsGlide) buf = applyPitchContour(buf, fx, dur)
        return buf
    }

    // ---------- Plucked strings (Karplus-Strong) ----------

    private data class PluckParams(
        val decayTime: Double,       // seconds for the fundamental to fall ~ -60 dB
        // Length of the circular moving-average applied to the noise burst
        // excitation: 1 = raw noise (harsh), larger = darker/softer attack.
        val excitationBox: Int,
        val distortion: Double,      // drive amount; 0 = clean (only the Distorted variant uses it)
        val targetPeak: Float,       // peak after normalization at velocity 127
    )

    private fun guitarParams(variant: Int): PluckParams = when (variant) {
        0 -> PluckParams(2.4, 3, 0.0, 0.80f)  // Acoustic Steel: bright pluck, moderate decay
        1 -> PluckParams(1.7, 14, 0.0, 0.78f) // Nylon Classic: soft, warm, highs die fast
        2 -> PluckParams(3.2, 6, 0.0, 0.80f)  // Clean Electric: smooth, long sustain, no grit
        3 -> PluckParams(3.4, 5, 0.85, 0.85f) // Distorted: the only gritty one
        else -> PluckParams(0.22, 18, 0.0, 0.75f) // Muted Pluck: very short, dark, percussive
    }

    private fun renderPluck(pitch: Int, vel: Float, durationSec: Double, p: PluckParams,
                            dead: Boolean, fx: FxSpec): FloatArray {
        val sr = SAMPLE_RATE
        val freq = midiToFreq(pitch)
        var decay = p.decayTime
        var excBox = p.excitationBox
        if (dead) decay = minOf(decay, 0.10)
        if (fx.palmMute) {
            decay = minOf(decay, 0.30)
            excBox = maxOf(excBox, 16) // darker
        }
        if (fx.softAttack) excBox = maxOf(excBox, 10)
        // Total length: at least the written duration, plus some ring-out, capped.
        val totalSec = minOf(maxOf(durationSec, minOf(decay, durationSec + decay * 0.5)), decay + 0.4)
            .coerceIn(0.05, 6.0)
        val n = (totalSec * sr).toInt().coerceAtLeast(256)
        val out = FloatArray(n)

        // Fractional-delay KS: ring length P plus blend S tunes the string exactly.
        val desiredPeriod = sr / freq
        var period = desiredPeriod.toInt().coerceAtLeast(2)
        var blend = (desiredPeriod - period).coerceIn(0.05, 0.95)
        if (period < 3) { period = 2; blend = 0.5 }
        val ring = FloatArray(period)
        val rnd = Random(pitch * 7919 + (p.decayTime * 1000).toInt() + excBox * 131)
        for (i in 0 until period) ring[i] = rnd.nextFloat() * 2f - 1f
        // Band-limit the excitation burst with a circular moving average so
        // the attack isn't raw full-band digital fizz (and there's no wrap
        // discontinuity where the ring loops).
        if (excBox > 1) {
            val src = ring.copyOf()
            val half = excBox / 2
            for (i in 0 until period) {
                var acc = 0f
                for (k in -half..half) acc += src[(i + k).mod(period)]
                ring[i] = acc / (2 * half + 1)
            }
        }
        // Loop gain for ~ -60 dB after decayTime seconds at the fundamental.
        val periodsPerDecay = maxOf(1.0, freq * decay)
        val loopGain = minOf(0.9995, 0.001.pow(1.0 / periodsPerDecay))

        var idx = 0
        val fadeIn = minOf(64, n / 4)
        val fadeOut = minOf((0.02 * sr).toInt(), n / 4)
        for (i in 0 until n) {
            val cur = ring[idx]
            val next = ring[(idx + 1) % period]
            ring[idx] = (loopGain * ((1.0 - blend) * cur + blend * next)).toFloat()
            var env = 1f
            if (i < fadeIn) env = i.toFloat() / fadeIn
            if (i >= n - fadeOut) env *= (n - i).toFloat() / fadeOut
            out[i] = cur * env
            idx = (idx + 1) % period
        }
        if (p.distortion > 0.0) {
            // Amp-style waveshaping over the whole note (attack gritty, tail
            // cleaner), then a one-pole pass to tame fizz. Only the Distorted
            // variant ever takes this branch.
            val drive = 1.0 + p.distortion * 7.0
            val norm = tanh(drive * 0.55)
            var lp = 0f
            for (i in 0 until n) {
                val shaped = (tanh(out[i] * drive) / norm).toFloat()
                lp += 0.5f * (shaped - lp)
                out[i] = lp
            }
        }
        val target = p.targetPeak * (0.30f + 0.70f * vel) * (if (fx.softAttack) 0.9f else 1f)
        return normalize(out, target)
    }

    // ---------- Bass ----------

    private fun renderBass(variant: Int, pitch: Int, vel: Float, durationSec: Double,
                           dead: Boolean, fx: FxSpec): FloatArray {
        return when (variant) {
            0 -> renderPluck(pitch, vel, durationSec, PluckParams(2.0, 9, 0.0, 0.85f), dead, fx) // Fingered
            1 -> renderPluck(pitch, vel, durationSec, PluckParams(2.4, 3, 0.0, 0.85f), dead, fx) // Picked
            2 -> { // Slap: bright clean pluck + pitch-drop thump, normalized as one
                val pluck = renderPluck(pitch, vel, durationSec, PluckParams(1.4, 2, 0.0, 0.9f), dead, fx)
                val thump = renderToneBurst(midiToFreq(pitch) * 0.5, 0.09, 0.8f, sweepTo = midiToFreq(pitch) * 0.25)
                mixInto(pluck, thump)
                normalize(pluck, 0.85f * (0.30f + 0.70f * vel))
            }
            3 -> renderFretless(pitch, vel, durationSec) // Fretless
            else -> renderSynthBass(pitch, vel, durationSec) // Synth Bass
        }
    }

    private fun renderFretless(pitch: Int, vel: Float, durationSec: Double): FloatArray {
        val sr = SAMPLE_RATE
        val total = minOf(maxOf(durationSec, 0.4), 4.0)
        val n = (total * sr).toInt()
        val out = FloatArray(n)
        val f0 = midiToFreq(pitch)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            // Slight upward glide at attack, gentle vibrato after.
            val glide = 1.0 - 0.06 * exp(-t * 18.0)
            val vib = 1.0 + 0.004 * sin(2 * PI * 5.0 * t) * minOf(1.0, t * 2)
            val f = f0 * glide * vib
            phase += 2 * PI * f / sr
            val attack = minOf(1.0, t * 30)
            val release = if (t > total - 0.08) maxOf(0.0, (total - t) / 0.08) else 1.0
            val s = sin(phase) * 0.8 + sin(phase * 2) * 0.25 + sin(phase * 3) * 0.08
            out[i] = (s * attack * release).toFloat()
        }
        return normalize(out, 0.80f * (0.30f + 0.70f * vel))
    }

    private fun renderSynthBass(pitch: Int, vel: Float, durationSec: Double): FloatArray {
        val sr = SAMPLE_RATE
        val total = minOf(maxOf(durationSec, 0.25), 4.0)
        val n = (total * sr).toInt()
        val out = FloatArray(n)
        val f0 = midiToFreq(pitch)
        var phase = 0.0
        var lp = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            phase += 2 * PI * f0 / sr
            // Sawtooth via phase wrap.
            val saw = (phase / PI) % 2.0 - 1.0
            val cutoff = 0.05 + 0.5 * exp(-t * 6.0)
            lp += cutoff * (saw - lp)
            val release = if (t > total - 0.05) maxOf(0.0, (total - t) / 0.05) else 1.0
            out[i] = (lp * release).toFloat()
        }
        return normalize(out, 0.85f * (0.30f + 0.70f * vel))
    }

    // ---------- Keyboard ----------

    private fun renderKeys(variant: Int, pitch: Int, vel: Float, durationSec: Double): FloatArray {
        val sr = SAMPLE_RATE
        val f0 = midiToFreq(pitch)
        val target = 0.78f * (0.30f + 0.70f * vel)
        return when (variant) {
            0 -> { // Grand Piano: additive harmonics, exponential decay
                val total = minOf(maxOf(durationSec * 1.5, 0.8), 5.0)
                val n = (total * sr).toInt()
                val out = FloatArray(n)
                val amps = doubleArrayOf(1.0, 0.55, 0.32, 0.18, 0.10, 0.05)
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    var s = 0.0
                    for (h in amps.indices) {
                        val decay = exp(-t * (1.6 + h * 0.9))
                        s += amps[h] * sin(2 * PI * f0 * (h + 1) * t) * decay
                    }
                    out[i] = s.toFloat()
                }
                normalize(out, target)
            }
            1 -> { // Electric Piano: FM bell-ish tine
                val total = minOf(maxOf(durationSec * 1.3, 0.7), 4.0)
                val n = (total * sr).toInt()
                val out = FloatArray(n)
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val modIndex = 3.0 * exp(-t * 5.0) + 0.4
                    val s = sin(2 * PI * f0 * t + modIndex * sin(2 * PI * f0 * 3.01 * t)) * exp(-t * 1.8)
                    out[i] = s.toFloat()
                }
                normalize(out, target)
            }
            2 -> { // Organ: sustained additive drawbars
                val total = minOf(maxOf(durationSec, 0.3), 6.0)
                val n = (total * sr).toInt()
                val out = FloatArray(n)
                val amps = doubleArrayOf(1.0, 0.6, 0.8, 0.3, 0.45, 0.2, 0.15, 0.1)
                val mult = doubleArrayOf(0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0)
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    var s = 0.0
                    for (h in amps.indices) s += amps[h] * sin(2 * PI * f0 * mult[h] * t)
                    val attack = minOf(1.0, t * 60)
                    val release = if (t > total - 0.06) maxOf(0.0, (total - t) / 0.06) else 1.0
                    out[i] = (s * attack * release).toFloat()
                }
                normalize(out, target)
            }
            3 -> { // Music Box: pure sine + octave partial, fast decay
                val total = minOf(maxOf(durationSec, 0.9), 3.0)
                val n = (total * sr).toInt()
                val out = FloatArray(n)
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val s = sin(2 * PI * f0 * t) * exp(-t * 3.5) +
                        0.35 * sin(2 * PI * f0 * 4 * t) * exp(-t * 9.0) +
                        0.12 * sin(2 * PI * f0 * 9.2 * t) * exp(-t * 16.0)
                    out[i] = s.toFloat()
                }
                normalize(out, target)
            }
            else -> { // Synth Lead: detuned saws through sweeping lowpass
                val total = minOf(maxOf(durationSec, 0.3), 5.0)
                val n = (total * sr).toInt()
                val out = FloatArray(n)
                var ph1 = 0.0; var ph2 = 0.0; var lp = 0.0
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    ph1 += 2 * PI * f0 / sr
                    ph2 += 2 * PI * f0 * 1.005 / sr
                    val saw = ((ph1 / PI) % 2.0 - 1.0) + ((ph2 / PI) % 2.0 - 1.0)
                    val cutoff = 0.08 + 0.45 * exp(-t * 2.5)
                    lp += cutoff * (saw * 0.5 - lp)
                    val attack = minOf(1.0, t * 80)
                    val release = if (t > total - 0.07) maxOf(0.0, (total - t) / 0.07) else 1.0
                    out[i] = (lp * attack * release).toFloat()
                }
                normalize(out, target)
            }
        }
    }

    // ---------- Drums ----------

    private enum class DrumPiece { KICK, SNARE, CLAP, HAT_CLOSED, HAT_OPEN, TOM, CRASH, RIDE, COWBELL, SHAKER, UNKNOWN }

    private fun pieceFor(pitch: Int): DrumPiece = when (pitch) {
        35, 36 -> DrumPiece.KICK
        38, 40 -> DrumPiece.SNARE
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
        val piece = pieceFor(pitch)
        val sr = SAMPLE_RATE
        // Kit character: [brightness, punch, decayScale, electronic]
        val (bright, punch, decayScale, electronic) = when (kit) {
            0 -> listOf(1.0, 1.0, 1.0, 0.0)   // Rock
            1 -> listOf(0.8, 0.8, 0.85, 0.0)  // Pop
            2 -> listOf(1.2, 1.1, 0.9, 1.0)   // Electronic
            3 -> listOf(0.6, 0.6, 1.2, 0.0)   // Jazz
            else -> listOf(0.5, 0.9, 0.7, 0.3) // Lo-Fi
        }
        val buf = when (piece) {
            DrumPiece.KICK -> renderToneBurst(
                if (electronic > 0.5) 120.0 else 150.0, 0.24 * decayScale, gain * punch.toFloat(),
                sweepTo = 40.0, noise = 0.15 * bright)
            DrumPiece.SNARE -> {
                val tone = renderToneBurst(190.0, 0.09 * decayScale, gain * 0.5f, sweepTo = 120.0)
                val noise = renderNoiseBurst(0.16 * decayScale, gain * 0.7f * punch.toFloat(), highpass = bright > 0.7)
                mix(tone, noise)
            }
            DrumPiece.CLAP -> renderNoiseBurst(0.22 * decayScale, gain * 0.8f, highpass = true, repeats = 3)
            DrumPiece.HAT_CLOSED -> renderNoiseBurst(0.05 * decayScale, gain * 0.5f, highpass = true, metallic = true)
            DrumPiece.HAT_OPEN -> renderNoiseBurst(0.35 * decayScale, gain * 0.5f, highpass = true, metallic = true)
            DrumPiece.TOM -> {
                val f = when (pitch) { 41, 43 -> 100.0; 45, 47 -> 130.0; 48 -> 165.0; else -> 190.0 }
                renderToneBurst(f, 0.28 * decayScale, gain * 0.9f, sweepTo = f * 0.6)
            }
            DrumPiece.CRASH -> renderNoiseBurst(1.1 * decayScale, gain * 0.55f, highpass = true, metallic = true)
            DrumPiece.RIDE -> renderNoiseBurst(0.5 * decayScale, gain * 0.4f, highpass = true, metallic = true)
            DrumPiece.COWBELL -> {
                val n = (0.25 * sr).toInt()
                val out = FloatArray(n)
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val s = sin(2 * PI * 540 * t) * 0.6 + sin(2 * PI * 800 * t) * 0.4
                    out[i] = (s * exp(-t * 14) * gain).toFloat()
                }
                out
            }
            DrumPiece.SHAKER -> renderNoiseBurst(0.12 * decayScale, gain * 0.45f, highpass = false)
            DrumPiece.UNKNOWN -> renderNoiseBurst(0.1, gain * 0.5f, highpass = true)
        }
        if (kit == 4) lofiCrush(buf)
        // Keep every hit at a known ceiling so kit pieces can't stack into clipping.
        return normalize(buf, 0.85f * (gain / 0.9f).coerceIn(0.15f, 1f))
    }

    private fun renderToneBurst(freq: Double, seconds: Double, gain: Float, sweepTo: Double, noise: Double = 0.0): FloatArray {
        val sr = SAMPLE_RATE
        val n = (seconds * sr).toInt().coerceAtLeast(64)
        val out = FloatArray(n)
        var phase = 0.0
        val rnd = Random(42)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val k = t / seconds
            val f = sweepTo + (freq - sweepTo) * exp(-k * 9.0)
            phase += 2 * PI * f / sr
            var s = sin(phase) * exp(-k * 4.5)
            if (noise > 0) s += (rnd.nextFloat() * 2 - 1) * noise * exp(-k * 30.0)
            out[i] = (s * gain).toFloat()
        }
        return out
    }

    private fun renderNoiseBurst(seconds: Double, gain: Float, highpass: Boolean,
                                 metallic: Boolean = false, repeats: Int = 1): FloatArray {
        val sr = SAMPLE_RATE
        val n = (seconds * sr).toInt().coerceAtLeast(64)
        val out = FloatArray(n)
        val rnd = Random(seconds.hashCode() * 31 + repeats)
        var prev = 0f
        var hpPrevIn = 0f; var hpPrevOut = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var w = rnd.nextFloat() * 2f - 1f
            if (metallic) {
                // Cheap metallic sheen: ring-modulate noise with a high square-ish tone.
                val m = if (sin(2 * PI * 6200 * t) > 0) 1f else -0.6f
                w *= m
            }
            var s = w
            if (highpass) {
                val a = 0.72f
                val o = a * (hpPrevOut + w - hpPrevIn)
                hpPrevIn = w; hpPrevOut = o
                s = o
            } else {
                s = (w + prev) * 0.5f
                prev = w
            }
            var env = exp(-t * (if (metallic) 9.0 else 16.0) / maxOf(seconds, 0.05) * 0.35)
            if (repeats > 1) {
                val seg = seconds / repeats
                val local = (t % seg) / seg
                env = exp(-local * 3.0) * (0.5 + 0.5 * ((t / seg).toInt() + 1) / repeats)
            }
            out[i] = (s * env * gain).toFloat()
        }
        return out
    }

    // ---------- Shared helpers ----------

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

    private fun mix(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(maxOf(a.size, b.size))
        for (i in out.indices) {
            out[i] = (if (i < a.size) a[i] else 0f) + (if (i < b.size) b[i] else 0f)
        }
        return out
    }

    private fun mixInto(target: FloatArray, extra: FloatArray) {
        for (i in extra.indices) if (i < target.size) target[i] += extra[i]
    }

    private fun lofiCrush(buf: FloatArray) {
        var hold = 0f
        for (i in buf.indices) {
            if (i % 3 == 0) hold = buf[i]
            buf[i] = hold * 0.9f
        }
    }
}
