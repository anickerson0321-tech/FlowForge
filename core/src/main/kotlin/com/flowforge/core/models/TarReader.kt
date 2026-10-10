package com.flowforge.core.models

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal streaming reader for POSIX ustar / GNU tar archives (regular files only). Supports GNU
 * long names ('L') and PAX 'path' records, validates header checksums, and never seeks, so it
 * works on a decompressing network stream.
 */
class TarReader(private val input: InputStream) {

    data class Entry(val name: String, val size: Long, val type: Char) {
        val isFile: Boolean get() = type == '0' || type == '\u0000' || type == '7'
        val baseName: String get() = name.trimEnd('/').substringAfterLast('/')
    }

    private val header = ByteArray(BLOCK)
    private var remaining = 0L
    private var padding = 0L

    /** Advances to the next entry (skipping any unread data of the current one), or null at the end. */
    fun next(): Entry? {
        skipEntry()
        var longName: String? = null
        while (true) {
            if (!readBlock(header)) return null
            if (header.all { it == 0.toByte() }) return null // end-of-archive marker
            verifyChecksum()
            val type = (header[156].toInt() and 0xff).toChar()
            val size = parseSize(header, 124, 12)
            setData(size)
            when (type) {
                'L' -> { // GNU long name: the data is the name of the next entry
                    longName = String(readData(size), Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'x', 'g' -> { // PAX extended header
                    val path = parsePaxPath(String(readData(size), Charsets.UTF_8))
                    if (type == 'x' && path != null) longName = path
                    continue
                }
            }
            val name = longName ?: ustarName()
            return Entry(name, size, type)
        }
    }

    /** Copies the current entry's data to [out]. */
    fun copyTo(out: OutputStream, onBytes: (Int) -> Unit = {}) {
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("tar entry truncated")
            out.write(buf, 0, n)
            remaining -= n
            onBytes(n)
        }
        skipPadding()
    }

    private fun skipEntry() {
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) throw EOFException("tar entry truncated")
                remaining--
            } else {
                remaining -= skipped
            }
        }
        skipPadding()
    }

    private fun setData(size: Long) {
        remaining = size
        padding = (BLOCK - size % BLOCK) % BLOCK
    }

    private fun skipPadding() {
        var p = padding
        while (p > 0) {
            if (input.read() < 0) throw EOFException("tar padding truncated")
            p--
        }
        padding = 0
    }

    private fun readData(size: Long): ByteArray {
        if (size > MAX_META) throw IOException("tar metadata entry too large")
        val data = ByteArray(size.toInt())
        readFully(data)
        remaining = 0
        skipPadding()
        return data
    }

    private fun readBlock(b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("tar header truncated")
            }
            off += n
        }
        return true
    }

    private fun readFully(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw EOFException("tar data truncated")
            off += n
        }
    }

    private fun ustarName(): String {
        val name = cString(header, 0, 100)
        val magic = String(header, 257, 5, Charsets.US_ASCII)
        val prefix = if (magic == "ustar") cString(header, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun verifyChecksum() {
        val stored = parseOctal(header, 148, 8)
        var sum = 0L
        for (i in 0 until BLOCK) sum += if (i in 148 until 156) 32 else (header[i].toInt() and 0xff)
        if (stored != sum) throw IOException("tar header checksum mismatch")
    }

    companion object {
        const val BLOCK = 512
        private const val MAX_META = 1L shl 20

        private fun cString(b: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && b[end] != 0.toByte()) end++
            return String(b, off, end - off, Charsets.UTF_8)
        }

        private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
            var v = 0L
            for (i in off until off + len) {
                val c = b[i].toInt().toChar()
                if (c == ' ' || c == '\u0000') {
                    if (v != 0L) break else continue
                }
                if (c !in '0'..'7') throw IOException("bad octal field in tar header")
                v = v * 8 + (c - '0')
            }
            return v
        }

        /** Size field: octal, or GNU base-256 when the high bit of the first byte is set. */
        private fun parseSize(b: ByteArray, off: Int, len: Int): Long {
            if (b[off].toInt() and 0x80 != 0) {
                var v = (b[off].toInt() and 0x7f).toLong()
                for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
                return v
            }
            return parseOctal(b, off, len)
        }

        /** PAX records are "<len> key=value\n". */
        fun parsePaxPath(records: String): String? =
            records.lineSequence().mapNotNull { line ->
                val rec = line.substringAfter(' ', "")
                if (rec.startsWith("path=")) rec.removePrefix("path=") else null
            }.firstOrNull()
    }
}
