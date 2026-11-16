package com.delukz.tabplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private val PC_STEP = intArrayOf(0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6)
private val PC_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** Diatonic staff step (C4 = 28) and whether the pitch is a sharp. */
private fun stepOf(midi: Int): Pair<Int, Boolean> {
    val pc = ((midi % 12) + 12) % 12
    val oct = midi / 12 - 1
    val step = PC_STEP[pc]
    return if (step >= 0) (oct * 7 + step) to false
    else (oct * 7 + PC_STEP[(pc + 11) % 12]) to true
}

fun pitchName(midi: Int): String = "${PC_NAMES[((midi % 12) + 12) % 12]}${midi / 12 - 1}"

private fun drumLabel(pitch: Int): String = when (pitch) {
    35, 36 -> "BD"; 38, 40 -> "SN"; 39 -> "CL"; 42, 44 -> "HH"; 46 -> "OH"
    41, 43 -> "LT"; 45, 47 -> "MT"; 48, 50 -> "HT"; 49, 57 -> "CR"; 51, 59 -> "RD"; 56 -> "CB"
    else -> "•"
}

@Composable
fun ScoreView(
    song: SongData,
    track: TrackData,
    currentTick: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selectedBars: IntRange? = null,
) {
    val systems = remember(song, track) {
        song.measures.chunked(2)
    }
    // Previous / next note on the same string, by index in track.notes
    // (drives slide lines and hammer-on/pull-off slurs).
    val links = remember(track) {
        val prev = HashMap<Int, Int>()
        val next = HashMap<Int, Int>()
        val lastOnString = HashMap<Int, Int>()
        track.notes.forEachIndexed { i, n ->
            if (n.string > 0) {
                lastOnString[n.string]?.let { p -> prev[i] = p; next[p] = i }
                lastOnString[n.string] = i
            }
        }
        prev to next
    }
    val listState = rememberLazyListState()
    val currentSystem = remember(currentTick, song) {
        val mi = song.measures.indexOfFirst { currentTick >= it.startTick && currentTick < it.startTick + it.lengthTicks }
        if (mi < 0) 0 else mi / 2
    }
    LaunchedEffect(currentSystem) {
        if (currentSystem in systems.indices) {
            listState.animateScrollToItem(currentSystem)
        }
    }
    // The most recently started note onset = "current" notes to highlight.
    val currentOnset = remember(currentTick, track) {
        track.notes.filter { it.startTick <= currentTick }.maxOfOrNull { it.startTick } ?: -1L
    }
    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(systems) { _, systemMeasures ->
            SystemCanvas(
                song = song,
                track = track,
                measures = systemMeasures,
                currentTick = currentTick,
                currentOnset = currentOnset,
                onSeek = onSeek,
                prevOnString = links.first,
                nextOnString = links.second,
                selectedBars = selectedBars,
            )
        }
    }
}

@Composable
private fun SystemCanvas(
    song: SongData,
    track: TrackData,
    measures: List<MeasureInfo>,
    currentTick: Long,
    currentOnset: Long,
    onSeek: (Long) -> Unit,
    prevOnString: Map<Int, Int>,
    nextOnString: Map<Int, Int>,
    selectedBars: IntRange?,
) {
    val textMeasurer = rememberTextMeasurer()
    val lineColor = Color(0xFF9E9E9E)
    val noteColor = Color(0xFF212121)
    val highlight = Color(0xFF1565C0)
    val bg = Color(0xFFFAFAFA)
    val stringCount = track.strings.size
    val tabLines = when {
        track.isPercussion -> 1
        stringCount > 0 -> stringCount
        else -> 1
    }
    val heightDp = 158 + (tabLines - 1) * 13
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .pointerInput(measures) {
                detectTapGestures { offset ->
                    val leftPad = 40.dp.toPx()
                    val usable = size.width - leftPad
                    if (offset.x > leftPad && measures.isNotEmpty()) {
                        val mw = usable / measures.size
                        val mi = ((offset.x - leftPad) / mw).toInt().coerceIn(0, measures.size - 1)
                        val m = measures[mi]
                        val frac = ((offset.x - leftPad - mi * mw) / mw).coerceIn(0f, 1f)
                        onSeek(m.startTick + (frac * m.lengthTicks).toLong())
                    }
                }
            }
    ) {
        drawRect(bg)
        val leftPad = 40.dp.toPx()
        val usable = size.width - leftPad
        val mw = usable / measures.size.coerceAtLeast(1)
        val staffTop = 26.dp.toPx()
        val lineGap = 9.dp.toPx()
        val staffBottom = staffTop + 4 * lineGap
        val tabTop = staffBottom + 34.dp.toPx()
        val tabGap = 13.dp.toPx()

        // Clef-ish label + time signature on the first system.
        if (measures.firstOrNull()?.number == 1) {
            val label = if (track.kind == TrackKind.BASS) "Bass" else if (track.isPercussion) "Drums" else "Treble"
            drawText(textMeasurer, label, Offset(4.dp.toPx(), staffTop), TextStyle(fontSize = 10.sp, color = lineColor))
            val m0 = measures.first()
            drawText(
                textMeasurer, "${m0.numerator}", Offset(leftPad + 3.dp.toPx(), staffTop - 2.dp.toPx()),
                TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = noteColor)
            )
            drawText(
                textMeasurer, "${m0.denominator}", Offset(leftPad + 3.dp.toPx(), staffTop + 2 * lineGap - 2.dp.toPx()),
                TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = noteColor)
            )
        }

        // Staff lines
        for (i in 0..4) {
            val y = staffTop + i * lineGap
            drawLine(lineColor, Offset(leftPad, y), Offset(size.width - 4.dp.toPx(), y), strokeWidth = 1.dp.toPx())
        }
        // Tab lines
        for (i in 0 until tabLines) {
            val y = tabTop + i * tabGap
            drawLine(lineColor, Offset(leftPad, y), Offset(size.width - 4.dp.toPx(), y), strokeWidth = 1.dp.toPx())
        }
        // String labels
        if (!track.isPercussion && stringCount > 0) {
            for (i in 0 until stringCount) {
                val name = PC_NAMES[((track.strings[i] % 12) + 12) % 12]
                drawText(
                    textMeasurer, name, Offset(14.dp.toPx(), tabTop + i * tabGap - 6.dp.toPx()),
                    TextStyle(fontSize = 10.sp, color = lineColor)
                )
            }
        }

        val isBass = track.kind == TrackKind.BASS
        val bottomStep = if (isBass) 18 else 30 // G2 bass clef / E4 treble clef bottom line
        val transpose = if (track.isPercussion) 0 else 12

        // X position of any tick inside this system (null when outside it).
        fun xFor(tick: Long): Float? {
            for ((j, mm) in measures.withIndex()) {
                if (tick >= mm.startTick && tick < mm.startTick + mm.lengthTicks) {
                    val f = (tick - mm.startTick).toFloat() / mm.lengthTicks.toFloat()
                    return leftPad + j * mw + 14.dp.toPx() + f * (mw - 20.dp.toPx())
                }
            }
            return null
        }

        for ((mi, m) in measures.withIndex()) {
            val mx = leftPad + mi * mw
            // Practice-loop selection shading
            if (selectedBars != null && m.number in selectedBars) {
                drawRect(
                    highlight.copy(alpha = 0.10f),
                    Offset(mx, staffTop - 16.dp.toPx()),
                    Size(mw, tabTop + (tabLines - 1) * tabGap + 12.dp.toPx() - (staffTop - 16.dp.toPx())),
                )
            }
            // Bar lines
            drawLine(noteColor, Offset(mx, staffTop), Offset(mx, staffBottom), strokeWidth = 1.2.dp.toPx())
            drawLine(noteColor, Offset(mx, tabTop), Offset(mx, tabTop + (tabLines - 1) * tabGap), strokeWidth = 1.2.dp.toPx())
            if (mi == measures.lastIndex) {
                drawLine(noteColor, Offset(mx + mw - 1.dp.toPx(), staffTop), Offset(mx + mw - 1.dp.toPx(), staffBottom), strokeWidth = 1.2.dp.toPx())
                drawLine(noteColor, Offset(mx + mw - 1.dp.toPx(), tabTop), Offset(mx + mw - 1.dp.toPx(), tabTop + (tabLines - 1) * tabGap), strokeWidth = 1.2.dp.toPx())
            }
            // Measure number
            drawText(
                textMeasurer, "${m.number}", Offset(mx + 2.dp.toPx(), 4.dp.toPx()),
                TextStyle(fontSize = 9.sp, color = lineColor)
            )

            val mNotes = track.notes.withIndex()
                .filter { it.value.startTick >= m.startTick && it.value.startTick < m.startTick + m.lengthTicks }
            for ((noteIndex, note) in mNotes) {
                val frac = (note.startTick - m.startTick).toFloat() / m.lengthTicks.toFloat()
                val x = mx + 14.dp.toPx() + frac * (mw - 20.dp.toPx())
                val isCurrent = note.startTick == currentOnset && currentTick >= 0
                val color = if (isCurrent) highlight else noteColor

                // --- Staff note ---
                val written = note.pitch + transpose
                val (step, sharp) = stepOf(written)
                val y = staffBottom - (step - bottomStep) * (lineGap / 2)
                // Ledger lines
                var ls = bottomStep - 2
                while (ls >= step - (if ((step - bottomStep) % 2 == 0) 0 else 1)) {
                    if (ls < bottomStep) {
                        val ly = staffBottom - (ls - bottomStep) * (lineGap / 2)
                        drawLine(noteColor, Offset(x - 9.dp.toPx(), ly), Offset(x + 9.dp.toPx(), ly), strokeWidth = 1.dp.toPx())
                    }
                    ls -= 2
                }
                var ts = bottomStep + 10
                while (ts <= step + (if ((step - bottomStep) % 2 == 0) 0 else 1)) {
                    if (ts > bottomStep + 8) {
                        val ly = staffBottom - (ts - bottomStep) * (lineGap / 2)
                        drawLine(noteColor, Offset(x - 9.dp.toPx(), ly), Offset(x + 9.dp.toPx(), ly), strokeWidth = 1.dp.toPx())
                    }
                    ts += 2
                }
                if (sharp) {
                    drawText(textMeasurer, "#", Offset(x - 17.dp.toPx(), y - 7.dp.toPx()),
                        TextStyle(fontSize = 11.sp, color = color))
                }
                val headW = 10.dp.toPx(); val headH = 7.5.dp.toPx()
                if (note.durationTicks >= 2 * PPQ) {
                    drawOval(color, Offset(x - headW / 2, y - headH / 2), Size(headW, headH), style = Stroke(width = 1.6.dp.toPx()))
                } else {
                    drawOval(color, Offset(x - headW / 2, y - headH / 2), Size(headW, headH))
                }
                // Stem (up below middle line, down above)
                val stemLen = 26.dp.toPx()
                if (step <= bottomStep + 4) {
                    drawLine(color, Offset(x + headW / 2 - 1, y), Offset(x + headW / 2 - 1, y - stemLen), strokeWidth = 1.4.dp.toPx())
                } else {
                    drawLine(color, Offset(x - headW / 2 + 1, y), Offset(x - headW / 2 + 1, y + stemLen), strokeWidth = 1.4.dp.toPx())
                }
                if (note.dead) {
                    drawText(textMeasurer, "x", Offset(x - 3.dp.toPx(), y - 6.dp.toPx()),
                        TextStyle(fontSize = 10.sp, color = color))
                }

                // --- Tab note ---
                if (track.isPercussion) {
                    val label = drumLabel(note.pitch)
                    val tw = textMeasurer.measure(label, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)).size.width
                    drawRect(bg, Offset(x - tw / 2 - 2.dp.toPx(), tabTop - 7.dp.toPx()), Size(tw + 4.dp.toPx(), 14.dp.toPx()))
                    drawText(textMeasurer, label, Offset(x - tw / 2, tabTop - 6.dp.toPx()),
                        TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color))
                } else if (stringCount > 0 && note.string in 1..stringCount) {
                    val ty = tabTop + (note.string - 1) * tabGap
                    val label = when {
                        note.dead -> "x"
                        note.effects.harmonicType == 1 -> "<${note.fret}>" // natural harmonic
                        else -> note.fret.toString()
                    }
                    val style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = color)
                    val measured = textMeasurer.measure(label, style)
                    drawRect(bg, Offset(x - measured.size.width / 2 - 1.5.dp.toPx(), ty - 7.dp.toPx()),
                        Size(measured.size.width + 3.dp.toPx(), 14.dp.toPx()))
                    drawText(textMeasurer, label, Offset(x - measured.size.width / 2, ty - 6.5.dp.toPx()), style)
                } else {
                    // Keys/other: note name under the staff.
                    val label = pitchName(note.pitch)
                    drawText(textMeasurer, label, Offset(x - 8.dp.toPx(), tabTop - 6.dp.toPx()),
                        TextStyle(fontSize = 10.sp, color = color))
                }

                // --- Technique notations ---
                val fx = note.effects
                if (!fx.isPlain && stringCount > 0 && note.string in 1..stringCount) {
                    val ty = tabTop + (note.string - 1) * tabGap
                    val prevIdx = prevOnString[noteIndex]
                    val prevNote = prevIdx?.let { track.notes[it] }
                    val nextIdx = nextOnString[noteIndex]
                    val nextNote = nextIdx?.let { track.notes[it] }
                    val techStyle = TextStyle(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = color)

                    // Lower annotation row: PM / harmonic kind / vibrato / let ring.
                    val tags = mutableListOf<String>()
                    if (fx.palmMute && prevNote?.effects?.palmMute != true) tags.add("PM")
                    when (fx.harmonicType) {
                        2 -> tags.add("AH")
                        3 -> tags.add("TH")
                        4 -> tags.add("PH")
                        5 -> tags.add("SH")
                    }
                    if (fx.letRing && prevNote?.effects?.letRing != true) tags.add("l.r.")
                    if (fx.vibrato) tags.add("~")
                    if (tags.isNotEmpty()) {
                        drawText(
                            textMeasurer, tags.joinToString(" "), Offset(x - 6.dp.toPx(), tabTop - 11.dp.toPx()),
                            techStyle,
                        )
                    }
                    // Palm-mute continuation dashes toward the next muted note.
                    if (fx.palmMute && nextNote?.effects?.palmMute == true) {
                        val nx = xFor(nextNote.startTick)
                        if (nx != null) {
                            var dx = x + 10.dp.toPx()
                            while (dx < nx - 8.dp.toPx()) {
                                drawLine(color, Offset(dx, tabTop - 6.dp.toPx()), Offset(dx + 4.dp.toPx(), tabTop - 6.dp.toPx()), strokeWidth = 1.dp.toPx())
                                dx += 7.dp.toPx()
                            }
                        }
                    }
                    // Staccato dot above the staff note head.
                    if (fx.staccato) {
                        drawCircle(color = color, center = Offset(x, y - 13.dp.toPx()), radius = 1.8.dp.toPx())
                    }
                    // Bend: curved arrow above the tab, labelled with the amount.
                    if (fx.hasBend) {
                        val bendLabel = when {
                            fx.bendSemitones >= 1.9f -> "full"
                            fx.bendSemitones >= 0.9f -> "½"
                            fx.bendSemitones >= 0.4f -> "¼"
                            else -> fx.bendSemitones.toString()
                        }
                        if (fx.bendType in intArrayOf(4, 5)) {
                            // Prebend: straight up arrow (note starts already bent).
                            drawLine(color, Offset(x - 4.dp.toPx(), tabTop - 12.dp.toPx()), Offset(x - 4.dp.toPx(), tabTop - 26.dp.toPx()), strokeWidth = 1.3.dp.toPx())
                            drawLine(color, Offset(x - 7.dp.toPx(), tabTop - 22.dp.toPx()), Offset(x - 4.dp.toPx(), tabTop - 27.dp.toPx()), strokeWidth = 1.3.dp.toPx())
                            drawLine(color, Offset(x - 1.dp.toPx(), tabTop - 22.dp.toPx()), Offset(x - 4.dp.toPx(), tabTop - 27.dp.toPx()), strokeWidth = 1.3.dp.toPx())
                        } else {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(x, tabTop - 12.dp.toPx())
                                quadraticBezierTo(x + 9.dp.toPx(), tabTop - 27.dp.toPx(), x + 17.dp.toPx(), tabTop - 23.dp.toPx())
                            }
                            drawPath(path, color, style = Stroke(width = 1.3.dp.toPx()))
                            drawLine(color, Offset(x + 12.dp.toPx(), tabTop - 24.dp.toPx()), Offset(x + 18.dp.toPx(), tabTop - 24.dp.toPx()), strokeWidth = 1.3.dp.toPx())
                            drawLine(color, Offset(x + 16.dp.toPx(), tabTop - 28.dp.toPx()), Offset(x + 18.dp.toPx(), tabTop - 23.dp.toPx()), strokeWidth = 1.3.dp.toPx())
                        }
                        drawText(textMeasurer, bendLabel, Offset(x + 3.dp.toPx(), tabTop - 33.dp.toPx()), techStyle)
                    }
                    // Slides drawn on the tab line.
                    if (fx.slideOut) {
                        val nx = nextNote?.let { xFor(it.startTick) }
                        if (nx != null && nextNote != null) {
                            val nty = tabTop + (nextNote.string - 1) * tabGap
                            drawLine(color, Offset(x + 8.dp.toPx(), ty), Offset(nx - 8.dp.toPx(), nty), strokeWidth = 1.2.dp.toPx())
                        } else {
                            val dy = if (fx.slideType == 3) 6.dp.toPx() else -6.dp.toPx()
                            drawLine(color, Offset(x + 8.dp.toPx(), ty), Offset(x + 22.dp.toPx(), ty + dy), strokeWidth = 1.2.dp.toPx())
                        }
                    }
                    if (fx.slideIn) {
                        val dy = if (fx.slideType == -1) 7.dp.toPx() else -7.dp.toPx()
                        drawLine(color, Offset(x - 21.dp.toPx(), ty + dy), Offset(x - 7.dp.toPx(), ty), strokeWidth = 1.2.dp.toPx())
                    }
                    // Hammer-on / pull-off: slur arc from the previous note + H/P.
                    if (fx.hammerPull && prevNote != null) {
                        val px = xFor(prevNote.startTick)
                        if (px != null) {
                            val pty = tabTop + (prevNote.string - 1) * tabGap
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(px, pty - 10.dp.toPx())
                                quadraticBezierTo((px + x) / 2, tabTop - 24.dp.toPx(), x, ty - 10.dp.toPx())
                            }
                            drawPath(path, color, style = Stroke(width = 1.1.dp.toPx()))
                            val letter = if (note.pitch < prevNote.pitch) "P" else "H"
                            drawText(
                                textMeasurer, letter,
                                Offset((px + x) / 2 - 3.dp.toPx(), tabTop - 25.dp.toPx()),
                                techStyle,
                            )
                        }
                    }
                }
            }

            // Playhead
            if (currentTick >= m.startTick && currentTick < m.startTick + m.lengthTicks) {
                val frac = (currentTick - m.startTick).toFloat() / m.lengthTicks.toFloat()
                val x = mx + 14.dp.toPx() + frac * (mw - 20.dp.toPx())
                drawLine(highlight, Offset(x, staffTop - 6.dp.toPx()), Offset(x, tabTop + (tabLines - 1) * tabGap + 8.dp.toPx()),
                    strokeWidth = 2.dp.toPx())
            }
        }
        @Suppress("UNUSED_EXPRESSION") abs(0)
        @Suppress("UNUSED_EXPRESSION") song
    }
}
