import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/*
 * Build-time provisioning of the offline neural voice assets.
 *
 * Everything is downloaded once, verified against pinned SHA-256 digests and
 * bundled into the APK. The app itself has no INTERNET permission and never
 * downloads anything.
 *
 *  - Kokoro-82M v1.0, 8-bit quantized ONNX (Apache-2.0) — onnx-community export,
 *    redistributed on npm as `kokoro-q8-shards` (byte-identical, 6 shards).
 *  - Kokoro voice style tensors (Apache-2.0) — from npm `kokoro-js` 1.2.1.
 *  - misaki US English pronunciation lexicon (Apache-2.0) — from PyPI `misaki` 0.9.4.
 */

data class Pinned(val url: String, val sha256: String, val fileName: String)

val pinned = listOf(
    Pinned(
        "https://registry.npmjs.org/kokoro-q8-shards/-/kokoro-q8-shards-1.0.0.tgz",
        "94b79d3f877a5f6d1f0b8b081fd2b7d2f4f33cefb1210400d2cf67431f8d57b8", "kokoro-q8-shards-1.0.0.tgz",
    ),
    Pinned(
        "https://registry.npmjs.org/kokoro-js/-/kokoro-js-1.2.1.tgz",
        "1f0d0383ef0349af0bd9440d81dc12abbea3a5392eb645283eda876f537f5860", "kokoro-js-1.2.1.tgz",
    ),
    Pinned(
        "https://files.pythonhosted.org/packages/82/ec/0ee4110ddb54278b8f21c40a140370ae8f687036c4edf578316602697c56/misaki-0.9.4-py3-none-any.whl",
        "90e2eeb169786c014c429e5058d2ea6bcd02d651f2a24450ba6c9ffc0f8da15a", "misaki-0.9.4-py3-none-any.whl",
    ),
)
val modelSha256 = "fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478"

// American (a*) and British (b*) English voices.
val bundledVoices = listOf(
    "af_heart", "af_alloy", "af_aoede", "af_bella", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river",
    "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx", "am_puck",
    "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
)

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().buffered().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Minimal tar reader (ustar, regular files only) for npm tarballs. */
fun readTarGz(f: File, wanted: (String) -> Boolean): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    GZIPInputStream(f.inputStream().buffered()).use { gz ->
        val header = ByteArray(512)
        while (true) {
            var read = 0
            while (read < 512) { val n = gz.read(header, read, 512 - read); if (n < 0) break; read += n }
            if (read < 512 || header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100).trimEnd('\u0000')
            val prefix = String(header, 345, 155).trimEnd('\u0000')
            val size = String(header, 124, 12).trim('\u0000', ' ').toLong(8)
            val type = header[156].toInt().toChar()
            val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val padded = (size + 511) / 512 * 512
            if ((type == '0' || type == '\u0000') && wanted(full)) {
                val data = ByteArray(size.toInt()); var off = 0
                while (off < size) { val n = gz.read(data, off, (size - off).toInt()); require(n > 0); off += n }
                out[full] = data
                var skip = padded - size
                while (skip > 0) { val n = gz.skip(skip); require(n > 0); skip -= n }
            } else {
                var skip = padded
                while (skip > 0) { val n = gz.skip(skip); if (n <= 0) { gz.read(); skip-- } else skip -= n }
            }
        }
    }
    return out
}

val cacheDir = File(gradle.gradleUserHomeDir, "privatevoice-assets")

val downloadVoiceSources by tasks.registering {
    group = "voice assets"
    description = "Downloads pinned voice model archives (build time only) and verifies SHA-256."
    outputs.files(pinned.map { File(cacheDir, it.fileName) })
    doLast {
        cacheDir.mkdirs()
        for (p in pinned) {
            val f = File(cacheDir, p.fileName)
            if (f.exists() && sha256(f) == p.sha256) continue
            logger.lifecycle("Downloading ${p.url}")
            val tmp = File(cacheDir, p.fileName + ".part")
            var lastError: Exception? = null
            for (attempt in 1..4) {
                try {
                    URI(p.url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    lastError = null; break
                } catch (e: Exception) { lastError = e; Thread.sleep(2000L shl attempt) }
            }
            lastError?.let { throw GradleException("Failed to download ${p.url}", it) }
            val actual = sha256(tmp)
            if (actual != p.sha256) { tmp.delete(); throw GradleException("SHA-256 mismatch for ${p.fileName}: $actual") }
            tmp.renameTo(f)
        }
    }
}

tasks.register("prepareVoiceAssets") {
    group = "voice assets"
    description = "Extracts the Kokoro model, voices and lexicon into generated APK assets."
    dependsOn(downloadVoiceSources)
    val out = layout.buildDirectory.dir("generated/voiceAssets")
    inputs.files(pinned.map { File(cacheDir, it.fileName) })
    inputs.property("voices", bundledVoices)
    inputs.property("format", 3)
    outputs.dir(out)
    doLast {
        val root = out.get().asFile
        root.deleteRecursively()
        val kokoro = File(root, "kokoro").apply { mkdirs() }

        // 1. Model: join shards, verify.
        val shards = readTarGz(File(cacheDir, pinned[0].fileName)) { it.matches(Regex("package/kokoro-q8\\.part\\d\\.bin")) }
        val model = File(kokoro, "kokoro-v1.0-q8.onnx")
        model.outputStream().buffered().use { os ->
            shards.keys.sortedBy { it.substringAfter(".part").substringBefore(".bin").toInt() }.forEach { os.write(shards.getValue(it)) }
        }
        val modelSha = sha256(model)
        if (modelSha != modelSha256) throw GradleException("Kokoro model SHA-256 mismatch: $modelSha")

        // 2. Voices.
        val voiceDir = File(kokoro, "voices").apply { mkdirs() }
        val voices = readTarGz(File(cacheDir, pinned[1].fileName)) { name ->
            bundledVoices.any { name == "package/voices/$it.bin" }
        }
        check(voices.size == bundledVoices.size) { "Missing voices: ${bundledVoices.size - voices.size}" }
        voices.forEach { (name, bytes) ->
            check(bytes.size == 510 * 256 * 4) { "Unexpected voice tensor size for $name" }
            File(voiceDir, name.substringAfterLast('/')).writeBytes(bytes)
        }

        // 3. Lexicon: misaki us_gold + us_silver -> compact TSV.
        //    word \t phonemes            (plain entry)
        //    word \t TAG=ph|TAG=ph|...   (part-of-speech entry; empty value = spell out)
        //    Gold entries are written first; the loader keeps the first occurrence.
        val slurper = groovy.json.JsonSlurper()
        val lex = File(kokoro, "lexicon_us.tsv")
        ZipFile(File(cacheDir, pinned[2].fileName)).use { zf ->
            lex.bufferedWriter().use { w ->
                for (part in listOf("gold", "silver")) {
                    val entry = zf.getEntry("misaki/data/us_$part.json")
                    @Suppress("UNCHECKED_CAST")
                    val map = zf.getInputStream(entry).reader(Charsets.UTF_8).use { slurper.parse(it) } as Map<String, Any?>
                    for ((word, value) in map) {
                        if (word.contains('\t') || word.contains('\n')) continue
                        val rendered = when (value) {
                            null -> ""
                            is String -> value
                            is Map<*, *> -> value.entries.joinToString("|") { (k, v) -> "$k=${v ?: ""}" }
                            else -> continue
                        }
                        w.write(word); w.write("\t"); w.write(rendered); w.write("\n")
                    }
                }
            }
        }
        File(kokoro, "MANIFEST.txt").writeText(
            "kokoro-v1.0-q8.onnx sha256=$modelSha\n" +
                "voices=${bundledVoices.joinToString(",")}\n" +
                "lexicon=misaki-0.9.4 us_gold+us_silver\n",
        )
    }
}
