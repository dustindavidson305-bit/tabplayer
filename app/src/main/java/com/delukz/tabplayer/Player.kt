package com.delukz.tabplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.tanh

/**
 * Streaming playback engine: schedules the selected track's notes through
 * the software synth and mixes them into an AudioTrack. Position is
 * published as a StateFlow of ticks for the playhead.
 */
class PlayerEngine {

    val positionTicks = MutableStateFlow(0L)
    val isPlaying = MutableStateFlow(false)

    @Volatile var bpm: Int = 120
    @Volatile var loop: Boolean = false
    @Volatile private var family: SoundFamily = SoundFamily.GUITAR
    @Volatile private var variant: Int = 0

    private var song: SongData? = null
    private var events: List<NoteEvent> = emptyList()
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
        events = track?.notes?.filter { !it.tie }?.sortedBy { it.startTick } ?: emptyList()
        totalTicks = song.totalTicks
        if (posTicks > totalTicks) seekTo(0)
    }

    fun setSound(family: SoundFamily, variant: Int) {
        this.family = family
        this.variant = variant
    }

    fun seekTo(tick: Long) {
        posTicks = tick.coerceIn(0, totalTicks).toDouble()
        positionTicks.value = posTicks.toLong()
    }

    fun play() {
        if (running) return
        if (totalTicks == 0L) return
        if (posTicks >= totalTicks) seekTo(0)
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
        return effective * PPQ / 60.0
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
                    val durSec = (ev.durationTicks / tps).coerceIn(0.05, 6.0)
                    val samples = try {
                        Synth.renderNote(family, variant, ev.pitch, ev.velocity, durSec, ev.dead)
                    } catch (e: Exception) {
                        null
                    }
                    if (samples != null && samples.isNotEmpty()) {
                        voices.add(Voice(samples, 0, offset))
                    }
                }
                eventIndex++
            }

            // Mix.
            for (f in 0 until blockFrames) {
                var sum = 0f
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    if (f >= v.startFrame) {
                        if (v.cursor < v.samples.size) {
                            sum += v.samples[v.cursor]
                            v.cursor++
                        }
                        if (v.cursor >= v.samples.size && f >= v.startFrame) {
                            // mark for removal below (can't remove mid-frame safely for other voices)
                        }
                    }
                }
                val clipped = tanh(sum.toDouble() * 0.9).toFloat()
                out[f] = (clipped * 32767).toInt().toShort()
            }
            voices.removeAll { v -> v.cursor >= v.samples.size }

            try {
                at.write(out, 0, out.size)
            } catch (e: Exception) {
                break
            }

            posTicks = blockEnd
            if (posTicks >= totalTicks) {
                if (loop) {
                    posTicks = 0.0
                    eventIndex = 0
                    voices.clear()
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
