package com.maheshkatakam.melo.lyrics

import android.content.Context
import android.os.ParcelFileDescriptor
import com.maheshkatakam.melo.data.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/**
 * Pulls lyrics that are already stored inside the song file, without any internet:
 *  - MP3: ID3v2 USLT (plain) and SYLT (timed) frames, plus TXXX "LYRICS"
 *  - FLAC / OGG / OPUS: Vorbis comments LYRICS, UNSYNCEDLYRICS, SYNCEDLYRICS
 *  - M4A / MP4 / AAC: the ©lyr atom
 * and from a matching .lrc file next to the song.
 * Returns raw text (LRC when timed).
 */
class EmbeddedLyricsReader(private val context: Context) {

    fun readSidecar(song: Song): String? {
        val path = song.path ?: return null
        val base = path.substringBeforeLast('.')
        for (ext in listOf(".lrc", ".LRC", ".Lrc", ".txt")) {
            val file = File(base + ext)
            val text = runCatching { if (file.isFile && file.canRead()) file.readText() else null }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    fun readEmbedded(song: Song): String? = runCatching {
        val pfd = context.contentResolver.openFileDescriptor(song.uri, "r") ?: return@runCatching null
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            read(Source(stream.channel))
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun read(src: Source): String? {
        val head = src.read(0, 12)
        if (head.size < 12) return null
        return when {
            head.startsWith("ID3") -> {
                val (lyrics, end) = readId3(src, 0)
                lyrics ?: if (src.read(end, 4).startsWith("fLaC")) readFlac(src, end) else null
            }
            head.startsWith("fLaC") -> readFlac(src, 0)
            head.copyOfRange(4, 8).startsWith("ftyp") -> readMp4(src)
            head.startsWith("OggS") -> readOgg(src)
            else -> null
        }
    }

    // ---- ID3v2 ---------------------------------------------------------------------------

    private fun readId3(src: Source, offset: Long): Pair<String?, Long> {
        val h = src.read(offset, 10)
        if (h.size < 10 || !h.startsWith("ID3")) return null to offset
        val major = h[3].toInt()
        val flags = h[5].toInt() and 0xFF
        val size = syncsafe(h, 6)
        val end = offset + 10 + size + if (flags and 0x10 != 0) 10 else 0
        if (size <= 0 || size > MAX_TAG) return null to end
        var tag = src.read(offset + 10, size)
        if (major <= 3 && flags and 0x80 != 0) tag = unsynchronise(tag)

        var pos = 0
        if (flags and 0x40 != 0 && tag.size > 4) {
            pos = when (major) {
                3 -> 4 + be32(tag, 0)
                4 -> syncsafe(tag, 0)
                else -> 0
            }
        }

        val headerSize = if (major == 2) 6 else 10
        var synced: Lyrics? = null
        val plain = mutableListOf<String>()
        while (pos + headerSize <= tag.size) {
            if (tag[pos].toInt() == 0) break // padding
            val id: String
            val frameSize: Int
            var frameFlags = 0
            if (major == 2) {
                id = String(tag, pos, 3, Charsets.ISO_8859_1)
                frameSize = be24(tag, pos + 3)
            } else {
                id = String(tag, pos, 4, Charsets.ISO_8859_1)
                frameSize = if (major == 4) syncsafe(tag, pos + 4) else be32(tag, pos + 4)
                frameFlags = ((tag[pos + 8].toInt() and 0xFF) shl 8) or (tag[pos + 9].toInt() and 0xFF)
            }
            val start = pos + headerSize
            if (frameSize <= 0 || start + frameSize > tag.size) break
            pos = start + frameSize

            var data = tag.copyOfRange(start, start + frameSize)
            if (major == 4) {
                if (frameFlags and 0x000C != 0) continue // compressed or encrypted
                var skip = 0
                if (frameFlags and 0x0040 != 0) skip += 1 // grouping id
                if (frameFlags and 0x0001 != 0) skip += 4 // data length indicator
                if (skip > 0) data = if (skip < data.size) data.copyOfRange(skip, data.size) else continue
                if (frameFlags and 0x0002 != 0) data = unsynchronise(data)
            } else if (major == 3) {
                if (frameFlags and 0x00C0 != 0) continue // compressed or encrypted
                if (frameFlags and 0x0020 != 0) data = if (data.size > 1) data.copyOfRange(1, data.size) else continue
            }

            when (id) {
                "USLT", "ULT" -> parseUslt(data)?.let { plain += it }
                "SYLT", "SLT" -> if (synced == null) synced = parseSylt(data)
                "TXXX", "TXX" -> parseTxxxLyrics(data)?.let { plain += it }
            }
        }
        val result = synced?.takeIf { it.lines.size > 2 }?.let { LrcParser.serialize(it) }
            ?: plain.maxByOrNull { it.length }
        return result to end
    }

    private fun parseUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val enc = data[0].toInt()
        val descEnd = terminatorEnd(data, 4, enc)
        if (descEnd >= data.size) return null
        return decode(data, descEnd, data.size, enc).trim('\u0000', ' ', '\n', '\r').ifBlank { null }
    }

    private fun parseSylt(data: ByteArray): Lyrics? {
        if (data.size < 7) return null
        val enc = data[0].toInt()
        val timestampFormat = data[4].toInt()
        if (timestampFormat != 2) return null // only milliseconds are reliable without the audio frame rate
        var p = terminatorEnd(data, 6, enc)
        val lines = mutableListOf<LyricLine>()
        while (p < data.size) {
            val textEnd = findTerminator(data, p, enc)
            if (textEnd < 0 || textEnd + termLen(enc) + 4 > data.size) break
            val text = decode(data, p, textEnd, enc).trim('\n', '\r', ' ')
            p = textEnd + termLen(enc)
            val ts = be32(data, p).toLong()
            p += 4
            lines += LyricLine(ts, text)
        }
        return Lyrics(lines.sortedBy { it.timeMs }).takeIf { !it.isBlank() }
    }

    private fun parseTxxxLyrics(data: ByteArray): String? {
        if (data.size < 3) return null
        val enc = data[0].toInt()
        val descEnd = findTerminator(data, 1, enc)
        if (descEnd < 0) return null
        val desc = decode(data, 1, descEnd, enc).uppercase()
        if (desc !in setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")) return null
        return decode(data, descEnd + termLen(enc), data.size, enc).trim('\u0000', ' ', '\n').ifBlank { null }
    }

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun termLen(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        return String(b, from, to - from, charset(enc)).replace("\u0000", "")
    }

    /** Index of the terminator starting at [from], or -1. */
    private fun findTerminator(b: ByteArray, from: Int, enc: Int): Int {
        if (termLen(enc) == 1) {
            for (i in from until b.size) if (b[i].toInt() == 0) return i
            return -1
        }
        var i = from
        while (i + 1 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i
            i += 2
        }
        return -1
    }

    private fun terminatorEnd(b: ByteArray, from: Int, enc: Int): Int {
        val t = findTerminator(b, from, enc)
        return if (t < 0) b.size else t + termLen(enc)
    }

    private fun unsynchronise(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---- FLAC ----------------------------------------------------------------------------

    private fun readFlac(src: Source, offset: Long): String? {
        var pos = offset + 4
        var guard = 0
        while (pos + 4 <= src.size && guard++ < 128) {
            val h = src.read(pos, 4)
            if (h.size < 4) return null
            val last = (h[0].toInt() and 0x80) != 0
            val type = h[0].toInt() and 0x7F
            val len = be24(h, 1)
            if (type == 4) {
                if (len > MAX_TAG) return null
                return parseVorbisComments(src.read(pos + 4, len), 0)
            }
            pos += 4 + len
            if (last) break
        }
        return null
    }

    // ---- OGG (Vorbis / Opus) ---------------------------------------------------------------

    private fun readOgg(src: Source): String? {
        var pos = 0L
        val packets = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()
        var pages = 0
        var total = 0L
        outer@ while (pos + 27 <= src.size && pages < 512) {
            val h = src.read(pos, 27)
            if (h.size < 27 || !h.startsWith("OggS")) break
            val segments = h[26].toInt() and 0xFF
            val table = src.read(pos + 27, segments)
            if (table.size < segments) break
            val payloadLen = table.sumOf { it.toInt() and 0xFF }
            val payload = src.read(pos + 27 + segments, payloadLen)
            total += payloadLen
            if (total > MAX_TAG) break
            var p = 0
            for (lace in table) {
                val l = lace.toInt() and 0xFF
                if (l > 0 && p + l <= payload.size) current.write(payload, p, l)
                p += l
                if (l < 255) {
                    packets += current.toByteArray()
                    current = ByteArrayOutputStream()
                    if (packets.size >= 2) break@outer
                }
            }
            pos += 27 + segments + payloadLen
            pages++
        }
        val comment = packets.getOrNull(1) ?: return null
        return when {
            comment.size > 7 && comment[0].toInt() == 3 && String(comment, 1, 6, Charsets.ISO_8859_1) == "vorbis" ->
                parseVorbisComments(comment, 7)
            comment.startsWith("OpusTags") -> parseVorbisComments(comment, 8)
            else -> null
        }
    }

    private fun parseVorbisComments(b: ByteArray, start: Int): String? {
        var p = start
        if (p + 4 > b.size) return null
        val vendorLen = le32(b, p)
        p += 4 + vendorLen
        if (vendorLen < 0 || p + 4 > b.size) return null
        val count = le32(b, p)
        p += 4
        val found = mutableMapOf<String, String>()
        var i = 0
        while (i < count && p + 4 <= b.size) {
            val len = le32(b, p)
            p += 4
            if (len < 0 || p + len > b.size) break
            val entry = String(b, p, len, Charsets.UTF_8)
            p += len
            i++
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq).uppercase()
            if (key in LYRIC_KEYS) found[key] = entry.substring(eq + 1)
        }
        if (found.isEmpty()) return null
        return found.values.firstOrNull { LrcParser.looksSynced(it) } ?: found.values.maxByOrNull { it.length }
    }

    // ---- MP4 / M4A -------------------------------------------------------------------------

    private class Atom(val dataStart: Long, val end: Long)

    private fun readMp4(src: Source): String? {
        val moov = findAtom(src, 0, src.size, "moov".toByteArray(Charsets.ISO_8859_1)) ?: return null
        val udta = findAtom(src, moov.dataStart, moov.end, "udta".toByteArray(Charsets.ISO_8859_1)) ?: return null
        val meta = findAtom(src, udta.dataStart, udta.end, "meta".toByteArray(Charsets.ISO_8859_1)) ?: return null
        // "meta" is normally a full box (4 bytes of version/flags before its children).
        val childStart = if (src.read(meta.dataStart + 4, 4).startsWith("hdlr")) meta.dataStart + 4 else meta.dataStart
        val ilst = findAtom(src, childStart, meta.end, "ilst".toByteArray(Charsets.ISO_8859_1)) ?: return null
        val lyrType = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val lyr = findAtom(src, ilst.dataStart, ilst.end, lyrType) ?: return null
        val data = findAtom(src, lyr.dataStart, lyr.end, "data".toByteArray(Charsets.ISO_8859_1)) ?: return null
        val len = (data.end - data.dataStart - 8).toInt()
        if (len <= 0 || len > MAX_TAG) return null
        return String(src.read(data.dataStart + 8, len), Charsets.UTF_8)
    }

    private fun findAtom(src: Source, from: Long, to: Long, type: ByteArray): Atom? {
        var pos = from
        var guard = 0
        while (pos + 8 <= to && guard++ < 4096) {
            val h = src.read(pos, 16)
            if (h.size < 8) return null
            var size = be32(h, 0).toLong() and 0xFFFFFFFFL
            var header = 8
            if (size == 1L && h.size >= 16) {
                size = ByteBuffer.wrap(h, 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < header) return null
            if (h.copyOfRange(4, 8).contentEquals(type)) return Atom(pos + header, minOf(pos + size, to))
            pos += size
        }
        return null
    }

    // ---- helpers -----------------------------------------------------------------------------

    private class Source(private val channel: FileChannel) {
        val size: Long = channel.size()

        fun read(position: Long, length: Int): ByteArray {
            if (length <= 0 || position < 0 || position >= size) return ByteArray(0)
            val n = minOf(length.toLong(), size - position).toInt()
            val buf = ByteBuffer.allocate(n)
            var p = position
            while (buf.hasRemaining()) {
                val r = channel.read(buf, p)
                if (r <= 0) break
                p += r
            }
            return if (buf.position() == n) buf.array() else buf.array().copyOf(buf.position())
        }
    }

    private fun ByteArray.startsWith(prefix: String): Boolean {
        if (size < prefix.length) return false
        for (i in prefix.indices) if (this[i] != prefix[i].code.toByte()) return false
        return true
    }

    private fun syncsafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun be24(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    companion object {
        private const val MAX_TAG = 48 * 1024 * 1024
        private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS", "SYNCED LYRICS", "LYRIC")
    }
}
