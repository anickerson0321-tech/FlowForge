package com.flowforge.core.models

import org.itadaki.bzip2.BZip2InputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)
class ModelInstallCancelled : IOException("Download cancelled")

/**
 * Downloads a [SpeechModel] archive and installs it in one streaming pass:
 * network → SHA-256 → bzip2 → tar → only the allow-listed files → temporary folder.
 * The folder replaces any previous install only after the archive hash matches the pinned value,
 * so a corrupted or tampered download is never used. Blocking; call off the main thread.
 */
class ModelInstaller(
    private val modelsRoot: File,
    private val open: (url: String) -> Download = ::httpDownload,
) {
    /** An opened byte stream plus its length (-1 if unknown). */
    class Download(val stream: InputStream, val length: Long)

    fun modelDir(model: SpeechModel) = File(modelsRoot, model.id)

    /** True when every expected file is present with the size recorded at install time. */
    fun isInstalled(model: SpeechModel): Boolean {
        val dir = modelDir(model)
        val manifest = File(dir, MANIFEST)
        if (!manifest.isFile) return false
        val sizes = manifest.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[0] to parts[1].toLongOrNull() else null
        }.toMap()
        return model.files.all { f -> val file = File(dir, f); file.isFile && sizes[f] == file.length() && file.length() > 0 }
    }

    /**
     * Removes leftovers of downloads that were interrupted (e.g. the app was killed). Call only
     * when no install is running.
     */
    fun deleteLeftovers() {
        modelsRoot.listFiles { f -> f.isDirectory && f.name.endsWith(TMP_SUFFIX) }?.forEach { it.deleteRecursively() }
    }

    fun delete(model: SpeechModel) {
        modelDir(model).deleteRecursively()
        File(modelsRoot, model.id + TMP_SUFFIX).deleteRecursively()
    }

    /**
     * @param progress called with (compressed bytes read, total compressed bytes or -1).
     * @param cancelled polled on every read; return true to abort. Must be cheap.
     */
    fun install(model: SpeechModel, progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }) {
        modelsRoot.mkdirs()
        val tmp = File(modelsRoot, model.id + TMP_SUFFIX)
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw ModelInstallException("Couldn't create ${tmp.path}")
        try {
            val download = open(model.url)
            val total = if (download.length > 0) download.length else model.downloadBytes
            val digest = MessageDigest.getInstance("SHA-256")
            progress(0, total)
            val counting = CountingDigestStream(download.stream, digest, cancelled) { read -> progress(read, total) }
            counting.use { raw ->
                val tar = TarReader(BZip2InputStream(BufferedInputStream(raw, 1 shl 16), false))
                val wanted = model.files.toSet()
                val written = HashSet<String>()
                // The checksum is only known at the end, so cap what a bad archive can write first.
                val limit = if (model.installedBytes > 0) model.installedBytes + model.installedBytes / 10 + 4096 else Long.MAX_VALUE
                var total = 0L
                while (true) {
                    val entry = tar.next() ?: break
                    if (!entry.isFile) continue
                    val name = entry.baseName
                    // Only allow-listed base names are written: no path from the archive is ever used.
                    if (name !in wanted || name in written) continue
                    if (entry.size > limit - total) throw ModelInstallException("The download is larger than expected, so it was discarded.")
                    total += entry.size
                    FileOutputStream(File(tmp, name)).use { out -> tar.copyTo(out) }
                    written += name
                }
                // Hash the whole archive, including anything after the tar end marker.
                val buf = ByteArray(1 shl 16)
                while (raw.read(buf) >= 0) { /* drain */ }
                progress(counting.count, total)
                val missing = wanted - written
                if (missing.isNotEmpty()) throw ModelInstallException("The download is missing ${missing.joinToString()}")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(model.sha256, ignoreCase = true)) {
                throw ModelInstallException("The download didn't match the expected checksum, so it was discarded.")
            }
            File(tmp, MANIFEST).writeText(
                model.files.joinToString("\n") { "$it\t${File(tmp, it).length()}" } + "\n",
            )
            val dir = modelDir(model)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw ModelInstallException("Couldn't move the model into place")
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            throw when (e) {
                is ModelInstallCancelled, is ModelInstallException -> e
                is IOException -> ModelInstallException("Download failed: ${e.message ?: e.javaClass.simpleName}", e)
                else -> ModelInstallException("Install failed: ${e.javaClass.simpleName}", e)
            }
        }
    }

    private class CountingDigestStream(
        input: InputStream,
        private val digest: MessageDigest,
        private val cancelled: () -> Boolean,
        private val onProgress: (Long) -> Unit,
    ) : FilterInputStream(input) {
        var count = 0L
            private set
        private var lastReport = 0L

        override fun read(): Int {
            if (cancelled()) throw ModelInstallCancelled()
            val b = super.read()
            if (b >= 0) {
                digest.update(b.toByte())
                advance(1)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (cancelled()) throw ModelInstallCancelled()
            val n = super.read(b, off, len)
            if (n > 0) {
                digest.update(b, off, n)
                advance(n)
            }
            return n
        }

        override fun skip(n: Long): Long {
            // Never skip: every byte must be hashed.
            var skipped = 0L
            val buf = ByteArray(8192)
            while (skipped < n) {
                val r = read(buf, 0, minOf(buf.size.toLong(), n - skipped).toInt())
                if (r < 0) break
                skipped += r
            }
            return skipped
        }

        private fun advance(n: Int) {
            count += n
            if (count - lastReport >= REPORT_EVERY) {
                lastReport = count
                onProgress(count)
            }
        }
    }

    companion object {
        const val MANIFEST = "installed.tsv"
        private const val TMP_SUFFIX = ".partial"
        private const val REPORT_EVERY = 256 * 1024L

        /** HTTPS download following redirects (GitHub release assets redirect to a CDN host). */
        fun httpDownload(url: String): Download {
            var current = URL(url)
            repeat(5) {
                val conn = current.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "FlowForge")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                    conn.disconnect()
                    val next = URL(current, location)
                    if (next.protocol != "https") throw IOException("Refusing non-HTTPS redirect")
                    current = next
                    return@repeat
                }
                if (code != 200) {
                    conn.disconnect()
                    throw IOException("Server answered HTTP $code")
                }
                return Download(conn.inputStream, conn.contentLengthLong)
            }
            throw IOException("Too many redirects")
        }
    }
}
