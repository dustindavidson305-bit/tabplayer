package com.delukz.tabplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

/**
 * Streaming playback engine: schedules the selected track's notes through
 * the software synth and mixes them into an AudioTrack. Position is
 * published as a StateFlow of ticks for the playhead.
 *
 * v1.1: per-note technique shaping (bends, slides, palm mutes, harmonics,
 * vibrato, hammer-ons), a practice loop over a selected bar range, and a
 * tempo trainer that raises the speed by a set % after each loop pass.
 * The mixer uses a soft-knee limiter that is linear below 0.75 instead of
 * v1.0's always-on tanh squash (a major cause of the "everything sounds
 * distorted" complaint, together with the old synth).
 */
class PlayerEngine {

    val positionTicks = MutableStateFlow(0L)
    val isPlaying = MutableStateFlow(false)
    /** Current trainer speed in % of the slider tempo (100 when trainer is off). */
    val speedPct = MutableStateFlow(100)
    /** Completed loop passes since playback started. */
    val loopPass = MutableStateFlow(0)

    @Volatile var bpm: Int = 120
    @Volatile var loop: Boolean = false
    @Volatile var loopStartTick: Long = 0L
    @Volatile var loopEndTick: Long = -1L // -1 = end of song
    @Volatile var trainerEnabled: Boolean = false
    @Volatile var trainerStartPct: Int = 50
    @Volatile var trainerStepPct: Int = 5
    @Volatile var trainerCapPct: Int = 100

    @Volatile private var currentPct: Int = 100
    @Volatile private var family: SoundFamily = SoundFamily.GUITAR
    @Volatile private var variant: Int = 0

    private var song: SongData? = null
    private var events: List<NoteEvent> = emptyList()
    private var eventFx: List<Synth.FxSpec> = emptyList()
    private var totalTicks: Long = 0

    @Volatile private var posTicks: Double = 0.0
    @Volatile private var running = false
    private var thread: Thread? = null
    private var audioTrack: AudioTrack? = null

    private class Voice(val samples: FloatArray, var cursor: Int, val startFrame: Int)

    fun configure(song: SongData, trackIndex: Int, family: SoundFamily, variant: Int) {
        this.song = song
        this.family = family
        this.variant = variant
        val track = song.tracks.getOrNull(trackIndex)
        val playable = track?.notes?.filter { !it.tie }?.sortedBy { it.startTick } ?: emptyList()
        events = playable
        eventFx = buildFx(playable)
        totalTicks = song.totalTicks
        if (posTicks > totalTicks) seekTo(0)
    }

    /**
     * Translate each note's techniques into synth shaping, resolving the
     * ones that depend on neighbouring notes (slide targets, legato
     * hammer-ons, let-ring sustain into the next note on the string).
     */
    internal fun buildFx(notes: List<NoteEvent>): List<Synth.FxSpec> {
        fun nextOnString(i: Int): Int? {
            val n = notes[i]
            if (n.string == 0) return null
            for (j in i + 1 until notes.size) {
                if (notes[j].string == n.string && notes[j].startTick > n.startTick) return j
            }
            return null
        }
        return notes.mapIndexed { i, n ->
            val e = n.effects
            var slideTo = 0f
            if (e.slideOut) {
                slideTo = when (e.slideType) {
                    3 -> -3f // slide out downwards: fall away from the note
                    4 -> 3f  // slide out upwards: rise away from the note
                    else -> { // shift / legato: glide into the next note's pitch
                        val j = nextOnString(i)
                        if (j != null) (notes[j].pitch - n.pitch).toFloat() else 0f
                    }
                }
            }
            val slideFrom = when (e.slideType) {
                -1 -> -2f // slide into from below: start 2 semitones under, glide up
                -2 -> 2f  // slide into from above: start 2 semitones over, glide down
                else -> 0f
            }
            // A hammer-on/pull-off target (or a legato-slide target) is not re-picked.
            val soft = e.hammerPull || (i > 0 && notes[i - 1].string == n.string &&
                notes[i - 1].effects.slideType == 2)
            Synth.FxSpec(
                palmMute = e.palmMute,
                harmonic = e.isHarmonic,
                bendSemitones = e.bendSemitones,
                bendRelease = e.bendType in intArrayOf(2, 3, 5),
                prebend = e.bendType in intArrayOf(4, 5),
                slideFromSemitones = slideFrom,
                slideToSemitones = slideTo,
                vibrato = e.vibrato,
                staccato = e.staccato,
                softAttack = soft,
            )
        }
    }

    fun setSound(family: SoundFamily, variant: Int) {
        this.family = family
        this.variant = variant
    }

    /** Called when trainer settings change: restart the ramp from the start %. */
    fun resetTrainer() {
        currentPct = if (trainerEnabled) trainerStartPct else 100
        speedPct.value = currentPct
        loopPass.value = 0
    }

    fun seekTo(tick: Long) {
        posTicks = tick.coerceIn(0, totalTicks).toDouble()
        positionTicks.value = posTicks.toLong()
    }

    fun play() {
        if (running) return
        if (totalTicks == 0L) return
        if (loop && loopEndTick > 0 && (posTicks < loopStartTick || posTicks >= loopEndTick)) {
            seekTo(loopStartTick)
        }
        if (posTicks >= totalTicks) seekTo(0)
        if (posTicks <= loopStartTick + 1) resetTrainer()
        val sr = Synth.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, sr / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = at
        running = true
        isPlaying.value = true
        at.play()
        thread = Thread({ renderLoop(at) }, "TabPlayerAudio").apply { start() }
    }

    fun pause() {
        running = false
        isPlaying.value = false
        thread?.join(500)
        thread = null
        audioTrack?.let { try { it.stop() } catch (_: Exception) {} ; it.release() }
        audioTrack = null
    }

    fun stop() {
        pause()
        seekTo(0)
    }

    fun release() = pause()

    private fun ticksPerSecAt(tick: Long): Double {
        val s = song ?: return bpm * PPQ / 60.0
        val fileBpm = s.bpmAt(tick)
        val effective = bpm * (fileBpm.toDouble() / s.tempoBpm.coerceAtLeast(1))
        val scale = if (trainerEnabled) currentPct / 100.0 else 1.0
        return effective * scale * PPQ / 60.0
    }

    private fun renderLoop(at: AudioTrack) {
        val sr = Synth.SAMPLE_RATE
        val blockFrames = 1024
        val out = ShortArray(blockFrames)
        val voices = mutableListOf<Voice>()
        var eventIndex = events.indexOfFirst { it.startTick >= posTicks }
        if (eventIndex < 0) eventIndex = events.size

        while (running) {
            val tps = ticksPerSecAt(posTicks.toLong())
            val blockTicks = blockFrames.toDouble() / sr * tps
            val blockStart = posTicks
            val blockEnd = posTicks + blockTicks

            // Trigger events starting in this block.
            while (eventIndex < events.size && events[eventIndex].startTick < blockEnd) {
                val ev = events[eventIndex]
                if (ev.startTick >= blockStart - 0.001) {
                    val offset = (((ev.startTick - blockStart) / blockTicks) * blockFrames).toInt()
                        .coerceIn(0, blockFrames - 1)
                    var durSec = (ev.durationTicks / tps).coerceIn(0.05, 6.0)
                    if (ev.effects.letRing) {
                        // Sustain toward the next note on the same string.
                        val nextStart = events.drop(eventIndex + 1)
                            .firstOrNull { it.string == ev.string && it.startTick > ev.startTick }
                            ?.startTick
                        if (nextStart != null) {
                            val gapSec = ((nextStart - ev.startTick) / tps).coerceIn(0.0, 6.0)
                            durSec = maxOf(durSec, minOf(gapSec * 0.95, 6.0))
                        } else {
                            durSec = minOf(durSec * 2, 6.0)
                        }
                    }
                    val samples = try {
                        Synth.renderNote(family, variant, ev.pitch, ev.velocity, durSec, ev.dead,
                            eventFx.getOrElse(eventIndex) { Synth.FxSpec() })
                    } catch (e: Exception) {
                        null
                    }
                    if (samples != null && samples.isNotEmpty()) {
                        voices.add(Voice(samples, 0, offset))
                    }
                }
                eventIndex++
            }

            // Mix with a soft-knee limiter (linear below 0.75, gentle above).
            for (f in 0 until blockFrames) {
                var sum = 0f
                for (vi in voices.indices) {
                    val v = voices[vi]
                    if (f >= v.startFrame && v.cursor < v.samples.size) {
                        sum += v.samples[v.cursor]
                        v.cursor++
                    }
                }
                val x = sum * 0.9f
                val ax = abs(x)
                val y = if (ax <= 0.75f) x
                    else sign(x) * (0.75f + 0.25f * tanh(((ax - 0.75f) / 0.25f).toDouble()).toFloat())
                out[f] = (y * 32767).toInt().toShort()
            }
            voices.removeAll { v -> v.cursor >= v.samples.size }

            try {
                at.write(out, 0, out.size)
            } catch (e: Exception) {
                break
            }

            posTicks = blockEnd
            val effEnd = if (loop && loopEndTick > 0) minOf(loopEndTick, totalTicks) else totalTicks
            if (posTicks >= effEnd) {
                if (loop) {
                    // Wrap to the loop start; a completed pass steps the trainer.
                    posTicks = (if (loopEndTick > 0) loopStartTick else 0L).toDouble()
                    eventIndex = events.indexOfFirst { it.startTick >= posTicks }
                    if (eventIndex < 0) eventIndex = events.size
                    voices.clear()
                    loopPass.value = loopPass.value + 1
                    if (trainerEnabled && currentPct < trainerCapPct) {
                        currentPct = minOf(trainerCapPct, currentPct + trainerStepPct)
                        speedPct.value = currentPct
                    }
                } else {
                    posTicks = totalTicks.toDouble()
                    positionTicks.value = posTicks.toLong()
                    // Let the tail ring briefly, then finish.
                    running = false
                    isPlaying.value = false
                    break
                }
            }
            positionTicks.value = posTicks.toLong()
        }
        try { at.stop() } catch (_: Exception) {}
        at.release()
        if (audioTrack === at) audioTrack = null
        isPlaying.value = false
    }
}
