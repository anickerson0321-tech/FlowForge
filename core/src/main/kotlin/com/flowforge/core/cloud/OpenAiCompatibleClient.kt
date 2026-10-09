package com.flowforge.core.cloud

import com.flowforge.core.privacy.ConsentPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

class CloudException(
    val status: Int,
    message: String,
    val retryable: Boolean,
) : IOException(message)

data class CloudConfig(
    /** Base URL such as https://api.openai.com/v1 (any OpenAI-compatible server works). */
    val baseUrl: String,
    /** Never logged, never persisted unencrypted. */
    val apiKey: String,
    val transcriptionModel: String = "whisper-1",
    val chatModel: String = "gpt-4o-mini",
) {
    val isUsable: Boolean get() = ConsentPolicy.hostOf(baseUrl) != null && baseUrl.isNotBlank()
    val host: String? get() = ConsentPolicy.hostOf(baseUrl)

    override fun toString(): String = "CloudConfig(baseUrl=$baseUrl, apiKey=<redacted>, " +
        "transcriptionModel=$transcriptionModel, chatModel=$chatModel)"
}

/**
 * Minimal client for the OpenAI-compatible endpoints `POST /audio/transcriptions` and
 * `POST /chat/completions`. Blocking; call from a background thread.
 */
class OpenAiCompatibleClient(
    private val config: CloudConfig,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {
    init {
        require(config.isUsable) { "Cloud endpoint must be https (or a local development host)." }
    }

    fun transcribe(wav: ByteArray, language: String? = null, vocabularyPrompt: String? = null): String {
        val boundary = "FlowForge-" + UUID.randomUUID().toString()
        val fields = LinkedHashMap<String, String>()
        fields["model"] = config.transcriptionModel
        fields["response_format"] = "json"
        if (!language.isNullOrBlank()) fields["language"] = language.substringBefore('-').lowercase()
        if (!vocabularyPrompt.isNullOrBlank()) fields["prompt"] = vocabularyPrompt
        val body = Multipart.build(boundary, fields, "file", "dictation.wav", "audio/wav", wav)
        val response = post("audio/transcriptions", "multipart/form-data; boundary=$boundary", body)
        return parseTranscription(response)
    }

    fun chat(system: String, user: String): String {
        val payload = JSONObject()
            .put("model", config.chatModel)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        val response = post("chat/completions", "application/json", payload.toString().toByteArray(Charsets.UTF_8))
        return parseChat(response)
    }

    private fun post(path: String, contentType: String, body: ByteArray): String {
        val url = URL(config.baseUrl.trimEnd('/') + "/" + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false // never forward the key to another host
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("Accept", "application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s -> s.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (status !in 200..299) {
                throw CloudException(status, describeError(status, text), retryable = status == 429 || status >= 500)
            }
            return text
        } catch (e: SocketTimeoutException) {
            throw CloudException(-1, "The cloud provider did not respond in time.", retryable = true)
        } catch (e: CloudException) {
            throw e
        } catch (e: IOException) {
            throw CloudException(-1, "Could not reach the cloud provider (${e.javaClass.simpleName}).", retryable = true)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parseTranscription(json: String): String {
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw CloudException(200, "The provider returned an unreadable transcription.", retryable = false)
            }
            if (!obj.has("text")) throw CloudException(200, "The provider response had no text.", retryable = false)
            return obj.getString("text").trim()
        }

        fun parseChat(json: String): String {
            try {
                val choices = JSONObject(json).getJSONArray("choices")
                return choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
            } catch (e: Exception) {
                throw CloudException(200, "The provider returned an unreadable reply.", retryable = false)
            }
        }

        fun describeError(status: Int, body: String): String {
            val detail = try {
                JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
            } catch (e: Exception) {
                ""
            }.take(200)
            val base = when (status) {
                401, 403 -> "The cloud provider rejected the API key"
                404 -> "The cloud endpoint or model was not found"
                413 -> "The recording is too large for the provider"
                429 -> "The cloud provider is rate limiting or out of quota"
                in 500..599 -> "The cloud provider had a server error"
                else -> "The cloud provider returned HTTP $status"
            }
            return if (detail.isBlank()) "$base." else "$base: $detail"
        }
    }
}

internal object Multipart {
    fun build(
        boundary: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        fileType: String,
        fileBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(fileBytes.size + 1024)
        fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$k\"")
            line("")
            line(v)
        }
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"")
        line("Content-Type: $fileType")
        line("")
        out.write(fileBytes)
        line("")
        line("--$boundary--")
        return out.toByteArray()
    }
}
