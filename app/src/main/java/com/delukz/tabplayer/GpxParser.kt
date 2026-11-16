package com.delukz.tabplayer

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser for Guitar Pro 7 (.gp) files: a ZIP archive containing a GPIF
 * XML score (Content/score.gpxml). The reader is deliberately liberal —
 * it resolves notes by id references and scans note properties by name,
 * taking the first integer it finds, so minor schema variations in the
 * value elements don't break it.
 *
 * (Guitar Pro 6 .gpx files that use the proprietary BCFZ container are
 * not supported; .gpx files that are plain ZIP/GPIF are.)
 */
object GpxParser {

    fun parse(data: ByteArray): SongData {
        val xml = extractScoreXml(data) ?: throw TabParseException("No score.gpxml found in this file")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        } catch (e: Exception) {
            throw TabParseException("Could not parse the score XML: ${e.message}")
        }
        val root = doc.documentElement

        fun byTag(tag: String): List<Element> {
            val list = root.getElementsByTagName(tag)
            return (0 until list.length).map { list.item(it) as Element }
        }
        fun byId(tag: String): Map<String, Element> =
            byTag(tag).associateBy { it.getAttribute("id") }
        fun childText(el: Element, tag: String): String? {
            val nodes = el.getElementsByTagName(tag)
            return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
        }
        fun directChildren(el: Element, tag: String): List<Element> {
            val out = mutableListOf<Element>()
            var n = el.firstChild
            while (n != null) {
                if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == tag) out.add(n)
                n = n.nextSibling
            }
            return out
        }

        val score = byTag("Score").firstOrNull()
        val title = score?.let { childText(it, "Title") }?.ifBlank { null } ?: "Untitled"
        val artist = score?.let { childText(it, "Artist") } ?: ""

        val rhythms = byId("Rhythm")
        val notesById = byId("Note")
        val beatsById = byId("Beat")
        val voicesById = byId("Voice")
        val barsById = byId("Bar")

        // Tempo from MasterTrack automations.
        var tempo = 120
        val tempoByBar = sortedMapOf<Int, Int>()
        for (auto in byTag("Automation")) {
            if (childText(auto, "Type") == "Tempo") {
                val v = childText(auto, "Value")?.toDoubleOrNull()?.toInt() ?: continue
                val bar = childText(auto, "Bar")?.toIntOrNull() ?: 0
                tempoByBar[bar] = v
            }
        }
        tempoByBar[0]?.let { tempo = it }
        if (tempo !in 20..400) tempo = 120

        // Master bars define measures and time signatures.
        val masterBars = byTag("MasterBar")
        val measures = mutableListOf<MeasureInfo>()
        var start = 0L
        val barIdsPerMeasure = mutableListOf<List<String>>()
        for ((i, mb) in masterBars.withIndex()) {
            val time = childText(mb, "Time") ?: "4/4"
            val parts = time.split("/")
            val num = parts.getOrNull(0)?.toIntOrNull() ?: 4
            val den = parts.getOrNull(1)?.toIntOrNull() ?: 4
            val len = num * (4 * PPQ) / den
            measures.add(MeasureInfo(i + 1, start, len, num, den))
            start += len
            barIdsPerMeasure.add(childText(mb, "Bars")?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList())
        }
        if (measures.isEmpty()) throw TabParseException("No measures found in score")

        // Tracks
        val trackEls = byTag("Track")
        val tracks = mutableListOf<TrackData>()
        for ((ti, trackEl) in trackEls.withIndex()) {
            val name = childText(trackEl, "Name") ?: childText(trackEl, "ShortName") ?: "Track ${ti + 1}"
            // Tuning property anywhere inside the track element.
            var tuning = listOf<Int>()
            val props = trackEl.getElementsByTagName("Property")
            var percussion = false
            for (pi in 0 until props.length) {
                val p = props.item(pi) as Element
                when (p.getAttribute("name")) {
                    "Tuning" -> {
                        val pitches = childText(p, "Pitches")?.split(Regex("\\s+"))
                            ?.mapNotNull { it.toIntOrNull() } ?: emptyList()
                        if (pitches.isNotEmpty()) tuning = pitches
                    }
                }
            }
            if (trackEl.getElementsByTagName("Drumkit").length > 0) percussion = true

            val notes = mutableListOf<NoteEvent>()
            for ((mi, barIds) in barIdsPerMeasure.withIndex()) {
                val barId = barIds.getOrNull(ti) ?: continue
                val bar = barsById[barId] ?: continue
                val voiceIds = childText(bar, "Voices")?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
                for (voiceId in voiceIds) {
                    val voice = voicesById[voiceId] ?: continue
                    var tick = measures[mi].startTick
                    val beatIds = childText(voice, "Beats")?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
                    for (beatId in beatIds) {
                        val beat = beatsById[beatId] ?: continue
                        val rhythmRef = directChildren(beat, "Rhythm").firstOrNull()?.getAttribute("ref")
                        val rhythm = rhythmRef?.let { rhythms[it] }
                        val durTicks = rhythm?.let { rhythmTicks(it) } ?: PPQ
                        val noteIds = childText(beat, "Notes")?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
                        val isRest = directChildren(beat, "Rest").isNotEmpty() ||
                            (noteIds.isEmpty() && directChildren(beat, "Notes").isEmpty() && childText(beat, "Type") == "Rest")
                        if (!isRest) {
                            for (noteId in noteIds) {
                                val noteEl = notesById[noteId] ?: continue
                                val ev = noteEvent(noteEl, tuning, tick, durTicks)
                                if (ev != null) {
                                    if (ev.string == 0 && ev.fret == -1 && tuning.isEmpty()) percussion = true
                                    notes.add(ev)
                                }
                            }
                        }
                        tick += durTicks
                    }
                }
            }
            notes.sortBy { it.startTick }
            tracks.add(TrackData(name, tuning, percussion, instrumentFor(name, tuning, percussion), notes))
        }
        if (tracks.isEmpty()) throw TabParseException("No tracks found in score")

        val tempoMap = mutableListOf(0L to tempo)
        for ((barIdx, bpm) in tempoByBar) {
            if (barIdx in measures.indices && barIdx != 0) tempoMap.add(measures[barIdx].startTick to bpm)
        }
        return SongData(title, artist, tempo, measures, tracks, "Guitar Pro 7 (.gp)", tempoMap)
    }

    private fun instrumentFor(name: String, tuning: List<Int>, percussion: Boolean): Int = when {
        percussion -> 0
        name.contains("bass", ignoreCase = true) -> 33
        tuning.size in 4..5 -> 33
        tuning.isNotEmpty() -> 25
        name.contains("piano", ignoreCase = true) || name.contains("key", ignoreCase = true) -> 0
        else -> 25
    }

    private fun rhythmTicks(rhythm: Element): Long {
        var base = when (rhythm.getElementsByTagName("NoteValue").item(0)?.textContent?.trim()) {
            "Whole" -> 4 * PPQ
            "Half" -> 2 * PPQ
            "Quarter" -> PPQ
            "Eighth" -> PPQ / 2
            "16th" -> PPQ / 4
            "32nd" -> PPQ / 8
            "64th" -> PPQ / 16
            "128th" -> PPQ / 32
            else -> PPQ
        }
        val dots = rhythm.getElementsByTagName("AugmentationDot")
        if (dots.length > 0) {
            val count = (dots.item(0) as Element).getAttribute("count").toIntOrNull() ?: 1
            var add = base
            repeat(count) { add /= 2; base += add }
        }
        val tuplets = rhythm.getElementsByTagName("PrimaryTuplet")
        if (tuplets.length > 0) {
            val t = tuplets.item(0) as Element
            val enters = t.getAttribute("enters").toIntOrNull() ?: 1
            val times = t.getAttribute("times").toIntOrNull() ?: 1
            if (enters > 0) base = base * times / enters
        }
        return base
    }

    private fun noteEvent(noteEl: Element, tuning: List<Int>, tick: Long, durTicks: Long): NoteEvent? {
        var stringNum = 0
        var fret = -1
        var midi: Int? = null
        val props = noteEl.getElementsByTagName("Property")
        for (pi in 0 until props.length) {
            val p = props.item(pi) as Element
            val value = p.textContent.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: continue
            when (p.getAttribute("name")) {
                "String" -> stringNum = value
                "Fret" -> fret = value
                "Midi" -> midi = value
            }
        }
        val tie = noteEl.getElementsByTagName("Tie").length > 0
        val pitch = when {
            midi != null -> midi
            stringNum in 1..tuning.size && fret >= 0 -> tuning[stringNum - 1] + fret
            else -> return null
        }
        return NoteEvent(
            startTick = tick,
            durationTicks = durTicks,
            pitch = pitch,
            string = stringNum,
            fret = fret,
            tie = tie,
        )
    }

    private fun extractScoreXml(data: ByteArray): ByteArray? {
        // ZIP magic check
        if (data.size < 4 || data[0] != 0x50.toByte() || data[1] != 0x4B.toByte()) return null
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            var entry = zip.nextEntry
            var fallback: ByteArray? = null
            while (entry != null) {
                val name = entry.name.lowercase()
                if (name.endsWith(".gpxml")) {
                    val bytes = zip.readBytes()
                    if (name.endsWith("score.gpxml")) return bytes
                    fallback = bytes
                }
                entry = zip.nextEntry
            }
            return fallback
        }
    }
}
