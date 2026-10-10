package com.flowforge.core.models

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class ModelInstallerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ff-models").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/models/$name")!!.use { it.readBytes() }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sha256(f: File) = sha256(f.readBytes())

    private fun model(archive: String, sha: String = sha256(fixture(archive))) = SpeechModel(
        id = "tiny", label = "Tiny", summary = "", url = "fixture://$archive", sha256 = sha,
        downloadBytes = fixture(archive).size.toLong(), installedBytes = 0, arch = ModelArch.NEMO_TRANSDUCER,
        files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), license = "test",
    )

    private fun installer(archive: String, wrap: (InputStream) -> InputStream = { it }) =
        ModelInstaller(root) { _ -> ModelInstaller.Download(wrap(ByteArrayInputStream(fixture(archive))), fixture(archive).size.toLong()) }

    private val expected = mapOf(
        "encoder.int8.onnx" to "fff7042fc8a0e7f8608d1724c044c818e6c04f020aec677f615b9c8dff01ff8f",
        "decoder.int8.onnx" to "0e80ec409ce3af197b16ea5badcf584393c889a99bce110460e48963be8dbc2c",
        "tokens.txt" to "80af065c50f8fc846acab3097df1865e8c8c8fc8d30404ee97cca7c6b9c7d7b3",
        // Stored under a 153-character path, which needs GNU long-name / ustar prefix handling.
        "joiner.int8.onnx" to "a5a315cdb9071287c2134a7605e60347d84c52420ac935025d34324ae2c8a37b",
    )

    private fun assertInstalled(m: SpeechModel, ins: ModelInstaller) {
        assertTrue(ins.isInstalled(m))
        val dir = ins.modelDir(m)
        expected.forEach { (name, hash) -> assertEquals(name, hash, sha256(File(dir, name))) }
        // Only allow-listed files are written.
        assertEquals((expected.keys + ModelInstaller.MANIFEST).toSet(), dir.list()!!.toSet())
    }

    @Test
    fun `installs allow-listed files from a GNU tar bz2 archive`() {
        val m = model("tiny-gnu.tar.bz2")
        val ins = installer("tiny-gnu.tar.bz2")
        var lastProgress = 0L
        ins.install(m, progress = { read, _ -> lastProgress = read })
        assertInstalled(m, ins)
        assertTrue(lastProgress > 0)
    }

    @Test
    fun `installs from a PAX tar bz2 archive`() {
        val m = model("tiny-pax.tar.bz2")
        val ins = installer("tiny-pax.tar.bz2")
        ins.install(m)
        assertInstalled(m, ins)
    }

    @Test
    fun `wrong checksum installs nothing`() {
        val m = model("tiny-gnu.tar.bz2", sha = "0".repeat(64))
        val ins = installer("tiny-gnu.tar.bz2")
        try {
            ins.install(m)
            fail("expected checksum failure")
        } catch (e: ModelInstallException) {
            assertTrue(e.message!!.contains("checksum"))
        }
        assertFalse(ins.isInstalled(m))
        assertEquals(0, root.list()!!.size)
    }

    @Test
    fun `a failed update keeps the previously installed model`() {
        val good = model("tiny-gnu.tar.bz2")
        installer("tiny-gnu.tar.bz2").install(good)
        try {
            installer("tiny-gnu.tar.bz2").install(good.copy(sha256 = "1".repeat(64)))
            fail("expected failure")
        } catch (e: ModelInstallException) {
            // expected
        }
        assertTrue(ModelInstaller(root).isInstalled(good))
    }

    @Test
    fun `truncated download fails cleanly`() {
        val bytes = fixture("tiny-gnu.tar.bz2")
        val ins = ModelInstaller(root) { ModelInstaller.Download(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)), bytes.size.toLong()) }
        try {
            ins.install(model("tiny-gnu.tar.bz2"))
            fail("expected failure")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(0, root.list()!!.size)
    }

    @Test
    fun `missing files are reported`() {
        val m = model("tiny-gnu.tar.bz2").copy(files = listOf("encoder.int8.onnx", "does-not-exist.onnx"))
        try {
            installer("tiny-gnu.tar.bz2").install(m)
            fail("expected failure")
        } catch (e: ModelInstallException) {
            assertTrue(e.message!!.contains("does-not-exist.onnx"))
        }
    }

    @Test
    fun `cancellation stops and cleans up`() {
        // Report progress often by reading through a stream that returns small chunks.
        val slow: (InputStream) -> InputStream = { input ->
            object : FilterInputStream(input) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 4096))
            }
        }
        var calls = 0
        try {
            installer("tiny-gnu.tar.bz2", slow).install(model("tiny-gnu.tar.bz2"), cancelled = { ++calls > 0 })
            fail("expected cancellation")
        } catch (e: ModelInstallCancelled) {
            // expected
        }
        assertEquals(0, root.list()!!.size)
    }

    @Test
    fun `an archive larger than the catalog size is rejected before the checksum`() {
        val m = model("tiny-gnu.tar.bz2").copy(installedBytes = 1000)
        try {
            installer("tiny-gnu.tar.bz2").install(m)
            fail("expected size limit")
        } catch (e: ModelInstallException) {
            assertTrue(e.message!!.contains("larger than expected"))
        }
        assertEquals(0, root.list()!!.size)
    }

    @Test
    fun `leftovers of interrupted downloads are removed`() {
        val m = model("tiny-gnu.tar.bz2")
        val ins = installer("tiny-gnu.tar.bz2")
        ins.install(m)
        File(root, "other.partial").mkdirs()
        File(root, "other.partial/encoder.int8.onnx").writeText("x")
        ins.deleteLeftovers()
        assertEquals(setOf("tiny"), root.list()!!.toSet())
        assertTrue(ins.isInstalled(m))
    }

    @Test
    fun `deleting removes the model`() {
        val m = model("tiny-gnu.tar.bz2")
        val ins = installer("tiny-gnu.tar.bz2")
        ins.install(m)
        ins.delete(m)
        assertFalse(ins.isInstalled(m))
    }

    @Test
    fun `tampered installed file is detected`() {
        val m = model("tiny-gnu.tar.bz2")
        val ins = installer("tiny-gnu.tar.bz2")
        ins.install(m)
        File(ins.modelDir(m), "tokens.txt").appendText("x")
        assertFalse(ins.isInstalled(m))
    }

    @Test
    fun `http download follows https-only redirects`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex ->
            val body = "hello".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect-to-http") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/file")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
        try {
            // Plain http is only used here because the test server has no TLS; redirects must stay https.
            val ok = ModelInstaller.httpDownload("http://127.0.0.1:${server.address.port}/file")
            assertEquals("hello", ok.stream.use { String(it.readBytes()) })
            try {
                ModelInstaller.httpDownload("http://127.0.0.1:${server.address.port}/redirect-to-http")
                fail("expected refusal")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("non-HTTPS"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `catalog entries are consistent`() {
        for (m in ModelCatalog.all) {
            assertTrue(m.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
            assertEquals(64, m.sha256.length)
            assertTrue(m.files.contains("tokens.txt"))
            assertTrue(m.installedBytes > m.downloadBytes / 2)
        }
        assertEquals(ModelCatalog.PARAKEET_V2, ModelCatalog.byId("parakeet-tdt-0.6b-v2-int8"))
    }

    /**
     * Opt-in check against a real model archive (hundreds of MB), e.g.
     * `FLOWFORGE_REAL_ARCHIVE=/path/sherpa-onnx-moonshine-base-en-int8.tar.bz2 ./gradlew :core:test`.
     */
    @Test
    fun `real model archive installs and matches the catalog`() {
        val path = System.getenv("FLOWFORGE_REAL_ARCHIVE")
        assumeTrue("set FLOWFORGE_REAL_ARCHIVE to run", path != null)
        val file = File(path!!)
        val m = ModelCatalog.all.first { file.name == it.url.substringAfterLast('/') }
        val ins = ModelInstaller(root) { ModelInstaller.Download(file.inputStream(), file.length()) }
        val t0 = System.nanoTime()
        ins.install(m)
        println("Installed ${m.id} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(ins.isInstalled(m))
        assertEquals(m.installedBytes, m.files.sumOf { File(ins.modelDir(m), it).length() })
    }
}
