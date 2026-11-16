package com.delukz.tabplayer

/** Ticks per quarter note used everywhere in the app model. */
const val PPQ = 480L

enum class TrackKind { GUITAR, BASS, DRUMS, KEYS, OTHER }

/**
 * Guitar techniques attached to a note, as carried by GP note/beat effects
 * or ASCII tab conventions. All defaults = plain note.
 *
 * Slide types use the PyGuitarPro SlideType encoding: -2 = slide into
 * from above, -1 = slide into from below, 1 = shift slide (to next note),
 * 2 = legato slide (to next note), 3 = slide out downwards, 4 = slide out
 * upwards. (On disk, GP5 stores slides as a bitmask and GP3/GP4 as this
 * signed type number; the parsers normalize both to these values.)
 * Harmonic types use the GP encoding: 1 natural, 2 artificial, 3 tapped,
 * 4 pinch, 5 semi.
 */
data class NoteEffects(
    val bendSemitones: Float = 0f,   // peak bend amount (max bend point), semitones
    val bendType: Int = 0,           // GP bend type: 1 bend, 2 bend+release, 4 prebend, 5 prebend+release, ...
    val slideType: Int = 0,
    val hammerPull: Boolean = false, // hammer-on / pull-off from previous note on the same string
    val palmMute: Boolean = false,
    val letRing: Boolean = false,
    val staccato: Boolean = false,
    val vibrato: Boolean = false,
    val harmonicType: Int = 0,
) {
    val hasBend: Boolean get() = bendSemitones > 0.01f
    val isHarmonic: Boolean get() = harmonicType != 0
    val slideOut: Boolean get() = slideType in 1..4
    val slideIn: Boolean get() = slideType < 0
    val isPlain: Boolean
        get() = !hasBend && slideType == 0 && !hammerPull && !palmMute && !letRing &&
            !staccato && !vibrato && harmonicType == 0
}

val NO_EFFECTS = NoteEffects()

data class NoteEvent(
    val startTick: Long,
    val durationTicks: Long,
    val pitch: Int,          // MIDI pitch; for drums this is the GM drum pitch
    val string: Int,         // 1 = highest string, 0 = no string (keys/drums display)
    val fret: Int,           // -1 = none
    val velocity: Int = 100,
    val dead: Boolean = false,
    val tie: Boolean = false,
    val effects: NoteEffects = NO_EFFECTS,
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
