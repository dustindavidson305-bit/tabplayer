package com.delukz.tabplayer

/** Ticks per quarter note used everywhere in the app model. */
const val PPQ = 480L

enum class TrackKind { GUITAR, BASS, DRUMS, KEYS, OTHER }

data class NoteEvent(
    val startTick: Long,
    val durationTicks: Long,
    val pitch: Int,          // MIDI pitch; for drums this is the GM drum pitch
    val string: Int,         // 1 = highest string, 0 = no string (keys/drums display)
    val fret: Int,           // -1 = none
    val velocity: Int = 100,
    val dead: Boolean = false,
    val tie: Boolean = false,
)

data class MeasureInfo(
    val number: Int,
    val startTick: Long,
    val lengthTicks: Long,
    val numerator: Int,
    val denominator: Int,
)

class TrackData(
    val name: String,
    val strings: List<Int>,  // tuning pitches, string 1 (highest) first; empty for non-fretted
    val isPercussion: Boolean,
    val midiInstrument: Int,
    val notes: List<NoteEvent>,
) {
    val kind: TrackKind
        get() = when {
            isPercussion -> TrackKind.DRUMS
            strings.size >= 6 -> TrackKind.GUITAR
            strings.size in 4..5 && (strings.maxOrNull() ?: 99) <= 50 -> TrackKind.BASS
            strings.size in 4..5 -> TrackKind.GUITAR
            midiInstrument in 32..39 -> TrackKind.BASS
            midiInstrument in 24..31 -> TrackKind.GUITAR
            midiInstrument in 0..23 -> TrackKind.KEYS
            else -> TrackKind.OTHER
        }
}

class SongData(
    val title: String,
    val artist: String,
    val tempoBpm: Int,
    val measures: List<MeasureInfo>,
    val tracks: List<TrackData>,
    val sourceFormat: String,
    /** (tick, bpm) tempo changes from the file, sorted by tick. First entry is at tick 0. */
    val tempoMap: List<Pair<Long, Int>> = listOf(0L to tempoBpm),
) {
    val totalTicks: Long
        get() = measures.lastOrNull()?.let { it.startTick + it.lengthTicks } ?: 0L

    fun bpmAt(tick: Long): Int {
        var bpm = tempoBpm
        for ((t, b) in tempoMap) {
            if (t <= tick) bpm = b else break
        }
        return bpm
    }
}

class TabParseException(message: String) : Exception(message)
