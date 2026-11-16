package com.delukz.tabplayer

/** Built-in demo: "Ode to Joy" (Beethoven, public domain) in C major, first position. */
object DemoSong {

    fun create(): SongData {
        val q = PPQ
        val h = 2 * PPQ
        // (pitch, string, fret, durationTicks)
        val melody: List<List<Triple<Int, Pair<Int, Int>, Long>>> = listOf(
            listOf(Triple(64, 1 to 0, q), Triple(64, 1 to 0, q), Triple(65, 1 to 1, q), Triple(67, 1 to 3, q)),
            listOf(Triple(67, 1 to 3, q), Triple(65, 1 to 1, q), Triple(64, 1 to 0, q), Triple(62, 2 to 3, q)),
            listOf(Triple(60, 2 to 1, q), Triple(60, 2 to 1, q), Triple(62, 2 to 3, q), Triple(64, 1 to 0, q)),
            listOf(Triple(64, 1 to 0, q), Triple(62, 2 to 3, q), Triple(62, 2 to 3, h)),
            listOf(Triple(64, 1 to 0, q), Triple(64, 1 to 0, q), Triple(65, 1 to 1, q), Triple(67, 1 to 3, q)),
            listOf(Triple(67, 1 to 3, q), Triple(65, 1 to 1, q), Triple(64, 1 to 0, q), Triple(62, 2 to 3, q)),
            listOf(Triple(60, 2 to 1, q), Triple(60, 2 to 1, q), Triple(62, 2 to 3, q), Triple(64, 1 to 0, q)),
            listOf(Triple(62, 2 to 3, q), Triple(60, 2 to 1, q), Triple(60, 2 to 1, h)),
        )
        val guitarNotes = mutableListOf<NoteEvent>()
        val measures = mutableListOf<MeasureInfo>()
        var start = 0L
        for ((mi, measure) in melody.withIndex()) {
            measures.add(MeasureInfo(mi + 1, start, 4 * PPQ, 4, 4))
            var t = start
            for ((pitch, sf, dur) in measure) {
                guitarNotes.add(NoteEvent(t, dur, pitch, sf.first, sf.second))
                t += dur
            }
            start += 4 * PPQ
        }
        // Simple drum groove: kick on beats, snare on 2 & 4, hats on eighths.
        val drumNotes = mutableListOf<NoteEvent>()
        for (m in 0 until 8) {
            val base = m * 4 * PPQ
            for (beat in 0 until 4) {
                drumNotes.add(NoteEvent(base + beat * PPQ, PPQ / 2, 36, 0, -1)) // kick
                if (beat == 1 || beat == 3) drumNotes.add(NoteEvent(base + beat * PPQ, PPQ / 2, 38, 0, -1)) // snare
            }
            for (e in 0 until 8) {
                drumNotes.add(NoteEvent(base + e * PPQ / 2, PPQ / 4, 42, 0, -1)) // closed hat
            }
        }
        val guitar = TrackData(
            name = "Melody (Guitar)",
            strings = listOf(64, 59, 55, 50, 45, 40),
            isPercussion = false,
            midiInstrument = 25,
            notes = guitarNotes,
        )
        val drums = TrackData(
            name = "Drums",
            strings = emptyList(),
            isPercussion = true,
            midiInstrument = 0,
            notes = drumNotes,
        )
        return SongData(
            title = "Ode to Joy (Demo)",
            artist = "L. v. Beethoven",
            tempoBpm = 108,
            measures = measures,
            tracks = listOf(guitar, drums),
            sourceFormat = "Built-in demo",
        )
    }
}
