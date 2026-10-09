package com.flowforge.core.cloud

import com.flowforge.core.audio.Pcm
import com.flowforge.core.cleanup.CleanupMode
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Exercises the HTTP client against a local server that implements the documented
 * OpenAI-compatible request/response shapes. This verifies FlowForge's protocol handling
 * (multipart upload, auth header, JSON parsing, error mapping); it is NOT evidence that any
 * real provider transcribes speech correctly.
 */
class CloudClientTest {
    private lateinit var server: HttpServer
    private var lastAuth: String? = null
    private var lastContentType: String? = null
    private var lastBody: ByteArray = ByteArray(0)
    private var status = 200
    private var reply = """{"text":" hello from the server "}"""

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { ex ->
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            lastContentType = ex.requestHeaders.getFirst("Content-Type")
            lastBody = ex.requestBody.readBytes()
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client() = OpenAiCompatibleClient(
        CloudConfig("http://127.0.0.1:${server.address.port}/v1", "sk-test-123", "whisper-1", "gpt-test"),
    )

    @Test
    fun `transcription uploads wav as multipart with bearer auth`() {
        val wav = Pcm.toWav(ByteArray(3200))
        val text = client().transcribe(wav, language = "en-US", vocabularyPrompt = "Siobhan, Kubernetes")
        assertEquals("hello from the server", text)
        assertEquals("Bearer sk-test-123", lastAuth)
        assertTrue(lastContentType!!.startsWith("multipart/form-data; boundary=FlowForge-"))
        val body = String(lastBody, Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nen"))
        assertTrue(body.contains("name=\"prompt\"\r\n\r\nSiobhan, Kubernetes"))
        assertTrue(body.contains("filename=\"dictation.wav\""))
        assertTrue(body.contains("RIFF"))
    }

    @Test
    fun `chat request and response`() {
        reply = """{"choices":[{"message":{"role":"assistant","content":"Fixed text."}}]}"""
        assertEquals("Fixed text.", client().chat("system prompt", "fixed text"))
        val json = JSONObject(String(lastBody))
        assertEquals("gpt-test", json.getString("model"))
        assertEquals("user", json.getJSONArray("messages").getJSONObject(1).getString("role"))
    }

    @Test
    fun `http errors become readable and classified`() {
        status = 401
        reply = """{"error":{"message":"Incorrect API key provided"}}"""
        try {
            client().transcribe(Pcm.toWav(ByteArray(10)))
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertEquals(401, e.status)
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("rejected the API key"))
            assertFalse("key must not leak", e.message!!.contains("sk-test"))
        }
        status = 503
        reply = "oops"
        try {
            client().chat("s", "u")
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `unreachable server is a retryable error`() {
        val port = server.address.port
        server.stop(0)
        try {
            OpenAiCompatibleClient(CloudConfig("http://127.0.0.1:$port/v1", "k"), connectTimeoutMs = 2000).chat("s", "u")
            fail("expected CloudException")
        } catch (e: CloudException) {
            assertTrue(e.retryable)
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `insecure remote endpoints are rejected`() {
        OpenAiCompatibleClient(CloudConfig("http://api.example.com/v1", "k"))
    }

    @Test
    fun `config never prints the key`() {
        assertFalse(CloudConfig("https://a.example/v1", "sk-secret").toString().contains("sk-secret"))
    }

    // --- AI cleanup validation ----------------------------------------------------------------

    @Test
    fun `ai output that keeps protected content is accepted`() {
        val v = AiCleanup.validate(
            "Hey Jason, I checked the bathrooms, and we should do it Friday, not Thursday.",
            "Hey Jason, I checked the bathrooms and we should do it on Friday, not Thursday.",
        )
        assertTrue(v.reasons.toString(), v.accepted)
    }

    @Test
    fun `ai output that changes numbers dates or negations is rejected`() {
        val src = "Don't send the 3 invoices until Friday."
        assertFalse(AiCleanup.validate(src, "Send the 3 invoices until Friday.").accepted)
        assertFalse(AiCleanup.validate(src, "Don't send the 4 invoices until Friday.").accepted)
        assertFalse(AiCleanup.validate(src, "Don't send the 3 invoices until Monday.").accepted)
        assertTrue(AiCleanup.validate(src, "Do not send the three invoices until Friday.").accepted)
    }

    @Test
    fun `ai output that drops a name or adds commentary is rejected`() {
        assertFalse(AiCleanup.validate("Ask Siobhan about the budget.", "Ask her about the budget.").accepted)
        assertFalse(AiCleanup.validate("Ask Siobhan.", "Here is the edited text: Ask Siobhan.").accepted)
        assertFalse(AiCleanup.validate("Ask Siobhan about the budget today please.", "Ask.").accepted)
    }

    @Test
    fun `wrapping quotes are stripped`() {
        assertEquals("Hi there.", AiCleanup.stripWrapping("\"Hi there.\""))
        assertEquals("Hi there.", AiCleanup.stripWrapping("```\nHi there.\n```"))
    }

    @Test
    fun `prompt includes vocabulary and safety rules`() {
        val p = AiCleanup.systemPrompt(CleanupMode.PROFESSIONAL, listOf("Siobhan"))
        assertTrue(p.contains("Siobhan") && p.contains("negations"))
    }

    // --- Audio helpers ----------------------------------------------------------------------------

    @Test
    fun `wav header is valid`() {
        val pcm = ByteArray(32000)
        val wav = Pcm.toWav(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1000L, Pcm.durationMillis(pcm.size))
    }

    @Test
    fun `rms levels`() {
        assertEquals(-90f, Pcm.rmsDbfs(ByteArray(100)), 0.01f)
        val loud = ByteArray(200) { if (it % 2 == 0) 0xFF.toByte() else 0x7F }
        assertTrue(Pcm.rmsDbfs(loud) > -1f)
        assertEquals(0f, Pcm.meterLevel(-90f), 0f)
        assertEquals(1f, Pcm.meterLevel(0f), 0f)
    }
}
