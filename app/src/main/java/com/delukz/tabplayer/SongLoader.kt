package com.delukz.tabplayer

/** Dispatches to the right parser based on file name and content sniffing. */
object SongLoader {

    fun load(fileName: String, data: ByteArray): SongData {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "ptb" -> throw TabParseException(
                "Power Tab (.ptb) files aren't supported yet. Export the song as Guitar Pro (.gp5/.gp) or text tab and open that instead."
            )
            ext in setOf("gp3", "gp4", "gp5") -> GpParser.parse(data)
            ext in setOf("gp", "gpx") -> {
                if (isZip(data)) GpxParser.parse(data)
                else throw TabParseException(
                    "This .gpx uses the Guitar Pro 6 container, which isn't supported. A .gp file saved from Guitar Pro 7 will open fine."
                )
            }
            ext in setOf("txt", "text", "tab", "crdownload") ->
                AsciiTabParser.parse(String(data, Charsets.UTF_8))
            else -> sniff(data)
        }
    }

    private fun sniff(data: ByteArray): SongData {
        val head = String(data, 0, minOf(data.size, 64), Charsets.ISO_8859_1)
        return when {
            head.contains("FICHIER GUITAR PRO") || head.contains("CLIPBOARD") -> GpParser.parse(data)
            isZip(data) -> GpxParser.parse(data)
            isMostlyText(data) -> AsciiTabParser.parse(String(data, Charsets.UTF_8))
            else -> throw TabParseException("Couldn't tell what kind of tab file this is")
        }
    }

    private fun isZip(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte() &&
            data[2] == 0x03.toByte() && data[3] == 0x04.toByte()

    private fun isMostlyText(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val sample = data.copyOfRange(0, minOf(data.size, 4096))
        val printable = sample.count { b ->
            val c = b.toInt() and 0xFF
            c == 9 || c == 10 || c == 13 || c in 32..126
        }
        return printable.toDouble() / sample.size > 0.95
    }
}
