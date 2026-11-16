package com.delukz.tabplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParserTest {

    private fun resource(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/$name")
            ?: throw AssertionError("Missing test resource $name")
        return stream.readBytes()
    }

    private fun checkFixtureSong(song: SongData, format: String) {
        assertEquals("TabPlayer Fixture", song.title)
        assertEquals(120, song.tempoBpm)
        assertEquals(2, song.measures.size)
        assertEquals(1, song.tracks.size)
        val track = song.tracks[0]
        assertEquals("Fixture Guitar", track.name)
        assertEquals(listOf(64, 59, 55, 50, 45, 40), track.strings)
        assertEquals(24, track.midiInstrument)
        assertEquals(TrackKind.GUITAR, track.kind)
        assertEquals(6, track.notes.size)
        // (start, duration, string, fret, pitch)
        val expected = listOf(
            listOf(0L, 480L, 1L, 0L, 64L),
            listOf(480L, 480L, 2L, 1L, 60L),
            listOf(960L, 480L, 3L, 0L, 55L),
            listOf(1440L, 480L, 4L, 2L, 52L),
            listOf(1920L, 960L, 5L, 3L, 48L),
            listOf(2880L, 960L, 6L, 0L, 40L),
        )
        for ((i, exp) in expected.withIndex()) {
            val n = track.notes[i]
            assertEquals("$format note $i start", exp[0], n.startTick)
            assertEquals("$format note $i duration", exp[1], n.durationTicks)
            assertEquals("$format note $i string", exp[2], n.string.toLong())
            assertEquals("$format note $i fret", exp[3], n.fret.toLong())
            assertEquals("$format note $i pitch", exp[4], n.pitch.toLong())
        }
    }

    @Test fun gp3Fixture() = checkFixtureSong(GpParser.parse(resource("fixture.gp3")), "gp3")
    @Test fun gp4Fixture() = checkFixtureSong(GpParser.parse(resource("fixture.gp4")), "gp4")
    @Test fun gp5Fixture() = checkFixtureSong(GpParser.parse(resource("fixture.gp5")), "gp5")

    @Test
    fun gp5RealFileVoices() {
        val song = GpParser.parse(resource("voices.gp5"))
        assertEquals(3, song.measures.size)
        val track = song.tracks[0]
        assertEquals(20, track.notes.size)
        // Voice 2 note: fret 6 on string 6 at tick 720.
        assertTrue(track.notes.any { it.startTick == 720L && it.string == 6 && it.fret == 6 && it.pitch == 46 })
        // Whole note in measure 2.
        assertTrue(track.notes.any { it.startTick == 1920L && it.durationTicks == 1920L && it.fret == 8 })
        // Three-note chord in measure 3 (one quarter into the measure).
        val chord = track.notes.filter { it.startTick == 4320L }
        assertEquals(3, chord.size)
    }

    @Test
    fun gp5RealFileBassTrack() {
        val song = GpParser.parse(resource("clef.gp5"))
        assertEquals(2, song.tracks.size)
        val bass = song.tracks[1]
        assertEquals(listOf(43, 38, 33, 28), bass.strings)
        assertEquals(TrackKind.BASS, bass.kind)
        assertEquals(4, bass.notes.size)
        assertTrue(bass.notes.all { it.pitch == 28 })
    }

    @Test
    fun gp5RealFileDrums() {
        val song = GpParser.parse(resource("funky.gp5"))
        assertEquals(125, song.tempoBpm)
        assertEquals(2, song.tracks.size)
        val bass = song.tracks[0]
        assertEquals(5, bass.strings.size)
        assertEquals(11, bass.notes.size)
        val drums = song.tracks[1]
        assertTrue(drums.isPercussion)
        assertEquals(TrackKind.DRUMS, drums.kind)
        assertEquals(24, drums.notes.size)
        assertTrue(drums.notes.all { it.pitch in 27..100 })
    }

    @Test
    fun gp3RealFileChords() {
        val song = GpParser.parse(resource("chords.gp3"))
        assertEquals(8, song.measures.size)
        assertEquals(78, song.tracks[0].notes.size)
    }

    @Test
    fun gp5RealFileEffects() {
        val song = GpParser.parse(resource("effects.gp5"))
        assertEquals(14, song.measures.size)
        assertEquals(47, song.tracks[0].notes.size)
    }

    @Test
    fun gp5RealFileDemo() {
        val song = GpParser.parse(resource("demo5.gp5"))
        assertEquals(165, song.tempoBpm)
        assertEquals(49, song.measures.size)
        assertEquals(5, song.tracks.size)
        assertEquals(listOf(761, 126, 9, 389, 638), song.tracks.map { it.notes.size })
    }

    @Test
    fun gp7Fixture() {
        val song = GpxParser.parse(resource("fixture.gp"))
        assertEquals("GPX Fixture", song.title)
        assertEquals(120, song.tempoBpm)
        assertEquals(2, song.measures.size)
        assertEquals(1, song.tracks.size)
        val track = song.tracks[0]
        assertEquals(listOf(64, 59, 55, 50, 45, 40), track.strings)
        assertEquals(6, track.notes.size)
        assertEquals(64, track.notes[0].pitch)
        assertEquals(0L, track.notes[0].startTick)
        assertEquals(48, track.notes[4].pitch)
        assertEquals(1920L, track.notes[4].startTick)
        assertEquals(960L, track.notes[4].durationTicks)
        assertEquals(40, track.notes[5].pitch)
    }

    @Test
    fun asciiTab() {
        val text = """
            e|---0---3---|
            B|---1-------|
            G|---0-------|
            D|---2-------|
            A|---3-------|
            E|------------|
        """.trimIndent()
        val song = AsciiTabParser.parse(text)
        assertEquals(1, song.tracks.size)
        val track = song.tracks[0]
        assertEquals(listOf(64, 59, 55, 50, 45, 40), track.strings)
        assertEquals(6, track.notes.size)
        assertTrue(track.notes.any { it.string == 1 && it.fret == 0 && it.pitch == 64 && it.startTick == 720L })
        assertTrue(track.notes.any { it.string == 1 && it.fret == 3 && it.pitch == 67 && it.startTick == 1680L })
        assertTrue(track.notes.any { it.string == 5 && it.fret == 3 && it.pitch == 48 })
    }

    @Test
    fun loaderDispatchAndSniff() {
        val gp5 = resource("fixture.gp5")
        assertEquals("TabPlayer Fixture", SongLoader.load("song.gp5", gp5).title)
        assertEquals("TabPlayer Fixture", SongLoader.load("noextension", gp5).title)
        assertEquals("GPX Fixture", SongLoader.load("song.gp", resource("fixture.gp")).title)
        try {
            SongLoader.load("song.ptb", byteArrayOf(1, 2, 3))
            fail("PTB should be rejected with a clear message")
        } catch (e: TabParseException) {
            assertTrue(e.message!!.contains("Power Tab"))
        }
    }

    @Test
    fun demoSong() {
        val song = DemoSong.create()
        assertEquals(8, song.measures.size)
        assertEquals(2, song.tracks.size)
        val guitar = song.tracks[0]
        assertEquals(30, guitar.notes.size)
        assertEquals(64, guitar.notes.first().pitch)
        assertEquals(1, guitar.notes.first().string)
        assertTrue(song.tracks[1].isPercussion)
    }

    @Test
    fun synthRendersAllSounds() {
        for (family in SoundFamily.values()) {
            for (variant in 0..4) {
                val buf = if (family == SoundFamily.DRUMS) {
                    Synth.renderNote(family, variant, 36, 100, 0.3)
                } else {
                    Synth.renderNote(family, variant, 60, 100, 0.4)
                }
                assertTrue("$family/$variant produced samples", buf.size > 1000)
                assertTrue("$family/$variant not silent", buf.maxOf { kotlin.math.abs(it) } > 0.05f)
                assertTrue("$family/$variant no NaN", buf.none { it.isNaN() })
            }
        }
        // Every GM drum pitch maps to something audible.
        for (pitch in 35..81) {
            val buf = Synth.renderDrum(0, pitch, 0.8f)
            assertTrue("drum $pitch renders", buf.isNotEmpty())
        }
    }

    @Test
    fun gp5EffectsFixture() {
        val song = GpParser.parse(resource("effects_fixture.gp5"))
        assertEquals("TabPlayer FX Fixture", song.title)
        assertEquals(3, song.measures.size)
        val notes = song.tracks[0].notes.sortedBy { it.startTick }
        assertEquals(10, notes.size)
        fun at(tick: Long) = notes.first { it.startTick == tick }
        // Measure 1
        at(0).let {
            assertEquals(2.0f, it.effects.bendSemitones, 0.01f)
            assertEquals(1, it.effects.bendType)
        }
        at(480).let { assertEquals(1, it.effects.slideType) }
        at(960).let {
            assertTrue(it.effects.hammerPull)
            assertTrue(it.effects.palmMute)
        }
        at(1440).let { assertEquals(1, it.effects.harmonicType) }
        // Measure 2
        at(1920).let {
            assertTrue(it.effects.letRing)
            assertTrue(it.effects.vibrato)
        }
        at(2400).let { assertTrue(it.dead) } // dead note (type 3)
        at(2880).let {
            assertEquals(4, it.effects.harmonicType) // pinch harmonic
            assertTrue(it.effects.staccato)
        }
        at(3360).let {
            assertEquals(1.0f, it.effects.bendSemitones, 0.01f)
            assertEquals(5, it.effects.bendType) // prebend + release
        }
        // Measure 3: GP5 stores slide flags as a bitmask, not the signed type
        at(3840).let { assertEquals(-2, it.effects.slideType) } // mask 0x20 = into from above
        at(4320).let { assertEquals(3, it.effects.slideType) } // mask 0x04 = out downwards
    }

    @Test
    fun asciiTechniques() {
        val tab = """
            PM|---------|
            e|---5h7---9---|
            B|-------------|
            G|-------------|
            D|-------------|
            A|-------------|
            E|-------------|

            e|/7---5/9---5b7r---3~---x---<12>---|
            B|-----------------------------------|
            G|-----------------------------------|
            D|-----------------------------------|
            A|-----------------------------------|
            E|-----------------------------------|
        """.trimIndent()
        val song = SongLoader.load("fx.txt", tab.toByteArray(Charsets.UTF_8))
        val notes = song.tracks[0].notes.sortedBy { it.startTick }
        assertEquals(10, notes.size)
        fun at(tick: Long) = notes.first { it.startTick == tick }
        // Block 1 (slot = 240 ticks): PM span covers cols 0..8
        at(720).let {
            assertEquals(5, it.fret)
            assertTrue(it.effects.palmMute)
            assertTrue(!it.effects.hammerPull)
        }
        at(1200).let {
            assertEquals(7, it.fret)
            assertTrue(it.effects.hammerPull)
            assertTrue(it.effects.palmMute)
        }
        at(2160).let {
            assertEquals(9, it.fret)
            assertTrue(!it.effects.palmMute) // col 9 is past the PM span
        }
        // Block 2 starts at (14 + 2) * 240 = 3840
        at(4080).let { assertEquals(-1, it.effects.slideType) } // /7 slide into from below
        at(5040).let { assertEquals(1, it.effects.slideType) } // 5/9 slide out
        at(6480).let { // 5b7r: bend up 2 semitones with release; target fret is not a note
            assertEquals(2.0f, it.effects.bendSemitones, 0.01f)
            assertEquals(2, it.effects.bendType)
        }
        at(8160).let { assertTrue(it.effects.vibrato) } // 3~
        at(9360).let { assertEquals(-1, it.fret) } // x dead note
        at(10320).let { // <12> natural harmonic
            assertEquals(12, it.fret)
            assertEquals(1, it.effects.harmonicType)
        }
    }
}
