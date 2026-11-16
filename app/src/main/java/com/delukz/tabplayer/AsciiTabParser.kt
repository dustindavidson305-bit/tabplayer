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
 *
 * Technique conventions are recognised per string line: h/p hammer-on and
 * pull-off, / and \ slides (between frets = slide to the next note, before
 * a fret = slide into it), 5b7 bends (with optional r release), ~ vibrato,
 * x dead notes, <12> harmonics, and PM...... annotation lines above a
 * block marking palm-muted spans.
 */
object AsciiTabParser {

    private val NOTE_PC = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    private class RawBlock(val pitchClasses: List<Int>, val bodies: List<String>, val pmSpan: IntRange?)

    private class MutNote(
        val startCol: Int,
        val stringIndex: Int,
        val fret: Int,
        val dead: Boolean,
        val widthCols: Int,
        var effects: NoteEffects,
    )

    fun parse(text: String): SongData {
        val blocks = mutableListOf<RawBlock>()
        var curPcs = mutableListOf<Int>()
        var curBodies = mutableListOf<String>()
        var pendingPm: IntRange? = null
        var blockPm: IntRange? = null

        fun flush() {
            if (curBodies.size >= 3) blocks.add(RawBlock(curPcs.toList(), curBodies.toList(), blockPm))
            curPcs = mutableListOf()
            curBodies = mutableListOf()
            blockPm = null
        }

        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val barIdx = line.indexOf('|')
            val pc = if (barIdx in 1..3) labelToPitchClass(line.substring(0, barIdx).trim()) else null
            if (pc != null) {
                if (curBodies.isEmpty()) {
                    blockPm = pendingPm
                    pendingPm = null
                }
                curPcs.add(pc)
                curBodies.add(line.substring(barIdx + 1))
            } else if (line.contains("PM")) {
                flush()
                pmSpanOf(line)?.let { pendingPm = it }
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
                for (mn in scanStringLine(body)) {
                    var fx = mn.effects
                    if (block.pmSpan != null && mn.startCol in block.pmSpan) {
                        fx = fx.copy(palmMute = true)
                    }
                    if (si < tuning.size) {
                        notes.add(
                            NoteEvent(
                                startTick = blockStart + mn.startCol * slotTicks,
                                durationTicks = slotTicks * (mn.widthCols + 1),
                                pitch = tuning[si] + if (mn.dead) 0 else mn.fret,
                                string = si + 1,
                                fret = if (mn.dead) -1 else mn.fret,
                                dead = mn.dead,
                                effects = fx,
                            )
                        )
                    }
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

    /** Extract the muted column span from a "PM......" annotation line, in body columns. */
    private fun pmSpanOf(line: String): IntRange? {
        val barIdx = line.indexOf('|')
        val body = if (barIdx >= 0) line.substring(barIdx + 1) else line
        // Without a '|', the span is measured from the line start, but string
        // bodies start after a 1-char label + '|' (2 columns).
        val offset = if (barIdx >= 0) 0 else 2
        var first = -1
        var last = -1
        for (i in body.indices) {
            if (body[i] == '-' || body[i] == '.') {
                if (first < 0) first = i
                last = i
            }
        }
        return if (first >= 0 && last - first >= 2) {
            (first - offset).coerceAtLeast(0)..(last - offset).coerceAtLeast(0)
        } else null
    }

    /** Tokenize one string line into notes with technique effects. */
    private fun scanStringLine(body: String): List<MutNote> {
        val out = mutableListOf<MutNote>()
        var col = 0
        var last: MutNote? = null
        var lastEnd = -1 // column just past the last note's token
        var pendingHammer = false
        var pendingSlideIn = 0

        fun emit(startCol: Int, fret: Int, dead: Boolean, width: Int, harmonic: Boolean = false) {
            val n = MutNote(
                startCol = startCol,
                stringIndex = 0,
                fret = fret,
                dead = dead,
                widthCols = width,
                effects = NoteEffects(
                    hammerPull = pendingHammer,
                    slideType = pendingSlideIn,
                    harmonicType = if (harmonic) 1 else 0,
                ),
            )
            out.add(n)
            last = n
            lastEnd = startCol + width
            pendingHammer = false
            pendingSlideIn = 0
        }

        // True when only dashes sit between the last note's end and [c].
        fun followsLastNote(c: Int): Boolean =
            last != null && c >= lastEnd && (lastEnd until c).all { body[it] == '-' }

        while (col < body.length) {
            val ch = body[col]
            when {
                ch.isDigit() -> {
                    var end = col
                    while (end < body.length && body[end].isDigit()) end++
                    val fret = body.substring(col, end).toIntOrNull()
                    if (fret != null) emit(col, fret, dead = false, width = end - col)
                    col = end
                }
                ch == '<' -> {
                    var end = col + 1
                    while (end < body.length && body[end].isDigit()) end++
                    val fret = if (end > col + 1 && end < body.length && body[end] == '>')
                        body.substring(col + 1, end).toIntOrNull() else null
                    if (fret != null) {
                        emit(col, fret, dead = false, width = end - col + 1, harmonic = true)
                        col = end + 1
                    } else col++
                }
                ch == 'x' || ch == 'X' -> {
                    emit(col, -1, dead = true, width = 1)
                    col++
                }
                ch == 'h' || ch == 'H' || ch == 'p' || ch == 'P' -> {
                    pendingHammer = true
                    col++
                }
                ch == '/' || ch == '\\' -> {
                    if (followsLastNote(col)) {
                        // Slide out of the previous note toward the next one.
                        last = last?.also { it.effects = it.effects.copy(slideType = 1) }
                    } else {
                        // Slide into the next note: / rises into it (from below,
                        // SlideType -1), \ falls into it (from above, -2).
                        pendingSlideIn = if (ch == '/') -1 else -2
                    }
                    col++
                }
                ch == 'b' || ch == 'B' -> {
                    val ln = last
                    if (ln != null && followsLastNote(col) && !ln.dead) {
                        // Optional target fret: 5b7, 5b(7); consumed, not a note.
                        var j = col + 1
                        if (j < body.length && body[j] == '(') j++
                        var end = j
                        while (end < body.length && body[end].isDigit()) end++
                        val target = if (end > j) body.substring(j, end).toIntOrNull() else null
                        val semis = if (target != null) (target - ln.fret).toFloat().coerceIn(0.25f, 6f) else 2f
                        var type = 1
                        var next = end
                        if (next < body.length && body[next] == ')') next++
                        if (next < body.length && (body[next] == 'r' || body[next] == 'R')) {
                            type = 2
                            next++
                        }
                        ln.effects = ln.effects.copy(bendSemitones = semis, bendType = type)
                        lastEnd = next
                        col = next
                    } else col++
                }
                ch == 'r' || ch == 'R' -> {
                    val ln = last
                    if (ln != null && ln.effects.hasBend) {
                        ln.effects = ln.effects.copy(bendType = 2)
                    }
                    col++
                }
                ch == '~' -> {
                    last?.let { it.effects = it.effects.copy(vibrato = true) }
                    col++
                }
                else -> col++
            }
        }
        return out
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
