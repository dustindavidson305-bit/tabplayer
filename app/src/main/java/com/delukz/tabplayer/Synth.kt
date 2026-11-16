package com.delukz.tabplayer

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
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
 */
object Synth {
    const val SAMPLE_RATE = 44100

    fun renderNote(family: SoundFamily, variant: Int, pitch: Int, velocity: Int,
                   durationSec: Double, dead: Boolean = false): FloatArray {
        val gain = (velocity.coerceIn(1, 127) / 127f) * 0.9f
        return when (family) {
            SoundFamily.DRUMS -> renderDrum(variant, pitch, gain)
            SoundFamily.GUITAR -> renderPluck(pitch, gain, durationSec, guitarParams(variant), dead)
            SoundFamily.BASS -> renderBass(variant, pitch, gain, durationSec, dead)
            SoundFamily.KEYS -> renderKeys(variant, pitch, gain, durationSec)
        }
    }

    // ---------- Plucked strings (Karplus-Strong) ----------

    private data class PluckParams(
        val brightness: Double,  // lowpass coefficient in the KS loop (higher = brighter/longer)
        val decayTime: Double,   // seconds to ~ -60dB
        val distortion: Double,  // 0 = clean
        val pickNoise: Double,   // attack noise amount
    )

    private fun guitarParams(variant: Int): PluckParams = when (variant) {
        0 -> PluckParams(0.996, 2.2, 0.0, 0.5)   // Acoustic Steel
        1 -> PluckParams(0.985, 1.6, 0.0, 0.25)  // Nylon
        2 -> PluckParams(0.998, 3.0, 0.0, 0.35)  // Clean Electric
        3 -> PluckParams(0.999, 3.5, 0.85, 0.4)  // Distorted
        else -> PluckParams(0.90, 0.35, 0.0, 0.6) // Muted
    }

    private fun renderPluck(pitch: Int, gain: Float, durationSec: Double, p: PluckParams, dead: Boolean): FloatArray {
        val freq = midiToFreq(pitch)
        val sr = SAMPLE_RATE
        val decay = if (dead) minOf(p.decayTime, 0.12) else p.decayTime
        val totalSec = minOf(maxOf(durationSec, decay * 0.7), decay + 0.3).coerceAtMost(6.0)
        val n = (totalSec * sr).toInt().coerceAtLeast(256)
        val out = FloatArray(n)
        val period = maxOf(2, (sr / freq).toInt())
        val ring = FloatArray(period)
        val rnd = Random(pitch * 7919 + variantSeed(p))
        for (i in 0 until period) ring[i] = (rnd.nextFloat() * 2f - 1f)
        var idx = 0
        // Overall amplitude envelope so the note also respects its written duration.
        val envDecay = exp(-3.0 / (decay * sr))
        var env = 1.0
        var prev = 0f
        for (i in 0 until n) {
            val cur = ring[idx]
            val next = ring[(idx + 1) % period]
            var v = (p.brightness * 0.5 * (cur + next)).toFloat()
            if (dead) v *= 0.7f
            ring[idx] = v
            var s = cur
            if (p.distortion > 0.0) {
                val d = 1.0 + p.distortion * 6.0
                s = (kotlin.math.tanh(s * d) / kotlin.math.tanh(d)).toFloat()
            }
            // A touch of pick noise at the very start.
            if (i < 300) s += ((rnd.nextFloat() * 2f - 1f) * p.pickNoise * (1.0 - i / 300.0)).toFloat() * 0.3f
            out[i] = (s * env * gain).toFloat()
            env *= envDecay
            prev = s
            idx = (idx + 1) % period
        }
        @Suppress("UNUSED_EXPRESSION") prev
        return out
    }

    private fun variantSeed(p: PluckParams): Int = (p.brightness * 10000).toInt()

    // ---------- Bass ----------

    private fun renderBass(variant: Int, pitch: Int, gain: Float, durationSec: Double, dead: Boolean): FloatArray {
        return when (variant) {
            0 -> renderPluck(pitch, gain, durationSec, PluckParams(0.990, 1.8, 0.0, 0.2), dead) // Fingered
            1 -> renderPluck(pitch, gain, durationSec, PluckParams(0.997, 2.2, 0.0, 0.7), dead) // Picked
            2 -> { // Slap: bright pluck + pitch-drop thump
                val pluck = renderPluck(pitch, gain, durationSec, PluckParams(0.999, 1.2, 0.1, 0.9), dead)
                val thump = renderToneBurst(midiToFreq(pitch) * 0.5, 0.09, gain * 0.8f, sweepTo = midiToFreq(pitch) * 0.25)
                mixInto(pluck, thump)
                pluck
            }
            3 -> renderFretless(pitch, gain, durationSec) // Fretless
            else -> renderSynthBass(pitch, gain, durationSec) // Synth Bass
        }
    }

    private fun renderFretless(pitch: Int, gain: Float, durationSec: Double): FloatArray {
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
            out[i] = (s * attack * release * gain * 0.7).toFloat()
        }
        return out
    }

    private fun renderSynthBass(pitch: Int, gain: Float, durationSec: Double): FloatArray {
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
            out[i] = (lp * release * gain * 0.9).toFloat()
        }
        return out
    }

    // ---------- Keyboard ----------

    private fun renderKeys(variant: Int, pitch: Int, gain: Float, durationSec: Double): FloatArray {
        val sr = SAMPLE_RATE
        val f0 = midiToFreq(pitch)
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
                    out[i] = (s * gain * 0.55).toFloat()
                }
                out
            }
            1 -> { // Electric Piano: FM bell-ish tine
                val total = minOf(maxOf(durationSec * 1.3, 0.7), 4.0)
                val n = (total * sr).toInt()
                val out = FloatArray(n)
                for (i in 0 until n) {
                    val t = i.toDouble() / sr
                    val modIndex = 3.0 * exp(-t * 5.0) + 0.4
                    val s = sin(2 * PI * f0 * t + modIndex * sin(2 * PI * f0 * 3.01 * t)) * exp(-t * 1.8)
                    out[i] = (s * gain * 0.6).toFloat()
                }
                out
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
                    out[i] = (s * attack * release * gain * 0.28).toFloat()
                }
                out
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
                    out[i] = (s * gain * 0.6).toFloat()
                }
                out
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
                    out[i] = (lp * attack * release * gain * 0.8).toFloat()
                }
                out
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
        val rnd = Random(pitch * 131 + kit * 17)
        // Kit character: [brightness, punch, decayScale, electronic]
        val (bright, punch, decayScale, electronic) = when (kit) {
            0 -> listOf(1.0, 1.0, 1.0, 0.0)   // Rock
            1 -> listOf(0.8, 0.8, 0.85, 0.0)  // Pop
            2 -> listOf(1.2, 1.1, 0.9, 1.0)   // Electronic
            3 -> listOf(0.6, 0.6, 1.2, 0.0)   // Jazz
            else -> listOf(0.5, 0.9, 0.7, 0.3) // Lo-Fi
        }
        return when (piece) {
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
        }.also { if (kit == 4) lofiCrush(it) }
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
