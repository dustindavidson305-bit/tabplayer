package com.delukz.tabplayer

/**
 * Parser for plain-text (ASCII) guitar/bass tablature.
 *
 * Handles the standard layout: blocks of string lines like
 *   e|---0---1---|
 *   B|---1---3---|
 * with bar lines and multi-digit frets. Each character column is one
 * eighth-note slot; fret groups starting at the same column sound together.
 * Tuning is taken from the standard tuning for the block's string count
 * when the labels match, otherwise derived from the labels' pitch classes.
 */
object AsciiTabParser {

    private val NOTE_PC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    private class RawBlock(val pitchClasses: List<Int>, val bodies: List<String>)

    fun parse(text: String): SongData {
        val blocks = mutableListOf<RawBlock>()
        var curPcs = mutableListOf<Int>()
        var curBodies = mutableListOf<String>()

        fun flush() {
            if (curBodies.size >= 3) blocks.add(RawBlock(curPcs.toList(), curBodies.toList()))
            curPcs = mutableListOf()
            curBodies = mutableListOf()
        }

        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val barIdx = line.indexOf('|')
            val pc = if (barIdx in 1..3) labelToPitchClass(line.substring(0, barIdx).trim()) else null
            if (pc != null) {
                curPcs.add(pc)
                curBodies.add(line.substring(barIdx + 1))
            } else {
                flush()
            }
        }
        flush()

        if (blocks.isEmpty()) throw TabParseException("No tablature lines found in this text file")

        val stringCount = blocks.first().bodies.size
        val tuning = tuningFor(blocks.first().pitchClasses, stringCount)
        val notes = mutableListOf<NoteEvent>()
        val slotTicks = PPQ / 2 // one character column = eighth note
        val measureStarts = mutableListOf<Long>()
        var blockStart = 0L

        for (block in blocks) {
            if (block.bodies.size != stringCount) continue
            val width = block.bodies.maxOf { it.length }
            val barCols = (0 until width).filter { col ->
                block.bodies.all { col < it.length && it[col] == '|' }
            }
            for ((si, body) in block.bodies.withIndex()) {
                var col = 0
                while (col < body.length) {
                    if (body[col].isDigit()) {
                        var end = col
                        while (end < body.length && body[end].isDigit()) end++
                        val fret = body.substring(col, end).toIntOrNull()
                        if (fret != null && si < tuning.size) {
                            notes.add(
                                NoteEvent(
                                    startTick = blockStart + col * slotTicks,
                                    durationTicks = slotTicks * (end - col + 1),
                                    pitch = tuning[si] + fret,
                                    string = si + 1,
                                    fret = fret,
                                )
                            )
                        }
                        col = end
                    } else col++
                }
            }
            for (bc in barCols) {
                val t = blockStart + bc * slotTicks
                if (measureStarts.lastOrNull() != t) measureStarts.add(t)
            }
            blockStart += (width + 2) * slotTicks
        }

        if (notes.isEmpty()) throw TabParseException("Found tab lines but no fret numbers")
        notes.sortBy { it.startTick }

        val total = notes.maxOf { it.startTick + it.durationTicks } + slotTicks
        val measures = mutableListOf<MeasureInfo>()
        val starts = measureStarts.distinct().sorted().filter { it < total }
        if (starts.size >= 2) {
            for (i in starts.indices) {
                val s = starts[i]
                val e = if (i + 1 < starts.size) starts[i + 1] else total
                if (e > s) measures.add(MeasureInfo(measures.size + 1, s, e - s, 4, 4))
            }
        }
        if (measures.isEmpty()) {
            var s = 0L
            while (s < total) {
                measures.add(MeasureInfo(measures.size + 1, s, 4 * PPQ, 4, 4))
                s += 4 * PPQ
            }
        }

        val isBass = stringCount <= 5
        val track = TrackData(
            name = if (isBass) "Bass Tab" else "Guitar Tab",
            strings = tuning,
            isPercussion = false,
            midiInstrument = if (isBass) 33 else 25,
            notes = notes,
        )
        return SongData(
            title = "Text Tab",
            artist = "",
            tempoBpm = 100,
            measures = measures,
            tracks = listOf(track),
            sourceFormat = "ASCII Tab",
        )
    }

    private fun labelToPitchClass(label: String): Int? {
        if (label.isEmpty() || label.length > 3) return null
        val letter = label[0].uppercaseChar()
        var pc = NOTE_PC[letter] ?: return null
        var idx = 1
        if (idx < label.length && (label[idx] == '#' || label[idx] == 'b')) {
            pc += if (label[idx] == '#') 1 else -1
            idx++
        }
        if (idx < label.length && !(label.length - idx == 1 && label[idx].uppercaseChar() == letter)) return null
        return ((pc % 12) + 12) % 12
    }

    private fun tuningFor(pcs: List<Int>, count: Int): List<Int> {
        val standards: Map<List<Int>, List<Int>> = mapOf(
            listOf(4, 11, 7, 2, 9, 4) to listOf(64, 59, 55, 50, 45, 40),       // guitar 6
            listOf(4, 11, 7, 2, 9, 4, 11) to listOf(64, 59, 55, 50, 45, 40, 35), // guitar 7
            listOf(7, 2, 9, 4) to listOf(43, 38, 33, 28),                      // bass 4
            listOf(7, 2, 9, 4, 11) to listOf(43, 38, 33, 28, 23),              // bass 5
        )
        standards[pcs]?.let { return it }
        // Derive: pick first pitch near a reference, then step down by pitch-class distance.
        val reference = if (count <= 5) 43 else 64
        val out = mutableListOf<Int>()
        var prev: Int? = null
        var prevPc = 0
        for (pc in pcs) {
            val p = if (prev == null) {
                var cand = pc + 60
                while (cand > reference + 6) cand -= 12
                while (cand < reference - 6) cand += 12
                cand
            } else {
                var step = (prevPc - pc + 12) % 12
                if (step == 0) step = 12
                prev - step
            }
            out.add(p)
            prev = p
            prevPc = pc
        }
        return out
    }
}
