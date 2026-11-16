package com.delukz.tabplayer

import java.nio.charset.Charset

/** Little-endian byte reader for the Guitar Pro binary formats. */
class GpReader(private val data: ByteArray) {
    var pos = 0
        private set

    fun remaining() = data.size - pos

    fun u8(): Int {
        if (pos >= data.size) throw TabParseException("Unexpected end of file")
        return data[pos++].toInt() and 0xFF
    }

    fun i8(): Int {
        val v = u8()
        return if (v > 127) v - 256 else v
    }

    fun bool(): Boolean = u8() != 0

    fun i16(): Int {
        val lo = u8(); val hi = u8()
        val v = lo or (hi shl 8)
        return if (v > 32767) v - 65536 else v
    }

    fun i32(): Int {
        var v = 0
        for (i in 0..3) v = v or (u8() shl (8 * i))
        return v
    }

    fun f64(): Double {
        var bits = 0L
        for (i in 0..7) bits = bits or (u8().toLong() shl (8 * i))
        return Double.fromBits(bits)
    }

    fun skip(n: Int) {
        if (n < 0 || pos + n > data.size) throw TabParseException("Unexpected end of file")
        pos += n
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0 || pos + n > data.size) throw TabParseException("Unexpected end of file")
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** 1 length byte followed by [count] bytes of field; string is the first [size] bytes. */
    fun byteSizeString(count: Int): String {
        val size = u8()
        val raw = bytes(count)
        val len = minOf(size, raw.size)
        return String(raw, 0, len, GP_CHARSET)
    }

    /** int length followed by that many bytes. */
    fun intSizeString(): String {
        val count = i32()
        if (count < 0 || count > 1_000_000) throw TabParseException("Bad string size")
        return String(bytes(count), GP_CHARSET)
    }

    /** int byte-count, then a byte-size string occupying count-1 more bytes. */
    fun intByteSizeString(): String {
        val count = i32()
        if (count < 1 || count > 100_000) throw TabParseException("Bad string size")
        return byteSizeString(count - 1)
    }

    companion object {
        val GP_CHARSET: Charset = Charset.forName("windows-1252")
    }
}

private class GpTrackRaw(
    val name: String,
    val tuning: List<Int>,       // string 1 (highest) first
    val isPercussion: Boolean,
    val instrument: Int,
)

private class GpHeader(val numerator: Int, val denominator: Int) {
    val lengthTicks: Long get() = numerator * (4 * PPQ) / denominator
}

/**
 * Parser for Guitar Pro 3, 4 and 5 binary files (.gp3/.gp4/.gp5).
 * Structure mirrors the documented format (as implemented by PyGuitarPro/AlphaTab).
 */
object GpParser {

    fun parse(data: ByteArray): SongData {
        val r = GpReader(data)
        val versionStr = r.byteSizeString(30)
        val major = Regex("""v(\d+)\.""").find(versionStr)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw TabParseException("Not a Guitar Pro file (version: $versionStr)")
        if (major !in 3..5) throw TabParseException("Unsupported Guitar Pro version: $versionStr")
        val minor = Regex("""v\d+\.(\d+)""").find(versionStr)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val is51 = major == 5 && minor >= 1
        val isClipboard = versionStr.startsWith("CLIPBOARD")

        if (isClipboard && major >= 4) {
            // startMeasure, stopMeasure, startTrack, stopTrack
            repeat(4) { r.i32() }
            if (major == 5) { r.i32(); r.i32(); r.i32() } // startBeat, stopBeat, subBarCopy
        }

        // ---- Info ----
        val title = r.intByteSizeString()
        r.intByteSizeString() // subtitle
        val artist = r.intByteSizeString()
        r.intByteSizeString() // album
        r.intByteSizeString() // words
        if (major == 5) r.intByteSizeString() // music (only GP5 stores it separately; GP3/GP4 have 8 info strings)
        r.intByteSizeString() // copyright
        r.intByteSizeString() // tabbed by
        r.intByteSizeString() // instructions
        val noticeCount = r.i32()
        repeat(noticeCount.coerceIn(0, 10000)) { r.intByteSizeString() }

        // ---- Triplet feel / lyrics / GP5 extras ----
        if (major == 3) {
            r.bool() // triplet feel
        } else if (major == 4) {
            r.bool() // triplet feel
            readLyrics(r)
        } else {
            readLyrics(r)
            // RSE master effect (5.1+)
            if (is51) {
                r.i32() // master volume
                r.i32() // reserved
                readEqualizer(r, 11)
            }
            readPageSetup(r)
        }

        // ---- Tempo / key ----
        if (major == 5) r.intByteSizeString() // tempo name
        val tempo = r.i32()
        if (major == 5) {
            if (is51) r.bool() // hide tempo (only > 5.0.0)
            r.i8() // key
            r.i32() // octave
        } else if (major == 4) {
            r.i32() // key
            r.i8() // octave
        } else {
            r.i32() // key
        }

        // ---- MIDI channels (64) ----
        val channelInstruments = IntArray(64)
        for (i in 0 until 64) {
            channelInstruments[i] = r.i32()
            repeat(6) { r.i8() } // volume, balance, chorus, reverb, phaser, tremolo
            r.skip(2)
        }

        if (major == 5) {
            repeat(19) { r.i16() } // directions
            r.i32() // master reverb
        }

        val measureCount = r.i32()
        val trackCount = r.i32()
        if (measureCount < 0 || measureCount > 100000 || trackCount < 0 || trackCount > 256) {
            throw TabParseException("Implausible measure/track count")
        }

        // ---- Measure headers ----
        val headers = mutableListOf<GpHeader>()
        var prevNum = 4
        var prevDen = 4
        for (n in 1..measureCount) {
            val h = readMeasureHeader(r, major, n, prevNum, prevDen)
            headers.add(h)
            prevNum = h.numerator; prevDen = h.denominator
        }

        // ---- Tracks ----
        val tracksRaw = mutableListOf<GpTrackRaw>()
        for (t in 1..trackCount) {
            tracksRaw.add(readTrack(r, major, is51, t, channelInstruments))
        }
        if (major == 5) r.skip(if (!is51) 2 else 1) // trailing blank after tracks

        // ---- Measures ----
        val tempoChanges = mutableListOf<Pair<Long, Int>>(0L to tempo)
        val notesByTrack = Array(trackCount) { mutableListOf<NoteEvent>() }
        val measures = mutableListOf<MeasureInfo>()
        var measureStart = 0L
        for ((mi, header) in headers.withIndex()) {
            measures.add(MeasureInfo(mi + 1, measureStart, header.lengthTicks, header.numerator, header.denominator))
            for (ti in 0 until trackCount) {
                val track = tracksRaw[ti]
                val ctx = BeatContext(track, notesByTrack[ti], measureStart, tempoChanges)
                if (major == 5) {
                    readVoice(r, major, is51, ctx) // voice 1
                    readVoice(r, major, is51, ctx) // voice 2
                    if (r.remaining() > 0) r.u8() // line break (absent at EOF in some files)
                } else {
                    readVoice(r, major, is51, ctx)
                }
            }
            measureStart += header.lengthTicks
        }

        val tracks = tracksRaw.mapIndexed { i, raw ->
            TrackData(raw.name, raw.tuning, raw.isPercussion, raw.instrument,
                notesByTrack[i].sortedBy { it.startTick })
        }
        return SongData(
            title = title.ifBlank { "Untitled" },
            artist = artist,
            tempoBpm = if (tempo in 20..400) tempo else 120,
            measures = measures,
            tracks = tracks,
            sourceFormat = "Guitar Pro $major",
            tempoMap = tempoChanges.distinctBy { it.first }.sortedBy { it.first },
        )
    }

    private fun readEqualizer(r: GpReader, knobs: Int) {
        repeat(knobs) { r.i8() }
    }

    private fun readLyrics(r: GpReader) {
        r.i32() // track choice
        repeat(5) {
            r.i32() // starting measure
            r.intSizeString()
        }
    }

    private fun readPageSetup(r: GpReader) {
        repeat(7) { r.i32() } // page size (2) + padding (4) + score proportion (1)
        r.i16() // header/footer elements
        repeat(10) { r.intByteSizeString() } // title..pageNumber (copyright is 2 strings -> total 10 reads covers: title, subtitle, artist, album, words, music, wordsAndMusic, copyright1, copyright2, pageNumber)
    }

    private fun readMarker(r: GpReader) {
        r.intByteSizeString() // title
        r.skip(4) // color
    }

    private fun readMeasureHeader(r: GpReader, major: Int, number: Int, prevNum: Int, prevDen: Int): GpHeader {
        if (major == 5 && number > 1) r.skip(1) // blank byte before header (not first)
        val flags = r.u8()
        var num = prevNum
        var den = prevDen
        if (flags and 0x01 != 0) num = r.i8()
        if (flags and 0x02 != 0) den = r.i8()
        if (flags and 0x08 != 0) r.i8() // repeat close count
        if (major == 3 || major == 4) {
            if (flags and 0x10 != 0) r.u8() // repeat alternative
            if (flags and 0x20 != 0) readMarker(r)
            if (flags and 0x40 != 0) { r.i8(); r.i8() } // key signature
        } else {
            // GP5 order: marker (0x20), key (0x40), repeat alternative (0x10)
            if (flags and 0x20 != 0) readMarker(r)
            if (flags and 0x40 != 0) { r.i8(); r.i8() }
            if (flags and 0x10 != 0) r.u8() // repeat alternative
            if (flags and 0x03 != 0) repeat(4) { r.u8() } // time signature beams
            if (flags and 0x10 == 0) r.skip(1) // blank byte
            r.u8() // triplet feel
        }
        if (num <= 0) num = 4
        if (den <= 0) den = 4
        return GpHeader(num, den)
    }

    private fun readTrack(r: GpReader, major: Int, is51: Boolean, number: Int, channelInstruments: IntArray): GpTrackRaw {
        var percussion = false
        if (major == 5) {
            if (number == 1 || !is51) r.skip(1) // blank byte (first track, or every track in 5.0)
            val flags1 = r.u8()
            percussion = flags1 and 0x01 != 0
        } else {
            val flags = r.u8()
            percussion = flags and 0x01 != 0
        }
        val name = r.byteSizeString(40)
        val stringCount = r.i32().coerceIn(0, 7)
        val tuning = mutableListOf<Int>()
        for (i in 0 until 7) {
            val t = r.i32()
            if (i < stringCount) tuning.add(t)
        }
        r.i32() // port
        // channel: index + effect channel
        val chIndex = r.i32() - 1
        r.i32() // effect channel
        var instrument = 0
        if (chIndex in 0..63) {
            instrument = channelInstruments[chIndex]
            if (instrument < 0) instrument = 0
            if (chIndex % 16 == 9) percussion = true
        }
        r.i32() // fret count
        r.i32() // capo offset
        r.skip(4) // color
        if (major == 5) {
            r.i16() // settings flags
            r.u8() // auto accentuation
            r.u8() // midi bank
            r.u8() // humanize
            r.i32() // clef transpose
            r.i32() // clef transpose secondary
            r.i32() // unknown
            r.skip(12) // unknown space
            readRSEInstrument(r, is51)
            if (is51) {
                readEqualizer(r, 4)
                // RSE instrument effect: 2 strings
                r.intByteSizeString()
                r.intByteSizeString()
            }
        }
        return GpTrackRaw(name, tuning, percussion, instrument)
    }

    private fun readRSEInstrument(r: GpReader, is51: Boolean) {
        r.i32() // instrument
        r.i32() // unknown
        r.i32() // sound bank
        if (!is51) {
            r.i16() // effect number (5.0)
            r.skip(1)
        } else {
            r.i32() // effect number (5.1)
        }
    }

    private class BeatContext(
        val track: GpTrackRaw,
        val out: MutableList<NoteEvent>,
        val measureStart: Long,
        val tempoChanges: MutableList<Pair<Long, Int>>,
    )

    private fun readVoice(r: GpReader, major: Int, is51: Boolean, ctx: BeatContext) {
        val beatCount = r.i32()
        if (beatCount < 0 || beatCount > 100000) throw TabParseException("Implausible beat count")
        var start = ctx.measureStart
        repeat(beatCount) {
            start += readBeat(r, major, is51, ctx, start)
        }
    }

    /** Returns the beat's duration in ticks (0 for empty beats). */
    private fun readBeat(r: GpReader, major: Int, is51: Boolean, ctx: BeatContext, start: Long): Long {
        val flags = r.u8()
        var status = 0 // 0 normal, 1 empty, 2 rest
        if (flags and 0x40 != 0) {
            status = when (r.u8()) { 0 -> 1; 2 -> 2; else -> 0 }
        }
        val durationTicks = readDuration(r, flags)
        if (flags and 0x02 != 0) readChord(r, major, ctx.track.tuning.size)
        if (flags and 0x04 != 0) r.intByteSizeString() // text
        if (flags and 0x08 != 0) readBeatEffects(r, major)
        if (flags and 0x10 != 0) {
            val newTempo = readMixTableChange(r, major, is51)
            if (newTempo != null) ctx.tempoChanges.add(start to newTempo)
        }
        // Notes are always present in the stream (even for rests/empty beats); discard unless normal.
        readNotes(r, major, is51, ctx, start, durationTicks, discard = status != 0)
        if (major == 5) {
            val beatFlags2 = r.i16() // beat display flags
            if (beatFlags2 and 0x0800 != 0) r.u8() // break secondary beams count
        }
        return if (status == 1) 0 else durationTicks
    }

    private fun readDuration(r: GpReader, flags: Int): Long {
        val valueByte = r.i8()
        val value = 1 shl (valueByte + 2) // 1=whole .. 128
        var ticks = (4 * PPQ) / value.coerceAtLeast(1)
        if (flags and 0x01 != 0) ticks += ticks / 2 // dotted
        if (flags and 0x20 != 0) {
            val n = r.i32()
            val (enters, times) = when (n) {
                3 -> 3 to 2; 5 -> 5 to 4; 6 -> 6 to 4; 7 -> 7 to 4
                9 -> 9 to 8; 10 -> 10 to 8; 11 -> 11 to 8; 12 -> 12 to 8; 13 -> 13 to 8
                else -> 1 to 1
            }
            ticks = ticks * times / enters
        }
        return ticks
    }

    private fun readNotes(r: GpReader, major: Int, is51: Boolean, ctx: BeatContext, start: Long, durationTicks: Long, discard: Boolean) {
        val stringFlags = r.u8()
        val stringCount = ctx.track.tuning.size
        for (stringNumber in 1..stringCount) {
            if (stringFlags and (1 shl (7 - stringNumber)) == 0) continue
            readNote(r, major, is51, ctx, stringNumber, start, durationTicks, discard = discard)
        }
    }

    private fun readNote(r: GpReader, major: Int, is51: Boolean, ctx: BeatContext, stringNumber: Int,
                         start: Long, durationTicks: Long, discard: Boolean) {
        val flags = r.u8()
        var type = 1 // 1 normal, 2 tie, 3 dead
        if (flags and 0x20 != 0) type = r.u8()
        if (major != 5 && flags and 0x01 != 0) { r.i8(); r.i8() } // time-independent duration (GP3/4)
        var velocity = 95
        if (flags and 0x10 != 0) {
            val dyn = r.i8()
            velocity = (15 + 16 * dyn - 16).coerceIn(1, 127)
        }
        var fret = 0
        var hasFret = false
        if (flags and 0x20 != 0) {
            fret = r.i8()
            hasFret = true
        }
        if (flags and 0x80 != 0) { r.i8(); r.i8() } // fingering
        if (major == 5) {
            if (flags and 0x01 != 0) r.f64() // duration percent
            r.u8() // flags2
        }
        var effects = NO_EFFECTS
        if (flags and 0x08 != 0) effects = readNoteEffects(r, major)
        if (discard || !hasFret) return
        if (type == 2) { // tie: reuse fret of previous note on same string (keep stored fret if none)
            val prev = ctx.out.lastOrNull { it.string == stringNumber && !it.tie }
            if (prev != null) fret = prev.fret
            if (fret < 0) fret = 0
        }
        val pitch = if (ctx.track.isPercussion) {
            fret // GM drum pitch stored as the note value
        } else {
            val open = ctx.track.tuning.getOrNull(stringNumber - 1) ?: return
            open + fret
        }
        ctx.out.add(
            NoteEvent(
                startTick = start,
                durationTicks = durationTicks,
                pitch = pitch,
                string = if (ctx.track.isPercussion) 0 else stringNumber,
                fret = if (ctx.track.isPercussion) -1 else fret,
                velocity = velocity,
                dead = type == 3,
                tie = type == 2,
                effects = effects,
            )
        )
    }

    /**
     * Reads note effects and returns them as a [NoteEffects]. The byte
     * consumption order is unchanged from the plain-skip version: flag-only
     * bits (hammer, let-ring, palm mute, staccato, vibrato) carry no payload.
     */
    private fun readNoteEffects(r: GpReader, major: Int): NoteEffects {
        if (major == 3) {
            val flags = r.u8()
            var bendSemitones = 0f
            var bendType = 0
            if (flags and 0x01 != 0) {
                val (st, type) = readBend(r)
                bendSemitones = st; bendType = type
            }
            if (flags and 0x10 != 0) readGrace(r, major)
            return NoteEffects(
                bendSemitones = bendSemitones,
                bendType = bendType,
                slideType = if (flags and 0x04 != 0) 1 else 0, // shift slide, no payload in GP3
                hammerPull = flags and 0x02 != 0,
                letRing = flags and 0x08 != 0,
            )
        }
        val flags1 = r.i8()
        val flags2 = r.i8()
        var bendSemitones = 0f
        var bendType = 0
        if (flags1 and 0x01 != 0) {
            val (st, type) = readBend(r)
            bendSemitones = st; bendType = type
        }
        if (flags1 and 0x10 != 0) readGrace(r, major)
        if (flags2 and 0x04 != 0) r.i8() // tremolo picking (display/playback not modelled)
        var slideType = 0
        if (flags2 and 0x08 != 0) {
            val raw = r.i8()
            // GP5 stores slides as a bitmask; GP4 stores the signed type number.
            slideType = if (major == 5) decodeGp5SlideMask(raw) else raw
        }
        var harmonicType = 0
        if (flags2 and 0x10 != 0) { // harmonic
            if (major == 5) {
                val type = r.i8()
                harmonicType = type
                when (type) {
                    2 -> { r.u8(); r.i8(); r.u8() } // artificial: note, accidental, octave
                    3 -> r.u8() // tapped: fret
                }
            } else {
                harmonicType = r.i8() // harmonic type (GP4)
            }
        }
        if (flags2 and 0x20 != 0) { r.i8(); r.i8() } // trill (not modelled)
        return NoteEffects(
            bendSemitones = bendSemitones,
            bendType = bendType,
            slideType = slideType,
            hammerPull = flags1 and 0x02 != 0,
            palmMute = flags2 and 0x02 != 0,
            letRing = flags1 and 0x04 != 0,
            staccato = flags2 and 0x01 != 0,
            vibrato = flags2 and 0x40 != 0,
            harmonicType = harmonicType,
        )
    }

    /**
     * GP5 slide bitmask -> SlideType value. Bits: 0x01 shift, 0x02 legato,
     * 0x04 out downwards, 0x08 out upwards, 0x10 into from below, 0x20 into
     * from above. A note can carry several; prefer the "to next note" kinds.
     */
    private fun decodeGp5SlideMask(raw: Int): Int {
        val b = raw and 0xFF
        return when {
            b and 0x02 != 0 -> 2
            b and 0x01 != 0 -> 1
            b and 0x04 != 0 -> 3
            b and 0x08 != 0 -> 4
            b and 0x10 != 0 -> -1
            b and 0x20 != 0 -> -2
            else -> 0
        }
    }

    /** Returns (peak bend in semitones, bend type). GP bend values are in 1/100 of a tone. */
    private fun readBend(r: GpReader): Pair<Float, Int> {
        val type = r.i8()
        var maxValue = r.i32() // total value
        val count = r.i32()
        repeat(count.coerceIn(0, 1000)) {
            r.i32() // position
            val v = r.i32() // point value
            if (v > maxValue) maxValue = v
            r.bool() // vibrato
        }
        // 100 units = 1 tone = 2 semitones.
        return (maxValue / 50f) to type
    }

    private fun readGrace(r: GpReader, major: Int) {
        if (major == 5) {
            r.u8(); r.u8(); r.u8(); r.u8(); r.u8() // fret, velocity, transition, duration, flags
        } else {
            r.i8() // fret
            r.u8() // velocity
            r.u8() // duration
            r.i8() // transition
        }
    }

    private fun readChord(r: GpReader, major: Int, stringCount: Int) {
        val newFormat = r.bool()
        if (!newFormat) {
            r.intByteSizeString() // name
            val firstFret = r.i32()
            if (firstFret != 0) repeat(6) { r.i32() }
            return
        }
        if (major == 3) {
            r.bool() // sharp
            r.skip(3)
            r.i32(); r.i32(); r.i32(); r.i32(); r.i32() // root, type, extension, bass, tonality
            r.bool() // add
            r.byteSizeString(22) // name
            r.i32(); r.i32(); r.i32() // fifth, ninth, eleventh
            r.i32() // first fret
            repeat(6) { r.i32() } // frets
            r.i32() // barre count
            repeat(2) { r.i32() }; repeat(2) { r.i32() }; repeat(2) { r.i32() } // barre frets/starts/ends
            repeat(7) { r.bool() } // omissions
            r.skip(1)
        } else {
            r.bool() // sharp
            r.skip(3)
            r.u8(); r.u8(); r.u8() // root, type, extension
            r.i32() // bass
            r.i32() // tonality
            r.bool() // add
            r.byteSizeString(22) // name
            r.u8(); r.u8(); r.u8() // fifth, ninth, eleventh
            r.i32() // first fret
            repeat(7) { r.i32() } // frets
            r.u8() // barre count
            repeat(5) { r.u8() }; repeat(5) { r.u8() }; repeat(5) { r.u8() } // barre frets/starts/ends
            repeat(7) { r.bool() } // omissions
            r.skip(1)
            repeat(7) { r.i8() } // fingerings
            r.bool() // show
        }
    }

    private fun readBeatEffects(r: GpReader, major: Int) {
        if (major == 3) {
            val flags1 = r.u8()
            if (flags1 and 0x20 != 0) {
                val v = r.u8()
                if (v == 0) r.i32() // tremolo bar value
                else r.i32() // tap/slap/pop: an int follows in GP3 encoding
            }
            if (flags1 and 0x40 != 0) { r.i8(); r.i8() } // stroke
        } else {
            val flags1 = r.i8()
            val flags2 = r.i8()
            if (flags1 and 0x20 != 0) r.i8() // slap effect
            if (flags2 and 0x04 != 0) readBend(r) // tremolo bar (bend format in GP4/5)
            if (flags1 and 0x40 != 0) { r.i8(); r.i8() } // stroke
            if (flags2 and 0x02 != 0) r.i8() // pick stroke
        }
    }

    /** Returns the new tempo if the mix table change sets one. */
    private fun readMixTableChange(r: GpReader, major: Int, is51: Boolean): Int? {
        var tempoValue: Int? = null
        val present = BooleanArray(8) // volume, balance, chorus, reverb, phaser, tremolo, tempo
        if (major == 5) {
            r.i8() // instrument
            readRSEInstrument(r, is51)
            if (!is51) r.skip(1)
            for (i in 0..5) {
                val v = r.i8()
                if (v >= 0) present[i] = true
            }
            r.intByteSizeString() // tempo name
            val t = r.i32()
            if (t >= 0) { present[6] = true; tempoValue = t }
        } else {
            val instrument = r.i8()
            for (i in 0..5) {
                val v = r.i8()
                if (v >= 0) present[i] = true
            }
            val t = r.i32()
            if (t >= 0) { present[6] = true; tempoValue = t }
            @Suppress("UNUSED_EXPRESSION") instrument
        }
        // durations for changed items (volume..tremolo, then tempo)
        for (i in 0..5) if (present[i]) r.i8()
        if (present[6]) {
            r.i8()
            if (major == 5 && is51) r.bool() // hide tempo
        }
        if (major >= 4) {
            r.i8() // mix table flags
            if (major == 5) {
                r.i8() // wah value
                if (is51) {
                    r.intByteSizeString() // RSE effect name
                    r.intByteSizeString() // RSE effect category
                }
            }
        }
        return tempoValue
    }
}
